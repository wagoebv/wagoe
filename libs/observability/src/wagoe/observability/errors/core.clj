(ns wagoe.observability.errors.core
  "Core error reporting functions and utilities.

   This namespace provides pure functions and higher-level abstractions over
   the error reporting protocols, making it easier for feature modules to
   report errors without dealing with protocol details directly."
  (:require
   [wagoe.observability.errors.ports :as ports]
   [clojure.string :as str]))

;; =============================================================================
;; Context Management Utilities
;; =============================================================================

(defn build-error-context
  "Builds a comprehensive error context map.
   
   Args:
     base-context - Base context map
     extra-data   - Additional context data
   
   Returns:
     Complete error context map"
  [base-context extra-data]
  (merge base-context
         {:timestamp (System/currentTimeMillis)
          :extra extra-data}))

(defn with-user-context
  "Adds user information to error context.
   
   Args:
     context - Existing context map
     user-id - User identifier
     user-info - Additional user information
   
   Returns:
     Updated context map"
  [context user-id user-info]
  (assoc context
         :user-id user-id
         :user-info user-info))

(defn with-request-context
  "Adds HTTP request information to error context.
   
   Args:
     context        - Existing context map
     request-id     - Request identifier
     method         - HTTP method
     path           - Request path
     headers        - Request headers (sensitive data filtered)
   
   Returns:
     Updated context map"
  [context request-id method path headers]
  (assoc context
         :request-id request-id
         :http-method (str/upper-case (name method))
         :http-path (str path)
         :http-headers headers))

(defn with-operation-context
  "Adds operation/business context to error reporting.
   
   Args:
     context   - Existing context map
     operation - Operation being performed
     entity    - Business entity involved
     action    - Specific action
   
   Returns:
     Updated context map"
  [context operation entity action]
  (assoc context
         :operation (str operation)
         :entity (str entity)
         :action (str action)))

(defn sanitize-context
  "Removes sensitive information from error context.
   
   Args:
     context        - Context map
     sensitive-keys - Set of keys to remove or mask
   
   Returns:
     Sanitized context map"
  [context sensitive-keys]
  (reduce (fn [ctx key]
            (if (contains? ctx key)
              (assoc ctx key "[REDACTED]")
              ctx))
          context
          sensitive-keys))

;; =============================================================================
;; Error Classification Utilities
;; =============================================================================

(defn classify-exception
  "Classifies an exception by type and characteristics.
   
   Args:
     exception - Exception instance
   
   Returns:
     Classification map with :type, :category, :severity"
  [exception]
  (let [ex-type (-> exception class .getSimpleName)
        ex-message (.getMessage exception)]
    {:type ex-type
     :category (cond
                 (re-find #"(?i)validation|invalid|malformed" ex-message) :validation
                 (re-find #"(?i)auth|permission|forbidden" ex-message) :security
                 (re-find #"(?i)timeout|connection|network" ex-message) :connectivity
                 (re-find #"(?i)database|sql|transaction" ex-message) :database
                 (re-find #"(?i)service|external|api" ex-message) :external-service
                 :else :application)
     :severity (cond
                 (instance? java.lang.OutOfMemoryError exception) :fatal
                 (instance? java.lang.SecurityException exception) :high
                 (instance? java.net.SocketTimeoutException exception) :medium
                 (instance? java.lang.IllegalArgumentException exception) :low
                 :else :medium)}))

(defn should-report-exception?
  "Determines if an exception should be reported based on classification.
   
   Args:
     exception - Exception instance
     config    - Error reporting configuration
   
   Returns:
     Boolean indicating whether to report"
  [exception config]
  (let [{:keys [category severity]} (classify-exception exception)
        min-severity (get config :min-severity :medium)
        excluded-categories (get config :excluded-categories #{})]
    (and (not (contains? excluded-categories category))
         (>= (get {:low 1 :medium 2 :high 3 :fatal 4} severity 2)
             (get {:low 1 :medium 2 :high 3 :fatal 4} min-severity 2)))))

;; =============================================================================
;; High-Level Error Reporting Functions
;; =============================================================================

(defn report-application-error
  "Reports an application error with full context.
   
   Args:
     reporter  - IErrorReporter instance
     exception - Exception instance
     message   - Descriptive error message
     context   - Error context map
   
   Returns:
     Error ID string"
  [reporter exception message context]
  (let [classification (classify-exception exception)
        enriched-context (merge context
                                classification
                                {:error-type :application
                                 :message message
                                 :timestamp (or (:timestamp context) (java.time.Instant/now))})]
    (ports/capture-exception reporter exception enriched-context)))

(defn error-context->reporting-context
  "Convert enhanced error context to error reporting context format.
   
   Transforms the enhanced error context from Problem Details into the format
   expected by the error reporting system, ensuring compatibility and proper
   context preservation.
   
   Args:
     error-context - Enhanced error context map from Problem Details
     
   Returns:
     Error reporting context map with proper structure"
  [error-context]
  (when error-context
    (cond-> {}
      (:user-id error-context) (assoc :user-id (:user-id error-context))
      (:tenant-id error-context) (assoc :tenant-id (:tenant-id error-context))
      (:trace-id error-context) (assoc :trace-id (:trace-id error-context))
      (:request-id error-context) (assoc :request-id (:request-id error-context))
      (:ip-address error-context) (assoc :ip-address (:ip-address error-context))
      (:user-agent error-context) (assoc :user-agent (:user-agent error-context))
      (:environment error-context) (assoc :environment (:environment error-context))
      (:timestamp error-context) (assoc :timestamp (:timestamp error-context))
      (:command error-context) (assoc :command (:command error-context))
      ;; HTTP-specific context
      (:uri error-context) (assoc :uri (:uri error-context))
      (:method error-context) (assoc :method (:method error-context))
      ;; CLI-specific context
      (:operation error-context) (assoc :operation (:operation error-context))
      (:process-id error-context) (assoc :process-id (:process-id error-context))
      (:cli-version error-context) (assoc :cli-version (:cli-version error-context))
      (:args error-context) (assoc :args (:args error-context))
      ;; Nested data structures
      (:request-headers error-context) (assoc :request-headers (:request-headers error-context))
      (:response-metadata error-context) (assoc :response-metadata (:response-metadata error-context))
      (:cache-hit error-context) (assoc :cache-hit (:cache-hit error-context))
      ;; Additional context goes into extra
      (seq (dissoc error-context :user-id :tenant-id :trace-id :request-id
                   :ip-address :user-agent :environment :timestamp :command
                   :uri :method :operation :process-id :cli-version :args
                   :request-headers :response-metadata :cache-hit))
      (assoc :extra (dissoc error-context :user-id :tenant-id :trace-id :request-id
                            :ip-address :user-agent :environment :timestamp :command
                            :uri :method :operation :process-id :cli-version :args
                            :request-headers :response-metadata :cache-hit)))))

(defn extract-cause-chain
  "Extract the cause chain from an exception into a nested structure.
   
   Args:
     exception - Exception instance
     
   Returns:
     Nested map representing the cause chain, or nil if no cause"
  [exception]
  (when-let [cause (.getCause exception)]
    (let [cause-info {:message (.getMessage cause)
                      :data (when (instance? clojure.lang.ExceptionInfo cause)
                              (ex-data cause))}]
      (if-let [nested-cause (extract-cause-chain cause)]
        (assoc cause-info :cause nested-cause)
        cause-info))))

(defn report-enhanced-application-error
  "Reports an application error with enhanced context from Problem Details.
   
   This function bridges the enhanced error context from our Problem Details
   implementation with the error reporting system, ensuring all context
   information is properly preserved for debugging.
   
   Args:
     reporter  - IErrorReporter instance
     exception - Exception instance
     message   - Descriptive error message
     context   - Standard error context map
     error-context - Enhanced error context from Problem Details
   
   Returns:
     Error ID string"
  [reporter exception message context error-context]
  (let [classification (classify-exception exception)
        ;; Convert enhanced context to reporting format
        reporting-context (error-context->reporting-context error-context)
        ;; Extract cause chain
        cause-chain (extract-cause-chain exception)
        ;; Merge all context together
        enriched-context (cond-> (merge context
                                        reporting-context
                                        classification
                                        {:error-type :application
                                         :message message
                                         :timestamp (System/currentTimeMillis)
                                         :enhanced-context error-context})
                           cause-chain (assoc :cause cause-chain))]
    (ports/capture-exception reporter exception enriched-context)))

(defn report-validation-error
  "Reports validation errors in a structured format.
   
   Args:
     reporter - IErrorReporter instance
     errors   - Validation error data
     context  - Error context map
   
   Returns:
     Error ID string"
  [reporter errors context]
  (let [error-context (merge context
                             {:error-type :validation
                              :validation-errors errors
                              :error-count (count errors)})]
    (ports/capture-message reporter "Validation failed" :warning error-context {})))

(defn report-external-service-error
  "Reports external service errors.
   
   Args:
     reporter     - IErrorReporter instance
     service-name - Name of external service
     operation    - Operation that failed
     error        - Error information
     context      - Error context map
   
   Returns:
     Error ID string"
  [reporter service-name operation error context]
  (let [service-context (merge context
                               {:error-type :external-service
                                :service service-name
                                :operation operation
                                :service-error error})]
    (ports/capture-message reporter
                           (str "External service error: " service-name)
                           :error
                           service-context {})))

(defn report-security-incident
  "Reports security-related incidents.
   
   Args:
     reporter   - IErrorReporter instance
     incident   - Incident type
     details    - Incident details
     context    - Error context map
   
   Returns:
     Error ID string"
  [reporter incident details context]
  (let [security-context (merge context
                                {:error-type :security
                                 :incident-type incident
                                 :security-details details
                                 :severity :high})]
    (ports/capture-message reporter
                           (str "Security incident: " incident)
                           :error
                           security-context {})))

;; =============================================================================
;; Exception Handling Utilities
;; =============================================================================

(defn with-error-reporting
  "Wraps a function with automatic error reporting.
   
   Args:
     reporter - IErrorReporter instance
     context  - Base error context
     f        - Function to wrap
   
   Returns:
     Result of function f, or re-throws exception after reporting"
  [reporter context f]
  (try
    (f)
    (catch Exception e
      (report-application-error reporter e "Unhandled exception in operation" context)
      (throw e))))

(defn with-fallback-error-reporting
  "Wraps a function with error reporting and fallback value.
   
   Args:
     reporter      - IErrorReporter instance
     context       - Base error context
     fallback      - Fallback value to return on error
     f             - Function to wrap
   
   Returns:
     Result of function f, or fallback value if exception occurs"
  [reporter context fallback f]
  (try
    (f)
    (catch Exception e
      (report-application-error reporter e "Exception caught with fallback" context)
      fallback)))

(defn report-and-continue
  "Reports an exception but doesn't re-throw it.
   
   Args:
     reporter  - IErrorReporter instance
     exception - Exception to report
     message   - Error message
     context   - Error context
   
   Returns:
     Error ID string"
  [reporter exception message context]
  (try
    (report-application-error reporter exception message context)
    (catch Exception _report-error
      ;; If error reporting itself fails, return sentinel — callers can check for this
      "error-reporting-failed")))

;; =============================================================================
;; Breadcrumb Management
;; =============================================================================

(defn add-breadcrumb
  "Adds a breadcrumb to the error context for tracking user actions.
   
   Args:
     context   - IErrorContext instance
     message   - Breadcrumb message
     category  - Breadcrumb category
     level     - Breadcrumb level (:debug, :info, :warning, :error)
     data      - Additional breadcrumb data
   
   Returns:
     nil"
  [context message category level data]
  (let [breadcrumb {:message message
                    :category category
                    :level level
                    :timestamp (java.time.Instant/now)
                    :data data}]
    (ports/add-breadcrumb! context breadcrumb)))

(defn track-user-navigation
  "Tracks user navigation as breadcrumbs.
   
   Args:
     context - IErrorContext instance
     path    - Navigation path
     method  - HTTP method (for API calls)
   
   Returns:
     nil"
  [context path method]
  (add-breadcrumb context
                  (str (str/upper-case (name method)) " " path)
                  "navigation"
                  :info
                  {:path path :method method}))

(defn track-user-action
  "Tracks user actions as breadcrumbs.
   
   Args:
     context - IErrorContext instance
     action  - Action performed
     target  - Target of the action
   
   Returns:
     nil"
  [context action target]
  (add-breadcrumb context
                  (str "User " action " " target)
                  "user-action"
                  :info
                  {:action action :target target}))

;; =============================================================================
;; Error Aggregation and Filtering
;; =============================================================================

(defn create-error-fingerprint
  "Creates a fingerprint for error grouping.
   
   Args:
     exception - Exception instance
     context   - Error context
   
   Returns:
     Fingerprint string for grouping similar errors"
  [exception context]
  (let [ex-type (-> exception class .getSimpleName)
        stack-trace-hash (hash (take 5 (.getStackTrace exception)))
        operation (get context :operation "unknown")]
    (str ex-type ":" operation ":" stack-trace-hash)))

(defn should-suppress-duplicate?
  "Determines if a duplicate error should be suppressed.
   
   Args:
     fingerprint    - Error fingerprint
     recent-reports - Map of recent error reports
     suppress-window - Time window for suppression in ms
   
   Returns:
     Boolean indicating whether to suppress"
  [fingerprint recent-reports suppress-window]
  (let [last-report-time (get recent-reports fingerprint 0)
        current-time (System/currentTimeMillis)]
    (< (- current-time last-report-time) suppress-window)))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn create-default-sensitive-keys
  "Creates a default set of sensitive keys to filter from error reports.
   
   Returns:
     Set of sensitive key names"
  []
  #{:password :token :secret :key :authorization :api-key
    :client-secret :private-key :credential :auth-token})

(defn merge-error-configs
  "Merges multiple error reporting configurations.
   
   Args:
     configs - Variable number of configuration maps
   
   Returns:
     Merged configuration map"
  [& configs]
  (apply merge-with (fn [a b] (if (coll? b) b a)) configs))

