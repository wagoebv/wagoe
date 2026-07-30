(ns wagoe.error-handling-integration-test
  "End-to-end integration tests for enhanced error handling system"
  (:require [wagoe.platform.core.http.problem-details :as pd]
            [wagoe.platform.shell.utils.error-handling :as eh]
            [wagoe.platform.shell.interfaces.cli.middleware :as cli-middleware]
            [wagoe.platform.shell.interfaces.http.middleware :as http-middleware]
            [wagoe.observability.errors.ports :as error-reporting]
            [wagoe.observability.errors.core :as er-core]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.util UUID]))

;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(defrecord MockErrorReportingService [reports]
  error-reporting/IErrorReporter

  (capture-exception [_ exception]
    (let [error-data {:message (.getMessage exception)
                      :exception-data (ex-data exception)
                      :type :application}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-exception [_ exception context]
    (let [error-data {:message (.getMessage exception)
                      :exception-data (ex-data exception)
                      :context context
                      :type :application}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-exception [_ exception context tags]
    (let [error-data {:message (.getMessage exception)
                      :exception-data (ex-data exception)
                      :context context
                      :tags tags
                      :type :application}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-message [_ message level]
    (let [error-data {:message message :level level :type :message}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-message [_ message level context]
    (let [error-data {:message message :level level :context context :type :message}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-message [_ message level context tags]
    (let [error-data {:message message :level level :context context :tags tags :type :message}]
      (swap! reports conj error-data)
      {:status :reported :id (str (java.util.UUID/randomUUID))}))

  (capture-event [_ event-map]
    (swap! reports conj event-map)
    {:status :reported :id (str (java.util.UUID/randomUUID))}))

(defn create-mock-error-service []
  (->MockErrorReportingService (atom [])))

(defn get-reported-errors [mock-service]
  @(:reports mock-service))

(defn create-test-request
  "Create a realistic HTTP request for testing"
  [& {:keys [method uri user-id tenant-id correlation-id headers]}]
  {:request-method (or method :get)
   :uri (or uri "/api/users")
   :headers (merge {"user-agent" "wagoe-client/1.0"
                    "accept" "application/json"
                    "content-type" "application/json"
                    "x-forwarded-for" "203.0.113.42, 198.51.100.17"
                    "host" "api.wagoe.example.com"}
                   (when user-id {"x-user-id" (str user-id)})
                   (when tenant-id {"x-tenant-id" (str tenant-id)})
                   (when correlation-id {"x-correlation-id" correlation-id})
                   (or headers {}))
   :body (when (#{:post :put :patch} (or method :get))
           "{\"name\":\"John Doe\",\"email\":\"john@example.com\"}")})

(defn parse-response-with-timestamps
  "Parse JSON response and convert timestamp strings to Instant objects"
  [json-string]
  (let [parsed (json/parse-string json-string keyword)]
    (update-in parsed [:context :timestamp]
               (fn [ts]
                 (if (string? ts)
                   (java.time.Instant/parse ts)
                   ts)))))

;; =============================================================================
;; HTTP Error Handling End-to-End Tests
;; =============================================================================

(deftest ^:contract test-http-error-handling-end-to-end
  (testing "complete HTTP error handling flow with context and reporting"
    (let [user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          correlation-id "integration-test-correlation-123"

          request (create-test-request :method :post
                                       :uri "/api/users"
                                       :user-id user-id
                                       :tenant-id tenant-id
                                       :correlation-id correlation-id)

          business-exception (ex-info "User validation failed"
                                      {:type :database-error
                                       :field "email"
                                       :constraint "unique"
                                       :attempted-value "john@example.com"})

          failing-handler (fn [_req] (throw business-exception))

          full-middleware-stack (-> failing-handler
                                    http-middleware/wrap-correlation-id
                                    eh/wrap-error-context
                                    (eh/wrap-enhanced-exception-handling {}))

          response (full-middleware-stack request)]

      (is (= 500 (:status response)))
      (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))
      (is (= correlation-id (get-in response [:headers "X-Correlation-ID"])))

      (let [problem-body (parse-response-with-timestamps (:body response))
            context (:context problem-body)]

        (is (= "Internal Server Error" (:title problem-body)))
        (is (= 500 (:status problem-body)))
        ;; BOU-161: an unrecognised exception :type (here :database-error, which
        ;; is not in the error-mappings) is a generic 500 — the raw ex-data
        ;; members must NOT leak into the client-facing problem body.
        (is (= "Internal Server Error" (:detail problem-body)))
        (is (nil? (:field problem-body)))
        (is (nil? (:constraint problem-body)))
        (is (nil? (:attempted-value problem-body)))

        (is (= (str user-id) (:user-id context)))
        (is (= (str tenant-id) (:tenant-id context)))
        (is (= correlation-id (:trace-id context)))
        (is (= "POST" (:method context)))
        (is (= "/api/users" (:uri context)))
        (is (= "wagoe-client/1.0" (:user-agent context)))
        (is (= "203.0.113.42" (:ip-address context)))
        (is (contains? context :timestamp))
        (is (contains? context :environment)))))

  (testing "HTTP error mapping with custom status codes"
    (let [validation-error (ex-info "Invalid input data"
                                    {:type :validation-error
                                     :field "age"
                                     :message "must be positive"})

          not-found-error (ex-info "Resource not found"
                                   {:type :not-found-error
                                    :resource "user"
                                    :id 12345})

          error-mappings {:validation-error [400 "Bad Request"]
                          :not-found-error [404 "Not Found"]}

          request (create-test-request :method :get :uri "/api/users/12345")

          validation-handler (fn [_] (throw validation-error))
          not-found-handler (fn [_] (throw not-found-error))

          middleware-fn (fn [handler]
                          (-> handler
                              eh/wrap-error-context
                              (eh/wrap-enhanced-exception-handling error-mappings)))

          validation-response ((middleware-fn validation-handler) request)
          not-found-response ((middleware-fn not-found-handler) request)]

      (is (= 400 (:status validation-response)))
      (is (= 404 (:status not-found-response)))

      (let [validation-body (parse-response-with-timestamps (:body validation-response))
            not-found-body (parse-response-with-timestamps (:body not-found-response))]

        (is (= "Bad Request" (:title validation-body)))
        (is (= "Not Found" (:title not-found-body))))))

  (testing "nested middleware context preservation"
    (let [outer-correlation-id "outer-correlation-456"
          inner-request-id "inner-request-789"

          request (create-test-request :correlation-id outer-correlation-id
                                       :headers {"x-request-id" inner-request-id})

          exception (ex-info "Nested context test" {:type :test-error :level "deep"})
          handler (fn [_] (throw exception))

          response (-> handler
                       http-middleware/wrap-correlation-id
                       eh/wrap-error-context
                       (eh/wrap-enhanced-exception-handling {})
                       (#(% request)))]

      (is (= outer-correlation-id (get-in response [:headers "X-Correlation-ID"])))

      (let [body (parse-response-with-timestamps (:body response))]
        (is (= outer-correlation-id (get-in body [:context :trace-id])))
        (is (= inner-request-id (get-in body [:context :request-id])))))))

;; =============================================================================
;; CLI Error Handling End-to-End Tests
;; =============================================================================

(deftest ^:unit test-cli-error-handling-end-to-end
  (testing "complete CLI error handling flow with context"
    (let [operation-context {:operation "bulk-import-users"
                             :user-id "admin-user"
                             :file-path "/data/users.csv"
                             :batch-size 100
                             :dry-run false}

          business-error (ex-info "CSV parsing failed"
                                  {:type :csv-error
                                   :row 25
                                   :column "email"
                                   :error "invalid format"
                                   :value "not-an-email"})

          failing-operation (fn [_context] (throw business-error))]

      (try
        (cli-middleware/with-cli-error-reporting operation-context failing-operation)
        (is false "Should have thrown enhanced exception")
        (catch Exception enhanced-e
          (let [error-data (ex-data enhanced-e)
                cli-context (:cli-context error-data)]

            (is (= "CSV parsing failed" (.getMessage enhanced-e)))
            (is (= {:type :csv-error :row 25 :column "email" :error "invalid format" :value "not-an-email"}
                   (:original-data error-data)))

            (is (= "bulk-import-users" (:operation cli-context)))
            (is (= "admin-user" (:user-id cli-context)))
            (is (= "/data/users.csv" (:file-path cli-context)))
            (is (= 100 (:batch-size cli-context)))
            (is (false? (:dry-run cli-context)))
            (is (contains? cli-context :timestamp))
            (is (contains? cli-context :environment))
            (is (contains? cli-context :process-id))

            (let [formatted-basic (eh/format-cli-error enhanced-e :include-context false)
                  formatted-full (eh/format-cli-error enhanced-e :include-context true)]

              (is (str/includes? formatted-basic "CSV parsing failed"))
              (is (str/includes? formatted-basic ":row 25"))
              (is (not (str/includes? formatted-basic "Operation:")))

              (is (str/includes? formatted-full "CSV parsing failed"))
              (is (str/includes? formatted-full "Operation: bulk-import-users"))
              (is (str/includes? formatted-full "User ID: admin-user"))
              (is (str/includes? formatted-full "file-path"))))))))

  (testing "nested CLI operations with context inheritance"
    (let [parent-context {:operation "system-maintenance" :user-id "system-admin"}
          child-context {:operation "database-cleanup" :database "users"}

          child-error (ex-info "Cleanup failed" {:type :cleanup-error :table "user_sessions" :affected-rows 0})

          child-operation (fn [_] (throw child-error))
          parent-operation (fn [context]
                             (cli-middleware/with-cli-error-reporting
                               (merge context child-context)
                               child-operation))]

      (try
        (cli-middleware/with-cli-error-reporting parent-context parent-operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (= "database-cleanup" (:operation cli-context)))
            (is (= "system-admin" (:user-id cli-context)))
            (is (= "users" (:database cli-context)))))))))

;; =============================================================================
;; Error Reporting Integration Tests
;; =============================================================================

(deftest ^:contract test-error-reporting-integration
  (testing "HTTP errors are automatically reported with full context"
    (let [mock-error-service (create-mock-error-service)
          user-id (UUID/randomUUID)
          correlation-id "reporting-test-correlation"

          request (create-test-request :method :put
                                       :uri "/api/users/123"
                                       :user-id user-id
                                       :correlation-id correlation-id)

          exception (ex-info "User update failed"
                             {:type :update-error :user-id 123 :operation "update-profile"})

          handler-with-reporting (fn [req]
                                   (let [context (:error-context req)]
                                     (try
                                       (throw exception)
                                       (catch Exception e
                                         (er-core/report-application-error
                                          mock-error-service e "User update failed" context)
                                         (throw e)))))

          response (-> handler-with-reporting
                       eh/wrap-error-context
                       (eh/wrap-enhanced-exception-handling {})
                       (#(% request)))]

      (is (= 500 (:status response)))
      (is (= correlation-id (get-in response [:headers "X-Correlation-ID"])))

      (let [reported-errors (get-reported-errors mock-error-service)
            error-data (first reported-errors)
            response-body (parse-response-with-timestamps (:body response))]

        (is (= 1 (count reported-errors)))
        (is (= "User update failed" (:message error-data)))
        (is (= {:type :update-error :user-id 123 :operation "update-profile"} (:exception-data error-data)))

        (let [reported-context (:context error-data)]
          (is (= (str user-id) (:user-id reported-context)))
          (is (= correlation-id (:trace-id reported-context)))
          (is (= "PUT" (:method reported-context)))
          (is (= "/api/users/123" (:uri reported-context))))

        (is (= "Internal Server Error" (:title response-body)))
        (is (= correlation-id (get-in response-body [:context :trace-id]))))))

  (testing "CLI errors are reported with operational context"
    (let [mock-error-service (create-mock-error-service)
          cli-context {:operation "data-migration"
                       :user-id "migration-admin"
                       :migration-version "v2.1.0"
                       :target-environment "production"}

          exception (ex-info "Migration rollback failed"
                             {:type :migration-error
                              :step "rollback-schema"
                              :affected-tables ["users" "sessions"]})

          operation-with-reporting (fn [context]
                                     (try
                                       (throw exception)
                                       (catch Exception e
                                         (er-core/report-application-error
                                          mock-error-service e "Migration rollback failed"
                                          (pd/cli-context* {:environment "development"
                                                            :timestamp (java.time.Instant/parse "2026-04-10T12:00:00Z")
                                                            :process-id "1234"}
                                                           context))
                                         (throw e))))]

      (try
        (cli-middleware/with-cli-error-reporting cli-context operation-with-reporting)
        (catch Exception _e
          (let [reported-errors (get-reported-errors mock-error-service)
                error-data (first reported-errors)]

            (is (= 1 (count reported-errors)))
            (is (= "Migration rollback failed" (:message error-data)))
            (is (= {:type :migration-error :step "rollback-schema" :affected-tables ["users" "sessions"]}
                   (:exception-data error-data)))

            (let [reported-context (:context error-data)]
              (is (= "data-migration" (:operation reported-context)))
              (is (= "migration-admin" (:user-id reported-context)))
              (is (= "v2.1.0" (:migration-version reported-context)))
              (is (= "production" (:target-environment reported-context)))
              (is (contains? reported-context :process-id)))))))))

;; =============================================================================
;; Cross-Context Error Correlation Tests
;; =============================================================================

(deftest ^:contract test-cross-context-error-correlation
  (testing "errors can be correlated across HTTP and CLI contexts"
    (let [mock-error-service (create-mock-error-service)
          trace-id "cross-context-trace-999"

          http-request (create-test-request :correlation-id trace-id
                                            :user-id "web-user")

          cli-context {:operation "background-process"
                       :user-id "system-user"
                       :trace-id trace-id}

          http-exception (ex-info "HTTP processing failed" {:type :http-error :api-endpoint "/api/process"})
          cli-exception (ex-info "Background job failed" {:type :job-error :job-id "job-456"})

          http-handler (fn [req]
                         (let [context (:error-context req)]
                           (er-core/report-application-error
                            mock-error-service http-exception "HTTP processing failed" context)
                           (throw http-exception)))

          cli-operation (fn [context]
                          (er-core/report-application-error
                           mock-error-service cli-exception "Background job failed"
                           (pd/cli-context* {:environment "development"
                                             :timestamp (java.time.Instant/parse "2026-04-10T12:00:00Z")
                                             :process-id "1234"}
                                            context))
                          (throw cli-exception))]

      (try
        (-> http-handler
            eh/wrap-error-context
            (eh/wrap-enhanced-exception-handling {})
            (#(% http-request)))
        (catch Exception _))

      (try
        (cli-middleware/with-cli-error-reporting cli-context cli-operation)
        (catch Exception _))

      (let [reported-errors (get-reported-errors mock-error-service)]
        (is (= 2 (count reported-errors)))

        (let [http-error (first reported-errors)
              cli-error (second reported-errors)]

          (is (= trace-id (get-in http-error [:context :trace-id])))
          (is (= trace-id (get-in cli-error [:context :trace-id])))

          (is (= "web-user" (get-in http-error [:context :user-id])))
          (is (= "system-user" (get-in cli-error [:context :user-id])))

          (is (= "HTTP processing failed" (:message http-error)))
          (is (= "Background job failed" (:message cli-error)))))))

  (testing "end-to-end error handling maintains context integrity"
    (let [mock-error-service (create-mock-error-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          request-id (str (UUID/randomUUID))

          request (create-test-request :method :delete
                                       :uri "/api/users/456"
                                       :user-id user-id
                                       :tenant-id tenant-id
                                       :correlation-id request-id)

          exception (ex-info "Deletion cascade failed"
                             {:type :cascade-error
                              :user-id 456
                              :cascade-failure "foreign-key-constraint"
                              :affected-tables ["user_sessions" "user_preferences"]})

          full-stack-handler (fn [req]
                               (let [context (:error-context req)]
                                 (er-core/report-application-error
                                  mock-error-service exception "Deletion cascade failed" context)
                                 (throw exception)))
          response (-> full-stack-handler
                       http-middleware/wrap-correlation-id
                       eh/wrap-error-context
                       (eh/wrap-enhanced-exception-handling {})
                       (#(% request)))]

      (is (= 500 (:status response)))
      (is (= request-id (get-in response [:headers "X-Correlation-ID"])))

      (let [response-body (parse-response-with-timestamps (:body response))
            reported-errors (get-reported-errors mock-error-service)
            error-data (first reported-errors)
            response-context (:context response-body)
            reported-context (:context error-data)]

        (is (= 1 (count reported-errors)))

        (is (= "Internal Server Error" (:title response-body)))
        (is (= "Deletion cascade failed" (:message error-data)))

        (is (= (str user-id) (:user-id response-context) (:user-id reported-context)))
        (is (= (str tenant-id) (:tenant-id response-context) (:tenant-id reported-context)))
        (is (= request-id (:trace-id response-context) (:trace-id reported-context)))
        (is (= "DELETE" (:method response-context) (:method reported-context)))
        (is (= "/api/users/456" (:uri response-context) (:uri reported-context)))

        (is (inst? (:timestamp response-context)))
        (is (inst? (:timestamp reported-context)))
        (is (= (:environment response-context) (:environment reported-context)))))))
