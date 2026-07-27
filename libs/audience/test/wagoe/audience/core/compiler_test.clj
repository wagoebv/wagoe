(ns wagoe.audience.core.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.compiler :as compiler]))

(deftest ^:unit compile-all-sql-filters
  (testing "all DB-evaluable filters go to :sql-clauses"
    (let [plan (compiler/compile-segment
                {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                           {:type :location :field :country :op :in :value ["NL"]}]})]
      (is (= 2 (count (:sql-clauses plan))))
      (is (empty? (:predicates plan))))))

(deftest ^:unit compile-mixed-filters
  (testing "filters partitioned into sql + predicates"
    (let [pred-fn (fn [_] true)
          plan (compiler/compile-segment
                {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                           {:type :behavior :op :fn :value pred-fn}]})]
      (is (= 1 (count (:sql-clauses plan))))
      (is (= 1 (count (:predicates plan)))))))

(deftest ^:unit compile-no-filters
  (testing "empty filters produce empty plan"
    (let [plan (compiler/compile-segment {:filters []})]
      (is (empty? (:sql-clauses plan)))
      (is (empty? (:predicates plan))))))

(deftest ^:unit compile-all-predicate-filters
  (testing "all predicate-only filters, no SQL clauses"
    (let [plan (compiler/compile-segment
                {:filters [{:type :behavior :op :fn :value (fn [_] true)}
                           {:type :behavior :op :fn :value (fn [_] false)}]})]
      (is (empty? (:sql-clauses plan)))
      (is (= 2 (count (:predicates plan)))))))
