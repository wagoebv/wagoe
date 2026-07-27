(ns wagoe.core.validation.coverage-test
  "Tests for pure coverage computation and reporting."
  (:require [wagoe.core.validation.coverage :as coverage]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

;; Tag all tests for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3])

;; -----------------------------------------------------------------------------
;; Coverage Computation Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 compute-basic-coverage-test
  (testing "Compute coverage with basic data"
    (let [result (coverage/compute
                  {:registered #{:user.email/required :user.name/required}
                   :executed #{:user.email/required}})]
      (is (= 2 (:total result)))
      (is (= 1 (:executed result)))
      (is (= 50.0 (:pct result)))
      (is (= #{:user.name/required} (:missing result))))))

(deftest ^:unit ^:phase3 compute-full-coverage-test
  (testing "Compute 100% coverage"
    (let [rules #{:user.email/required :user.name/required}
          result (coverage/compute {:registered rules :executed rules})]
      (is (= 2 (:total result)))
      (is (= 2 (:executed result)))
      (is (= 100.0 (:pct result)))
      (is (empty? (:missing result))))))

(deftest ^:unit ^:phase3 compute-zero-coverage-test
  (testing "Compute 0% coverage"
    (let [result (coverage/compute
                  {:registered #{:user.email/required :user.name/required}
                   :executed #{}})]
      (is (= 2 (:total result)))
      (is (= 0 (:executed result)))
      (is (= 0.0 (:pct result)))
      (is (= #{:user.email/required :user.name/required} (:missing result))))))

(deftest ^:unit ^:phase3 compute-empty-registered-test
  (testing "Compute with no registered rules"
    (let [result (coverage/compute {:registered #{} :executed #{}})]
      (is (= 0 (:total result)))
      (is (= 0 (:executed result)))
      (is (= 0.0 (:pct result)))
      (is (empty? (:missing result))))))

(deftest ^:unit ^:phase3 compute-with-modules-test
  (testing "Compute coverage with per-module breakdown"
    (let [result (coverage/compute
                  {:registered #{:user.email/required :user.name/required
                                 :billing.amount/positive :billing.currency/valid}
                   :executed #{:user.email/required :billing.amount/positive}
                   :by-module {:user #{:user.email/required :user.name/required}
                               :billing #{:billing.amount/positive :billing.currency/valid}}})]
      ;; Overall
      (is (= 4 (:total result)))
      (is (= 2 (:executed result)))
      (is (= 50.0 (:pct result)))
      ;; User module
      (is (= 2 (get-in result [:per-module :user :total])))
      (is (= 1 (get-in result [:per-module :user :executed])))
      (is (= 50.0 (get-in result [:per-module :user :pct])))
      (is (= #{:user.name/required} (get-in result [:per-module :user :missing])))
      ;; Billing module  
      (is (= 2 (get-in result [:per-module :billing :total])))
      (is (= 1 (get-in result [:per-module :billing :executed])))
      (is (= 50.0 (get-in result [:per-module :billing :pct])))
      (is (= #{:billing.currency/valid} (get-in result [:per-module :billing :missing]))))))

(deftest ^:unit ^:phase3 compute-extra-executed-rules-test
  (testing "Compute handles executed rules not in registered"
    (let [result (coverage/compute
                  {:registered #{:user.email/required}
                   :executed #{:user.email/required :user.name/required}})]
      (is (= 1 (:total result)))
      (is (= 1 (:executed result)))
      (is (= 100.0 (:pct result)))
      (is (empty? (:missing result))))))

;; -----------------------------------------------------------------------------
;; Merge Executions Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 merge-executions-test
  (testing "Merge multiple execution sets"
    (let [result (coverage/merge-executions
                  [#{:user.email/required}
                   #{:user.name/required}
                   #{:billing.amount/positive}])]
      (is (= 3 (count result)))
      (is (contains? result :user.email/required))
      (is (contains? result :user.name/required))
      (is (contains? result :billing.amount/positive)))))

(deftest ^:unit ^:phase3 merge-executions-empty-test
  (testing "Merge empty execution sets"
    (let [result (coverage/merge-executions [])]
      (is (empty? result)))))

(deftest ^:unit ^:phase3 merge-executions-overlapping-test
  (testing "Merge overlapping execution sets"
    (let [result (coverage/merge-executions
                  [#{:user.email/required :user.name/required}
                   #{:user.name/required :billing.amount/positive}])]
      (is (= 3 (count result)))
      (is (contains? result :user.email/required))
      (is (contains? result :user.name/required))
      (is (contains? result :billing.amount/positive)))))

;; -----------------------------------------------------------------------------
;; EDN Report Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 edn-report-basic-test
  (testing "Generate basic EDN report"
    (let [cov {:total 10 :executed 8 :pct 80.0 :missing #{:rule1 :rule2}}
          report (coverage/edn-report cov {:timestamp "2025-01-04"})]
      (is (= 80.0 (:coverage report)))
      (is (= 10 (:total report)))
      (is (= 8 (:executed report)))
      (is (= "2025-01-04" (:timestamp report)))
      (is (= [:rule1 :rule2] (:missing report))))))

(deftest ^:unit ^:phase3 edn-report-with-modules-test
  (testing "Generate EDN report with module breakdown"
    (let [cov {:total 10 :executed 8 :pct 80.0
               :per-module {:user {:total 5 :executed 4 :pct 80.0 :missing #{:r1}}
                            :billing {:total 5 :executed 4 :pct 80.0 :missing #{:r2}}}
               :missing #{:r1 :r2}}
          report (coverage/edn-report cov {:timestamp "2025-01-04"})]
      (is (map? (:by-module report)))
      (is (= 80.0 (get-in report [:by-module :user :coverage])))
      (is (= 80.0 (get-in report [:by-module :billing :coverage]))))))

(deftest ^:unit ^:phase3 edn-report-with-metadata-test
  (testing "Generate EDN report with custom metadata"
    (let [cov {:total 10 :executed 8 :pct 80.0 :missing #{}}
          report (coverage/edn-report cov {:metadata {:run-id "test-123" :env "ci"}})]
      (is (= "test-123" (get-in report [:metadata :run-id])))
      (is (= "ci" (get-in report [:metadata :env]))))))

;; -----------------------------------------------------------------------------
;; Human Report Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 human-report-basic-test
  (testing "Generate basic human-readable report"
    (let [cov {:total 10 :executed 8 :pct 80.0 :missing #{:rule1 :rule2}}
          report (coverage/human-report cov {})]
      (is (str/includes? report "Validation Coverage Report"))
      (is (str/includes? report "80.0%"))
      (is (str/includes? report "(8/10)"))
      (is (str/includes? report ":rule1"))
      (is (str/includes? report ":rule2")))))

(deftest ^:unit ^:phase3 human-report-with-modules-test
  (testing "Generate report with module breakdown"
    (let [cov {:total 10 :executed 8 :pct 80.0
               :per-module {:user {:total 5 :executed 4 :pct 80.0 :missing #{}}
                            :billing {:total 5 :executed 4 :pct 80.0 :missing #{}}}
               :missing #{}}
          report (coverage/human-report cov {})]
      (is (str/includes? report "By Module:"))
      (is (str/includes? report "user: 80.0%"))
      (is (str/includes? report "billing: 80.0%")))))

(deftest ^:unit ^:phase3 human-report-hide-missing-test
  (testing "Generate report without missing rules"
    (let [cov {:total 10 :executed 8 :pct 80.0 :missing #{:rule1 :rule2}}
          report (coverage/human-report cov {:show-missing false})]
      (is (not (str/includes? report "Missing Rules:")))
      (is (not (str/includes? report ":rule1"))))))

(deftest ^:unit ^:phase3 human-report-hide-modules-test
  (testing "Generate report without module breakdown"
    (let [cov {:total 10 :executed 8 :pct 80.0
               :per-module {:user {:total 5 :executed 4 :pct 80.0}}
               :missing #{}}
          report (coverage/human-report cov {:show-modules false})]
      (is (not (str/includes? report "By Module:")))
      (is (not (str/includes? report "user:"))))))

;; -----------------------------------------------------------------------------
;; Summary Line Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 summary-line-test
  (testing "Generate summary line"
    (let [cov {:total 10 :executed 8 :pct 80.0}
          line (coverage/summary-line cov)]
      (is (= "Coverage: 80.0% (8/10 rules executed)" line)))))

(deftest ^:unit ^:phase3 summary-line-100-pct-test
  (testing "Generate summary line for 100% coverage"
    (let [cov {:total 5 :executed 5 :pct 100.0}
          line (coverage/summary-line cov)]
      (is (= "Coverage: 100.0% (5/5 rules executed)" line)))))

;; -----------------------------------------------------------------------------
;; Coverage Comparison Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 compare-coverage-improved-test
  (testing "Compare coverage showing improvement"
    (let [before {:total 10 :executed 7 :pct 70.0 :missing #{:r1 :r2 :r3}}
          after {:total 10 :executed 9 :pct 90.0 :missing #{:r1}}
          result (coverage/compare-coverage before after)]
      (is (= 20.0 (:delta-pct result)))
      (is (= 2 (:delta-executed result)))
      (is (true? (:improved? result)))
      (is (= #{:r2 :r3} (:new-rules result)))
      (is (empty? (:lost-rules result))))))

(deftest ^:unit ^:phase3 compare-coverage-declined-test
  (testing "Compare coverage showing decline"
    (let [before {:total 10 :executed 9 :pct 90.0 :missing #{:r1}}
          after {:total 10 :executed 7 :pct 70.0 :missing #{:r1 :r2 :r3}}
          result (coverage/compare-coverage before after)]
      (is (= -20.0 (:delta-pct result)))
      (is (= -2 (:delta-executed result)))
      (is (false? (:improved? result)))
      (is (empty? (:new-rules result)))
      (is (= #{:r2 :r3} (:lost-rules result))))))

(deftest ^:unit ^:phase3 compare-coverage-no-change-test
  (testing "Compare coverage with no change"
    (let [before {:total 10 :executed 8 :pct 80.0 :missing #{:r1 :r2}}
          after {:total 10 :executed 8 :pct 80.0 :missing #{:r1 :r2}}
          result (coverage/compare-coverage before after)]
      (is (= 0.0 (:delta-pct result)))
      (is (= 0 (:delta-executed result)))
      (is (false? (:improved? result)))
      (is (empty? (:new-rules result)))
      (is (empty? (:lost-rules result))))))

;; -----------------------------------------------------------------------------
;; Filter By Module Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 filter-by-module-test
  (testing "Filter coverage to specific modules"
    (let [cov {:total 10 :executed 7 :pct 70.0
               :per-module {:user {:total 5 :executed 4 :pct 80.0 :missing #{:r1}}
                            :billing {:total 3 :executed 2 :pct 66.67 :missing #{:r2}}
                            :workflow {:total 2 :executed 1 :pct 50.0 :missing #{:r3}}}
               :missing #{:r1 :r2 :r3}}
          filtered (coverage/filter-by-module cov #{:user :billing})]
      (is (= 8 (:total filtered)))
      (is (= 6 (:executed filtered)))
      (is (= 75.0 (:pct filtered)))
      (is (= #{:r1 :r2} (:missing filtered)))
      (is (= 2 (count (:per-module filtered))))
      (is (contains? (:per-module filtered) :user))
      (is (contains? (:per-module filtered) :billing))
      (is (not (contains? (:per-module filtered) :workflow))))))

(deftest ^:unit ^:phase3 filter-by-module-empty-test
  (testing "Filter to no modules returns empty coverage"
    (let [cov {:total 10 :executed 7 :pct 70.0
               :per-module {:user {:total 5 :executed 4 :pct 80.0 :missing #{}}}
               :missing #{}}
          filtered (coverage/filter-by-module cov #{})]
      (is (= 0 (:total filtered)))
      (is (= 0 (:executed filtered)))
      (is (= 0.0 (:pct filtered)))
      (is (empty? (:per-module filtered))))))

;; -----------------------------------------------------------------------------
;; Edge Cases and Error Handling
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 compute-with-nil-values-test
  (testing "Compute handles nil registered/executed sets"
    (let [result (coverage/compute {})]
      (is (= 0 (:total result)))
      (is (= 0 (:executed result)))
      (is (= 0.0 (:pct result)))
      (is (empty? (:missing result))))))

(deftest ^:unit ^:phase3 deterministic-missing-order-test
  (testing "Missing rules are deterministically ordered in EDN report"
    (let [cov {:total 5 :executed 2 :pct 40.0
               :missing #{:z :a :m :b}}
          report (coverage/edn-report cov {})]
      (is (= [:a :b :m :z] (:missing report))))))

(deftest ^:unit ^:phase3 percentage-precision-test
  (testing "Percentage formatting has 1 decimal place"
    (let [cov {:total 3 :executed 1 :pct 33.333333}
          line (coverage/summary-line cov)]
      (is (str/includes? line "33.3%")))))
