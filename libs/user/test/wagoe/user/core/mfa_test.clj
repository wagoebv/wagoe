(ns wagoe.user.core.mfa-test
  "Unit tests for MFA core business logic.
   
   Tests pure functions in wagoe.user.core.mfa with no I/O or side effects."
  {:kaocha.testable/meta {:unit true :user true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.mfa :as mfa])
  (:import [java.time Instant]))

;; =============================================================================
;; MFA Requirement Tests
;; =============================================================================

(deftest ^:unit ^:security should-require-mfa-test
  (testing "MFA requirement determination"
    (testing "requires MFA when enabled for user"
      (let [user {:mfa-enabled true}
            risk-analysis {:risk-level :low}
            result (mfa/should-require-mfa? user risk-analysis)]
        (is (true? result))))

    (testing "does not require MFA when disabled"
      (let [user {:mfa-enabled false}
            risk-analysis {:risk-level :low}
            result (mfa/should-require-mfa? user risk-analysis)]
        (is (or (false? result) (nil? result)))))

    (testing "does not require MFA when nil"
      (let [user {:mfa-enabled nil}
            risk-analysis {:risk-level :low}
            result (mfa/should-require-mfa? user risk-analysis)]
        (is (or (false? result) (nil? result)))))

    (testing "does not require MFA when key missing"
      (let [user {}
            risk-analysis {:risk-level :low}
            result (mfa/should-require-mfa? user risk-analysis)]
        (is (or (false? result) (nil? result)))))))

(deftest ^:unit ^:security determine-mfa-requirement-test
  (testing "MFA requirement logic for login"
    (testing "requires MFA when enabled and password valid"
      (let [user {:mfa-enabled true}
            password-valid? true
            mfa-code nil
            login-risk {:risk-level :low}
            result (mfa/determine-mfa-requirement user password-valid? mfa-code login-risk)]
        (is (true? (:requires-mfa? result)))
        (is (= "MFA code required" (:reason result)))))

    (testing "does not require MFA when disabled"
      (let [user {:mfa-enabled false}
            password-valid? true
            mfa-code nil
            login-risk {:risk-level :low}
            result (mfa/determine-mfa-requirement user password-valid? mfa-code login-risk)]
        (is (false? (:requires-mfa? result)))))

    (testing "does not require MFA when password invalid"
      (let [user {:mfa-enabled true}
            password-valid? false
            mfa-code nil
            login-risk {:risk-level :low}
            result (mfa/determine-mfa-requirement user password-valid? mfa-code login-risk)]
        (is (false? (:requires-mfa? result)))))

    (testing "requires verification when code provided"
      (let [user {:mfa-enabled true}
            password-valid? true
            mfa-code "123456"
            login-risk {:risk-level :low}
            result (mfa/determine-mfa-requirement user password-valid? mfa-code login-risk)]
        (is (true? (:requires-mfa? result)))
        (is (= :pending (:mfa-verified? result)))
        (is (= "MFA code verification pending" (:reason result)))))))

;; =============================================================================
;; MFA Enablement Tests
;; =============================================================================

(deftest ^:unit ^:security can-enable-mfa-test
  (testing "MFA enablement business rules"
    (testing "can enable when not already enabled and user is active"
      (let [user {:mfa-enabled false :active true}
            result (mfa/can-enable-mfa? user)]
        (is (true? (:can-enable? result)))
        (is (nil? (:reason result)))))

    (testing "cannot enable when already enabled"
      (let [user {:mfa-enabled true :active true}
            result (mfa/can-enable-mfa? user)]
        (is (false? (:can-enable? result)))
        (is (= "MFA is already enabled" (:reason result)))))

    (testing "can enable when mfa-enabled is nil and user is active"
      (let [user {:mfa-enabled nil :active true}
            result (mfa/can-enable-mfa? user)]
        (is (true? (:can-enable? result)))))

    (testing "can enable when mfa-enabled key missing and user is active"
      (let [user {:active true}
            result (mfa/can-enable-mfa? user)]
        (is (true? (:can-enable? result)))))

    (testing "cannot enable when user is not active"
      (let [user {:mfa-enabled false :active false}
            result (mfa/can-enable-mfa? user)]
        (is (false? (:can-enable? result)))
        (is (= "User account is not active" (:reason result)))))

    (testing "cannot enable when user is deleted"
      (let [user {:mfa-enabled false :active true :deleted-at (Instant/now)}
            result (mfa/can-enable-mfa? user)]
        (is (false? (:can-enable? result)))
        (is (= "User account is deleted" (:reason result)))))

    (testing "cannot enable when user is nil"
      (let [result (mfa/can-enable-mfa? nil)]
        (is (false? (:can-enable? result)))
        (is (= "User not found" (:reason result)))))))

(deftest ^:unit ^:security prepare-mfa-enablement-test
  (testing "Prepare user data for MFA enablement"
    (let [user {:id "user-123" :email "test@example.com" :mfa-enabled false}
          secret "ABCDEFGH12345678"
          backup-codes ["CODE1" "CODE2" "CODE3"]
          current-time (Instant/parse "2024-01-01T12:00:00Z")
          result (mfa/prepare-mfa-enablement user secret backup-codes current-time)]

      (testing "returns update map only (not merged with user)"
        (is (= 5 (count (keys result)))))

      (testing "sets mfa-enabled to true"
        (is (true? (:mfa-enabled result))))

      (testing "stores secret"
        (is (= secret (:mfa-secret result))))

      (testing "stores backup codes"
        (is (= backup-codes (:mfa-backup-codes result))))

      (testing "initializes used codes as empty"
        (is (= [] (:mfa-backup-codes-used result))))

      (testing "records enablement timestamp"
        (is (= current-time (:mfa-enabled-at result)))))))

;; =============================================================================
;; MFA Disablement Tests
;; =============================================================================

(deftest ^:unit ^:security can-disable-mfa-test
  (testing "MFA disablement business rules"
    (testing "can disable when enabled"
      (let [user {:mfa-enabled true}
            result (mfa/can-disable-mfa? user)]
        (is (true? (:can-disable? result)))
        (is (nil? (:reason result)))))

    (testing "cannot disable when not enabled"
      (let [user {:mfa-enabled false}
            result (mfa/can-disable-mfa? user)]
        (is (false? (:can-disable? result)))
        (is (= "MFA is not enabled" (:reason result)))))

    (testing "cannot disable when mfa-enabled is nil"
      (let [user {:mfa-enabled nil}
            result (mfa/can-disable-mfa? user)]
        (is (false? (:can-disable? result)))))

    (testing "cannot disable when mfa-enabled key missing"
      (let [user {}
            result (mfa/can-disable-mfa? user)]
        (is (false? (:can-disable? result)))))

    (testing "cannot disable when user is nil"
      (let [result (mfa/can-disable-mfa? nil)]
        (is (false? (:can-disable? result)))
        (is (= "User not found" (:reason result)))))))

(deftest ^:unit ^:security prepare-mfa-disablement-test
  (testing "Prepare user data for MFA disablement"
    (let [user {:id "user-123"
                :email "test@example.com"
                :mfa-enabled true
                :mfa-secret "SECRET123"
                :mfa-backup-codes ["CODE1" "CODE2"]
                :mfa-backup-codes-used ["CODE1"]
                :mfa-enabled-at (Instant/parse "2024-01-01T00:00:00Z")}
          result (mfa/prepare-mfa-disablement user)]

      (testing "returns update map only (not merged with user)"
        (is (= 5 (count (keys result)))))

      (testing "sets mfa-enabled to false"
        (is (false? (:mfa-enabled result))))

      (testing "clears secret"
        (is (nil? (:mfa-secret result))))

      (testing "clears backup codes"
        (is (nil? (:mfa-backup-codes result))))

      (testing "clears used backup codes"
        (is (nil? (:mfa-backup-codes-used result))))

      (testing "clears enablement timestamp"
        (is (nil? (:mfa-enabled-at result)))))))

;; =============================================================================
;; Backup Code Tests
;; =============================================================================

;; Backup-code verification moved to the shell (bcrypt hash-verify) — see
;; wagoe.user.shell.mfa-test. The former pure plaintext-compare tests are
;; removed with the functions they covered.

(deftest ^:unit ^:security count-remaining-backup-codes-test
  (testing "Count remaining backup codes"
    (testing "counts correctly when all codes available"
      (let [user {:mfa-backup-codes ["CODE1" "CODE2" "CODE3"]
                  :mfa-backup-codes-used []}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 3 result))))

    (testing "counts correctly when some codes used"
      (let [user {:mfa-backup-codes ["CODE1" "CODE2" "CODE3"]
                  :mfa-backup-codes-used ["CODE1"]}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 2 result))))

    (testing "returns 0 when all codes used"
      (let [user {:mfa-backup-codes ["CODE1" "CODE2"]
                  :mfa-backup-codes-used ["CODE1" "CODE2"]}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 0 result))))

    (testing "returns 0 when no codes available"
      (let [user {:mfa-backup-codes []
                  :mfa-backup-codes-used []}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 0 result))))

    (testing "handles nil values"
      (let [user {:mfa-backup-codes nil
                  :mfa-backup-codes-used nil}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 0 result))))

    (testing "handles missing keys"
      (let [user {}
            result (mfa/count-remaining-backup-codes user)]
        (is (= 0 result))))))
