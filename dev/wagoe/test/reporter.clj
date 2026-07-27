(ns wagoe.test.reporter
  "Custom Kaocha reporter that shows a green ✓ for passing tests
   and a red ✗ for failing tests.

   Configure in tests.edn:
     :kaocha/reporter [wagoe.test.reporter/reporter]"
  (:require [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.output :as output]
            [kaocha.report :as report]))

;; Tracks whether the current test var has any failures or errors.
;; Reset at the start of each test var (:kaocha/begin-test).
(def ^:private test-failed? (atom false))

(defmulti pretty :type :hierarchy #'hierarchy/hierarchy)

(defmethod pretty :default [_])

(defmethod pretty :kaocha/begin-suite [m]
  (t/with-test-out
    (println (str "\n" (-> m :kaocha/testable :kaocha.testable/desc)))))

(defmethod pretty :kaocha/begin-group [m]
  (t/with-test-out
    (println (str "\n  " (-> m :kaocha/testable :kaocha.testable/desc)))))

(defmethod pretty :kaocha/begin-test [_]
  (reset! test-failed? false))

(defmethod pretty :kaocha/fail-type [_]
  (reset! test-failed? true))

(defmethod pretty :error [_]
  (reset! test-failed? true))

(defmethod pretty :kaocha/end-test [m]
  (t/with-test-out
    (let [desc (-> m :kaocha/testable :kaocha.testable/desc)]
      (if @test-failed?
        (println (str "    " desc " " (output/colored :red "✗")))
        (println (str "    " desc " " (output/colored :green "✓")))))))

;; reporter is a vector: kaocha applies each reporter to every event.
;; - pretty    → visual structure with ✓/✗ symbols
;; - report/result → prints failure details + final summary counts
(def reporter [pretty report/result])
