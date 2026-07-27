(ns wagoe.platform.shell.utils.error-handling-test
  "Tests for error handling utilities with context preservation"
  (:require [wagoe.platform.shell.utils.error-handling :as eh]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json])
  (:import [java.util UUID]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- create-test-request
  "Create a test HTTP request"
  [& {:keys [user-id tenant-id uri method headers]}]
  {:request-method (or method :get)
   :uri (or uri "/api/test")
   :headers (merge {"user-agent" "test-client/1.0"
                    "x-forwarded-for" "10.0.0.1"
                    "x-correlation-id" (str (UUID/randomUUID))}
                   (when user-id {"x-user-id" (str user-id)})
                   (when tenant-id {"x-tenant-id" (str tenant-id)})
                   (or headers {}))})

(defn- create-failing-handler
  "Create a handler that throws an exception"
  [exception]
  (fn [_request] (throw exception)))

(defn- create-successful-handler
  "Create a handler that returns a successful response"
  [response]
  (fn [_request] response))

;; =============================================================================
;; HTTP Error Context Middleware Tests
;; =============================================================================

(deftest ^:unit test-wrap-error-context
  (testing "captures context early and makes it available"
    (let [user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          request (create-test-request :user-id user-id :tenant-id tenant-id)
          context-atom (atom nil)
          handler (fn [req]
                    (reset! context-atom (:error-context req))
                    {:status 200 :body "ok"})
          wrapped (eh/wrap-error-context handler)
          response (wrapped request)]

      (is (= 200 (:status response)))
      (let [context @context-atom]
        (is (= (str user-id) (:user-id context)))
        (is (= (str tenant-id) (:tenant-id context)))
        (is (contains? context :timestamp)))))

  (testing "enriches context with timestamp and environment"
    (let [request (create-test-request)
          context-atom (atom nil)
          handler (fn [req]
                    (reset! context-atom (:error-context req))
                    {:status 200 :body "ok"})
          wrapped (eh/wrap-error-context handler)]

      (wrapped request)

      (let [context @context-atom]
        (is (contains? context :timestamp))

        (is (inst? (:timestamp context))))))

  (testing "handles requests without headers gracefully"
    (let [request {:request-method :post :uri "/api/minimal"}
          context-atom (atom nil)
          handler (fn [req]
                    (reset! context-atom (:error-context req))
                    {:status 200 :body "ok"})
          wrapped (eh/wrap-error-context handler)]

      (wrapped request)

      (let [context @context-atom]
        (is (nil? (:user-id context)))
        (is (nil? (:tenant-id context)))
        (is (= "POST" (:method context)))
        (is (= "/api/minimal" (:uri context)))))))

(deftest ^:unit test-wrap-enhanced-exception-handling
  (testing "handles exceptions with enhanced context"
    (let [user-id (UUID/randomUUID)
          exception (ex-info "Test error" {:field "test"})
          request (create-test-request :user-id user-id)
          handler (create-failing-handler exception)
          error-mappings {}
          wrapped (eh/wrap-enhanced-exception-handling handler error-mappings)
          response (wrapped request)]

      (is (= 500 (:status response)))
      (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))

      (let [body (json/parse-string (:body response) keyword)]
        ;; Untyped (500) errors must not leak the raw exception message.
        (is (= "Internal Server Error" (:title body)))
        (is (= "Internal Server Error" (:detail body)))
        (is (not= "Test error" (:title body)))
        (is (contains? (:errorContext body) :user-id))
        (is (= (str user-id) (get-in body [:errorContext :user-id]))))))

  (testing "passes through successful responses unchanged"
    (let [success-response {:status 201 :body {:id 123} :headers {"Location" "/api/users/123"}}
          request (create-test-request)
          handler (create-successful-handler success-response)
          wrapped (eh/wrap-enhanced-exception-handling handler {})
          response (wrapped request)]

      (is (= success-response response))))

  (testing "applies error mappings correctly"
    (let [validation-error (ex-info "Invalid email" {:type :validation-error})
          request (create-test-request)
          handler (create-failing-handler validation-error)
          error-mappings {:validation-error [400 "Validation Error"]}
          wrapped (eh/wrap-enhanced-exception-handling handler error-mappings)
          response (wrapped request)]

      (is (= 400 (:status response)))

      (let [body (json/parse-string (:body response) keyword)]
        (is (= "Validation Error" (:title body))))))

  (testing "includes correlation-id in response headers"
    (let [correlation-id "test-correlation-123"
          exception (ex-info "Server error" {})
          request (create-test-request :headers {"x-correlation-id" correlation-id})
          handler (create-failing-handler exception)
          wrapped (eh/wrap-enhanced-exception-handling handler {})
          response (wrapped request)]

      (is (= correlation-id (get-in response [:headers "X-Correlation-ID"])))))

  (testing "combines with error context middleware"
    (let [user-id (UUID/randomUUID)
          exception (ex-info "Combined test" {:code "TEST_ERROR"})
          request (create-test-request :user-id user-id)
          handler (create-failing-handler exception)
          wrapped (-> handler
                      (eh/wrap-enhanced-exception-handling {})
                      eh/wrap-error-context)
          response (wrapped request)]

      (is (= 500 (:status response)))

      (let [body (json/parse-string (:body response) keyword)]
        (is (= "Internal Server Error" (:title body)))
        (is (= "Internal Server Error" (:detail body)))
        (is (contains? (:errorContext body) :user-id))
        (is (contains? (:errorContext body) :timestamp))))))

;; =============================================================================
;; CLI Error Context Tests
;; =============================================================================

(deftest ^:unit test-with-cli-error-context
  (testing "executes function successfully"
    (let [result (eh/with-cli-error-context
                   {:operation "test-op" :user-id "test-user"}
                   (fn [] {:success true :data "test"}))]
      (is (= {:success true :data "test"} result))))

  (testing "enhances exceptions with CLI context"
    (let [operation-context {:operation "create-user" :user-id "test-user"}]
      (try
        (eh/with-cli-error-context
          operation-context
          (fn [] (throw (ex-info "CLI error" {:field "name"}))))
        (catch Exception e
          (let [data (ex-data e)]
            (is (= "CLI error" (.getMessage e)))
            (is (= {:field "name"} (:original-data data)))
            (is (contains? (:cli-context data) :operation))
            (is (contains? (:cli-context data) :user-id))
            (is (contains? (:cli-context data) :timestamp))
            (is (= "create-user" (get-in data [:cli-context :operation])))
            (is (= "test-user" (get-in data [:cli-context :user-id]))))))))

  (testing "handles nested context correctly"
    (let [outer-context {:operation "parent-op" :level "outer"}
          inner-context {:operation "child-op" :level "inner"}]
      (try
        (eh/with-cli-error-context
          outer-context
          (fn []
            (eh/with-cli-error-context
              inner-context
              (fn [] (throw (ex-info "Nested error" {}))))))
        (catch Exception e
          (let [data (ex-data e)]
            (is (= "child-op" (get-in data [:cli-context :operation])))
            (is (= "inner" (get-in data [:cli-context :level])))))))))

(deftest ^:unit test-format-cli-error
  (testing "formats error without context"
    (let [exception (ex-info "Simple CLI error" {:field "value"})
          formatted (eh/format-cli-error exception)]

      (is (re-find #"Simple CLI error" formatted))
      (is (re-find #"field.*value" formatted))))

  (testing "formats error with CLI context"
    (let [cli-context {:operation "delete-user" :user-id "user-123" :timestamp (java.time.Instant/now)}
          exception (ex-info "Context error" {:cli-context cli-context :field "test"})
          formatted (eh/format-cli-error exception :include-context true)]

      (is (re-find #"Context error" formatted))
      (is (re-find #"Operation: delete-user" formatted))
      (is (re-find #"User ID: user-123" formatted))
      (is (re-find #"field.*test" formatted))))

  (testing "handles missing context gracefully"
    (let [exception (ex-info "No context error" {:some "data"})
          formatted (eh/format-cli-error exception :include-context true)]

      (is (re-find #"No context error" formatted))
      (is (re-find #"some.*data" formatted))))

  (testing "respects include-context flag"
    (let [cli-context {:operation "test-op" :user-id "user-456"}
          exception (ex-info "Context error" {:cli-context cli-context})
          formatted-without (eh/format-cli-error exception :include-context false)
          formatted-with (eh/format-cli-error exception :include-context true)]

      (is (not (re-find #"Operation:" formatted-without)))
      (is (re-find #"Operation: test-op" formatted-with)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit test-middleware-integration
  (testing "HTTP middleware stack with context preservation"
    (let [user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          exception (ex-info "Integration test error" {:validation-field "email"})
          request (create-test-request :user-id user-id :tenant-id tenant-id)
          handler (create-failing-handler exception)

          wrapped (-> handler
                      (eh/wrap-enhanced-exception-handling {})
                      eh/wrap-error-context)

          response (wrapped request)]

      (is (= 500 (:status response)))
      (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))

      (let [body (json/parse-string (:body response) keyword)]
        (is (= "Internal Server Error" (:title body)))
        (is (= "Internal Server Error" (:detail body)))
        (is (= (str user-id) (get-in body [:errorContext :user-id])))
        (is (= (str tenant-id) (get-in body [:errorContext :tenant-id])))
        (is (contains? (:errorContext body) :timestamp))))))