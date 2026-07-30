(ns wagoe.platform.core.http.problem-details-test
  "Tests for Problem Details context preservation functionality"
  (:require [wagoe.platform.core.http.problem-details :as pd]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test Helpers  
;; =============================================================================

(defn- create-test-request
  "Create a test HTTP request with context information"
  [& {:keys [user-id tenant-id headers uri method]}]
  {:request-method (or method :get)
   :uri (or uri "/api/users")
   :headers (merge {"user-agent" "test-agent/1.0"
                    "x-forwarded-for" "192.168.1.100"
                    "x-trace-id" (str (UUID/randomUUID))
                    "x-request-id" (str (UUID/randomUUID))}
                   (when user-id {"x-user-id" (str user-id)})
                   (when tenant-id {"x-tenant-id" (str tenant-id)})
                   (or headers {}))})

(defn- create-test-exception
  "Create a test exception with optional message and data"
  [& {:keys [message data]}]
  (ex-info (or message "Test exception") (or data {})))

;; =============================================================================
;; Context Extraction Tests
;; =============================================================================

(deftest ^:unit test-request->context
  (testing "extracts context from HTTP request"
    (let [user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          request (create-test-request :user-id user-id :tenant-id tenant-id)
          timestamp (Instant/parse "2026-04-10T12:00:00Z")
          context (pd/request->context* request {:environment "test"
                                                 :timestamp timestamp})]

      (is (= (str user-id) (:user-id context)))
      (is (= (str tenant-id) (:tenant-id context)))
      (is (contains? context :trace-id))
      (is (contains? context :request-id))
      (is (= "test-agent/1.0" (:user-agent context)))
      (is (= "192.168.1.100" (:ip-address context)))
      (is (= "/api/users" (:uri context)))
      (is (= "GET" (:method context)))
      (is (= "test" (:environment context)))
      (is (= timestamp (:timestamp context)))))

  (testing "handles missing headers gracefully"
    (let [request {:request-method :post :uri "/api/test"}
          context (pd/request->context* request {:environment "test"})]

      (is (nil? (:user-id context)))
      (is (nil? (:tenant-id context)))
      (is (nil? (:trace-id context)))
      (is (= "POST" (:method context)))
      (is (= "/api/test" (:uri context)))))

  (testing "handles x-forwarded-for header variations"
    (let [request-single-ip (assoc-in (create-test-request) [:headers "x-forwarded-for"] "10.0.0.1")
          request-multiple-ips (assoc-in (create-test-request) [:headers "x-forwarded-for"] "10.0.0.1, 192.168.1.1")
          context-single (pd/request->context* request-single-ip)
          context-multiple (pd/request->context* request-multiple-ips)]

      (is (= "10.0.0.1" (:ip-address context-single)))
      (is (= "10.0.0.1" (:ip-address context-multiple))))))

(deftest ^:unit test-cli-context
  (testing "creates CLI context with environment info"
    (let [timestamp (Instant/parse "2026-04-10T12:00:00Z")
          context (pd/cli-context* {:environment "development"
                                    :timestamp timestamp
                                    :process-id "1234"})]

      (is (= "development" (:environment context)))
      (is (= timestamp (:timestamp context)))
      (is (= "1234" (:process-id context)))))

  (testing "merges additional context"
    (let [additional {:user-id "test-user" :operation "create-user"}
          context (pd/cli-context* {:environment "development"
                                    :timestamp (Instant/parse "2026-04-10T12:00:00Z")
                                    :process-id "1234"}
                                   additional)]

      (is (= "test-user" (:user-id context)))
      (is (= "create-user" (:operation context)))
      (is (contains? context :environment)))))

(deftest ^:unit test-enrich-context
  (testing "enriches context with timestamp and environment"
    (let [base-context {:user-id "test-user"}
          enriched (pd/enrich-context base-context {:timestamp (java.time.Instant/now) :environment "test"})]

      (is (= "test-user" (:user-id enriched)))
      (is (contains? enriched :timestamp))
      (is (contains? enriched :environment))
      (is (inst? (:timestamp enriched)))))

  (testing "preserves existing timestamp"
    (let [existing-timestamp (Instant/parse "2023-01-01T12:00:00Z")
          base-context {:user-id "test-user" :timestamp existing-timestamp}
          enriched (pd/enrich-context base-context {:environment "test"})]

      (is (= existing-timestamp (:timestamp enriched))))))

;; =============================================================================
;; Problem Details Creation Tests
;; =============================================================================

(deftest ^:unit test-exception->problem-body-with-context
  (testing "creates problem body with context"
    (let [exception (create-test-exception :message "Validation failed"
                                           :data {:field "email" :error "invalid"})
          context {:user-id "test-user" :tenant-id "test-tenant"}
          problem-body (pd/exception->problem-body exception nil nil {} context)]

      ;; BOU-161: untyped exceptions are 500s whose body is generic — the raw
      ;; message and ex-data extension members must not leak to the client.
      (is (= "Internal Server Error" (:title problem-body)))
      (is (= 500 (:status problem-body)))
      (is (= "Internal Server Error" (:detail problem-body)))
      (is (nil? (:field problem-body)))
      (is (nil? (:error problem-body)))
      ;; Context IS still preserved on 5xx (for the client's correlation), only
      ;; internals are suppressed.
      (is (= "test-user" (get-in problem-body [:errorContext :user-id])))
      (is (= "test-tenant" (get-in problem-body [:errorContext :tenant-id])))
      (is (not (contains? (:errorContext problem-body) :timestamp)))
      (is (contains? problem-body :instance))))

  (testing "works without context"
    (let [exception (create-test-exception :message "Simple error")
          problem-body (pd/exception->problem-body exception nil nil {} {})]

      (is (= "Internal Server Error" (:title problem-body)))
      (is (= 500 (:status problem-body)))
      (is (= "Internal Server Error" (:detail problem-body)))
      (is (nil? (:errorContext problem-body)))))

  (testing "untyped exception with a :status key is still a generic 500"
    (let [exception (create-test-exception :message "Not found"
                                           :data {:status 404})
          context {:user-id "test-user"}
          problem-body (pd/exception->problem-body exception nil nil {} context)]

      ;; A bare :status key in ex-data does NOT set the HTTP status — only a
      ;; recognised :type via error-mappings does. Untyped => generic 500.
      (is (= 500 (:status problem-body)))
      (is (= "Internal Server Error" (:detail problem-body)))
      (is (= "test-user" (get-in problem-body [:errorContext :user-id])))
      (is (not (contains? (:errorContext problem-body) :timestamp))))))

(deftest ^:unit test-exception->problem-response-with-context
  (testing "creates problem response with context"
    (let [exception (create-test-exception :message "Server error")
          context {:user-id "test-user" :operation "get-user"}
          response (pd/exception->problem-response exception "test-correlation-123" nil {} context)]

      (is (= 500 (:status response)))
      (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))

      ;; Parse the JSON body since it's a string
      (let [body (json/parse-string (:body response))]
        (is (= "Internal Server Error" (get body "title")))
        (is (= "Internal Server Error" (get body "detail")))
        ;; Context contains only explicitly supplied values
        (is (= "test-user" (get-in body ["errorContext" "user-id"])))
        (is (not (contains? (get body "errorContext") "timestamp"))))))

  (testing "includes correlation-id in headers when present in context"
    (let [exception (create-test-exception)
          context {:correlation-id "test-correlation-123"}
          response (pd/exception->problem-response exception "test-correlation-123" nil {} context)
          body (json/parse-string (:body response))]
      (is (= "test-correlation-123" (get body "correlationId")))))

  (testing "sets correct content type"
    (let [exception (create-test-exception)
          context {:user-id "test-user"}
          response (pd/exception->problem-response exception nil nil {} context)]

      (is (= "application/problem+json" (get-in response [:headers "Content-Type"]))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit test-full-context-flow
  (testing "full flow from request to problem response"
    (let [request (create-test-request :user-id (UUID/randomUUID)
                                       :tenant-id (UUID/randomUUID))
          context (pd/request->context* request)
          enriched-context (pd/enrich-context context {:environment "test"
                                                       :timestamp (Instant/parse "2026-04-10T12:00:00Z")})
          exception (create-test-exception :message "Business logic error"
                                           :data {:code "BUSINESS_ERROR"})
          response (pd/exception->problem-response exception nil nil {} enriched-context)]

      (is (= 500 (:status response)))

      ;; Parse the JSON body since it's a string
      (let [body (json/parse-string (:body response))]
        (is (= "Internal Server Error" (get body "title")))
        (is (= "Internal Server Error" (get body "detail")))
        ;; Check that context fields are present using string keys
        (is (contains? (get body "errorContext") "user-id"))
        (is (contains? (get body "errorContext") "tenant-id"))
        (is (contains? (get body "errorContext") "timestamp"))
        (is (contains? (get body "errorContext") "environment"))))))
