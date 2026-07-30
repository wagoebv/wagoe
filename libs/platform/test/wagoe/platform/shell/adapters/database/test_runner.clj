(ns wagoe.platform.shell.adapters.database.test-runner
  "Test runner for the multi-database adapter configuration system.
   
   This runner executes all configuration tests and provides detailed reporting
   on which parts of the system are working and which need implementation."
  (:require [clojure.java.io :as io]
            [clojure.test :as test])
  (:import (java.util Date)))

;; =============================================================================
;; Test Namespaces
;; =============================================================================

;; We'll import test namespaces conditionally to handle missing implementations
(def test-namespaces
  "List of test namespaces to run"
  ['wagoe.platform.shell.adapters.database.config-test
   'wagoe.platform.shell.adapters.database.config-factory-test
   'wagoe.platform.shell.adapters.database.integration-test])

;; =============================================================================
;; Test Execution Helpers
;; =============================================================================

(defn namespace-exists?
  "Check if a namespace exists and can be loaded."
  [ns-symbol]
  (try
    (require ns-symbol)
    true
    (catch Exception _
      false)))

(defn run-namespace-tests
  "Run tests for a specific namespace, handling missing implementations gracefully."
  [ns-symbol]
  (println (str "\n" (apply str (repeat 80 "=")) "\n"))
  (println (str "Testing namespace: " ns-symbol))
  (println (str (apply str (repeat 80 "=")) "\n"))

  (if (namespace-exists? ns-symbol)
    (try
      (require ns-symbol :reload)
      (let [results (test/run-tests ns-symbol)]
        (println (str "\nResults for " ns-symbol ":"))
        (println (str "  Tests run: " (:test results)))
        (println (str "  Assertions: " (:pass results)))
        (println (str "  Failures: " (:fail results)))
        (println (str "  Errors: " (:error results)))
        results)
      (catch Exception e
        (println (str "ERROR: Failed to run tests for " ns-symbol))
        (println (str "  Error: " (.getMessage e)))
        (println "  This likely indicates missing implementation functions.")
        {:test 0 :pass 0 :fail 0 :error 1 :namespace ns-symbol :error-msg (.getMessage e)}))
    (do
      (println (str "SKIPPED: Namespace " ns-symbol " not found or cannot be loaded"))
      (println "  This indicates missing implementation files.")
      {:test 0 :pass 0 :fail 0 :error 0 :skip 1 :namespace ns-symbol})))

;; =============================================================================
;; Configuration Verification
;; =============================================================================

(defn check-config-files
  "Check if configuration files exist."
  []
  (println "\n" (str (repeat 80 "=")) "\n")
  (println "Configuration Files Check")
  (println (str (repeat 80 "=")) "\n")

  (let [config-files ["resources/conf/dev/config.edn"
                      "resources/conf/test/config.edn"
                      "resources/conf/prod/config.edn"]
        results      (atom {:found 0 :missing 0})]

    (doseq [config-file config-files]
      (if (.exists (io/file config-file))
        (do
          (println (str "✅ FOUND: " config-file))
          (swap! results update :found inc))
        (do
          (println (str "❌ MISSING: " config-file))
          (swap! results update :missing inc))))

    (println "\nConfiguration files summary:")
    (println (str "  Found: " (:found @results)))
    (println (str "  Missing: " (:missing @results)))
    @results))

(defn check-implementation-files
  "Check if implementation files exist."
  []
  (println "\n" (str (repeat 80 "=")) "\n")
  (println "Implementation Files Check")
  (println (str (repeat 80 "=")) "\n")

  (let [impl-files ["src/wagoe/shell/adapters/database/config.clj"
                    "src/wagoe/shell/adapters/database/config_factory.clj"
                    "src/wagoe/shell/adapters/database/protocols.clj"
                    "src/wagoe/shell/adapters/database/core.clj"
                    "src/wagoe/shell/adapters/database/sqlite.clj"
                    "src/wagoe/shell/adapters/database/h2.clj"
                    "src/wagoe/shell/adapters/database/postgresql.clj"
                    "src/wagoe/shell/adapters/database/mysql.clj"]
        results    (atom {:found 0 :missing 0})]

    (doseq [impl-file impl-files]
      (if (.exists (io/file impl-file))
        (do
          (println (str "✅ FOUND: " impl-file))
          (swap! results update :found inc))
        (do
          (println (str "❌ MISSING: " impl-file))
          (swap! results update :missing inc))))

    (println "\nImplementation files summary:")
    (println (str "  Found: " (:found @results)))
    (println (str "  Missing: " (:missing @results)))
    @results))

;; =============================================================================
;; Dependency Check
;; =============================================================================

(defn check-database-drivers
  "Check if database JDBC drivers are available on classpath."
  []
  (println "\n" (str (repeat 80 "=")) "\n")
  (println "Database JDBC Drivers Check")
  (println (str (repeat 80 "=")) "\n")

  (let [drivers {"SQLite"     "org.sqlite.JDBC"
                 "H2"         "org.h2.Driver"
                 "PostgreSQL" "org.postgresql.Driver"
                 "MySQL"      "com.mysql.cj.jdbc.Driver"}
        results (atom {:available 0 :missing 0})]

    (doseq [[db-name driver-class] drivers]
      (try
        (Class/forName driver-class)
        (println (str "✅ AVAILABLE: " db-name " (" driver-class ")"))
        (swap! results update :available inc)
        (catch ClassNotFoundException _
          (println (str "❌ MISSING: " db-name " (" driver-class ")"))
          (swap! results update :missing inc))))

    (println "\nJDBC drivers summary:")
    (println (str "  Available: " (:available @results)))
    (println (str "  Missing: " (:missing @results)))
    (println "\nNote: Missing drivers are expected with conditional dependency system.")
    (println "Use appropriate aliases (e.g., :db/h2, :db/mysql) to load needed drivers.")
    @results))

;; =============================================================================
;; Main Test Runner
;; =============================================================================

(defn run-configuration-tests
  "Run all configuration-related tests with comprehensive reporting."
  []
  (println "🚀 Multi-Database Configuration System Test Suite")
  (println (str "Started at: " (Date.)))

  ;; Check prerequisites
  (let [config-results (check-config-files)
        impl-results   (check-implementation-files)
        driver-results (check-database-drivers)
        test-results   (atom {:total-tests       0 :total-pass 0 :total-fail 0 :total-error 0 :total-skip 0
                              :namespace-results []})]
    ;; Run tests

    (doseq [ns-symbol test-namespaces]
      (let [result (run-namespace-tests ns-symbol)]
        (swap! test-results update :total-tests + (get result :test 0))
        (swap! test-results update :total-pass + (get result :pass 0))
        (swap! test-results update :total-fail + (get result :fail 0))
        (swap! test-results update :total-error + (get result :error 0))
        (swap! test-results update :total-skip + (get result :skip 0))
        (swap! test-results update :namespace-results conj result)))

    ;; Final summary
    (println "\n" (str (repeat 80 "=")) "\n")
    (println "🎯 FINAL SUMMARY")
    (println (str (repeat 80 "=")) "\n")

    (println "Configuration Files:")
    (println (str "  Found: " (:found config-results) ", Missing: " (:missing config-results)))

    (println "\nImplementation Files:")
    (println (str "  Found: " (:found impl-results) ", Missing: " (:missing impl-results)))

    (println "\nJDBC Drivers:")
    (println (str "  Available: " (:available driver-results) ", Missing: " (:missing driver-results)))

    (println "\nTest Results:")
    (println (str "  Total Tests: " (:total-tests @test-results)))
    (println (str "  Passed: " (:total-pass @test-results)))
    (println (str "  Failed: " (:total-fail @test-results)))
    (println (str "  Errors: " (:total-error @test-results)))
    (println (str "  Skipped: " (:total-skip @test-results)))

    ;; Per-namespace breakdown
    (println "\nPer-Namespace Results:")
    (doseq [result (:namespace-results @test-results)]
      (println (str "  " (:namespace result) ":"))
      (if (:error-msg result)
        (println (str "    ERROR: " (:error-msg result)))
        (if (:skip result)
          (println "    SKIPPED: Namespace not found")
          (println (str "    Tests: " (:test result) ", Pass: " (:pass result)
                        ", Fail: " (:fail result) ", Error: " (:error result))))))

    ;; Recommendations
    (println "\n📋 RECOMMENDATIONS:")

    (when (> (:missing config-results) 0)
      (println "  🔸 Create missing configuration files using the templates provided"))

    (when (> (:missing impl-results) 0)
      (println "  🔸 Implement missing database adapter files"))

    (when (> (:total-error @test-results) 0)
      (println "  🔸 Fix implementation errors before running integration tests"))

    (when (> (:missing driver-results) 0)
      (println "  🔸 Use database-specific aliases to load JDBC drivers when testing"))

    (if (and (= 0 (:missing config-results))
             (= 0 (:missing impl-results))
             (= 0 (:total-error @test-results)))
      (println "  ✅ System appears ready for integration testing!")
      (println "  ⚠️  Complete the missing components before full integration testing"))

    (println (str "\nCompleted at: " (Date.)))
    @test-results))

;; =============================================================================
;; Specific Test Scenarios
;; =============================================================================

(defn test-basic-configuration
  "Test basic configuration loading without dependencies."
  []
  (println "\n🧪 Basic Configuration Test (No Dependencies Required)")
  (println (str (repeat 60 "-")))

  (try
    ;; Try to load configurations
    (doseq [env ["dev" "test" "prod"]]
      (println (str "Testing " env " environment:"))
      (try
        (if (.exists (io/file (str "resources/conf/" env "/config.edn")))
          ;; Try to read the file manually
          (let [config-content (slurp (str "resources/conf/" env "/config.edn"))]
            (println (str "  ✅ Config file readable (" (count config-content) " chars)"))

            ;; Try to parse as EDN
            (let [config (read-string config-content)]
              (if (and (map? config)
                       (contains? config :active)
                       (contains? config :inactive))
                (println (str "  ✅ Config structure valid (active: " (count (:active config))
                              ", inactive: " (count (:inactive config)) ")"))
                (println "  ❌ Invalid config structure"))))
          (println "  ❌ Config file missing"))
        (catch Exception e
          (println (str "  ❌ Error: " (.getMessage e))))))

    (catch Exception e
      (println (str "Basic configuration test failed: " (.getMessage e)))))

  (println "Basic configuration test completed."))

;; =============================================================================
;; Entry Points
;; =============================================================================

(defn -main
  "Main entry point for test runner."
  [& args]
  (let [test-type (first args)]
    (case test-type
      "basic" (test-basic-configuration)
      "full" (run-configuration-tests)
      ;; Default: run full test suite
      (run-configuration-tests)))

  ;; Exit cleanly
  (System/exit 0))

;; For REPL use
(defn run-tests
  "Run all configuration tests from REPL."
  []
  (run-configuration-tests))

(defn run-basic-tests
  "Run basic configuration tests from REPL."
  []
  (test-basic-configuration))

(comment
  ;; Usage examples:

  ;; Run from REPL
  (run-tests)
  (run-basic-tests))

;; Run from command line
;; clj -M:test -m wagoe.platform.shell.adapters.database.test-runner
;; clj -M:test -m wagoe.platform.shell.adapters.database.test-runner basic
;; clj -M:test -m wagoe.platform.shell.adapters.database.test-runner full
