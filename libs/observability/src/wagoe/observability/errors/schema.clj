(ns wagoe.observability.errors.schema
  "Configuration schemas for error reporting infrastructure.
   
   This namespace defines Malli schemas for validating error reporting configuration,
   ensuring proper structure and constraints for different error reporting providers."
  (:require
   [malli.core :as m]
   [malli.transform :as mt]))

;; =============================================================================
;; Core Error Reporting Configuration Schemas
;; =============================================================================

(def ErrorProvider
  "Supported error reporting providers."
  [:enum :no-op :sentry :rollbar :bugsnag :webhook :custom])

(def ErrorSeverity
  "Severity levels for error reporting."
  [:enum :debug :info :warning :error :fatal])

(def ErrorEnvironment
  "Environment types for error context."
  [:enum :development :staging :test :production])

(def FilterConfig
  "Configuration for error filtering."
  [:map {:title "Error Filter Configuration"}
   [:enabled {:optional true} :boolean]
   [:min-severity {:optional true} ErrorSeverity]
   [:exclude-exceptions {:optional true} [:vector :string]]
   [:include-exceptions {:optional true} [:vector :string]]
   [:exclude-messages {:optional true} [:vector :string]]
   [:include-messages {:optional true} [:vector :string]]
   [:exclude-paths {:optional true} [:vector :string]]
   [:rate-limit {:optional true}
    [:map {:title "Rate Limiting"}
     [:enabled {:optional true} :boolean]
     [:max-reports-per-minute {:optional true} [:int {:min 1 :max 1000}]]
     [:max-reports-per-hour {:optional true} [:int {:min 1 :max 10000}]]
     [:burst-allowance {:optional true} [:int {:min 1 :max 100}]]]]])

(def ContextConfig
  "Configuration for error context collection."
  [:map {:title "Error Context Configuration"}
   [:collect-user-context {:optional true} :boolean]
   [:collect-request-context {:optional true} :boolean]
   [:collect-system-context {:optional true} :boolean]
   [:collect-custom-context {:optional true} :boolean]
   [:max-context-size {:optional true} [:int {:min 1024 :max 1048576}]] ; 1KB to 1MB
   [:sensitive-keys {:optional true} [:vector :string]]
   [:include-local-variables {:optional true} :boolean]
   [:max-stack-frames {:optional true} [:int {:min 5 :max 200}]]
   [:source-code-context {:optional true}
    [:map {:title "Source Code Context"}
     [:enabled {:optional true} :boolean]
     [:lines-before {:optional true} [:int {:min 0 :max 20}]]
     [:lines-after {:optional true} [:int {:min 0 :max 20}]]]]])

(def RetryConfig
  "Configuration for error reporting retry logic."
  [:map {:title "Retry Configuration"}
   [:enabled {:optional true} :boolean]
   [:max-attempts {:optional true} [:int {:min 1 :max 10}]]
   [:initial-delay-ms {:optional true} [:int {:min 100 :max 60000}]]
   [:max-delay-ms {:optional true} [:int {:min 1000 :max 300000}]]
   [:backoff-multiplier {:optional true} [:double {:min 1.0 :max 10.0}]]
   [:jitter {:optional true} :boolean]])

(def BaseErrorReportingConfig
  "Base error reporting configuration shared by all providers."
  [:map {:title "Base Error Reporting Configuration"}
   [:provider ErrorProvider]
   [:enabled {:optional true} :boolean]
   [:environment {:optional true} ErrorEnvironment]
   [:service-name {:optional true} [:string {:min 1 :max 100}]]
   [:service-version {:optional true} [:string {:min 1 :max 50}]]
   [:release {:optional true} [:string {:min 1 :max 100}]]
   [:server-name {:optional true} [:string {:min 1 :max 253}]]
   [:filtering {:optional true} FilterConfig]
   [:context {:optional true} ContextConfig]
   [:retry {:optional true} RetryConfig]
   [:tags {:optional true} [:map-of :keyword :string]]
   [:async {:optional true} :boolean]
   [:buffer-size {:optional true} [:int {:min 10 :max 10000}]]
   [:flush-timeout {:optional true} [:int {:min 1000 :max 60000}]]])

;; =============================================================================
;; Provider-Specific Configuration Schemas
;; =============================================================================

(def NoOpErrorReportingConfig
  "Configuration for no-op error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "No-Op Error Reporting Configuration"}
    [:provider [:= :no-op]]
    [:log-errors {:optional true} :boolean]]])

(def SentryErrorReportingConfig
  "Configuration for Sentry error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "Sentry Error Reporting Configuration"}
    [:provider [:= :sentry]]
    [:dsn [:string {:min 1 :max 500}]]
    [:project-id {:optional true} [:string {:min 1 :max 100}]]
    [:organization {:optional true} [:string {:min 1 :max 100}]]
    [:sample-rate {:optional true} [:double {:min 0.0 :max 1.0}]]
    [:traces-sample-rate {:optional true} [:double {:min 0.0 :max 1.0}]]
    [:max-breadcrumbs {:optional true} [:int {:min 0 :max 1000}]]
    [:attach-stacktrace {:optional true} :boolean]
    [:send-default-pii {:optional true} :boolean]
    [:in-app-includes {:optional true} [:vector :string]]
    [:in-app-excludes {:optional true} [:vector :string]]
    [:before-send {:optional true} [:string {:min 1 :max 200}]]
    [:before-breadcrumb {:optional true} [:string {:min 1 :max 200}]]
    [:transport-options {:optional true}
     [:map {:title "Transport Options"}
      [:timeout {:optional true} [:int {:min 1000 :max 60000}]]
      [:shutdown-timeout {:optional true} [:int {:min 1000 :max 30000}]]]]]])

(def RollbarErrorReportingConfig
  "Configuration for Rollbar error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "Rollbar Error Reporting Configuration"}
    [:provider [:= :rollbar]]
    [:access-token [:string {:min 32 :max 32}]]
    [:endpoint {:optional true} [:string {:min 1 :max 500}]]
    [:code-version {:optional true} [:string {:min 1 :max 100}]]
    [:branch {:optional true} [:string {:min 1 :max 100}]]
    [:person-tracking {:optional true}
     [:map {:title "Person Tracking"}
      [:enabled {:optional true} :boolean]
      [:person-id-fn {:optional true} [:string {:min 1 :max 200}]]
      [:person-username-fn {:optional true} [:string {:min 1 :max 200}]]
      [:person-email-fn {:optional true} [:string {:min 1 :max 200}]]]]
    [:payload-options {:optional true}
     [:map {:title "Payload Options"}
      [:host {:optional true} [:string {:min 1 :max 253}]]
      [:root {:optional true} [:string {:min 1 :max 500}]]
      [:framework {:optional true} [:string {:min 1 :max 100}]]]]]])

(def BugsnagErrorReportingConfig
  "Configuration for Bugsnag error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "Bugsnag Error Reporting Configuration"}
    [:provider [:= :bugsnag]]
    [:api-key [:string {:min 32 :max 32}]]
    [:endpoint {:optional true} [:string {:min 1 :max 500}]]
    [:app-version {:optional true} [:string {:min 1 :max 50}]]
    [:app-type {:optional true} [:string {:min 1 :max 50}]]
    [:auto-capture-sessions {:optional true} :boolean]
    [:send-uncaught-exceptions {:optional true} :boolean]
    [:send-unhandled-rejections {:optional true} :boolean]
    [:max-events {:optional true} [:int {:min 1 :max 1000}]]
    [:metadata-filters {:optional true} [:vector :string]]
    [:project-packages {:optional true} [:vector :string]]]])

(def WebhookErrorReportingConfig
  "Configuration for webhook-based error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "Webhook Error Reporting Configuration"}
    [:provider [:= :webhook]]
    [:url [:string {:min 1 :max 500}]]
    [:method {:optional true} [:enum :post :put]]
    [:headers {:optional true} [:map-of :string :string]]
    [:timeout {:optional true} [:int {:min 1000 :max 60000}]]
    [:authentication {:optional true}
     [:map {:title "Authentication"}
      [:type [:enum :none :basic :bearer :api-key :custom]]
      [:username {:optional true} [:string {:min 1 :max 100}]]
      [:password {:optional true} [:string {:min 1 :max 200}]]
      [:token {:optional true} [:string {:min 1 :max 500}]]
      [:api-key {:optional true} [:string {:min 1 :max 200}]]
      [:custom-header {:optional true} [:string {:min 1 :max 100}]]
      [:custom-value {:optional true} [:string {:min 1 :max 500}]]]]
    [:payload-format {:optional true} [:enum :json :form :custom]]
    [:custom-payload-fn {:optional true} [:string {:min 1 :max 200}]]]])

(def CustomErrorReportingConfig
  "Configuration for custom error reporting provider."
  [:and BaseErrorReportingConfig
   [:map {:title "Custom Error Reporting Configuration"}
    [:provider [:= :custom]]
    [:adapter-ns [:string {:min 1 :max 200}]]
    [:adapter-config {:optional true} :map]]])

;; =============================================================================
;; Unified Configuration Schema
;; =============================================================================

(def ErrorReportingConfig
  "Unified error reporting configuration schema that validates any provider."
  [:multi {:dispatch :provider
           :title "Error Reporting Configuration"}
   [:no-op NoOpErrorReportingConfig]
   [:sentry SentryErrorReportingConfig]
   [:rollbar RollbarErrorReportingConfig]
   [:bugsnag BugsnagErrorReportingConfig]
   [:webhook WebhookErrorReportingConfig]
   [:custom CustomErrorReportingConfig]])

;; =============================================================================
;; Application Error Handling Configuration
;; =============================================================================

(def ErrorHandlingConfig
  "Configuration for application-level error handling."
  [:map {:title "Error Handling Configuration"}
   [:enabled {:optional true} :boolean]
   [:catch-all {:optional true} :boolean]
   [:handle-http-errors {:optional true} :boolean]
   [:handle-async-errors {:optional true} :boolean]
   [:handle-validation-errors {:optional true} :boolean]
   [:handle-database-errors {:optional true} :boolean]
   [:handle-external-service-errors {:optional true} :boolean]
   [:custom-handlers {:optional true} [:vector :string]]
   [:error-pages {:optional true}
    [:map {:title "Error Pages"}
     [:enabled {:optional true} :boolean]
     [:template-path {:optional true} [:string {:min 1 :max 500}]]
     [:include-stack-trace {:optional true} :boolean]]]])

(def AlertingConfig
  "Configuration for error-based alerting."
  [:map {:title "Alerting Configuration"}
   [:enabled {:optional true} :boolean]
   [:channels {:optional true} [:vector [:enum :email :slack :pagerduty :webhook]]]
   [:thresholds {:optional true}
    [:map {:title "Alert Thresholds"}
     [:error-rate {:optional true} [:double {:min 0.0 :max 1.0}]]
     [:error-count {:optional true} [:int {:min 1 :max 10000}]]
     [:time-window-minutes {:optional true} [:int {:min 1 :max 1440}]]]] ; Up to 24 hours
   [:escalation {:optional true}
    [:map {:title "Escalation Rules"}
     [:enabled {:optional true} :boolean]
     [:levels {:optional true} [:vector :string]]
     [:delay-minutes {:optional true} [:int {:min 1 :max 480}]]]]])

;; =============================================================================
;; Complete System Configuration Schema
;; =============================================================================

(def SystemErrorReportingConfig
  "Complete system error reporting configuration."
  [:map {:title "System Error Reporting Configuration"}
   [:primary {:optional true} ErrorReportingConfig]
   [:secondary {:optional true} ErrorReportingConfig] ; Backup reporting
   [:handling {:optional true} ErrorHandlingConfig]
   [:alerting {:optional true} AlertingConfig]
   [:monitoring {:optional true} :map]]) ; For future monitoring integration

;; =============================================================================
;; Default Configurations
;; =============================================================================

(def default-no-op-config
  "Default no-op error reporting configuration."
  {:provider :no-op
   :enabled false
   :log-errors true
   :environment :development})

(def default-sentry-config
  "Default Sentry error reporting configuration."
  {:provider :sentry
   :enabled true
   :environment :development
   :service-name "boundary"
   :sample-rate 1.0
   :traces-sample-rate 0.1
   :max-breadcrumbs 100
   :attach-stacktrace true
   :send-default-pii false
   :filtering {:enabled true
               :min-severity :warning
               :rate-limit {:enabled true
                            :max-reports-per-minute 60
                            :max-reports-per-hour 1000
                            :burst-allowance 10}}
   :context {:collect-user-context true
             :collect-request-context true
             :collect-system-context true
             :collect-custom-context false
             :max-context-size 65536
             :sensitive-keys ["password" "token" "secret" "key" "authorization"]
             :include-local-variables false
             :max-stack-frames 50
             :source-code-context {:enabled true
                                   :lines-before 5
                                   :lines-after 5}}
   :retry {:enabled true
           :max-attempts 3
           :initial-delay-ms 1000
           :max-delay-ms 30000
           :backoff-multiplier 2.0
           :jitter true}
   :async true
   :buffer-size 100
   :flush-timeout 5000})

(def default-webhook-config
  "Default webhook error reporting configuration."
  {:provider :webhook
   :enabled true
   :environment :development
   :service-name "boundary"
   :method :post
   :timeout 10000
   :authentication {:type :none}
   :payload-format :json
   :filtering {:enabled true
               :min-severity :error
               :rate-limit {:enabled true
                            :max-reports-per-minute 30
                            :max-reports-per-hour 500
                            :burst-allowance 5}}
   :context {:collect-user-context false
             :collect-request-context true
             :collect-system-context true
             :collect-custom-context false
             :max-context-size 32768
             :sensitive-keys ["password" "token" "secret" "key"]
             :include-local-variables false
             :max-stack-frames 25}
   :retry {:enabled true
           :max-attempts 2
           :initial-delay-ms 2000
           :max-delay-ms 10000
           :backoff-multiplier 1.5
           :jitter false}
   :async true
   :buffer-size 50})

(def default-error-handling-config
  "Default error handling configuration."
  {:enabled true
   :catch-all false
   :handle-http-errors true
   :handle-async-errors true
   :handle-validation-errors true
   :handle-database-errors true
   :handle-external-service-errors true
   :error-pages {:enabled true
                 :include-stack-trace false}})

(def default-alerting-config
  "Default alerting configuration."
  {:enabled false
   :channels [:email]
   :thresholds {:error-rate 0.05
                :error-count 10
                :time-window-minutes 5}
   :escalation {:enabled false
                :delay-minutes 15}})

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private error-reporting-config-validator (m/validator ErrorReportingConfig))
(def ^:private system-error-reporting-config-validator (m/validator SystemErrorReportingConfig))
(def ^:private error-reporting-config-explainer (m/explainer ErrorReportingConfig))
(def ^:private system-error-reporting-config-explainer (m/explainer SystemErrorReportingConfig))

(defn validate-error-reporting-config
  "Validates an error reporting configuration map."
  [config]
  (error-reporting-config-validator config))

(defn validate-system-error-reporting-config
  "Validates a complete system error reporting configuration map."
  [config]
  (system-error-reporting-config-validator config))

(defn explain-error-reporting-config
  "Provides detailed validation errors for error reporting configuration."
  [config]
  (error-reporting-config-explainer config))

(defn explain-system-error-reporting-config
  "Provides detailed validation errors for system error reporting configuration."
  [config]
  (system-error-reporting-config-explainer config))

;; =============================================================================
;; Configuration Transformation
;; =============================================================================

(def error-reporting-config-transformer
  "Transforms external configuration to internal format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :error-reporting-config}))

(defn normalize-error-reporting-config
  "Normalizes and validates an error reporting configuration map."
  [config]
  (let [normalized (m/decode ErrorReportingConfig config error-reporting-config-transformer)]
    (if (validate-error-reporting-config normalized)
      normalized
      (throw (ex-info "Invalid error reporting configuration"
                      {:errors (explain-error-reporting-config normalized)
                       :config config})))))

(defn normalize-system-error-reporting-config
  "Normalizes and validates a system error reporting configuration map."
  [config]
  (let [normalized (m/decode SystemErrorReportingConfig config error-reporting-config-transformer)]
    (if (validate-system-error-reporting-config normalized)
      normalized
      (throw (ex-info "Invalid system error reporting configuration"
                      {:errors (explain-system-error-reporting-config normalized)
                       :config config})))))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all error reporting schemas for easy access."
  {:core
   {:error-reporting-config ErrorReportingConfig
    :system-error-reporting-config SystemErrorReportingConfig
    :error-handling-config ErrorHandlingConfig
    :alerting-config AlertingConfig}
   :providers
   {:no-op NoOpErrorReportingConfig
    :sentry SentryErrorReportingConfig
    :rollbar RollbarErrorReportingConfig
    :bugsnag BugsnagErrorReportingConfig
    :webhook WebhookErrorReportingConfig
    :custom CustomErrorReportingConfig}
   :common
   {:error-provider ErrorProvider
    :error-severity ErrorSeverity
    :error-environment ErrorEnvironment
    :filter-config FilterConfig
    :context-config ContextConfig
    :retry-config RetryConfig}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))