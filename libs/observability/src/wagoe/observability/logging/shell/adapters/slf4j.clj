(ns wagoe.observability.logging.shell.adapters.slf4j
  "SLF4J logging adapter that delegates to SLF4J backend.
   
   This adapter implements all logging protocols and uses SLF4J as the
   underlying logging facade. It supports:
   - All log levels (trace, debug, info, warn, error, fatal)
   - Structured context via MDC (Mapped Diagnostic Context)
   - Audit and security event logging
   - Dynamic context management
   - Runtime level configuration
   
   The adapter leverages SLF4J's backend (e.g., Logback, Log4j2) for
   actual log routing, formatting, and output, eliminating duplication
   with clojure.tools.logging infrastructure."
  (:require
   [wagoe.observability.logging.ports :as ports])
  (:import
   [org.slf4j LoggerFactory MDC]))

;; =============================================================================
;; Level Management
;; =============================================================================

(def ^:private level-hierarchy
  "Numeric ordering of log levels for filtering."
  {:trace 0
   :debug 1
   :info 2
   :warn 3
   :error 4
   :fatal 5})

(defn- level-enabled?
  "Check if a log level should be emitted given current minimum level.
   
   Args:
     level - Keyword log level to check
     min-level - Current minimum level threshold
   
   Returns:
     Boolean indicating if level should be logged
   
   Pure: Yes"
  [level min-level]
  (>= (get level-hierarchy level 0)
      (get level-hierarchy min-level 0)))

;; =============================================================================
;; MDC Context Management
;; =============================================================================

(def ^:private mdc-key-mapping
  "Mapping from Boundary context keys to MDC keys."
  {:correlation-id "correlationId"
   :request-id "requestId"
   :tenant-id "tenantId"
   :user-id "userId"
   :trace-id "traceId"
   :span-id "spanId"})

(defn- populate-mdc!
  "Populate SLF4J MDC with context values.
   
   Args:
     context - Context map with Boundary keys
   
   Returns:
     nil (side effect only)
   
   Pure: No (mutates MDC thread-local state)"
  [context]
  (doseq [[boundary-key mdc-key] mdc-key-mapping]
    (when-let [value (get context boundary-key)]
      (MDC/put mdc-key (str value))))

  ;; Handle tags map
  (when-let [tags (:tags context)]
    (doseq [[k v] tags]
      (MDC/put (str "tag." (name k)) (str v))))

  ;; Add audit/security flags
  (when (:audit context)
    (MDC/put "audit" "true"))
  (when (:security context)
    (MDC/put "security" "true")))

(defn- clear-mdc!
  "Clear all MDC values to prevent context leakage.
   
   Returns:
     nil (side effect only)
   
   Pure: No (mutates MDC thread-local state)"
  []
  (MDC/clear))

(defn- with-mdc
  "Execute function with MDC context populated.
   
   Args:
     context - Context map
     f - Zero-argument function to execute
   
   Returns:
     Result of calling (f)
   
   Pure: No (manages MDC state)"
  [context f]
  (if (seq context)
    (try
      (populate-mdc! context)
      (f)
      (finally
        (clear-mdc!)))
    (f)))

;; =============================================================================
;; Context Sanitization
;; =============================================================================

(defn- sanitize-context
  "Remove or redact sensitive information from context.
   
   Args:
     context - Context map
   
   Returns:
     Sanitized context map
   
   Pure: Yes"
  [context]
  (let [sensitive-keys #{:password :token :secret :api-key :private-key}]
    (reduce-kv (fn [m k v]
                 (if (sensitive-keys k)
                   (assoc m k "***REDACTED***")
                   (assoc m k v)))
               {}
               context)))

(defn- merge-contexts
  "Merge base context, dynamic context, and call context.
   
   Args:
     base-context - Base context from configuration
     dynamic-context - Thread-local dynamic context
     call-context - Context passed in log call
   
   Returns:
     Merged context map
   
   Pure: Yes"
  [base-context dynamic-context call-context]
  (merge base-context dynamic-context call-context))

;; =============================================================================
;; Core Logging Logic
;; =============================================================================

(defn- log-with-slf4j
  "Delegate logging to SLF4J logger.
   
   Args:
     slf4j-logger - SLF4J Logger instance
     level - Log level keyword
     message - Log message string
     exception - Optional exception/throwable
   
   Returns:
     nil (side effect only)
   
   Pure: No (writes to SLF4J backend)"
  [slf4j-logger level message exception]
  (case level
    :trace (if exception
             (.trace slf4j-logger message exception)
             (.trace slf4j-logger message))
    :debug (if exception
             (.debug slf4j-logger message exception)
             (.debug slf4j-logger message))
    :info (if exception
            (.info slf4j-logger message exception)
            (.info slf4j-logger message))
    :warn (if exception
            (.warn slf4j-logger message exception)
            (.warn slf4j-logger message))
    :error (if exception
             (.error slf4j-logger message exception)
             (.error slf4j-logger message))
    :fatal (if exception
             (.error slf4j-logger (str "[FATAL] " message) exception)
             (.error slf4j-logger (str "[FATAL] " message)))))

(defn- write-log
  "Write a log entry via SLF4J with MDC context.
   
   Args:
     slf4j-logger - SLF4J Logger instance
     level - Log level keyword
     message - Log message string
     context - Context map
     exception - Optional exception
     config - Adapter configuration map
     context-atom - Atom holding dynamic context
   
   Returns:
     nil (side effect only)
   
   Pure: No (writes to SLF4J)"
  [slf4j-logger level message context exception config context-atom]
  (let [min-level @(:level-atom config)
        enabled? (level-enabled? level min-level)]
    (when enabled?
      (let [base-context (or (:default-tags config) {})
            dynamic-context @context-atom
            full-context (merge-contexts base-context
                                         dynamic-context
                                         (or context {}))
            sanitized-context (sanitize-context full-context)]
        (with-mdc sanitized-context
          (fn []
            (log-with-slf4j slf4j-logger level message exception)))))))

;; =============================================================================
;; SLF4J Logger Implementation
;; =============================================================================

(defrecord Slf4jLogger [slf4j-logger config context-atom]
  ports/ILogger
  (log* [_ level message context exception]
    (write-log slf4j-logger level message context exception config context-atom)
    nil)

  (trace [_ message]
    (write-log slf4j-logger :trace message nil nil config context-atom)
    nil)
  (trace [_ message context]
    (write-log slf4j-logger :trace message context nil config context-atom)
    nil)

  (debug [_ message]
    (write-log slf4j-logger :debug message nil nil config context-atom)
    nil)
  (debug [_ message context]
    (write-log slf4j-logger :debug message context nil config context-atom)
    nil)

  (info [_ message]
    (write-log slf4j-logger :info message nil nil config context-atom)
    nil)
  (info [_ message context]
    (write-log slf4j-logger :info message context nil config context-atom)
    nil)

  (warn [_ message]
    (write-log slf4j-logger :warn message nil nil config context-atom)
    nil)
  (warn [_ message context]
    (write-log slf4j-logger :warn message context nil config context-atom)
    nil)
  (warn [_ message context exception]
    (write-log slf4j-logger :warn message context exception config context-atom)
    nil)

  (error [_ message]
    (write-log slf4j-logger :error message nil nil config context-atom)
    nil)
  (error [_ message context]
    (write-log slf4j-logger :error message context nil config context-atom)
    nil)
  (error [_ message context exception]
    (write-log slf4j-logger :error message context exception config context-atom)
    nil)

  (fatal [_ message]
    (write-log slf4j-logger :fatal message nil nil config context-atom)
    nil)
  (fatal [_ message context]
    (write-log slf4j-logger :fatal message context nil config context-atom)
    nil)
  (fatal [_ message context exception]
    (write-log slf4j-logger :fatal message context exception config context-atom)
    nil)

  ports/IAuditLogger
  (audit-event [_ event-type actor resource action result context]
    (let [audit-context (assoc (or context {})
                               :event-type (name event-type)
                               :actor actor
                               :resource resource
                               :action (name action)
                               :result (name result)
                               :audit true)
          audit-message (format "Audit: %s performed %s on %s -> %s"
                                actor
                                (name action)
                                resource
                                (name result))]
      (write-log slf4j-logger :info audit-message audit-context nil config context-atom))
    nil)

  (security-event [_ event-type severity details context]
    (let [security-context (assoc (or context {})
                                  :event-type (name event-type)
                                  :severity (name severity)
                                  :details details
                                  :security true)
          level (case severity
                  :critical :fatal
                  :high :error
                  :medium :warn
                  :low :info
                  :info)
          security-message (format "Security [%s]: %s"
                                   (name severity)
                                   (name event-type))]
      (write-log slf4j-logger level security-message security-context nil config context-atom))
    nil)

  ports/ILoggingContext
  (with-context [_ context-map f]
    (let [old-context @context-atom]
      (try
        (swap! context-atom merge context-map)
        (f)
        (finally
          (reset! context-atom old-context)))))

  (current-context [_this]
    @context-atom)

  ports/ILoggingConfig
  (set-level! [_ level]
    (let [old-level @(:level-atom config)]
      (reset! (:level-atom config) level)
      old-level))

  (get-level [_this]
    @(:level-atom config))

  (set-config! [_ config-map]
    (let [old-config @(:config-atom config)]
      (swap! (:config-atom config) merge config-map)
      old-config))

  (get-config [_this]
    @(:config-atom config)))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-slf4j-logger
  "Creates an SLF4J logger instance.
   
   Args:
     config - Configuration map with keys:
              :provider - Should be :slf4j
              :level - Minimum log level (default :info)
              :logger-name - SLF4J logger name (default \"boundary\")
              :default-tags - Map of default context tags
   
   Returns:
     Logger implementing ILogger, IAuditLogger, ILoggingContext, ILoggingConfig
   
   Pure: No (creates mutable state atoms and gets SLF4J logger)"
  [config]
  (let [logger-name (or (:logger-name config) "boundary")
        slf4j-logger (LoggerFactory/getLogger logger-name)
        level-atom (atom (or (:level config) :info))
        config-atom (atom config)
        context-atom (atom {})
        logger-config (assoc config
                             :level-atom level-atom
                             :config-atom config-atom)]
    (->Slf4jLogger slf4j-logger logger-config context-atom)))

(defn create-logging-component
  "Creates an SLF4J logging component for system wiring.
   
   This is the main entry point for Integrant system initialization.
   
   Args:
     config - Configuration map (see create-slf4j-logger)
   
   Returns:
     Logger component implementing all logging protocols
   
   Pure: No (creates mutable state)"
  [config]
  (create-slf4j-logger config))
