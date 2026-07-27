(ns wagoe.platform.shell.adapters.database.config-test
  "Tests for database configuration loading and parsing."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.adapters.database.config :as config]
            [clojure.java.io :as io]))

;; =============================================================================
;; Test Data and Fixtures
;; =============================================================================

(def sample-config
  "Sample configuration for testing"
  {:active
   {:wagoe/sqlite
    {:db "test-database.db"
     :pool {:minimum-idle 1
            :maximum-pool-size 3
            :connection-timeout-ms 10000}}

    :wagoe/h2
    {:memory true
     :pool {:minimum-idle 1
            :maximum-pool-size 5}}}

   :inactive
   {:wagoe/postgresql
    {:host "localhost"
     :port 5432
     :dbname "test_db"
     :user "test_user"
     :password "test_password"
     :pool {:minimum-idle 5
            :maximum-pool-size 15}}

    :wagoe/mysql
    {:host "localhost"
     :port 3306
     :dbname "test_db"
     :user "test_user"
     :password "test_password"
     :pool {:minimum-idle 5
            :maximum-pool-size 15}}}

   :wagoe/settings
   {:name "test-app"
    :version "1.0.0"}})

;; =============================================================================
;; Configuration Loading Tests
;; =============================================================================

(deftest ^:unit test-load-config-dev
  (testing "Loading development configuration"
    (let [config (config/load-config "dev")]
      (is (map? config) "Config should be a map")
      (is (contains? config :active) "Config should have :active section")
      (is (contains? config :inactive) "Config should have :inactive section")

      ;; In current dev config PostgreSQL is the active database
      (is (contains? (:active config) :wagoe/postgresql)
          "PostgreSQL should be active in dev environment")

      ;; SQLite is available but inactive in dev
      (is (contains? (:inactive config) :wagoe/sqlite)
          "SQLite should be inactive in dev environment"))))

(deftest ^:unit test-load-config-test
  (testing "Loading test configuration"
    (let [config (config/load-config "test")]
      (is (map? config) "Config should be a map")
      (is (contains? config :active) "Config should have :active section")

      ;; Check that H2 is active in test
      (is (contains? (:active config) :wagoe/h2)
          "H2 should be active in test environment")

      ;; Check H2 is configured for in-memory
      (let [h2-config (get-in config [:active :wagoe/h2])]
        (is (true? (:memory h2-config))
            "H2 should be configured for in-memory in test environment")))))

(deftest ^:unit test-load-config-prod
  (testing "Loading production configuration"
    (let [config (config/load-config "prod")]
      (is (map? config) "Config should be a map")
      (is (contains? config :active) "Config should have :active section")

      ;; Check that PostgreSQL is active in prod
      (is (contains? (:active config) :wagoe/postgresql)
          "PostgreSQL should be active in prod environment")

      ;; Check PostgreSQL pool configuration
      (let [pg-config (get-in config [:active :wagoe/postgresql])]
        (is (contains? pg-config :pool)
            "PostgreSQL should have pool configuration")
        (is (>= (get-in pg-config [:pool :maximum-pool-size]) 10)
            "Production PostgreSQL should have large pool size")))))

(deftest ^:unit test-load-config-nonexistent
  (testing "Loading nonexistent configuration should throw"
    (is (thrown? Exception (config/load-config "nonexistent"))
        "Should throw exception for nonexistent environment")))

;; =============================================================================
;; Configuration Validation Tests
;; =============================================================================

(deftest ^:unit test-get-active-adapters
  (testing "Extracting active adapters from configuration"
    (let [active-adapters (config/get-active-adapters-from-config sample-config)]
      (is (map? active-adapters) "Active adapters should be a map")
      (is (= 2 (count active-adapters)) "Should have 2 active adapters")
      (is (contains? active-adapters :wagoe/sqlite)
          "Should contain SQLite adapter")
      (is (contains? active-adapters :wagoe/h2)
          "Should contain H2 adapter")
      (is (not (contains? active-adapters :wagoe/postgresql))
          "Should not contain inactive PostgreSQL adapter"))))

(deftest ^:unit test-get-inactive-adapters
  (testing "Extracting inactive adapters from configuration"
    (let [inactive-adapters (config/get-inactive-adapters sample-config)]
      (is (map? inactive-adapters) "Inactive adapters should be a map")
      (is (= 2 (count inactive-adapters)) "Should have 2 inactive adapters")
      (is (contains? inactive-adapters :wagoe/postgresql)
          "Should contain PostgreSQL adapter")
      (is (contains? inactive-adapters :wagoe/mysql)
          "Should contain MySQL adapter")
      (is (not (contains? inactive-adapters :wagoe/sqlite))
          "Should not contain active SQLite adapter"))))

(deftest ^:unit test-get-all-database-configs
  (testing "Getting all database configurations (active + inactive)"
    (let [all-configs (config/get-all-database-configs sample-config)]
      (is (map? all-configs) "All configs should be a map")
      (is (= 4 (count all-configs)) "Should have 4 total database configs")
      (is (contains? all-configs :wagoe/sqlite) "Should contain SQLite")
      (is (contains? all-configs :wagoe/h2) "Should contain H2")
      (is (contains? all-configs :wagoe/postgresql) "Should contain PostgreSQL")
      (is (contains? all-configs :wagoe/mysql) "Should contain MySQL"))))

;; =============================================================================
;; Configuration Structure Validation Tests
;; =============================================================================

(deftest ^:unit test-validate-config-structure
  (testing "Configuration structure validation"
    (testing "Valid configuration"
      (is (config/valid-config-structure? sample-config)
          "Sample config should be valid"))

    (testing "Missing :active section"
      (let [invalid-config (dissoc sample-config :active)]
        (is (not (config/valid-config-structure? invalid-config))
            "Config missing :active should be invalid")))

    (testing "Missing :inactive section"
      (let [invalid-config (dissoc sample-config :inactive)]
        (is (not (config/valid-config-structure? invalid-config))
            "Config missing :inactive should be invalid")))

    (testing "Empty configuration"
      (is (not (config/valid-config-structure? {}))
          "Empty config should be invalid"))

    (testing "Non-map configuration"
      (is (not (config/valid-config-structure? "not-a-map"))
          "Non-map config should be invalid"))))

(deftest ^:unit test-validate-adapter-configs
  (testing "Individual adapter configuration validation"
    (testing "Valid SQLite config"
      (let [sqlite-config {:db "test.db"
                           :pool {:minimum-idle 1
                                  :maximum-pool-size 5}}]
        (is (config/valid-adapter-config? :wagoe/sqlite sqlite-config)
            "Valid SQLite config should pass validation")))

    (testing "Valid PostgreSQL config"
      (let [pg-config {:host "localhost"
                       :port 5432
                       :dbname "testdb"
                       :user "testuser"
                       :password "testpass"}]
        (is (config/valid-adapter-config? :wagoe/postgresql pg-config)
            "Valid PostgreSQL config should pass validation")))

    (testing "Valid H2 config"
      (let [h2-config {:memory true}]
        (is (config/valid-adapter-config? :wagoe/h2 h2-config)
            "Valid H2 config should pass validation")))

    (testing "Invalid config - missing required fields"
      (let [invalid-pg-config {:host "localhost"}] ; missing required fields
        (is (not (config/valid-adapter-config? :wagoe/postgresql invalid-pg-config))
            "PostgreSQL config missing required fields should fail validation")))))

;; =============================================================================
;; Environment Detection Tests
;; =============================================================================

(deftest ^:unit test-detect-environment
  (testing "Environment detection from system properties"
    (testing "Environment set via system property"
      (System/setProperty "env" "test")
      (is (= "test" (config/detect-environment))
          "Should detect environment from system property")
      (System/clearProperty "env"))

    ;; Env vars can't be modified inside a JVM (and the documented test
    ;; invocation exports WAG_ENV=test), so exercise the env-var branches
    ;; through the config/getenv seam instead of the real environment.
    (testing "System property takes precedence over env vars"
      (System/setProperty "env" "prop-env")
      (with-redefs [config/getenv (constantly "env-env")]
        (is (= "prop-env" (config/detect-environment))))
      (System/clearProperty "env"))

    (testing "WAG_ENV wins over ENV and ENVIRONMENT"
      (System/clearProperty "env")
      (with-redefs [config/getenv {"WAG_ENV" "from-bnd" "ENV" "from-env"}]
        (is (= "from-bnd" (config/detect-environment))))
      (with-redefs [config/getenv {"ENV" "from-env" "ENVIRONMENT" "from-environment"}]
        (is (= "from-env" (config/detect-environment))))
      (with-redefs [config/getenv {"ENVIRONMENT" "from-environment"}]
        (is (= "from-environment" (config/detect-environment)))))

    (testing "Defaults to 'dev' when nothing is set"
      (System/clearProperty "env")
      (with-redefs [config/getenv (constantly nil)]
        (is (= "dev" (config/detect-environment))
            "Should default to 'dev' when env not set")))))

;; =============================================================================
;; Configuration Merging and Override Tests
;; =============================================================================

(deftest ^:unit test-merge-configurations
  (testing "Merging configurations for override scenarios"
    (let [base-config {:active {:wagoe/sqlite {:db "base.db"}}
                       :inactive {:wagoe/h2 {:memory true}}}
          override-config {:active {:wagoe/h2 {:memory false :db "override.db"}}
                           :inactive {:wagoe/sqlite {:db "moved.db"}}}
          merged (config/merge-configs base-config override-config)]

      (is (contains? (:active merged) :wagoe/h2)
          "H2 should be moved to active")
      (is (contains? (:inactive merged) :wagoe/sqlite)
          "SQLite should be moved to inactive")
      (is (= "override.db" (get-in merged [:active :wagoe/h2 :db]))
          "H2 config should be updated with override values"))))

;; =============================================================================
;; Performance and Edge Case Tests
;; =============================================================================

(deftest ^:unit test-config-loading-performance
  (testing "Configuration loading should be reasonably fast"
    (let [start-time (System/nanoTime)
          _ (config/load-config "dev")
          end-time (System/nanoTime)
          duration-ms (/ (- end-time start-time) 1000000.0)]
      (is (< duration-ms 100) ; Should load in under 100ms
          (str "Config loading took " duration-ms "ms, should be under 100ms")))))

(deftest ^:unit test-config-caching
  (testing "Configuration should be cached for performance"
    (let [config1 (config/load-config "dev")
          config2 (config/load-config "dev")]
      ;; This test assumes caching is implemented
      ;; If not implemented yet, this test documents the expected behavior
      (is (identical? config1 config2)
          "Same environment config should return cached instance"))))

(deftest ^:unit test-concurrent-config-loading
  (testing "Configuration loading should be thread-safe"
    (let [results (atom [])
          threads (for [_i (range 10)]
                    (Thread. #(swap! results conj (config/load-config "dev"))))]

      ; Start all threads
      (doseq [t threads] (.start t))

      ; Wait for completion
      (doseq [t threads] (.join t))

      ; All results should be identical (cached) and valid
      (let [configs @results]
        (is (= 10 (count configs)) "Should have 10 results")
        (is (every? map? configs) "All results should be maps")
        (is (apply = configs) "All configs should be identical (cached)")))))

;; =============================================================================
;; Integration Tests with Real Config Files
;; =============================================================================

(deftest ^:unit test-real-config-files-exist
  (testing "Real configuration files should exist and be loadable"
    (doseq [env ["dev" "test" "prod"]]
      (testing (str "Environment: " env)
        (let [config-path (str "resources/conf/" env "/config.edn")]
          (is (.exists (io/file config-path))
              (str "Config file should exist: " config-path))

          (let [config (config/load-config env)]
            (is (map? config) (str "Config should be a map for env: " env))
            (is (contains? config :active)
                (str "Config should have :active section for env: " env))
            (is (seq (:active config))
                (str "Config should have at least one active adapter for env: " env))))))))

;; Run all tests
(defn run-config-tests []
  (clojure.test/run-tests 'wagoe.platform.shell.adapters.database.config-test))
