(ns wagoe.observability.tracing.shell.adapters.no-op
  "No-op tracer: satisfies ITracer but records nothing. The default, so feature
   code can use the tracing port even when tracing is disabled."
  (:require [wagoe.observability.tracing.ports :as ports]))

(defrecord NoOpTracer []
  ports/ITracer
  (start-span! [_ _] ::no-op-span)
  (start-span! [_ _ _] ::no-op-span)
  (end-span! [_ _] nil)
  (add-event! [_ _ _] nil)
  (add-event! [_ _ _ _] nil)
  (set-attributes! [_ _ _] nil)
  (record-exception! [_ _ _] nil)
  (span-context [_ _] {:trace-id nil :span-id nil})
  (with-span* [_ _ _ f] (f ::no-op-span)))

(defn create-tracer
  ([] (->NoOpTracer))
  ([_config] (->NoOpTracer)))

(defn create-tracing-component
  "The wiring entry point (mirrors the metrics/errors adapters)."
  ([] (->NoOpTracer))
  ([_config] (->NoOpTracer)))
