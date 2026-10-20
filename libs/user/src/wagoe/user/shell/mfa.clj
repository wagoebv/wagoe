(ns wagoe.user.shell.mfa
  "Shell Layer - MFA operations with side effects.
   
   This is the SHELL layer for MFA operations:
   - TOTP secret generation
   - TOTP code verification
   - Backup code generation (secure random)
   - QR code generation for authenticator apps
   
   All I/O and side effects happen here. Pure business logic
   is delegated to wagoe.user.core.mfa."
  (:require [wagoe.user.core.audit :as audit-core]
            [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.core.mfa :as mfa-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [buddy.hashers :as hashers]
            [one-time.core :as otp]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.security SecureRandom)
           (java.net URLEncoder)
           (java.time Instant)
           (java.util Base64 Date)
           (java.io ByteArrayOutputStream)
           (org.apache.commons.codec.binary Base32)
           (com.google.zxing BarcodeFormat)
           (com.google.zxing.qrcode QRCodeWriter)
           (com.google.zxing.client.j2se MatrixToImageWriter)))

;; =============================================================================
;; TOTP Operations (Side Effects)
;; =============================================================================

(defn generate-totp-secret
  "Shell function: Generate a new TOTP secret.
   
   Returns:
     Base32-encoded secret string for TOTP
     
   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        bytes (byte-array 20) ; 160 bits = 20 bytes
        base32 (Base32.)]
    (.nextBytes random bytes)
    (.encodeAsString base32 bytes)))

(def ^:dynamic *now*
  "The Instant TOTP codes are checked at; nil reads the clock. Tests bind it
   so a code they generate cannot fall into the next 30 s window."
  nil)

(defn verify-totp-code
  "Shell function: Verify TOTP code against secret.
   
   Args:
     code: 6-digit TOTP code (string)
     secret: Base32-encoded TOTP secret
     
   Returns:
     Boolean - true if code is valid
     
   Side effects: Time-dependent verification"
  [code secret]
  (try
    (if (and code secret)
      (let [code-int (Integer/parseInt (str/trim code))]
        (boolean (otp/is-valid-totp-token? code-int secret
                                           {:date (Date/from (or *now* (Instant/now)))})))
      false) ; Explicit false instead of nil when inputs invalid
    (catch Exception _e
      false)))

(defn generate-totp-uri
  "Shell function: Generate TOTP URI for QR code.
   
   Args:
     secret: Base32-encoded TOTP secret
     account-name: User identifier (usually email)
     issuer: Application name
     
   Returns:
     TOTP URI string for QR code generation
     
   Side effects: None (pure string generation)"
  [secret account-name issuer]
  (str "otpauth://totp/"
       (URLEncoder/encode issuer "UTF-8")
       ":"
       (URLEncoder/encode account-name "UTF-8")
       "?secret=" secret
       "&issuer=" (URLEncoder/encode issuer "UTF-8")
       "&algorithm=SHA1"
       "&digits=6"
       "&period=30"))

(defn generate-qr-code-data-url
  "Shell function: Generate QR code as a base64-encoded PNG data URL.

   Args:
     totp-uri: TOTP URI string

   Returns:
     Data URL string (data:image/png;base64,...) for use in <img> src attribute.
     Generated locally using ZXing — no external service required."
  [totp-uri]
  (let [writer (QRCodeWriter.)
        bit-matrix (.encode writer totp-uri BarcodeFormat/QR_CODE 200 200)
        baos (ByteArrayOutputStream.)]
    (MatrixToImageWriter/writeToStream bit-matrix "PNG" baos)
    (str "data:image/png;base64,"
         (.encodeToString (Base64/getEncoder) (.toByteArray baos)))))

;; =============================================================================
;; Backup Code Operations (Side Effects)
;; =============================================================================

(defn generate-backup-code
  "Shell function: Generate a single secure backup code.
   
  Returns:
     12-character alphanumeric backup code
     
   Side effects: Secure random generation"
  []
  (let [random (SecureRandom.)
        alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        alphabet-length (count alphabet)]
    (apply str
           (repeatedly 12
                       (fn []
                         (.charAt alphabet (.nextInt random alphabet-length)))))))

(defn generate-backup-codes
  "Shell function: Generate multiple backup codes.
   
   Args:
     count: Number of backup codes to generate (default 10)
     
   Returns:
     Vector of formatted backup codes
     
   Side effects: Secure random generation"
  ([] (generate-backup-codes 10))
  ([count]
   (vec (repeatedly count #(mfa-core/format-backup-code (generate-backup-code))))))

;; =============================================================================
;; MFA Service Coordination
;; =============================================================================

(defrecord MFAService [user-repository config])

(defn create-mfa-service
  "Factory function: Create MFA service instance.
   
   Args:
     user-repository: IUserRepository implementation
     config: MFA configuration map
     opts: optional :audit-repository, which records a disable, and
           :auth-config, whose lockout policy a wrong disable proof counts
           towards
     
   Returns:
     MFAService instance"
  ([user-repository config]
   (create-mfa-service user-repository config {}))
  ([user-repository config opts]
   (merge (->MFAService user-repository config)
          (select-keys opts [:audit-repository :auth-config]))))

(defn setup-mfa
  "Initiate MFA setup for a user.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user setting up MFA
     
   Returns:
     `{:success? true :secret … :qr-code-url … :backup-codes …}`, or
     `{:success? false :error {:type … :message …}}` — the ADR-036 §3 shape.
     `:type` is `:cannot-enable` or `:mfa-setup-failed`."
  [mfa-service user-id]
  (let [{:keys [user-repository config]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)
            can-enable (mfa-core/can-enable-mfa? user)]
        (if (:can-enable? can-enable)
          ;; Generate MFA setup data
          (let [secret (generate-totp-secret)
                backup-codes (generate-backup-codes 10)
                issuer (get config :issuer "Wagoe Framework")
                account-name (:email user)
                totp-uri (generate-totp-uri secret account-name issuer)
                qr-code-url (generate-qr-code-data-url totp-uri)]
            {:success? true
             :secret secret
             :qr-code-url qr-code-url
             :backup-codes backup-codes
             :issuer issuer
             :account-name account-name})
          ;; Cannot enable MFA
          {:success? false
           :error {:type    :cannot-enable
                   :message (:reason can-enable)}}))
      (catch Exception e
        (log/error e "MFA setup failed")
        {:success? false
         :error {:type    :mfa-setup-failed
                 :message "MFA setup failed"}}))))

(defn enable-mfa
  "Enable MFA for a user after verifying setup.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user enabling MFA
     secret: TOTP secret from setup
     backup-codes: Backup codes from setup
     verification-code: 6-digit code from authenticator app
     
   Returns:
     `{:success? true}`, or `{:success? false :error {:type … :message …}}` —
     the ADR-036 §3 shape. A code that does not match is an answer, not an
     exceptional condition. `:type` is `:invalid-code`, `:cannot-enable` or
     `:mfa-enable-failed`."
  [mfa-service user-id secret backup-codes verification-code]
  (let [{:keys [user-repository]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)
            can-enable (mfa-core/can-enable-mfa? user)]
        (if (:can-enable? can-enable)
          ;; Verify the code before enabling
          (if (verify-totp-code verification-code secret)
            ;; Enable MFA. Backup codes are bcrypt-hashed for storage (the
            ;; plaintext was shown to the user once at setup); the TOTP secret is
            ;; stored plaintext in the entity and encrypted at the persistence
            ;; boundary.
            (let [current-time (Instant/now)
                  hashed-codes (mapv mfa-crypto/hash-backup-code backup-codes)
                  updates (mfa-core/prepare-mfa-enablement
                           user secret hashed-codes current-time)]
              (log/info "Updating user with MFA"
                        {:user-id (:id user)
                         :mfa-enabled (:mfa-enabled updates)
                         :has-secret (boolean (:mfa-secret updates))
                         :backup-codes-count (count (:mfa-backup-codes updates))})
              (.update-user user-repository (assoc updates :id (:id user)))
              {:success? true})
            ;; Invalid verification code
            {:success? false
             :error {:type    :invalid-code
                     :message "Invalid verification code"}})
          ;; Cannot enable MFA
          {:success? false
           :error {:type    :cannot-enable
                   :message (:reason can-enable)}}))
      (catch Exception e
        (log/error e "MFA enable failed")
        {:success? false
         :error {:type    :mfa-enable-failed
                 :message "Enabling MFA failed"}}))))

(defn- find-unused-backup-code-hash
  "Return the stored bcrypt hash that the presented plaintext `code` matches and
   that has not already been used, or nil. Constant-time per candidate."
  [user code]
  (let [hashes (or (:mfa-backup-codes user) [])
        used   (set (or (:mfa-backup-codes-used user) []))]
    (some (fn [h]
            (when (and (not (contains? used h))
                       (mfa-crypto/backup-code-matches? code h))
              h))
          hashes)))

(defn verify-mfa-code
  "Verify MFA code for authentication.
   
   Args:
     mfa-service: MFAService instance
     user: User entity
     code: 6-digit TOTP code or backup code
     
   Returns:
     {:valid? boolean
      :used-backup-code? boolean
      :code-hash string (the matched backup code's stored hash)}

   Spending the backup code is the caller's: `ports/consume-backup-code`."
  [_mfa-service user code]
  (cond
      ;; Try TOTP verification first
    (verify-totp-code code (:mfa-secret user))
    {:valid? true
     :used-backup-code? false}

      ;; Try backup code — hash-verify the presented code against stored bcrypt
      ;; hashes, excluding already-used ones. The matched hash (not the plaintext)
      ;; is what gets recorded as used.
    :else
    (if-let [used-hash (find-unused-backup-code-hash user code)]
      {:valid? true
       :used-backup-code? true
       :code-hash used-hash}
      {:valid? false
       :used-backup-code? false})))

(defn- spend-backup-code!
  "Spend hash `h`. The write is conditional on the codes being as read; when a
   parallel login changed them, it is tried again against a fresh read, unless
   that login spent `h`, which makes the code wrong here (BOU-708). Each lost
   write means another code was spent, so it ends within one try per code."
  [user-repository user h]
  (loop [user user, tries (count (:mfa-backup-codes user))]
    (or (ports/consume-backup-code user-repository user h)
        (when-let [fresh (and (pos? tries) (ports/find-user-by-id user-repository (:id user)))]
          (and (= (:mfa-backup-codes fresh) (:mfa-backup-codes user))
               (not (some #{h} (:mfa-backup-codes-used fresh)))
               (recur fresh (dec tries)))))))

(defn check-code!
  "Verify `code`, spending it when it is a backup code."
  [user-repository user code]
  (let [check (verify-mfa-code nil user code)]
    (if (or (not (:used-backup-code? check))
            (spend-backup-code! user-repository user (:code-hash check)))
      check
      {:valid? false :used-backup-code? false})))

(defn claim-attempts!
  "Count `n` attempts for `user-id` before checking them, unless the account is
   locked: the count is the lockout check, so parallel attempts cannot exceed
   the limit (BOU-785). Returns {:claimed? true :locked? b}, :locked? when this
   claim reached the limit, or {:claimed? false :retry-after inst}."
  [user-repository auth-config user-id n current-time]
  (let [lockout (auth-core/failed-login-lockout auth-config current-time)
        claim   (ports/claim-login-attempts user-repository user-id n lockout)]
    (if (:claimed? claim)
      {:claimed? true :locked? (>= (:failed-login-count claim) (:max-attempts lockout))}
      {:claimed? false :retry-after (or (:lockout-until claim) (:until lockout))})))

(defn release-attempt!
  "Take back an attempt `claim-attempts!` counted whose password was right,
   and the lockout it set, if it set one."
  [user-repository user-id claim]
  (ports/release-login-attempt user-repository user-id (:locked? claim)))

(defn locked-error
  "The ADR-036 refusal for an account locked until `retry-after`."
  [retry-after]
  {:type        :account-locked
   :message     "Too many failed attempts, try again later"
   :retry-after retry-after})

(defn- proof-holds?
  "Whether `password` is the user's, or `code` a current TOTP or unused backup
   code, which it spends. The password goes first so a backup code is not
   spent when it was not needed."
  [user-repository user {:keys [password code]}]
  (boolean (or (and (not (str/blank? password)) (:password-hash user)
                    (hashers/check password (:password-hash user)))
               (and (not (str/blank? code))
                    (:valid? (check-code! user-repository user code))))))

(defn- audit-disable!
  "Record the disable; a failed write is logged and does not undo it."
  [audit-repository user {:keys [ip-address user-agent]}]
  (when audit-repository
    (try
      (ports/create-audit-log audit-repository
                              (audit-core/mfa-disabled-audit-entry (:id user) (:email user)
                                                                   ip-address user-agent))
      (catch Exception e
        (log/warn e "Could not audit an MFA disable" {:user-id (:id user)})))))

(defn disable-mfa
  "Turn MFA off for `user-id` once they prove it is them: `proof` is
   `{:password …}` or `{:code …}`, a current TOTP or backup code, the only
   proof a user without a password has. A wrong proof counts as a failed
   login, so guessing locks the account as at sign-in, and a locked account
   cannot disable. `request-context` carries :ip-address and :user-agent for
   the audit entry.

   Returns `{:success? true}`, or `{:success? false :error {:type … :message …}}`
   — the ADR-036 §3 shape. `:type` is `:cannot-disable`, `:proof-required`,
   `:invalid-proof`, `:account-locked` (with `:retry-after`) or
   `:mfa-disable-failed`."
  [mfa-service user-id proof request-context]
  (let [{:keys [user-repository audit-repository auth-config]} mfa-service]
    (try
      (let [now     (Instant/now)
            user    (ports/find-user-by-id user-repository user-id)
            can     (mfa-core/can-disable-mfa? user)
            allowed (when user (auth-core/should-allow-login-attempt? user auth-config now))]
        (cond
          (not (:can-disable? can))
          {:success? false :error {:type :cannot-disable :message (:reason can)}}

          (:retry-after allowed)
          {:success? false :error (locked-error (:retry-after allowed))}

          (not (:allowed? allowed))
          {:success? false :error {:type :cannot-disable :message (:reason allowed)}}

          (and (str/blank? (:password proof)) (str/blank? (:code proof)))
          {:success? false :error {:type    :proof-required
                                   :message "Confirm with your password or an authentication code"}}

          :else
          ;; One attempt per field given: a request is not two guesses for one.
          (let [claim (claim-attempts! user-repository auth-config user-id
                                       (count (remove str/blank? [(:password proof) (:code proof)]))
                                       now)]
            (cond
              (not (:claimed? claim))
              {:success? false :error (locked-error (:retry-after claim))}

              (not (proof-holds? user-repository user proof))
              {:success? false :error {:type :invalid-proof :message "Incorrect password or code"}}

              :else
              (do (ports/update-user user-repository
                                     (assoc (mfa-core/prepare-mfa-disablement user)
                                            :id (:id user) :failed-login-count 0 :lockout-until nil))
                  (audit-disable! audit-repository user request-context)
                  {:success? true})))))
      (catch Exception e
        (log/error e "MFA disable failed")
        {:success? false
         :error {:type    :mfa-disable-failed
                 :message "Disabling MFA failed"}}))))

(defn get-mfa-status
  "Get MFA status for a user.
   
   Args:
     mfa-service: MFAService instance
     user-id: UUID of user
     
   Returns:
     MFA status map"
  [mfa-service user-id]
  (let [{:keys [user-repository]} mfa-service]
    (try
      (let [user (.find-user-by-id user-repository user-id)]
        (if user
          (mfa-core/get-mfa-status user)
          {:enabled false
           :backup-codes-remaining 0}))
      (catch Exception e
        (log/error e "MFA status lookup failed")
        {:enabled false
         :backup-codes-remaining 0
         :error "MFA status unavailable"}))))
