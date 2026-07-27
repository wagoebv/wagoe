(ns wagoe.core.validation.behavior-test
  "Tests for behavior specification DSL."
  (:require [wagoe.core.validation.behavior :as behavior]
            [wagoe.user.schema :as user-schema]
            [clojure.test :refer [deftest is testing]]))

;; Tag all tests for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3])

;; -----------------------------------------------------------------------------
;; Fake Validation Function for Testing
;; -----------------------------------------------------------------------------

(defn- fake-validate
  "Fake validation function for testing scenarios.
   
  Simplified validator that checks required fields and types without
  complex regex validation."
  [_schema data]
  (let [required-fields [:name :email :role :tenant-id]
        missing-fields (filter #(not (contains? data %)) required-fields)
        errors (cond
                 (seq missing-fields)
                 [{:code :malli.core/missing-key
                   :path [(first missing-fields)]
                   :message (str "Missing required field: " (first missing-fields))}]

                 (and (:email data) (not (string? (:email data))))
                 [{:code :malli.core/invalid-type
                   :path [:email]
                   :message "Email must be a string"}]

                 :else nil)]
    (if (seq errors)
      {:status :failure :errors errors}
      {:status :success :data data})))

(defn validate-create-user-request
  "Validate CreateUserRequest using fake validator."
  [data]
  (fake-validate user-schema/CreateUserRequest data))

;; -----------------------------------------------------------------------------
;; Mutation Helper Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 remove-field-test
  (testing "remove-field removes top-level field"
    (let [mutation (behavior/remove-field :email)
          data {:name "Test" :email "test@example.com"}
          result (mutation data)]
      (is (= {:name "Test"} result))
      (is (not (contains? result :email))))))

(deftest ^:unit ^:phase3 set-field-test
  (testing "set-field sets top-level field"
    (let [mutation (behavior/set-field :email "new@example.com")
          data {:name "Test" :email "old@example.com"}
          result (mutation data)]
      (is (= {:name "Test" :email "new@example.com"} result)))))

(deftest ^:unit ^:phase3 replace-type-test
  (testing "replace-type replaces with string"
    (let [mutation (behavior/replace-type :age :string)
          data {:age 25}
          result (mutation data)]
      (is (string? (:age result))))))

;; -----------------------------------------------------------------------------
;; Scenario Execution Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 execute-scenario-valid-data-test
  (testing "Execute scenario with valid data"
    (let [scenario {:name "valid-user"
                    :base {:name "Test User"
                           :email "testuser@example.com"
                           :role :user
                           :tenant-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")}
                    :mutations []
                    :action validate-create-user-request
                    :assertions [{:expect :success}]}
          result (behavior/execute-scenario scenario {})]
      (is (= "valid-user" (:scenario-name result)))
      (is (= :success (get-in result [:result :status])))
      (is (:all-passed? result)))))

(deftest ^:unit ^:phase3 execute-scenario-missing-field-test
  (testing "Execute scenario with missing required field"
    (let [scenario {:name "email-required"
                    :base {:name "Test User"
                           :email "testuser@example.com"
                           :role :user
                           :tenant-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")}
                    :mutations [(behavior/remove-field :email)]
                    :action validate-create-user-request
                    :assertions [{:expect :failure}]}
          result (behavior/execute-scenario scenario {})]
      (is (= "email-required" (:scenario-name result)))
      (is (= :failure (get-in result [:result :status])))
      (is (:all-passed? result)))))

(deftest ^:unit ^:phase3 execute-scenario-multiple-mutations-test
  (testing "Execute scenario with multiple mutations"
    (let [scenario {:name "multiple-mutations"
                    :base {:name "Test User"
                           :email "testuser@example.com"
                           :role :user
                           :tenant-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
                           :extra "field"}
                    :mutations [(behavior/remove-field :extra)
                                (behavior/set-field :email "newuser@example.com")]
                    :action validate-create-user-request
                    :assertions [{:expect :success}]}
          result (behavior/execute-scenario scenario {})]
      (is (not (contains? (:input result) :extra)))
      (is (= "newuser@example.com" (get-in result [:input :email])))
      (is (:all-passed? result)))))

;; -----------------------------------------------------------------------------
;; Scenario Validation Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 validate-scenario-missing-name-test
  (testing "Scenario validation fails without name"
    (let [scenario {:action (fn [_] {:status :success})
                    :assertions [{:expect :success}]}]
      (is (thrown? Exception (behavior/execute-scenario scenario {}))))))

(deftest ^:unit ^:phase3 validate-scenario-missing-action-test
  (testing "Scenario validation fails without action"
    (let [scenario {:name "test"
                    :assertions [{:expect :success}]}]
      (is (thrown? Exception (behavior/execute-scenario scenario {}))))))

;; -----------------------------------------------------------------------------
;; Scenario Compilation Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 compile-scenarios-test
  (testing "Compile scenarios produces test function pairs"
    (let [scenarios [{:name "test-1"
                      :base {:name "Test"}
                      :mutations []
                      :action (fn [_] {:status :success})
                      :assertions [{:expect :success}]}]
          compiled (behavior/compile-scenarios scenarios {})
          [test-name test-fn] (first compiled)]
      (is (= 1 (count compiled)))
      (is (= "test-1" test-name))
      (is (fn? test-fn)))))

;; -----------------------------------------------------------------------------
;; Template Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 missing-required-field-template-test
  (testing "Missing required field template creates valid scenario"
    (let [base-data {:name "Test"
                     :email "testuser@example.com"
                     :role :user
                     :tenant-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")}
          scenario (behavior/missing-required-field-template
                    :email
                    :user.email/required
                    base-data
                    validate-create-user-request)
          result (behavior/execute-scenario scenario {})]
      (is (= "email-required-missing" (:scenario-name result)))
      (is (not (contains? (:input result) :email)))
      (is (= :failure (get-in result [:result :status]))))))

(deftest ^:unit ^:phase3 valid-data-template-test
  (testing "Valid data template creates valid scenario"
    (let [base-data {:name "Test"
                     :email "testuser@example.com"
                     :role :user
                     :tenant-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")}
          scenario (behavior/valid-data-template
                    "valid-user"
                    base-data
                    validate-create-user-request)
          result (behavior/execute-scenario scenario {})]
      (is (= "valid-user" (:scenario-name result)))
      (is (= :success (get-in result [:result :status]))))))
