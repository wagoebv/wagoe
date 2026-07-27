(ns wagoe.user.core.user-validation-test
  "Unit tests for user validation business logic.
   
   These tests verify pure validation functions in the functional core.
   All tests are fast and require no external dependencies."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.user :as user-core]
            [wagoe.user.schema :as schema]
            [malli.core :as m]
            [support.validation-helpers :as vh])
  (:import (java.util UUID)
           (java.time Instant)))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def valid-user-id (UUID/randomUUID))
(def current-time (Instant/now))

(def valid-user-creation-request
  {:email "test@example.com"
   :name "Test User"
   :role :user
   :password "test-password-123"})

;; =============================================================================
;; Email Validation Tests
;; =============================================================================

(deftest ^:unit email-validation-accepts-valid-emails
  (testing "Standard email formats are accepted"
    (let [valid-emails ["user@example.com"
                        "john.doe@company.co.uk"
                        "test+tag@domain.org"
                        "name_123@test-domain.com"
                        "a@b.co"
                        "user.name+tag@example.com"]]
      (doseq [email valid-emails]
        (let [request (assoc valid-user-creation-request :email email)
              result (user-core/validate-user-creation-request request vh/test-validation-config)]
          (is (:valid? result)
              (str "Email should be valid: " email))
          (is (nil? (:errors result))
              (str "No errors expected for valid email: " email)))))))

(deftest ^:unit email-validation-rejects-invalid-emails
  (testing "Invalid email formats are rejected"
    (let [invalid-emails ["notanemail"
                          "@example.com"
                          "user@"
                          "user @example.com"
                          "user@.com"
                          "user@domain"
                          ""
                          "user@@example.com"
                          "user@domain..com"]]
      (doseq [email invalid-emails]
        (let [request (assoc valid-user-creation-request :email email)
              result (user-core/validate-user-creation-request request vh/test-validation-config)]
          (is (not (:valid? result))
              (str "Email should be invalid: " email))
          (is (some? (:errors result))
              (str "Errors expected for invalid email: " email)))))))

(deftest ^:unit email-validation-schema-direct-test
  (testing "Malli schema directly validates email format"
    (is (m/validate [:re schema/email-regex] "valid@example.com")
        "Valid email passes regex")
    (is (not (m/validate [:re schema/email-regex] "invalid"))
        "Invalid email fails regex")
    (is (not (m/validate [:re schema/email-regex] ""))
        "Empty string fails email validation"))

  (testing "Email regex pattern validation"
    (is (re-matches schema/email-regex "test@example.com")
        "Standard email matches regex")
    (is (nil? (re-matches schema/email-regex "notanemail"))
        "Invalid format does not match regex")))

;; =============================================================================
;; Required Field Validation Tests
;; =============================================================================

(deftest ^:unit email-field-required-validation
  (testing "Missing email field causes validation failure"
    (let [request-without-email (dissoc valid-user-creation-request :email)
          result (user-core/validate-user-creation-request request-without-email vh/test-validation-config)]
      (is (not (:valid? result))
          "Validation should fail when email is missing")
      (is (some? (:errors result))
          "Errors should be present")
      (let [explained (m/explain schema/CreateUserRequest request-without-email)
            error-paths (map #(-> % :path) (:errors explained))]
        (is (some #(= [:email] %) error-paths)
            "Error path should include :email field"))))

  (testing "Nil email value causes validation failure"
    (let [request-with-nil-email (assoc valid-user-creation-request :email nil)
          result (user-core/validate-user-creation-request request-with-nil-email vh/test-validation-config)]
      (is (not (:valid? result))
          "Validation should fail when email is nil")
      (is (some? (:errors result))
          "Errors should be present for nil email"))))

(deftest ^:unit all-required-fields-validation
  (testing "All required fields must be present"
    (let [required-fields [:email :name :role]]
      (doseq [field required-fields]
        (let [request (dissoc valid-user-creation-request field)
              result (user-core/validate-user-creation-request request vh/test-validation-config)]
          (is (not (:valid? result))
              (str "Validation should fail when " field " is missing"))
          (is (some? (:errors result))
              (str "Errors should be present for missing " field)))))))

;; =============================================================================
;; Name Length Validation Tests
;; =============================================================================

(deftest ^:unit name-length-validation-too-short
  (testing "Empty name is rejected"
    (let [request (assoc valid-user-creation-request :name "")
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (is (not (:valid? result))
          "Validation should fail for empty name")
      (is (some? (:errors result))
          "Errors should be present for empty name")
      (let [explained (m/explain schema/CreateUserRequest request)
            error-paths (map #(-> % :path) (:errors explained))]
        (is (some #(= [:name] %) error-paths)
            "Error path should include :name field"))))

  (testing "Name with length below minimum is rejected"
    (let [request (assoc valid-user-creation-request :name "")
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (is (not (:valid? result))
          "Names shorter than 1 character should be invalid"))))

(deftest ^:unit name-length-validation-too-long
  (testing "Name exceeding maximum length is rejected"
    (let [too-long-name (apply str (repeat 256 "a"))
          request (assoc valid-user-creation-request :name too-long-name)
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (is (not (:valid? result))
          "Validation should fail for name exceeding 255 characters")
      (is (some? (:errors result))
          "Errors should be present for name that is too long")
      (let [explained (m/explain schema/CreateUserRequest request)
            error-paths (map #(-> % :path) (:errors explained))]
        (is (some #(= [:name] %) error-paths)
            "Error path should include :name field"))))

  (testing "Name at maximum length boundary"
    (let [max-length-name (apply str (repeat 255 "a"))
          request (assoc valid-user-creation-request :name max-length-name)
          result (user-core/validate-user-creation-request request vh/test-validation-config)]
      (is (:valid? result)
          "Name with exactly 255 characters should be valid")
      (is (nil? (:errors result))
          "No errors for name at maximum allowed length"))))

(deftest ^:unit name-length-validation-valid-boundaries
  (testing "Valid name lengths are accepted"
    (let [valid-names ["A"
                       "John Doe"
                       (apply str (repeat 100 "x"))
                       (apply str (repeat 255 "y"))]]
      (doseq [name valid-names]
        (let [request (assoc valid-user-creation-request :name name)
              result (user-core/validate-user-creation-request request vh/test-validation-config)]
          (is (:valid? result)
              (str "Name should be valid with length: " (count name)))
          (is (nil? (:errors result))
              (str "No errors expected for valid name length: " (count name))))))))

;; =============================================================================

;; =============================================================================

;; =============================================================================
;; Integration Tests - Multiple Validation Rules
;; =============================================================================

(deftest ^:unit multiple-validation-errors
  (testing "Multiple invalid fields produce multiple errors"
    (let [invalid-request {:email "notanemail"
                           :name ""
                           :role :invalid-role}
          result (user-core/validate-user-creation-request invalid-request vh/test-validation-config)]
      (is (not (:valid? result))
          "Validation should fail with multiple errors")
      (is (some? (:errors result))
          "Multiple errors should be present")
      (let [explained (m/explain schema/CreateUserRequest invalid-request)
            error-count (count (:errors explained))]
        (is (>= error-count 2)
            "At least 2 validation errors expected")))))

(deftest ^:unit complete-valid-user-creation-flow
  (testing "Valid user creation request passes all validations"
    (let [valid-request {:email "john.doe@company.com"
                         :name "John Doe"
                         :role :admin
                         :active true
                         :send-welcome true
                         :password "secure-password-123"}
          validation-result (user-core/validate-user-creation-request valid-request vh/test-validation-config)]
      (is (:valid? validation-result)
          "Complete valid request should pass validation")
      (is (nil? (:errors validation-result))
          "No errors for completely valid request")
      (is (= valid-request (:data validation-result))
          "Valid data should be returned unchanged"))))
