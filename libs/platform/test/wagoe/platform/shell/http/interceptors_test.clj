(ns wagoe.platform.shell.http.interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.http.interceptors :as http-interceptors]))

;; ==============================================================================
;; Mock System (simplified)
;; ==============================================================================

(defn create-mock-system
  "Creates a minimal mock system for testing."
  []
  {:system {}})

;; ==============================================================================
;; HTTP Context Tests
;; ==============================================================================

(deftest ^:unit create-http-context-test
  (testing "creates basic HTTP context"
    (let [request {:request-method :get
                   :uri "/api/users"
                   :headers {}
                   :query-params {:limit "10"}}
          system {}
          ctx (http-interceptors/create-http-context request system)]

      (is (= request (:request ctx)))
      (is (nil? (:response ctx)))
      (is (= {:limit "10"} (:query-params ctx)))
      (is (= system (:system ctx)))
      (is (some? (:correlation-id ctx)))
      (is (some? (:started-at ctx)))))

  (testing "uses correlation ID from request header"
    (let [request {:headers {"x-correlation-id" "test-123"}}
          system {}
          ctx (http-interceptors/create-http-context request system)]

      (is (= "test-123" (:correlation-id ctx)))))

  (testing "includes route data when provided"
    (let [request {:uri "/api/users/123"}
          system {}
          route-data {:path "/api/users/:id" :name :get-user}
          ctx (http-interceptors/create-http-context request system route-data)]

      (is (= route-data (:route ctx))))))

(deftest ^:unit extract-response-test
  (testing "extracts response from context"
    (let [response {:status 200 :body "OK"}
          ctx {:response response}]
      (is (= response (http-interceptors/extract-response ctx)))))

  (testing "returns safe error response when no response"
    (let [ctx {:correlation-id "test-123"}
          response (http-interceptors/extract-response ctx)]
      (is (= 500 (:status response)))
      (is (= "test-123" (:correlation-id (:body response)))))))

(deftest ^:unit set-response-test
  (testing "sets response in context"
    (let [ctx {}
          response {:status 200 :body "OK"}
          updated (http-interceptors/set-response ctx response)]
      (is (= response (:response updated))))))

(deftest ^:unit merge-response-headers-test
  (testing "merges headers into response"
    (let [ctx {:response {:headers {"Content-Type" "application/json"}}}
          updated (http-interceptors/merge-response-headers ctx {"X-Custom" "value"})]
      (is (= {"Content-Type" "application/json"
              "X-Custom" "value"}
             (get-in updated [:response :headers]))))))

;; ==============================================================================
;; HTTP Interceptor Runner Tests
;; ==============================================================================

(deftest ^:unit run-http-interceptors-happy-path-test
  (testing "runs interceptors and handler successfully"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] {:status 200 :body "OK"})
          request {:request-method :get :uri "/api/users"}

          enter-called (atom [])
          leave-called (atom [])

          test-interceptor {:name :test
                            :enter (fn [ctx]
                                     (swap! enter-called conj :enter)
                                     ctx)
                            :leave (fn [ctx]
                                     (swap! leave-called conj :leave)
                                     ctx)}

          response (http-interceptors/run-http-interceptors
                    handler
                    [test-interceptor]
                    request
                    system)]

      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))
      (is (= [:enter] @enter-called))
      (is (= [:leave] @leave-called)))))

(deftest ^:unit run-http-interceptors-error-handling-test
  (testing "catches handler exceptions and runs error phase"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] (throw (ex-info "Handler error" {:type :validation-error})))
          request {:request-method :get :uri "/api/users"}

          error-called (atom [])

          test-interceptor {:name :test
                            :error (fn [ctx]
                                     (swap! error-called conj :error)
                                     ctx)}

          response (http-interceptors/run-http-interceptors
                    handler
                    [test-interceptor http-interceptors/http-error-handler]
                    request
                    system)]

      (is (= 400 (:status response)))
      (is (= [:error] @error-called)))))

(deftest ^:unit run-http-interceptors-ordering-test
  (testing "executes enter in forward order, leave in reverse"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] {:status 200 :body "OK"})
          request {:request-method :get :uri "/api/users"}

          execution-order (atom [])

          interceptor-1 {:name :first
                         :enter (fn [ctx] (swap! execution-order conj :first-enter) ctx)
                         :leave (fn [ctx] (swap! execution-order conj :first-leave) ctx)}

          interceptor-2 {:name :second
                         :enter (fn [ctx] (swap! execution-order conj :second-enter) ctx)
                         :leave (fn [ctx] (swap! execution-order conj :second-leave) ctx)}

          _ (http-interceptors/run-http-interceptors
             handler
             [interceptor-1 interceptor-2]
             request
             system)]

      (is (= [:first-enter :second-enter :second-leave :first-leave]
             @execution-order)))))

(deftest ^:unit run-http-interceptors-context-propagation-test
  (testing "context changes propagate through pipeline"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] {:status 200 :body "OK"})
          request {:request-method :get :uri "/api/users"}

          add-attr-interceptor {:name :add-attr
                                :enter (fn [ctx]
                                         (assoc-in ctx [:attrs :user-id] 123))}

          check-attr-interceptor {:name :check-attr
                                  :leave (fn [ctx]
                                           (is (= 123 (get-in ctx [:attrs :user-id])))
                                           ctx)}

          _ (http-interceptors/run-http-interceptors
             handler
             [add-attr-interceptor check-attr-interceptor]
             request
             system)])))

;; ==============================================================================
;; Middleware Wrapper Tests
;; ==============================================================================

(deftest ^:unit wrap-http-interceptors-test
  (testing "wraps handler with interceptors as middleware"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] {:status 200 :body "OK"})

          test-interceptor {:name :test
                            :enter (fn [ctx]
                                     (assoc-in ctx [:attrs :test] true))}

          wrapped-handler (http-interceptors/wrap-http-interceptors
                           handler
                           [test-interceptor]
                           system)

          response (wrapped-handler {:request-method :get :uri "/test"})]

      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))

(deftest ^:unit interceptor-middleware-test
  (testing "creates middleware function from interceptors"
    (let [{:keys [system]} (create-mock-system)
          handler (fn [_req] {:status 200 :body "OK"})

          test-interceptor {:name :test
                            :leave (fn [ctx]
                                     (assoc-in ctx [:response :headers "X-Test"] "true"))}

          middleware-fn (http-interceptors/interceptor-middleware
                         [test-interceptor]
                         system)

          wrapped-handler (middleware-fn handler)
          response (wrapped-handler {:request-method :get :uri "/test"})]

      (is (= "true" (get-in response [:headers "X-Test"]))))))

(deftest ^:unit http-error-handler-test
  (testing "converts validation error to 400"
    (let [exception (ex-info "Invalid input" {:type :validation-error})
          ctx {:exception exception :correlation-id "test-123"}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 400 (get-in result [:response :status])))
      (is (= "validation-error" (get-in result [:response :body :error])))))

  (testing "converts not-found to 404"
    (let [exception (ex-info "Not found" {:type :not-found})
          ctx {:exception exception :correlation-id "test-123"}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 404 (get-in result [:response :status])))))

  (testing "converts unauthorized to 401"
    (let [exception (ex-info "Unauthorized" {:type :unauthorized})
          ctx {:exception exception :correlation-id "test-123"}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 401 (get-in result [:response :status])))))

  (testing "defaults to 500 for unknown errors"
    (let [exception (Exception. "Unknown error")
          ctx {:exception exception :correlation-id "test-123"}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 500 (get-in result [:response :status])))))

  (testing "includes correlation ID in response"
    (let [exception (ex-info "Error" {})
          ctx {:exception exception :correlation-id "test-123"}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= "test-123" (get-in result [:response :body :correlation-id]))))))

(deftest ^:unit http-correlation-header-test
  (testing "adds correlation header on leave"
    (let [ctx {:correlation-id "test-123"
               :response {:headers {}}}
          result ((:leave http-interceptors/http-correlation-header) ctx)]
      (is (= "test-123" (get-in result [:response :headers "X-Correlation-ID"])))))

  (testing "adds correlation header on error"
    (let [ctx {:correlation-id "test-123"
               :response {:headers {}}}
          result ((:error http-interceptors/http-correlation-header) ctx)]
      (is (= "test-123" (get-in result [:response :headers "X-Correlation-ID"]))))))

;; ==============================================================================
;; Error Type Enforcement Tests
;; ==============================================================================

(deftest ^:unit http-error-handler-enforcement-test
  (testing "detects missing :type in dev environment"
    (let [exception (ex-info "Validation error" {:field "name" :value ""})
          ctx {:exception exception
               :correlation-id "test-123"
               :request {:uri "/api/users" :request-method :post}
               :system {}}
          result ((:error http-interceptors/http-error-handler) ctx)]
      ;; In dev mode (default), should respond with missing-error-type
      (is (= 500 (get-in result [:response :status])))
      (is (= "missing-error-type" (get-in result [:response :body :error])))))

  (testing "allows missing :type when enforcement is disabled"
    ;; Set environment to production-like to skip enforcement
    (let [exception (ex-info "Validation error" {:field "name"})
          ctx {:exception exception
               :correlation-id "test-123"
               :request {:uri "/api/users"}
               :system {:environment "production"}}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 500 (get-in result [:response :status])))
      (is (= "internal-error" (get-in result [:response :body :error])))))

  (testing "handles plain exceptions without ex-data gracefully"
    (let [exception (Exception. "Something went wrong")
          ctx {:exception exception
               :correlation-id "test-123"
               :request {:uri "/api/users"}
               :system {}}
          result ((:error http-interceptors/http-error-handler) ctx)]
      ;; Plain exceptions don't have ex-data, so no enforcement check
      (is (= 500 (get-in result [:response :status])))
      (is (= "internal-error" (get-in result [:response :body :error])))))

  (testing "preserves explicit :type when provided"
    (let [exception (ex-info "Auth failed" {:type :unauthorized :reason "Invalid token"})
          ctx {:exception exception
               :correlation-id "test-123"
               :request {:uri "/api/users"}
               :system {}}
          result ((:error http-interceptors/http-error-handler) ctx)]
      (is (= 401 (get-in result [:response :status])))
      (is (= "unauthorized" (get-in result [:response :body :error])))
      (is (= "Invalid token" (get-in result [:response :body :details :reason]))))))
