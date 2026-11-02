(ns wagoe.audience.core.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.compiler :as compiler]))

(deftest ^:unit compile-all-sql-filters
  (testing "all DB-evaluable filters go to :query-filters"
    (let [plan (compiler/compile-segment
                {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                           {:type :location :field :country :op :in :value ["NL"]}]})]
      (is (= 2 (count (:query-filters plan))))
      (is (empty? (:predicates plan))))))

(deftest ^:unit compile-mixed-filters
  (testing "filters partitioned into sql + predicates"
    (let [pred-fn (fn [_] true)
          plan (compiler/compile-segment
                {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                           {:type :behavior :op :fn :value pred-fn}]})]
      (is (= 1 (count (:query-filters plan))))
      (is (= 1 (count (:predicates plan)))))))

(deftest ^:unit compile-no-filters
  (testing "empty filters produce empty plan"
    (let [plan (compiler/compile-segment {:filters []})]
      (is (empty? (:query-filters plan)))
      (is (empty? (:predicates plan))))))

(deftest ^:unit compile-all-predicate-filters
  (testing "all predicate-only filters, no SQL clauses"
    (let [plan (compiler/compile-segment
                {:filters [{:type :behavior :op :fn :value (fn [_] true)}
                           {:type :behavior :op :fn :value (fn [_] false)}]})]
      (is (empty? (:query-filters plan)))
      (is (= 2 (count (:predicates plan)))))))

(deftest ^:unit a-date-filter-without-a-date-is-reported-not-guessed
  ;; `compile-segment`'s one-argument arity supplies no `:now`, and the date
  ;; filters need one on both sides since their cutoff became a bound parameter
  ;; (BOU-425). Building a predicate anyway produced one that closed over nil
  ;; and threw on the first user carrying the timestamp; dropping the filter
  ;; silently would have resolved the audience to *everyone*, since an empty
  ;; plan is the universe. It is reported instead.
  (doseq [filt [{:type :last-active :op :within-days :value 30}
                {:type :account-tenure :op :gte :value 30}]]
    (testing (str (:type filt) " without a date")
      (let [plan (compiler/compile-segment {:filters [filt]})]
        (is (empty? (:query-filters plan)))
        (is (empty? (:predicates plan))
            "a predicate was built that can only throw when it runs")
        (is (= [filt] (:unsupported plan)))))

    (testing (str (:type filt) " with one compiles to SQL")
      (let [plan (compiler/compile-segment {:filters [filt]}
                                           {:now (java.time.LocalDate/of 2026 9 10)})]
        (is (= 1 (count (:query-filters plan))))
        (is (empty? (:unsupported plan)))))

    (testing (str (:type filt) " carrying its own :now compiles too")
      ;; The escape the docstring documents: `:now` on the filter itself.
      (let [plan (compiler/compile-segment
                  {:filters [(assoc filt :now (java.time.LocalDate/of 2026 9 10))]})]
        (is (= 1 (count (:query-filters plan))))
        (is (empty? (:unsupported plan)))))))
