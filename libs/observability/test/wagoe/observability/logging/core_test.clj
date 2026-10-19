(ns wagoe.observability.logging.core-test
  "Unit tests for wagoe.observability.logging.core namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.logging.core :as logging-core]
            [wagoe.observability.logging.ports :as ports]))

(deftest ^:unit merge-contexts-test
  (testing "merges multiple context maps"
    (is (= {:a 1 :b 2 :c 3}
           (logging-core/merge-contexts {:a 1} {:b 2} {:c 3}))))

  (testing "later maps override earlier keys"
    (is (= {:a 2} (logging-core/merge-contexts {:a 1} {:a 2}))))

  (testing "single context passes through"
    (is (= {:a 1} (logging-core/merge-contexts {:a 1}))))

  (testing "nil contexts produce nil entries"
    (is (= {:a 1} (logging-core/merge-contexts nil {:a 1})))))

(deftest ^:unit with-correlation-id-test
  (testing "adds correlation-id to context"
    (is (= {:correlation-id "abc-123"}
           (logging-core/with-correlation-id {} "abc-123"))))

  (testing "overwrites existing correlation-id"
    (is (= {:correlation-id "new-id"}
           (logging-core/with-correlation-id {:correlation-id "old-id"} "new-id")))))

(deftest ^:unit with-tenant-id-test
  (testing "adds tenant-id to context"
    (is (= {:tenant-id "t-1"} (logging-core/with-tenant-id {} "t-1"))))

  (testing "preserves other keys"
    (is (= {:foo "bar" :tenant-id "t-2"}
           (logging-core/with-tenant-id {:foo "bar"} "t-2")))))

(deftest ^:unit with-user-id-test
  (testing "adds user-id to context"
    (is (= {:user-id "u-1"} (logging-core/with-user-id {} "u-1")))))

(deftest ^:unit with-tags-test
  (testing "adds tags to context"
    (is (= {:tags {:env "test"}}
           (logging-core/with-tags {} {:env "test"}))))

  (testing "merges with existing tags"
    (is (= {:tags {:env "test" :service "api"}}
           (logging-core/with-tags {:tags {:env "test"}} {:service "api"}))))

  (testing "later tags override"
    (is (= {:tags {:env "prod"}}
           (logging-core/with-tags {:tags {:env "test"}} {:env "prod"})))))

(deftest ^:unit with-trace-info-test
  (testing "adds trace-id and span-id"
    (is (= {:trace-id "t-1" :span-id "s-1"}
           (logging-core/with-trace-info {} "t-1" "s-1"))))

  (testing "preserves other keys"
    (is (= {:foo "bar" :trace-id "t-2" :span-id "s-2"}
           (logging-core/with-trace-info {:foo "bar"} "t-2" "s-2")))))

(deftest ^:unit log-exception-logs-no-quoted-value
  ;; BOU-633: the logger gets neither the value nor an exception carrying it.
  (let [logged (atom nil)
        ;; Only log* is called; the level methods are not.
        logger #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/ILogger
          (log* [_ level message context exception]
            (reset! logged [level message context exception])))
        driver (java.sql.SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)]
    (logging-core/log-exception logger :error "save failed" (ex-info "save failed" {} driver) {})
    (let [[_ _ context exception] @logged]
      (is (not (.contains (pr-str context) "alice@example.com")))
      (is (not-any? #(.contains (str (ex-message %)) "alice@example.com")
                    (take-while some? (iterate ex-cause exception)))))))

(deftest ^:unit log-exception-names-the-thrown-type
  ;; The redacted copy of an IllegalStateException is a RuntimeException.
  (let [logged (atom nil)
        logger #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/ILogger
          (log* [_ _ _ context _] (reset! logged context)))
        driver (java.sql.SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)]
    (logging-core/log-exception logger :error "save failed" (IllegalStateException. "save failed" driver) {})
    (is (= "IllegalStateException" (:exception-type @logged)))))

(defn- recording-logger
  "An ILogger + IAuditLogger that keeps [level message context exception]."
  [calls]
  (reify
    ports/ILogger
    (log* [_ level message context exception] (swap! calls conj [level message context exception]))
    (trace [_ message] (swap! calls conj [:trace message nil nil]))
    (trace [_ message context] (swap! calls conj [:trace message context nil]))
    (debug [_ message] (swap! calls conj [:debug message nil nil]))
    (debug [_ message context] (swap! calls conj [:debug message context nil]))
    (info [_ message] (swap! calls conj [:info message nil nil]))
    (info [_ message context] (swap! calls conj [:info message context nil]))
    (warn [_ message] (swap! calls conj [:warn message nil nil]))
    (warn [_ message context] (swap! calls conj [:warn message context nil]))
    (warn [_ message context e] (swap! calls conj [:warn message context e]))
    (error [_ message] (swap! calls conj [:error message nil nil]))
    (error [_ message context] (swap! calls conj [:error message context nil]))
    (error [_ message context e] (swap! calls conj [:error message context e]))
    (fatal [_ message] (swap! calls conj [:fatal message nil nil]))
    (fatal [_ message context] (swap! calls conj [:fatal message context nil]))
    (fatal [_ message context e] (swap! calls conj [:fatal message context e]))

    ports/IAuditLogger
    (audit-event [_ event-type actor resource action result context]
      (swap! calls conj [:audit event-type actor resource action result context]))
    (security-event [_ event-type severity details context]
      (swap! calls conj [:security event-type severity details context]))))

(deftest ^:unit log-with-timing-test
  (let [calls  (atom [])
        result (logging-core/log-with-timing (recording-logger calls) :info "query" {:table "users"}
                                             (fn [] (Thread/sleep 5) :rows))
        [[level message context]] @calls]
    (is (= :rows result) "the timed function's value is returned")
    (is (= [:info "query"] [level message]))
    (is (= "users" (:table context)))
    (is (>= (:duration-ms context) 5.0))))

(deftest ^:unit with-function-logging-test
  (testing "entry and exit are logged around the call"
    (let [calls (atom [])]
      (is (= 5 (logging-core/with-function-logging (recording-logger calls) "add" {:req "r1"} + 2 3)))
      (is (= [[:debug "Entering add" {:req "r1" :function "add" :args [2 3] :event :function-entry} nil]
              [:debug "Exiting add" {:req "r1" :function "add" :result 5 :event :function-exit} nil]]
             (mapv (fn [[l m c e]] [l m (cond-> c (:args c) (update :args vec)) e]) @calls)))))

  (testing "an exception is logged at error with the exception, then rethrown"
    (let [calls (atom [])
          boom  (IllegalStateException. "boom")]
      (is (identical? boom
                      (try (logging-core/with-function-logging (recording-logger calls) "f" {} #(throw boom))
                           (catch IllegalStateException e e))))
      (is (= [:error "Exception in f" {} boom] (last @calls))))))

(deftest ^:unit event-helpers-log-at-the-right-level-test
  (let [calls  (atom [])
        logger (recording-logger calls)]
    (logging-core/log-validation-error logger [{:field :email}] {:form "signup"})
    (logging-core/log-external-service-error logger "stripe" "charge" "502" {})
    (logging-core/log-user-action logger "u1" "delete" "order/7" :ok {})
    (logging-core/log-business-event logger :order-placed "order/7" {:total 10} {})
    (logging-core/log-performance-metric logger "render" 12 "ms" {})
    (logging-core/log-request-metrics logger "GET" "/a" 200 15 {})
    (is (= [[:warn "Validation failed"]
            [:error "External service error: stripe"]
            [:info "User action: delete"]
            [:info "Business event: :order-placed"]
            [:info "Performance: render = 12ms"]
            [:info "GET /a 200 (15ms)"]]
           (mapv #(subvec % 0 2) @calls)))
    (is (= [:validation-error :external-service-error :user-action
            :business-event :performance-metric :http-request]
           (mapv #(:event (nth % 2)) @calls)))
    (is (= 1 (:error-count (nth (first @calls) 2))))
    (is (= {:http-method "GET" :http-path "/a" :http-status 200 :duration-ms 15 :event :http-request}
           (nth (last @calls) 2)))))

(deftest ^:unit audit-helpers-test
  (let [calls  (atom [])
        logger (recording-logger calls)]
    (logging-core/audit-user-action logger "u1" "invoice/3" :update :success {:ip "1.2.3.4"})
    (logging-core/audit-system-event logger "scheduler" "jobs" :purge :success {})
    (logging-core/audit-security-event logger :login-failed :high {:attempts 3} {})
    (is (= [[:audit :user-action "u1" "invoice/3" :update :success {:ip "1.2.3.4"}]
            [:audit :system-event "scheduler" "jobs" :purge :success {}]
            [:security :login-failed :high {:attempts 3} {}]]
           @calls))))

(deftest ^:unit conditional-logging-test
  (testing "log-when logs only when the condition holds"
    (let [calls (atom [])]
      (logging-core/log-when false (recording-logger calls) :info "no" {})
      (logging-core/log-when true (recording-logger calls) :info "yes" {})
      (is (= [[:info "yes" {} nil]] @calls))))

  (testing "log-when-dev logs only in :development"
    (let [calls (atom [])]
      (logging-core/log-when-dev :production (recording-logger calls) :debug "no" {})
      (logging-core/log-when-dev :development (recording-logger calls) :debug "yes" {})
      (is (= ["yes"] (mapv second @calls)))))

  (testing "log-sampling at the extremes"
    (let [calls (atom [])]
      (dotimes [_ 20] (logging-core/log-sampling 0.0 (recording-logger calls) :info "never" {}))
      (dotimes [_ 20] (logging-core/log-sampling 1.0 (recording-logger calls) :info "always" {}))
      (is (= (repeat 20 "always") (map second @calls))))))
