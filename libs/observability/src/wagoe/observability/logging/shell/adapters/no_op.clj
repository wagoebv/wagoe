(ns wagoe.observability.logging.shell.adapters.no-op
  "No-op logging adapter that safely ignores all logging calls.
   
   This adapter implements all logging protocols but performs no actual logging,
   making it safe for feature modules to use logging protocols even when logging
   is disabled or not configured."
  (:require
   [wagoe.observability.logging.ports :as ports]))

;; =============================================================================
;; No-Op Logger Implementation
;; =============================================================================

(defrecord NoOpLogger []
  ports/ILogger
  (log* [_ _ _ _ _] nil)

  (trace [_ _] nil)
  (trace [_ _ _] nil)

  (debug [_ _] nil)
  (debug [_ _ _] nil)

  (info [_ _] nil)
  (info [_ _ _] nil)

  (warn [_ _] nil)
  (warn [_ _ _] nil)
  (warn [_ _ _ _] nil)

  (error [_ _] nil)
  (error [_ _ _] nil)
  (error [_ _ _ _] nil)

  (fatal [_ _] nil)
  (fatal [_ _ _] nil)
  (fatal [_ _ _ _] nil))

;; =============================================================================
;; No-Op Audit Logger Implementation
;; =============================================================================

(defrecord NoOpAuditLogger []
  ports/IAuditLogger
  (audit-event [_ _ _ _ _ _ _] nil)
  (security-event [_ _ _ _ _] nil))

;; =============================================================================
;; No-Op Logging Context Implementation
;; =============================================================================

(defrecord NoOpLoggingContext []
  ports/ILoggingContext
  (with-context [_ _ f] (f))
  (current-context [_] {}))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-no-op-logger
  "Creates a no-op logger instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     ILogger instance that ignores all logging calls"
  [_config]
  (->NoOpLogger))

(defn create-no-op-audit-logger
  "Creates a no-op audit logger instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IAuditLogger instance that ignores all audit logging calls"
  [_config]
  (->NoOpAuditLogger))

(defn create-no-op-logging-context
  "Creates a no-op logging context instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     ILoggingContext instance that ignores all context operations"
  [_config]
  (->NoOpLoggingContext))

;; =============================================================================
;; Combined Component for System Wiring 
;; =============================================================================

(defrecord NoOpLoggingComponent []
  ports/ILogger
  (log* [_ _ _ _ _] nil)

  (trace [_ _] nil)
  (trace [_ _ _] nil)

  (debug [_ _] nil)
  (debug [_ _ _] nil)

  (info [_ _] nil)
  (info [_ _ _] nil)

  (warn [_ _] nil)
  (warn [_ _ _] nil)
  (warn [_ _ _ _] nil)

  (error [_ _] nil)
  (error [_ _ _] nil)
  (error [_ _ _ _] nil)

  (fatal [_ _] nil)
  (fatal [_ _ _] nil)
  (fatal [_ _ _ _] nil)

  ports/IAuditLogger
  (audit-event [_ _ _ _ _ _ _] nil)
  (security-event [_ _ _ _ _] nil)

  ports/ILoggingContext
  (with-context [_ _ f] (f))
  (current-context [_] {}))

(defn create-logging-component
  "Creates a combined no-op logging component that implements all protocols.
   
   This is the recommended component for system wiring, as it provides
   a single component that application code can use for all logging needs.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Component implementing ILogger, IAuditLogger, and ILoggingContext"
  [_config]
  (->NoOpLoggingComponent))

;; =============================================================================
;; Component Integration
;; =============================================================================

(defn create-no-op-logging-components
  "Creates a complete set of no-op logging components.
   
   This is useful for testing or when logging is completely disabled.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Map with all logging component instances:
     {:logger         ILogger
      :audit-logger   IAuditLogger
      :context        ILoggingContext}"
  [config]
  {:logger (create-no-op-logger config)
   :audit-logger (create-no-op-audit-logger config)
   :context (create-no-op-logging-context config)})