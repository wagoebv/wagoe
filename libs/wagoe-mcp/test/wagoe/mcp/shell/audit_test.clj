(ns wagoe.mcp.shell.audit-test
  (:require [wagoe.mcp.ports :as ports]
            [wagoe.mcp.shell.audit :as audit]
            [clojure.test :refer [deftest is]]))

(deftest ^:unit in-memory-accumulates-events
  (let [a (audit/in-memory-audit-log)]
    (ports/record! a {:event :server-start})
    (ports/record! a {:event :tool-call :tool "lint"})
    (is (= [{:event :server-start}
            {:event :tool-call :tool "lint"}]
           (audit/events a)))))

(deftest ^:unit record-returns-the-event
  (let [a     (audit/in-memory-audit-log)
        event {:event :denied :tool "eval"}]
    (is (= event (ports/record! a event)))))

(deftest ^:unit logging-sink-records-without-throwing
  ;; Exercises the JSON-encoding path; output goes to the log (stderr).
  (let [a (audit/logging-audit-log)]
    (is (= {:event :server-start :security {:mode :read-only}}
           (ports/record! a {:event :server-start :security {:mode :read-only}})))))

(deftest ^:unit logging-sink-never-throws-on-unencodable-event
  ;; Audit must never break the caller's path, even if an event holds a value
  ;; cheshire cannot serialize (here: a raw Exception object).
  (let [a     (audit/logging-audit-log)
        event {:event :denied :error (Exception. "boom")}]
    (is (= event (ports/record! a event)))))
