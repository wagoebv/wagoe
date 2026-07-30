(ns wagoe.user.core.mfa
  "Functional Core - Pure MFA business logic.
   
   This namespace contains ONLY pure functions for MFA:
   - MFA decision logic (when to require MFA)
   - Backup code validation logic
   - MFA setup preparation
   - MFA status evaluation
   
   All functions are pure and deterministic - no I/O operations.
   TOTP generation and cryptographic operations are handled in the shell layer."
  (:require [clojure.string :as str]))

;; =============================================================================
;; MFA Business Rules
;; =============================================================================

(defn should-require-mfa?
  "Pure function: Determine if MFA should be required for login.
   
   Args:
     user: User entity with MFA settings
     risk-analysis: Risk analysis from authentication core
     
   Returns:
     Boolean - true if MFA should be required
     
    Pure - business rule evaluation for MFA requirement."
  [user _risk-analysis]
  ;; Always require MFA if enabled
  ;; Could add risk-based logic here using risk-analysis
  (:mfa-enabled user))

(defn can-enable-mfa?
  "Pure function: Check if user can enable MFA.
   
   Args:
     user: User entity
     
   Returns:
     {:can-enable? boolean :reason optional-string}
     
   Pure - business rule for MFA enablement."
  [user]
  (cond
    ;; User doesn't exist
    (nil? user)
    {:can-enable? false :reason "User not found"}

    ;; MFA already enabled
    (:mfa-enabled user)
    {:can-enable? false :reason "MFA is already enabled"}

    ;; User is not active
    (not (:active user))
    {:can-enable? false :reason "User account is not active"}

    ;; User is deleted
    (:deleted-at user)
    {:can-enable? false :reason "User account is deleted"}

    ;; Can enable MFA
    :else
    {:can-enable? true}))

(defn can-disable-mfa?
  "Pure function: Check if user can disable MFA.
   
   Args:
     user: User entity
     
   Returns:
     {:can-disable? boolean :reason optional-string}
     
   Pure - business rule for MFA disablement."
  [user]
  (cond
    ;; User doesn't exist
    (nil? user)
    {:can-disable? false :reason "User not found"}

    ;; MFA not enabled
    (not (:mfa-enabled user))
    {:can-disable? false :reason "MFA is not enabled"}

    ;; Can disable MFA
    :else
    {:can-disable? true}))

(defn prepare-mfa-enablement
  "Pure function: Prepare user updates for MFA enablement.
   
   Args:
     user: User entity
     mfa-secret: Base32-encoded TOTP secret
     backup-codes: Vector of backup codes
     current-time: Current timestamp
     
   Returns:
     Map of field updates to apply to user
     
    Pure - data transformation for MFA setup."
  [_user mfa-secret backup-codes current-time]
  {:mfa-enabled true
   :mfa-secret mfa-secret
   :mfa-backup-codes backup-codes
   :mfa-backup-codes-used []
   :mfa-enabled-at current-time})

(defn prepare-mfa-disablement
  "Pure function: Prepare user updates for MFA disablement.
   
   Args:
     user: User entity
     
   Returns:
     Map of field updates to apply to user
     
    Pure - data transformation for MFA removal."
  [_user]
  {:mfa-enabled false
   :mfa-secret nil
   :mfa-backup-codes nil
   :mfa-backup-codes-used nil
   :mfa-enabled-at nil})

;; =============================================================================
;; Backup Code Logic
;; =============================================================================

;; NOTE: backup-code verification is no longer a pure plaintext comparison.
;; Codes are stored bcrypt-hashed, so matching a presented code against the
;; stored hashes (and recording the used hash) is a shell concern —
;; see wagoe.user.shell.mfa/verify-mfa-code.

(defn count-remaining-backup-codes
  "Pure function: Count remaining unused backup codes.
   
   Args:
     user: User entity
     
   Returns:
     Integer count of remaining backup codes
     
   Pure - backup code inventory."
  [user]
  (let [backup-codes (or (:mfa-backup-codes user) [])
        used-codes (or (:mfa-backup-codes-used user) [])]
    (- (count backup-codes) (count used-codes))))

(defn should-regenerate-backup-codes?
  "Pure function: Determine if backup codes should be regenerated.
   
   Args:
     user: User entity
     threshold: Minimum number of backup codes before regeneration alert
     
   Returns:
     Boolean - true if backup codes should be regenerated
     
   Pure - backup code lifecycle policy."
  [user threshold]
  (< (count-remaining-backup-codes user) threshold))

;; =============================================================================
;; MFA Setup Logic
;; =============================================================================

(defn generate-backup-codes-pattern
  "Pure function: Generate pattern for backup codes.
   
   Args:
     count: Number of backup codes to generate
     
   Returns:
     Vector of integers representing required code count
     
   Pure - backup code generation template.
   Actual random generation happens in shell layer."
  [count]
  (vec (range count)))

(defn format-backup-code
  "Pure function: Format backup code for display.
   
   Args:
     code: Raw backup code string
     
   Returns:
     Formatted backup code string (e.g., 'XXXX-XXXX-XXXX')
     
   Pure - backup code formatting."
  [code]
  (let [clean-code (str/replace code #"[^A-Za-z0-9]" "")
        uppercase-code (str/upper-case clean-code)]
    (if (>= (count uppercase-code) 12)
      (str (subs uppercase-code 0 4) "-"
           (subs uppercase-code 4 8) "-"
           (subs uppercase-code 8 12))
      uppercase-code)))

(defn validate-backup-codes-format
  "Pure function: Validate backup codes have correct format.
   
   Args:
     backup-codes: Vector of backup codes
     
   Returns:
     {:valid? boolean :errors [...]}
     
   Pure - backup code format validation."
  [backup-codes]
  (let [errors (cond-> []
                 ;; Must have codes
                 (empty? backup-codes)
                 (conj "No backup codes provided")

                 ;; Each code must be proper length (without dashes)
                 (some #(< (count (str/replace % #"-" "")) 12) backup-codes)
                 (conj "Some backup codes are too short")

                 ;; Each code must be alphanumeric
                 (some #(not (re-matches #"[A-Za-z0-9-]+" %)) backup-codes)
                 (conj "Some backup codes contain invalid characters"))]
    {:valid? (empty? errors)
     :errors errors}))

;; =============================================================================
;; MFA Status Functions
;; =============================================================================

(defn get-mfa-status
  "Pure function: Get current MFA status for user.
   
   Args:
     user: User entity
     
   Returns:
     {:enabled boolean
      :enabled-at optional-instant
      :backup-codes-remaining integer}
     
   Pure - MFA status aggregation."
  [user]
  {:enabled (boolean (:mfa-enabled user))
   :enabled-at (:mfa-enabled-at user)
   :backup-codes-remaining (if (:mfa-enabled user)
                             (count-remaining-backup-codes user)
                             0)})

(defn is-mfa-setup-complete?
  "Pure function: Check if MFA setup is complete.
   
   Args:
     user: User entity
     
   Returns:
     Boolean - true if MFA is properly set up
     
   Pure - MFA setup completeness check."
  [user]
  (and (:mfa-enabled user)
       (:mfa-secret user)
       (seq (:mfa-backup-codes user))))

;; =============================================================================
;; Login Flow Logic
;; =============================================================================

(defn determine-mfa-requirement
  "Pure function: Determine MFA requirement for login attempt.
   
   Args:
     user: User entity
     password-valid?: Boolean indicating if password was valid
     mfa-code: Optional MFA code provided
     risk-analysis: Risk analysis from authentication
     
   Returns:
     {:requires-mfa? boolean
      :mfa-verified? boolean-or-pending  ; :pending when shell verification needed
      :allow-login? boolean-or-pending   ; :pending when shell verification needed
      :reason string}
     
    Pure - MFA login flow decision logic."
  [user password-valid? mfa-code _risk-analysis]
  (cond
    ;; Password invalid - don't proceed
    (not password-valid?)
    {:requires-mfa? false
     :mfa-verified? false
     :allow-login? false
     :reason "Invalid password"}

    ;; MFA not enabled - allow login
    (not (:mfa-enabled user))
    {:requires-mfa? false
     :mfa-verified? true
     :allow-login? true
     :reason "MFA not enabled"}

    ;; MFA enabled but no code provided - require MFA
    (and (:mfa-enabled user) (nil? mfa-code))
    {:requires-mfa? true
     :mfa-verified? false
     :allow-login? false
     :reason "MFA code required"}

    ;; MFA enabled and code provided - verification needed (done in shell)
    (and (:mfa-enabled user) (some? mfa-code))
    {:requires-mfa? true
     :mfa-verified? :pending ; Actual verification in shell layer
     :allow-login? :pending
     :reason "MFA code verification pending"}))
