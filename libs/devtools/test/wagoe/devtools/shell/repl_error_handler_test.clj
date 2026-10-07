(ns wagoe.devtools.shell.repl-error-handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [wagoe.platform.system :as platform-system]
            [wagoe.devtools.shell.repl-error-handler :as handler]))

(deftest ^:integration handle-repl-error-stores-exception-test
  (testing "handle-repl-error! stores exception in last-exception* atom"
    (reset! handler/last-exception* nil)
    (let [ex (ex-info "test error" {:wagoe/error-code "WGE-201"})]
      (with-out-str (handler/handle-repl-error! ex))
      (is (= ex @handler/last-exception*)))))

(deftest ^:integration handle-repl-error-prints-output-test
  (testing "handle-repl-error! prints formatted output for classified error"
    (let [ex (ex-info "validation failed" {:wagoe/error-code "WGE-201"})
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (str/includes? output "WGE-201"))))

  (testing "handle-repl-error! prints fallback for unclassified error"
    (let [ex (Exception. "mystery error")
          output (with-out-str (handler/handle-repl-error! ex))]
      (is (str/includes? output "mystery error"))
      (is (str/includes? output "explain")))))

(deftest ^:integration the-box-shows-the-message-that-was-classified
  ;; BOU-561: an Integrant build failure wraps the validation error, and the
  ;; box showed neither message, so it could not name the failing key.
  (let [cause  (ex-info "Invalid workflow definition: {:hooks {:on-enter-paid [\"invalid type\"]}}"
                        {:type :validation-error})
        ex     (ex-info "Error on key :acme/workflow when building system" {} cause)
        output (with-out-str (handler/handle-repl-error! ex))]
    (is (str/includes? output "WGE-201"))
    (is (str/includes? output ":on-enter-paid"))))

(deftest ^:integration the-dashboard-link-uses-the-running-dashboard-port
  ;; BOU-561: the link was always :9999.
  (let [ex (ex-info "validation failed" {:wagoe/error-code "WGE-201"})]
    (testing "the port the dashboard is serving on, from a REPL (go)"
      (with-redefs [state/system            {:wagoe/dashboard {:port 9990}}
                    platform-system/running (constantly nil)]
        (let [output (with-out-str (handler/handle-repl-error! ex))]
          (is (str/includes? output "http://localhost:9990/dashboard/errors"))
          (is (not (str/includes? output "9999"))))))

    (testing "or from a system started by wagoe.main"
      (with-redefs [state/system            nil
                    platform-system/running (constantly {:wagoe/dashboard {:port 9970}})]
        (is (str/includes? (with-out-str (handler/handle-repl-error! ex))
                           "http://localhost:9970/dashboard/errors"))))

    (testing "no link when no dashboard is running, whatever the config says"
      (with-redefs [state/system            nil
                    state/config            {:wagoe/dashboard {:port 9980}}
                    platform-system/running (constantly nil)]
        (is (not (str/includes? (with-out-str (handler/handle-repl-error! ex))
                                "Dashboard:")))))))

(deftest ^:integration handle-repl-error-nil-safe-test
  (testing "handle-repl-error! handles nil gracefully"
    (is (= "" (with-out-str (handler/handle-repl-error! nil))))))
