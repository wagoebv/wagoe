(ns wagoe.platform.shell.adapters.database.config-factory
  "Configuration-driven database adapter factory.
   
   This namespace provides factory functions that only load database adapters
   that are marked as :active in the configuration files. This enables optimized
   deployments where only necessary JDBC drivers are loaded and initialized.
   
   Key Features:
   - Only loads adapters marked as :active in config.edn
   - Graceful error handling for inactive adapters
   - Environment-aware adapter loading
   - Automatic configuration validation
   - JDBC driver optimization
   
   Usage:
     (require '[wagoe.platform.shell.adapters.database.config-factory :as cf])
     
     ;; Load only active adapters for current environment
     (cf/create-active-contexts)
     
     ;; Create specific adapter context from config
     (cf/create-config-context \"dev\" :boundary/sqlite)"
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.common.core :as core]
            [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Configuration-Aware Adapter Loading
;; =============================================================================

(defn- load-adapter-constructor-if-active
  "Dynamically load adapter constructor only if adapter is active in config.
   
   Args:
     adapter-type: Keyword adapter type (:sqlite, :postgresql, :mysql, :h2)
     env: Environment string (e.g. 'dev', 'prod')
     
   Returns:
     Constructor function if adapter is active, nil otherwise
     
   Throws:
     RuntimeException if adapter is active but cannot be loaded"
  [adapter-type env]
  (if (db-config/adapter-active? adapter-type env)
    (do
      (log/info "Loading active database adapter" {:adapter adapter-type :env env})
      (let [adapter-ns (case adapter-type
                         :sqlite 'wagoe.platform.shell.adapters.database.sqlite.core
                         :postgresql 'wagoe.platform.shell.adapters.database.postgresql.core
                         :mysql 'wagoe.platform.shell.adapters.database.mysql.core
                         :h2 'wagoe.platform.shell.adapters.database.h2.core)
            constructor-fn (case adapter-type
                             :sqlite 'new-adapter
                             :postgresql 'new-adapter
                             :mysql 'new-adapter
                             :h2 'new-adapter)]

        (try
          (require adapter-ns)
          (let [ns-obj (find-ns adapter-ns)]
            (if ns-obj
              (ns-resolve ns-obj constructor-fn)
              (throw (RuntimeException. (str "Active adapter namespace not found: " adapter-ns)))))
          (catch Exception e
            (throw (RuntimeException.
                    (str "Failed to load active database adapter: " adapter-type
                         " for environment: " env ". Error: " (.getMessage e)) e))))))
    (do
      (log/debug "Skipping inactive database adapter" {:adapter adapter-type :env env})
      nil)))

(defn create-config-adapter
  "Create database adapter from configuration if it's active.
   
   Args:
     adapter-type: Keyword adapter type (:sqlite, :postgresql, :mysql, :h2)
     env: Environment string (e.g. 'dev', 'prod')
     
   Returns:
     Database adapter implementing DBAdapter protocol
     
   Throws:
     IllegalStateException if adapter is not active
     RuntimeException if adapter loading fails"
  [adapter-type env]
  (if-let [constructor-fn (load-adapter-constructor-if-active adapter-type env)]
    (do
      (log/debug "Creating configured database adapter" {:adapter adapter-type :env env})
      (constructor-fn))
    (throw (IllegalStateException.
            (str "Database adapter " adapter-type " is not active in environment " env
                 ". Check your configuration in conf/" env "/config.edn")))))

;; =============================================================================
;; Configuration-Based Context Creation
;; =============================================================================

(defn create-config-context
  "Create database context from configuration key.
   
   Args:
     env: Environment string
     config-key: Configuration key (e.g. :boundary/sqlite)
     
   Returns:
     Database context map {:adapter adapter :datasource datasource}
     
   Example:
     (create-config-context \"dev\" :boundary/sqlite)"
  [env config-key]
  (let [active-configs (db-config/get-active-db-configs env)]
    (if-let [db-config (get active-configs config-key)]
      (let [adapter (create-config-adapter (:adapter db-config) env)
            datasource (core/create-connection-pool adapter db-config)]
        (log/info "Created database context from configuration"
                  {:env env :config-key config-key :adapter (:adapter db-config)})
        {:adapter adapter
         :datasource datasource
         :config-key config-key
         :environment env})
      (throw (IllegalArgumentException.
              (str "Configuration key " config-key " not found in active configs for environment " env
                   ". Available keys: " (keys active-configs)))))))

(defn create-active-contexts
  "Create database contexts for all active configurations in environment.
   
   Args:
     env: Environment string (optional, defaults to detected environment)
     
   Returns:
     Map of config-key -> database context
     
   Example:
     (create-active-contexts \"dev\")
     ;; => {:boundary/sqlite {:adapter ... :datasource ...}}"
  ([]
   (create-active-contexts (db-config/detect-environment)))
  ([env]
   (let [active-configs (db-config/get-active-db-configs env)
         contexts (atom {})]

     (log/info "Creating database contexts for active configurations"
               {:env env :active-count (count active-configs)})

     (doseq [[config-key _db-config] active-configs]
       (try
         (let [ctx (create-config-context env config-key)]
           (swap! contexts assoc config-key ctx))
         (catch Exception e
           (log/error e "Failed to create context for configuration"
                      {:env env :config-key config-key})
           (throw e))))

     (let [result @contexts]
       (log/info "Created database contexts successfully"
                 {:env env :contexts (keys result)})
       result))))

;; =============================================================================
;; Environment-Aware Database Factory
;; =============================================================================

(defn get-active-adapter-types
  "Get list of active adapter types for environment"
  [env]
  (db-config/get-active-adapters env))

(defn adapter-available?
  "Check if an adapter type is available (active) in current environment"
  [adapter-type env]
  (db-config/adapter-active? adapter-type env))

(defn ensure-adapter-available!
  "Ensure adapter is available, throw helpful error if not"
  [adapter-type env]
  (when-not (adapter-available? adapter-type env)
    (let [available-adapters (get-active-adapter-types env)]
      (throw (IllegalStateException.
              (str "Database adapter " adapter-type " is not active in environment " env ". "
                   "Available adapters: " available-adapters ". "
                   "To activate this adapter, add it to the :active section in conf/" env "/config.edn"))))))

;; =============================================================================  
;; Configuration Validation and Health Checks
;; =============================================================================

(defn validate-environment-config
  "Validate database configuration for environment"
  [env]
  (log/info "Validating database configuration" {:env env})
  (let [validation-result (db-config/validate-database-configs env)]
    (if (:valid? validation-result)
      (log/info "Database configuration validation successful" {:env env})
      (log/error "Database configuration validation failed"
                 {:env env :errors (:errors validation-result)}))
    validation-result))

(defn health-check
  "Perform health check on all active database connections.
   
   Args:
     env: Environment string
     
   Returns:
     Map with health check results for each active adapter"
  [env]
  (log/info "Performing database health check" {:env env})
  (let [contexts (create-active-contexts env)
        results (atom {})]

    (doseq [[config-key ctx] contexts]
      (try
        (let [start-time (System/currentTimeMillis)
              test-result (core/execute-query! ctx {:select [1]})
              duration (- (System/currentTimeMillis) start-time)]
          (swap! results assoc config-key
                 {:status :healthy
                  :adapter (protocols/dialect (:adapter ctx))
                  :response-time-ms duration
                  :test-query-result test-result}))
        (catch Exception e
          (swap! results assoc config-key
                 {:status :unhealthy
                  :adapter (protocols/dialect (:adapter ctx))
                  :error (.getMessage e)
                  :exception-type (type e)}))))

    ;; Clean up contexts
    (doseq [[_ ctx] contexts]
      (core/close-connection-pool! (:datasource ctx)))

    (let [result @results
          healthy-count (count (filter #(= :healthy (:status %)) (vals result)))
          total-count (count result)]
      (log/info "Database health check completed"
                {:env env
                 :healthy-count healthy-count
                 :total-count total-count
                 :all-healthy? (= healthy-count total-count)})
      result)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn get-default-context
  "Get default database context for environment.
   
   Tries to find contexts in this order:
   1. SQLite (most common for development)
   2. First available active adapter
   
   Args:
     env: Environment string (optional)
     
   Returns:
     Database context or throws if no active adapters found"
  ([]
   (get-default-context (db-config/detect-environment)))
  ([env]
   (let [contexts (create-active-contexts env)]
     (cond
       ;; Prefer SQLite if available
       (:boundary/sqlite contexts) (:boundary/sqlite contexts)

       ;; Otherwise use first available
       (seq contexts) (val (first contexts))

       ;; No active adapters
       :else (throw (IllegalStateException.
                     (str "No active database adapters found in environment " env
                          ". Check your configuration in conf/" env "/config.edn")))))))

(defn with-config-context
  "Execute function with database context from configuration.
   
   Args:
     env: Environment string
     config-key: Configuration key (e.g. :boundary/sqlite)
     f: Function that takes database context
     
   Returns:
     Result of function execution"
  [env config-key f]
  (let [ctx (create-config-context env config-key)]
    (try
      (f ctx)
      (finally
        (core/close-connection-pool! (:datasource ctx))))))

(defn with-default-context
  "Execute function with default database context for environment.
   
   Args:
     env: Environment string (optional)
     f: Function that takes database context
     
   Returns:
     Result of function execution"
  ([f]
   (with-default-context (db-config/detect-environment) f))
  ([env f]
   (let [ctx (get-default-context env)]
     (try
       (f ctx)
       (finally
         (core/close-connection-pool! (:datasource ctx)))))))

;; =============================================================================
;; Test Compatibility Functions
;; =============================================================================

(defn list-available-adapters
  "List all available adapter types"
  []
  [:boundary/sqlite :boundary/h2 :boundary/postgresql :boundary/mysql :boundary/settings :boundary/logging])

(defn adapter-supported?
  "Check if adapter type is supported"
  [adapter-key]
  (contains? (set (list-available-adapters)) adapter-key))

(defn valid-adapter-config?
  "Validate adapter configuration (test compatibility)"
  [adapter-key config]
  (when-not (map? config)
    false)

  (case adapter-key
    :boundary/sqlite
    (and (contains? config :db)
         (string? (:db config)))

    :boundary/h2
    (or (and (contains? config :memory)
             (boolean? (:memory config)))
        (and (contains? config :db)
             (string? (:db config))))

    :boundary/postgresql
    (and (every? #(contains? config %) [:host :port :dbname :user :password])
         (string? (:host config))
         (integer? (:port config))
         (string? (:dbname config))
         (string? (:user config))
         (string? (:password config)))

    :boundary/mysql
    (and (every? #(contains? config %) [:host :port :dbname :user :password])
         (string? (:host config))
         (integer? (:port config))
         (string? (:dbname config))
         (string? (:user config))
         (string? (:password config)))

    :boundary/settings
    ;; Settings adapter can be any valid map - very permissive for now
    true

    :boundary/logging
    ;; Logging adapter can be any valid map - very permissive for now  
    true

    false))

(defn create-adapter
  "Create adapter instance (test compatibility function)"
  [adapter-key config]
  ;; Validate inputs
  (when (nil? config)
    (throw (IllegalArgumentException. "Configuration cannot be null")))

  (when-not (map? config)
    (throw (IllegalArgumentException. "Configuration must be a map")))

  (when-not (adapter-supported? adapter-key)
    (throw (IllegalArgumentException. (str "Unsupported adapter type: " adapter-key ". Supported types: " (list-available-adapters)))))

  ;; Validate configuration based on adapter type
  (when-not (valid-adapter-config? adapter-key config)
    (throw (IllegalArgumentException. (str "Invalid configuration for adapter type: " adapter-key))))

  ;; For now, create a mock adapter that satisfies the protocol
  ;; This will be replaced when actual adapter implementations are available
  (reify protocols/DBAdapter
    (dialect [_this]
      (case adapter-key
        :boundary/sqlite :sqlite
        :boundary/h2 :h2
        :boundary/postgresql :postgresql
        :boundary/mysql :mysql
        :boundary/settings :sqlite ; Use SQLite as underlying implementation for settings
        :boundary/logging :sqlite ; Use SQLite as underlying implementation for logging
        :unknown))

    (jdbc-driver [_this]
      (case adapter-key
        :boundary/sqlite "org.sqlite.JDBC"
        :boundary/h2 "org.h2.Driver"
        :boundary/postgresql "org.postgresql.Driver"
        :boundary/mysql "com.mysql.cj.jdbc.Driver"
        :boundary/settings "org.sqlite.JDBC" ; Use SQLite driver for settings
        :boundary/logging "org.sqlite.JDBC" ; Use SQLite driver for logging
        "unknown"))

    (jdbc-url [_this db-config]
      (case adapter-key
        :boundary/sqlite (str "jdbc:sqlite:" (or (:db config) (:database-path db-config)))
        :boundary/h2 (if (or (:memory config) (and (:database-path db-config) (str/starts-with? (str (:database-path db-config)) "mem:")))
                       (str "jdbc:h2:" (or (:database-path db-config) "mem:testdb"))
                       (str "jdbc:h2:file:" (or (:db config) (:database-path db-config))))
        :boundary/postgresql (str "jdbc:postgresql://"
                                  (or (:host config) (:host db-config "localhost")) ":"
                                  (or (:port config) (:port db-config 5432)) "/"
                                  (or (:dbname config) (:name db-config)))
        :boundary/mysql (str "jdbc:mysql://"
                             (or (:host config) (:host db-config "localhost")) ":"
                             (or (:port config) (:port db-config 3306)) "/"
                             (or (:dbname config) (:name db-config)))
        :boundary/settings (str "jdbc:sqlite:" (or (:database-path config) (:db config) "settings.db"))
        :boundary/logging (str "jdbc:sqlite:" (or (:database-path config) (:db config) "logging.db"))
        "jdbc:unknown"))

    (pool-defaults [_this]
      {:minimum-idle 2 :maximum-pool-size 10 :connection-timeout-ms 30000})

    (init-connection! [_this _datasource _db-config] nil)

    (build-where [_this _filters]
      ;; Simple implementation - just return nil for mock adapter
      nil)

    (boolean->db [_this boolean-value] boolean-value)

    (db->boolean [_this db-value] db-value)

    (table-exists? [_this _datasource _table-name] false)

    (get-table-info [_this _datasource _table-name] [])))

(defn create-active-adapters
  "Create all active adapters from configuration (test compatibility)"
  [config]
  ;; Validate that config is a map
  (when-not (map? config)
    (throw (IllegalArgumentException. "Configuration must be a map")))

  ;; Check if :active section exists
  (when-not (contains? config :active)
    (throw (IllegalArgumentException. "Configuration must contain an :active section")))

  (let [active-configs (:active config)
        supported-keys (set (list-available-adapters))]
    ;; Validate that :active section is a map
    (when-not (map? active-configs)
      (throw (IllegalArgumentException. "The :active section must be a map")))
    (into {}
          (comp
           (filter (fn [[adapter-key _]]
                     (contains? supported-keys adapter-key)))
           (map (fn [[adapter-key adapter-config]]
                  [adapter-key (create-adapter adapter-key adapter-config)])))
          active-configs)))

;; =============================================================================
;; Configuration Summary and Debugging
;; =============================================================================

(defn print-environment-summary
  "Print comprehensive summary of database configuration for environment"
  [env]
  (println (str "\n=== Database Environment Summary: " env " ==="))

  ;; Configuration summary
  (db-config/print-config-summary env)

  ;; Validation results
  (let [validation (validate-environment-config env)]
    (if (:valid? validation)
      (println "✅ Configuration Validation: PASSED")
      (do
        (println "❌ Configuration Validation: FAILED")
        (doseq [error (:errors validation)]
          (println "  Error:" (:adapter error) "-" (:error error))))))

  ;; Available adapters
  (let [active-adapters (get-active-adapter-types env)]
    (println "\n🔌 Available Database Adapters:")
    (if (empty? active-adapters)
      (println "  ⚠️  No active adapters configured")
      (doseq [adapter active-adapters]
        (println "  ✅" adapter))))

  (println "===========================================\n"))
