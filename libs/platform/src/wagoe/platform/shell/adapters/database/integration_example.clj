(ns wagoe.platform.shell.adapters.database.integration-example
  "Example integration showing how to use the multi-database adapter system.
   
   This namespace demonstrates how to:
   - Load environment-specific configurations
   - Initialize database adapters based on active configurations
   - Use the initialized adapters for database operations
   - Handle multiple active databases simultaneously"
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.config-factory :as config-factory]
            [wagoe.platform.shell.adapters.database.common.core :as db-core]
            [wagoe.platform.shell.adapters.database.utils.driver-loader :as driver-loader]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Application State Management
;; =============================================================================

(def ^:private app-state
  "Global application state holding active database connections."
  (atom {:databases {}
         :config nil}))

;; =============================================================================
;; Initialization Functions
;; =============================================================================

(defn initialize-databases!
  "Initialize all active databases based on the current environment configuration.
   
   This function:
   1. Loads the environment-specific config.edn
   2. Identifies active database adapters
   3. Loads required JDBC drivers based on active databases
   4. Creates connection pools for each active adapter
   5. Stores the initialized adapters in application state
   
   Returns: map of {:adapter-key {:adapter adapter :pool pool}}"
  ([]
   (initialize-databases! (or (System/getProperty "env") "dev")))

  ([environment]
   (try
     (log/info "Initializing databases for environment:" environment)

     ;; Load configuration for the specified environment
     (let [config (db-config/load-config environment)]

       ;; Load required JDBC drivers based on active databases
       (log/info "Loading JDBC drivers for active databases in environment:" environment)
       (driver-loader/load-required-drivers! config)

       ;; Create database contexts for active databases
       (let [active-contexts (config-factory/create-active-contexts environment)]

         (log/info "Found" (count active-contexts) "active database contexts:"
                   (keys active-contexts))

         ;; Convert contexts to the format expected by app-state
         (let [initialized-dbs
               (into {}
                     (map (fn [[adapter-key ctx]]
                            (log/info "Registering context for adapter:" adapter-key)
                            [adapter-key {:adapter (:adapter ctx)
                                          :pool (:datasource ctx)}])
                          active-contexts))]

           ;; Update application state
           (swap! app-state assoc
                  :databases initialized-dbs
                  :config config)

           (log/info "Successfully initialized" (count initialized-dbs) "database connections")
           initialized-dbs)))

     (catch Exception e
       (log/error e "Failed to initialize databases")
       (throw (ex-info "Database initialization failed"
                       {:environment environment
                        :error (.getMessage e)}
                       e))))))

(defn shutdown-databases!
  "Safely shutdown all active database connections."
  []
  (let [{:keys [databases]} @app-state]
    (log/info "Shutting down" (count databases) "database connections")

    (doseq [[adapter-key {:keys [pool]}] databases]
      (try
        (log/debug "Closing connection pool for:" adapter-key)
        (.close pool)
        (log/debug "Successfully closed:" adapter-key)
        (catch Exception e
          (log/warn e "Error closing connection pool for:" adapter-key))))

    (swap! app-state assoc :databases {})
    (log/info "All database connections shut down")))

;; =============================================================================
;; Database Access Functions
;; =============================================================================

(defn get-database
  "Get a specific database adapter by key.
   
   Args:
     adapter-key - Keyword identifying the adapter (e.g., :wagoe/sqlite)
   
   Returns: {:adapter adapter-config :pool connection-pool} or nil"
  [adapter-key]
  (get-in @app-state [:databases adapter-key]))

(defn get-primary-database
  "Get the primary database (first active adapter).
   
   Useful when you have a single active database or want a default."
  []
  (let [databases (:databases @app-state)]
    (when (seq databases)
      (second (first databases)))))

(defn list-active-databases
  "List all currently active database adapters.
   
   Returns: sequence of [adapter-key {:adapter ... :pool ...}]"
  []
  (seq (:databases @app-state)))

(defn execute-query
  "Execute a query on a specific database adapter.
   
   Args:
     adapter-key - Keyword identifying the adapter
     query-map - HoneySQL query map
   
   Returns: Query results or throws exception"
  [adapter-key query-map]
  (if-let [db (get-database adapter-key)]
    ;; db is already a proper context map with :adapter and :pool (which is the datasource)
    ;; Just rename :pool to :datasource for the db-core API
    (db-core/execute-query! {:adapter (:adapter db) :datasource (:pool db)} query-map)
    (throw (ex-info "Database adapter not found or not initialized"
                    {:adapter-key adapter-key
                     :available-adapters (keys (:databases @app-state))}))))

;; =============================================================================
;; Example Usage Functions
;; =============================================================================

(defn example-basic-usage
  "Demonstrate basic usage of the multi-database system."
  []
  (log/info "=== Basic Usage Example ===")

  ;; Initialize databases for current environment
  (initialize-databases!)

  ;; List what's available
  (let [active-dbs (list-active-databases)]
    (log/info "Active databases:" (map first active-dbs))

    ;; Try to execute a simple query on the first available database
    (when-let [[adapter-key _] (first active-dbs)]
      (try
        (let [result (execute-query adapter-key {:select [:1 :as :test]})]
          (log/info "Test query result on" adapter-key ":" result))
        (catch Exception e
          (log/warn "Test query failed on" adapter-key ":" (.getMessage e))))))

  ;; Clean shutdown
  (shutdown-databases!)
  (log/info "=== Example Complete ==="))

(defn example-environment-switching
  "Demonstrate switching between different environment configurations."
  []
  (log/info "=== Environment Switching Example ===")

  ;; Test different environments
  (doseq [env ["dev" "test" "prod"]]
    (log/info "--- Testing environment:" env "---")
    (try
      (let [_config (db-config/load-config env)
            active-db-configs (db-config/get-active-db-configs env)]
        (log/info "Environment" env "has" (count active-db-configs) "active database configs:"
                  (keys active-db-configs)))
      (catch Exception e
        (log/warn "Failed to load environment" env ":" (.getMessage e)))))

  (log/info "=== Environment Switching Example Complete ==="))

(defn reset-application-state!
  "Reset application state - useful for development and testing."
  []
  (shutdown-databases!)
  (reset! app-state {:databases {} :config nil})
  (log/info "Application state reset"))

(defn current-state
  "Get current application state - useful for debugging."
  []
  (let [state @app-state]
    {:active-databases (keys (:databases state))
     :config-loaded? (some? (:config state))}))