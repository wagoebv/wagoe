(ns wagoe.observability.metrics.schema
  "Configuration schemas for metrics infrastructure.
   
   This namespace defines Malli schemas for validating metrics configuration,
   ensuring proper structure and constraints for different metrics providers."
  (:require
   [malli.core :as m]
   [malli.transform :as mt]))

;; =============================================================================
;; Core Metrics Configuration Schemas
;; =============================================================================

(def MetricProvider
  "Supported metrics providers."
  [:enum :in-memory :prometheus :datadog-statsd :otlp :statsd :cloudwatch :custom])

(def MetricType
  "Types of metrics that can be collected."
  [:enum :counter :gauge :histogram :timer :set])

(def AggregationType
  "Types of aggregations for metrics."
  [:enum :sum :avg :min :max :count :percentile])

(def TaggingConfig
  "Configuration for metric tagging/labeling."
  [:map {:title "Tagging Configuration"}
   [:enabled {:optional true} :boolean]
   [:max-tags {:optional true} [:int {:min 1 :max 100}]]
   [:max-tag-key-length {:optional true} [:int {:min 1 :max 200}]]
   [:max-tag-value-length {:optional true} [:int {:min 1 :max 500}]]
   [:default-tags {:optional true} [:map-of :keyword :string]]
   [:sanitize-keys {:optional true} :boolean]
   [:allow-special-chars {:optional true} :boolean]])

(def SamplingConfig
  "Configuration for metrics sampling."
  [:map {:title "Metrics Sampling Configuration"}
   [:enabled {:optional true} :boolean]
   [:rate {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:by-metric-type {:optional true}
    [:map {:title "Per-Type Sampling"}
     [:counter {:optional true} [:double {:min 0.0 :max 1.0}]]
     [:gauge {:optional true} [:double {:min 0.0 :max 1.0}]]
     [:histogram {:optional true} [:double {:min 0.0 :max 1.0}]]
     [:timer {:optional true} [:double {:min 0.0 :max 1.0}]]
     [:set {:optional true} [:double {:min 0.0 :max 1.0}]]]]])

(def RetentionConfig
  "Configuration for metrics retention."
  [:map {:title "Retention Configuration"}
   [:enabled {:optional true} :boolean]
   [:max-age-seconds {:optional true} [:int {:min 60 :max 31536000}]] ; 1 minute to 1 year
   [:max-metrics {:optional true} [:int {:min 100 :max 1000000}]]
   [:cleanup-interval-seconds {:optional true} [:int {:min 60 :max 3600}]]]) ; 1 minute to 1 hour

(def BaseMetricsConfig
  "Base metrics configuration shared by all providers."
  [:map {:title "Base Metrics Configuration"}
   [:provider MetricProvider]
   [:enabled {:optional true} :boolean]
   [:namespace {:optional true} [:string {:min 1 :max 100}]]
   [:prefix {:optional true} [:string {:min 1 :max 50}]]
   [:tagging {:optional true} TaggingConfig]
   [:sampling {:optional true} SamplingConfig]
   [:retention {:optional true} RetentionConfig]
   [:buffer-size {:optional true} [:int {:min 100 :max 100000}]]
   [:flush-interval {:optional true} [:int {:min 1000 :max 300000}]] ; 1s to 5min
   [:error-handling {:optional true} [:enum :drop :log :throw]]])

;; =============================================================================
;; Provider-Specific Configuration Schemas
;; =============================================================================

(def InMemoryMetricsConfig
  "Configuration for in-memory metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "In-Memory Metrics Configuration"}
    [:provider [:= :in-memory]]
    [:max-memory-mb {:optional true} [:int {:min 1 :max 1024}]]
    [:enable-gc {:optional true} :boolean]
    [:gc-threshold {:optional true} [:double {:min 0.1 :max 0.9}]]
    [:export-snapshots {:optional true} :boolean]
    [:snapshot-interval {:optional true} [:int {:min 5000 :max 300000}]]]])

(def PrometheusMetricsConfig
  "Configuration for Prometheus metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "Prometheus Metrics Configuration"}
    [:provider [:= :prometheus]]
    [:port {:optional true} [:int {:min 1024 :max 65535}]]
    [:path {:optional true} [:string {:min 1 :max 100}]]
    [:registry-name {:optional true} [:string {:min 1 :max 100}]]
    [:enable-default-metrics {:optional true} :boolean]
    [:include-help-text {:optional true} :boolean]
    [:histogram-buckets {:optional true}
     [:vector [:double {:min 0.0}]]]
    [:summary-quantiles {:optional true}
     [:vector [:double {:min 0.0 :max 1.0}]]]
    [:summary-max-age {:optional true} [:int {:min 60 :max 3600}]]]])

(def OtlpProtocol
  "OTLP transport. Only `:http/protobuf` is bundled (okhttp sender)."
  [:enum :http/protobuf])

(def OtlpMetricsConfig
  "Configuration for the OpenTelemetry OTLP metrics provider (push to any OTel
   collector: SigNoz, Grafana, Datadog-via-OTel, …)."
  [:and BaseMetricsConfig
   [:map {:title "OTLP Metrics Configuration"}
    [:provider [:= :otlp]]
    ;; OTLP base endpoint (OTEL_EXPORTER_OTLP_ENDPOINT); the exporter appends
    ;; /v1/metrics. HTTP receiver default port is 4318.
    [:endpoint {:optional true} [:string {:min 1 :max 500}]]
    [:protocol {:optional true} OtlpProtocol]
    [:service-name {:optional true} [:string {:min 1 :max 200}]]
    ;; Push interval to the collector (PeriodicMetricReader).
    [:interval-ms {:optional true} [:int {:min 1000 :max 600000}]]
    [:timeout-ms {:optional true} [:int {:min 100 :max 120000}]]
    [:default-tags {:optional true} [:map-of :keyword :string]]
    [:headers {:optional true} [:map-of :keyword :string]]]])

(def DatadogStatsdConfig
  "Configuration for Datadog StatsD metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "Datadog StatsD Configuration"}
    [:provider [:= :datadog-statsd]]
    [:host [:string {:min 1 :max 253}]]
    [:port {:optional true} [:int {:min 1024 :max 65535}]]
    [:api-key {:optional true} [:string {:min 32 :max 32}]]
    [:service [:string {:min 1 :max 100}]]
    [:environment {:optional true} [:string {:min 1 :max 50}]]
    [:version {:optional true} [:string {:min 1 :max 50}]]
    [:global-tags {:optional true} [:vector :string]]
    [:origin-detection {:optional true} :boolean]
    [:socket-timeout {:optional true} [:int {:min 1000 :max 30000}]]
    [:max-packet-size {:optional true} [:int {:min 512 :max 65507}]]]])

(def StatsdConfig
  "Configuration for generic StatsD metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "StatsD Configuration"}
    [:provider [:= :statsd]]
    [:host [:string {:min 1 :max 253}]]
    [:port {:optional true} [:int {:min 1024 :max 65535}]]
    [:protocol {:optional true} [:enum :udp :tcp]]
    [:max-packet-size {:optional true} [:int {:min 512 :max 65507}]]
    [:socket-timeout {:optional true} [:int {:min 1000 :max 30000}]]
    [:connection-timeout {:optional true} [:int {:min 1000 :max 30000}]]]])

(def CloudwatchMetricsConfig
  "Configuration for AWS CloudWatch metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "CloudWatch Metrics Configuration"}
    [:provider [:= :cloudwatch]]
    [:region [:string {:min 1 :max 50}]]
    [:namespace [:string {:min 1 :max 255}]]
    [:access-key-id {:optional true} [:string {:min 16 :max 128}]]
    [:secret-access-key {:optional true} [:string {:min 40 :max 128}]]
    [:session-token {:optional true} [:string {:min 1 :max 2048}]]
    [:endpoint {:optional true} [:string {:min 1 :max 500}]]
    [:batch-size {:optional true} [:int {:min 1 :max 20}]]
    [:high-resolution {:optional true} :boolean]
    [:storage-resolution {:optional true} [:enum 1 60]]]])

(def CustomMetricsConfig
  "Configuration for custom metrics provider."
  [:and BaseMetricsConfig
   [:map {:title "Custom Metrics Configuration"}
    [:provider [:= :custom]]
    [:adapter-ns [:string {:min 1 :max 200}]]
    [:adapter-config {:optional true} :map]]])

;; =============================================================================
;; Unified Configuration Schema
;; =============================================================================

(def MetricsConfig
  "Unified metrics configuration schema that validates any provider."
  [:multi {:dispatch :provider
           :title    "Metrics Configuration"}
   [:in-memory InMemoryMetricsConfig]
   [:prometheus PrometheusMetricsConfig]
   [:datadog-statsd DatadogStatsdConfig]
   [:otlp OtlpMetricsConfig]
   [:statsd StatsdConfig]
   [:cloudwatch CloudwatchMetricsConfig]
   [:custom CustomMetricsConfig]])

;; =============================================================================
;; Application Metrics Configuration
;; =============================================================================

(def ApplicationMetricsConfig
  "Configuration for application-level metrics collection."
  [:map {:title "Application Metrics Configuration"}
   [:enabled {:optional true} :boolean]
   [:collect-jvm-metrics {:optional true} :boolean]
   [:collect-gc-metrics {:optional true} :boolean]
   [:collect-memory-metrics {:optional true} :boolean]
   [:collect-thread-metrics {:optional true} :boolean]
   [:collect-cpu-metrics {:optional true} :boolean]
   [:collect-http-metrics {:optional true} :boolean]
   [:collect-database-metrics {:optional true} :boolean]
   [:collect-business-metrics {:optional true} :boolean]
   [:custom-collectors {:optional true} [:vector :string]]])

(def BusinessMetricsConfig
  "Configuration for business domain metrics."
  [:map {:title "Business Metrics Configuration"}
   [:user-actions {:optional true} :boolean]
   [:api-usage {:optional true} :boolean]
   [:feature-usage {:optional true} :boolean]
   [:performance-indicators {:optional true} :boolean]
   [:error-rates {:optional true} :boolean]
   [:success-rates {:optional true} :boolean]
   [:conversion-metrics {:optional true} :boolean]
   [:custom-kpis {:optional true} [:vector :string]]])

;; =============================================================================
;; Complete System Configuration Schema
;; =============================================================================

(def SystemMetricsConfig
  "Complete system metrics configuration."
  [:map {:title "System Metrics Configuration"}
   [:infrastructure {:optional true} MetricsConfig]
   [:application {:optional true} ApplicationMetricsConfig]
   [:business {:optional true} BusinessMetricsConfig]
   [:alerting {:optional true} :map]                        ; For future alerting integration
   [:dashboards {:optional true} :map]])                    ; For future dashboard integration

;; =============================================================================
;; Default Configurations
;; =============================================================================

(def default-in-memory-config
  "Default in-memory metrics configuration."
  {:provider         :in-memory
   :enabled          true
   :namespace        "boundary"
   :max-memory-mb    50
   :enable-gc        true
   :gc-threshold     0.8
   :export-snapshots false
   :tagging          {:enabled              true
                      :max-tags             20
                      :max-tag-key-length   50
                      :max-tag-value-length 200
                      :default-tags         {:service "boundary"}
                      :sanitize-keys        true}
   :retention        {:enabled                  true
                      :max-age-seconds          3600
                      :max-metrics              10000
                      :cleanup-interval-seconds 300}})

(def default-prometheus-config
  "Default Prometheus metrics configuration."
  {:provider               :prometheus
   :enabled                true
   :namespace              "boundary"
   :port                   9090
   :path                   "/metrics"
   :enable-default-metrics true
   :include-help-text      true
   :histogram-buckets      [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]
   :summary-quantiles      [0.5 0.95 0.99]
   :summary-max-age        600
   :tagging                {:enabled       true
                            :max-tags      15
                            :default-tags  {:service "boundary"}
                            :sanitize-keys true}})

(def default-otlp-config
  "Default OTLP metrics configuration (push to a local collector)."
  {:provider     :otlp
   :enabled      true
   :namespace    "boundary"
   :endpoint     "http://localhost:4318"
   :protocol     :http/protobuf
   :service-name "boundary"
   :interval-ms  60000
   :timeout-ms   10000})

(def default-datadog-config
  "Default Datadog StatsD metrics configuration."
  {:provider         :datadog-statsd
   :enabled          true
   :namespace        "boundary"
   :host             "localhost"
   :port             8125
   :service          "boundary"
   :environment      "development"
   :origin-detection false
   :socket-timeout   5000
   :max-packet-size  1432
   :tagging          {:enabled              true
                      :max-tags             20
                      :max-tag-key-length   200
                      :max-tag-value-length 500
                      :default-tags         {:service "boundary"}
                      :sanitize-keys        true
                      :allow-special-chars  false}})

(def default-application-config
  "Default application metrics configuration."
  {:enabled                  true
   :collect-jvm-metrics      true
   :collect-gc-metrics       true
   :collect-memory-metrics   true
   :collect-thread-metrics   true
   :collect-cpu-metrics      true
   :collect-http-metrics     true
   :collect-database-metrics true
   :collect-business-metrics false})

(def default-business-config
  "Default business metrics configuration."
  {:user-actions           true
   :api-usage              true
   :feature-usage          false
   :performance-indicators true
   :error-rates            true
   :success-rates          true
   :conversion-metrics     false})

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private metrics-config-validator (m/validator MetricsConfig))
(def ^:private system-metrics-config-validator (m/validator SystemMetricsConfig))
(def ^:private metrics-config-explainer (m/explainer MetricsConfig))
(def ^:private system-metrics-config-explainer (m/explainer SystemMetricsConfig))

(defn validate-metrics-config
  "Validates a metrics configuration map."
  [config]
  (metrics-config-validator config))

(defn validate-system-metrics-config
  "Validates a complete system metrics configuration map."
  [config]
  (system-metrics-config-validator config))

(defn explain-metrics-config
  "Provides detailed validation errors for metrics configuration."
  [config]
  (metrics-config-explainer config))

(defn explain-system-metrics-config
  "Provides detailed validation errors for system metrics configuration."
  [config]
  (system-metrics-config-explainer config))

;; =============================================================================
;; Configuration Transformation
;; =============================================================================

(def metrics-config-transformer
  "Transforms external configuration to internal format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :metrics-config}))

(defn normalize-metrics-config
  "Normalizes and validates a metrics configuration map."
  [config]
  (let [normalized (m/decode MetricsConfig config metrics-config-transformer)]
    (if (validate-metrics-config normalized)
      normalized
      (throw (ex-info "Invalid metrics configuration"
                      {:errors (explain-metrics-config normalized)
                       :config config})))))

(defn normalize-system-metrics-config
  "Normalizes and validates a system metrics configuration map."
  [config]
  (let [normalized (m/decode SystemMetricsConfig config metrics-config-transformer)]
    (if (validate-system-metrics-config normalized)
      normalized
      (throw (ex-info "Invalid system metrics configuration"
                      {:errors (explain-system-metrics-config normalized)
                       :config config})))))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all metrics schemas for easy access."
  {:core
   {:metrics-config        MetricsConfig
    :system-metrics-config SystemMetricsConfig
    :application-config    ApplicationMetricsConfig
    :business-config       BusinessMetricsConfig}
   :providers
   {:in-memory      InMemoryMetricsConfig
    :prometheus     PrometheusMetricsConfig
    :datadog-statsd DatadogStatsdConfig
    :otlp           OtlpMetricsConfig
    :statsd         StatsdConfig
    :cloudwatch     CloudwatchMetricsConfig
    :custom         CustomMetricsConfig}
   :common
   {:metric-provider  MetricProvider
    :metric-type      MetricType
    :aggregation-type AggregationType
    :tagging-config   TaggingConfig
    :sampling-config  SamplingConfig
    :retention-config RetentionConfig}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))