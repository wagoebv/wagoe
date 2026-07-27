(ns wagoe.observability.tracing.shell.adapters.logging
  "Logging tracer: records spans to the log (start, end + duration, events,
   exceptions). Useful for local development and for seeing the trace shape
   without standing up an OpenTelemetry collector. Not a sampled/exportable
   tracer — for real distributed tracing use the OTLP adapter.

   A span carries a generated trace-id/span-id, a start timestamp, and an atom
   of accumulated attributes so `set-attributes!` calls made mid-span are folded
   in and appear on the final `span.end` line (matching how a real backend
   mutates its span object)."
  (:require [wagoe.observability.tracing.ports :as ports]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defn- hex-id [n]
  (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 n))

(defrecord LoggingTracer []
  ports/ITracer
  (start-span! [this name] (ports/start-span! this name {}))
  (start-span! [_ name attributes]
    (let [span {:name     (if (keyword? name) (subs (str name) 1) (str name))
                :trace-id (hex-id 32)
                :span-id  (hex-id 16)
                :start-ns (System/nanoTime)
                :attrs    (atom (or attributes {}))}]
      (log/info "span.start" {:name     (:name span)
                              :trace-id (:trace-id span)
                              :span-id  (:span-id span)
                              :attrs    @(:attrs span)})
      span))

  (end-span! [_ span]
    (when span
      (log/info "span.end"
                {:name        (:name span)
                 :trace-id    (:trace-id span)
                 :span-id     (:span-id span)
                 :duration-ms (/ (double (- (System/nanoTime) (:start-ns span))) 1e6)
                 :attrs       (some-> (:attrs span) deref)}))
    nil)

  (add-event! [this span name] (ports/add-event! this span name {}))
  (add-event! [_ span name attributes]
    (log/info "span.event" {:name (str name) :span-id (:span-id span) :attrs attributes})
    nil)

  (set-attributes! [_ span attributes]
    (when-let [a (:attrs span)] (swap! a merge attributes))
    (log/debug "span.attrs" {:span-id (:span-id span) :attrs attributes})
    nil)

  (record-exception! [_ span throwable]
    (log/warn throwable "span.exception" {:span-id (:span-id span)})
    nil)

  (span-context [_ span]
    {:trace-id (:trace-id span) :span-id (:span-id span)})

  (with-span* [this name attributes f]
    (let [span (ports/start-span! this name attributes)]
      (try
        (f span)
        (catch Throwable t
          (ports/record-exception! this span t)
          (throw t))
        (finally
          (ports/end-span! this span))))))

(defn create-tracer
  ([] (->LoggingTracer))
  ([_config] (->LoggingTracer)))

(defn create-tracing-component
  ([] (->LoggingTracer))
  ([_config] (->LoggingTracer)))
