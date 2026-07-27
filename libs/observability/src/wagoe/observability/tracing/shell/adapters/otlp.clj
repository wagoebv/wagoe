(ns wagoe.observability.tracing.shell.adapters.otlp
  "OpenTelemetry OTLP tracer: real, exportable distributed tracing behind the
   `ITracer` port. Spans are exported over OTLP/HTTP (protobuf) to any
   OpenTelemetry collector — SigNoz, Grafana Tempo, Jaeger, Honeycomb, or a
   Datadog OTel endpoint. Only the endpoint changes; there is no backend-specific
   code here (that is the whole point of OTLP).

   Transport is OTLP/HTTP protobuf via the okhttp sender bundled with
   `opentelemetry-exporter-otlp`. gRPC is intentionally not bundled (it would add
   grpc-netty to every app's classpath) — use `:protocol :http/protobuf`.

   Context propagation uses W3C `traceparent`, so nested `with-span` calls and
   spans started on the current thread become parent/child automatically. The
   span handle returned by `start-span!` is the OpenTelemetry `Span`."
  (:require [wagoe.observability.tracing.ports :as ports]
            [wagoe.observability.tracing.schema :as schema]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m])
  (:import [io.opentelemetry.api.common Attributes]
           [io.opentelemetry.api.trace Span SpanContext StatusCode Tracer]
           [io.opentelemetry.context Scope]
           [io.opentelemetry.context.propagation ContextPropagators]
           [io.opentelemetry.api.trace.propagation W3CTraceContextPropagator]
           [io.opentelemetry.exporter.otlp.http.trace OtlpHttpSpanExporter]
           [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.resources Resource]
           [io.opentelemetry.sdk.trace SdkTracerProvider]
           [io.opentelemetry.sdk.trace.export BatchSpanProcessor]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Attribute conversion (Clojure map -> OTel Attributes)
;; ---------------------------------------------------------------------------

(defn- attr-name ^String [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn ->attributes
  "Convert a Clojure map into OpenTelemetry `Attributes`. Keys become strings;
   values are typed by class (string/long/double/boolean), anything else is
   stringified. `nil` or empty yields empty Attributes."
  ^Attributes [m]
  (let [b (Attributes/builder)]
    (doseq [[k v] m]
      (let [ks (attr-name k)]
        (cond
          (string? v)  (.put b ks ^String v)
          (integer? v) (.put b ks (long v))
          (float? v)   (.put b ks (double v))
          (boolean? v) (.put b ks (boolean v))
          (nil? v)     nil
          :else        (.put b ks (str v)))))
    (.build b)))

(defn- span-name ^String [name]
  (if (keyword? name) (subs (str name) 1) (str name)))

;; ---------------------------------------------------------------------------
;; Tracer
;; ---------------------------------------------------------------------------

(defrecord OtlpTracer [^Tracer tracer ^OpenTelemetrySdk sdk]
  ports/ITracer
  (start-span! [this name] (ports/start-span! this name {}))
  (start-span! [_ name attributes]
    (-> (.spanBuilder tracer (span-name name))
        (.setAllAttributes (->attributes attributes))
        (.startSpan)))

  (end-span! [_ span]
    (when span (.end ^Span span))
    nil)

  (add-event! [this span name] (ports/add-event! this span name {}))
  (add-event! [_ span name attributes]
    (when span
      (.addEvent ^Span span (span-name name) (->attributes attributes)))
    nil)

  (set-attributes! [_ span attributes]
    (when span
      (.setAllAttributes ^Span span (->attributes attributes)))
    nil)

  (record-exception! [_ span throwable]
    (when span
      (.recordException ^Span span throwable)
      (.setStatus ^Span span StatusCode/ERROR))
    nil)

  (span-context [_ span]
    (if span
      (let [^SpanContext sc (.getSpanContext ^Span span)]
        {:trace-id (.getTraceId sc) :span-id (.getSpanId sc)})
      {:trace-id nil :span-id nil}))

  (with-span* [this name attributes f]
    (let [^Span span   (ports/start-span! this name attributes)
          ^Scope scope (.makeCurrent span)]
      (try
        (f span)
        (catch Throwable t
          (ports/record-exception! this span t)
          (throw t))
        (finally
          (.close scope)
          (ports/end-span! this span))))))

;; ---------------------------------------------------------------------------
;; SDK construction
;; ---------------------------------------------------------------------------

(def ^:private config-validator (m/validator schema/OtlpTracingConfig))
(def ^:private config-explainer (m/explainer schema/OtlpTracingConfig))

(defn- traces-endpoint ^String [endpoint]
  ;; Config carries the OTLP base (e.g. http://localhost:4318, per
  ;; OTEL_EXPORTER_OTLP_ENDPOINT); the HTTP exporter wants the full signal URL.
  (let [base (if (str/ends-with? endpoint "/")
               (subs endpoint 0 (dec (count endpoint)))
               endpoint)]
    (str base "/v1/traces")))

(defn sdk-from-processor
  "Build an `OpenTelemetrySdk` around an arbitrary `SpanProcessor` with a
   W3C-propagating tracer provider tagged `service.name`. Production wraps a
   batch OTLP exporter; tests inject a simple in-memory processor."
  ^OpenTelemetrySdk [processor service-name]
  (let [resource (.merge (Resource/getDefault)
                         (Resource/create (-> (Attributes/builder)
                                              (.put "service.name" ^String service-name)
                                              (.build))))
        provider (-> (SdkTracerProvider/builder)
                     (.addSpanProcessor processor)
                     (.setResource resource)
                     (.build))]
    (-> (OpenTelemetrySdk/builder)
        (.setTracerProvider provider)
        (.setPropagators
         (ContextPropagators/create (W3CTraceContextPropagator/getInstance)))
        (.build))))

(defn build-sdk
  "Build an `OpenTelemetrySdk` with a batch OTLP/HTTP span exporter. Returns the
   sdk (hold it to flush/shutdown on halt)."
  ^OpenTelemetrySdk [{:keys [endpoint service-name timeout-ms headers]
                      :or   {endpoint     "http://localhost:4318"
                             service-name "wagoe"
                             timeout-ms   10000}}]
  (let [eb        (-> (OtlpHttpSpanExporter/builder)
                      (.setEndpoint (traces-endpoint endpoint))
                      (.setTimeout (long timeout-ms) TimeUnit/MILLISECONDS))
        _         (doseq [[k v] headers] (.addHeader eb (str (name k)) (str v)))
        exporter  (.build eb)
        processor (-> (BatchSpanProcessor/builder exporter) (.build))]
    (sdk-from-processor processor service-name)))

(defn tracer-from-sdk
  "Wrap a built `OpenTelemetrySdk` as an `OtlpTracer`."
  [^OpenTelemetrySdk sdk]
  (->OtlpTracer (.getTracer sdk "wagoe.observability") sdk))

(defn create-tracing-component
  "Build the OTLP tracer component (wiring entry point; mirrors the no-op /
   logging adapters). Validates config, stands up the OpenTelemetry SDK, and
   returns an `OtlpTracer`."
  ([] (create-tracing-component schema/default-otlp-tracing-config))
  ([config]
   (let [config (merge schema/default-otlp-tracing-config config)]
     (when-not (config-validator config)
       (throw (ex-info "Invalid OTLP tracing configuration"
                       {:type   :validation-error
                        :config config
                        :errors (config-explainer config)})))
     (log/info "Building OTLP tracer"
               {:endpoint (:endpoint config) :service-name (:service-name config)})
     (tracer-from-sdk (build-sdk config)))))

(defn create-tracer
  ([] (create-tracing-component))
  ([config] (create-tracing-component config)))

(defn shutdown!
  "Flush + shut down the tracer's SDK (call on Integrant halt). Blocks up to
   `timeout-ms` for the batch processor to drain."
  ([tracer] (shutdown! tracer 10000))
  ([tracer timeout-ms]
   (when-let [^OpenTelemetrySdk sdk (:sdk tracer)]
     (-> (.shutdown sdk)
         (.join (long timeout-ms) TimeUnit/MILLISECONDS)))
   nil))
