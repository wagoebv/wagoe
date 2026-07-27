(ns wagoe.devtools.core.state-analyzer-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.state-analyzer :as analyzer]))

(deftest ^:unit analyze-modules-test
  (testing "detects unintegrated modules"
    (let [result (analyzer/analyze-modules #{"user" "admin" "invoice"} #{"user" "admin"})]
      (is (= :unintegrated-modules (:type result)))
      (is (= :warn (:level result)))
      (is (= 1 (:count result)))
      (is (contains? (:modules result) "invoice"))))

  (testing "returns nil when all integrated"
    (is (nil? (analyzer/analyze-modules #{"user" "admin"} #{"user" "admin"})))))

(deftest ^:unit analyze-migrations-test
  (testing "detects pending migrations"
    (let [result (analyzer/analyze-migrations {:pending 2 :pending-names ["001" "002"]})]
      (is (= :pending-migrations (:type result)))
      (is (= 2 (:count result)))))

  (testing "returns nil when no pending"
    (is (nil? (analyzer/analyze-migrations {:pending 0})))))

(deftest ^:unit analyze-seeds-test
  (testing "detects missing seed file"
    (let [result (analyzer/analyze-seeds false)]
      (is (= :missing-seeds (:type result)))))

  (testing "returns nil when seed file exists"
    (is (nil? (analyzer/analyze-seeds true)))))

(deftest ^:unit analyze-tests-test
  (testing "reports passing tests"
    (let [result (analyzer/analyze-tests {:total 42 :pass 42 :fail 0 :error 0})]
      (is (= :tests-passing (:type result)))
      (is (= :pass (:level result)))))

  (testing "reports failing tests"
    (let [result (analyzer/analyze-tests {:total 42 :pass 40 :fail 2 :error 0})]
      (is (= :tests-failing (:type result)))
      (is (= :error (:level result)))))

  (testing "returns nil when no test data"
    (is (nil? (analyzer/analyze-tests {:total nil})))))

(deftest ^:unit format-findings-test
  (testing "formats findings into display string"
    (let [findings [{:level :warn :msg "1 pending migration" :fix "bb migrate up"}
                    {:level :pass :msg "All tests passing (42 tests)" :fix nil}]
          result   (analyzer/format-findings findings 3)]
      (is (clojure.string/includes? result "3 modules"))
      (is (clojure.string/includes? result "pending migration"))
      (is (clojure.string/includes? result "All tests passing")))))
