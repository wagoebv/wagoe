(ns wagoe.platform.shell.interceptors
  "Universal interceptors for cross-cutting concerns.
   
   These interceptors handle common infrastructure concerns like logging,
   metrics, error handling, and context management across all modules."
  (:require [wagoe.observability.logging.ports :as logging]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.observability.errors.ports :as error-reporting]
            [wagoe.platform.core.http.errors :as errors]
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

(defn- json-safe
  "ex-data fit for a JSON body: UUIDs as strings."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc k (if (instance? UUID v) (str v) v))) {} m))

(def error-normalize
  "Generic error normalization interceptor that works with context error mappings.
   This runs in the :error phase when actual exceptions are thrown during pipeline execution."
  {:name :error-normalize
   :error (fn [{:keys [correlation-id exception] :as ctx}]
            (let [error-data (ex-data exception)
                  error-type (or (:type error-data) :internal-server-error) ; Default if no type
                  error-mappings (or (:error-mappings ctx) {}) ; Default empty mappings
                  [status-code _title] (get error-mappings error-type [500 "Internal Server Error"])]
              (assoc ctx :response
                     {:status status-code
                      :body   (errors/body error-type
                                           (ex-message exception)
                                           {:details        (json-safe (dissoc error-data :type :message))
                                            :correlation-id correlation-id})})))})

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
        [status-code _title] (get error-mappings error-type [500 "Internal Server Error"])
        respond (fn [status type message details]
                  (assoc ctx :response
                         {:status status
                          :body   (errors/body type message
                                               {:details        details
                                                :correlation-id correlation-id})}))]

    (cond
      ;; Handle validation errors with enhanced field extraction
      (= error-type :validation-error)
      (let [errors (:errors error-data)
            field-of (fn [error]
                       (let [field (:field error)]
                         (cond
                           (keyword? field) field
                           (vector? field) (first field)
                           :else (keyword (str field)))))
            ;; Only errors that really are an absent key. Every validation
            ;; error used to land here, so a password rejected by the policy
            ;; was reported as "Missing fields: :password" — a rule violation
            ;; described as a field the caller forgot to send (BOU-447).
            missing-fields (into [] (comp (filter #(= :missing-required-field (:code %)))
                                          (map field-of))
                                 errors)
            ;; Field + message for everything else, so a consumer has the
            ;; violation itself and not just the name of the field it is about.
            field-errors (into [] (comp (remove #(= :missing-required-field (:code %)))
                                        (map (fn [e] {:field (field-of e)
                                                      :code (:code e)
                                                      :message (:message e)})))
                               errors)
            ;; Try to get provided fields from the original data in error context
            provided-fields (when-let [original-data (:original-data error-data)]
                              (vec (keys original-data)))]
        (respond 400 error-type error-message
                 {:missing-fields     missing-fields
                  :field-errors       field-errors
                  :provided-fields    (or provided-fields [])
                  :interface-type     (:interface-type error-data :cli)
                  :validation-details errors}))

      ;; Handle user-related errors
      (= error-type :user-not-found)
      (respond status-code error-type error-message
               {:user-id (str (:user-id error-data))})

      ;; Handle session-related errors
      (= error-type :session-not-found)
      (respond status-code error-type error-message
               {:valid (:valid error-data)})

      ;; Handle other domain errors with extension fields from error-data
      error-type
      (respond status-code error-type error-message
               (json-safe (dissoc error-data :type :message)))

      ;; Default case for unhandled error types
      :else
      (respond 500 :internal-error
               "An unexpected error occurred while processing your request"
               nil))))

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
                         (errors/response 400 :business-rule-violation "Business Rule Violation"
                                          {:details        {:errors (:errors result)}
                                           :correlation-id (:correlation-id ctx)}))

                  ;; Default case
                  (assoc ctx :response
                         (errors/response 500 :internal-error "Unknown Result Status"
                                          {:correlation-id (:correlation-id ctx)}))))))})

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
                    (let [error-details (get-in body [:error :message])
                          details (get-in body [:error :details])
                          missing-fields (seq (:missing-fields details))
                          field-errors (seq (:field-errors details))
                          provided-fields (:provided-fields details)
                          interface-type (:interface-type details)

                          ;; Build detailed error message
                          detailed-message (str error-details
                                                (when missing-fields
                                                  (str "\nMissing required fields: " (str/join ", " missing-fields)))
                                                ;; The violation itself, which
                                                ;; a bare field name never said.
                                                (when field-errors
                                                  (str "\n"
                                                       (str/join "\n"
                                                                 (for [{:keys [field message]} field-errors]
                                                                   (str "  " field ": " message)))))
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