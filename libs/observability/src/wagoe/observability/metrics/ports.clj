(ns wagoe.observability.metrics.ports
  "Port definitions for metrics infrastructure.
   
   This namespace defines the protocols that metrics adapters must implement,
   providing a clean abstraction over different metrics backends (Prometheus,
   Datadog, in-memory registries, etc.).
   
   Core protocols:
   - IMetricsRegistry: Metric registration and management
   - IMetricsEmitter: Metric value emission and updates
   - IMetricsExporter: Metric export and serialization
   
   Metric Types:
   - Counter: Monotonically increasing values (requests, errors)
   - Gauge: Point-in-time values that can go up/down (active users, memory)
   - Histogram: Distribution of values with configurable buckets (latency)
   - Summary: Distribution statistics (quantiles, avg, etc.)")

;; =============================================================================
;; Core Metrics Registry Protocol
;; =============================================================================

(defprotocol IMetricsRegistry
  "Core metrics registry for managing metric definitions.
   
   The registry tracks metric metadata, validates operations, and provides
   a central catalog of all metrics in the system."

  (register-counter! [this name description tags]
    "Register a new counter metric.
     
     Parameters:
       name        - Keyword metric name (e.g. :http.requests)
       description - String description for metric
       tags        - Map of default tags to apply to all values
     
     Returns:
       Metric handle/identifier for use with IMetricsEmitter")

  (register-gauge! [this name description tags]
    "Register a new gauge metric.
     
     Parameters:
       name        - Keyword metric name (e.g. :users.active)
       description - String description for metric
       tags        - Map of default tags to apply to all values
     
     Returns:
       Metric handle/identifier for use with IMetricsEmitter")

  (register-histogram! [this name description buckets tags]
    "Register a new histogram metric.
     
     Parameters:
       name        - Keyword metric name (e.g. :http.request.duration)
       description - String description for metric
       buckets     - Vector of numeric bucket boundaries
       tags        - Map of default tags to apply to all values
     
     Returns:
       Metric handle/identifier for use with IMetricsEmitter")

  (register-summary! [this name description quantiles tags]
    "Register a new summary metric.
     
     Parameters:
       name        - Keyword metric name (e.g. :db.query.duration)
       description - String description for metric
       quantiles   - Vector of quantile values [0.5 0.95 0.99]
       tags        - Map of default tags to apply to all values
     
     Returns:
       Metric handle/identifier for use with IMetricsEmitter")

  (unregister! [this name]
    "Remove a metric from the registry.
     
     Parameters:
       name - Keyword metric name to remove
     
     Returns:
       Boolean indicating if metric was found and removed")

  (list-metrics [this]
    "Get all registered metrics.
     
     Returns:
       Vector of maps describing each metric:
       [{:name :http.requests
         :type :counter
         :description 'HTTP requests received'
         :tags {:service 'boundary'}
         :handle ...}]")

  (get-metric [this name]
    "Get a specific metric by name.
     
     Parameters:
       name - Keyword metric name
     
     Returns:
       Metric description map or nil if not found"))

;; =============================================================================
;; Metrics Emission Protocol
;; =============================================================================

(defprotocol IMetricsEmitter
  "Protocol for emitting metric values.
   
   This is the interface used by application code to record metric values.
   Implementation handles proper tagging, batching, and backend communication."

  (inc-counter! [this metric-handle] [this metric-handle value] [this metric-handle value tags]
    "Increment a counter metric.
     
     Parameters:
       metric-handle - Handle returned from register-counter!
       value        - Optional increment amount (default: 1)
       tags         - Optional additional tags for this measurement
     
     Returns:
       nil (side effect only)")

  (set-gauge! [this metric-handle value] [this metric-handle value tags]
    "Set a gauge metric to a specific value.
     
     Parameters:
       metric-handle - Handle returned from register-gauge!
       value         - Numeric value to set
       tags          - Optional additional tags for this measurement
     
     Returns:
       nil (side effect only)")

  (observe-histogram! [this metric-handle value] [this metric-handle value tags]
    "Record a value in a histogram metric.
     
     Parameters:
       metric-handle - Handle returned from register-histogram!
       value         - Numeric value to observe
       tags          - Optional additional tags for this measurement
     
     Returns:
       nil (side effect only)")

  (observe-summary! [this metric-handle value] [this metric-handle value tags]
    "Record a value in a summary metric.
     
     Parameters:
       metric-handle - Handle returned from register-summary!
       value         - Numeric value to observe
       tags          - Optional additional tags for this measurement
     
     Returns:
       nil (side effect only)")

  (time-histogram! [this metric-handle f] [this metric-handle tags f]
    "Time the execution of function f and record in histogram.
     
     Parameters:
       metric-handle - Handle returned from register-histogram!
       tags          - Optional additional tags for this measurement
       f             - Zero-argument function to time
     
     Returns:
       Result of calling (f)")

  (time-summary! [this metric-handle f] [this metric-handle tags f]
    "Time the execution of function f and record in summary.
     
     Parameters:
       metric-handle - Handle returned from register-summary!
       tags          - Optional additional tags for this measurement
       f             - Zero-argument function to time
     
     Returns:
       Result of calling (f)"))

;; =============================================================================
;; Metrics Export Protocol
;; =============================================================================

(defprotocol IMetricsExporter
  "Protocol for exporting metrics data.
   
   This handles serialization and export of accumulated metrics data
   to external systems (Prometheus endpoint, Datadog agent, etc.)"

  (export-metrics [this format]
    "Export all metrics in the specified format.
     
     Parameters:
       format - Keyword format (:prometheus :json :datadog :openmetrics)
     
     Returns:
       String representation of metrics in requested format")

  (export-metric [this metric-name format]
    "Export a specific metric in the specified format.
     
     Parameters:
       metric-name - Keyword name of metric to export
       format      - Keyword format (:prometheus :json :datadog :openmetrics)
     
     Returns:
       String representation of metric in requested format")

  (get-metric-values [this metric-name]
    "Get raw metric values for a specific metric.
     
     Parameters:
       metric-name - Keyword name of metric
     
     Returns:
       Map with metric values and metadata:
       {:type :counter
        :value 42
        :tags {:method 'GET' :status '200'}
        :timestamp 1625097600000}")

  (reset-metrics! [this]
    "Reset all metrics to their initial state.
     
     Note: This is primarily for testing. Production systems should
     use metric TTL or external aggregation for metric lifecycle.
     
     Returns:
       nil (side effect only)")

  (flush! [this]
    "Force flush of any buffered metrics data.
     
     This ensures all pending metrics are sent to external systems.
     Typically called during application shutdown.
     
     Returns:
       nil (side effect only)"))

;; =============================================================================
;; Metrics Configuration Protocol
;; =============================================================================

(defprotocol IMetricsConfig
  "Protocol for runtime metrics configuration changes.
   
   This allows for dynamic adjustment of metrics behavior without
   requiring application restart."

  (set-default-tags! [this tags]
    "Set default tags applied to all metrics.
     
     Parameters:
       tags - Map of tag keys/values
     
     Returns:
       Previous default tags map")

  (get-default-tags [this]
    "Get current default tags.
     
     Returns:
       Map of default tag keys/values")

  (set-export-interval! [this interval-ms]
    "Set the metrics export interval for push-based systems.
     
     Parameters:
       interval-ms - Export interval in milliseconds
     
     Returns:
       Previous interval in milliseconds")

  (get-export-interval [this]
    "Get current export interval.
     
     Returns:
       Current interval in milliseconds")

  (enable-metric! [this metric-name]
    "Enable collection for a specific metric.
     
     Parameters:
       metric-name - Keyword metric name
     
     Returns:
       Boolean indicating if metric was found and enabled")

  (disable-metric! [this metric-name]
    "Disable collection for a specific metric.
     
     Disabled metrics will be no-ops for performance.
     
     Parameters:
       metric-name - Keyword metric name
     
     Returns:
       Boolean indicating if metric was found and disabled")

  (metric-enabled? [this metric-name]
    "Check if a metric is currently enabled.
     
     Parameters:
       metric-name - Keyword metric name
     
     Returns:
       Boolean indicating if metric is enabled"))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn increment
  "Convenience function for incrementing counters.
   For now, this is a no-op placeholder until metrics infrastructure is set up."
  [_metrics-collector _metric-name _tags]
  ;; TODO: Implement when metrics infrastructure is available
  nil)

(defn observe
  "Convenience function for observing histogram/summary values.
   For now, this is a no-op placeholder until metrics infrastructure is set up."
  [_metrics-collector _metric-name _value _tags]
  ;; TODO: Implement when metrics infrastructure is available
  nil)

