(ns wagoe.user.shell.service
  "FC/IS Shell Layer - User module service coordination.
   
   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   The shell coordinates between:
   - Pure functional CORE (wagoe.user.core.*)
   - I/O SHELL persistence (wagoe.user.shell.persistence)
   
   Shell responsibilities:
   1. Coordinate calls between core and persistence layers
   2. Manage external dependencies (time, UUIDs, logging)
   3. Handle all side effects and I/O operations
   4. Orchestrate transaction boundaries
   5. Transform between external and internal representations
   
   The shell does NOT contain business logic - that lives in core.*
   The shell does NOT handle database operations - that lives in persistence.*"
  (:require [wagoe.user.core.session :as session-core]
            [wagoe.user.core.user :as user-core]
            [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.ports :as ports]
            [wagoe.platform.core.database.constraint :as constraint]
            [wagoe.platform.shell.service-interceptors :as service-interceptors]
            [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Helper Functions for External Dependencies
;; =============================================================================

(defn generate-secure-token
  "Generate cryptographically secure random token for sessions.
       This is a shell layer responsibility as it involves external randomness."
  []
  (let [secure-random (SecureRandom.)
        token-bytes (byte-array 32)]
    (.nextBytes secure-random token-bytes)
    (-> (java.util.Base64/getEncoder)
        (.encodeToString token-bytes)
        (str/replace "+" "-")
        (str/replace "/" "_")
        (str/replace "=" ""))))

(defn generate-user-id
  "Generate UUID for new users. Shell layer responsibility."
  []
  (UUID/randomUUID))

(defn- current-timestamp
  "Get current timestamp. Shell layer responsibility for time dependency.

   Private: a one-line clock read is not this library's API, and being public
   made it so (BOU-302). Tests still pin it with `with-redefs`, which
   var-quotes and so reaches a private var."
  []
  (Instant/now))

(def ^:dynamic *audit-context*
  "Per-request audit context bound by HTTP/CLI interceptors.
   Expected keys: :actor-id, :actor-email, :ip-address, :user-agent."
  nil)

(defn with-audit-context
  "Execute `f` with audit context bound for downstream service calls."
  [audit-context f]
  (binding [*audit-context* (merge *audit-context* audit-context)]
    (f)))

(defn request-audit-context
  "The audit context of a Ring request: its signed-in user is the actor."
  [request]
  (let [actor (or (:user request) (get-in request [:session :user]))]
    {:actor-id    (:id actor)
     :actor-email (:email actor)
     :ip-address  (:remote-addr request)
     :user-agent  (get-in request [:headers "user-agent"])}))

(defn- effective-audit-context
  "Build final audit context by combining defaults with bound request context."
  [{:keys [default-actor-id default-actor-email]}]
  (let [ctx (or *audit-context* {})]
    {:actor-id (or (:actor-id ctx) default-actor-id)
     :actor-email (or (:actor-email ctx) default-actor-email "system")
     :ip-address (:ip-address ctx)
     :user-agent (:user-agent ctx)}))

(defn- tx-bind-repository
  [repository tx-context]
  (when repository
    (assoc repository :ctx tx-context)))

(defn- tx-bind-user-service
  [user-service tx-context]
  (let [user-repository (tx-bind-repository (:user-repository user-service) tx-context)
        session-repository (tx-bind-repository (:session-repository user-service) tx-context)
        audit-repository (tx-bind-repository (:audit-repository user-service) tx-context)
        auth-service (some-> (:auth-service user-service)
                             (assoc :user-repository user-repository
                                    :session-repository session-repository))]
    (assoc user-service
           :user-repository user-repository
           :session-repository session-repository
           :audit-repository audit-repository
           :auth-service auth-service)))

(defn- foreign-key-refusal?
  "Whether `e`, or anything that caused it, is a refused foreign key: the
   platform's :constraint (BOU-590), or a driver error read by the platform's
   constraint reader, on every database."
  [e]
  (boolean
   (some (fn [x]
           (or (= :foreign-key (:constraint (ex-data x)))
               (and (instance? java.sql.SQLException x)
                    (= :foreign-key (:kind (constraint/violation
                                            {:sql-state  (.getSQLState ^java.sql.SQLException x)
                                             :error-code (.getErrorCode ^java.sql.SQLException x)
                                             :message    (ex-message x)}))))))
         (take-while some? (iterate ex-cause e)))))

(defn- admin-refusal
  "The :conflict refusing a write that takes admin rights from the `removed`
   users: one of them is the actor, or no other active admin would remain.
   Nil when it is allowed."
  [user-repository removed]
  (let [actor-id (:actor-id *audit-context*)
        ids      (set (map :id removed))
        others   (->> (:users (.find-users user-repository {:filter-role   :admin
                                                            :filter-active true
                                                            :limit         (inc (count ids))}))
                      (remove (comp ids :id))
                      count)]
    (some (fn [target]
            (when-let [{:keys [error]} (user-core/admin-removal-refusal actor-id target others)]
              (ex-info (:message error) (assoc error :user-id (:id target)))))
          ;; Your own rights first: that is the refusal to name.
          (sort-by #(not= actor-id (:id %)) removed))))

(defn- locked-write!
  "Run `write!` in one transaction holding the active admins' rows, refused
   first when it takes admin rights from the `removed` users. The count and
   the write cannot interleave with another's, so two admins demoting each
   other at once cannot leave none (BOU-673)."
  [user-repository removed write!]
  ;; The refusal is thrown outside the transaction, which would log it as a
  ;; failed one.
  (let [{:keys [refusal result]}
        (.with-admins-locked user-repository
                             (fn []
                               (if-let [refusal (when (seq removed) (admin-refusal user-repository removed))]
                                 {:refusal refusal}
                                 {:result (write!)})))]
    (if refusal (throw refusal) result)))

(defn- ends-sessions?
  "Whether `after` changes `before`'s access: a delete (nil), a role change
   (BOU-191) or a deactivation (BOU-665). A role `after` leaves out is kept."
  [before after]
  (or (nil? after)
      (and (:role after) (not= (:role before) (:role after)))
      (user-core/deactivates? before after)))

(defn- change-access!
  "Run `write!`, the change from `before` to `after` (nil: a delete): refused
   by the admin guard, and ending the user's sessions when it changes their
   access. Returns what `write!` returned."
  [user-repository session-repository before after write!]
  (let [result (if (and before (user-core/removes-admin? before after))
                 (locked-write! user-repository [before] write!)
                 (write!))]
    (when (and result before (ends-sessions? before after))
      (.invalidate-all-user-sessions session-repository (:id before)))
    result))

(defn- audit-quietly!
  "Record `entry-fn`'s audit entry for `user-id`; a failed write warns and does
   not undo the change."
  [audit-repository entry-fn user-id email]
  (try
    (let [{:keys [actor-id actor-email ip-address user-agent]} (effective-audit-context {})]
      (.create-audit-log audit-repository
                         (entry-fn actor-id actor-email user-id email ip-address user-agent)))
    (catch Exception e
      (println "WARN: Failed to create audit log:" (.getMessage e)))))

(defn- delete-users!
  "Delete `user-ids`, soft unless `permanent?`, in one transaction: all of
   them or, when the admin guard refuses the set or a reference blocks one,
   none. Returns the users deleted."
  [{:keys [user-repository session-repository audit-repository]} user-ids permanent?]
  (let [;; find-user-by-id skips a soft-deleted user, whom erasure still
        ;; removes, unaudited as before; no longer an admin, it needs no guard.
        users   (into [] (keep #(or (.find-user-by-id user-repository %) (when permanent? {:id %})))
                      (distinct user-ids))
        delete! (fn [{:keys [id]}]
                  (if permanent?
                    (.hard-delete-user user-repository id)
                    (when (.soft-delete-user user-repository id)
                      (.invalidate-all-user-sessions session-repository id)
                      true)))
        deleted (when (seq users)
                  (try
                    (locked-write! user-repository (filter #(user-core/removes-admin? % nil) users)
                                   #(filterv delete! users))
                    (catch Exception e
                      (if (foreign-key-refusal? e)
                        (throw (ex-info "Cannot permanently delete a user other records still reference"
                                        {:type     :hard-deletion-not-allowed
                                         :user-ids (mapv :id users)}
                                        e))
                        (throw e)))))]
    (doseq [{:keys [id email]} deleted :when email]
      (audit-quietly! audit-repository
                      (if permanent?
                        audit-core/permanently-delete-user-audit-entry
                        audit-core/delete-user-audit-entry)
                      id email))
    (vec deleted)))

;; =============================================================================
;; Database-Agnostic User Service (I/O Shell Layer)
;; =============================================================================

(defrecord UserService [user-repository session-repository audit-repository validation-config auth-service]

  ports/IUserService

  ;; User Management - Shell layer orchestrates I/O and calls pure core functions
  (register-user [_ user-data]
    (let [result (service-interceptors/execute-service-operation
                  :register-user
                  {:user-data user-data
                   :email (:email user-data)}
                  (fn [{:keys [params]}]
                    (let [user-data (:user-data params)]
                      ;; 1. Validate request using pure core function with validation config
                      (let [validation-result (user-core/validate-user-creation-request user-data validation-config)]
                        (when-not (:valid? validation-result)
                          (throw (ex-info "Invalid user data"
                                          {:type :validation-error
                                           :errors (:errors validation-result)
                                           :original-data user-data
                                           :interface-type :cli}))))

                      ;; 2. Validate password policy using pure authentication core
                      (when (:password user-data)
                        (let [password-validation (auth-core/meets-password-policy? (:password user-data)
                                                                                    (:password-policy validation-config)
                                                                                    {:email (:email user-data)})]
                          (when-not (:valid? password-validation)
                            ;; A :validation-error, as step 1 throws for the same rules, so
                            ;; the HTTP boundary answers 400 and forms render it. Its own
                            ;; type mapped to nothing and answered 500 (BOU-552).
                            (throw (ex-info "Password does not meet requirements"
                                            {:type :validation-error
                                             :errors (mapv #(assoc % :field :password)
                                                           (:violations password-validation))
                                             :violations (:violations password-validation)})))))

                      ;; 3. Check business rules using pure core function
                      (let [existing-user (.find-user-by-email user-repository (:email user-data))
                            uniqueness-result (user-core/check-duplicate-user-decision user-data existing-user)]
                        (when (= :reject (:decision uniqueness-result))
                          (throw (ex-info "User already exists"
                                          {:type :user-exists
                                           :message (:message uniqueness-result)}))))

                      ;; 4. Hash password using auth service (shell layer I/O)
                      (let [password-hash (when (:password user-data)
                                            (auth-shell/hash-password (:password user-data)))
                            user-data-with-hash (if password-hash
                                                  (-> user-data
                                                      (assoc :password-hash password-hash)
                                                      (dissoc :password))
                                                  user-data)
                            ;; 5. Persist using impure shell persistence layer
                            prepared-user (user-core/prepare-user-for-creation user-data-with-hash (current-timestamp) (generate-user-id))
                            created-user (.create-user user-repository prepared-user)]

                        ;; 6. Create audit log entry for user creation
                        (try
                          (.create-audit-log audit-repository
                                             (audit-core/create-user-audit-entry
                                              nil  ; actor-id (nil for self-registration, or actor from context)
                                              "system"  ; actor-email (system or actual actor)
                                              created-user
                                              nil  ; ip-address (extract from request context if available)
                                              nil)) ; user-agent (extract from request context if available)
                          (catch Exception e
                            ;; Log audit failure but don't fail the operation
                            (println "WARN: Failed to create audit log:" (.getMessage e))))

                        ;; Remove sensitive data before returning
                        (dissoc created-user :password-hash))))
                  {:system {:user-repository user-repository
                            :session-repository session-repository
                            :auth-service auth-service}})]
      result))

  (register-or-authenticate-user [this user-data login-context]
    (let [{:keys [user created? authenticated? auth-result]}
          (.claim-user-identity ^wagoe.user.ports.IUserService this
                                {:user-data user-data
                                 :login-context login-context})]
      {:user user
       :created? created?
       :authenticated? (if created? false authenticated?)
       :auth-result (if created? nil auth-result)}))

  (claim-user-identity [this {:keys [user-data login-context tx-context]}]
    (service-interceptors/execute-service-operation
     :claim-user-identity
     {:email (:email user-data)}
     (fn [_]
       (let [service (if tx-context
                       (tx-bind-user-service this tx-context)
                       this)]
         (if-let [existing-user (.find-user-by-email (:user-repository service) (:email user-data))]
           (let [auth-result (.authenticate-user ^wagoe.user.ports.IUserService service
                                                 (merge {:email (:email user-data)
                                                         :password (:password user-data)
                                                         :remember false}
                                                        login-context))]
             (if (:authenticated auth-result)
               {:mode :authenticated
                :user (:user auth-result)
                :created? false
                :authenticated? true
                :auth-result auth-result}
               (throw (ex-info "Existing account could not be verified"
                               {:type :unauthorized
                                :email (:email existing-user)}))))
           (let [_ (.register-user ^wagoe.user.ports.IUserService service user-data)
                 auth-result (.authenticate-user ^wagoe.user.ports.IUserService service
                                                 (merge {:email (:email user-data)
                                                         :password (:password user-data)
                                                         :remember false}
                                                        login-context))]
             (when-not (:authenticated auth-result)
               (throw (ex-info "Authentication failed after creating account"
                               {:type :internal-error
                                :email (:email user-data)})))
             {:mode :registered
              :user (:user auth-result)
              :created? true
              :authenticated? true
              :auth-result auth-result}))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (authenticate-user [_ user-credentials]
    (service-interceptors/execute-service-operation
     :authenticate-user
     {:user-credentials user-credentials
      :email (:email user-credentials)}
     (fn [{:keys [params]}]
       (let [user-credentials (:user-credentials params)
             {:keys [email password ip-address user-agent mfa-code]} user-credentials
             login-context {:ip-address ip-address
                            :user-agent user-agent
                            :mfa-code mfa-code}

             ;; Service-level lockout gate: look up user and check lockout BEFORE
             ;; delegating to auth-shell, providing belt-and-suspenders enforcement.
             existing-user (.find-user-by-email user-repository email)
             current-time (current-timestamp)
             lockout-check (when existing-user
                             (auth-core/should-allow-login-attempt?
                              existing-user nil current-time))]

         (if (and existing-user
                  (not (:allowed? lockout-check))
                  ;; Only short-circuit for actual lockout (has :retry-after).
                  ;; Other rejection reasons (deactivated, deleted) must fall
                  ;; through to the normal auth flow so they keep their own
                  ;; error semantics — see should-allow-login-attempt? in
                  ;; authentication.clj for the full set of reasons.
                  (:retry-after lockout-check))
           ;; Account is locked out — return immediately without attempting auth
           (let [audit-entry (audit-core/login-audit-entry
                              (:id existing-user)
                              (:email existing-user)
                              ip-address
                              user-agent
                              false
                              (or (:reason lockout-check) "Account locked"))]
             (.create-audit-log audit-repository audit-entry)
             {:authenticated false
              :reason :account-locked
              :message (or (:reason lockout-check)
                           "Too many failed attempts, please try again later")
              :retry-after (:retry-after lockout-check)})

           ;; Not locked out — delegate to auth-service for full authentication
           (let [auth-result (auth-shell/authenticate-user
                              auth-service
                              email
                              password
                              login-context)]

             ;; Transform auth-service result to match expected format
             (cond
               ;; Successful authentication
               (:success? auth-result)
               (let [audit-entry (audit-core/login-audit-entry
                                  (get-in auth-result [:user :id])
                                  (get-in auth-result [:user :email])
                                  ip-address
                                  user-agent
                                  true
                                  nil)]
                 (.create-audit-log audit-repository audit-entry)
                 {:authenticated true
                  :user (:user auth-result)
                  :session (:session auth-result)
                  :session-token (get-in auth-result [:session :session-token])
                  :jwt-token (get-in auth-result [:session :jwt-token])})

               ;; MFA required (intermediate state)
               (:requires-mfa? auth-result)
               {:authenticated false
                :requires-mfa? true
                :user (:user auth-result)
                :message (get-in auth-result [:error :message])}

               ;; Authentication failed
               :else
               (let [user-for-audit (or existing-user
                                        (.find-user-by-email user-repository email))
                     audit-entry (when user-for-audit
                                   (audit-core/login-audit-entry
                                    (:id user-for-audit)
                                    (:email user-for-audit)
                                    ip-address
                                    user-agent
                                    false
                                    (or (get-in auth-result [:error :message])
                                        "Authentication failed")))]
                 (when audit-entry
                   (.create-audit-log audit-repository audit-entry))
                 ;; The public shape is unchanged. auth-shell moved to the
                 ;; ADR-036 §3 return — {:error {:type … :message …}} — and this
                 ;; is the layer that flattens it, so `login` keeps answering
                 ;; {:authenticated false :reason … :message … :retry-after …}
                 ;; to every existing caller (BOU-323).
                 {:authenticated false
                  :reason (get-in auth-result [:error :type])
                  :message (get-in auth-result [:error :message])
                  :retry-after (get-in auth-result [:error :retry-after])}))))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (validate-session [_ session-token]
    (service-interceptors/execute-service-operation
     :validate-session
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)]
         ;; Read from the store on every call, never a cache: revocation happens
         ;; in many places and on other replicas, and must count on the next
         ;; request (BOU-663).
         (when-let [session (.find-session-by-token session-repository session-token)]
           (let [current-time (current-timestamp)
                 {:keys [valid? reason]} (session-core/is-session-valid? session current-time)
                 user    (when valid? (.find-user-by-id user-repository (:user-id session)))
                 refusal (if valid? (session-core/account-refusal user) reason)]
             (when refusal
               ;; Session invalid — throw so middleware returns 401
               (throw (ex-info "Session invalid"
                               {:type :session-invalid
                                :reason refusal})))
             ;; The user rides along, so the middleware need not read it again.
             (let [to-write (session-core/session-access-write session current-time)]
               (assoc (or (some->> to-write (.update-session session-repository))
                          to-write
                          session)
                      :user (user-core/without-credentials user)))))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (logout-user [_ session-token]
    (service-interceptors/execute-service-operation
     :logout-user
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)]
         ;; 1. Find and invalidate session using impure shell persistence layer
         (if-let [session (.find-session-by-token session-repository session-token)]
           (let [_invalidated-session (.invalidate-session session-repository session-token)
                 session-user-id (or (:user-id session) (:user_id session))
                 session-ip-address (or (:ip-address session) (:ip_address session))
                 session-user-agent (or (:user-agent session) (:user_agent session))
                  ;; Get user info for audit log
                 user (when session-user-id
                        (.find-user-by-id user-repository session-user-id))]

             ;; 2. Create audit log entry
             (when user
               (let [audit-entry (audit-core/logout-audit-entry
                                  (:id user)
                                  (:email user)
                                  session-ip-address  ; Use IP from session
                                  session-user-agent)] ; Use user-agent from session
                 (.create-audit-log audit-repository audit-entry)))

             ;; Return result
             {:invalidated true :session-id (:id session)})

           ;; Session not found
           {:invalidated false})))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  ;; Additional IUserService methods
  (get-user-by-id [_ user-id]
    (service-interceptors/execute-service-operation
     :get-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (some-> (.find-user-by-id user-repository (:user-id params))
               (dissoc :password-hash)))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (get-user-by-email [_ email]
    (service-interceptors/execute-service-operation
     :get-user-by-email
     {:email email}
     (fn [{:keys [params]}]
       (let [{:keys [email]} params
             user (.find-user-by-email user-repository email)]
         ;; Remove sensitive data before returning
         (when user
           (dissoc user :password-hash))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (list-users [_ options]
    (service-interceptors/execute-service-operation
     :list-users
     {:options options}
     (fn [{:keys [params]}]
       (let [{:keys [options]} params
             result (.find-users user-repository options)
             users (:users result)
             total-count (:total-count result)
             cleaned-users (map #(dissoc % :password-hash) users)]
         ;; Remove sensitive data from all users and return with pagination info
         {:users cleaned-users
          :total-count total-count}))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (update-user-profile [_ user-entity]
    (service-interceptors/execute-service-operation
     :update-user-profile
     {:user-entity user-entity
      :user-id (:id user-entity)}
     (fn [{:keys [params]}]
       (let [;; Get old user data for audit trail (before update)
             old-user (.find-user-by-id user-repository (:id (:user-entity params)))
             ;; A partial update keeps the stored values of the required keys.
             user-entity (cond->> (:user-entity params)
                           old-user (merge (select-keys old-user [:email :name :role :active :created-at])))]
         ;; 1. Validate update using pure core function
         (let [validation-result (user-core/validate-user-update-request user-entity)]
           (when-not (:valid? validation-result)
             (throw (ex-info "Invalid user data"
                             {:type :validation-error
                              :errors (:errors validation-result)
                              :original-data user-entity
                              :interface-type :cli}))))

         ;; The database's refusal names no column on MySQL, so the email is
         ;; checked first, in the shape the platform gives a named one; a form
         ;; shows it on its field (BOU-673). The constraint stays the backstop.
         (when-let [other (when (and old-user (not= (:email user-entity) (:email old-user)))
                            (.find-user-by-email user-repository (:email user-entity)))]
           (when (not= (:id other) (:id user-entity))
             (throw (ex-info "Another record already has this email"
                             {:type :conflict :constraint :unique :field :email}))))

         ;; 2. Stamp updated-at
         (let [current-time (current-timestamp)
               prepared-user (user-core/prepare-user-for-update user-entity current-time)
               ;; 3. Persist using impure shell persistence layer
               updated-user (change-access! user-repository session-repository old-user user-entity
                                            #(.update-user user-repository prepared-user))]

          ;; 4. Create audit log entry
           (when (and updated-user old-user)
             (let [{:keys [actor-id actor-email ip-address user-agent]}
                   (effective-audit-context {:default-actor-id (:id updated-user)
                                             :default-actor-email (:email updated-user)})
                   audit-entry (audit-core/update-user-audit-entry
                                actor-id
                                actor-email
                                (:id updated-user)      ; target-user-id
                                (:email updated-user)   ; target-user-email
                                old-user                ; old user data
                                updated-user            ; new user data
                                ip-address
                                user-agent)]
               (.create-audit-log audit-repository audit-entry)))

           ;; Remove sensitive data before returning. A partial update returns
           ;; the whole user, as the port promises.
           (when updated-user
             (dissoc (merge old-user updated-user) :password-hash)))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (deactivate-user [_ user-id]
    (service-interceptors/execute-service-operation
     :deactivate-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             ;; Get user details before deactivation for audit trail
             user (.find-user-by-id user-repository user-id)
             ;; :active only, never :deleted-at: a deactivated user stays
             ;; listed and can be reactivated (BOU-681). Only that column is
             ;; written, so a concurrent password or MFA change is not undone.
             after  {:id user-id :active false}
             result (when user
                      (change-access! user-repository session-repository user after
                                      #(.update-user user-repository
                                                     (user-core/prepare-user-for-update after (current-timestamp)))))]
         (when result
           (audit-quietly! audit-repository audit-core/deactivate-user-audit-entry user-id (:email user)))
         (boolean result)))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (delete-user [this user-id]
    (service-interceptors/execute-service-operation
     :delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (boolean (seq (delete-users! this [(:user-id params)] false))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (permanently-delete-user [this user-id]
    (service-interceptors/execute-service-operation
     :permanently-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (boolean (seq (delete-users! this [(:user-id params)] true))))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (delete-users [this user-ids {:keys [permanent?]}]
    (service-interceptors/execute-service-operation
     :delete-users
     {:user-ids user-ids}
     (fn [{:keys [params]}]
       (mapv :id (delete-users! this (:user-ids params) permanent?)))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :audit-repository audit-repository
               :auth-service auth-service}}))

  (logout-user-everywhere [_ user-id]
    (service-interceptors/execute-service-operation
     :logout-user-everywhere
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             invalidated-count (.invalidate-all-user-sessions session-repository user-id)]
         ;; Return count of invalidated sessions
         invalidated-count))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  (get-user-sessions [_ user-id]
    (service-interceptors/execute-service-operation
     :get-user-sessions
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             sessions (.find-sessions-by-user session-repository user-id)]
         ;; Return sessions (no sensitive data to filter)
         sessions))
     {:system {:user-repository user-repository
               :session-repository session-repository
               :auth-service auth-service}}))

  ;; ---------------------------------------------------------------------------
  ;; Audit Log Query Operations
  ;; ---------------------------------------------------------------------------

  (list-audit-logs [_ options]
    (service-interceptors/execute-service-operation
     :list-audit-logs
     {:options options}
     (fn [{:keys [params]}]
       (let [options (:options params)
             ;; Query audit repository with provided options
             result (.find-audit-logs audit-repository options)]
         ;; Return result map with :audit-logs and :total-count
         result))
     {:system {:audit-repository audit-repository}}))

  (get-audit-logs-for-user [_ user-id options]
    (service-interceptors/execute-service-operation
     :get-audit-logs-for-user
     {:user-id user-id :options options}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             options (:options params)
             ;; Query audit logs for specific user
             logs (.find-audit-logs-by-user audit-repository user-id options)]
         ;; Return vector of audit log entities
         logs))
     {:system {:audit-repository audit-repository}}))

  ;; ---------------------------------------------------------------------------
  ;; Password Management Operations
  ;; ---------------------------------------------------------------------------

  (change-password [_ user-id current-password new-password]
    (service-interceptors/execute-service-operation
     :change-password
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             ;; 1. Get user by ID
             user (.find-user-by-id user-repository user-id)]
         (when-not user
           (throw (ex-info "User not found"
                           {:type :user-not-found
                            :user-id user-id})))

         ;; 2. Verify current password
         (when-not (auth-shell/verify-password current-password (:password-hash user))
           (throw (ex-info "Current password is incorrect"
                           {:type :invalid-current-password
                            :user-id user-id})))

         ;; 3. Validate new password against policy
         (let [password-validation (auth-core/meets-password-policy? new-password
                                                                     (:password-policy validation-config)
                                                                     user)]
           (when-not (:valid? password-validation)
             (throw (ex-info "New password does not meet requirements"
                             {:type :password-policy-violation
                              :violations (:violations password-validation)}))))

         ;; 4. Hash new password
         (let [new-password-hash (auth-shell/hash-password new-password)
               current-time (current-timestamp)
               ;; 5. Update user with new password hash
               updated-user (.update-user user-repository
                                          (assoc user
                                                 :password-hash new-password-hash
                                                 :updated-at current-time))]
         ;; 5b. Invalidate every session for this user (BOU-191): a credential
         ;;     change must not leave older sessions authenticated. The caller
         ;;     re-authenticates with the new password.
           (.invalidate-all-user-sessions session-repository user-id)

         ;; 6. Create audit log entry
           (try
             (let [{:keys [actor-id actor-email ip-address user-agent]}
                   (effective-audit-context {:default-actor-id user-id
                                             :default-actor-email (:email user)})
                   audit-entry (audit-core/update-user-audit-entry
                                actor-id
                                actor-email
                                user-id           ; target-user-id
                                (:email user)     ; target-user-email
                                user              ; old user data (without exposing password hash)
                                updated-user      ; new user data
                                ip-address
                                user-agent)]
               (.create-audit-log audit-repository audit-entry))
             (catch Exception e
               (println "WARN: Failed to create audit log for password change:" (.getMessage e))))

           ;; Return success
           true)))
     {:system {:user-repository user-repository
               :audit-repository audit-repository
               :auth-service auth-service}})))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-service
  "Create a user service instance with injected repositories, validation config, and auth service.

   Args:
     user-repository: Implementation of IUserRepository
     session-repository: Implementation of IUserSessionRepository
     audit-repository: Implementation of IUserAuditRepository
     validation-config: Map containing validation policies and settings
     auth-service: Implementation of IAuthenticationService

   Returns:
     UserService instance

   Example:
     (def service (create-user-service user-repo session-repo audit-repo validation-cfg auth-svc))"
  [user-repository session-repository audit-repository validation-config auth-service]
  (->UserService user-repository session-repository audit-repository validation-config auth-service))
