(ns wagoe.platform.shell.validation-registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.shell.validation-registry :as registry]))

(use-fixtures :each (fn [f] (registry/clear-registry!) (f) (registry/clear-registry!)))

(def ^:private rule-a
  {:rule-id :user.email/required :description "Email required" :category :schema
   :module :user :fields [:email] :error-code :required :validator-fn some?})

(def ^:private rule-b
  {:rule-id :user.name/required :description "Name required" :category :schema
   :module :user :fields [:name] :error-code :required :validator-fn some?})

(deftest ^:unit register-and-lookup
  (testing "register-rule! stores and returns the rule"
    (is (= rule-a (registry/register-rule! rule-a)))
    (is (= rule-a (registry/get-rule :user.email/required))))
  (testing "get-rule returns nil for unknown id"
    (is (nil? (registry/get-rule :nope))))
  (testing "register-rule! rejects a rule with no :rule-id"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :rule-id"
                          (registry/register-rule! {:description "x"}))))
  (testing "register-rule! rejects a duplicate id"
    ;; rule-a already registered by the first testing block above
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already registered"
                          (registry/register-rule! rule-a)))))

(deftest ^:unit query-and-clear
  (registry/register-rules! [rule-a rule-b])
  (testing "get-all-rules / by-module / by-category / for-field"
    (is (= 2 (count (registry/get-all-rules))))
    (is (= 2 (count (registry/get-rules-by-module :user))))
    (is (= 2 (count (registry/get-rules-by-category :schema))))
    (is (= [rule-a] (registry/get-rules-for-field :email))))
  (testing "registry-ready? / clear-registry!"
    (is (true? (registry/registry-ready?)))
    (registry/clear-registry!)
    (is (false? (registry/registry-ready?)))
    (is (empty? (registry/get-all-rules)))))

(deftest ^:unit execution-tracking
  (registry/register-rules! [rule-a rule-b])
  (testing "track + count"
    (registry/track-rule-execution! :user.email/required)
    (registry/track-rule-execution! :user.email/required)
    (is (= 2 (registry/get-execution-count :user.email/required)))
    (is (= 0 (registry/get-execution-count :user.name/required))))
  (testing "coverage stats"
    (let [stats (registry/get-execution-stats)]
      (is (= 2 (:total-rules stats)))
      (is (= 1 (:executed-rules stats)))
      (is (= 50.0 (:coverage-percent stats)))))
  (testing "reset"
    (registry/reset-execution-tracking!)
    (is (= 0 (registry/get-execution-count :user.email/required)))))

(deftest ^:unit stats-and-conflicts
  (registry/register-rules! [rule-a rule-b
                             {:rule-id :dup :description "d" :category :schema
                              :module :user :fields [:email] :error-code :x
                              :validator-fn some?}])
  (testing "registry-stats"
    (let [s (registry/registry-stats)]
      (is (= 3 (:total-rules s)))
      (is (= {:user 3} (:by-module s)))))
  (testing "conflicting-rules delegates to the pure core helper"
    (let [conflicts (registry/conflicting-rules)]
      (is (some #(= #{:user.email/required :dup} (set (:rule-ids %))) conflicts)))))
