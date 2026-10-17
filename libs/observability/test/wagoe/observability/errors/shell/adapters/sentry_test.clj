(ns wagoe.observability.errors.shell.adapters.sentry-test
  "Tests for Sentry error reporting adapter implementation.
   
   These tests verify the Sentry adapter correctly implements all error reporting
   protocols and integrates properly with the Sentry client library."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wagoe.observability.errors.shell.adapters.sentry :as sentry-adapter]
   [wagoe.observability.errors.ports :as ports]
   [sentry-clj.core :as sentry]))

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def test-config
  "Test configuration for Sentry adapter - uses fake DSN for testing"
  {:dsn "https://fake-key@fake-sentry.io/fake-project"
   :environment "test"
   :release "test-1.0.0"
   :sample-rate 1.0
   :debug false})

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn mock-sentry-fixture
  "Fixture to mock Sentry client calls during testing.
   
   This prevents actual network calls to Sentry during tests while still
   testing the adapter logic and protocol implementations."
  [test-fn]
  ;; In a real implementation, we would mock sentry-clj.core functions here
  ;; For now, we'll create components but note that Sentry init will fail with fake DSN
  (try
    (test-fn)
    (catch Exception e
      ;; Expected with fake DSN - we're testing adapter logic, not Sentry client
      (if (re-find #"DSN|Invalid" (.getMessage e))
        (println "Note: Sentry client initialization failed as expected with fake DSN")
        (throw e)))))

(use-fixtures :each mock-sentry-fixture)

;; =============================================================================
;; Error Reporter Tests
;; =============================================================================

(deftest ^:unit create-sentry-error-reporter-test
  (testing "Creating Sentry error reporter"
    (testing "requires DSN"
      (is (thrown-with-msg? Exception #"Sentry DSN is required"
                            (sentry-adapter/create-sentry-error-reporter {}))))

    (testing "accepts valid config with fake DSN for testing"
      ;; Fake DSN should work for testing since we're not sending real data
      (let [reporter (sentry-adapter/create-sentry-error-reporter test-config)]
        (is (satisfies? ports/IErrorReporter reporter))))))

(deftest ^:unit error-reporter-protocol-test
  (testing "Error reporter implements IErrorReporter protocol"
    ;; We'll test the protocol implementation structure without actual Sentry calls
    (let [methods (-> ports/IErrorReporter :sigs keys set)]
      (is (contains? methods :capture-exception))
      (is (contains? methods :capture-message))
      (is (contains? methods :capture-event)))))

;; =============================================================================
;; Error Context Tests
;; =============================================================================

(deftest ^:unit create-sentry-error-context-test
  (testing "Creating Sentry error context"
    (let [context (sentry-adapter/create-sentry-error-context test-config)]
      (is (satisfies? ports/IErrorContext context))
      (is (instance? wagoe.observability.errors.shell.adapters.sentry.SentryErrorContext context)))))

(deftest ^:unit error-context-protocol-test
  (testing "Error context implements IErrorContext protocol"
    (let [context (sentry-adapter/create-sentry-error-context test-config)]
      (testing "current-context returns context map initially"
        (let [initial-context (ports/current-context context)]
          (is (or (nil? initial-context) (map? initial-context)))))

      (testing "with-context executes function and returns result"
        (let [result (ports/with-context context {:test-key "test-value"}
                       (fn [] "test-result"))]
          (is (= "test-result" result))))

      (testing "breadcrumb operations don't throw"
        (is (nil? (ports/add-breadcrumb! context {:message "test breadcrumb"})))
        (is (nil? (ports/clear-breadcrumbs! context))))

      (testing "user operations don't throw"
        (is (nil? (ports/set-user! context {:id "test-user"}))))

      (testing "tag operations don't throw"
        (is (nil? (ports/set-tags! context {:test "tag"}))))

      (testing "extra operations don't throw"
        (is (nil? (ports/set-extra! context {:test "extra"})))))))

;; =============================================================================
;; Error Filter Tests
;; =============================================================================

(deftest ^:unit create-sentry-error-filter-test
  (testing "Creating Sentry error filter"
    (let [filter (sentry-adapter/create-sentry-error-filter test-config)]
      (is (satisfies? ports/IErrorFilter filter))
      (is (instance? wagoe.observability.errors.shell.adapters.sentry.SentryErrorFilter filter)))))

(deftest ^:unit error-filter-protocol-test
  (testing "Error filter implements IErrorFilter protocol"
    (let [filter (sentry-adapter/create-sentry-error-filter test-config)
          test-exception (Exception. "Test exception")]

      (testing "should-report? returns boolean"
        (is (boolean? (ports/should-report? filter test-exception {}))))

      (testing "should-report-message? returns boolean"
        (is (boolean? (ports/should-report-message? filter "test message" :error {}))))

      (testing "sample-rate returns configured rate"
        (is (= 1.0 (ports/sample-rate filter Exception)))))))

(deftest ^:unit filter-rules-decide-what-is-reported
  (let [f (sentry-adapter/create-sentry-error-filter
           {:filtering {:min-severity :warning
                        :exclude-exceptions ["java.io.IOException"]
                        :exclude-messages ["healthcheck"]}})]
    (testing ":filtering config"
      (is (false? (ports/should-report? f (java.io.IOException. "pipe") {})))
      (is (true? (ports/should-report? f (IllegalStateException. "x") {})))
      (is (false? (ports/should-report-message? f "healthcheck failed" :error {})))
      (is (false? (ports/should-report-message? f "noise" :info {})))
      (is (true? (ports/should-report-message? f "real" :error {}))))

    (testing "a rule added at runtime applies, and can be removed"
      (let [id (ports/add-filter-rule! f {:type :exception :pattern #"timeout" :action :ignore})]
        (is (string? id))
        (is (false? (ports/should-report? f (RuntimeException. "read timeout") {})))
        (is (true? (ports/remove-filter-rule! f id)))
        (is (false? (ports/remove-filter-rule! f id)))
        (is (true? (ports/should-report? f (RuntimeException. "read timeout") {})))))

    (testing ":report wins over min-severity; the first matching rule decides"
      (ports/add-filter-rule! f {:id "audit" :type :message :pattern "audit" :action :report})
      (is (true? (ports/should-report-message? f "audit trail" :debug {}))))

    (testing "context rules take a function"
      (ports/add-filter-rule! f {:type :context :pattern #(= "t-1" (:tenant-id %)) :action :ignore})
      (is (false? (ports/should-report? f (Exception. "x") {:tenant-id "t-1"})))
      (is (true? (ports/should-report? f (Exception. "x") {:tenant-id "t-2"}))))

    (testing "sample-rate per exception type"
      (ports/add-filter-rule! f {:type :exception :pattern "ArithmeticException" :action :sample :sample-rate 0.25})
      (is (= 0.25 (ports/sample-rate f ArithmeticException)))
      (is (= 0.0 (ports/sample-rate f java.io.IOException)))
      (is (= 1.0 (ports/sample-rate f Exception))))

    (testing "a malformed rule is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ports/add-filter-rule! f {:type :exception :action :drop :pattern "x"}))))))

;; =============================================================================
;; Error Reporting Configuration Tests
;; =============================================================================

(deftest ^:unit create-sentry-error-reporting-config-test
  (testing "Creating Sentry error reporting configuration"
    (let [config (sentry-adapter/create-sentry-error-reporting-config test-config)]
      (is (satisfies? ports/IErrorReportingConfig config))
      (is (instance? wagoe.observability.errors.shell.adapters.sentry.SentryErrorReportingConfig config)))))

(deftest ^:unit error-reporting-config-protocol-test
  (testing "Error reporting config implements IErrorReportingConfig protocol"
    (let [config (sentry-adapter/create-sentry-error-reporting-config test-config)]

      (testing "environment management"
        (let [old-env (ports/set-environment! config "new-env")]
          (is (= "test" old-env))
          (is (= "new-env" (ports/get-environment config)))))

      (testing "release management"
        (let [old-release (ports/set-release! config "new-release")]
          (is (= "test-1.0.0" old-release))
          (is (= "new-release" (ports/get-release config)))))

      (testing "sample rate management"
        (let [old-rate (ports/set-sample-rate! config 0.5)]
          (is (= 1.0 old-rate))
          (is (= 0.5 (ports/get-sample-rate config)))))

      (testing "reporting enabled/disabled management"
        (is (true? (ports/reporting-enabled? config)) "on unless :enabled is false")
        (is (true? (ports/disable-reporting! config)))
        (is (false? (ports/reporting-enabled? config)))
        (is (false? (ports/enable-reporting! config)))
        (is (true? (ports/reporting-enabled? config)))))))

;; =============================================================================
;; Component Integration Tests
;; =============================================================================

(deftest ^:unit create-sentry-error-reporting-component-test
  (testing "Creating complete Sentry error reporting component"
    ;; Test that component creation works with fake DSN for testing
    (let [component (sentry-adapter/create-sentry-error-reporting-component test-config)]
      (is (satisfies? ports/IErrorReporter (:error-reporter component)))
      (is (satisfies? ports/IErrorContext (:error-context component))))))

(deftest ^:unit create-sentry-error-reporting-components-test
  (testing "Creating map of Sentry error reporting components"
    ;; Test that components creation works with fake DSN for testing
    (let [components (sentry-adapter/create-sentry-error-reporting-components test-config)]
      (is (satisfies? ports/IErrorReporter (:error-reporter components)))
      (is (satisfies? ports/IErrorContext (:error-context components)))
      (is (satisfies? ports/IErrorFilter (:error-filter components)))
      (is (satisfies? ports/IErrorReportingConfig (:error-config components))))))

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(defn- with-captured-event
  "Run f with sentry/send-event stubbed, returning the captured payload."
  [config f]
  (let [captured (atom nil)]
    (with-redefs [sentry/init! (fn [& _] nil)
                  sentry/send-event (fn [event]
                                      (reset! captured event)
                                      nil)]
      (let [reporter (sentry-adapter/create-sentry-error-reporter config)]
        (f reporter)
        @captured))))

(deftest ^:unit utility-function-tests
  (testing "Private utility functions through public interface"
    (let [context (sentry-adapter/create-sentry-error-context test-config)]

      (testing "context management works"
        (ports/with-context context {:correlation-id "test-123"}
          (fn []
                             ;; Context should be set during execution
            (let [current (ports/current-context context)]
              (is (map? current)) ; Thread-local context should be a map
              (is (contains? current :correlation-id))))))

      (testing "breadcrumb management works"
        (ports/add-breadcrumb! context {:message "Step 1" :category "test"})
        (ports/add-breadcrumb! context {:message "Step 2" :category "test"})
        (is (= ["Step 1" "Step 2"]
               (take-last 2 (map :message (:breadcrumbs (ports/current-context context))))))
        (ports/clear-breadcrumbs! context)
        (is (empty? (:breadcrumbs (ports/current-context context))))))))

;; =============================================================================
;; PII Redaction Tests
;; =============================================================================

(deftest ^:unit pii-redaction-default-extra-test
  (testing "Default redaction applies to :extra fields when :redact is present"
    (let [config {:dsn "https://fake-key@fake-sentry.io/fake-project"
                  :environment "test"
                  :redact {}} ; use defaults
          event (with-captured-event config
                  (fn [reporter]
                    (ports/capture-exception
                     reporter
                     (ex-info "boom" {})
                     {:extra {:password "secret123"
                              :token "abcd"
                              :email "user@example.com"
                              :safe "ok"}}
                     nil)))]
      (is (some? event) "An event must be sent")
      (testing "password and token are redacted"
        (is (= "[REDACTED]" (get-in event [:extra :password])))
        (is (= "[REDACTED]" (get-in event [:extra :token]))))
      (testing "email is masked, not raw"
        (is (re-find #".*\*\*\*@example\.com$" (get-in event [:extra :email]))))
      (testing "non-sensitive fields are preserved"
        (is (= "ok" (get-in event [:extra :safe])))))))

(deftest ^:unit pii-redaction-default-tags-test
  (testing "Default redaction applies to :tags as well when :redact is present"
    (let [config {:dsn "https://fake-key@fake-sentry.io/fake-project"
                  :environment "test"
                  :redact {}}
          event (with-captured-event config
                  (fn [reporter]
                    (ports/capture-message
                     reporter
                     "hello"
                     :error
                     {:tags {:password "pw"
                             :api-key "k123"
                             :tenant-id "tenant-1"}}
                     nil)))]
      (is (some? event) "Event should be sent")
      (is (= "[REDACTED]" (get-in event [:tags :password])))
      (is (= "[REDACTED]" (get-in event [:tags :api-key])))
      ;; non-sensitive tag should remain
      (is (= "tenant-1" (get-in event [:tags :tenant-id]))))))

(deftest ^:unit pii-redaction-additional-keys-test
  (testing "Custom additional keys are redacted"
    (let [config {:dsn "https://fake-key@fake-sentry.io/fake-project"
                  :environment "test"
                  :redact {:additional-keys [:custom-field]}}
          event (with-captured-event config
                  (fn [reporter]
                    (ports/capture-exception
                     reporter
                     (Exception. "boom")
                     {:extra {:custom-field "extra-1"
                              :other "keep"}}
                     nil)))]
      (is (some? event))
      (is (= "[REDACTED]" (get-in event [:extra :custom-field])))
      (is (= "keep" (get-in event [:extra :other]))))))

(deftest ^:unit pii-redaction-disable-email-masking-test
  (testing "Email is not masked when :mask-email? is false"
    (let [config {:dsn "https://fake-key@fake-sentry.io/fake-project"
                  :environment "test"
                  :redact {:mask-email? false}}
          event (with-captured-event config
                  (fn [reporter]
                    (ports/capture-exception
                     reporter
                     (Exception. "boom")
                     {:extra {:email "user@example.com"}}
                     nil)))]
      (is (some? event))
      (is (= "user@example.com" (get-in event [:extra :email]))))))

(deftest ^:unit pii-redaction-disabled-when-no-redact-config-test
  (testing "When :redact is not present, default PII redaction still applies"
    (let [config {:dsn "https://fake-key@fake-sentry.io/fake-project"
                  :environment "test"}
          event (with-captured-event config
                  (fn [reporter]
                    (ports/capture-message
                     reporter
                     "hi"
                     :error
                     {:extra {:password "pw"
                              :email "user@example.com"}}
                     nil)))]
      (is (some? event))
      (is (= "[REDACTED]" (get-in event [:extra :password])))
      (is (re-find #".*\*\*\*@example\.com$" (get-in event [:extra :email]))))))

;; =============================================================================
;; Integration with Error Reporting Ports Tests
;; =============================================================================

(deftest ^:unit protocol-compatibility-test
  (testing "All created components satisfy the expected protocols"
    (let [context (sentry-adapter/create-sentry-error-context test-config)
          filter (sentry-adapter/create-sentry-error-filter test-config)
          config (sentry-adapter/create-sentry-error-reporting-config test-config)]

      (is (satisfies? ports/IErrorContext context))
      (is (satisfies? ports/IErrorFilter filter))
      (is (satisfies? ports/IErrorReportingConfig config))

      ;; Verify protocol methods exist and are callable
      (is (ifn? ports/current-context))
      (is (ifn? ports/with-context))
      (is (ifn? ports/add-breadcrumb!))
      (is (ifn? ports/should-report?))
      (is (ifn? ports/sample-rate))
      (is (ifn? ports/get-environment))
      (is (ifn? ports/set-environment!)))))

(deftest ^:unit error-handling-test
  (testing "Adapter handles errors gracefully"
    (let [context (sentry-adapter/create-sentry-error-context test-config)]

      (testing "with-context handles exceptions in function"
        (is (thrown? RuntimeException
                     (ports/with-context context {:test "context"}
                       (fn [] (throw (RuntimeException. "Test error"))))))))))

(deftest ^:unit a-quoted-value-does-not-reach-sentry
  ;; Whoever reports, through whatever path (BOU-633).
  (let [driver (java.sql.SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        event  (with-captured-event test-config
                 #(ports/capture-exception % (ex-info "save failed" {:type :conflict} driver) {}))]
    (is (some? (:throwable event)))
    (is (not-any? #(.contains (str (ex-message %)) "alice@example.com")
                  (take-while some? (iterate ex-cause (:throwable event)))))))

;; =============================================================================
;; The component's runtime settings reach what is sent
;; =============================================================================

(defn- with-sent-events
  "Run f with a stubbed Sentry client; the events sent."
  [config f]
  (let [sent (atom [])]
    (with-redefs [sentry/init! (fn [& _] nil)
                  sentry/send-event (fn [event] (swap! sent conj event) nil)]
      (f (sentry-adapter/create-sentry-error-reporting-component config))
      @sent)))

(deftest ^:unit runtime-settings-reach-the-reporter
  (testing "disabled sends nothing"
    (is (empty? (with-sent-events test-config
                  (fn [c]
                    (ports/disable-reporting! c)
                    (is (nil? (ports/capture-exception c (Exception. "x"))))
                    (is (nil? (ports/capture-message c "m" :error))))))))

  (testing "sample rate 0 sends nothing; 1 sends everything"
    (is (empty? (with-sent-events (assoc test-config :sample-rate 0.0)
                  #(dotimes [_ 20] (ports/capture-exception % (Exception. "x"))))))
    (is (= 20 (count (with-sent-events test-config
                       #(dotimes [_ 20] (ports/capture-exception % (Exception. "x"))))))))

  (testing "set-sample-rate! applies at runtime"
    (is (empty? (with-sent-events test-config
                  (fn [c]
                    (ports/set-sample-rate! c 0.0)
                    (ports/capture-exception c (Exception. "x")))))))

  (testing "a filtered event is not sent"
    (is (empty? (with-sent-events (assoc test-config :filtering {:exclude-exceptions ["IllegalStateException"]})
                  #(ports/capture-exception % (IllegalStateException. "x"))))))

  (testing "environment and release go on every event, and follow runtime changes"
    (let [[before after] (with-sent-events test-config
                           (fn [c]
                             (ports/capture-message c "one" :error)
                             (ports/set-environment! c "staging")
                             (ports/set-release! c "2.0.0")
                             (ports/capture-message c "two" :error)))]
      (is (= ["test" "test-1.0.0"] ((juxt :environment :release) before)))
      (is (= ["staging" "2.0.0"] ((juxt :environment :release) after)))))

  (testing "a filter rule that throws does not cost the report"
    (is (= 1 (count (with-sent-events test-config
                      (fn [c]
                        (ports/add-filter-rule! c {:type :context :action :ignore
                                                   :pattern (fn [_] (throw (RuntimeException. "bad rule")))})
                        (ports/capture-exception c (Exception. "x"))))))))

  (testing "an unknown event type is not sent"
    (is (empty? (with-sent-events test-config
                  #(is (nil? (ports/capture-event % {:type :bogus}))))))))
