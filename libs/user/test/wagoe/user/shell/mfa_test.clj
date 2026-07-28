(ns wagoe.user.shell.mfa-test
  "Integration tests for MFA shell layer services.
   
   Tests I/O operations like TOTP generation/verification and service orchestration.
   
   Note: TOTP code verification tests are limited because we cannot easily generate
   valid time-based codes in tests. Real verification is tested in integration tests."
  {:kaocha.testable/meta {:integration true :user true}}
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wagoe.user.shell.mfa :as mfa-shell]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [wagoe.user.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-user
  "Test user entity for MFA tests."
  {:id "test-user-123"
   :email "test@example.com"
   :active true
   :mfa-enabled false
   :mfa-secret nil
   :mfa-backup-codes nil
   :mfa-backup-codes-used nil
   :mfa-enabled-at nil})

(defn create-mock-repository
  "Create mock repository with mutable state for testing."
  []
  (let [user-state (atom test-user)]
    (reify ports/IUserRepository
      (find-user-by-id [_ user-id]
        (when (= user-id (:id @user-state))
          @user-state))

      (update-user [_ user]
        (reset! user-state user)
        @user-state)

      (find-user-by-email [_ email]
        (when (= email (:email @user-state))
          @user-state))

      (find-users [_ _options]
        {:users [@user-state] :total-count 1})

      (create-user [_ user-entity]
        (reset! user-state user-entity)
        @user-state)

      (soft-delete-user [_ _user-id]
        true)

      (hard-delete-user [_ _user-id]
        true)

      (find-active-users-by-role [_ _role]
        [@user-state])

      (count-users [_]
        1)

      (find-users-created-since [_ _since-date]
        [@user-state])

      (find-users-by-email-domain [_ _email-domain]
        [@user-state])

      (create-users-batch [_ user-entities]
        user-entities)

      (update-users-batch [_ user-entities]
        user-entities))))

;; =============================================================================
;; TOTP Secret Generation Tests
;; =============================================================================

(deftest ^:unit ^:security generate-totp-secret-test
  (testing "TOTP secret generation"
    (let [secret (mfa-shell/generate-totp-secret)]

      (testing "generates non-empty secret"
        (is (some? secret))
        (is (string? secret))
        (is (< 0 (count secret))))

      (testing "generates Base32 encoded string"
        (is (re-matches #"[A-Z2-7]+" secret)))

      (testing "generates secrets of appropriate length"
        ;; Base32 encoding of 20 bytes = 32 characters
        (is (>= (count secret) 16)))

      (testing "generates unique secrets on each call"
        (let [secret2 (mfa-shell/generate-totp-secret)]
          (is (not= secret secret2)))))))

;; =============================================================================
;; TOTP Code Verification Tests
;; =============================================================================

(deftest ^:unit ^:security verify-totp-code-test
  (testing "TOTP code verification"
    (let [secret (mfa-shell/generate-totp-secret)]

      (testing "rejects invalid TOTP code"
        (is (false? (mfa-shell/verify-totp-code "000000" secret))))

      (testing "rejects nil code"
        (is (false? (mfa-shell/verify-totp-code nil secret))))

      (testing "rejects empty string code"
        (is (false? (mfa-shell/verify-totp-code "" secret))))

      (testing "rejects non-numeric code"
        (is (false? (mfa-shell/verify-totp-code "ABCDEF" secret))))

      (testing "handles invalid secret gracefully"
        (is (false? (mfa-shell/verify-totp-code "123456" "INVALID")))))))

;; =============================================================================
;; Backup Code Generation Tests
;; =============================================================================

(deftest ^:unit ^:security generate-backup-codes-test
  (testing "Backup code generation"
    (let [codes (mfa-shell/generate-backup-codes 10)]

      (testing "generates requested number of codes"
        (is (= 10 (count codes))))

      (testing "generates unique codes"
        (is (= (count codes) (count (set codes)))))

      (testing "generates alphanumeric codes with dashes"
        (is (every? #(re-matches #"[A-Za-z0-9-]+" %) codes)))

      (testing "generates codes of appropriate length (12+ chars without dashes)"
        (is (every? #(>= (count (str/replace % #"-" "")) 12) codes)))

      (testing "generates different codes on each call"
        (let [codes2 (mfa-shell/generate-backup-codes 10)]
          (is (not= codes codes2)))))))

;; =============================================================================
;; TOTP URI Generation Tests
;; =============================================================================

(deftest ^:unit ^:security generate-totp-uri-test
  (testing "TOTP URI generation"
    (let [secret "JBSWY3DPEHPK3PXP"
          email "test@example.com"
          issuer "Wagoe"
          uri (mfa-shell/generate-totp-uri secret email issuer)]

      (testing "generates otpauth:// URI"
        (is (some? uri))
        (is (.startsWith uri "otpauth://totp/")))

      (testing "includes email in URI (URL-encoded)"
        (is (or (.contains uri email)
                (.contains uri "test%40example.com"))))

      (testing "includes issuer in URI"
        (is (.contains uri issuer)))

      (testing "includes secret in URI"
        (is (.contains uri secret)))

      (testing "properly encodes parameters"
        (is (.contains uri "secret="))
        (is (.contains uri "issuer="))))))

;; =============================================================================
;; QR Code Generation Tests
;; =============================================================================

(deftest ^:unit ^:security generate-qr-code-data-url-test
  (testing "QR code data URL generation"
    (let [secret "JBSWY3DPEHPK3PXP"
          email "test@example.com"
          issuer "Wagoe"
          totp-uri (mfa-shell/generate-totp-uri secret email issuer)
          data-url (mfa-shell/generate-qr-code-data-url totp-uri)]

      (testing "generates base64 PNG data URL"
        (is (some? data-url))
        (is (string? data-url)))

      (testing "URL is a data URL"
        (is (.startsWith data-url "data:image/png;base64,")))

      (testing "data URL contains non-empty base64 payload"
        (is (< (count "data:image/png;base64,") (count data-url)))))))

;; =============================================================================
;; MFA Service Integration Tests
;; =============================================================================

(deftest ^:unit ^:security setup-mfa-service-test
  (testing "MFA setup flow"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)
          result (mfa-shell/setup-mfa service (:id test-user))]

      (testing "setup succeeds for valid user"
        (is (some? result))
        (is (true? (:success? result))))

      (testing "returns secret"
        (is (some? (:secret result)))
        (is (string? (:secret result))))

      (testing "returns backup codes"
        (is (some? (:backup-codes result)))
        (is (= 10 (count (:backup-codes result)))))

      (testing "returns QR code data URL"
        (is (some? (:qr-code-url result)))
        (is (.startsWith (:qr-code-url result) "data:image/png;base64,")))

      (testing "user not yet enabled (requires verification)"
        (let [user (ports/find-user-by-id mock-repo (:id test-user))]
          (is (false? (:mfa-enabled user))))))))

(deftest ^:unit ^:security enable-mfa-invalid-code-test
  (testing "MFA enable with invalid code"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)

          ;; Setup MFA
          setup-result (mfa-shell/setup-mfa service (:id test-user))
          secret (:secret setup-result)
          backup-codes (:backup-codes setup-result)

          ;; Try to enable with invalid code
          enable-result (mfa-shell/enable-mfa service (:id test-user) secret backup-codes "000000")]

      (testing "enable fails with invalid code"
        (is (some? enable-result))
        (is (false? (:success? enable-result)))
        (is (some? (:error enable-result))))

      (testing "user remains not enabled"
        (let [user (ports/find-user-by-id mock-repo (:id test-user))]
          (is (false? (:mfa-enabled user))))))))

(deftest ^:unit ^:security get-mfa-status-service-test
  (testing "MFA status retrieval when disabled"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)
          status (mfa-shell/get-mfa-status service (:id test-user))]

      (testing "status shows MFA disabled"
        (is (some? status))
        (is (false? (:enabled status)))
        (is (nil? (:enabled-at status)))
        (is (= 0 (:backup-codes-remaining status)))))))

(deftest ^:unit ^:security verify-mfa-code-invalid-test
  (testing "MFA code verification with invalid codes"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)

          ;; Setup MFA
          setup-result (mfa-shell/setup-mfa service (:id test-user))
          secret (:secret setup-result)
          backup-codes (:backup-codes setup-result)
          ;; Post-enable state: backup codes are stored bcrypt-hashed (the
          ;; plaintext was shown to the user once), secret is plaintext in the
          ;; entity (encrypted only at the persistence boundary).
          hashed-codes (mapv mfa-crypto/hash-backup-code backup-codes)
          user-with-mfa (merge test-user
                               {:mfa-enabled true
                                :mfa-secret secret
                                :mfa-backup-codes hashed-codes
                                :mfa-backup-codes-used []
                                :mfa-enabled-at (java.time.Instant/now)})]

      (testing "rejects invalid TOTP code"
        (let [result (mfa-shell/verify-mfa-code service user-with-mfa "000000")]
          (is (some? result))
          (is (false? (:valid? result)))))

      (testing "verifies valid backup code (plaintext matched against stored hash)"
        (let [result (mfa-shell/verify-mfa-code service user-with-mfa (first backup-codes))]
          (is (true? (:valid? result)))
          (is (true? (:used-backup-code? result)))
          ;; the recorded 'used' value is the matched hash, not the plaintext code
          (let [used (get-in result [:updates :mfa-backup-codes-used])]
            (is (mfa-crypto/backup-code-matches? (first backup-codes) (last used))))))

      (testing "rejects an already-used backup code"
        (let [user-with-used-code (update user-with-mfa :mfa-backup-codes-used conj (first hashed-codes))
              result (mfa-shell/verify-mfa-code service user-with-used-code (first backup-codes))]
          (is (false? (:valid? result))))))))
(deftest ^:unit ^:security setup-mfa-user-not-found-test
  (testing "MFA setup with non-existent user"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)
          result (mfa-shell/setup-mfa service "non-existent-user-id")]

      (testing "returns error for missing user"
        (is (some? result))
        (is (false? (:success? result)))
        (is (= "User not found" (:error result)))))))

(deftest ^:unit ^:security enable-mfa-without-setup-test
  (testing "MFA enable without prior setup (missing secret/codes)"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)
          ;; Try to enable without calling setup first
          result (mfa-shell/enable-mfa service (:id test-user) "fake-secret" ["CODE1"] "123456")]

      (testing "returns error when MFA not properly set up"
        (is (some? result))
        (is (false? (:success? result)))
        (is (some? (:error result)))))))

(deftest ^:unit ^:security disable-mfa-when-not-enabled-test
  (testing "MFA disable when not enabled"
    (let [mock-repo (create-mock-repository)
          config {:issuer "TestApp"}
          service (mfa-shell/create-mfa-service mock-repo config)
          result (mfa-shell/disable-mfa service (:id test-user))]

      (testing "returns error when MFA not enabled"
        (is (some? result))
        (is (false? (:success? result)))
        (is (= "MFA is not enabled" (:error result)))))))
