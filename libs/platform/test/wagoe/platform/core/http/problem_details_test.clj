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
  (testing "an untyped exception is a generic 500 in the one error shape"
    (let [exception (create-test-exception :message "Validation failed"
                                           :data {:field "email" :error "invalid"})
          context {:user-id "test-user" :tenant-id "test-tenant"}
          problem-body (pd/exception->problem-body exception "cid-1" "/api/users" {} context)]

      ;; BOU-161: the raw message and ex-data must not leak to the client.
      (is (= {:error {:type "internal-error" :message "Internal Server Error"
                      :correlation-id "cid-1"}}
             problem-body))
      ;; BOU-586: request context is for the log, not the body.
      (is (not (re-find #"test-user|test-tenant|email" (pr-str problem-body))))))

  (testing "works without context or correlation id"
    (let [problem-body (pd/exception->problem-body (create-test-exception :message "Simple error")
                                                   nil nil {} {})]
      (is (= {:error {:type "internal-error" :message "Internal Server Error"}}
             problem-body))))

  (testing "untyped exception with a :status key is still a generic 500"
    (let [exception (create-test-exception :message "Not found" :data {:status 404})]
      ;; A bare :status key in ex-data does NOT set the HTTP status — only a
      ;; recognised :type via error-mappings does.
      (is (= [500 "Internal Server Error"] (pd/exception-status exception {})))
      (is (= "Internal Server Error"
             (get-in (pd/exception->problem-body exception nil nil {} {}) [:error :message])))))

  (testing "a typed 4xx carries its message and the rest of its ex-data as details"
    (let [exception (create-test-exception :message "Invalid email"
                                           :data {:type :validation-error :field "email"})
          problem-body (pd/exception->problem-body exception "cid-2" nil {})]
      (is (= {:error {:type "validation-error" :message "Invalid email"
                      :details {:field "email"} :correlation-id "cid-2"}}
             problem-body)))))

(deftest ^:unit test-exception->problem-response-with-context
  (testing "creates a JSON response with the status of the exception"
    (let [exception (create-test-exception :message "Server error")
          context {:user-id "test-user" :operation "get-user"}
          response (pd/exception->problem-response exception "test-correlation-123" nil {} context)
          body (json/parse-string (:body response))]

      (is (= 500 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "internal-error" (get-in body ["error" "type"])))
      (is (= "Internal Server Error" (get-in body ["error" "message"])))
      (is (= "test-correlation-123" (get-in body ["error" "correlation-id"])))
      (is (not (re-find #"test-user|Server error" (:body response))))))

  (testing "a mapped type sets the status"
    (let [exception (create-test-exception :message "Gone" :data {:type :not-found})
          response (pd/exception->problem-response exception nil nil)]
      (is (= 404 (:status response)))
      (is (= "not-found" (get-in (json/parse-string (:body response)) ["error" "type"]))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit test-full-context-flow
  (testing "full flow from request to error response"
    (let [request (create-test-request :user-id (UUID/randomUUID)
                                       :tenant-id (UUID/randomUUID))
          context (pd/request->context* request)
          enriched-context (pd/enrich-context context {:environment "test"
                                                       :timestamp (Instant/parse "2026-04-10T12:00:00Z")})
          exception (create-test-exception :message "Business logic error"
                                           :data {:code "BUSINESS_ERROR"})
          response (pd/exception->problem-response exception nil nil {} enriched-context)
          body (json/parse-string (:body response))]

      (is (= 500 (:status response)))
      (is (= {"error" {"type" "internal-error" "message" "Internal Server Error"}} body)))))
