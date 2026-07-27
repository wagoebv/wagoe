(ns wagoe.user.core.authentication-test
  "Unit tests for the user authentication core — all functions pure and deterministic."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.authentication :as auth])
  (:import [java.time Instant Duration]))

;; =============================================================================
;; Fixed test values
;; =============================================================================

(def ^:private now (Instant/parse "2026-01-15T12:00:00Z"))
(def ^:private past (Instant/parse "2025-06-01T00:00:00Z"))    ; > 30 days ago
(def ^:private recent (Instant/parse "2026-01-10T00:00:00Z"))  ; < 30 days ago

(def ^:private active-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "alice@example.com"
   :active true
   :role :user
   :deleted-at nil
   :failed-login-count 0
   :password-hash "bcrypt-hash"})

(def ^:private login-config
  {:max-failed-attempts 5
   :lockout-duration-minutes 15
   :alert-threshold 3})

(def ^:private login-ctx {:ip-address "1.2.3.4" :user-agent "Chrome/120"})
(def ^:private low-risk {:risk-score 10 :risk-factors [] :requires-mfa? false})
(def ^:private high-risk {:risk-score 80 :risk-factors [] :requires-mfa? true})
(def ^:private strict-policy
  {:min-length 8 :max-length 255
   :require-uppercase true :require-lowercase true
   :require-numbers true :require-special-chars true})
(def ^:private policy {:max-password-age-days 90})
(def ^:private pwd-created-recent (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private pwd-created-old (Instant/parse "2025-09-01T00:00:00Z")) ; > 90 days before now

;; =============================================================================
;; validate-login-credentials
;; =============================================================================

(deftest ^:unit validate-login-credentials-test
  (testing "valid credentials pass schema"
    (let [result (auth/validate-login-credentials
                  {:email "user@example.com" :password "password123"})]
      (is (true? (:valid? result)))))

  (testing "missing password fails validation"
    (let [result (auth/validate-login-credentials {:email "user@example.com"})]
      (is (false? (:valid? result)))))

  (testing "invalid email format fails validation"
    (let [result (auth/validate-login-credentials
                  {:email "not-an-email" :password "password123"})]
      (is (false? (:valid? result)))))

  (testing "password too short fails validation"
    (let [result (auth/validate-login-credentials
                  {:email "user@example.com" :password "short"})]
      (is (false? (:valid? result))))))

;; =============================================================================
;; should-allow-login-attempt?
;; =============================================================================

(deftest ^:unit should-allow-login-attempt?-test
  (testing "returns false when user is nil"
    (let [result (auth/should-allow-login-attempt? nil login-config now)]
      (is (false? (:allowed? result)))
      (is (string? (:reason result)))))

  (testing "returns false when user is inactive"
    (let [user (assoc active-user :active false)
          result (auth/should-allow-login-attempt? user login-config now)]
      (is (false? (:allowed? result)))))

  (testing "returns false when user is deleted"
    (let [user (assoc active-user :deleted-at past)
          result (auth/should-allow-login-attempt? user login-config now)]
      (is (false? (:allowed? result)))))

  (testing "returns false when account is locked"
    (let [lockout-until (.plus now (Duration/ofMinutes 10))
          user (assoc active-user :lockout-until lockout-until)
          result (auth/should-allow-login-attempt? user login-config now)]
      (is (false? (:allowed? result)))
      (is (= lockout-until (:retry-after result)))))

  (testing "returns true when lockout has expired"
    (let [lockout-until (.minus now (Duration/ofMinutes 5))
          user (assoc active-user :lockout-until lockout-until)
          result (auth/should-allow-login-attempt? user login-config now)]
      (is (true? (:allowed? result)))))

  (testing "returns true for valid active user"
    (let [result (auth/should-allow-login-attempt? active-user login-config now)]
      (is (true? (:allowed? result))))))

;; =============================================================================
;; calculate-failed-login-consequences
;; =============================================================================

(deftest ^:unit calculate-failed-login-consequences-test
  (testing "increments failed-login-count"
    (let [result (auth/calculate-failed-login-consequences active-user login-config now)]
      (is (= 1 (:failed-login-count result)))))

  (testing "sets should-alert? when threshold is reached"
    (let [user (assoc active-user :failed-login-count 2) ; 2 failures, next makes 3 = alert-threshold
          result (auth/calculate-failed-login-consequences user login-config now)]
      (is (true? (:should-alert? result)))))

  (testing "does not set should-alert? below threshold"
    (let [result (auth/calculate-failed-login-consequences active-user login-config now)]
      (is (false? (:should-alert? result)))))

  (testing "sets lockout-until when max attempts exceeded"
    (let [user (assoc active-user :failed-login-count 4) ; 4 failures, next (5) = max
          result (auth/calculate-failed-login-consequences user login-config now)]
      (is (some? (:lockout-until result)))
      (is (.isAfter (:lockout-until result) now))))

  (testing "does not set lockout-until below max attempts"
    (let [result (auth/calculate-failed-login-consequences active-user login-config now)]
      (is (nil? (:lockout-until result))))))

;; =============================================================================
;; prepare-successful-login-updates
;; =============================================================================

(deftest ^:unit prepare-successful-login-updates-test
  (testing "resets security counters and sets last-login"
    (let [user (assoc active-user :failed-login-count 3 :login-count 5)
          updates (auth/prepare-successful-login-updates user now)]
      (is (= now (:last-login updates)))
      (is (= 6 (:login-count updates)))
      (is (= 0 (:failed-login-count updates)))
      (is (nil? (:lockout-until updates))))))

;; =============================================================================
;; analyze-login-risk
;; =============================================================================

(deftest ^:unit analyze-login-risk-test
  (testing "zero risk for known IP and user-agent, recent activity"
    (let [recent-sessions [{:ip-address "1.2.3.4" :user-agent "Chrome/120"}]
          user (assoc active-user :last-login recent :role :user)
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (= 0 (:risk-score result)))
      (is (empty? (:risk-factors result)))
      (is (false? (:requires-mfa? result)))))

  (testing "adds risk for new IP address"
    (let [recent-sessions [{:ip-address "9.9.9.9" :user-agent "Chrome/120"}]
          user (assoc active-user :last-login recent :role :user)
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (some #(= :new-ip-address (:factor %)) (:risk-factors result)))))

  (testing "adds risk for new user-agent"
    (let [recent-sessions [{:ip-address "1.2.3.4" :user-agent "Firefox/100"}]
          user (assoc active-user :last-login recent :role :user)
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (some #(= :new-user-agent (:factor %)) (:risk-factors result)))))

  (testing "adds risk for admin user"
    (let [recent-sessions [{:ip-address "1.2.3.4" :user-agent "Chrome/120"}]
          user (assoc active-user :last-login recent :role :admin)
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (some #(= :admin-user (:factor %)) (:risk-factors result)))))

  (testing "adds risk for dormant account"
    (let [recent-sessions [{:ip-address "1.2.3.4" :user-agent "Chrome/120"}]
          user (assoc active-user :last-login past :role :user)  ; > 30 days ago
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (some #(= :dormant-account (:factor %)) (:risk-factors result)))))

  (testing "requires-mfa? is true when risk-score > 50"
    ;; admin (25) + new-ip (30) + new-user-agent (20) = 75 > 50
    (let [recent-sessions []
          user (assoc active-user :last-login recent :role :admin)
          result (auth/analyze-login-risk user login-ctx recent-sessions now)]
      (is (true? (:requires-mfa? result))))))

;; =============================================================================
;; should-create-session?
;; =============================================================================

(deftest ^:unit should-create-session?-test
  (testing "always creates session"
    (is (true? (:create-session? (auth/should-create-session? active-user low-risk {})))))

  (testing "uses base duration for normal user with low risk"
    (let [result (auth/should-create-session? active-user low-risk {:default-session-hours 24})]
      (is (= 24 (:session-duration-hours result)))))

  (testing "uses short session for high-risk login"
    (let [result (auth/should-create-session? active-user high-risk {:default-session-hours 24})]
      (is (= 1 (:session-duration-hours result)))))

  (testing "uses shorter session for admin user"
    (let [admin (assoc active-user :role :admin)
          result (auth/should-create-session? admin low-risk {:default-session-hours 24})]
      (is (= 8 (:session-duration-hours result)))))

  (testing "requires-additional-verification? mirrors requires-mfa? from risk"
    (is (false? (:requires-additional-verification?
                 (auth/should-create-session? active-user low-risk {}))))
    (is (true? (:requires-additional-verification?
                (auth/should-create-session? active-user high-risk {}))))))

;; =============================================================================
;; meets-password-policy?
;; =============================================================================

(deftest ^:unit meets-password-policy?-test
  (testing "valid password passes"
    (let [result (auth/meets-password-policy? "Str0ng!P@ss" strict-policy nil)]
      (is (true? (:valid? result)))
      (is (empty? (:violations result)))))

  (testing "too-short password fails"
    (let [result (auth/meets-password-policy? "Sh0rt!" strict-policy nil)]
      (is (false? (:valid? result)))
      (is (some #(= :too-short (:code %)) (:violations result)))))

  (testing "missing uppercase fails"
    (let [result (auth/meets-password-policy? "str0ng!p@ss" strict-policy nil)]
      (is (some #(= :missing-uppercase (:code %)) (:violations result)))))

  (testing "missing number fails"
    (let [result (auth/meets-password-policy? "Strong!P@ss" strict-policy nil)]
      (is (some #(= :missing-number (:code %)) (:violations result)))))

  (testing "missing special char fails"
    (let [result (auth/meets-password-policy? "Strong1Pass" strict-policy nil)]
      (is (some #(= :missing-special-char (:code %)) (:violations result)))))

  (testing "forbidden pattern fails"
    (let [policy (assoc strict-policy :forbidden-patterns #{"password"})
          result (auth/meets-password-policy? "Password1!" policy nil)]
      (is (some #(= :common-password (:code %)) (:violations result)))))

  (testing "password containing email username fails"
    (let [user-ctx {:email "alice@example.com"}
          result (auth/meets-password-policy? "Alice!123" strict-policy user-ctx)]
      (is (some #(= :contains-email (:code %)) (:violations result))))))

;; =============================================================================
;; should-require-password-reset?
;; =============================================================================

(deftest ^:unit should-require-password-reset?-test
  (testing "requires reset when no password hash"
    (let [user (dissoc active-user :password-hash)
          result (auth/should-require-password-reset? user now policy)]
      (is (true? (:requires-reset? result)))))

  (testing "requires reset when password is expired"
    (let [user (assoc active-user :password-created-at pwd-created-old)
          result (auth/should-require-password-reset? user now policy)]
      (is (true? (:requires-reset? result)))))

  (testing "requires reset when force-password-reset is set"
    (let [user (assoc active-user
                      :password-created-at pwd-created-recent
                      :force-password-reset true)
          result (auth/should-require-password-reset? user now policy)]
      (is (true? (:requires-reset? result)))))

  (testing "does not require reset for valid recent password"
    (let [user (assoc active-user :password-created-at pwd-created-recent)
          result (auth/should-require-password-reset? user now policy)]
      (is (false? (:requires-reset? result))))))

;; =============================================================================
;; calculate-password-strength
;; =============================================================================

(deftest ^:unit calculate-password-strength-test
  (testing "weak password scores low"
    (let [result (auth/calculate-password-strength "abc")]
      (is (= :weak (:strength-level result)))
      (is (pos? (count (:feedback result))))))

  (testing "moderate password"
    ;; "password" = 8 lower-only chars — scores ~45, which is :moderate
    (let [result (auth/calculate-password-strength "password")]
      (is (= :moderate (:strength-level result)))))

  (testing "strong password with mixed chars"
    (let [result (auth/calculate-password-strength "MyStr0ng!Passw0rd")]
      (is (#{:strong :very-strong} (:strength-level result)))
      (is (>= (:strength-score result) 60))))

  (testing "score is a non-negative number"
    ;; The scoring formula sums three components; it is not capped at 100
    (doseq [pwd ["a" "password" "Str0ng!P@ss1234567890"]]
      (let [{:keys [strength-score]} (auth/calculate-password-strength pwd)]
        (is (>= strength-score 0))))))
