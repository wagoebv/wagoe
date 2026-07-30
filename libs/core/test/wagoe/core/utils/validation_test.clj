(ns wagoe.core.utils.validation-test
  "Unit tests for wagoe.core.utils.validation namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.transform :as mt]
            [wagoe.core.utils.validation :as validation]))

(def TestSchema
  [:map
   [:name :string]
   [:age :int]
   [:active :boolean]])

(deftest ^:unit validate-with-transform-test
  (testing "valid data passes"
    (let [result (validation/validate-with-transform
                  TestSchema
                  {:name "John" :age 25 :active true}
                  (mt/transformer))]
      (is (validation/validation-passed? result))
      (is (= "John" (:name (validation/get-validated-data result))))))

  (testing "invalid data fails"
    (let [result (validation/validate-with-transform
                  TestSchema
                  {:name 123 :age "not-int" :active "yes"}
                  (mt/transformer))]
      (is (not (validation/validation-passed? result)))
      (is (some? (validation/get-validation-errors result))))))

(deftest ^:unit validate-cli-args-test
  (testing "delegates to validate-with-transform"
    (let [result (validation/validate-cli-args
                  TestSchema
                  {:name "Jane" :age "30" :active "true"}
                  mt/string-transformer)]
      (is (validation/validation-passed? result))
      (is (= 30 (:age (validation/get-validated-data result)))))))

(deftest ^:unit validate-request-test
  (testing "delegates to validate-with-transform"
    (let [result (validation/validate-request
                  TestSchema
                  {:name "Bob" :age 42 :active false}
                  (mt/transformer))]
      (is (validation/validation-passed? result)))))

(deftest ^:unit validation-result-utilities-test
  (testing "validation-passed? returns boolean"
    (is (true? (validation/validation-passed? {:valid? true :data {}})))
    (is (false? (validation/validation-passed? {:valid? false :errors []}))))

  (testing "get-validation-errors extracts errors"
    (let [errors [{:field :name :message "required"}]]
      (is (= errors (validation/get-validation-errors {:valid? false :errors errors})))
      (is (nil? (validation/get-validation-errors {:valid? true :data {}})))))

  (testing "get-validated-data extracts data"
    (is (= {:name "Test"} (validation/get-validated-data {:valid? true :data {:name "Test"}})))
    (is (nil? (validation/get-validated-data {:valid? false :errors []})))))

(deftest ^:unit valid-uuid?-test
  (testing "valid UUID strings"
    (is (true? (validation/valid-uuid? "123e4567-e89b-12d3-a456-426614174000"))))

  (testing "invalid UUID strings"
    (is (false? (validation/valid-uuid? "not-a-uuid")))
    (is (false? (validation/valid-uuid? "")))))

(deftest ^:unit valid-output-format?-test
  (testing "valid formats"
    (is (true? (validation/valid-output-format? "table")))
    (is (true? (validation/valid-output-format? "json"))))

  (testing "invalid formats"
    (is (false? (validation/valid-output-format? "xml")))
    (is (false? (validation/valid-output-format? "csv")))))
