(ns wagoe.mcp.shell.audit
  "Audit sinks for security events. The logging sink writes structured JSON
   events to stderr via tools.logging (never stdout — that stream is reserved
   for the protocol; see ADR-031). The in-memory sink supports tests."
  (:require [wagoe.mcp.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn- render
  "Serialize an audit event to JSON, falling back to pr-str so a non-encodable
   value can never make auditing throw into the caller's path."
  [event]
  (try
    (json/generate-string event)
    (catch Exception _
      (pr-str event))))

(defrecord LoggingAuditLog []
  ports/AuditLog
  (record! [_ event]
    (log/info (str "AUDIT " (render event)))
    event))

(defn logging-audit-log
  "Audit sink that emits each event as a JSON line to the log (stderr)."
  []
  (->LoggingAuditLog))

(defrecord InMemoryAuditLog [events]
  ports/AuditLog
  (record! [_ event]
    (swap! events conj event)
    event))

(defn in-memory-audit-log
  "Audit sink that accumulates events in an atom. For tests."
  []
  (->InMemoryAuditLog (atom [])))

(defn events
  "All events recorded by an in-memory audit sink, in order."
  [audit]
  @(:events audit))
