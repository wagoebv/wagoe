(ns wagoe.platform.shell.interfaces.cli.middleware-test
  "Tests for CLI middleware with enhanced error handling"
  (:require [wagoe.platform.shell.interfaces.cli.middleware :as cli-middleware]
            [wagoe.platform.shell.utils.error-handling :as eh]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- create-successful-operation
  "Create an operation that succeeds"
  [result]
  (fn [_context] result))

(defn- create-failing-operation
  "Create an operation that throws an exception"
  [exception]
  (fn [_context] (throw exception)))

(defn- create-test-context
  "Create a test CLI context"
  [& {:keys [operation user-id additional]}]
  (merge {:operation (or operation "test-operation")
          :user-id (or user-id "test-user")}
         (or additional {})))

;; =============================================================================
;; CLI Error Reporting Middleware Tests
;; =============================================================================

(deftest ^:unit test-with-cli-error-reporting-success
  (testing "executes successful operation without interference"
    (let [context (create-test-context)
          operation (create-successful-operation {:status :success :data "test-result"})
          result (cli-middleware/with-cli-error-reporting context operation)]

      (is (= {:status :success :data "test-result"} result))))

  (testing "handles nil operation context gracefully"
    (let [operation (create-successful-operation {:ok true})
          result (cli-middleware/with-cli-error-reporting nil operation)]

      (is (= {:ok true} result))))

  (testing "preserves operation return values exactly"
    (let [context (create-test-context)
          complex-result {:users [{:id 1 :name "John"} {:id 2 :name "Jane"}]
                          :pagination {:page 1 :total 50}
                          :metadata {:query-time-ms 25}}
          operation (create-successful-operation complex-result)
          result (cli-middleware/with-cli-error-reporting context operation)]

      (is (= complex-result result)))))

(deftest ^:unit test-with-cli-error-reporting-errors
  (testing "enhances exceptions with CLI context"
    (let [context (create-test-context :operation "create-user" :user-id "admin-123")
          exception (ex-info "Validation failed" {:field "email" :error "invalid-format"})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (is false "Should have thrown enhanced exception")
        (catch Exception e
          (let [data (ex-data e)]
            (is (= "Validation failed" (.getMessage e)))
            (is (= {:field "email" :error "invalid-format"} (:original-data data)))
            (is (contains? (:cli-context data) :operation))
            (is (contains? (:cli-context data) :user-id))
            (is (contains? (:cli-context data) :timestamp))
            (is (= "create-user" (get-in data [:cli-context :operation])))
            (is (= "admin-123" (get-in data [:cli-context :user-id]))))))))

  (testing "preserves original exception message"
    (let [context (create-test-context)
          original-message "Database connection failed"
          exception (ex-info original-message {:connection-timeout true})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (is (= original-message (.getMessage e)))))))

  (testing "handles exceptions without ex-data"
    (let [context (create-test-context :operation "system-check")
          exception (RuntimeException. "System error")
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (let [data (ex-data e)]
            (is (= "System error" (.getMessage e)))
            (is (contains? (:cli-context data) :operation))
            (is (= "system-check" (get-in data [:cli-context :operation]))))))))

  (testing "handles nested CLI contexts correctly"
    (let [outer-context (create-test-context :operation "batch-process" :user-id "batch-user")
          inner-context (create-test-context :operation "process-item" :user-id "item-user")
          exception (ex-info "Processing failed" {:item-id 123})

          inner-operation (create-failing-operation exception)
          outer-operation (fn [_]
                            (cli-middleware/with-cli-error-reporting inner-context inner-operation))]

      (try
        (cli-middleware/with-cli-error-reporting outer-context outer-operation)
        (catch Exception e
          (let [data (ex-data e)]
            (is (= "process-item" (get-in data [:cli-context :operation])))
            (is (= "item-user" (get-in data [:cli-context :user-id])))))))))

(deftest ^:unit test-context-enrichment
  (testing "enriches context with environment information"
    (let [context (create-test-context :operation "deploy" :user-id "deploy-user")
          exception (ex-info "Deployment failed" {:stage "production"})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (contains? cli-context :timestamp))
            (is (contains? cli-context :environment))
            (is (contains? cli-context :process-id))
            (is (inst? (:timestamp cli-context))))))))

  (testing "preserves additional context information"
    (let [context (create-test-context
                   :operation "migration"
                   :user-id "admin"
                   :additional {:migration-version "2.1.0"
                                :database "production"
                                :dry-run false})
          exception (ex-info "Migration error" {})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (= "migration" (:operation cli-context)))
            (is (= "admin" (:user-id cli-context)))
            (is (= "2.1.0" (:migration-version cli-context)))
            (is (= "production" (:database cli-context)))
            (is (false? (:dry-run cli-context))))))))

  (testing "handles empty context gracefully"
    (let [context {}
          exception (ex-info "Empty context test" {})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (contains? cli-context :timestamp))
            (is (contains? cli-context :environment))
            (is (nil? (:operation cli-context)))
            (is (nil? (:user-id cli-context)))))))))

;; =============================================================================
;; Error Formatting Integration Tests
;; =============================================================================

(deftest ^:unit test-error-formatting-integration
  (testing "errors can be formatted with CLI context"
    (let [context (create-test-context :operation "validate-config" :user-id "config-admin")
          exception (ex-info "Configuration invalid" {:config-file "app.yml" :line 15})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception enhanced-e
          (let [formatted-basic (eh/format-cli-error enhanced-e :include-context false)
                formatted-with-context (eh/format-cli-error enhanced-e :include-context true)]

            (is (str/includes? formatted-basic "Configuration invalid"))
            (is (str/includes? formatted-basic "config-file"))
            (is (not (str/includes? formatted-basic "Operation: validate-config")))

            (is (str/includes? formatted-with-context "Configuration invalid"))
            (is (str/includes? formatted-with-context "Operation: validate-config"))
            (is (str/includes? formatted-with-context "User ID: config-admin")))))))

  (testing "error formatting handles complex data structures"
    (let [context (create-test-context :operation "bulk-import"
                                       :additional {:batch-size 100 :source "csv"})
          complex-error-data {:validation-errors [{:row 5 :field "email" :message "invalid"}
                                                  {:row 12 :field "age" :message "out of range"}]
                              :processed-count 50
                              :failed-count 2}
          exception (ex-info "Bulk import failed" complex-error-data)
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (let [formatted (eh/format-cli-error e :include-context true)]
            (is (str/includes? formatted "Bulk import failed"))
            (is (str/includes? formatted "Operation: bulk-import"))
            (is (str/includes? formatted "batch-size"))
            (is (str/includes? formatted "validation-errors"))))))))

;; =============================================================================
;; Performance and Edge Case Tests
;; =============================================================================

(deftest ^:unit test-performance-and-edge-cases
  (testing "handles large context data efficiently"
    (let [large-context (create-test-context
                         :additional {:large-data (vec (range 1000))
                                      :metadata (zipmap (map str (range 100))
                                                        (range 100))})
          exception (ex-info "Large context test" {})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting large-context operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (contains? cli-context :large-data))
            (is (contains? cli-context :metadata))
            (is (= 1000 (count (:large-data cli-context)))))))))

  (testing "handles recursive data structures safely"
    (let [recursive-map (atom {})
          _ (swap! recursive-map assoc :self @recursive-map)
          context (create-test-context :additional {:recursive @recursive-map})
          exception (ex-info "Recursive test" {})
          operation (create-failing-operation exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (is false "Expected exception to be thrown")
        (catch Exception e
          (is (= "Recursive test" (ex-message e))
              "Should preserve original exception message")
          (is (contains? (ex-data e) :cli-context))))))

  (testing "preserves exception cause chain"
    (let [context (create-test-context)
          root-cause (RuntimeException. "Root cause")
          intermediate-cause (ex-info "Intermediate" {:level 2} root-cause)
          main-exception (ex-info "Main error" {:level 1} intermediate-cause)
          operation (create-failing-operation main-exception)]

      (try
        (cli-middleware/with-cli-error-reporting context operation)
        (catch Exception e
          (is (= "Main error" (.getMessage e)))
          (is (= "Intermediate" (.getMessage (.getCause e))))
          (is (= "Root cause" (.getMessage (.getCause (.getCause e))))))))))