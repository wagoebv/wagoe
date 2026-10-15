(ns wagoe.observability.logging.shell.redacting-factory-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [wagoe.observability.logging.shell.redacting-factory :as sut])
  (:import [java.sql SQLException]))

(deftest ^:unit a-direct-log-call-writes-no-quoted-value
  ;; BOU-634: `(log/error e …)` bypasses the observability adapters.
  (let [the-log (log-test/atomic-log log-test/->LogEntry)
        driver  (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)]
    (binding [log/*logger-factory* (sut/redacting (log-test/logger-factory the-log (constantly true)))]
      (log/error (ex-info "save failed" {} driver) "Save failed"))
    (let [[{:keys [level throwable message]}] (log-test/entries the-log)]
      (is (= :error level))
      (is (= "Save failed" message))
      (is (not-any? #(.contains (str (ex-message %)) "alice@example.com")
                    (take-while some? (iterate ex-cause throwable)))))))

(deftest ^:unit a-failing-redaction-does-not-fail-the-log-call
  (let [the-log (log-test/atomic-log log-test/->LogEntry)
        ;; ex-data that cannot be walked makes redaction throw.
        broken  (ex-info "save failed" {:rows (lazy-seq (throw (IllegalStateException.)))}
                         (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062))]
    (binding [log/*logger-factory* (sut/redacting (log-test/logger-factory the-log (constantly true)))]
      (log/error broken "Save failed"))
    (let [[{:keys [throwable]}] (log-test/entries the-log)]
      (is (some? throwable))
      (is (not (.contains (str (ex-message throwable)) "alice@example.com"))))))

(deftest ^:unit wrapping-twice-is-wrapping-once
  (let [f (sut/redacting (log-test/logger-factory (log-test/atomic-log log-test/->LogEntry) (constantly true)))]
    (is (identical? f (sut/redacting f)))))

(deftest ^:unit loading-installs-it
  (is (instance? wagoe.observability.logging.shell.redacting_factory.RedactingFactory
                 (.getRawRoot #'log/*logger-factory*))))
