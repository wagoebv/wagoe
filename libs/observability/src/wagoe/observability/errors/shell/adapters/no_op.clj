(ns wagoe.observability.errors.shell.adapters.no-op
  "No-op error reporting adapter that safely ignores all error reporting operations.
   
   This adapter implements all error reporting protocols but performs no actual
   error reporting, making it safe for feature modules to use error reporting
   protocols even when error reporting is disabled or not configured."
  (:require
   [wagoe.observability.errors.ports :as ports]))

;; =============================================================================
;; No-Op Error Reporter Implementation
;; =============================================================================

(defrecord NoOpErrorReporter []
  ports/IErrorReporter
  (capture-exception [_ _] "no-op-event-id")
  (capture-exception [_ _ _] "no-op-event-id")
  (capture-exception [_ _ _ _] "no-op-event-id")

  (capture-message [_ _ _] "no-op-event-id")
  (capture-message [_ _ _ _] "no-op-event-id")
  (capture-message [_ _ _ _ _] "no-op-event-id")

  (capture-event [_ _] "no-op-event-id"))

;; =============================================================================
;; No-Op Error Context Implementation
;; =============================================================================

(defrecord NoOpErrorContext []
  ports/IErrorContext
  (with-context [_ _ f] (f))
  (add-breadcrumb! [_ _] nil)
  (clear-breadcrumbs! [_] nil)
  (set-user! [_ _] nil)
  (set-tags! [_ _] nil)
  (set-extra! [_ _] nil)
  (current-context [_] {}))

;; =============================================================================
;; No-Op Error Filter Implementation
;; =============================================================================

(defrecord NoOpErrorFilter []
  ports/IErrorFilter
  (should-report? [_ _ _] false)
  (should-report-message? [_ _ _ _] false)
  (sample-rate [_ _] 0.0)
  (add-filter-rule! [_ _] nil)
  (remove-filter-rule! [_ _] false))

;; =============================================================================
;; No-Op Error Reporting Config Implementation
;; =============================================================================

(defrecord NoOpErrorReportingConfig []
  ports/IErrorReportingConfig
  (set-environment! [_ _] "development")
  (get-environment [_] "development")
  (set-release! [_ _] "unknown")
  (get-release [_] "unknown")
  (set-sample-rate! [_ _] 0.0)
  (get-sample-rate [_] 0.0)
  (enable-reporting! [_] false)
  (disable-reporting! [_] false)
  (reporting-enabled? [_] false))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-error-reporter
  "Creates a no-op error reporter instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IErrorReporter instance that ignores all error reporting calls"
  ([]
   (->NoOpErrorReporter))
  ([_config]
   (->NoOpErrorReporter)))

(defn create-error-context
  "Creates a no-op error context instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IErrorContext instance that ignores all context operations"
  ([]
   (->NoOpErrorContext))
  ([_config]
   (->NoOpErrorContext)))

(defn create-error-filter
  "Creates a no-op error filter instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IErrorFilter instance that denies all error reporting"
  ([]
   (->NoOpErrorFilter))
  ([_config]
   (->NoOpErrorFilter)))

(defn create-error-reporting-config
  "Creates a no-op error reporting config instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IErrorReportingConfig instance that reports disabled state"
  ([]
   (->NoOpErrorReportingConfig))
  ([_config]
   (->NoOpErrorReportingConfig)))

;; =============================================================================
;; Combined Component for System Wiring 
;; =============================================================================

(defrecord NoOpErrorReportingComponent []
  ports/IErrorReporter
  (capture-exception [_ _] "no-op-event-id")
  (capture-exception [_ _ _] "no-op-event-id")
  (capture-exception [_ _ _ _] "no-op-event-id")

  (capture-message [_ _ _] "no-op-event-id")
  (capture-message [_ _ _ _] "no-op-event-id")
  (capture-message [_ _ _ _ _] "no-op-event-id")

  (capture-event [_ _] "no-op-event-id")

  ports/IErrorContext
  (with-context [_ _ f] (f))
  (add-breadcrumb! [_ _] nil)
  (clear-breadcrumbs! [_] nil)
  (set-user! [_ _] nil)
  (set-tags! [_ _] nil)
  (set-extra! [_ _] nil)
  (current-context [_] {})

  ports/IErrorFilter
  (should-report? [_ _ _] false)
  (should-report-message? [_ _ _ _] false)
  (sample-rate [_ _] 0.0)
  (add-filter-rule! [_ _] nil)
  (remove-filter-rule! [_ _] false)

  ports/IErrorReportingConfig
  (set-environment! [_ _] "development")
  (get-environment [_] "development")
  (set-release! [_ _] "unknown")
  (get-release [_] "unknown")
  (set-sample-rate! [_ _] 0.0)
  (get-sample-rate [_] 0.0)
  (enable-reporting! [_] false)
  (disable-reporting! [_] false)
  (reporting-enabled? [_] false))

(defn create-error-reporting-component
  "Creates a combined no-op error reporting component that implements all protocols.
   
   This is the recommended component for system wiring, as it provides
   a single component that application code can use for all error reporting needs.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Component implementing IErrorReporter, IErrorContext, 
     IErrorFilter, and IErrorReportingConfig"
  ([]
   (->NoOpErrorReportingComponent))
  ([_config]
   (->NoOpErrorReportingComponent)))

;; =============================================================================
;; Component Integration
;; =============================================================================

(defn create-error-reporting-components
  "Creates a complete set of no-op error reporting components.
   
   This is useful for testing or when error reporting is completely disabled.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Map with all error reporting component instances:
     {:reporter  IErrorReporter
      :context   IErrorContext
      :filter    IErrorFilter
      :config    IErrorReportingConfig}"
  ([]
   (create-error-reporting-components {}))
  ([config]
   {:reporter (create-error-reporter config)
    :context (create-error-context config)
    :filter (create-error-filter config)
    :config (create-error-reporting-config config)}))