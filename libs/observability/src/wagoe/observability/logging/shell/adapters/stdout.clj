(ns wagoe.observability.logging.shell.adapters.stdout
  "Stdout logging adapter that writes formatted logs to standard output.
   
   This adapter implements all logging protocols and provides configurable
   formatting options including plain text and JSON output with optional
   colors, timestamps, and structured context."
  (:require
   [wagoe.observability.logging.ports :as ports]
   [clojure.string :as str]
   [cheshire.core :as json])
  (:import
   [java.time Instant]
   [java.time.format DateTimeFormatter]))

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

(def ^:private level-names
  "Display names for log levels."
  {:trace "TRACE"
   :debug "DEBUG"
   :info "INFO"
   :warn "WARN"
   :error "ERROR"
   :fatal "FATAL"})

(def ^:private level-colors
  "ANSI color codes for log levels."
  {:trace "\033[36m" ; cyan
   :debug "\033[34m" ; blue
   :info "\033[32m" ; green
   :warn "\033[33m" ; yellow
   :error "\033[31m" ; red
   :fatal "\033[35m" ; magenta
   :reset "\033[0m"})

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
;; Timestamp Formatting
;; =============================================================================

(def ^:private iso-formatter
  "ISO 8601 timestamp formatter."
  DateTimeFormatter/ISO_INSTANT)

(defn- current-timestamp
  "Get current timestamp as ISO 8601 string.
   
   Returns:
     ISO 8601 formatted timestamp string
   
   Pure: No (depends on system time)"
  []
  (.format iso-formatter (Instant/now)))

;; =============================================================================
;; Context Formatting
;; =============================================================================

(defn- format-exception
  "Format exception information for logging.
   
   Args:
     exception - Throwable/Exception instance
     max-elements - Maximum stack trace elements to include
   
   Returns:
     Map with exception details
   
   Pure: Yes"
  [exception max-elements]
  (when exception
    {:exception-type (-> exception class .getSimpleName)
     :exception-message (.getMessage exception)
     :stack-trace (vec (take max-elements
                             (map str (.getStackTrace exception))))}))

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
;; Text Formatting
;; =============================================================================

(defn- format-context-text
  "Format context map as key=value pairs.
   
   Args:
     context - Context map
   
   Returns:
     String of space-separated key=value pairs
   
   Pure: Yes"
  [context]
  (when (seq context)
    (str/join " "
              (map (fn [[k v]]
                     (str (name k) "=" (pr-str v)))
                   context))))

(defn- format-text-log
  "Format a log entry as plain text.
   
   Args:
     level - Log level keyword
     message - Log message string
     context - Context map
     config - Adapter configuration
   
   Returns:
     Formatted text string
   
   Pure: Yes (given inputs)"
  [level message context config]
  (let [parts (cond-> []
                (:include-timestamp config)
                (conj (str "[" (current-timestamp) "]"))

                (:include-level config)
                (conj (if (:colors config)
                        (str (get level-colors level)
                             (get level-names level)
                             (get level-colors :reset))
                        (get level-names level)))

                (:include-thread config)
                (conj (str "[" (.getName (Thread/currentThread)) "]"))

                true
                (conj message))
        context-str (format-context-text context)]
    (str (str/join " " parts)
         (when (seq context-str)
           (str " | " context-str)))))

;; =============================================================================
;; JSON Formatting
;; =============================================================================

(defn- format-json-log
  "Format a log entry as JSON.
   
   Args:
     level - Log level keyword
     message - Log message string
     context - Context map
     config - Adapter configuration
   
   Returns:
     JSON string
   
   Pure: Yes (given inputs)"
  [level message context config]
  (let [log-entry (cond-> {:level (name level)
                           :message message
                           :timestamp (current-timestamp)}

                    (:include-thread config)
                    (assoc :thread (.getName (Thread/currentThread)))

                    (seq context)
                    (assoc :context context))]
    (json/generate-string log-entry)))

;; =============================================================================
;; Core Logging Logic
;; =============================================================================

(defn- write-log
  "Write a log entry to stdout.
   
   Args:
     level - Log level keyword
     message - Log message string
     context - Context map
     exception - Optional exception
     config - Adapter configuration map
     context-atom - Atom holding dynamic context
   
   Returns:
     nil (side effect only)
   
   Pure: No (writes to stdout)"
  [level message context exception config context-atom]
  (let [min-level @(:level-atom config)
        enabled? (level-enabled? level min-level)]
    (when enabled?
      (let [base-context (or (:default-tags config) {})
            dynamic-context @context-atom
            exception-context (when exception
                                (format-exception exception
                                                  (or (:max-stacktrace-elements config) 50)))
            full-context (merge-contexts base-context
                                         dynamic-context
                                         (merge (or context {})
                                                (or exception-context {})))
            sanitized-context (sanitize-context full-context)
            formatted (if (= (:format config :text) :json)
                        (format-json-log level message sanitized-context config)
                        (format-text-log level message sanitized-context config))]
        (println formatted)
        (flush)))))

;; =============================================================================
;; Stdout Logger Implementation
;; =============================================================================

(defrecord StdoutLogger [config context-atom]
  ports/ILogger
  (log* [_ level message context exception]
    (write-log level message context exception config context-atom)
    nil)

  (trace [_ message]
    (write-log :trace message nil nil config context-atom)
    nil)
  (trace [_ message context]
    (write-log :trace message context nil config context-atom)
    nil)

  (debug [_ message]
    (write-log :debug message nil nil config context-atom)
    nil)
  (debug [_ message context]
    (write-log :debug message context nil config context-atom)
    nil)

  (info [_ message]
    (write-log :info message nil nil config context-atom)
    nil)
  (info [_ message context]
    (write-log :info message context nil config context-atom)
    nil)

  (warn [_ message]
    (write-log :warn message nil nil config context-atom)
    nil)
  (warn [_ message context]
    (write-log :warn message context nil config context-atom)
    nil)
  (warn [_ message context exception]
    (write-log :warn message context exception config context-atom)
    nil)

  (error [_ message]
    (write-log :error message nil nil config context-atom)
    nil)
  (error [_ message context]
    (write-log :error message context nil config context-atom)
    nil)
  (error [_ message context exception]
    (write-log :error message context exception config context-atom)
    nil)

  (fatal [_ message]
    (write-log :fatal message nil nil config context-atom)
    nil)
  (fatal [_ message context]
    (write-log :fatal message context nil config context-atom)
    nil)
  (fatal [_ message context exception]
    (write-log :fatal message context exception config context-atom)
    nil)

  ports/IAuditLogger
  (audit-event [_ event-type actor resource action result context]
    (let [audit-context (assoc (or context {})
                               :event-type (name event-type)
                               :actor actor
                               :resource resource
                               :action (name action)
                               :result (name result)
                               :audit true)]
      (write-log :info "Audit event" audit-context nil config context-atom))
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
                  :info)]
      (write-log level "Security event" security-context nil config context-atom))
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

(defn- normalize-config
  "Normalize configuration, handling :json flag vs :format key.
   
   Args:
     config - Raw configuration map
   
   Returns:
     Normalized configuration map
   
   Pure: Yes"
  [config]
  (let [format (cond
                 (:json config) :json
                 (:format config) (:format config)
                 :else :text)]
    (-> config
        (assoc :format format)
        (dissoc :json))))

(defn create-stdout-logger
  "Creates a stdout logger instance.
   
   Args:
     config - Configuration map with keys:
              :provider - Should be :stdout
              :level - Minimum log level (default :info)
              :format - :text or :json (default :text)
              :colors - Enable ANSI colors (default true for :text)
              :include-timestamp - Include timestamps (default true)
              :include-level - Include level in output (default true)
              :include-thread - Include thread name (default false)
              :default-tags - Map of default context tags
              :max-stacktrace-elements - Max stack trace lines (default 50)
   
   Returns:
     Logger implementing ILogger, IAuditLogger, ILoggingContext, ILoggingConfig
   
   Pure: No (creates mutable state atoms)"
  [config]
  (let [normalized-config (normalize-config config)
        level-atom (atom (or (:level normalized-config) :info))
        config-atom (atom normalized-config)
        context-atom (atom {})
        logger-config (assoc normalized-config
                             :level-atom level-atom
                             :config-atom config-atom)]
    (->StdoutLogger logger-config context-atom)))

(defn create-logging-component
  "Creates a stdout logging component for system wiring.
   
   This is the main entry point for Integrant system initialization.
   
   Args:
     config - Configuration map (see create-stdout-logger)
   
   Returns:
     Logger component implementing all logging protocols
   
   Pure: No (creates mutable state)"
  [config]
  (create-stdout-logger config))
