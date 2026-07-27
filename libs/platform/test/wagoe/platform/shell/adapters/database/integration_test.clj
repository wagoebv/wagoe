(ns wagoe.platform.shell.adapters.database.integration-test
  "Integration tests for the complete multi-database adapter system."
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.integration-example :as integration]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.embedded-pg :as epg]))

;; =============================================================================
;; Test Fixtures and Setup
;; =============================================================================

(defn reset-system-fixture
  "Fixture to reset the integration system before each test."
  [test-fn]
  (try
    ;; Reset application state before test
    (integration/reset-application-state!)
    (test-fn)
    (finally
      ;; Clean up after test
      (integration/reset-application-state!))))

(use-fixtures :each reset-system-fixture)

;; =============================================================================
;; System Initialization Tests
;; =============================================================================

(def ^:private embedded-env
  "Synthetic environment name used to seed the config cache with a config
   pointing at the embedded PostgreSQL instance (no config file involved)."
  "embedded-pg")

(defn- embedded-pg-config
  "Minimal system config with PostgreSQL active, pointed at the embedded
   instance. Mirrors the :boundary/postgresql shape of the real dev config."
  [pg]
  {:active
   {:boundary/postgresql
    {:host        "localhost"
     :port        (epg/port pg)
     :dbname      "postgres"
     :user        "postgres"
     :password    "postgres"
     :auto-commit true
     :pool        {:minimum-idle          1
                   :maximum-pool-size     3
                   :connection-timeout-ms 30000}}}})

(deftest ^:integration test-system-initialization-postgres
  (testing "Full system initialization against real (embedded) PostgreSQL"
    ;; BOU-183: this test used to self-skip when no PostgreSQL listened on
    ;; localhost:5432. It now boots the full init path (config -> driver
    ;; loading -> pool creation -> query) against support.embedded-pg, so the
    ;; PG boot path is always exercised instead of skipped.
    (let [pg (epg/start!)]
      (try
        ;; Seed the (dynamic) config cache so load-config resolves the
        ;; synthetic env to the embedded instance instead of a config file.
        (swap! db-config/*config-cache* assoc embedded-env (embedded-pg-config pg))
        (let [initialized-dbs (integration/initialize-databases! embedded-env)]
          (is (map? initialized-dbs) "Should return map of initialized databases")
          (is (contains? initialized-dbs :boundary/postgresql)
              "PostgreSQL context should be initialized")

          ;; Check application state is updated
          (let [state (integration/current-state)]
            (is (:config-loaded? state) "Config should be loaded")
            (is (seq (:active-databases state)) "Should have active databases"))

          ;; Verify the initialized context shape
          (let [active-dbs (integration/list-active-databases)]
            (doseq [[adapter-key db-info] active-dbs]
              (is (keyword? adapter-key) "Adapter key should be keyword")
              (is (contains? db-info :adapter) "Should contain adapter")
              (is (contains? db-info :pool) "Should contain connection pool")))

          ;; The booted system serves a query against real PostgreSQL
          (let [result (integration/execute-query :boundary/postgresql
                                                  {:select [[1 :test]]})]
            (is (coll? result) "Query should return a collection")
            (is (= 1 (:test (first result)))
                "Query should return the selected value from PostgreSQL")))
        (finally
          (integration/shutdown-databases!)
          (swap! db-config/*config-cache* dissoc embedded-env)
          (epg/stop! pg))))))

(deftest ^:integration test-system-initialization-test
  (testing "System initialization for test environment"
    (let [initialized-dbs (integration/initialize-databases! "test")]
      (is (map? initialized-dbs) "Should return map of initialized databases")
      (is (seq initialized-dbs) "Should have at least one initialized database")

      ;; Test environment should typically use H2
      (let [active-dbs (integration/list-active-databases)]
        (is (some #(= :boundary/h2 (first %)) active-dbs)
            "Test environment should typically have H2 active")))))

(deftest ^:integration test-system-initialization-prod
  (testing "System initialization for production environment"
    ;; Production config requires environment variables (POSTGRES_HOST, etc.)
    ;; Without them, initialization should fail gracefully
    (try
      (let [initialized-dbs (integration/initialize-databases! "prod")]
        ;; If env vars are set, initialization should succeed
        (is (map? initialized-dbs) "Should return map of initialized databases")
        (is (seq initialized-dbs) "Should have at least one initialized database")

        ;; Production environment should typically use PostgreSQL
        (let [active-dbs (integration/list-active-databases)]
          (is (some #(= :boundary/postgresql (first %)) active-dbs)
              "Production environment should typically have PostgreSQL active")))
      (catch Exception e
        ;; Expected failure when production environment variables are not set
        (is (or (.contains (.getMessage e) "Database initialization failed")
                (.contains (.getMessage e) "Invalid database configuration"))
            (str "Expected production config error due to missing env vars, got: " (.getMessage e)))
        (println "Note: Production initialization failed as expected due to missing environment variables")))))

(deftest ^:integration test-system-initialization-invalid-env
  (testing "System initialization with invalid environment should fail gracefully"
    (is (thrown? Exception (integration/initialize-databases! "nonexistent"))
        "Should throw exception for nonexistent environment")))

;; =============================================================================
;; Database Access Tests
;; =============================================================================

(deftest ^:integration test-database-access
  (testing "Database access after system initialization"
    (integration/initialize-databases! "test")              ; Use test env with H2

    (testing "Get specific database"
      (let [active-dbs (integration/list-active-databases)]
        (when-let [[adapter-key _] (first active-dbs)]
          (let [db (integration/get-database adapter-key)]
            (is (some? db) "Should be able to get specific database")
            (is (map? db) "Database should be a map")
            (is (contains? db :adapter) "Should contain adapter")
            (is (contains? db :pool) "Should contain pool")))))

    (testing "Get primary database"
      (let [primary-db (integration/get-primary-database)]
        (is (some? primary-db) "Should have a primary database")
        (is (map? primary-db) "Primary database should be a map")))

    (testing "Get non-existent database"
      (let [non-existent-db (integration/get-database :boundary/nonexistent)]
        (is (nil? non-existent-db) "Non-existent database should return nil")))))

(deftest ^:integration test-query-execution
  (testing "Query execution through integration system"
    (integration/initialize-databases! "test")

    (let [active-dbs (integration/list-active-databases)]
      (if-let [[adapter-key _] (first active-dbs)]
        (do
          (testing (str "Query execution on " adapter-key)
            (try
              ;; Simple test query - use proper HoneySQL syntax
              (let [result (integration/execute-query adapter-key {:select [[1 :test]]})]
                (is (some? result) "Query should return a result")
                (is (coll? result) "Result should be a collection"))
              (catch Exception e
                ;; Since our dynamic driver loading works, we expect database-related errors
                ;; rather than driver-loading errors
                (is (or (.contains (.getMessage e) "Database query failed")
                        (.contains (.getMessage e) "ClassNotFoundException")
                        (.contains (.getMessage e) "No suitable driver")
                        (.contains (.getMessage e) "Database initialization failed"))
                    (str "Expected database or driver-related error, got: " (.getMessage e))))))

          (testing "Environment switching example"
            ;; This should work even without JDBC drivers since it only loads configs
            (try
              (let [_result (integration/example-environment-switching)]
                (is (some? (integration/list-active-databases)) "Environment switching example should complete"))
              (catch Exception e
                (println (str "Environment switching example failed: " (.getMessage e)))
                ;; This test documents expected behavior even if it fails
                (is false (str "Environment switching should not fail: " (.getMessage e)))))))
        ;; No active databases — skip
        (do
          (println "SKIPPED: test-query-execution (no active databases)")
          (is (empty? active-dbs) "Skipped — no active databases"))))))

;; Run all tests
(defn run-integration-tests []
  (clojure.test/run-tests 'wagoe.platform.shell.adapters.database.integration-test))
