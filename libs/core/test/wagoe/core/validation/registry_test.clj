(ns wagoe.core.validation.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.validation.registry :as registry]))

(def ^:private sample-rule
  {:rule-id      :user.email/required
   :description  "Email is required"
   :category     :schema
   :module       :user
   :fields       [:email]
   :error-code   :required
   :validator-fn (fn [data] (some? (:email data)))})

(deftest ^:unit valid-rule?-test
  (testing "a well-formed rule is valid"
    (is (true? (registry/valid-rule? sample-rule))))
  (testing "missing keys / wrong types are invalid"
    (is (false? (registry/valid-rule? (dissoc sample-rule :rule-id))))
    (is (false? (registry/valid-rule? (assoc sample-rule :fields "email"))))
    (is (false? (registry/valid-rule? (assoc sample-rule :validator-fn 42))))
    (is (false? (registry/valid-rule? "not-a-map")))))

(deftest ^:unit validate-rule-test
  (testing "valid rule yields no errors"
    (is (= [] (registry/validate-rule sample-rule))))
  (testing "each violated constraint yields an error string"
    (let [errors (registry/validate-rule {:rule-id "not-kw" :category :bogus})]
      (is (some #(re-find #":rule-id" %) errors))
      (is (some #(re-find #":category" %) errors))
      (is (some #(re-find #":validator-fn" %) errors)))))

(deftest ^:unit find-duplicate-rule-ids-test
  (testing "returns the set of ids appearing more than once"
    (is (= #{:a} (registry/find-duplicate-rule-ids
                  [{:rule-id :a} {:rule-id :a} {:rule-id :b}])))
    (is (= #{} (registry/find-duplicate-rule-ids
                [{:rule-id :a} {:rule-id :b}])))))

(deftest ^:unit find-conflicting-rules-test
  (testing "rules sharing category + fields are reported as conflicts"
    (let [rules   [{:rule-id :a :category :schema :fields [:email]}
                   {:rule-id :b :category :schema :fields [:email]}
                   {:rule-id :c :category :schema :fields [:name]}]
          result  (registry/find-conflicting-rules rules)]
      (is (= 1 (count result)))
      (is (= #{:a :b} (set (:rule-ids (first result)))))))
  (testing "no conflicts when categories/fields differ"
    (is (empty? (registry/find-conflicting-rules
                 [{:rule-id :a :category :schema :fields [:email]}
                  {:rule-id :b :category :business :fields [:email]}])))))
