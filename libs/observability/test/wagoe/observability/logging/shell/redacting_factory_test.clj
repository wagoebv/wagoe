(ns wagoe.observability.logging.shell.redacting-factory-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [wagoe.observability.logging.shell.redacting-factory :as sut])
  (:import [java.sql SQLException]))

(defn- capturing-factory
  "A factory whose loggers keep what they were asked to write."
  [written]
  (reify impl/LoggerFactory
    (name [_] "capturing")
    (get-logger [_ _]
      (reify impl/Logger
        (enabled? [_ _] true)
        (write! [_ level throwable message] (swap! written conj [level throwable message]))))))

(deftest ^:unit a-direct-log-call-writes-no-quoted-value
  ;; BOU-634: `(log/error e …)` bypasses the observability adapters.
  (let [written (atom [])
        driver  (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)]
    (binding [log/*logger-factory* (sut/redacting (capturing-factory written))]
      (log/error (ex-info "save failed" {} driver) "Save failed"))
    (let [[[level throwable message]] @written]
      (is (= :error level))
      (is (= "Save failed" message))
      (is (not-any? #(.contains (str (ex-message %)) "alice@example.com")
                    (take-while some? (iterate ex-cause throwable)))))))

(deftest ^:unit wrapping-twice-is-wrapping-once
  (let [f (sut/redacting (capturing-factory (atom [])))]
    (is (identical? f (sut/redacting f)))))
