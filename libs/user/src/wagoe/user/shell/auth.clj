(ns wagoe.user.shell.auth
  "Shell Layer - Authentication service with side effects.
   
   This is the SHELL layer for authentication operations:
   - Password hashing and verification (bcrypt)
   - JWT token creation and validation
   - Session token generation
   - Authentication coordination
   - MFA verification
   
   All I/O and side effects happen here. Pure business logic
   is delegated to wagoe.user.core.authentication."
  (:require [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.core.mfa :as mfa-core]
            [wagoe.user.core.user :as user-core]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.lockout :as lockout]
            [wagoe.user.shell.mfa :as mfa-shell]
            [wagoe.user.shell.bcrypt :as bcrypt]
            [wagoe.user.shell.jwt-secret :as jwt-secret]
            [buddy.core.codecs :as codecs]
            [buddy.core.mac :as mac]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.time Instant Duration)
           (java.security SecureRandom)
           (java.util Base64)))

;; =============================================================================
;; Password Hashing Operations (Side Effects)
;; =============================================================================

(defn hash-password
  "`password` bcrypt-hashed, see `wagoe.user.shell.bcrypt`."
  [password]
  (bcrypt/derive-hash password))

(defn verify-password
  "Shell function: Verify password against bcrypt hash.
   
   Args:
     password: Plain text password  
     hash: Bcrypt hash string
     
   Returns:
     Boolean - true if password matches
     
   Side effects: Crypto operations"
  [password hash]
  (hashers/check password hash))

;; =============================================================================
;; JWT Token Operations (Side Effects)
;; =============================================================================

(def ^:private jwt-alg
  "Pinned JWT signature algorithm. Passed to both sign and unsign so a token
   presented with a different alg (e.g. the classic alg:none downgrade) is
   rejected instead of trusting the header's self-declared algorithm."
  :hs256)

(defn get-jwt-secret
  "The validated JWT_SECRET. Throws WGE-103 as `jwt-secret/value` does."
  []
  (jwt-secret/value))

(defn validate-jwt-secret!
  "Fail-fast startup check: force resolution + validation of JWT_SECRET so a
   missing or too-short secret aborts boot, rather than booting green and
   failing on the first authentication request. Call from system wiring."
  []
  (get-jwt-secret)
  nil)

(defn password-fingerprint
  "What changes when `user`'s password does, without revealing its hash: an
   enrolment ticket carries it, and a changed password voids the ticket."
  [user]
  (codecs/bytes->hex (mac/hash (str (:password-hash user)) {:key (get-jwt-secret) :alg :hmac+sha256})))

(defn create-jwt-token
  "Shell function: Create JWT token for authenticated user.
   
   Args:
     user: User entity
     session-duration-hours: How long token should be valid
     
   Returns:
     JWT token string
     
   Side effects: Crypto operations, time-dependent

   `session-id` goes in as :sid; the JWT middleware refuses a token whose
   session has ended, and one without a sid (BOU-1006)."
  ([user session-duration-hours] (create-jwt-token user session-duration-hours nil))
  ([user session-duration-hours session-id]
   (let [now (Instant/now)
         exp (.plus now (Duration/ofHours session-duration-hours))
         claims (cond-> {:sub (str (:id user))
                         :email (:email user)
                         :role (name (or (:role user) :user))
                         :iat (.getEpochSecond now)
                         :exp (.getEpochSecond exp)}
                  session-id (assoc :sid (str session-id)))]
     (jwt/sign claims (get-jwt-secret) {:alg jwt-alg}))))

(defn validate-jwt-token
  "Shell function: Validate and decode JWT token.
   
   Args:
     token: JWT token string
     
   Returns:
     {:valid? true :claims {...}} or {:valid? false :error string}
     
   Side effects: Crypto operations"
  [token]
  (try
    (let [claims (jwt/unsign token (get-jwt-secret) {:alg jwt-alg})]
      {:valid? true :claims claims})
    (catch Exception e
      {:valid? false :error (.getMessage e)})))

;; =============================================================================
;; Session Token Generation (Side Effects)
;; =============================================================================

(defn generate-session-token
  "Shell function: Generate cryptographically secure session token.

   Returns:
     URL-safe base64-encoded random token string (no +, /, or = chars).
     Uses URL-safe encoding so tokens can safely appear in URL paths
     without triggering Jetty 'Ambiguous URI path separator' errors.

   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

;; =============================================================================
;; Authentication Service Coordination
;; =============================================================================

(defrecord AuthenticationService [user-repository session-repository mfa-service auth-config])

(defn create-authentication-service
  "Factory function: Create authentication service instance.
   
   Args:
     user-repository: IUserRepository implementation
     session-repository: IUserSessionRepository implementation
     mfa-service: MFAService implementation  
     auth-config: Authentication configuration map. :mfa-required? is an
                  optional (fn [user login-context] bool), a symbol naming
                  one, or an IMfaPolicy; throws :configuration-error otherwise.
     
   Returns:
     AuthenticationService instance"
  [user-repository session-repository mfa-service auth-config]
  (->AuthenticationService user-repository session-repository mfa-service
                           (cond-> auth-config
                             (:mfa-required? auth-config)
                             (update :mfa-required? #(hooks/as-fn :mfa-required? %)))))

(defn mfa-required?
  "Whether this login needs a second factor: the app's :mfa-required? hook
   when it set one, else whether the user turned MFA on. Shared by password
   and OIDC sign-in. The hook sees neither the user's secrets nor the code."
  [auth-service user login-context]
  (boolean
   (if-let [hook (get-in auth-service [:auth-config :mfa-required?])]
     (try
       (hook (dissoc user :password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used)
             (dissoc login-context :mfa-code))
       (catch Exception e
         (throw (ex-info "The :mfa-required? hook failed" {:type :internal-error} e))))
     (:mfa-enabled user))))

(defn open-session!
  "Create the DB session for a user who has proven who they are, under the
   session policy. Shared by password and OIDC sign-in.

   Returns {:policy … :session …}; :session carries a :jwt-token that is not
   stored."
  [auth-service user login-risk login-context current-time]
  (let [{:keys [session-repository auth-config]} auth-service
        policy (auth-core/should-create-session? user login-risk auth-config)]
    {:policy  policy
     :session (when (:create-session? policy)
                (let [hours   (:session-duration-hours policy)
                      session (.create-session session-repository
                                               {:user-id       (:id user)
                                                :duration-hours hours
                                                :session-token (generate-session-token)
                                                :expires-at    (.plus ^Instant current-time (Duration/ofHours hours))
                                                :user-agent    (:user-agent login-context)
                                                :ip-address    (:ip-address login-context)})]
                  (assoc session :jwt-token (create-jwt-token user hours (:id session)))))}))

(defn verify-second-factor!
  "Check the TOTP or backup `code` for `user`, spending a backup code. The
   caller has claimed the attempt with `lockout/claim-attempts!`, so a wrong
   or blank code stays counted, the same counter as a wrong password (BOU-698).
   Returns `mfa-shell/verify-mfa-code`'s map, or nil for a blank code."
  [auth-service user code]
  (when-not (str/blank? code)
    (mfa-shell/check-code! (:user-repository auth-service) user code)))

(def ^:private dummy-hash
  "A hash to check a password against when no account has the email."
  (delay (hash-password "no account has this email")))

(defn accounts-of
  "Every account of `email`. A repository without `ITenantAccounts` knows one
   per email (BOU-995)."
  [user-repository email]
  (if (satisfies? ports/ITenantAccounts user-repository)
    (ports/find-users-by-email user-repository email)
    (some-> (.find-user-by-email user-repository email) vector)))

(defn account-in-tenant
  "The account of `email` in tenant `tenant-id`, nil meaning no tenant."
  [user-repository tenant-id email]
  (if (satisfies? ports/ITenantAccounts user-repository)
    (ports/find-user-by-tenant-and-email user-repository tenant-id email)
    (.find-user-by-email user-repository email)))

(defn login-accounts
  "The accounts `email` signs in to: the one of `login-context`'s tenant when it
   names one (`:tenant-given?`, `:tenant-id` nil for no tenant), else every one."
  [user-repository email login-context]
  (if (:tenant-given? login-context)
    (some-> (account-in-tenant user-repository (:tenant-id login-context) email) vector)
    (accounts-of user-repository email)))

(defn- attempt
  "Steps 3 and 4 for one account: may it try, the attempt counted, then the
   password checked. Counting first is the lockout check parallel attempts
   cannot race (BOU-785)."
  [user-repository auth-config user password current-time]
  (let [allowed (auth-core/should-allow-login-attempt? user auth-config current-time)
        claim   (when (:allowed? allowed)
                  (lockout/claim-attempts! user-repository auth-config (:id user) 1 current-time))]
    (when-not (:allowed? allowed)
      (log/warn "Login attempt blocked" {:user-id (:id user) :reason (:reason allowed)}))
    {:user            user
     :login-allowed?  allowed
     :claim           claim
     :password-valid? (when (:claimed? claim) (verify-password password (:password-hash user)))}))

(defn authenticate-user
  "Coordinate user authentication with all security checks including MFA.
   
   Args:
     auth-service: AuthenticationService instance
     email: User email
     password: Plain text password
     login-context: Map with :ip-address, :user-agent, :mfa-code (optional),
                    and :tenant-given? with :tenant-id to sign in to one tenant's
                    account; without, every account of the email is tried
     
   Returns:
     On success `{:success? true :user … :session …}`.

     On failure `{:success? false :error {:type … :message …}}`, the shape
     ADR-036 §3 decided — a wrong password is an answer, not an exceptional
     condition, and a caller that wants to escalate can rethrow the `:error`
     map as a typed `ex-info` without inventing a taxonomy. `:type` is one of
     `:validation-error`, `:authentication-failed`, `:account-locked`, `:mfa-required`,
     `:mfa-enrolment-required`, `:mfa-verification-failed`,
     `:tenant-choice-required`; `:account-locked`, and an authentication-failed
     lockout, carry `:retry-after`. A tenant choice comes with `:tenant-ids`: the
     tenants (nil for none) where this email and password both match (BOU-995).

     `:requires-mfa?` stays at the top level: it is not part of why the attempt
     failed, it is what the caller does next."
  [auth-service email password login-context]
  (let [{:keys [user-repository session-repository auth-config]} auth-service]
    (log/info "Authentication attempt" {:email email :ip (:ip-address login-context)})

    (let [current-time (Instant/now)

          ;; Step 1: Validate login request format
          credential-validation (auth-core/validate-login-credentials
                                 {:email email :password password})

          _ (when-not (:valid? credential-validation)
              (log/warn "Invalid login credentials format" {:email email}))

          ;; Step 2: the accounts this email signs in to (I/O): the named
          ;; tenant's, else each of them (BOU-995)
          ;; The caller may have read them already
          accounts (when (:valid? credential-validation)
                     (or (:accounts login-context) (login-accounts user-repository email login-context)))

          ;; Steps 3 and 4, per account. With several, only those the password
          ;; opens count, and nothing tells the others apart.
          ;; ponytail: one bcrypt per account of the email
          attempts (mapv #(attempt user-repository auth-config % password current-time) accounts)
          ;; No account: one hash anyway, so the time says nothing of it
          _        (when (and (:valid? credential-validation) (empty? attempts))
                     (verify-password password @dummy-hash))
          several? (< 1 (count attempts))
          matched  (filterv :password-valid? attempts)
          winner   (if several? (user-core/sole matched) (first attempts))
          ;; The password opened some: the others were not guessed at, and a
          ;; choice signs into none yet, so no attempt but the winner's counts
          _        (when (and several? (seq matched))
                     (run! #(lockout/release-attempt! user-repository (get-in % [:user :id]) (:claim %))
                           (remove #{winner} (filter (comp :claimed? :claim) attempts))))
          {:keys [user login-allowed? claim password-valid?]} winner

          ;; Step 5: Analyze login risk
          login-risk (when (and user password-valid?)
                       (auth-core/analyze-login-risk
                        user login-context
                        (.find-sessions-by-user session-repository (:id user))
                        current-time))

          ;; Step 6: Check MFA requirement and verification
          ;; Blank is not a code: answer :mfa-required, without counting.
          mfa-code (when-not (str/blank? (:mfa-code login-context)) (:mfa-code login-context))
          mfa-requirement (when (and user password-valid?)
                            (mfa-core/determine-mfa-requirement
                             user password-valid? mfa-code login-risk
                             (mfa-required? auth-service user login-context)))

          ;; Step 7: Verify MFA code if provided
          mfa-verification (when (and (:requires-mfa? mfa-requirement) mfa-code)
                             (verify-second-factor! auth-service user mfa-code))

          ;; Step 8: Handle authentication result
          result (cond
                   ;; Validation errors
                   (not (:valid? credential-validation))
                   {:success? false
                    :error {:type    :validation-error
                            :message "Invalid request format"}}

                   ;; The password opens accounts in several tenants: they choose
                   (and several? (< 1 (count matched)))
                   {:success?   false
                    :error      {:type    :tenant-choice-required
                                 :message "Choose the tenant to sign in to"}
                    :tenant-ids (mapv (comp :tenant-id :user) matched)}

                   ;; Several accounts, none the password opens: one answer for all
                   (and several? (empty? matched))
                   (do (log/warn "Authentication failed - invalid password" {:email email})
                       {:success? false
                        :error    {:type    :authentication-failed
                                   :message "Invalid credentials"}})

                   ;; User not found or login blocked
                   (not (:allowed? login-allowed?))
                   {:success? false
                    :error {:type        :authentication-failed
                            :message     (:reason login-allowed?)
                            ;; Beside the message rather than at the top level:
                            ;; it is part of why this failed. The service lifts
                            ;; it back out for its own callers.
                            :retry-after (:retry-after login-allowed?)}}

                   (not (:claimed? claim))
                   {:success? false
                    :error    (lockout/locked-error (:retry-after claim))}

                   ;; Password verification failed; the claim counted it
                   (not password-valid?)
                   (do
                     (log/warn "Authentication failed - invalid password" {:email email})
                     {:success? false
                      :error {:type    :authentication-failed
                              :message "Invalid credentials"}})

                   ;; Required, but the user has no second factor to give.
                   ;; The password alone may set one up only just after an
                   ;; admin reset, or on registering.
                   (:requires-enrolment? mfa-requirement)
                   (let [enrol? (or (:registration? login-context)
                                    (mfa-core/reset-enrolment-open?
                                     user current-time
                                     (get auth-config :mfa-reset-enrolment-hours
                                          mfa-core/default-reset-enrolment-hours)))]
                     (lockout/release-attempt! user-repository (:id user) claim)
                     (log/warn "MFA required but not enrolled" {:email email :may-enrol? enrol?})
                     (cond-> {:success? false
                              :error {:type    :mfa-enrolment-required
                                      :message (:reason mfa-requirement)}}
                       enrol? (assoc :user (user-core/without-credentials user)
                                     :password-fingerprint (password-fingerprint user))))

                   ;; MFA required but not provided
                   (and (:requires-mfa? mfa-requirement)
                        (nil? mfa-code))
                   (do
                     (lockout/release-attempt! user-repository (:id user) claim)
                     {:success? false
                      :requires-mfa? true
                      :error {:type    :mfa-required
                              :message "MFA code required"}
                      :user (dissoc user :password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used)})

                   ;; MFA code provided but invalid
                   (and (:requires-mfa? mfa-requirement)
                        mfa-code
                        (not (:valid? mfa-verification)))
                   (do
                     (log/warn "MFA verification failed" {:email email})
                     {:success? false
                      :error {:type    :mfa-verification-failed
                              :message "Invalid MFA code"}})

                   ;; Successful authentication (with or without MFA)
                   :else
                   (let [;; Update user for successful login (I/O)
                         login-updates (auth-core/prepare-successful-login-updates user current-time)
                         _ (.update-user user-repository (assoc login-updates :id (:id user)))

                         {session-policy :policy session :session}
                         (open-session! auth-service user login-risk login-context current-time)]

                     (when (:used-backup-code? mfa-verification)
                       (log/info "Backup code used for MFA" {:email email}))
                     (log/info "Authentication successful"
                               {:email email
                                :risk-score (:risk-score login-risk)
                                :mfa-used (:requires-mfa? mfa-requirement)
                                :session-duration (:session-duration-hours session-policy)})

                     {:success? true
                      :user (dissoc user :password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used) ; Don't return sensitive data
                      :session session
                      :risk-analysis login-risk
                      :mfa-verified? (or (not (:requires-mfa? mfa-requirement)) (:valid? mfa-verification))
                      :requires-additional-verification? (:requires-additional-verification? session-policy)}))]

      result)))

;; =============================================================================
;; Password Management Functions
;; =============================================================================

(defn create-user-with-password
  "Shell function: Create user with hashed password.
   
   Args:
     user-data: User creation data with plain text password
     user-repository: IUserRepository implementation
     
   Returns:
     User entity with password-hash field populated
     
    Side effects: Password hashing, I/O operations"
  [user-data _user-repository]
  (let [password (:password user-data)
        password-hash (hash-password password)
        user-without-password (dissoc user-data :password)
        user-with-hash (assoc user-without-password
                              :password-hash password-hash
                              :password-created-at (Instant/now))]
    user-with-hash))

