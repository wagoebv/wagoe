(ns wagoe.observability.errors.shell.adapters.sentry
  "Sentry error reporting adapter implementation.
   
   This adapter integrates with Sentry (https://sentry.io) for production error tracking
   and monitoring. It implements all error reporting protocols to provide comprehensive
   exception tracking, context management, and alerting capabilities.
   
   Features:
   - Exception capture with stack traces
   - Message-level reporting (debug, info, warning, error, fatal)
   - Context management (user, tags, extra data, breadcrumbs)
   - Environment and release tracking
   - Sampling and filtering capabilities
   - Runtime configuration management
   
   Configuration:
   The adapter requires a Sentry DSN (Data Source Name) and optionally accepts:
   - Environment name (dev, staging, prod)
   - Release identifier
   - Sample rate (0.0 to 1.0)
   - Debug mode flag
   - Server name
   
   Example:
   (def sentry-reporter
     (create-sentry-error-reporter
       {:dsn \"https://your-dsn@sentry.io/project-id\"
        :environment \"production\"
        :release \"1.0.0\"
        :sample-rate 1.0
        :debug false}))"
  (:require
   [wagoe.observability.errors.ports :as ports]
   [wagoe.core.utils.pii-redaction :as pii]
   [clojure.tools.logging :as log]
   [sentry-clj.core :as sentry])
  (:import
   (java.time Instant)
   (java.util UUID)))


(defn- current-timestamp
  "Get current timestamp as Instant.

   Private and local: `logging` shares one of these across its two adapters,
   but errors is a different module and reaching across would be a boundary
   crossing check:ports refuses (BOU-302)."
  []
  (Instant/now))

;; =============================================================================
;; State Management
;; =============================================================================

(def ^:private thread-local-context
  "Thread-local context storage for error reporting context."
  (ThreadLocal.))

(defn- get-thread-context
  "Get the current thread-local error reporting context."
  []
  (.get thread-local-context))

(defn- set-thread-context!
  "Set the thread-local error reporting context."
  [context]
  (.set thread-local-context context))

(defn- merge-thread-context!
  "Merge additional context into the current thread-local context."
  [additional-context]
  (let [current (get-thread-context)
        merged (merge current additional-context)]
    (set-thread-context! merged)
    merged))

;; =============================================================================
;; Context Management Utilities
;; =============================================================================

(defn- normalize-level
  "Convert keyword level to Sentry level string."
  [level]
  (case level
    :debug "debug"
    :info "info"
    :warning "warning"
    :warn "warning"
    :error "error"
    :fatal "fatal"
    :critical "fatal"
    "info")) ; default

(defn- generate-event-id
  "Generate a unique event ID for Sentry."
  []
  (str (UUID/randomUUID)))

(defn- prepare-context
  "Prepare context map for Sentry, merging thread-local and provided context."
  [provided-context]
  (let [thread-context (get-thread-context)
        merged-context (merge thread-context provided-context)]
    merged-context))

(defn- extract-sentry-context
  "Extract Sentry-specific context from merged context map."
  [context]
  (let [{:keys [correlation-id request-id tenant-id user-id span-id trace-id
                tags extra breadcrumbs]} context]
    (cond-> {}
      correlation-id (assoc-in [:tags :correlation-id] correlation-id)
      request-id (assoc-in [:tags :request-id] request-id)
      tenant-id (assoc-in [:tags :tenant-id] tenant-id)
      user-id (assoc-in [:user :id] user-id)
      span-id (assoc-in [:tags :span-id] span-id)
      trace-id (assoc-in [:tags :trace-id] trace-id)
      tags (update :tags merge tags)
      extra (assoc :extra extra)
      breadcrumbs (assoc :breadcrumbs breadcrumbs))))

;; =============================================================================
;; Sentry Error Reporter Implementation
;; =============================================================================

(defrecord SentryErrorReporter [config]
  ports/IErrorReporter
  (capture-exception [this exception]
    (ports/capture-exception this exception nil nil))

  (capture-exception [this exception context]
    (ports/capture-exception this exception context nil))

  (capture-exception [_ exception context tags]
    (try
      (let [merged-context (prepare-context context)
            sentry-context (extract-sentry-context merged-context)
            sentry-context (if tags
                             (update-in sentry-context [:tags] merge tags)
                             sentry-context)
            sentry-context (pii/apply-redaction sentry-context config)
            event-id (generate-event-id)]

        (sentry/send-event
         (merge sentry-context
                {:throwable exception
                 :event-id event-id
                 :timestamp (current-timestamp)}))

        (log/debug "Captured exception to Sentry" {:event-id event-id
                                                   :exception-type (type exception)})
        event-id)
      (catch Exception e
        (log/error e "Failed to capture exception to Sentry")
        nil)))

  (capture-message [this message level]
    (ports/capture-message this message level nil nil))

  (capture-message [this message level context]
    (ports/capture-message this message level context nil))

  (capture-message [_ message level context tags]
    (try
      (let [merged-context (prepare-context context)
            sentry-context (extract-sentry-context merged-context)
            sentry-context (if tags
                             (update-in sentry-context [:tags] merge tags)
                             sentry-context)
            sentry-context (pii/apply-redaction sentry-context config)
            event-id (generate-event-id)]

        (sentry/send-event
         (merge sentry-context
                {:message message
                 :level (normalize-level level)
                 :event-id event-id
                 :timestamp (current-timestamp)}))

        (log/debug "Captured message to Sentry" {:event-id event-id
                                                 :level level
                                                 :message message})
        event-id)
      (catch Exception e
        (log/error e "Failed to capture message to Sentry")
        nil)))

  (capture-event [_ event-map]
    (try
      (let [{:keys [type exception message level context tags extra breadcrumbs]} event-map
            merged-context (prepare-context context)
            sentry-context (extract-sentry-context merged-context)
            sentry-context (cond-> sentry-context
                             tags (update :tags merge tags)
                             extra (assoc :extra extra)
                             breadcrumbs (assoc :breadcrumbs breadcrumbs))
            sentry-context (pii/apply-redaction sentry-context config)
            event-id (generate-event-id)]

        (case type
          :exception
          (sentry/send-event
           (merge sentry-context
                  {:throwable exception
                   :event-id event-id
                   :timestamp (current-timestamp)}))

          :message
          (sentry/send-event
           (merge sentry-context
                  {:message message
                   :level (normalize-level level)
                   :event-id event-id
                   :timestamp (current-timestamp)}))

          (throw (ex-info "Unknown event type" {:type type :event-map event-map})))

        (log/debug "Captured event to Sentry" {:event-id event-id
                                               :type type})
        event-id)
      (catch Exception e
        (log/error e "Failed to capture event to Sentry")
        nil))))

;; =============================================================================
;; Sentry Error Context Implementation
;; =============================================================================

(defrecord SentryErrorContext [config]
  ports/IErrorContext
  (with-context [_this context-map f]
    (let [original-context (get-thread-context)
          merged-context (merge original-context context-map)]
      (try
        (set-thread-context! merged-context)
        (f)
        (finally
          (set-thread-context! original-context)))))

  (add-breadcrumb! [_this breadcrumb]
    (let [{:keys [message category level timestamp data]} breadcrumb
          sentry-breadcrumb {:message message
                             :category (or category "default")
                             :level (normalize-level (or level :info))
                             :timestamp (or timestamp (current-timestamp))
                             :data (or data {})}
          current-context (get-thread-context)
          breadcrumbs (get current-context :breadcrumbs [])
          updated-breadcrumbs (conj breadcrumbs sentry-breadcrumb)]
      (merge-thread-context! {:breadcrumbs updated-breadcrumbs})
      nil))

  (clear-breadcrumbs! [_this]
    (merge-thread-context! {:breadcrumbs []})
    nil)

  (set-user! [_this user-info]
    (let [{:keys [id username email ip-address additional]} user-info
          sentry-user (cond-> {}
                        id (assoc :id id)
                        username (assoc :username username)
                        email (assoc :email email)
                        ip-address (assoc :ip_address ip-address)
                        additional (merge additional))]
      (merge-thread-context! {:user sentry-user :user-id id})
      nil))

  (set-tags! [_this tags]
    (merge-thread-context! {:tags tags})
    nil)

  (set-extra! [_this extra]
    (merge-thread-context! {:extra extra})
    nil)

  (current-context [_this]
    (get-thread-context)))

;; =============================================================================
;; Sentry Error Filter Implementation
;; =============================================================================

(defrecord SentryErrorFilter [config filters]
  ports/IErrorFilter
  (should-report? [_this _exception _context]
    ;; For now, implement basic filtering
    ;; TODO: Implement more sophisticated filtering based on config
    true)

  (should-report-message? [_this _message _level _context]
    ;; For now, implement basic filtering
    ;; TODO: Implement more sophisticated filtering based on config
    true)

  (sample-rate [_this _exception-type]
    ;; Return configured sample rate or default
    (get config :sample-rate 1.0))

  (add-filter-rule! [_this _rule]
    ;; TODO: Implement dynamic filter rule addition
    (log/warn "Dynamic filter rules not yet implemented"))

  (remove-filter-rule! [_this _rule-id]
    ;; TODO: Implement dynamic filter rule removal
    (log/warn "Dynamic filter rule removal not yet implemented")
    false))

;; =============================================================================
;; Sentry Error Reporting Configuration Implementation
;; =============================================================================

(defrecord SentryErrorReportingConfig [config-atom]
  ports/IErrorReportingConfig
  (set-environment! [_this environment]
    (let [old-env (:environment @config-atom)]
      (swap! config-atom assoc :environment environment)
      old-env))

  (get-environment [_this]
    (:environment @config-atom))

  (set-release! [_this release]
    (let [old-release (:release @config-atom)]
      (swap! config-atom assoc :release release)
      old-release))

  (get-release [_this]
    (:release @config-atom))

  (set-sample-rate! [_this sample-rate]
    (let [old-rate (:sample-rate @config-atom)]
      (swap! config-atom assoc :sample-rate sample-rate)
      ;; Note: Sentry doesn't support runtime sample rate changes
      ;; This would require reinitializing the client
      old-rate))

  (get-sample-rate [_this]
    (:sample-rate @config-atom))

  (enable-reporting! [_this]
    (let [old-enabled (:enabled @config-atom)]
      (swap! config-atom assoc :enabled true)
      old-enabled))

  (disable-reporting! [_this]
    (let [old-enabled (:enabled @config-atom)]
      (swap! config-atom assoc :enabled false)
      old-enabled))

  (reporting-enabled? [_this]
    (:enabled @config-atom)))
;; Factory Functions
;; =============================================================================

(defn create-sentry-error-reporter
  "Create a Sentry error reporter instance.

   Config map should contain:
   - :dsn (required) - Sentry Data Source Name
   - :environment (optional) - Environment name (dev, staging, prod)
   - :release (optional) - Release identifier
   - :sample-rate (optional) - Sample rate 0.0 to 1.0 (default 1.0)
   - :debug (optional) - Enable debug mode (default false)
   - :server-name (optional) - Server name for grouping

   Example:
   (create-sentry-error-reporter
     {:dsn \"https://your-dsn@sentry.io/project-id\"
      :environment \"production\"
      :release \"1.0.0\"
      :sample-rate 1.0})"
  [config]
  (let [{:keys [dsn environment release sample-rate debug server-name]} config]
    (when-not dsn
      (throw (ex-info "Sentry DSN is required" {:type :configuration-error :config config})))

    ;; Initialize Sentry client
    (sentry/init! dsn (cond-> {}
                        environment (assoc :environment environment)
                        release (assoc :release release)
                        sample-rate (assoc :traces-sample-rate sample-rate)
                        debug (assoc :debug debug)
                        server-name (assoc :server-name server-name)))

    (log/info "Initialized Sentry error reporter" {:dsn (subs dsn 0 20)
                                                   :environment environment
                                                   :release release})

    (->SentryErrorReporter config)))

(defn create-sentry-error-context
  "Create a Sentry error context manager instance."
  [config]
  (->SentryErrorContext config))

(defn create-sentry-error-filter
  "Create a Sentry error filter instance."
  [config]
  (->SentryErrorFilter config (atom {})))

(defn create-sentry-error-reporting-config
  "Create a Sentry error reporting configuration manager instance."
  [config]
  (->SentryErrorReportingConfig (atom config)))

;; =============================================================================
;; Integrant Components
;; =============================================================================

(defrecord ^{:clj-kondo/ignore [:unused-binding]}
 SentryErrorReportingComponent [config error-reporter error-context error-filter error-config]
  ports/IErrorReporter
  (capture-exception [_this exception]
    (ports/capture-exception error-reporter exception))
  (capture-exception [_this exception context]
    (ports/capture-exception error-reporter exception context))
  (capture-exception [_this exception context tags]
    (ports/capture-exception error-reporter exception context tags))
  (capture-message [_this message level]
    (ports/capture-message error-reporter message level))
  (capture-message [_this message level context]
    (ports/capture-message error-reporter message level context))
  (capture-message [_this message level context tags]
    (ports/capture-message error-reporter message level context tags))
  (capture-event [_this event-map]
    (ports/capture-event error-reporter event-map))

  ports/IErrorContext
  (with-context [_this context-map f]
    (ports/with-context error-context context-map f))
  (add-breadcrumb! [_this breadcrumb]
    (ports/add-breadcrumb! error-context breadcrumb))
  (clear-breadcrumbs! [_this]
    (ports/clear-breadcrumbs! error-context))
  (set-user! [_this user-info]
    (ports/set-user! error-context user-info))
  (set-tags! [_this tags]
    (ports/set-tags! error-context tags))
  (set-extra! [_this extra]
    (ports/set-extra! error-context extra))
  (current-context [_this]
    (ports/current-context error-context))

  ports/IErrorFilter
  (should-report? [_this exception context]
    (ports/should-report? error-filter exception context))
  (should-report-message? [_this message level context]
    (ports/should-report-message? error-filter message level context))
  (sample-rate [_this exception-type]
    (ports/sample-rate error-filter exception-type))
  (add-filter-rule! [_this rule]
    (ports/add-filter-rule! error-filter rule))
  (remove-filter-rule! [_this rule-id]
    (ports/remove-filter-rule! error-filter rule-id))

  ports/IErrorReportingConfig
  (set-environment! [_this environment]
    (ports/set-environment! error-config environment))
  (get-environment [_this]
    (ports/get-environment error-config))
  (set-release! [_this release]
    (ports/set-release! error-config release))
  (get-release [_this]
    (ports/get-release error-config))
  (set-sample-rate! [_this sample-rate]
    (ports/set-sample-rate! error-config sample-rate))
  (get-sample-rate [_this]
    (ports/get-sample-rate error-config))
  (enable-reporting! [_this]
    (ports/enable-reporting! error-config))
  (disable-reporting! [_this]
    (ports/disable-reporting! error-config))
  (reporting-enabled? [_this]
    (ports/reporting-enabled? error-config)))

(defn create-sentry-error-reporting-component
  "Create a complete Sentry error reporting component that implements all protocols.

   This is the recommended way to create Sentry error reporting for use with Integrant."
  [config]
  (let [error-reporter (create-sentry-error-reporter config)
        error-context (create-sentry-error-context config)
        error-filter (create-sentry-error-filter config)
        error-config (create-sentry-error-reporting-config config)]
    (->SentryErrorReportingComponent config error-reporter error-context error-filter error-config)))

(defn create-sentry-error-reporting-components
  "Create a map of Sentry error reporting components for Integrant system configuration.

   Returns a map with keys:
   - :error-reporter - IErrorReporter implementation
   - :error-context - IErrorContext implementation
   - :error-filter - IErrorFilter implementation
   - :error-config - IErrorReportingConfig implementation"
  [config]
  {:error-reporter (create-sentry-error-reporter config)
   :error-context (create-sentry-error-context config)
   :error-filter (create-sentry-error-filter config)
   :error-config (create-sentry-error-reporting-config config)})