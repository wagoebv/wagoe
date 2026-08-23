(ns wagoe.observability.logging.shell.time
  "The clock, read once for this module's adapters.

   `current-timestamp` was defined identically in the stdout and datadog
   logging adapters, both formatting an ISO-8601 string (BOU-302).

   Scoped to `logging` rather than to the library: observability is four
   modules — errors, logging, metrics, tracing — and `check:ports` treats a
   require across them as a boundary crossing, correctly. The errors adapter
   keeps its own one-line clock read for that reason."
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

(defn now-iso
  "The current instant as an ISO-8601 string — what log lines carry."
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))
