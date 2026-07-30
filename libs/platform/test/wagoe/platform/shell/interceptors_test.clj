(ns wagoe.platform.shell.interceptors-test
  "Tests for platform shell interceptors (context, logging, metrics, effects, response shaping)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.interceptors :as interceptors]
            [wagoe.core.interceptor :as ic]
            [wagoe.user.ports]
            [wagoe.observability.logging.ports]
            [wagoe.observability.errors.ports])
  (:import [java.time Instant]))

;; Mock implementations for testing

(defn mock-logger []
  (let [log-entries (atom [])]
    (with-meta
      (reify wagoe.observability.logging.ports/ILogger
        (log* [_ _level _message _context _exception] nil)
        (trace [_ _message] nil)
        (trace [_ _message _context] nil)
        (debug [_ _message] nil)
        (debug [_ _message _context] nil)
        (info [_ event data]
          (swap! log-entries conj {:level :info :event event :data data}))
        (warn [_ event data]
          (swap! log-entries conj {:level :warn :event event :data data}))
        (error [_ event data]
          (swap! log-entries conj {:level :error :event event :data data}))
        (fatal [_ _message] nil)
        (fatal [_ _message _context] nil)
        (fatal [_ _message _context _exception] nil))
      {:log-entries log-entries})))

(defn mock-metrics
  "Simple mock for metrics system. Since metrics/increment and metrics/observe 
   are currently no-ops, this just returns a simple tracking map."
  []
  (let [metrics-data (atom [])]
    (with-meta
      {:type :mock-metrics}
      {:data metrics-data})))

(defn mock-error-reporter []
  (let [errors (atom [])]
    (with-meta
      (reify wagoe.observability.errors.ports/IErrorReporter
        (capture-exception [_ exception context]
          (swap! errors conj {:exception exception :context context}))
        (capture-message [_ _message _level] nil)
        (capture-message [_ _message _level _context] nil)
        (capture-message [_ _message _level _context _tags] nil)
        (capture-event [_ _event-map] nil))
      {:errors errors})))

(defn get-log-entries [mock-logger]
  @(:log-entries (meta mock-logger)))

(defn get-metrics-data [mock-metrics]
  @(:data (meta mock-metrics)))

(defn get-captured-errors [mock-error-reporter]
  @(:errors (meta mock-error-reporter)))

;; Context Interceptor Tests

(deftest ^:unit context-interceptor-test
  (testing "adds correlation-id and timestamp to context"
    (let [initial-ctx {:op :test :system {}}
          result-ctx ((:enter interceptors/context-interceptor) initial-ctx)]

      (is (string? (:correlation-id result-ctx)))
      (is (instance? Instant (:now result-ctx)))
      (is (= :test (:op result-ctx)))))

  (testing "preserves existing correlation-id from request headers"
    (let [existing-id "existing-correlation-id"
          initial-ctx {:op :test
                       :system {}
                       :request {:headers {"x-correlation-id" existing-id}}}
          result-ctx ((:enter interceptors/context-interceptor) initial-ctx)]

      (is (= existing-id (:correlation-id result-ctx)))))

  (testing "preserves existing correlation-id from request"
    (let [existing-id "request-correlation-id"
          initial-ctx {:op :test
                       :system {}
                       :request {:correlation-id existing-id}}
          result-ctx ((:enter interceptors/context-interceptor) initial-ctx)]

      (is (= existing-id (:correlation-id result-ctx)))))

  (testing "sets default operation if missing"
    (let [initial-ctx {:system {}}
          result-ctx ((:enter interceptors/context-interceptor) initial-ctx)]

      (is (= :unknown-operation (:op result-ctx))))))

;; Logging Interceptor Tests

(deftest ^:unit logging-interceptors-test
  (testing "logging-start logs operation start"
    (let [logger (mock-logger)
          ctx {:op :test/operation
               :correlation-id "test-correlation-id"
               :now (Instant/now)
               :system {:logger logger}}
          result-ctx ((:enter interceptors/logging-start) ctx)]

      (is (= ctx result-ctx)) ; Context unchanged
      (let [logs (get-log-entries logger)]
        (is (= 1 (count logs)))
        (is (= :info (:level (first logs))))
        (is (= "operation-start" (:event (first logs))))
        (is (= :test/operation (get-in (first logs) [:data :op]))))))

  (testing "logging-complete logs successful completion"
    (let [logger (mock-logger)
          start-time (System/nanoTime)
          ctx {:op :test/operation
               :correlation-id "test-correlation-id"
               :system {:logger logger}
               :timing {:start start-time}
               :result {:status :success}
               :effect-errors []}
          result-ctx ((:leave interceptors/logging-complete) ctx)]

      (is (= ctx result-ctx))
      (let [logs (get-log-entries logger)]
        (is (= 1 (count logs)))
        (is (= :info (:level (first logs))))
        (is (= "operation-success" (:event (first logs))))
        (is (number? (get-in (first logs) [:data :duration-ms]))))))

  (testing "logging-complete logs completion with errors"
    (let [logger (mock-logger)
          ctx {:op :test/operation
               :correlation-id "test-correlation-id"
               :system {:logger logger}
               :timing {:start (System/nanoTime)}
               :result {:status :error}
               :effect-errors [{:effect {} :error "test error"}]}
          _result-ctx ((:leave interceptors/logging-complete) ctx)]

      (is (= 1 (count (get-log-entries logger))))
      (is (= :warn (:level (first (get-log-entries logger)))))
      (is (= "operation-completed-with-errors" (:event (first (get-log-entries logger)))))
      (is (= 1 (get-in (first (get-log-entries logger)) [:data :effect-errors])))))

  (testing "logging-error logs exceptions"
    (let [logger (mock-logger)
          exception (ex-info "Test exception" {})
          ctx {:op :test/operation
               :correlation-id "test-correlation-id"
               :system {:logger logger}
               :exception exception}
          result-ctx ((:error interceptors/logging-error) ctx)]

      (is (= ctx result-ctx))
      (let [logs (get-log-entries logger)]
        (is (= 1 (count logs)))
        (is (= :error (:level (first logs))))
        (is (= "operation-error" (:event (first logs))))
        (is (= "Test exception" (get-in (first logs) [:data :error-message])))))))

;; Metrics Interceptor Tests

(deftest ^:unit metrics-interceptors-test
  (testing "metrics-start records attempt and timing"
    (let [metrics (mock-metrics)
          ctx {:op :test/operation :system {:metrics metrics}}
          result-ctx ((:enter interceptors/metrics-start) ctx)]

      ;; Should add timing information
      (is (number? (get-in result-ctx [:timing :start])))
      ;; Metrics calls are currently no-ops, so we just ensure no errors
      (is (= ctx (dissoc result-ctx :timing)))))

  (testing "metrics-complete records success and latency"
    (let [metrics (mock-metrics)
          start-time (System/nanoTime)
          ctx {:op :test/operation
               :system {:metrics metrics}
               :timing {:start start-time}
               :result {:status :success}}
          result-ctx ((:leave interceptors/metrics-complete) ctx)]

      ;; Context should be unchanged (metrics functions are no-ops)
      (is (= ctx result-ctx))))

  (testing "metrics-complete records errors"
    (let [metrics (mock-metrics)
          ctx {:op :test/operation
               :system {:metrics metrics}
               :timing {:start (System/nanoTime)}
               :result {:status :error}}
          result-ctx ((:leave interceptors/metrics-complete) ctx)]

      ;; Context should be unchanged (metrics functions are no-ops)
      (is (= ctx result-ctx))))

  (testing "metrics-error records failures"
    (let [metrics (mock-metrics)
          ctx {:op :test/operation :system {:metrics metrics}}
          result-ctx ((:error interceptors/metrics-error) ctx)]

      ;; Context should be unchanged (metrics functions are no-ops)
      (is (= ctx result-ctx)))))

;; Error Handling Interceptor Tests

(deftest ^:unit error-handling-interceptors-test
  (testing "error-capture sends exception to error reporter"
    (let [error-reporter (mock-error-reporter)
          exception (ex-info "Test exception" {:test true})
          ctx {:op :test/operation
               :correlation-id "test-correlation-id"
               :system {:error-reporter error-reporter}
               :exception exception}
          result-ctx ((:error interceptors/error-capture) ctx)]

      (is (= ctx result-ctx))
      (let [errors (get-captured-errors error-reporter)]
        (is (= 1 (count errors)))
        (is (= exception (:exception (first errors))))
        (is (= "test/operation" (get-in (first errors) [:context :operation])))
        (is (= "test-correlation-id" (get-in (first errors) [:context :correlation-id]))))))

  (testing "error-normalize creates standard error response"
    (let [exception (ex-info "Test exception" {})
          ctx {:correlation-id "test-correlation-id"
               :exception exception}
          result-ctx ((:error interceptors/error-normalize) ctx)]

      (is (= 500 (get-in result-ctx [:response :status])))
      (is (= "internal-server-error" (get-in result-ctx [:response :body :type])))
      (is (= "test-correlation-id" (get-in result-ctx [:response :body :correlation-id]))))))

;; Effects Dispatch Interceptor Tests

(deftest ^:unit effects-dispatch-test
  (testing "executes persist-user effect"
    (let [user-repo (reify
                      wagoe.user.ports/IUserRepository
                      (create-user [_ user] user)
                      (find-user-by-id [_ _] nil)
                      (find-user-by-email [_ _] nil)
                      (find-users [_ _] {:users [] :total-count 0})
                      (update-user [_ user] user)
                      (soft-delete-user [_ _] true)
                      (hard-delete-user [_ _] true)
                      (find-active-users-by-role [_ _] [])
                      (count-users [_] 0)
                      (find-users-created-since [_ _] [])
                      (find-users-by-email-domain [_ _] [])
                      (create-users-batch [_ users] users)
                      (update-users-batch [_ users] users))
          ctx {:system {:user-repository user-repo}
               :result {:effects [{:type :persist-user
                                   :user {:id 1 :name "Test User"}}]}}
          result-ctx ((:enter interceptors/effects-dispatch) ctx)]

      (is (= [] (:effect-errors result-ctx)))))

  (testing "handles effect execution errors"
    (let [failing-repo (reify
                         wagoe.user.ports/IUserRepository
                         (create-user [_ _user] (throw (ex-info "Database error" {})))
                         (find-user-by-id [_ _] nil)
                         (find-user-by-email [_ _] nil)
                         (find-users [_ _] {:users [] :total-count 0})
                         (update-user [_ user] user)
                         (soft-delete-user [_ _] true)
                         (hard-delete-user [_ _] true)
                         (find-active-users-by-role [_ _] [])
                         (count-users [_] 0)
                         (find-users-created-since [_ _] [])
                         (find-users-by-email-domain [_ _] [])
                         (create-users-batch [_ users] users)
                         (update-users-batch [_ users] users))
          ctx {:system {:user-repository failing-repo}
               :result {:effects [{:type :persist-user
                                   :user {:id 1 :name "Test User"}}]}}
          result-ctx ((:enter interceptors/effects-dispatch) ctx)]

      (is (= 1 (count (:effect-errors result-ctx))))
      (is (= "Database error" (:error (first (:effect-errors result-ctx)))))))

  (testing "handles unknown effect types"
    (let [logger (mock-logger)
          ctx {:system {:logger logger}
               :result {:effects [{:type :unknown-effect :data "test"}]}}
          result-ctx ((:enter interceptors/effects-dispatch) ctx)]

      (is (= [] (:effect-errors result-ctx)))
      (let [logs (get-log-entries logger)]
        (is (= 1 (count logs)))
        (is (= :warn (:level (first logs))))
        (is (= "unknown-effect-type" (:event (first logs)))))))

  (testing "no effects to process"
    (let [ctx {:system {} :result {}}
          result-ctx ((:enter interceptors/effects-dispatch) ctx)]

      (is (= ctx result-ctx)))))

;; Response Shaping Interceptor Tests

(deftest ^:unit response-shaping-test
  (testing "response-shape-http creates successful HTTP response"
    (let [ctx {:result {:status :success :data {:id 1 :name "Test"}}
               :correlation-id "test-correlation-id"}
          result-ctx ((:leave interceptors/response-shape-http) ctx)]

      (is (= 201 (get-in result-ctx [:response :status])))
      (is (= {:id 1 :name "Test"} (get-in result-ctx [:response :body])))
      (is (= "test-correlation-id" (get-in result-ctx [:response :headers "X-Correlation-ID"])))))

  (testing "response-shape-http creates error HTTP response"
    (let [ctx {:result {:status :error
                        :errors [{:field "email" :code "required" :message "Email is required"}]}
               :correlation-id "test-correlation-id"
               :now (Instant/now)}
          result-ctx ((:leave interceptors/response-shape-http) ctx)]

      (is (= 400 (get-in result-ctx [:response :status])))
      (is (= "domain-error" (get-in result-ctx [:response :body :type])))
      (is (= [{:field "email" :code "required" :message "Email is required"}]
             (get-in result-ctx [:response :body :errors])))))

  (testing "response-shape-http preserves existing response"
    (let [existing-response {:status 422 :body "Validation failed"}
          ctx {:response existing-response
               :result {:status :success :data "test"}}
          result-ctx ((:leave interceptors/response-shape-http) ctx)]

      (is (= existing-response (:response result-ctx)))))

  (testing "response-shape-cli creates successful CLI response"
    (let [ctx {:result {:status :success :data {:id 1 :name "Test"}}}
          result-ctx ((:leave interceptors/response-shape-cli) ctx)]

      (is (= 0 (get-in result-ctx [:response :exit])))
      (is (string? (get-in result-ctx [:response :stdout])))))

  (testing "response-shape-cli creates error CLI response"
    (let [ctx {:result {:status :error :errors ["Validation failed"]}}
          result-ctx ((:leave interceptors/response-shape-cli) ctx)]

      (is (= 1 (get-in result-ctx [:response :exit])))
      (is (string? (get-in result-ctx [:response :stderr]))))))

;; Pipeline Assembly Tests

(deftest ^:unit pipeline-assembly-test
  (testing "create-http-pipeline assembles correctly"
    (let [custom-interceptor {:name :custom :enter (fn [ctx] ctx)}
          pipeline (interceptors/create-http-pipeline custom-interceptor)]

      (is (vector? pipeline))
      (is (> (count pipeline) 5)) ; At least context, logging, metrics, custom, effects, response
      (is (= :context (:name (first pipeline))))
      (is (= :custom (:name (nth pipeline 4)))) ; Custom interceptor in middle
      (is (= :response-shape-http (:name (last pipeline))))))

  (testing "create-cli-pipeline assembles correctly"
    (let [custom-interceptor {:name :custom :enter (fn [ctx] ctx)}
          pipeline (interceptors/create-cli-pipeline custom-interceptor)]

      (is (vector? pipeline))
      (is (= :context (:name (first pipeline))))
      (is (= :response-shape-cli (:name (last pipeline))))))

  (testing "add-error-handling adds error interceptors"
    (let [base-pipeline [{:name :test :enter (fn [ctx] ctx)}]
          enhanced-pipeline (interceptors/add-error-handling base-pipeline)]

      (is (> (count enhanced-pipeline) (count base-pipeline)))
      (is (some #(= :error-capture (:name %)) enhanced-pipeline))
      (is (some #(= :error-normalize (:name %)) enhanced-pipeline)))))

;; Integration Tests

(deftest ^:unit full-pipeline-integration-test
  (testing "complete pipeline execution with all interceptors"
    (let [logger (mock-logger)
          metrics (mock-metrics)
          error-reporter (mock-error-reporter)
          system {:logger logger :metrics metrics :error-reporter error-reporter}

          core-interceptor {:name :core
                            :enter (fn [ctx]
                                     (assoc ctx :result {:status :success
                                                         :data {:id 1 :name "Test User"}
                                                         :effects []}))}

          pipeline (interceptors/create-http-pipeline core-interceptor)
          initial-ctx {:op :test/operation :system system :request {}}
          result (ic/run-pipeline initial-ctx pipeline)]

      ;; Check final response
      (is (= 201 (get-in result [:response :status])))
      (is (= {:id 1 :name "Test User"} (get-in result [:response :body])))

      ;; Check logging occurred
      (let [logs (get-log-entries logger)]
        (is (>= (count logs) 2)) ; At least start and success logs
        (is (some #(= "operation-start" (:event %)) logs))
        (is (some #(= "operation-success" (:event %)) logs)))

      ;; Metrics are currently no-ops, so we just verify no crashes occurred
      ;; and that the metrics object exists in the system
      (is (some? metrics))

      ;; No errors should be captured
      (is (empty? (get-captured-errors error-reporter))))))