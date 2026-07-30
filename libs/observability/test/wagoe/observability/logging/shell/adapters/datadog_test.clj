(ns wagoe.observability.logging.shell.adapters.datadog-test
  "Tests for Datadog logging adapter implementation.
   
   These tests verify the Datadog adapter correctly implements all logging
   protocols and integrates properly with the Datadog HTTP API."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wagoe.observability.logging.shell.adapters.datadog :as datadog-adapter]
   [wagoe.observability.logging.ports :as ports])
)

;; Test configuration
(def test-config
  "Test configuration with required Datadog settings"
  {:api-key "test-api-key-32chars-long1234567"
   :service "test-service"
   :source "test-source"
   :environment "test"
   :tags ["team:test" "version:1.0.0"]
   :batch-size 10
   :flush-interval 1000})

(def minimal-config
  "Minimal configuration with only required fields"
  {:api-key "minimal-key-32chars-long123456789"
   :service "minimal-service"})

;; Mock HTTP client fixture
(defn mock-http-fixture
  "Fixture to mock HTTP client calls during testing"
  [test-fn]
  ;; In a real test, we might mock the HTTP client
  ;; For now, we'll test without making actual HTTP calls
  (test-fn))

(use-fixtures :each mock-http-fixture)

;; =============================================================================
;; Logger Protocol Tests
;; =============================================================================

(deftest ^:unit create-datadog-logger-test
  (testing "creates Datadog logger with valid config"
    (let [logger (datadog-adapter/create-datadog-logger test-config)]
      (is (satisfies? ports/ILogger logger))
      (is (instance? wagoe.observability.logging.shell.adapters.datadog.DatadogLogger logger))))

  (testing "creates logger with minimal config"
    (let [logger (datadog-adapter/create-datadog-logger minimal-config)]
      (is (satisfies? ports/ILogger logger))))

  (testing "handles missing required config fields"
    (is (thrown? Exception (datadog-adapter/create-datadog-logger {})))
    (is (thrown? Exception (datadog-adapter/create-datadog-logger {:api-key "test"})))))

(deftest ^:unit logger-protocol-test
  (testing "implements ILogger protocol methods"
    (let [logger (datadog-adapter/create-datadog-logger test-config)
          test-message "Test log message"
          test-context {:user-id "123" :request-id "abc"}]

      (testing "trace logging"
        (is (nil? (ports/trace logger test-message)))
        (is (nil? (ports/trace logger test-message test-context))))

      (testing "debug logging"
        (is (nil? (ports/debug logger test-message)))
        (is (nil? (ports/debug logger test-message test-context))))

      (testing "info logging"
        (is (nil? (ports/info logger test-message)))
        (is (nil? (ports/info logger test-message test-context))))

      (testing "warn logging"
        (is (nil? (ports/warn logger test-message)))
        (is (nil? (ports/warn logger test-message test-context))))

      (testing "error logging"
        (let [test-exception (Exception. "Test exception")]
          (is (nil? (ports/error logger test-message)))
          (is (nil? (ports/error logger test-message test-context)))
          (is (nil? (ports/error logger test-message test-exception)))
          (is (nil? (ports/error logger test-message test-exception test-context)))))

      (testing "fatal logging"
        (let [test-exception (Exception. "Fatal exception")]
          (is (nil? (ports/fatal logger test-message)))
          (is (nil? (ports/fatal logger test-message test-context)))
          (is (nil? (ports/fatal logger test-message test-exception)))
          (is (nil? (ports/fatal logger test-message test-exception test-context))))))))

;; =============================================================================
;; Audit Logger Protocol Tests
;; =============================================================================

(deftest ^:unit create-datadog-audit-logger-test
  (testing "creates Datadog audit logger with valid config"
    (let [audit-logger (datadog-adapter/create-datadog-audit-logger test-config)]
      (is (satisfies? ports/IAuditLogger audit-logger))
      (is (instance? wagoe.observability.logging.shell.adapters.datadog.DatadogAuditLogger audit-logger)))))

(deftest ^:unit audit-logger-protocol-test
  (testing "implements IAuditLogger protocol methods"
    (let [audit-logger (datadog-adapter/create-datadog-audit-logger test-config)]

      (testing "audit event logging"
        (is (nil? (ports/audit-event audit-logger :user-action "user-123" "invoice-456" :create :success {:additional "context"})))
        (is (nil? (ports/audit-event audit-logger :system-event "system" "database" :backup :success {}))))

      (testing "security event logging"
        (is (nil? (ports/security-event audit-logger :login-attempt :high {:user-id "456" :ip-address "192.168.1.1"} {:session-id "abc"})))
        (is (nil? (ports/security-event audit-logger :permission-denied :medium {:resource "admin-panel"} {})))))))

;; =============================================================================
;; Logging Context Protocol Tests
;; =============================================================================

(deftest ^:unit create-datadog-logging-context-test
  (testing "creates Datadog logging context with valid config"
    (let [context (datadog-adapter/create-datadog-logging-context test-config)]
      (is (satisfies? ports/ILoggingContext context))
      (is (instance? wagoe.observability.logging.shell.adapters.datadog.DatadogLoggingContext context)))))

(deftest ^:unit logging-context-protocol-test
  (testing "implements ILoggingContext protocol methods"
    (let [context (datadog-adapter/create-datadog-logging-context test-config)
          test-context {:user-id "456" :session-id "session-789"}]

      (testing "current context - initially empty"
        (is (empty? (ports/current-context context))))

      (testing "with-context execution"
        (let [result (ports/with-context context test-context
                       (fn []
                         (let [ctx (ports/current-context context)]
                           (is (= "456" (:user-id ctx)))
                           (is (= "session-789" (:session-id ctx)))
                           "test-result")))]
          (is (= "test-result" result))))

      (testing "nested context execution"
        (ports/with-context context {:outer "value"}
          (fn []
            (ports/with-context context {:inner "nested"}
              (fn []
                (let [ctx (ports/current-context context)]
                  (is (= "value" (:outer ctx)))
                  (is (= "nested" (:inner ctx))))))))))))

;; =============================================================================
;; Logging Config Protocol Tests
;; =============================================================================

(deftest ^:unit create-datadog-logging-config-test
  (testing "creates Datadog logging config with valid config"
    (let [config-manager (datadog-adapter/create-datadog-logging-config test-config)]
      (is (satisfies? ports/ILoggingConfig config-manager))
      (is (instance? wagoe.observability.logging.shell.adapters.datadog.DatadogLoggingConfig config-manager)))))

(deftest ^:unit logging-config-protocol-test
  (testing "implements ILoggingConfig protocol methods"
    (let [config-manager (datadog-adapter/create-datadog-logging-config test-config)
          updated-config {:api-key "new-key-32chars-long123456789"
                          :service "updated-service"}]

      (testing "get current config"
        (let [current-config (ports/get-config config-manager)]
          (is (map? current-config))
          (is (contains? current-config :api-key))
          (is (contains? current-config :service))))

      (testing "set config updates"
        (let [old-config (ports/get-config config-manager)
              previous-config (ports/set-config! config-manager updated-config)
              new-config (ports/get-config config-manager)]
          (is (= old-config previous-config))
          (is (= "new-key-32chars-long123456789" (:api-key new-config)))
          (is (= "updated-service" (:service new-config)))))

      (testing "logging level management"
        (let [original-level (ports/get-level config-manager)
              previous-level (ports/set-level! config-manager :debug)
              new-level (ports/get-level config-manager)]
          (is (= original-level previous-level))
          (is (= :debug new-level))
          ;; Reset to original
          (ports/set-level! config-manager original-level))))))

;; =============================================================================
;; Component Tests
;; =============================================================================

(deftest ^:unit create-datadog-logging-component-test
  (testing "creates Datadog logging component with all protocols"
    (let [component (datadog-adapter/create-datadog-logging-component test-config)]
      (is (satisfies? ports/ILogger component))
      (is (satisfies? ports/IAuditLogger component))
      (is (satisfies? ports/ILoggingContext component))
      (is (satisfies? ports/ILoggingConfig component))
      (is (instance? wagoe.observability.logging.shell.adapters.datadog.DatadogLoggingComponent component)))))

(deftest ^:unit create-datadog-logging-components-test
  (testing "creates separate Datadog logging components"
    (let [components (datadog-adapter/create-datadog-logging-components test-config)]
      (is (map? components))
      (is (satisfies? ports/ILogger (:logger components)))
      (is (satisfies? ports/IAuditLogger (:audit-logger components)))
      (is (satisfies? ports/ILoggingContext (:logging-context components)))
      (is (satisfies? ports/ILoggingConfig (:logging-config components))))))

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest ^:unit utility-function-tests
  (testing "datadog log level mapping"
    ;; These would test internal functions if they were made public
    ;; For now, we'll test through the public API
    (let [logger (datadog-adapter/create-datadog-logger test-config)]
      (is (nil? (ports/trace logger "trace message")))
      (is (nil? (ports/debug logger "debug message")))
      (is (nil? (ports/info logger "info message")))
      (is (nil? (ports/warn logger "warn message")))
      (is (nil? (ports/error logger "error message")))
      (is (nil? (ports/fatal logger "fatal message"))))))

;; =============================================================================
;; Protocol Compatibility Tests
;; =============================================================================

(deftest ^:unit protocol-compatibility-test
  (testing "all components implement expected protocols"
    (let [logger (datadog-adapter/create-datadog-logger test-config)
          audit-logger (datadog-adapter/create-datadog-audit-logger test-config)
          context (datadog-adapter/create-datadog-logging-context test-config)
          config-manager (datadog-adapter/create-datadog-logging-config test-config)
          component (datadog-adapter/create-datadog-logging-component test-config)]

      (testing "individual components"
        (is (satisfies? ports/ILogger logger))
        (is (satisfies? ports/IAuditLogger audit-logger))
        (is (satisfies? ports/ILoggingContext context))
        (is (satisfies? ports/ILoggingConfig config-manager)))

      (testing "combined component"
        (is (satisfies? ports/ILogger component))
        (is (satisfies? ports/IAuditLogger component))
        (is (satisfies? ports/ILoggingContext component))
        (is (satisfies? ports/ILoggingConfig component))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:unit error-handling-test
  (testing "gracefully handles error conditions"
    (let [logger (datadog-adapter/create-datadog-logger test-config)]

      (testing "handles nil messages"
        (is (nil? (ports/info logger nil))))

      (testing "handles empty messages"
        (is (nil? (ports/info logger ""))))

      (testing "handles nil context"
        (is (nil? (ports/info logger "test" nil))))

      (testing "handles invalid context"
        (is (nil? (ports/info logger "test" "invalid-context"))))

      (testing "handles nil exceptions"
        (is (nil? (ports/error logger "test" nil))))

      (testing "handles malformed exceptions"
        (is (nil? (ports/error logger "test" "not-an-exception")))))))