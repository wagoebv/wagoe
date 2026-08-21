(ns wagoe.core.validation.result-test
  "Unit tests for validation result format and utilities."
  (:require [wagoe.core.validation.result :as vr]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Feature Flag Tests
;; =============================================================================

(deftest ^:unit devex-validation-enabled-test
  (testing "Feature flag reads from environment"
    ;; By default should be false (unset)
    (is (false? (vr/devex-validation-enabled?)))))

;; =============================================================================
;; Result Constructor Tests
;; =============================================================================

(deftest ^:unit success-result-test
  (testing "Success result with data only"
    (let [result (vr/success-result {:email "user@example.com" :name "John"})]
      (is (vr/validation-passed? result))
      (is (= {:email "user@example.com" :name "John"} (:data result)))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))))

  (testing "Success result with warnings"
    (let [warning (vr/warning-map :email :deprecated "Email field will be deprecated")
          result (vr/success-result {:email "test@example.com"} [warning])]
      (is (vr/validation-passed? result))
      (is (= 1 (count (:warnings result))))
      (is (= warning (first (:warnings result)))))))

(deftest ^:unit failure-result-test
  (testing "Failure result with single error"
    (let [error (vr/error-map :email :required "Email is required")
          result (vr/failure-result error)]
      (is (vr/validation-failed? result))
      (is (nil? (:data result)))
      (is (= 1 (count (:errors result))))
      (is (= error (first (:errors result))))))

  (testing "Failure result with multiple errors"
    (let [errors [(vr/error-map :email :required "Email is required")
                  (vr/error-map :name :too-short "Name is too short")]
          result (vr/failure-result errors)]
      (is (vr/validation-failed? result))
      (is (= 2 (vr/error-count result)))))

  (testing "Failure result with warnings"
    (let [error (vr/error-map :email :invalid-format "Invalid email")
          warning (vr/warning-map :name :length "Name length is borderline")
          result (vr/failure-result error [warning])]
      (is (vr/validation-failed? result))
      (is (vr/has-warnings? result)))))

;; =============================================================================
;; Error Map Tests
;; =============================================================================

(deftest ^:unit error-map-test
  (testing "Basic error map"
    (let [error (vr/error-map :email :required "Email is required")]
      (is (= :email (:field error)))
      (is (= :required (:code error)))
      (is (= "Email is required" (:message error)))
      (is (= {} (:params error)))
      (is (= [:email] (:path error)))))

  (testing "Error map with params and path"
    (let [error (vr/error-map :email :invalid-format "Invalid email format"
                              {:params {:value "bad@email" :regex "..."}
                               :path [:user :contact :email]})]
      (is (= {:value "bad@email" :regex "..."} (:params error)))
      (is (= [:user :contact :email] (:path error)))))

  (testing "Error map with rule-id"
    (let [error (vr/error-map :email :required "Email is required"
                              {:rule-id :user.email/required})]
      (is (= :user.email/required (:rule-id error))))))

;; =============================================================================
;; Result Utility Tests
;; =============================================================================

(deftest ^:unit get-errors-test
  (testing "Get errors from failure result"
    (let [errors [(vr/error-map :email :required "Required")
                  (vr/error-map :name :too-short "Too short")]
          result (vr/failure-result errors)]
      (is (= 2 (count (vr/get-errors result))))
      (is (= errors (vr/get-errors result)))))

  (testing "Get errors from success result"
    (let [result (vr/success-result {:email "test@example.com"})]
      (is (empty? (vr/get-errors result))))))

(deftest ^:unit errors-by-field-test
  (testing "Group errors by field"
    (let [errors [(vr/error-map :email :required "Required")
                  (vr/error-map :email :invalid-format "Invalid format")
                  (vr/error-map :name :too-short "Too short")]
          result (vr/failure-result errors)
          by-field (vr/errors-by-field result)]
      (is (= 2 (count (:email by-field))))
      (is (= 1 (count (:name by-field)))))))

(deftest ^:unit errors-by-code-test
  (testing "Group errors by code"
    (let [errors [(vr/error-map :email :required "Required")
                  (vr/error-map :name :required "Required")
                  (vr/error-map :age :out-of-range "Out of range")]
          result (vr/failure-result errors)
          by-code (vr/errors-by-code result)]
      (is (= 2 (count (:required by-code))))
      (is (= 1 (count (:out-of-range by-code)))))))

;; =============================================================================
;; Legacy Compatibility Tests
;; =============================================================================

(deftest ^:unit merge-results-test
  (testing "Merge all successful results"
    (let [result1 (vr/success-result {:email "test@example.com"})
          result2 (vr/success-result {:name "John"})
          merged (vr/merge-results [result1 result2])]
      (is (vr/validation-passed? merged))
      (is (= "test@example.com" (get-in merged [:data :email])))
      (is (= "John" (get-in merged [:data :name])))))

  (testing "Merge with one failure"
    (let [result1 (vr/success-result {:email "test@example.com"})
          result2 (vr/failure-result (vr/error-map :name :required "Required"))
          merged (vr/merge-results [result1 result2])]
      (is (vr/validation-failed? merged))
      (is (= 1 (vr/error-count merged)))))

  (testing "Merge warnings from multiple results"
    (let [warning1 (vr/warning-map :email :deprecated "Deprecated field")
          warning2 (vr/warning-map :name :length "Length warning")
          result1 (vr/success-result {:email "test@example.com"} [warning1])
          result2 (vr/success-result {:name "John"} [warning2])
          merged (vr/merge-results [result1 result2])]
      (is (vr/validation-passed? merged))
      (is (= 2 (count (vr/get-warnings merged)))))))

(deftest ^:unit add-error-test
  (testing "Add error to success result"
    (let [result (vr/success-result {:email "test@example.com"})
          error (vr/error-map :name :required "Name is required")
          updated (vr/add-error result error)]
      (is (vr/validation-failed? updated))
      (is (nil? (:data updated)))
      (is (= 1 (vr/error-count updated)))))

  (testing "Add error to failure result"
    (let [error1 (vr/error-map :email :required "Required")
          result (vr/failure-result error1)
          error2 (vr/error-map :name :required "Required")
          updated (vr/add-error result error2)]
      (is (= 2 (vr/error-count updated))))))

(deftest ^:unit add-warning-test
  (testing "Add warning to success result"
    (let [result (vr/success-result {:email "test@example.com"})
          warning (vr/warning-map :email :deprecated "Will be deprecated")
          updated (vr/add-warning result warning)]
      (is (vr/validation-passed? updated))
      (is (vr/has-warnings? updated))
      (is (= 1 (count (vr/get-warnings updated)))))))
