(ns wagoe.platform.shell.interceptors
  "Universal interceptors for cross-cutting concerns.
   
   These interceptors handle common infrastructure concerns like logging,
   metrics, error handling, and context management across all modules."
  (:require [wagoe.observability.logging.ports :as logging]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.observability.errors.ports :as error-reporting]
            [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))
;; Context Management Interceptors

(def context-interceptor
  "Establishes basic context for request processing.
   
   Adds:
   - :correlation-id - UUID for request tracing
   - :now - Current timestamp
   - Ensures :op is present for operation identification"
  {:name :context
   :enter (fn [ctx]
            (-> ctx
                (assoc :correlation-id
                       (or (get-in ctx [:request :headers "x-correlation-id"])
                           (get-in ctx [:request :correlation-id])
                           (str (UUID/randomUUID))))
                (assoc :now (Instant/now))
                (update :op #(or % :unknown-operation))))})

;; Logging Interceptors

(def logging-start
  "Logs the start of an operation with structured data."
  {:name :logging-start
   :enter (fn [{:keys [op correlation-id system] :as ctx}]
            (when-let [logger (:logger system)]
              (logging/info logger "operation-start"
                            {:op op
                             :correlation-id correlation-id
                             :timestamp (:now ctx)}))
            ctx)})

(def logging-complete
  "Logs successful completion of an operation with timing information."
  {:name :logging-complete
   :leave (fn [{:keys [op correlation-id system] :as ctx}]
            (when-let [logger (:logger system)]
              (let [duration-ms (when-let [start (get-in ctx [:timing :start])]
                                  (/ (- (System/nanoTime) start) 1e6))
                    success? (= :success (get-in ctx [:result :status]))
                    log-data {:op op
                              :correlation-id correlation-id
                              :duration-ms duration-ms
                              :status (if success? "success" "completed-with-errors")
                              :effect-errors (count (:effect-errors ctx))}]
                (if success?
                  (logging/info logger "operation-success" log-data)
                  (logging/warn logger "operation-completed-with-errors" log-data))))
            ctx)})

(def logging-error
  "Logs operation failures with error details."
  {:name :logging-error
   :error (fn [{:keys [op correlation-id system exception] :as ctx}]
            (when-let [logger (:logger system)]
              (logging/error logger "operation-error"
                             {:op op
                              :correlation-id correlation-id
                              :error-message (ex-message exception)
                              :error-type (type exception)}))
            ctx)})

;; Metrics Interceptors

(def metrics-start
  "Records operation attempt and starts timing measurement."
  {:name :metrics-start
   :enter (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (metrics/increment metric-collector (str (name op) ".attempt") {}))
            (assoc-in ctx [:timing :start] (System/nanoTime)))})

(def metrics-complete
  "Records operation completion metrics including success/failure and latency."
  {:name :metrics-complete
   :leave (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (let [start-time (get-in ctx [:timing :start])
                    duration-ms (when start-time
                                  (/ (- (System/nanoTime) start-time) 1e6))
                    success? (= :success (get-in ctx [:result :status]))
                    op-name (name op)]

                ;; Record latency
                (when duration-ms
                  (metrics/observe metric-collector (str op-name ".latency.ms") duration-ms {}))

                ;; Record success/failure
                (if success?
                  (metrics/increment metric-collector (str op-name ".success") {})
                  (metrics/increment metric-collector (str op-name ".error") {}))))
            ctx)})

(def metrics-error
  "Records operation failure metrics when exceptions occur."
  {:name :metrics-error
   :error (fn [{:keys [op system] :as ctx}]
            (when-let [metric-collector (:metrics system)]
              (metrics/increment metric-collector (str (name op) ".failure") {}))
            ctx)})

;; Error Handling Interceptors

(def error-capture
  "Captures exceptions in error reporting system."
  {:name :error-capture
   :error (fn [{:keys [op correlation-id system exception] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/capture-exception
               error-reporter
               exception
               {:operation (subs (str op) 1) ; Remove the leading colon 
                :correlation-id correlation-id
                :context-keys (keys (dissoc ctx :system :exception))}))
            ctx)})


(def error-normalize
  "Generic error normalization interceptor that works with context error mappings.
   This runs in the :error phase when actual exceptions are thrown during pipeline execution."
  {:name :error-normalize
   :error (fn [{:keys [correlation-id exception] :as ctx}]
            (let [error-data (ex-data exception)
                  error-type (or (:type error-data) :internal-server-error) ; Default if no type
                  error-message (ex-message exception)
                  error-mappings (or (:error-mappings ctx) {}) ; Default empty mappings
                  ;; Look up the error mapping for this error type
                  [status-code title] (get error-mappings error-type [500 "Internal Server Error"])
                  ;; Create generic error response using mappings
                  base-body {:type (name error-type)
                             :title title
                             :status status-code
                             :detail error-message
                             :correlation-id correlation-id ; Use kebab-case to match test
                             :timestamp (.toString (java.time.Instant/now))}
                  ;; Add all extension fields from error-data except :type and :message
                  ;; Convert UUID values to strings for JSON serialization
                  extension-fields (reduce-kv (fn [acc k v]
                                                (if (instance? java.util.UUID v)
                                                  (assoc acc k (str v))
                                                  (assoc acc k v)))
                                              {}
                                              (dissoc error-data :type :message))
                  full-body (merge base-body extension-fields)]
              (assoc ctx :response {:status status-code :body full-body})))})
(defn convert-exception-to-response
  "Converts an exception in the context into an appropriate HTTP error response.
   Uses error mappings from the context to map domain-specific error types to HTTP responses."
  [ctx]
  (let [exception (:exception ctx)
        error-data (ex-data exception)
        error-type (:type error-data)
        error-message (ex-message exception)
        correlation-id (:correlation-id ctx)
        error-mappings (:error-mappings ctx)
        ;; Look up the error mapping for this error type
        [status-code title] (get error-mappings error-type [500 "Internal Server Error"])]

    (cond
      ;; Handle validation errors with enhanced field extraction
      (= error-type :validation-error)
      (let [errors (:errors error-data)
            ;; Extract field-level information from validation errors
            field-info (reduce (fn [acc error]
                                 (let [field (:field error)
                                       field-keyword (cond
                                                       (keyword? field) field
                                                       (vector? field) (first field)
                                                       :else (keyword (str field)))]
                                   (update acc :missing-fields (fnil conj []) field-keyword)))
                               {:missing-fields []}
                               errors)
            ;; Try to get provided fields from the original data in error context
            provided-fields (when-let [original-data (:original-data error-data)]
                              (vec (keys original-data)))

            response {:status 400
                      :body {:type (name error-type)
                             :title "Validation Error"
                             :status 400
                             :detail error-message
                             :correlationId correlation-id
                             :missing-fields (:missing-fields field-info)
                             :provided-fields (or provided-fields [])
                             :interface-type (:interface-type error-data :cli)
                             :validation-details errors ; Include original error details
                             :timestamp (.toString (java.time.Instant/now))}}]
        (assoc ctx :response response))

      ;; Handle user-related errors
      (= error-type :user-not-found)
      (let [response {:status status-code
                      :body {:type (name error-type)
                             :title title
                             :status status-code
                             :detail error-message
                             :instance (str "/users/" (:user-id error-data))
                             :correlationId correlation-id
                             :user-id (str (:user-id error-data))
                             :timestamp (.toString (java.time.Instant/now))}}]
        (assoc ctx :response response))

      ;; Handle session-related errors
      (= error-type :session-not-found)
      (let [response {:status status-code
                      :body {:type (name error-type)
                             :title title
                             :status status-code
                             :detail error-message
                             :instance (str "/sessions/" (:token error-data))
                             :correlationId correlation-id
                             :token (str (:token error-data))
                             :valid (:valid error-data)
                             :timestamp (.toString (java.time.Instant/now))}}]
        (assoc ctx :response response))

      ;; Handle other domain errors with extension fields from error-data
      error-type
      (let [base-body {:type (name error-type)
                       :title title
                       :status status-code
                       :detail error-message
                       :correlationId correlation-id
                       :timestamp (.toString (java.time.Instant/now))}
            ;; Add all extension fields from error-data except :type and :message
            ;; Convert UUID values to strings for JSON serialization
            extension-fields (reduce-kv (fn [acc k v]
                                          (if (instance? java.util.UUID v)
                                            (assoc acc k (str v))
                                            (assoc acc k v)))
                                        {}
                                        (dissoc error-data :type :message))
            full-body (merge base-body extension-fields)]
        (assoc ctx :response {:status status-code :body full-body}))

      ;; Default case for unhandled error types
      :else
      (let [response {:status 500
                      :body {:type "internal-server-error"
                             :title "Internal Server Error"
                             :status 500
                             :detail "An unexpected error occurred while processing your request"
                             :correlationId correlation-id
                             :timestamp (.toString (java.time.Instant/now))}}]
        (assoc ctx :response response)))))

(def error-response-converter
  "Converts failed contexts (halted with exceptions) into proper HTTP error responses.
   Runs in both leave and error phases to catch contexts that were halted by fail-with-exception
   or had exceptions during pipeline execution.

   Uses error mappings from the context to map domain-specific error types to HTTP responses."
  {:name :error-response-converter
   :leave (fn [ctx]
            (if (and (:halt? ctx) (:exception ctx) (not (:response ctx)))
              ;; Context was halted with an exception but no response was set
              ;; Convert the exception into an HTTP error response using context mappings
              (convert-exception-to-response ctx)
              ;; Context is not failed or already has a response
              ctx))
   :error (fn [ctx]
            (if (and (:exception ctx) (not (:response ctx)))
              ;; Context has an exception from pipeline execution but no response was set
              ;; Convert the exception into an HTTP error response using context mappings
              (convert-exception-to-response ctx)
              ;; Context doesn't have an exception or already has a response
              ctx))})

;; Effects Execution Interceptor

(def effects-dispatch
  "Executes side effects returned by core functions.
   
   Processes the :effects vector from core function results and executes
   each effect, collecting any errors for later handling."
  {:name :effects-dispatch
   :enter (fn [ctx]
            (if-let [effects (get-in ctx [:result :effects])]
              (reduce
               (fn [acc-ctx effect]
                 (try
                   (case (:type effect)
                     :persist-user
                     (do
                       (when-let [repo (get-in acc-ctx [:system :user-repository])]
                          ;; Execute persistence effect
                         (.create-user repo (:user effect)))
                       acc-ctx)

                     :send-welcome-email
                     (do
                       (when-let [notification-service (get-in acc-ctx [:system :notification-service])]
                          ;; Execute notification effect
                         (.send-welcome-email notification-service (:user effect)))
                       acc-ctx)

                     :send-notification
                     (do
                       (when-let [notification-service (get-in acc-ctx [:system :notification-service])]
                         (.send-notification notification-service (:notification effect)))
                       acc-ctx)

                      ;; Default: log unknown effect type
                     (do
                       (when-let [logger (get-in acc-ctx [:system :logger])]
                         (logging/warn logger "unknown-effect-type"
                                       {:effect-type (:type effect)
                                        :effect effect}))
                       acc-ctx))
                   (catch Throwable e
                     (update acc-ctx :effect-errors (fnil conj [])
                             {:effect effect
                              :error (ex-message e)
                              :error-type (type e)}))))
               (assoc ctx :effect-errors [])
               effects)
              ;; No effects to process
              ctx))})

;; Request/Response Transformation Interceptors

(def response-shape-http
  "Shapes successful results into HTTP response format."
  {:name :response-shape-http
   :leave (fn [ctx]
            (if (:response ctx)
              ;; Response already set (probably by validation or error)
              ctx
              ;; Shape successful result
              (let [result (:result ctx)]
                (case (:status result)
                  :success
                  (assoc ctx :response
                         {:status 201
                          :body (:data result)
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})

                  :error
                  (assoc ctx :response
                         {:status 400
                          :body {:type "domain-error"
                                 :title "Business Rule Violation"
                                 :status 400
                                 :errors (:errors result)
                                 :correlation-id (:correlation-id ctx)
                                 :timestamp (.toString (:now ctx))}
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})

                  ;; Default case
                  (assoc ctx :response
                         {:status 500
                          :body {:type "unknown-result-status"
                                 :title "Unknown Result Status"
                                 :status 500
                                 :correlation-id (:correlation-id ctx)}
                          :headers {"X-Correlation-ID" (:correlation-id ctx)}})))))})

(def response-shape-cli
  "Shapes results into CLI response format with exit codes.
   
   This interceptor handles both :result format (with :status/:data/:errors)
   and :response format (direct CLI response) to maintain compatibility.
   
   For error responses already set by error handling interceptors, converts
   HTTP-style responses to CLI format with detailed error messages."
  {:name :response-shape-cli
   :leave (fn [ctx]
            (if-let [response (:response ctx)]
              ;; Response already set - check if it needs CLI formatting
              (if (and (contains? response :status) (contains? response :body))
                ;; HTTP-style error response - convert to CLI format
                (let [body (:body response)
                      status (:status response)
                      exit-code (if (>= status 400) 1 0)]
                  (if (>= status 400)
                    ;; Error response - extract detailed error info
                    (let [error-details (:detail body)
                          missing-fields (:missing-fields body)
                          provided-fields (:provided-fields body)
                          interface-type (:interface-type body)

                          ;; Build detailed error message
                          detailed-message (str error-details
                                                (when missing-fields
                                                  (str "\nMissing required fields: " (str/join ", " missing-fields)))
                                                (when provided-fields
                                                  (str "\nProvided fields: " (str/join ", " provided-fields)))
                                                (when interface-type
                                                  (str "\nInterface: " (name interface-type))))]
                      (assoc ctx :response
                             {:exit exit-code
                              :stderr detailed-message}))
                    ;; Success response
                    (assoc ctx :response
                           {:exit exit-code
                            :stdout (str "Success: " (pr-str body))})))
                ;; Already CLI format - pass through
                ctx)
              ;; Shape result for CLI (fallback)
              (let [result (:result ctx)]
                (case (:status result)
                  :success
                  (assoc ctx :response
                         {:exit 0
                          :stdout (str "Success: " (pr-str (:data result)))})

                  :error
                  (assoc ctx :response
                         {:exit 1
                          :stderr (str "Error: " (pr-str (:errors result)))})

                  ;; Default case
                  (assoc ctx :response
                         {:exit 2
                          :stderr "Unknown error occurred"})))))})

;; Common Pipeline Templates

(def base-observability-pipeline
  "Base pipeline with essential observability interceptors.
   Suitable for most operations that don't need specialized handling."
  [context-interceptor
   logging-start
   metrics-start
   logging-complete
   metrics-complete])

(def error-handling-pipeline
  "Error handling interceptors for exception management."
  [error-capture
   logging-error
   metrics-error
   error-normalize])

(def http-response-pipeline
  "HTTP-specific pipeline with request/response handling."
  (conj base-observability-pipeline response-shape-http))

(def cli-response-pipeline
  "CLI-specific pipeline with command/response handling."
  (conj base-observability-pipeline response-shape-cli))

;; Pipeline Assembly Utilities

(defn create-http-pipeline
  "Creates a complete HTTP pipeline with custom interceptors inserted at the appropriate point."
  [& custom-interceptors]
  (vec (concat [context-interceptor
                logging-start
                metrics-start
                error-response-converter] ; Move error handling BEFORE custom interceptors
               custom-interceptors
               [effects-dispatch
                logging-complete
                metrics-complete
                response-shape-http])))

(defn create-cli-pipeline
  "Creates a complete CLI pipeline with custom interceptors."
  [& custom-interceptors]
  (vec (concat [context-interceptor
                logging-start
                metrics-start
                error-response-converter] ; Add error handling for CLI like HTTP
               custom-interceptors
               [effects-dispatch
                logging-complete
                metrics-complete
                response-shape-cli])))

(defn add-error-handling
  "Adds error handling interceptors to any pipeline."
  [pipeline]
  (vec (concat pipeline error-handling-pipeline)))