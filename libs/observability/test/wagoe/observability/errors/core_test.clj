(ns wagoe.observability.errors.core-test
  "Tests for error reporting with enhanced context integration"
  (:require [wagoe.observability.errors.core :as error-reporting]
            [wagoe.observability.errors.ports :as er-ports]
            [wagoe.platform.core.http.problem-details :as pd]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- create-test-problem-details-context
  "Create a test context from Problem Details"
  [& {:keys [user-id tenant-id trace-id operation additional]}]
  (merge {:user-id (or user-id "test-user")
          :tenant-id (or tenant-id "test-tenant")
          :trace-id (or trace-id (str (UUID/randomUUID)))
          :timestamp (Instant/now)
          :environment "test"}
         (when operation {:operation operation})
         (or additional {})))

(defn- create-test-exception
  "Create a test exception with optional data"
  [& {:keys [message data cause]}]
  (let [ex (ex-info (or message "Test exception") (or data {}))]
    (if cause
      (ex-info (or message "Test exception") (or data {}) cause)
      ex)))

(defrecord MockErrorReportingService [reports]
  er-ports/IErrorReporter

  (capture-exception [_ exception]
    (let [event-id (str (UUID/randomUUID))]
      (swap! reports conj {:type :exception
                           :exception exception
                           :message (ex-message exception)
                           :data (ex-data exception)
                           :event-id event-id})
      event-id))

  (capture-exception [_ exception context]
    (let [event-id (str (UUID/randomUUID))
          ;; Extract cause chain from context if present
          base-error {:type :exception
                      :exception exception
                      :message (ex-message exception)
                      :data (ex-data exception)
                      :context context
                      :event-id event-id}
          ;; Add cause at top level if it exists in context
          error-with-cause (if-let [cause (:cause context)]
                             (assoc base-error :cause cause)
                             base-error)]
      (swap! reports conj error-with-cause)
      event-id))

  (capture-exception [_ exception context tags]
    (let [event-id (str (UUID/randomUUID))
          base-error {:type :exception
                      :exception exception
                      :message (ex-message exception)
                      :data (ex-data exception)
                      :context context
                      :tags tags
                      :event-id event-id}
          ;; Add cause at top level if it exists in context
          error-with-cause (if-let [cause (:cause context)]
                             (assoc base-error :cause cause)
                             base-error)]
      (swap! reports conj error-with-cause)
      event-id))

  (capture-message [_ message level]
    (let [event-id (str (UUID/randomUUID))]
      (swap! reports conj {:type :message
                           :message message
                           :level level
                           :event-id event-id})
      event-id))

  (capture-message [_ message level context]
    (let [event-id (str (UUID/randomUUID))]
      (swap! reports conj {:type :message
                           :message message
                           :level level
                           :context context
                           :event-id event-id})
      event-id))

  (capture-message [_ message level context tags]
    (let [event-id (str (UUID/randomUUID))]
      (swap! reports conj {:type :message
                           :message message
                           :level level
                           :context context
                           :tags tags
                           :event-id event-id})
      event-id))

  (capture-event [_ event-map]
    (let [event-id (str (UUID/randomUUID))]
      (swap! reports conj (assoc event-map :event-id event-id))
      event-id)))

(defn create-mock-error-service []
  (->MockErrorReportingService (atom [])))

(defn get-reported-errors [mock-service]
  @(:reports mock-service))

;; =============================================================================
;; Context Conversion Tests
;; =============================================================================

(deftest ^:unit test-error-context->reporting-context
  (testing "converts Problem Details context to reporting format"
    (let [pd-context (create-test-problem-details-context
                      :user-id "user-123"
                      :tenant-id "tenant-456"
                      :trace-id "trace-789"
                      :additional {:uri "/api/users"
                                   :method "POST"
                                   :ip-address "192.168.1.100"})
          reporting-context (error-reporting/error-context->reporting-context pd-context)]

      (is (= "user-123" (:user-id reporting-context)))
      (is (= "tenant-456" (:tenant-id reporting-context)))
      (is (= "trace-789" (:trace-id reporting-context)))
      (is (= "/api/users" (:uri reporting-context)))
      (is (= "POST" (:method reporting-context)))
      (is (= "192.168.1.100" (:ip-address reporting-context)))
      (is (contains? reporting-context :timestamp))
      (is (= "test" (:environment reporting-context)))))

  (testing "handles minimal context gracefully"
    (let [minimal-context {:timestamp (Instant/now)}
          reporting-context (error-reporting/error-context->reporting-context minimal-context)]

      (is (nil? (:user-id reporting-context)))
      (is (nil? (:tenant-id reporting-context)))
      (is (contains? reporting-context :timestamp))))

  (testing "preserves CLI-specific context"
    (let [cli-context (create-test-problem-details-context
                       :operation "create-user"
                       :additional {:process-id 12345
                                    :cli-version "1.2.3"
                                    :args ["--name" "John"]})
          reporting-context (error-reporting/error-context->reporting-context cli-context)]

      (is (= "create-user" (:operation reporting-context)))
      (is (= 12345 (:process-id reporting-context)))
      (is (= "1.2.3" (:cli-version reporting-context)))
      (is (= ["--name" "John"] (:args reporting-context)))))

  (testing "handles nested data structures"
    (let [complex-context (create-test-problem-details-context
                           :additional {:request-headers {"user-agent" "test/1.0"
                                                          "authorization" "Bearer token"}
                                        :response-metadata {:duration-ms 150
                                                            :cache-hit false}})
          reporting-context (error-reporting/error-context->reporting-context complex-context)]

      (is (= {"user-agent" "test/1.0" "authorization" "Bearer token"}
             (:request-headers reporting-context)))
      (is (= {:duration-ms 150 :cache-hit false}
             (:response-metadata reporting-context))))))

;; =============================================================================
;; Enhanced Error Reporting Tests
;; =============================================================================

(deftest ^:unit test-report-enhanced-application-error
  (testing "reports application error with enhanced context"
    (let [mock-service (create-mock-error-service)
          exception (create-test-exception :message "Business logic error"
                                           :data {:validation-field "email"})
          context (create-test-problem-details-context :user-id "user-789")

          result (error-reporting/report-enhanced-application-error
                  mock-service exception "Business logic error" {} context)]

      (is (string? result))
      (is (not (empty? result)))

      (let [reported-errors (get-reported-errors mock-service)
            error-data (first reported-errors)]
        (is (= 1 (count reported-errors)))
        (is (= "Business logic error" (:message error-data)))
        (is (= {:validation-field "email"} (:data error-data)))
        (is (= "user-789" (get-in error-data [:context :user-id])))
        (is (contains? (:context error-data) :timestamp)))))

  (testing "works without context"
    (let [mock-service (create-mock-error-service)
          exception (create-test-exception :message "Simple error")

          result (error-reporting/report-enhanced-application-error
                  mock-service exception "Simple error" {} nil)]

      (is (string? result))

      (let [reported-errors (get-reported-errors mock-service)
            error-data (first reported-errors)]
        (is (= "Simple error" (:message error-data)))
        (is (nil? (get-in error-data [:context :enhanced-context]))))))

  (testing "preserves exception cause chain"
    (let [mock-service (create-mock-error-service)
          root-cause (RuntimeException. "Database timeout")
          intermediate (ex-info "Connection failed" {:db "users"} root-cause)
          main-exception (ex-info "User lookup failed" {:user-id 123} intermediate)
          context (create-test-problem-details-context)

          result (error-reporting/report-enhanced-application-error
                  mock-service main-exception "User lookup failed" {} context)]

      (is (string? result))

      (let [error-data (first (get-reported-errors mock-service))]
        (is (= "User lookup failed" (:message error-data)))
        (is (= {:user-id 123} (:data error-data)))
        (is (= "Connection failed" (get-in error-data [:cause :message])))
        (is (= {:db "users"} (get-in error-data [:cause :data])))
        (is (= "Database timeout" (get-in error-data [:cause :cause :message]))))))

  (testing "handles different exception types"
    (let [mock-service (create-mock-error-service)
          runtime-ex (RuntimeException. "Runtime error")
          illegal-arg-ex (IllegalArgumentException. "Bad argument")
          context (create-test-problem-details-context)]

      (error-reporting/report-enhanced-application-error mock-service runtime-ex "Runtime error" {} context)
      (error-reporting/report-enhanced-application-error mock-service illegal-arg-ex "Bad argument" {} context)

      (let [errors (get-reported-errors mock-service)]
        (is (= 2 (count errors)))
        (is (= "Runtime error" (:message (first errors))))
        (is (= "Bad argument" (:message (second errors))))))))

;; =============================================================================
;; Problem Details Integration Tests
;; =============================================================================

(deftest ^:unit test-problem-details-error-reporting-integration
  (testing "Problem Details context flows correctly to error reporting"
    (let [mock-service (create-mock-error-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)

          request {:request-method :post
                   :uri "/api/users"
                   :headers {"x-user-id" (str user-id)
                             "x-tenant-id" (str tenant-id)
                             "x-correlation-id" "corr-123"
                             "user-agent" "test-client/2.0"}}

          context (pd/request->context* request {:environment "test"
                                                 :timestamp (Instant/parse "2026-04-10T12:00:00Z")})
          enriched-context (pd/enrich-context context {:environment "test"})
          exception (create-test-exception :message "Integration test error"
                                           :data {:operation "create-user"})

          result (error-reporting/report-enhanced-application-error
                  mock-service exception "Integration test error" {} enriched-context)]

      ;; Result should be an event ID string
      (is (string? result))
      (is (not (empty? result)))

      (let [error-data (first (get-reported-errors mock-service))
            reported-context (:context error-data)]
        (is (= "Integration test error" (:message error-data)))
        (is (= {:operation "create-user"} (:data error-data)))
        (is (= (str user-id) (:user-id reported-context)))
        (is (= (str tenant-id) (:tenant-id reported-context)))
        (is (= "corr-123" (:trace-id reported-context)))
        (is (= "test-client/2.0" (:user-agent reported-context)))
        (is (= "/api/users" (:uri reported-context)))
        (is (= "POST" (:method reported-context))))))

  (testing "CLI context flows correctly to error reporting"
    (let [mock-service (create-mock-error-service)
          cli-context (pd/cli-context* {:environment "development"
                                        :timestamp (Instant/parse "2026-04-10T12:00:00Z")
                                        :process-id "1234"}
                                       {:operation "delete-user"
                                        :user-id "admin-user"
                                        :args ["--id" "123" "--force"]})
          exception (create-test-exception :message "CLI operation failed")

          result (error-reporting/report-enhanced-application-error
                  mock-service exception "CLI operation failed" {} cli-context)]

      ;; Result should be an event ID string
      (is (string? result))
      (is (not (empty? result)))

      (let [error-data (first (get-reported-errors mock-service))
            reported-context (:context error-data)]
        (is (= "CLI operation failed" (:message error-data)))
        (is (= "delete-user" (:operation reported-context)))
        (is (= "admin-user" (:user-id reported-context)))
        (is (= ["--id" "123" "--force"] (:args reported-context)))
        (is (contains? reported-context :process-id)))))

  (testing "enhanced error response preserves reporting context"
    (let [mock-service (create-mock-error-service)
          exception (create-test-exception :message "Response context test")
          context (create-test-problem-details-context
                   :trace-id "response-trace-456")

          response (pd/exception->problem-response exception :context context)
          event-id (error-reporting/report-enhanced-application-error
                    mock-service exception "Response context test" {} context)]

      (is (= 500 (:status response)))

      ;; Check if response has correlation header
      (let [correlation-header (get-in response [:headers "X-Correlation-ID"])]
        (when correlation-header
          (is (= "response-trace-456" correlation-header))))

      ;; Parse response body if it exists. Untyped exceptions map to a 5xx, whose
      ;; title is now generalized to hide the raw message (BOU-161); the reporting
      ;; context (trace-id) must still be preserved in the response.
      (when-let [body-str (:body response)]
        (let [body (json/parse-string body-str keyword)]
          (when (:title body)
            (is (= "Internal Server Error" (:title body))))
          (when (get-in body [:context :trace-id])
            (is (= "response-trace-456" (get-in body [:context :trace-id]))))))

      ;; Verify error was reported correctly
      (is (string? event-id))
      (is (not (empty? event-id)))

      (let [error-data (first (get-reported-errors mock-service))]
        (is (= "response-trace-456" (get-in error-data [:context :trace-id])))))))

;; =============================================================================
;; Error Correlation Tests
;; =============================================================================

(deftest ^:unit test-error-correlation
  (testing "correlates HTTP request errors with reporting context"
    (let [mock-service (create-mock-error-service)
          correlation-id "correlation-12345"
          request-id "request-67890"

          request {:request-method :put
                   :uri "/api/users/123"
                   :headers {"x-correlation-id" correlation-id
                             "x-request-id" request-id
                             "x-user-id" "user-456"}}

          context (pd/request->context* request {:timestamp (Instant/parse "2026-04-10T12:00:00Z")})
          exception (create-test-exception :message "Correlated error")

          problem-response (pd/exception->problem-response exception :context context)
          event-id (error-reporting/report-enhanced-application-error
                    mock-service exception "Correlated error" {} context)]

      ;; Verify the problem response structure  
      (is (= 500 (:status problem-response)))
      (is (= "application/problem+json" (get-in problem-response [:headers "Content-Type"])))

      ;; Parse the response body and check correlation information
      (let [error-data (first (get-reported-errors mock-service))
            response-body (json/parse-string (:body problem-response))]

        ;; Check error reporting captured the correlation correctly
        (is (string? event-id))
        (is (= correlation-id (get-in error-data [:context :trace-id])))
        (is (= request-id (get-in error-data [:context :request-id])))

        ;; Check problem details response has correlation in the instance field.
        ;; The untyped exception yields a 5xx whose detail is now generic to avoid
        ;; leaking the raw message (BOU-161); correlation must still be present.
        (is (= correlation-id (get-in response-body ["instance" "trace-id"])))
        (is (= "user-456" (get-in response-body ["instance" "user-id"])))
        (is (= "Internal Server Error" (get response-body "detail"))))))

  (testing "maintains correlation across service boundaries"
    (let [mock-service (create-mock-error-service)
          trace-id "distributed-trace-999"

          http-context {:trace-id trace-id :user-id "web-user" :uri "/api/process"}
          cli-context {:trace-id trace-id :user-id "cli-user" :operation "background-job"}

          http-exception (create-test-exception :message "HTTP error")
          cli-exception (create-test-exception :message "CLI error")]

      (error-reporting/report-enhanced-application-error
       mock-service http-exception "HTTP error" {} http-context)
      (error-reporting/report-enhanced-application-error
       mock-service cli-exception "CLI error" {} cli-context)

      (let [errors (get-reported-errors mock-service)]
        (is (= 2 (count errors)))
        (is (= trace-id (get-in (first errors) [:context :trace-id])))
        (is (= trace-id (get-in (second errors) [:context :trace-id])))
        (is (= "web-user" (get-in (first errors) [:context :user-id])))
        (is (= "cli-user" (get-in (second errors) [:context :user-id])))))))

(deftest ^:unit test-trace-id-debug
  (testing "isolate trace-id issue"
    (let [mock-service (create-mock-error-service)
          correlation-id "correlation-12345"
          request-id "request-67890"
          request {:request-method :put
                   :uri "/api/users/123"
                   :headers {"x-correlation-id" correlation-id
                             "x-request-id" request-id
                             "x-user-id" "user-456"}}
          context (pd/request->context* request {:timestamp (Instant/parse "2026-04-10T12:00:00Z")})
          exception (create-test-exception :message "Test error")
          _ (error-reporting/report-enhanced-application-error
             mock-service exception "Test error" {} context)
          error-data (first (get-reported-errors mock-service))]
      (is (= correlation-id (get-in error-data [:context :trace-id]))))))
