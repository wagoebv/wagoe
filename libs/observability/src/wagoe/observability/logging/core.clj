(ns wagoe.observability.logging.core
  "Core logging functions and utilities.
   
   This namespace provides pure functions and higher-level abstractions over
   the logging protocols, making it easier for feature modules to perform
   common logging operations without dealing with protocol details directly."
  (:require
   [wagoe.observability.logging.ports :as ports]))

;; =============================================================================
;; Context Management Utilities
;; =============================================================================

(defn merge-contexts
  "Merges multiple context maps, with later maps taking precedence.
   
   Args:
     contexts - Variable number of context maps
   
   Returns:
     Merged context map"
  [& contexts]
  (apply merge contexts))

(defn with-correlation-id
  "Adds or updates correlation ID in context.
   
   Args:
     context        - Existing context map
     correlation-id - Correlation ID string
   
   Returns:
     Updated context map"
  [context correlation-id]
  (assoc context :correlation-id correlation-id))

(defn with-tenant-id
  "Adds or updates tenant ID in context.
   
   Args:
     context   - Existing context map
     tenant-id - Tenant ID string
   
   Returns:
     Updated context map"
  [context tenant-id]
  (assoc context :tenant-id tenant-id))

(defn with-user-id
  "Adds or updates user ID in context.
   
   Args:
     context - Existing context map
     user-id - User ID string
   
   Returns:
     Updated context map"
  [context user-id]
  (assoc context :user-id user-id))

(defn with-tags
  "Adds or merges tags in context.
   
   Args:
     context - Existing context map
     tags    - Map of tag key-value pairs
   
   Returns:
     Updated context map"
  [context tags]
  (update context :tags merge tags))

(defn with-trace-info
  "Adds distributed tracing information to context.
   
   Args:
     context  - Existing context map
     trace-id - Trace ID string
     span-id  - Span ID string
   
   Returns:
     Updated context map"
  [context trace-id span-id]
  (assoc context :trace-id trace-id :span-id span-id))

;; =============================================================================
;; Structured Logging Helpers
;; =============================================================================

(defn log-with-timing
  "Logs execution time of a function.
   
   Args:
     logger  - ILogger instance
     level   - Log level keyword
     message - Log message
     context - Context map
     f       - Function to time
   
   Returns:
     Result of function f"
  [logger level message context f]
  (let [start-time (System/nanoTime)
        result (f)
        duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)
        timing-context (assoc context :duration-ms duration-ms)]
    (ports/log* logger level message timing-context nil)
    result))

(defn log-function-entry
  "Logs function entry with arguments.
   
   Args:
     logger    - ILogger instance
     fn-name   - Function name string
     args      - Function arguments
     context   - Context map
   
   Returns:
     nil"
  [logger fn-name args context]
  (let [entry-context (assoc context
                             :function fn-name
                             :args args
                             :event :function-entry)]
    (ports/debug logger (str "Entering " fn-name) entry-context)))

(defn log-function-exit
  "Logs function exit with result.
   
   Args:
     logger    - ILogger instance
     fn-name   - Function name string
     result    - Function result
     context   - Context map
   
   Returns:
     result (passed through)"
  [logger fn-name result context]
  (let [exit-context (assoc context
                            :function fn-name
                            :result result
                            :event :function-exit)]
    (ports/debug logger (str "Exiting " fn-name) exit-context)
    result))

(defn with-function-logging
  "Wraps a function with entry/exit logging.
   
   Args:
     logger    - ILogger instance
     fn-name   - Function name string
     context   - Context map
     f         - Function to wrap
     & args    - Arguments to pass to function
   
   Returns:
     Result of function f"
  [logger fn-name context f & args]
  (log-function-entry logger fn-name args context)
  (try
    (let [result (apply f args)]
      (log-function-exit logger fn-name result context))
    (catch Exception e
      (ports/error logger (str "Exception in " fn-name) context e)
      (throw e))))

;; =============================================================================
;; Error Logging Helpers
;; =============================================================================

(defn log-exception
  "Logs an exception with structured context.
   
   Args:
     logger    - ILogger instance
     level     - Log level (:warn, :error, :fatal)
     message   - Descriptive message
     exception - Exception/throwable
     context   - Context map
   
   Returns:
     nil"
  [logger level message exception context]
  (let [error-context (assoc context
                             :exception-type (-> exception class .getSimpleName)
                             :exception-message (.getMessage exception)
                             :stack-trace (mapv str (.getStackTrace exception)))]
    (ports/log* logger level message error-context exception)))

(defn log-validation-error
  "Logs validation errors in a structured format.
   
   Args:
     logger - ILogger instance
     errors - Validation error data
     context - Context map
   
   Returns:
     nil"
  [logger errors context]
  (let [validation-context (assoc context
                                  :validation-errors errors
                                  :error-count (count errors)
                                  :event :validation-error)]
    (ports/warn logger "Validation failed" validation-context)))

(defn log-external-service-error
  "Logs external service call errors.
   
   Args:
     logger       - ILogger instance
     service-name - Name of external service
     operation    - Operation that failed
     error        - Error information
     context      - Context map
   
   Returns:
     nil"
  [logger service-name operation error context]
  (let [service-context (assoc context
                               :service service-name
                               :operation operation
                               :error error
                               :event :external-service-error)]
    (ports/error logger (str "External service error: " service-name) service-context)))

;; =============================================================================
;; Business Event Logging
;; =============================================================================

(defn log-user-action
  "Logs user actions for audit and analytics.
   
   Args:
     logger  - ILogger instance
     user-id - User identifier
     action  - Action taken
     resource - Resource acted upon
     result  - Action result
     context - Context map
   
   Returns:
     nil"
  [logger user-id action resource result context]
  (let [action-context (assoc context
                              :user-id user-id
                              :action action
                              :resource resource
                              :result result
                              :event :user-action)]
    (ports/info logger (str "User action: " action) action-context)))

(defn log-business-event
  "Logs business domain events.
   
   Args:
     logger     - ILogger instance
     event-type - Type of business event
     entity     - Business entity involved
     details    - Event details
     context    - Context map
   
   Returns:
     nil"
  [logger event-type entity details context]
  (let [business-context (assoc context
                                :event-type event-type
                                :entity entity
                                :details details
                                :event :business-event)]
    (ports/info logger (str "Business event: " event-type) business-context)))

;; =============================================================================
;; Audit Logging Helpers
;; =============================================================================

(defn audit-user-action
  "Audit logs user actions with full context.
   
   Args:
     audit-logger - IAuditLogger instance
     user-id      - User identifier
     resource     - Resource acted upon
     action       - Action taken
     result       - Action result
     context      - Additional context
   
   Returns:
     nil"
  [audit-logger user-id resource action result context]
  (ports/audit-event audit-logger :user-action user-id resource action result context))

(defn audit-system-event
  "Audit logs system events.
   
   Args:
     audit-logger - IAuditLogger instance
     system-id    - System/service identifier
     resource     - Resource affected
     action       - Action performed
     result       - Action result
     context      - Additional context
   
   Returns:
     nil"
  [audit-logger system-id resource action result context]
  (ports/audit-event audit-logger :system-event system-id resource action result context))

(defn audit-security-event
  "Audit logs security events.
   
   Args:
     audit-logger - IAuditLogger instance
     event-type   - Security event type
     severity     - Event severity
     details      - Event details
     context      - Additional context
   
   Returns:
     nil"
  [audit-logger event-type severity details context]
  (ports/security-event audit-logger event-type severity details context))

;; =============================================================================
;; Performance Logging
;; =============================================================================

(defn log-performance-metric
  "Logs performance measurements.
   
   Args:
     logger      - ILogger instance
     metric-name - Name of the metric
     value       - Metric value
     unit        - Unit of measurement
     context     - Context map
   
   Returns:
     nil"
  [logger metric-name value unit context]
  (let [perf-context (assoc context
                            :metric metric-name
                            :value value
                            :unit unit
                            :event :performance-metric)]
    (ports/info logger (str "Performance: " metric-name " = " value unit) perf-context)))

(defn log-request-metrics
  "Logs HTTP request performance metrics.
   
   Args:
     logger     - ILogger instance
     method     - HTTP method
     path       - Request path
     status     - Response status
     duration   - Request duration in ms
     context    - Context map
   
   Returns:
     nil"
  [logger method path status duration context]
  (let [request-context (assoc context
                               :http-method method
                               :http-path path
                               :http-status status
                               :duration-ms duration
                               :event :http-request)]
    (ports/info logger (str method " " path " " status " (" duration "ms)") request-context)))

;; =============================================================================
;; Conditional Logging
;; =============================================================================

(defn log-when
  "Conditionally logs a message.
   
   Args:
     condition - Boolean condition
     logger    - ILogger instance
     level     - Log level
     message   - Log message
     context   - Context map
   
   Returns:
     nil"
  [condition logger level message context]
  (when condition
    (ports/log* logger level message context nil)))

(defn log-when-dev
  "Logs only in development environment.
   
   Args:
     env     - Environment keyword
     logger  - ILogger instance
     level   - Log level
     message - Log message
     context - Context map
   
   Returns:
     nil"
  [env logger level message context]
  (log-when (= env :development) logger level message context))

(defn log-sampling
  "Logs with sampling rate.
   
   Args:
     sample-rate - Sampling rate (0.0 to 1.0)
     logger      - ILogger instance
     level       - Log level
     message     - Log message
     context     - Context map
   
   Returns:
     nil"
  [sample-rate logger level message context]
  (when (< (rand) sample-rate)
    (ports/log* logger level message context nil)))