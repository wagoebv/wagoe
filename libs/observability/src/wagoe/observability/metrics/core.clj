(ns wagoe.observability.metrics.core
  "Core metrics functions and utilities.
   
   This namespace provides pure functions and higher-level abstractions over
   the metrics protocols, making it easier for feature modules to collect
   metrics without dealing with protocol details directly."
  (:require
   [wagoe.observability.metrics.ports :as ports]
   [clojure.string :as str]))

;; =============================================================================
;; Metric Name and Tag Utilities
;; =============================================================================

(defn normalize-metric-name
  "Normalizes a metric name according to common conventions.
   
   Args:
     name - Metric name string
   
   Returns:
     Normalized metric name string"
  [name]
  (-> name
      (str/lower-case)
      (str/replace #"[^a-z0-9_]" "_")
      (str/replace #"_{2,}" "_")
      (str/replace #"^_|_$" "")))

(defn build-metric-name
  "Builds a hierarchical metric name from components.
   
   Args:
     namespace   - Metric namespace
     subsystem   - Metric subsystem (optional)
     name        - Base metric name
   
   Returns:
     Fully qualified metric name"
  [namespace subsystem name]
  (let [components (filter seq [namespace subsystem name])]
    (str/join "_" (map normalize-metric-name components))))

(defn sanitize-tags
  "Sanitizes metric tags by removing invalid characters.
   
   Args:
     tags - Map of tag key-value pairs
   
   Returns:
     Sanitized tags map"
  [tags]
  (->> tags
       (map (fn [[k v]]
              [(normalize-metric-name (name k))
               (str v)]))
       (into {})))

(defn merge-tags
  "Merges multiple tag maps, with later maps taking precedence.
   
   Args:
     tag-maps - Variable number of tag maps
   
   Returns:
     Merged tags map"
  [& tag-maps]
  (apply merge tag-maps))

;; =============================================================================
;; Counter Utilities
;; =============================================================================

(defn increment-counter
  "Increments a counter metric by 1.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Counter name
     tags    - Optional tags map
   
   Returns:
     nil"
  ([emitter name]
   (increment-counter emitter name {}))
  ([emitter name tags]
   (ports/inc-counter! emitter name 1 tags)))

(defn increment-counter-by
  "Increments a counter metric by a specific amount.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Counter name
     amount  - Amount to increment by
     tags    - Optional tags map
   
   Returns:
     nil"
  ([emitter name amount]
   (increment-counter-by emitter name amount {}))
  ([emitter name amount tags]
   (ports/inc-counter! emitter name amount tags)))

(defn count-events
  "Convenience function to count application events.
   
   Args:
     emitter    - IMetricsEmitter instance
     event-type - Type of event
     tags       - Optional additional tags
   
   Returns:
     nil"
  ([emitter event-type]
   (count-events emitter event-type {}))
  ([emitter event-type tags]
   (let [counter-name (build-metric-name "app" "events" (name event-type))
         event-tags (merge {:event-type (name event-type)} tags)]
     (increment-counter emitter counter-name event-tags))))

;; =============================================================================
;; Gauge Utilities
;; =============================================================================

(defn set-gauge-value
  "Sets a gauge to a specific value.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Gauge name
     value   - Gauge value
     tags    - Optional tags map
   
   Returns:
     nil"
  ([emitter name value]
   (set-gauge-value emitter name value {}))
  ([emitter name value tags]
   (ports/set-gauge! emitter name value tags)))

(defn track-active-count
  "Tracks active count by setting gauge values.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Gauge name
     value   - Current count value
     tags    - Optional tags map
   
   Returns:
     nil"
  ([emitter name value]
   (track-active-count emitter name value {}))
  ([emitter name value tags]
   (ports/set-gauge! emitter name value tags)))

(defn track-resource-usage
  "Tracks resource usage metrics.
   
   Args:
     emitter       - IMetricsEmitter instance
     resource-type - Type of resource (memory, cpu, etc.)
     value         - Current usage value
     tags          - Optional additional tags
   
   Returns:
     nil"
  ([emitter resource-type value]
   (track-resource-usage emitter resource-type value {}))
  ([emitter resource-type value tags]
   (let [gauge-name (build-metric-name "system" "resources" (name resource-type))
         resource-tags (merge {:resource (name resource-type)} tags)]
     (set-gauge-value emitter gauge-name value resource-tags))))

;; =============================================================================
;; Histogram Utilities
;; =============================================================================

(defn observe-histogram
  "Records an observation in a histogram.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Histogram name
     value   - Observed value
     tags    - Optional tags map
   
   Returns:
     nil"
  ([emitter name value]
   (observe-histogram emitter name value {}))
  ([emitter name value tags]
   (ports/observe-histogram! emitter name value tags)))

(defn time-with-histogram
  "Times an operation and records the duration in a histogram.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Histogram name
     tags    - Optional tags map
     f       - Function to time
   
   Returns:
     Result of function f"
  ([emitter name f]
   (time-with-histogram emitter name {} f))
  ([emitter name tags f]
   (let [start-time (System/nanoTime)
         result (f)
         duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)]
     (observe-histogram emitter name duration-ms tags)
     result)))

(defn track-request-duration
  "Tracks HTTP request durations.
   
   Args:
     emitter    - IMetricsEmitter instance
     method     - HTTP method
     path       - Request path
     status     - Response status
     duration   - Duration in milliseconds
   
   Returns:
     nil"
  [emitter method path status duration]
  (let [histogram-name (build-metric-name "http" "requests" "duration")
        request-tags {:method (str/upper-case (name method))
                      :path (str path)
                      :status (str status)}]
    (observe-histogram emitter histogram-name duration request-tags)))

;; =============================================================================
;; Timer Utilities
;; =============================================================================

(defn record-timer
  "Records a timing measurement using histogram.
   
   Args:
     emitter  - IMetricsEmitter instance
     name     - Timer name
     duration - Duration value
     tags     - Optional tags map
   
   Returns:
     nil"
  ([emitter name duration]
   (record-timer emitter name duration {}))
  ([emitter name duration tags]
   (ports/observe-histogram! emitter name duration tags)))

(defn time-operation
  "Times an operation using histogram timing.
   
   Args:
     emitter - IMetricsEmitter instance
     name    - Timer name
     tags    - Optional tags map
     f       - Function to time
   
   Returns:
     Result of function f"
  ([emitter name f]
   (time-operation emitter name {} f))
  ([emitter name tags f]
   (ports/time-histogram! emitter name tags f)))

;; =============================================================================
;; Business Metrics Helpers
;; =============================================================================

(defn track-user-action
  "Tracks user actions as metrics.
   
   Args:
     emitter - IMetricsEmitter instance
     action  - Action type
     user-id - User identifier
     success - Whether action was successful
   
   Returns:
     nil"
  [emitter action _user-id success]
  (let [tags {:action (name action)
              :success (str success)}]
    (count-events emitter "user-action" tags)
    (when success
      (count-events emitter "successful-action" {:action (name action)}))))

(defn track-api-usage
  "Tracks API endpoint usage.
   
   Args:
     emitter  - IMetricsEmitter instance
     endpoint - API endpoint
     method   - HTTP method
     status   - Response status
     duration - Request duration in ms
   
   Returns:
     nil"
  [emitter endpoint method status duration]
  (let [base-tags {:endpoint (str endpoint)
                   :method (str/upper-case (name method))
                   :status (str status)}]
    ;; Count total requests
    (count-events emitter "api-request" base-tags)

    ;; Count successful vs error requests
    (if (< status 400)
      (count-events emitter "api-success" base-tags)
      (count-events emitter "api-error" base-tags))

    ;; Track request duration
    (track-request-duration emitter method endpoint status duration)))

(defn track-business-kpi
  "Tracks key performance indicators.
   
   Args:
     emitter - IMetricsEmitter instance
     kpi     - KPI name
     value   - KPI value
     tags    - Optional additional tags
   
   Returns:
     nil"
  ([emitter kpi value]
   (track-business-kpi emitter kpi value {}))
  ([emitter kpi value tags]
   (let [gauge-name (build-metric-name "business" "kpi" (name kpi))
         kpi-tags (merge {:kpi (name kpi)} tags)]
     (set-gauge-value emitter gauge-name value kpi-tags))))

;; =============================================================================
;; System Metrics Helpers
;; =============================================================================

(defn track-database-operations
  "Tracks database operation metrics.
   
   Args:
     emitter   - IMetricsEmitter instance
     operation - Database operation type
     table     - Database table/collection
     duration  - Operation duration in ms
     success   - Whether operation was successful
   
   Returns:
     nil"
  [emitter operation table duration success]
  (let [base-tags {:operation (name operation)
                   :table (str table)
                   :success (str success)}]
    ;; Count operations
    (count-events emitter "db-operation" base-tags)

    ;; Track operation duration
    (let [timer-name (build-metric-name "db" "operations" "duration")]
      (record-timer emitter timer-name duration base-tags))

    ;; Count errors separately
    (when-not success
      (count-events emitter "db-error" (dissoc base-tags :success)))))

(defn track-external-service-calls
  "Tracks external service call metrics.
   
   Args:
     emitter      - IMetricsEmitter instance
     service-name - Name of external service
     operation    - Operation/endpoint called
     duration     - Call duration in ms
     success      - Whether call was successful
   
   Returns:
     nil"
  [emitter service-name operation duration success]
  (let [base-tags {:service (str service-name)
                   :operation (str operation)
                   :success (str success)}]
    ;; Count service calls
    (count-events emitter "external-service-call" base-tags)

    ;; Track call duration
    (let [timer-name (build-metric-name "external" "services" "duration")]
      (record-timer emitter timer-name duration base-tags))

    ;; Count errors separately
    (when-not success
      (count-events emitter "external-service-error" (dissoc base-tags :success)))))

;; =============================================================================
;; Health Check Metrics
;; =============================================================================

(defn track-health-check
  "Tracks health check results.
   
   Args:
     emitter   - IMetricsEmitter instance
     component - Component being checked
     status    - Health status (healthy, unhealthy, degraded)
     duration  - Check duration in ms
   
   Returns:
     nil"
  [emitter component status duration]
  (let [base-tags {:component (str component)
                   :status (name status)}]
    ;; Count health checks
    (count-events emitter "health-check" base-tags)

    ;; Track check duration
    (let [timer-name (build-metric-name "health" "checks" "duration")]
      (record-timer emitter timer-name duration base-tags))

    ;; Set health status gauge
    (let [gauge-name (build-metric-name "health" "status" (str component))
          status-value (case status
                         :healthy 1
                         :degraded 0.5
                         :unhealthy 0)]
      (set-gauge-value emitter gauge-name status-value {:component (str component)}))))

;; =============================================================================
;; Conditional Metrics
;; =============================================================================

(defn track-when
  "Conditionally tracks a metric.
   
   Args:
     condition - Boolean condition
     f         - Function that performs metric tracking
   
   Returns:
     Result of function f if condition is true, nil otherwise"
  [condition f]
  (when condition
    (f)))

(defn track-sampling
  "Tracks metrics with sampling rate.
   
   Args:
     sample-rate - Sampling rate (0.0 to 1.0)
     f           - Function that performs metric tracking
   
   Returns:
     Result of function f if sample passes, nil otherwise"
  [sample-rate f]
  (when (< (rand) sample-rate)
    (f)))