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
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.shell.lockout :as lockout]
            [wagoe.user.shell.mfa :as mfa-shell]
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
  "Shell function: Hash password using bcrypt.
   
   Args:
     password: Plain text password
     
   Returns:
     Bcrypt hash string (60 characters)
     
   Side effects: Crypto operations with secure random"
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

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

(def ^:private min-jwt-secret-length
  "Minimum JWT_SECRET length. A short HMAC key materially weakens signing, so the
   framework refuses anything shorter."
  32)

(def ^:private jwt-alg
  "Pinned JWT signature algorithm. Passed to both sign and unsign so a token
   presented with a different alg (e.g. the classic alg:none downgrade) is
   rejected instead of trusting the header's self-declared algorithm."
  :hs256)

(defn- validate-jwt-secret*
  "Return secret when valid, else throw a :configuration-error. Enforces
   presence and the minimum length."
  [secret]
  (cond
    (str/blank? secret)
    (throw (ex-info "JWT_SECRET environment variable not configured. Set JWT_SECRET before starting the application."
                    {:type :configuration-error
                     :required-env-var "JWT_SECRET"}))

    (< (count secret) min-jwt-secret-length)
    (throw (ex-info (str "JWT_SECRET must be at least " min-jwt-secret-length " characters (HMAC key strength).")
                    {:type :configuration-error
                     :required-env-var "JWT_SECRET"}))

    :else secret))

(def ^:private jwt-secret
  "JWT signing secret, resolved and validated from the environment once per
   process. The environment cannot change within a JVM, so a per-call lookup
   only added cost on every sign/verify."
  (delay (validate-jwt-secret* (System/getenv "JWT_SECRET"))))

(defn get-jwt-secret
  "The validated JWT_SECRET. Throws :configuration-error when it is missing or short."
  []
  @jwt-secret)

(defn validate-jwt-secret!
  "Fail-fast startup check: force resolution + validation of JWT_SECRET so a
   missing or too-short secret aborts boot, rather than booting green and
   failing on the first authentication request. Call from system wiring."
  []
  (get-jwt-secret)
  nil)

(defn create-jwt-token
  "Shell function: Create JWT token for authenticated user.
   
   Args:
     user: User entity
     session-duration-hours: How long token should be valid
     
   Returns:
     JWT token string
     
   Side effects: Crypto operations, time-dependent"
  [user session-duration-hours]
  (let [now (Instant/now)
        exp (.plus now (Duration/ofHours session-duration-hours))
        claims {:sub (str (:id user))
                :email (:email user)
                :role (name (or (:role user) :user))
                :iat (.getEpochSecond now)
                :exp (.getEpochSecond exp)}]
    (jwt/sign claims (get-jwt-secret) {:alg jwt-alg})))

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
                                                :session-token (generate-session-token)
                                                :expires-at    (.plus ^Instant current-time (Duration/ofHours hours))
                                                :user-agent    (:user-agent login-context)
                                                :ip-address    (:ip-address login-context)})]
                  (assoc session :jwt-token (create-jwt-token user hours))))}))

(defn verify-second-factor!
  "Check the TOTP or backup `code` for `user`, spending a backup code. The
   caller has claimed the attempt with `lockout/claim-attempts!`, so a wrong
   or blank code stays counted, the same counter as a wrong password (BOU-698).
   Returns `mfa-shell/verify-mfa-code`'s map, or nil for a blank code."
  [auth-service user code]
  (when-not (str/blank? code)
    (mfa-shell/check-code! (:user-repository auth-service) user code)))

(defn authenticate-user
  "Coordinate user authentication with all security checks including MFA.
   
   Args:
     auth-service: AuthenticationService instance
     email: User email
     password: Plain text password
     login-context: Map with :ip-address, :user-agent, :mfa-code (optional)
     
   Returns:
     On success `{:success? true :user … :session …}`.

     On failure `{:success? false :error {:type … :message …}}`, the shape
     ADR-036 §3 decided — a wrong password is an answer, not an exceptional
     condition, and a caller that wants to escalate can rethrow the `:error`
     map as a typed `ex-info` without inventing a taxonomy. `:type` is one of
     `:validation-error`, `:authentication-failed`, `:account-locked`, `:mfa-required`,
     `:mfa-enrolment-required`, `:mfa-verification-failed`; `:account-locked`, and
     an authentication-failed lockout, carry `:retry-after`.

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

          ;; Step 2: Look up user (I/O operation)
          user (when (:valid? credential-validation)
                 (.find-user-by-email user-repository email))

          ;; Step 3: Check if login attempt should be allowed
          login-allowed? (when user
                           (auth-core/should-allow-login-attempt?
                            user auth-config current-time))

          _ (when (and user (not (:allowed? login-allowed?)))
              (log/warn "Login attempt blocked" {:email email :reason (:reason login-allowed?)}))

          ;; Step 4: Count the attempt, then verify the password. Counting
          ;; first is the lockout check parallel attempts cannot race (BOU-785).
          claim (when (and user (:allowed? login-allowed?))
                  (lockout/claim-attempts! user-repository auth-config (:id user) 1 current-time))
          password-valid? (when (:claimed? claim)
                            (verify-password password (:password-hash user)))

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

                   ;; Required, but the user has no second factor to give
                   (:requires-enrolment? mfa-requirement)
                   (do
                     (lockout/release-attempt! user-repository (:id user) claim)
                     (log/warn "MFA required but not enrolled" {:email email})
                     {:success? false
                      :error {:type    :mfa-enrolment-required
                              :message (:reason mfa-requirement)}
                      ;; Who to enrol: the password was right.
                      :user (dissoc user :password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used)})

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

