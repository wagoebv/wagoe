(ns wagoe.audience.shell.security-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.filter :as f]
            [malli.core :as m]
            [wagoe.audience.schema :as schema]))

(deftest ^:security ^:unit filter-values-parameterized
  (testing "SQL injection via filter :value is parameterized by HoneySQL"
    (let [malicious "'; DROP TABLE users; --"
          result (f/filter->sql {:type :demographics :field :email :op :eq :value malicious})]
      (is (vector? result))
      (is (= malicious (last result))))))

(deftest ^:security ^:unit dynamic-segment-rejects-fn-values
  (testing "DynamicAudienceDefinition rejects fn-typed :value"
    (let [definition {:id :evil :label "Evil" :filters [{:type :behavior :op :fn :value identity}]}]
      (is (not (m/validate schema/DynamicAudienceDefinition definition))))))
