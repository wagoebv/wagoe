(ns wagoe.audience.shell.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.audience.shell.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)))

(deftest ^:unit defaudience-registers-definition
  (testing "defaudience creates var and registers by :id"
    (registry/defaudience test-segment
      {:id      :test-segment
       :label   "Test"
       :filters [{:type :demographics :field :plan :op :eq :value "free"}]})
    (is (= :test-segment (:id test-segment)))
    (is (= "Test" (:label (registry/get-audience :test-segment))))))

(deftest ^:unit registry-operations
  (testing "list-audiences returns registered ids"
    (registry/register-audience! {:id :seg-a :label "A" :filters []})
    (registry/register-audience! {:id :seg-b :label "B" :filters []})
    (is (= #{:seg-a :seg-b} (set (registry/list-audiences)))))

  (testing "get-audience returns nil for unknown id"
    (is (nil? (registry/get-audience :nonexistent))))

  (testing "clear-registry! empties registry"
    (registry/register-audience! {:id :seg-c :label "C" :filters []})
    (registry/clear-registry!)
    (is (empty? (registry/list-audiences)))))
