(ns wagoe.core.validation.messages-test
  "Unit tests for validation message templating and suggestion engine."
  (:require [wagoe.core.validation.messages :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; String Distance Tests
;; =============================================================================

(deftest ^:unit suggest-similar-value-test
  (testing "Did you mean suggestions"
    (testing "Suggests similar value for simple typo"
      (is (= "admin" (msg/suggest-similar-value "admim" ["admin" "user" "viewer"] {}))))

    (testing "Suggests similar value for transposition"
      (is (= "user" (msg/suggest-similar-value "uesr" ["admin" "user" "viewer"] {}))))

    (testing "No suggestion when distance too large"
      (is (nil? (msg/suggest-similar-value "xyz" ["admin" "user" "viewer"] {}))))

    (testing "Case insensitive matching"
      (is (= "admin" (msg/suggest-similar-value "ADMIM" ["admin" "user" "viewer"] {}))))

    (testing "Nil input"
      (is (nil? (msg/suggest-similar-value nil ["admin" "user"] {}))))

    (testing "Empty allowed values"
      (is (nil? (msg/suggest-similar-value "test" [] {}))))))

(deftest ^:unit format-allowed-values-test
  (testing "Format list of allowed values"
    (testing "Single value"
      (is (= "admin" (msg/format-allowed-values ["admin"] {}))))

    (testing "Two values"
      (is (= "admin, and user" (msg/format-allowed-values ["admin" "user"] {}))))

    (testing "Three values with 'and'"
      (is (= "admin, user, and viewer"
             (msg/format-allowed-values ["admin" "user" "viewer"] {}))))

    (testing "Three values with 'or'"
      (is (= "admin, user, or viewer"
             (msg/format-allowed-values ["admin" "user" "viewer"] {:conjunction "or"}))))

    (testing "Many values truncated"
      (let [many-values (map str (range 1 20))
            result (msg/format-allowed-values many-values {:max-items 5})]
        (is (.contains result "..."))
        (is (= 5 (count (str/split result #","))))))

    (testing "Empty list"
      (is (= "" (msg/format-allowed-values [] {}))))))

;; =============================================================================
;; Message Rendering Tests
;; =============================================================================

(deftest ^:unit render-message-test
  (testing "Basic message rendering"
    (testing "Required field message"
      (is (= "Email is required"
             (msg/render-message :required {:field :email} {}))))

    (testing "Invalid format message"
      (is (= "Email format is invalid"
             (msg/render-message :invalid-format {:field :email} {}))))

    (testing "Out of range message"
      (is (= "Age is out of range"
             (msg/render-message :out-of-range {:field :age} {}))))

    (testing "Duplicate message"
      (is (= "Email already exists"
             (msg/render-message :duplicate {:field :email} {})))))

  (testing "Detailed message rendering"
    (testing "Detailed format with parameters"
      (is (= "Email must match the format: user@domain.com"
             (msg/render-message :invalid-format
                                 {:field :email
                                  :expected "user@domain.com"
                                  :use-detailed? true} {}))))

    (testing "Detailed range with min/max"
      (is (= "Age must be between 0 and 120"
             (msg/render-message :out-of-range
                                 {:field :age
                                  :min "0"
                                  :max "120"
                                  :use-detailed? true} {}))))

    (testing "Detailed length validation"
      (is (= "Password must be at least 8 characters"
             (msg/render-message :too-short
                                 {:field :password
                                  :min "8"
                                  :use-detailed? true} {})))))

  (testing "Field name formatting"
    (testing "Single word field"
      (is (= "Email is required"
             (msg/render-message :required {:field :email} {}))))

    (testing "Hyphenated field to Title Case"
      (is (= "Tenant ID is required"
             (msg/render-message :required {:field :tenant-id} {}))))

    (testing "Multi-word field"
      (is (= "User Agent is required"
             (msg/render-message :required {:field :user-agent} {}))))

    (testing "Field with ID acronym"
      (is (= "Payment Method ID is required"
             (msg/render-message :required {:field :payment-method-id} {}))))))

;; =============================================================================
;; Suggestion Rendering Tests
;; =============================================================================

(deftest ^:unit render-suggestion-test
  (testing "Did you mean suggestions"
    (testing "Invalid value with suggestion"
      (is (= "Did you mean \"admin\"? Allowed values: admin, user, viewer"
             (msg/render-suggestion :invalid-value
                                    {:value "admim"
                                     :allowed "admin, user, viewer"
                                     :suggestion "admin"}))))

    (testing "No suggestion without match"
      (is (nil? (msg/render-suggestion :invalid-value
                                       {:value "xyz"
                                        :allowed "admin, user, viewer"})))))

  (testing "Format hints"
    (testing "Expected format hint"
      (is (= "Provide Email in the correct format. Expected: user@domain.com"
             (msg/render-suggestion :invalid-format
                                    {:field-name "Email"
                                     :expected "user@domain.com"})))))

  (testing "Range hints"
    (testing "Range hint without value"
      (is (= "Provide Age between 0 and 120"
             (msg/render-suggestion :out-of-range
                                    {:field-name "Age"
                                     :min "0"
                                     :max "120"}))))

    (testing "Range hint with value"
      (is (= "Provide Age between 0 and 120. You provided: 150"
             (msg/render-suggestion :out-of-range
                                    {:field-name "Age"
                                     :min "0"
                                     :max "120"
                                     :value "150"})))))

  (testing "Length hints"
    (testing "Too short hint"
      (is (= "Provide Password with at least 8 characters"
             (msg/render-suggestion :too-short
                                    {:field-name "Password"
                                     :min "8"}))))

    (testing "Too long hint"
      (is (= "Provide Name with at most 255 characters"
             (msg/render-suggestion :too-long
                                    {:field-name "Name"
                                     :max "255"})))))

  (testing "Dependency hints"
    (testing "Required dependency"
      (is (= "Provide Payment Method before setting Billing Address"
             (msg/render-suggestion :dependency
                                    {:field-name "Billing Address"
                                     :dependency "Payment Method"}))))))

;; =============================================================================
;; Error Enhancement Tests
;; =============================================================================

(deftest ^:unit enhance-error-test
  (testing "Error map enhancement"
    (testing "Basic error enhancement"
      (let [error {:field :email
                   :code :required
                   :params {}}
            enhanced (msg/enhance-error error {})]
        (is (= "Email is required" (:message enhanced)))
        (is (= :required (:code enhanced)))
        (is (= :email (:field enhanced)))))

    (testing "Error with suggestion"
      (let [error {:field :role
                   :code :invalid-value
                   :params {:value "admim"
                            :allowed "admin, user, viewer"
                            :suggestion "admin"}}
            enhanced (msg/enhance-error error {})]
        (is (= "Role has an invalid value" (:message enhanced)))
        (is (= "Did you mean \"admin\"? Allowed values: admin, user, viewer"
               (:suggestion enhanced)))))

    (testing "Error with range"
      (let [error {:field :age
                   :code :out-of-range
                   :params {:min "0" :max "120" :value "150"}}
            enhanced (msg/enhance-error error {})]
        (is (= "Age is out of range" (:message enhanced)))
        (is (= "Provide Age between 0 and 120. You provided: 150"
               (:suggestion enhanced)))))

    (testing "Preserves original on error"
      (let [malformed-error {:code nil :params nil}
            enhanced (msg/enhance-error malformed-error {})]
        ;; Should not crash, should preserve original
        (is (map? enhanced))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit integration-test
  (testing "Complete validation error flow"
    (testing "User creation with missing email"
      (let [error {:field :email
                   :code :user.email/required
                   :params {}}
            enhanced (msg/enhance-error error {})]
        (is (= "Email is required" (:message enhanced)))))

    (testing "User role typo"
      (let [error {:field :role
                   :code :user.role/invalid-value
                   :params {:value "admim"
                            :suggestion "admin"
                            :allowed "admin, user, viewer"}}
            enhanced (msg/enhance-error error {})]
        (is (= "Role has an invalid value" (:message enhanced)))
        (is (.contains (:suggestion enhanced) "Did you mean"))
        (is (.contains (:suggestion enhanced) "admin"))))

    (testing "Password too short"
      (let [error {:field :password
                   :code :user.password/too-short
                   :params {:min "8" :use-detailed? true}}
            enhanced (msg/enhance-error error {})]
        (is (= "Password must be at least 8 characters" (:message enhanced)))
        (is (= "Provide Password with at least 8 characters" (:suggestion enhanced)))))

    (testing "Forbidden tenant-id change"
      (let [error {:field :tenant-id
                   :code :user.tenant-id/forbidden
                   :params {:reason "user creation"
                            :use-detailed? true}}
            enhanced (msg/enhance-error error {})]
        (is (= "Tenant ID cannot be changed after user creation" (:message enhanced)))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Edge cases"
    (testing "Empty params"
      (let [result (msg/render-message :required {:field :email} {})]
        (is (string? result))
        (is (not (empty? result)))))

    (testing "Missing field name"
      (let [result (msg/render-message :required {} {})]
        (is (string? result))))

    (testing "Unknown error code"
      (let [result (msg/render-message :unknown-code {:field :test} {})]
        (is (string? result))
        ;; Should fallback to default
        (is (= "Validation error" result))))

    (testing "Value sanitization"
      (let [long-value (apply str (repeat 100 "a"))
            error {:field :name
                   :code :invalid-value
                   :params {:value long-value}}
            enhanced (msg/enhance-error error {})]
        ;; Value should be truncated in message
        (is (< (count (:message enhanced)) (count long-value)))))

    (testing "PII redaction"
      (let [error {:field :email
                   :code :invalid-format
                   :params {:value "user@example.com"}}
            enhanced (msg/enhance-error error {})]
        ;; Email should be redacted
        (is (not (.contains (:message enhanced) "user@example.com")))))))
