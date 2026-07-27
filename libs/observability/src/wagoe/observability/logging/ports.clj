(ns wagoe.observability.logging.ports
  "Port definitions for logging infrastructure.
   
   This namespace defines the protocols that logging adapters must implement,
   providing a clean abstraction over different logging backends (stdout, JSON,
   structured logging systems, etc.).
   
   Core protocols:
   - ILogger: Basic application logging with levels and context
   - IAuditLogger: Structured audit logging for compliance and monitoring
   
   Context Map Structure:
   {:correlation-id string - Request correlation ID
    :request-id    string - HTTP request ID  
    :tenant-id     string - Multi-tenant context
    :user-id       string - User context (if authenticated)
    :span-id       string - Distributed tracing span ID
    :trace-id      string - Distributed tracing trace ID
    :tags          map    - Additional structured tags}")

;; =============================================================================
;; Core Logging Protocol
;; =============================================================================

(defprotocol ILogger
  "Core logging interface for application events.
   
   All log methods accept:
   - message: String message or format string
   - context: Optional map with correlation-id, request-id, tenant-id, etc.
   - exception: Optional exception/throwable for error logs"

  (log* [this level message context exception]
    "Low-level logging method. All other methods delegate to this.
     
     Parameters:
       level     - Keyword log level (:trace :debug :info :warn :error :fatal)
       message   - String message or format string
       context   - Map with structured context (correlation-id, tags, etc.)
       exception - Optional throwable/exception
     
     Returns:
       nil (side effect only)")

  (trace [this message] [this message context]
    "Log at TRACE level for very detailed debugging")

  (debug [this message] [this message context]
    "Log at DEBUG level for development debugging")

  (info [this message] [this message context]
    "Log at INFO level for normal application flow")

  (warn [this message] [this message context] [this message context exception]
    "Log at WARN level for recoverable issues")

  (error [this message] [this message context] [this message context exception]
    "Log at ERROR level for application errors")

  (fatal [this message] [this message context] [this message context exception]
    "Log at FATAL level for critical system failures"))

;; =============================================================================
;; Audit Logging Protocol
;; =============================================================================

(defprotocol IAuditLogger
  "Specialized logging for audit events and compliance.
   
   Audit logs are structured events that track:
   - User actions (create, update, delete)
   - System state changes
   - Security events
   - Compliance-relevant activities"

  (audit-event [this event-type actor resource action result context]
    "Log a structured audit event.
     
     Parameters:
       event-type - Keyword event type (:user-action :system-event :security-event)
       actor      - Who performed the action (user-id, system, api-key, etc.)
       resource   - What was acted upon (user, invoice, etc.)
       action     - What action was taken (create, update, delete, view)
       result     - Outcome (success, failure, partial)
       context    - Additional structured context
     
     Returns:
       nil (side effect only)")

  (security-event [this event-type severity details context]
    "Log security-related events.
     
     Parameters:
       event-type - Keyword (:login-attempt :permission-denied :suspicious-activity)
       severity   - Keyword (:low :medium :high :critical)
       details    - Map with event-specific details
       context    - Standard context map
     
     Returns:
       nil (side effect only)"))

;; =============================================================================
;; Logging Context Management
;; =============================================================================

(defprotocol ILoggingContext
  "Protocol for managing logging context across request lifecycle.
   
   This allows for correlation IDs, request context, and other metadata
   to be automatically included in all log messages within a request scope."

  (with-context [this context-map f]
    "Execute function f with additional logging context.
     
     The context-map will be merged with any existing context and made
     available to all logging calls within the execution of f.
     
     Parameters:
       context-map - Map of context keys/values to add
       f          - Zero-argument function to execute
     
     Returns:
       Result of calling (f)")

  (current-context [this]
    "Get the current logging context map.
     
     Returns:
       Map of current context keys/values"))

;; =============================================================================
;; Logging Configuration Protocol
;; =============================================================================

(defprotocol ILoggingConfig
  "Protocol for runtime logging configuration changes.
   
   This allows for dynamic adjustment of logging behavior without
   requiring application restart."

  (set-level! [this level]
    "Change the minimum logging level.
     
     Parameters:
       level - Keyword log level (:trace :debug :info :warn :error :fatal)
     
     Returns:
       Previous level")

  (get-level [this]
    "Get the current minimum logging level.
     
     Returns:
       Keyword log level")

  (set-config! [this config-map]
    "Update logging configuration.
     
     Parameters:
       config-map - Map of configuration options (adapter-specific)
     
     Returns:
       Previous configuration map")

  (get-config [this]
    "Get the current logging configuration.
     
     Returns:
       Map of current configuration options"))