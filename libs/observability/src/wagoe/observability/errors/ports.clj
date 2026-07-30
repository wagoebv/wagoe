(ns wagoe.observability.errors.ports
  "Port definitions for error reporting infrastructure.
   
   This namespace defines the protocols that error reporting adapters must implement,
   providing a clean abstraction over different error reporting backends (Sentry,
   Rollbar, custom webhooks, etc.).
   
   Core protocols:
   - IErrorReporter: Exception and error message reporting
   - IErrorContext: Error context management and enrichment
   
   Error Context Structure:
   {:correlation-id string - Request correlation ID
    :request-id    string - HTTP request ID  
    :tenant-id     string - Multi-tenant context
    :user-id       string - User context (if authenticated)
    :span-id       string - Distributed tracing span ID
    :trace-id      string - Distributed tracing trace ID
    :tags          map    - Additional structured tags
    :extra         map    - Additional context data
    :breadcrumbs   vector - Sequence of events leading to error}")

;; =============================================================================
;; Core Error Reporting Protocol
;; =============================================================================

(defprotocol IErrorReporter
  "Core error reporting interface for exception tracking.
   
   This protocol handles the capture and reporting of errors, exceptions,
   and critical messages to external error tracking systems."

  (capture-exception [this exception] [this exception context] [this exception context tags]
    "Capture and report an exception/throwable.
     
     Parameters:
       exception - Throwable/Exception instance
       context   - Optional map with correlation-id, user-id, etc.
       tags      - Optional map of additional tags for filtering/grouping
     
     Returns:
       String event ID or nil if reporting failed")

  (capture-message [this message level] [this message level context] [this message level context tags]
    "Capture and report a message without an exception.
     
     Parameters:
       message - String message to report
       level   - Keyword severity (:debug :info :warning :error :fatal)
       context - Optional map with correlation-id, user-id, etc.
       tags    - Optional map of additional tags for filtering/grouping
     
     Returns:
       String event ID or nil if reporting failed")

  (capture-event [this event-map]
    "Capture a structured event map.
     
     Parameters:
       event-map - Map containing:
                   {:type :exception | :message
                    :exception throwable (for :exception type)
                    :message string (for :message type)
                    :level :debug | :info | :warning | :error | :fatal
                    :context map
                    :tags map
                    :extra map
                    :breadcrumbs vector}
     
     Returns:
       String event ID or nil if reporting failed"))

;; =============================================================================
;; Error Context Management Protocol
;; =============================================================================

(defprotocol IErrorContext
  "Protocol for managing error reporting context.
   
   This allows for correlation IDs, user context, and other metadata
   to be automatically included in all error reports within a request scope."

  (with-context [this context-map f]
    "Execute function f with additional error reporting context.
     
     The context-map will be merged with any existing context and included
     in any error reports generated within the execution of f.
     
     Parameters:
       context-map - Map of context keys/values to add
       f           - Zero-argument function to execute
     
     Returns:
       Result of calling (f)")

  (add-breadcrumb! [this breadcrumb]
    "Add a breadcrumb to the current error context.
     
     Breadcrumbs help track the sequence of events leading to an error.
     
     Parameters:
       breadcrumb - Map containing:
                    {:message string - Description of the event
                     :category string - Event category (navigation, http, etc.)
                     :level :debug | :info | :warning | :error
                     :timestamp instant - When the event occurred
                     :data map - Additional event data}
     
     Returns:
       nil (side effect only)")

  (clear-breadcrumbs! [this]
    "Clear all breadcrumbs from the current context.
     
     Returns:
       nil (side effect only)")

  (set-user! [this user-info]
    "Set user information for the current context.
     
     Parameters:
       user-info - Map containing:
                   {:id string - User ID
                    :username string - Username
                    :email string - User email
                    :ip-address string - User IP address
                    :additional map - Other user metadata}
     
     Returns:
       nil (side effect only)")

  (set-tags! [this tags]
    "Set tags for the current context.
     
     Tags are used for filtering and grouping errors.
     
     Parameters:
       tags - Map of tag keys/values
     
     Returns:
       nil (side effect only)")

  (set-extra! [this extra]
    "Set extra context data for the current context.
     
     Extra data provides additional debugging information.
     
     Parameters:
       extra - Map of additional context data
     
     Returns:
       nil (side effect only)")

  (current-context [this]
    "Get the current error reporting context.
     
     Returns:
       Map of current context including user, tags, extra, breadcrumbs"))

;; =============================================================================
;; Error Filtering Protocol
;; =============================================================================

(defprotocol IErrorFilter
  "Protocol for filtering and sampling errors before reporting.
   
   This allows for rate limiting, noise reduction, and conditional reporting
   based on error characteristics or system state."

  (should-report? [this exception context]
    "Determine if an exception should be reported.
     
     Parameters:
       exception - Throwable/Exception instance
       context   - Error context map
     
     Returns:
       Boolean indicating if error should be reported")

  (should-report-message? [this message level context]
    "Determine if a message should be reported.
     
     Parameters:
       message - String message
       level   - Keyword severity level
       context - Error context map
     
     Returns:
       Boolean indicating if message should be reported")

  (sample-rate [this exception-type]
    "Get the sample rate for a specific exception type.
     
     Parameters:
       exception-type - Class or keyword identifying exception type
     
     Returns:
       Float between 0.0 and 1.0 indicating sample rate")

  (add-filter-rule! [this rule]
    "Add a filtering rule.
     
     Parameters:
       rule - Map containing:
              {:type :exception | :message | :context
               :pattern regex | string | predicate-fn
               :action :ignore | :sample | :report
               :sample-rate float (for :sample action)}
     
     Returns:
       nil (side effect only)")

  (remove-filter-rule! [this rule-id]
    "Remove a filtering rule.
     
     Parameters:
       rule-id - Identifier returned when rule was added
     
     Returns:
       Boolean indicating if rule was found and removed"))

;; =============================================================================
;; Error Reporting Configuration Protocol
;; =============================================================================

(defprotocol IErrorReportingConfig
  "Protocol for runtime error reporting configuration changes.
   
   This allows for dynamic adjustment of error reporting behavior without
   requiring application restart."

  (set-environment! [this environment]
    "Set the environment name for error reporting.
     
     Parameters:
       environment - String environment name (dev, staging, prod)
     
     Returns:
       Previous environment name")

  (get-environment [this]
    "Get the current environment name.
     
     Returns:
       String environment name")

  (set-release! [this release]
    "Set the application release/version for error reporting.
     
     Parameters:
       release - String release identifier
     
     Returns:
       Previous release identifier")

  (get-release [this]
    "Get the current release identifier.
     
     Returns:
       String release identifier")

  (set-sample-rate! [this sample-rate]
    "Set the global error reporting sample rate.
     
     Parameters:
       sample-rate - Float between 0.0 and 1.0
     
     Returns:
       Previous sample rate")

  (get-sample-rate [this]
    "Get the current global sample rate.
     
     Returns:
       Float sample rate")

  (enable-reporting! [this]
    "Enable error reporting.
     
     Returns:
       Previous enabled state (boolean)")

  (disable-reporting! [this]
    "Disable error reporting.
     
     Returns:
       Previous enabled state (boolean)")

  (reporting-enabled? [this]
    "Check if error reporting is currently enabled.
     
     Returns:
       Boolean indicating if reporting is enabled"))