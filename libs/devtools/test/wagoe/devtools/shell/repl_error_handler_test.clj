(ns wagoe.devtools.shell.repl-error-handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.devtools.shell.repl-error-handler :as handler]))

(deftest ^:integration handle-repl-error-stores-exception-test
  (testing "handle-repl-error! stores exception in last-exception* atom"
    (reset! handler/last-exception* nil)
    (let [ex (ex-info "test error" {:boundary/error-code "BND-201"})]
      (with-out-str (handler/handle-repl-error! ex))
      (is (= ex @handler/last-exception*)))))

(deftest ^:integration handle-repl-error-prints-output-test
  (testing "handle-repl-error! prints formatted output for classified error"
    (let [ex (ex-info "validation failed" {:boundary/error-code "BND-201"})
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (str/includes? output "BND-201"))))

  (testing "handle-repl-error! prints fallback for unclassified error"
    (let [ex (Exception. "mystery error")
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (str/includes? output "mystery error"))
      (is (str/includes? output "explain")))))

(deftest ^:integration handle-repl-error-nil-safe-test
  (testing "handle-repl-error! handles nil gracefully"
    (is (= "" (with-out-str (handler/handle-repl-error! nil))))))
