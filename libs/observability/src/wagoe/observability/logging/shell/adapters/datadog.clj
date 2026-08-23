(ns wagoe.observability.logging.shell.adapters.datadog
  "Datadog logging adapter implementation.
   
   This adapter integrates with Datadog (https://datadoghq.com) for production logging,
   audit tracking, and observability. It implements all logging protocols to provide
   comprehensive structured logging capabilities that integrate with Datadog's platform.
   
   Features:
   - Structured JSON logging to Datadog HTTP API
   - Log levels with proper Datadog level mapping
   - Context management (correlation IDs, request metadata)
   - Audit event logging for compliance
   - Security event tracking
   - Batch processing for performance
   - Runtime configuration management
   
   Configuration:
   The adapter requires a Datadog API key and service name, optionally accepting:
   - Source identifier (application/component name)
   - Hostname override
   - Custom tags
   - Custom endpoint (for EU region, etc.)
   - Batch size and flush interval settings
   
   Example:
   (def datadog-logger
     (create-datadog-logger
       {:api-key \"your-32-char-datadog-api-key\"
        :service \"my-service\"
        :source \"my-app\"
        :environment \"production\"
        :tags [\"team:backend\" \"version:1.0.0\"]
        :batch-size 50
        :flush-interval 5000}))"
  (:require
   [wagoe.observability.logging.shell.time :as time]
   [wagoe.observability.logging.ports :as ports]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import
   [java.util.concurrent LinkedBlockingQueue Executors TimeUnit]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
   [java.net URI]
   [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Thread-local context management
;; =============================================================================

(def ^:private thread-local-context
  "Thread-local storage for logging context that persists across log calls
   within the same request/thread scope."
  (ThreadLocal.))

(defn- get-thread-context
  "Get the current thread's logging context."
  []
  (.get thread-local-context))

(defn- set-thread-context!
  "Set the current thread's logging context."
  [context]
  (.set thread-local-context context))

(defn- merge-thread-context!
  "Merge additional context with the current thread context."
  [additional-context]
  (let [current (get-thread-context)
        merged (merge current additional-context)]
    (set-thread-context! merged)
    merged))

;; =============================================================================
;; Datadog API integration
;; =============================================================================

(def ^:private datadog-log-levels
  "Mapping from our log levels to Datadog log levels."
  {:trace "debug"
   :debug "debug"
   :info "info"
   :warn "warn"
   :error "error"
   :fatal "error"})

(def ^:private default-datadog-endpoint
  "Default Datadog logs endpoint for US region."
  "https://http-intake.logs.datadoghq.com/v1/input/")

(defn- prepare-log-entry
  "Prepare a log entry in Datadog format."
  [level message context exception config]
  (let [base-entry {:timestamp (time/now-iso)
                    :level (get datadog-log-levels level "info")
                    :message (str message)
                    :service (:service config)
                    :source (or (:source config) (:service config))
                    :hostname (or (:hostname config) (.getHostName (java.net.InetAddress/getLocalHost)))}
        with-context (if context
                       (merge base-entry context)
                       base-entry)
        with-tags (if-let [tags (:tags config)]
                    (assoc with-context :ddtags (str/join "," tags))
                    with-context)
        with-exception (if exception
                         (assoc with-tags
                                :error.kind (str (class exception))
                                :error.message (.getMessage exception)
                                :error.stack (str/join "\n"
                                                       (map str (.getStackTrace exception))))
                         with-tags)]
    with-exception))

(defn- send-log-batch
  "Send a batch of log entries to Datadog."
  [api-key endpoint log-entries]
  (try
    (let [payload (json/generate-string log-entries)
          url (str endpoint api-key)
          client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString payload StandardCharsets/UTF_8))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (when (>= (.statusCode response) 400)
        (log/warn "Datadog logging failed" {:status (.statusCode response)
                                            :body (.body response)})))
    (catch Exception e
      (log/error e "Failed to send logs to Datadog"))))

;; =============================================================================
;; Batch processing
;; =============================================================================

(defn- create-batch-processor
  "Create a batch processor for log entries."
  [api-key config]
  (let [queue (LinkedBlockingQueue.)
        batch-size (or (:batch-size config) 100)
        flush-interval (or (:flush-interval config) 5000)
        endpoint (or (:endpoint config) default-datadog-endpoint)
        executor (Executors/newSingleThreadScheduledExecutor)

        flush-batch! (fn []
                       (let [batch (transient [])]
                         (loop [entry (.poll queue)
                                batch batch
                                count 0]
                           (if (and entry (< count batch-size))
                             (recur (.poll queue) (conj! batch entry) (inc count))
                             (let [final-batch (persistent! batch)]
                               (when (seq final-batch)
                                 (send-log-batch api-key endpoint final-batch)))))))

        submit-entry! (fn [entry]
                        (.offer queue entry)
                        (when (>= (.size queue) batch-size)
                          (flush-batch!)))

        shutdown! (fn []
                    (flush-batch!)
                    (.shutdown executor))]

    ;; Schedule periodic flushes
    (.scheduleAtFixedRate executor flush-batch! flush-interval flush-interval TimeUnit/MILLISECONDS)

    {:submit-entry! submit-entry!
     :flush-batch! flush-batch!
     :shutdown! shutdown!}))

;; =============================================================================
;; Logger Implementation
;; =============================================================================

(defrecord DatadogLogger [config batch-processor]
  ports/ILogger
  (log* [_ level message context exception]
    (try
      (let [thread-context (get-thread-context)
            merged-context (merge thread-context context)
            log-entry (prepare-log-entry level message merged-context exception config)]
        ((:submit-entry! batch-processor) log-entry))
      (catch Exception e
        (log/error e "Error in Datadog logger"))))

  (trace [this message]
    (ports/log* this :trace message nil nil))
  (trace [this message context]
    (ports/log* this :trace message context nil))

  (debug [this message]
    (ports/log* this :debug message nil nil))
  (debug [this message context]
    (ports/log* this :debug message context nil))

  (info [this message]
    (ports/log* this :info message nil nil))
  (info [this message context]
    (ports/log* this :info message context nil))

  (warn [this message]
    (ports/log* this :warn message nil nil))
  (warn [this message context]
    (ports/log* this :warn message context nil))
  (warn [this message context exception]
    (ports/log* this :warn message context exception))

  (error [this message]
    (ports/log* this :error message nil nil))
  (error [this message context]
    (ports/log* this :error message context nil))
  (error [this message context exception]
    (ports/log* this :error message context exception))

  (fatal [this message]
    (ports/log* this :fatal message nil nil))
  (fatal [this message context]
    (ports/log* this :fatal message context nil))
  (fatal [this message context exception]
    (ports/log* this :fatal message context exception)))

;; =============================================================================
;; Audit Logger Implementation
;; =============================================================================

(defrecord DatadogAuditLogger [logger]
  ports/IAuditLogger
  (audit-event [_ event-type actor resource action result context]
    (let [audit-context (merge context
                               {:audit-event-type (name event-type)
                                :audit-actor (str actor)
                                :audit-resource (str resource)
                                :audit-action (name action)
                                :audit-result (name result)
                                :event-category "audit"})]
      (ports/info logger
                  (format "Audit: %s %s %s -> %s" actor action resource result)
                  audit-context)))

  (security-event [_ event-type severity details context]
    (let [security-context (merge context details
                                  {:security-event-type (name event-type)
                                   :security-severity (name severity)
                                   :event-category "security"})]
      (ports/warn logger
                  (format "Security: %s (%s)" event-type severity)
                  security-context))))

;; =============================================================================
;; Logging Context Implementation
;; =============================================================================

(defrecord DatadogLoggingContext []
  ports/ILoggingContext
  (with-context [_ context-map f]
    (let [previous-context (get-thread-context)]
      (try
        (merge-thread-context! context-map)
        (f)
        (finally
          (set-thread-context! previous-context)))))

  (current-context [_this]
    (get-thread-context)))

;; =============================================================================
;; Logging Config Implementation
;; =============================================================================

(defrecord DatadogLoggingConfig [config-atom]
  ports/ILoggingConfig
  (set-level! [_ level]
    (let [old-config @config-atom
          new-config (assoc old-config :level level)]
      (reset! config-atom new-config)
      (:level old-config)))

  (get-level [_this]
    (:level @config-atom))

  (set-config! [_ config-map]
    (let [old-config @config-atom]
      (reset! config-atom (merge old-config config-map))
      old-config))

  (get-config [_this]
    @config-atom))

;; =============================================================================
;; Component Creation Functions
;; =============================================================================

(defn create-datadog-logger
  "Create a Datadog logger instance."
  [config]
  (when-not (:api-key config)
    (throw (ex-info "Datadog API key is required" {:type :configuration-error :config config})))
  (when-not (:service config)
    (throw (ex-info "Datadog service name is required" {:type :configuration-error :config config})))
  (let [batch-processor (create-batch-processor (:api-key config) config)]
    (->DatadogLogger config batch-processor)))

(defn create-datadog-audit-logger
  "Create a Datadog audit logger instance."
  [config]
  (let [logger (create-datadog-logger config)]
    (->DatadogAuditLogger logger)))

(defn create-datadog-logging-context
  "Create a Datadog logging context instance."
  [_config]
  (->DatadogLoggingContext))

(defn create-datadog-logging-config
  "Create a Datadog logging config instance."
  [config]
  (let [config-atom (atom (merge {:level :info} config))]
    (->DatadogLoggingConfig config-atom)))

;; =============================================================================
;; Combined Component Implementation
;; =============================================================================

(defrecord DatadogLoggingComponent [config logger audit-logger logging-context logging-config batch-processor]
  ports/ILogger
  (log* [_ level message context exception]
    (ports/log* logger level message context exception))
  (trace [_ message]
    (ports/trace logger message))
  (trace [_ message context]
    (ports/trace logger message context))
  (debug [_ message]
    (ports/debug logger message))
  (debug [_ message context]
    (ports/debug logger message context))
  (info [_ message]
    (ports/info logger message))
  (info [_ message context]
    (ports/info logger message context))
  (warn [_ message]
    (ports/warn logger message))
  (warn [_ message context]
    (ports/warn logger message context))
  (warn [_ message context exception]
    (ports/warn logger message context exception))
  (error [_ message]
    (ports/error logger message))
  (error [_ message context]
    (ports/error logger message context))
  (error [_ message context exception]
    (ports/error logger message context exception))
  (fatal [_ message]
    (ports/fatal logger message))
  (fatal [_ message context]
    (ports/fatal logger message context))
  (fatal [_ message context exception]
    (ports/fatal logger message context exception))

  ports/IAuditLogger
  (audit-event [_ event-type actor resource action result context]
    (ports/audit-event audit-logger event-type actor resource action result context))
  (security-event [_ event-type severity details context]
    (ports/security-event audit-logger event-type severity details context))

  ports/ILoggingContext
  (with-context [_ context-map f]
    (ports/with-context logging-context context-map f))
  (current-context [_this]
    (ports/current-context logging-context))

  ports/ILoggingConfig
  (set-level! [_ level]
    (ports/set-level! logging-config level))
  (get-level [_this]
    (ports/get-level logging-config))
  (set-config! [_ config-map]
    (ports/set-config! logging-config config-map))
  (get-config [_this]
    (ports/get-config logging-config))

  java.io.Closeable
  (close [_this]
    ((:shutdown! batch-processor))))

(defn create-datadog-logging-component
  "Create a complete Datadog logging component that implements all protocols.
   
   This is the recommended way to create Datadog logging for use with Integrant."
  [config]
  (when-not (:api-key config)
    (throw (ex-info "Datadog API key is required" {:type :configuration-error :config config})))
  (when-not (:service config)
    (throw (ex-info "Datadog service name is required" {:type :configuration-error :config config})))
  (let [batch-processor (create-batch-processor (:api-key config) config)
        logger (->DatadogLogger config batch-processor)
        audit-logger (->DatadogAuditLogger logger)
        logging-context (->DatadogLoggingContext)
        logging-config (create-datadog-logging-config config)]
    (->DatadogLoggingComponent config logger audit-logger logging-context logging-config batch-processor)))

(defn create-datadog-logging-components
  "Create a map of Datadog logging components for Integrant system configuration.
   
   Returns a map with keys:
   - :logger - ILogger implementation
   - :audit-logger - IAuditLogger implementation
   - :logging-context - ILoggingContext implementation
   - :logging-config - ILoggingConfig implementation"
  [config]
  (let [batch-processor (create-batch-processor (:api-key config) config)]
    {:logger (->DatadogLogger config batch-processor)
     :audit-logger (->DatadogAuditLogger (->DatadogLogger config batch-processor))
     :logging-context (->DatadogLoggingContext)
     :logging-config (create-datadog-logging-config config)}))
