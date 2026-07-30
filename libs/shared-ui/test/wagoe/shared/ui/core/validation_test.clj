(ns wagoe.shared.ui.core.validation-test
  "Unit tests for validation error transformation."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.shared.ui.core.validation :as validation]
            [malli.core :as m]))

;; =============================================================================
;; Test Schemas
;; =============================================================================

(def test-user-schema
  [:map
   [:name [:string {:min 1}]]
   [:email [:re #"^[^@]+@[^@]+$"]]
   [:password [:string {:min 8}]]])

;; =============================================================================
;; explain->field-errors Tests
;; =============================================================================

(deftest ^:unit explain->field-errors-basic-test
  (testing "converts basic validation errors to field map"
    (let [invalid-data {:name ""
                        :email "not-an-email"
                        :password "short"}
          explain (m/explain test-user-schema invalid-data)
          result (validation/explain->field-errors explain)]

      (is (map? result))
      (is (contains? result :name))
      (is (contains? result :email))
      (is (contains? result :password))
      (is (vector? (:name result)))
      (is (seq (:name result)))
      (is (every? string? (:name result))))))

(deftest ^:unit explain->field-errors-single-error-test
  (testing "handles single field error"
    (let [invalid-data {:name "Valid Name"
                        :email "not-an-email"
                        :password "valid-password-123"}
          explain (m/explain test-user-schema invalid-data)
          result (validation/explain->field-errors explain)]

      (is (map? result))
      (is (contains? result :email))
      (is (not (contains? result :name)))
      (is (not (contains? result :password))))))

(deftest ^:unit explain->field-errors-nil-test
  (testing "handles nil explain data gracefully"
    (let [result (validation/explain->field-errors nil)]
      (is (nil? result)))))

(deftest ^:unit explain->field-errors-valid-data-test
  (testing "returns nil for valid data"
    (let [valid-data {:name "John Doe"
                      :email "john@example.com"
                      :password "secure-password-123"}
          explain (m/explain test-user-schema valid-data)
          result (validation/explain->field-errors explain)]
      (is (nil? result)))))

;; =============================================================================
;; has-errors? Tests
;; =============================================================================

(deftest ^:unit has-errors?-test
  (testing "returns true for non-empty error map"
    (is (true? (validation/has-errors? {:name ["error"]}))))

  (testing "returns false for empty error map"
    (is (false? (validation/has-errors? {}))))

  (testing "returns false for nil"
    (is (false? (validation/has-errors? nil))))

  (testing "returns false for non-map"
    (is (false? (validation/has-errors? "not a map")))))

;; =============================================================================
;; field-error Tests
;; =============================================================================

(deftest ^:unit field-error-test
  (testing "retrieves error for existing field"
    (let [errors {:name ["Name is required"]
                  :email ["Invalid email"]}]
      (is (= ["Name is required"] (validation/field-error errors :name)))
      (is (= ["Invalid email"] (validation/field-error errors :email)))))

  (testing "returns nil for non-existent field"
    (let [errors {:name ["Name is required"]}]
      (is (nil? (validation/field-error errors :email)))))

  (testing "handles nil errors gracefully"
    (is (nil? (validation/field-error nil :name)))))
