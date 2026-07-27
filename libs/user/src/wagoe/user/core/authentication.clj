(ns wagoe.user.core.authentication
  "Functional Core - Pure authentication business logic.
   
   This namespace contains ONLY pure functions for authentication:
   - Credential validation logic
   - Authentication decision making
   - Login attempt analysis
   - Account lockout logic
   
   All functions are pure and deterministic - no I/O operations.
   Password hashing and token operations are handled in the shell layer."
  (:require [wagoe.user.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m])
  (:import (java.time Duration)))

;; =============================================================================
;; Authentication Business Rules
;; =============================================================================

(def ^:private login-request-validator (m/validator schema/LoginRequest))
(def ^:private login-request-explainer (m/explainer schema/LoginRequest))

(defn validate-login-credentials
  "Pure function: Validate login request format and basic requirements.
   
   Args:
     login-data: Map with :email and :password
     
   Returns:
     {:valid? true :data processed-data} or 
     {:valid? false :errors [...]}
     
   Pure - schema and format validation only."
  [login-data]
  (if (login-request-validator login-data)
    {:valid? true :data login-data}
    {:valid? false
     :errors (login-request-explainer login-data)}))

(defn should-allow-login-attempt?
  "Pure function: Determine if login attempt should be allowed based on account state.
   
   Args:
     user: User entity (or nil if not found)
     login-config: Configuration map with lockout policies
     current-time: Current timestamp (Instant)
     
   Returns:
     {:allowed? true} or 
     {:allowed? false :reason string :retry-after optional-instant}
     
    Pure - business rule evaluation for login attempts."
  [user _login-config current-time]
  (cond
    ;; User doesn't exist
    (nil? user)
    {:allowed? false :reason "Invalid credentials"}

    ;; User is inactive
    (not (:active user))
    {:allowed? false :reason "Account is deactivated"}

    ;; User is deleted
    (:deleted-at user)
    {:allowed? false :reason "Account no longer exists"}

    ;; Check if account is locked due to failed attempts
    (and (:lockout-until user)
         (.isBefore current-time (:lockout-until user)))
    {:allowed? false
     :reason "Account temporarily locked due to failed login attempts"
     :retry-after (:lockout-until user)}

    ;; Login allowed
    :else
    {:allowed? true}))

(defn calculate-failed-login-consequences
  "Pure function: Calculate consequences of a failed login attempt.
   
   Args:
     user: User entity with current failed login count
     login-config: Configuration with lockout policies
     current-time: Current timestamp
     
   Returns:
     {:lockout-until optional-instant
      :failed-login-count new-count
      :should-alert? boolean}
      
   Pure - business rule calculations for security policies."
  [user login-config current-time]
  (let [current-failures (or (:failed-login-count user) 0)
        new-failures (inc current-failures)
        max-attempts (get login-config :max-failed-attempts 5)
        lockout-duration-minutes (get login-config :lockout-duration-minutes 15)
        alert-threshold (get login-config :alert-threshold 3)]

    (cond-> {:failed-login-count new-failures
             :should-alert? (>= new-failures alert-threshold)}

      ;; Lock account if max attempts exceeded
      (>= new-failures max-attempts)
      (assoc :lockout-until (.plus current-time
                                   (Duration/ofMinutes lockout-duration-minutes))))))

(defn prepare-successful-login-updates
  "Pure function: Prepare user updates for successful login.
   
   Args:
     user: User entity
     current-time: Current timestamp
     
   Returns:
     Map of field updates to apply to user
     
   Pure - data transformation for successful authentication."
  [user current-time]
  {:last-login current-time
   :login-count (inc (or (:login-count user) 0))
   :failed-login-count 0 ; Reset failed attempts
   :lockout-until nil}) ; Clear any lockout

(defn analyze-login-risk
  "Pure function: Analyze risk factors for a login attempt.
   
   Args:
     user: User entity
     login-context: Map with :ip-address, :user-agent, etc.
     recent-sessions: Recent session history
     now: Current timestamp provided by shell layer
     
   Returns:
     {:risk-score 0-100
      :risk-factors [...]
      :requires-mfa? boolean}
      
   Pure - risk assessment based on patterns and context."
  [user login-context recent-sessions now]
  (let [risk-factors (cond-> []
                       ;; New IP address
                       (not-any? #(= (:ip-address login-context)
                                     (:ip-address %))
                                 recent-sessions)
                       (conj {:factor :new-ip-address :weight 30})

                       ;; New user agent
                       (not-any? #(= (:user-agent login-context)
                                     (:user-agent %))
                                 recent-sessions)
                       (conj {:factor :new-user-agent :weight 20})

                       ;; Admin user (higher scrutiny)
                       (= :admin (:role user))
                       (conj {:factor :admin-user :weight 25})

                       ;; No recent activity (dormant account)
                       (and (:last-login user)
                            (> (.toEpochMilli (.minus now
                                                      (Duration/ofDays 30)))
                               (.toEpochMilli (:last-login user))))
                       (conj {:factor :dormant-account :weight 40}))

        risk-score (reduce + 0 (map :weight risk-factors))
        requires-mfa? (> risk-score 50)]

    {:risk-score risk-score
     :risk-factors risk-factors
     :requires-mfa? requires-mfa?}))

(defn should-create-session?
  "Pure function: Determine if a session should be created for authenticated user.
   
   Args:
     user: Authenticated user entity
     login-risk: Risk analysis from analyze-login-risk
     auth-config: Authentication configuration
     
   Returns:
     {:create-session? boolean
      :session-duration-hours number
      :requires-additional-verification? boolean}
      
   Pure - session creation policy decisions."
  [user login-risk auth-config]
  (let [base-duration (get auth-config :default-session-hours 24)
        high-risk? (> (:risk-score login-risk) 70)
        admin-user? (= :admin (:role user))]

    {:create-session? true ; Basic auth always creates session
     :session-duration-hours (cond
                               high-risk? 1 ; Short sessions for risky logins
                               admin-user? 8 ; Shorter admin sessions
                               :else base-duration)
     :requires-additional-verification? (:requires-mfa? login-risk)}))

;; =============================================================================
;; Password Policy Validation
;; =============================================================================

(defn meets-password-policy?
  "Pure function: Check if password meets security policy requirements.
   
   Args:
     password: Plain text password
     policy: Password policy configuration
     user-context: Optional user context for personalized checks
     
   Returns:
     {:valid? boolean :violations [...]}
     
   Pure - password policy validation."
  [password policy user-context]
  (let [violations (cond-> []
                     ;; Length requirements
                     (< (count password) (get policy :min-length 8))
                     (conj {:code :too-short
                            :message (str "Must be at least " (get policy :min-length 8) " characters")})

                     (> (count password) (get policy :max-length 255))
                     (conj {:code :too-long
                            :message (str "Must be no more than " (get policy :max-length 255) " characters")})

                     ;; Character requirements
                     (and (get policy :require-uppercase false)
                          (not (re-find #"[A-Z]" password)))
                     (conj {:code :missing-uppercase
                            :message "Must contain at least one uppercase letter"})

                     (and (get policy :require-lowercase false)
                          (not (re-find #"[a-z]" password)))
                     (conj {:code :missing-lowercase
                            :message "Must contain at least one lowercase letter"})

                     (and (get policy :require-numbers true)
                          (not (re-find #"\d" password)))
                     (conj {:code :missing-number
                            :message "Must contain at least one number"})

                     (and (get policy :require-special-chars false)
                          (not (re-find #"[!@#$%^&*(),.?\":{}|<>]" password)))
                     (conj {:code :missing-special-char
                            :message "Must contain at least one special character"})

                     ;; Common password checks
                     (some #(str/includes? (str/lower-case password)
                                           (str/lower-case %))
                           (get policy :forbidden-patterns #{}))
                     (conj {:code :common-password
                            :message "Password is too common or predictable"})

                     ;; User-specific checks
                     (and user-context
                          (:email user-context)
                          (str/includes? (str/lower-case password)
                                         (str/lower-case (first (str/split (:email user-context) #"@")))))
                     (conj {:code :contains-email
                            :message "Password cannot contain your email address"}))]

    {:valid? (empty? violations)
     :violations violations}))

;; =============================================================================
;; Account Security Functions
;; =============================================================================

(defn should-require-password-reset?
  "Pure function: Determine if user should be required to reset password.
   
   Args:
     user: User entity
     current-time: Current timestamp
     policy: Password policy configuration
     
   Returns:
     {:requires-reset? boolean :reason optional-string}
     
   Pure - password reset policy evaluation."
  [user current-time policy]
  (let [max-age-days (get policy :max-password-age-days 90)
        password-created (:password-created-at user)
        days-since-creation (when password-created
                              (.toDays (Duration/between password-created current-time)))]

    (cond
      ;; No password set (should not happen, but defensive)
      (not (:password-hash user))
      {:requires-reset? true :reason "No password set"}

      ;; Password expired
      (and days-since-creation
           (> days-since-creation max-age-days))
      {:requires-reset? true :reason "Password has expired"}

      ;; Account was compromised (would be set by admin)
      (:force-password-reset user)
      {:requires-reset? true :reason "Password reset required by administrator"}

      ;; No reset required
      :else
      {:requires-reset? false})))

(defn calculate-password-strength
  "Pure function: Calculate password strength score.
   
   Args:
     password: Plain text password
     
   Returns:
     {:strength-score 0-100
      :strength-level :weak|:moderate|:strong|:very-strong
      :feedback [...]}
      
   Pure - password strength analysis."
  [password]
  (let [length (count password)
        has-lower? (boolean (re-find #"[a-z]" password))
        has-upper? (boolean (re-find #"[A-Z]" password))
        has-numbers? (boolean (re-find #"\d" password))
        has-special? (boolean (re-find #"[!@#$%^&*(),.?\":{}|<>]" password))
        unique-chars (count (distinct password))

        ;; Score components
        length-score (min 25 (* length 2))
        variety-score (* 15 (count (filter identity [has-lower? has-upper? has-numbers? has-special?])))
        uniqueness-score (min 20 (* unique-chars 2))

        total-score (+ length-score variety-score uniqueness-score)

        strength-level (cond
                         (< total-score 30) :weak
                         (< total-score 60) :moderate
                         (< total-score 80) :strong
                         :else :very-strong)

        feedback (cond-> []
                   (< length 8) (conj "Use at least 8 characters")
                   (< length 12) (conj "Consider using 12+ characters for better security")
                   (not has-lower?) (conj "Include lowercase letters")
                   (not has-upper?) (conj "Include uppercase letters")
                   (not has-numbers?) (conj "Include numbers")
                   (not has-special?) (conj "Include special characters (!@#$%^&*)")
                   (< unique-chars 6) (conj "Use more varied characters"))]

    {:strength-score total-score
     :strength-level strength-level
     :feedback feedback}))
