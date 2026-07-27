(ns wagoe.observability.tracing.schema
  "Malli schemas for tracing configuration.")

(def TracingProvider
  "Supported tracer backends. `:no-op` (default, inert), `:logging` (logs span
   shape; dev only), `:otlp` (OpenTelemetry export to any OTLP collector)."
  [:enum :no-op :logging :otlp])

(def OtlpProtocol
  "OTLP transport. Only `:http/protobuf` is bundled (okhttp sender); `:grpc`
   would pull grpc-netty onto every classpath and is intentionally unsupported."
  [:enum :http/protobuf])

(def OtlpTracingConfig
  "Configuration for the `:otlp` tracer."
  [:map
   [:provider {:optional true} [:= :otlp]]
   ;; OTLP base endpoint (OTEL_EXPORTER_OTLP_ENDPOINT); the exporter appends
   ;; /v1/traces. HTTP receiver default port is 4318.
   [:endpoint {:optional true} [:string {:min 1 :max 500}]]
   [:protocol {:optional true} OtlpProtocol]
   ;; Logical service name attached to spans (OTel `service.name`).
   [:service-name {:optional true} [:string {:min 1 :max 200}]]
   [:timeout-ms {:optional true} [:int {:min 100 :max 120000}]]
   [:headers {:optional true} [:map-of :keyword :string]]])

(def TracingConfig
  [:map
   [:provider {:optional true} TracingProvider]
   ;; Logical service name attached to spans (OTel `service.name`).
   [:service-name {:optional true} [:string {:min 1 :max 200}]]
   [:endpoint {:optional true} [:string {:min 1 :max 500}]]
   [:protocol {:optional true} OtlpProtocol]
   [:timeout-ms {:optional true} [:int {:min 100 :max 120000}]]
   [:headers {:optional true} [:map-of :keyword :string]]])

(def default-tracing-config
  {:provider :no-op})

(def default-otlp-tracing-config
  {:provider     :otlp
   :endpoint     "http://localhost:4318"
   :protocol     :http/protobuf
   :service-name "boundary"
   :timeout-ms   10000})
