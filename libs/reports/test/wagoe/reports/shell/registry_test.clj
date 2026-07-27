(ns wagoe.reports.shell.registry-test
  "Unit tests for the report definition registry (shell state)."
  (:require [wagoe.reports.shell.registry :as registry]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; =============================================================================
;; Test fixture — clear registry between tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)
    (registry/clear-registry!)))

;; =============================================================================
;; defreport macro and registry
;; =============================================================================

;; Defined at top level so clj-kondo can resolve the symbol.
;; clear-registry! is called in the :each fixture so the registry stays clean.
(registry/defreport test-report-a
  {:id       :test-report-a
   :type     :pdf
   :template (fn [_] [:html [:body [:h1 "Test"]]])})

(deftest ^:unit defreport-macro-test
  (testing "defreport binds var to definition map"
    (is (= :test-report-a (:id test-report-a)))
    (is (= :pdf (:type test-report-a))))
  (testing "defreport registers definition in the registry"
    ;; Re-register since the :each fixture clears the registry before each test.
    (registry/register-report! test-report-a)
    (is (= test-report-a (registry/get-report :test-report-a))))
  (testing "list-reports includes registered id"
    (registry/register-report! test-report-a)
    (is (some #{:test-report-a} (registry/list-reports)))))

(deftest ^:unit register-report-test
  (testing "programmatic registration via register-report!"
    (let [defn {:id :prog-report :type :excel}]
      (registry/register-report! defn)
      (is (= defn (registry/get-report :prog-report))))))
