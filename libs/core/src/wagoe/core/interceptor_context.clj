;; ^:boundary/allow-throw — a construction-time guard for the interceptor
;; context schema (part of the pipeline engine, see wagoe.core.interceptor).
(ns ^:boundary/allow-throw wagoe.core.interceptor-context
  "Context schema and validation for interceptor pipelines.

   Defines the structure and validation rules for the context map that flows
   through interceptor pipelines, ensuring consistent data flow and preventing
   common runtime errors."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; Core context schemas

(def SystemDeps
  "Schema for the system dependency injection map."
  [:map {:title "System Dependencies"}
   [:logger {:optional true} :any]
   [:metrics {:optional true} :any]
   [:error-reporter {:optional true} :any]
   [:config {:optional true} :any]
   ;; Domain-specific repositories and services
   [:user-repository {:optional true} :any]
   [:notification-service {:optional true} :any]
   [:billing-repository {:optional true} :any]
   [:payment-processor {:optional true} :any]
   [:workflow-engine {:optional true} :any]])

(def Request
  "Schema for request data (HTTP, CLI, or service call)."
  [:map {:title "Request Data"}
   [:headers {:optional true} [:map-of :string :string]]
   [:body {:optional true} :any]
   [:params {:optional true} [:map-of :keyword :any]]
   [:query {:optional true} [:map-of :string :string]]
   [:correlation-id {:optional true} :string]
   ;; CLI-specific fields
   [:args {:optional true} [:vector :string]]
   [:options {:optional true} [:map-of :keyword :any]]])

(def Result
  "Schema for core function results."
  [:map {:title "Core Function Result"}
   [:status [:enum :success :error]]
   [:data {:optional true} :any]
   [:errors {:optional true} [:vector [:map
                                       [:field {:optional true} :string]
                                       [:code :string]
                                       [:message :string]]]]
   [:events {:optional true} [:vector :map]]
   [:effects {:optional true} [:vector [:map
                                        [:type :keyword]
                                        [:user {:optional true} :any]
                                        [:notification {:optional true} :any]
                                        [:email {:optional true} :string]]]]])

(def Response
  "Schema for final response data."
  [:or
   ;; HTTP Response
   [:map {:title "HTTP Response"}
    [:status :int]
    [:body :any]
    [:headers {:optional true} [:map-of :string :string]]]
   ;; CLI Response
   [:map {:title "CLI Response"}
    [:exit :int]
    [:stdout {:optional true} :string]
    [:stderr {:optional true} :string]]])

(def Timing
  "Schema for timing measurements."
  [:map {:title "Timing Data"}
   [:start {:optional true} :int] ; nanosecond timestamp
   [:end {:optional true} :int]
   [:duration-ms {:optional true} :double]])

(def EffectError
  "Schema for side effect execution errors."
  [:map {:title "Effect Execution Error"}
   [:effect :map]
   [:error :string]
   [:error-type :any]])

(def Context
  "Complete schema for interceptor context."
  [:map {:title "Interceptor Context"}
   ;; Required fields
   [:op :keyword]
   [:system SystemDeps]

   ;; Optional but commonly present fields
   [:request {:optional true} Request]
   [:correlation-id {:optional true} :string]
   [:now {:optional true} inst?]
   [:tenant {:optional true} :map]

   ;; Processing state fields
   [:validated {:optional true} :any]
   [:result {:optional true} Result]
   [:response {:optional true} Response]
   [:timing {:optional true} Timing]

   ;; Error handling fields
   [:errors {:optional true} [:vector :map]]
   [:exception {:optional true} :any]
   [:effect-errors {:optional true} [:vector EffectError]]

   ;; Control flow fields
   [:halt? {:optional true} :boolean]
   [:breadcrumbs {:optional true} [:vector :map]]])

(def ^:private context-validator (m/validator Context))
(def ^:private context-explainer (m/explainer Context))

;; Validation functions

(defn validate-context
  "Validates context map against schema.
   Returns {:valid? true :data context} or {:valid? false :errors [...]}."
  [context]
  (if (context-validator context)
    {:valid? true :data context}
    (let [errors (-> (context-explainer context)
                     (me/humanize))]
      {:valid? false :errors errors})))

(defn validate-context!
  "Validates context and throws exception if invalid."
  [context]
  (let [result (validate-context context)]
    (if (:valid? result)
      (:data result)
      (throw (ex-info "Invalid interceptor context"
                      {:context-keys (keys context)
                       :validation-errors (:errors result)})))))

(defn create-initial-context
  "Creates a properly structured initial context for pipeline execution."
  [op system & {:keys [request correlation-id tenant]}]
  (cond-> {:op op
           :system system}
    request (assoc :request request)
    correlation-id (assoc :correlation-id correlation-id)
    tenant (assoc :tenant tenant)))

;; Context manipulation utilities

(defn get-operation
  "Safely extracts operation from context."
  [context]
  (:op context))

(defn get-system-dependency
  "Safely extracts system dependency from context."
  [context dependency-key]
  (get-in context [:system dependency-key]))

(defn get-logger
  "Extracts logger from system dependencies."
  [context]
  (get-system-dependency context :logger))

(defn get-metrics
  "Extracts metrics collector from system dependencies."
  [context]
  (get-system-dependency context :metrics))

(defn get-error-reporter
  "Extracts error reporter from system dependencies."
  [context]
  (get-system-dependency context :error-reporter))

(defn get-request-data
  "Safely extracts request data from context."
  ([context]
   (:request context))
  ([context key-path]
   (if (vector? key-path)
     (get-in context (concat [:request] key-path))
     (get-in context [:request key-path]))))

(defn get-correlation-id
  "Extracts correlation ID from context."
  [context]
  (:correlation-id context))

(defn get-timing
  "Extracts timing information from context."
  ([context]
   (:timing context))
  ([context timing-key]
   (get-in context [:timing timing-key])))

(defn has-result?
  "Checks if context contains a result from core function execution.
   
    Args:
      context: Interceptor context map
    
    Returns:
      Boolean indicating result presence"
  [context]
  (contains? context :result))

(defn get-result
  "Extracts result from context."
  ([context]
   (:result context))
  ([context key-path]
   (if (vector? key-path)
     (get-in context (concat [:result] key-path))
     (get-in context [:result key-path]))))

(defn success?
  "Checks if the operation was successful based on result status.
   
    Args:
      context: Interceptor context map
    
    Returns:
      Boolean indicating operation success"
  [context]
  (= :success (get-in context [:result :status])))

(defn has-errors?
  "Checks if context contains validation or domain errors.
   
    Args:
      context: Interceptor context map
    
    Returns:
      Boolean indicating error presence"
  [context]
  (or (seq (:errors context))
      (seq (get-in context [:result :errors]))))

(defn halted?
  "Checks if pipeline execution should be halted.
   
    Args:
      context: Interceptor context map
    
    Returns:
      Boolean indicating halt flag status"
  [context]
  (:halt? context))

(defn get-response
  "Extracts response from context."
  [context]
  (:response context))

;; Context update utilities

(defn update-timing
  "Updates timing information in context."
  [context timing-key value]
  (assoc-in context [:timing timing-key] value))

(defn record-start-time
  "Records operation start time in nanoseconds."
  [context]
  (update-timing context :start (System/nanoTime)))

(defn record-end-time
  "Records operation end time and calculates duration."
  [context]
  (let [end-time (System/nanoTime)
        start-time (get-timing context :start)
        duration-ms (when start-time
                      (/ (- end-time start-time) 1e6))]
    (-> context
        (update-timing :end end-time)
        (cond-> duration-ms (update-timing :duration-ms duration-ms)))))

(defn add-effect-error
  "Adds an effect execution error to the context."
  [context effect error]
  (update context :effect-errors (fnil conj [])
          {:effect effect
           :error (if (instance? Throwable error)
                    (ex-message error)
                    (str error))
           :error-type (type error)}))

(defn merge-context
  "Safely merges additional data into context."
  [context additional-data]
  (merge context additional-data))

;; Default error mappings for exception→HTTP status resolution.
;; Inlined from problem-details to avoid core→platform circular dependency.

(def default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles."
  {:validation-error          [400 "Validation Error"]
   :invalid-request           [400 "Invalid Request"]
   :unauthorized              [401 "Unauthorized"]
   :auth-failed               [401 "Authentication Failed"]
   :forbidden                 [403 "Forbidden"]
   :not-found                 [404 "Not Found"]
   :user-not-found            [404 "User Not Found"]
   :resource-not-found        [404 "Resource Not Found"]
   :conflict                  [409 "Conflict"]
   :user-exists               [409 "User Already Exists"]
   :resource-exists           [409 "Resource Already Exists"]
   :business-rule-violation   [400 "Business Rule Violation"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; Pre-built context creators for common scenarios

(defn get-interface-type
  "Gets the interface type from context (:http, :cli, :service)."
  [context]
  (:interface-type context))

(defn get-service
  "Gets the service/user-service from system dependencies."
  [context]
  (get-in context [:system :user-service]))

(defn add-breadcrumb
  "Adds a breadcrumb for observability tracking."
  [context category action details]
  (let [breadcrumb {:category category
                    :action action
                    :details details
                    :timestamp (:now context)}]
    (update context :breadcrumbs (fnil conj []) breadcrumb)))

(defn add-validation-error
  "Adds a validation error to the context."
  [context error-key error-details]
  (update context :errors (fnil conj []) {:type :validation
                                          :key error-key
                                          :details error-details}))

(defn fail-with-exception
  "Marks context as failed and associates an exception."
  [context exception]
  (-> context
      (assoc :halt? true)
      (assoc :exception exception)))

(defn create-http-context
  "Creates context for HTTP request processing."
  [op system request]
  (assoc (create-initial-context op system
                                 :request request
                                 :correlation-id (get-in request [:headers "x-correlation-id"]))
         :interface-type :http))

(defn create-cli-context
  "Creates context for CLI command processing."
  [op system args options]
  (assoc (create-initial-context op system
                                 :request {:args args :options options})
         :interface-type :cli
         :error-mappings default-error-mappings))

(defn create-service-context
  "Creates context for internal service calls."
  [op system data & {:keys [correlation-id]}]
  (assoc (create-initial-context op system
                                 :request {:body data}
                                 :correlation-id correlation-id)
         :interface-type :service))

;; Debugging and inspection utilities

(defn context-summary
  "Creates a summary of context for debugging purposes."
  [context]
  {:op (:op context)
   :correlation-id (:correlation-id context)
   :has-request? (boolean (:request context))
   :has-result? (has-result? context)
   :success? (when (has-result? context) (success? context))
   :has-errors? (has-errors? context)
   :halted? (halted? context)
   :has-response? (boolean (:response context))
   :timing-keys (keys (:timing context))
   :effect-error-count (count (:effect-errors context))
   :system-dependencies (keys (:system context))})

(defn sanitize-context-for-logging
  "Removes sensitive data from context for safe logging."
  [context]
  (-> context
      (update :system select-keys [:logger :metrics :error-reporter])
      (update :request #(when % (select-keys % [:headers :params :query])))
      (dissoc :exception))) ; Don't log exception objects directly
