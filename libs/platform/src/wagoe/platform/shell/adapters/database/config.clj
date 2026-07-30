(ns wagoe.platform.shell.adapters.database.config
  "Configuration-driven database adapter management.
   
   This namespace provides functionality to read database configuration from
   config.edn and only load adapters that are marked as :active. This allows
   for optimized deployments where only required database drivers are loaded.
   
   Key Features:
   - Configuration-driven adapter loading
   - Environment-specific database configs
   - Graceful handling of inactive adapters
   - JDBC driver optimization (only load needed drivers)
   
   Usage:
     (require '[wagoe.platform.shell.adapters.database.config :as db-config])
     
     (db-config/get-active-adapters \"dev\")     ; Get active adapters for dev env
     (db-config/adapter-active? :postgresql)    ; Check if adapter is active"
  (:require [clojure.tools.logging :as log]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(def ^:dynamic *config-cache*
  "Cache for loaded configurations to avoid repeated file reads"
  (atom {}))

(defn- config-file-path
  "Generate path to configuration file for environment"
  [env]
  (str "conf/" env "/config.edn"))

(defn- load-config-file
  "Load configuration from file with error handling"
  [env]
  (let [config-path (config-file-path env)
        config-resource (io/resource config-path)]
    (if config-resource
      (do
        (log/info "Loading database configuration" {:env env :path config-path})
        (aero/read-config config-resource))
      (throw (ex-info (str "Configuration file not found: " config-path)
                      {:env env :path config-path})))))

(defn load-config
  "Load configuration for environment with caching"
  [env]
  (if-let [cached-config (get @*config-cache* env)]
    (do
      (log/debug "Using cached configuration" {:env env})
      cached-config)
    (let [config (load-config-file env)]
      (swap! *config-cache* assoc env config)
      config)))

(defn clear-config-cache!
  "Clear configuration cache (useful for testing)"
  []
  (log/debug "Clearing configuration cache")
  (reset! *config-cache* {}))

;; =============================================================================
;; Environment Detection
;; =============================================================================

(def ^:dynamic *default-environment* "dev")

(defn getenv
  "Environment variable lookup. Exists as a var (rather than inline interop)
   so tests can redef it — env vars cannot be modified inside a JVM."
  [k]
  (System/getenv k))

(defn detect-environment
  "Detect current environment from various sources"
  []
  (or (System/getProperty "env")
      (getenv "WAG_ENV")
      (getenv "ENV")
      (getenv "ENVIRONMENT")
      *default-environment*))

(defn with-environment
  "Execute function with specific environment context"
  [env f]
  (binding [*default-environment* env]
    (f)))

;; =============================================================================
;; Database Adapter Configuration Analysis
;; =============================================================================

(defn- extract-database-configs
  "Extract database adapter configurations from config map"
  [config]
  (let [active-configs (:active config {})
        inactive-configs (:inactive config {})

        ;; Find all keys that look like database configurations
        db-key? (fn [k] (and (keyword? k)
                             (some? (re-matches #"(sqlite|postgresql|mysql|h2)" (name k)))))

        active-dbs (into {} (filter #(db-key? (first %)) active-configs))
        inactive-dbs (into {} (filter #(db-key? (first %)) inactive-configs))]

    {:active active-dbs
     :inactive inactive-dbs
     :all (merge inactive-dbs active-dbs)}))

(defn get-database-configs
  "Get database configurations for environment"
  [env]
  (let [config (load-config env)]
    (extract-database-configs config)))

(defn- config-key->adapter-type
  "Convert configuration key to adapter type keyword"
  [config-key]
  (let [key-name (name config-key)]
    (cond
      (re-find #"sqlite" key-name) :sqlite
      (re-find #"postgresql" key-name) :postgresql
      (re-find #"mysql" key-name) :mysql
      (re-find #"h2" key-name) :h2
      :else nil)))

(defn get-active-adapters
  "Get list of active database adapter types for environment"
  [env]
  (let [db-configs (get-database-configs env)
        active-configs (:active db-configs)]
    (into #{}
          (comp (map first)
                (map config-key->adapter-type)
                (filter some?))
          active-configs)))

(defn adapter-active?
  "Check if a specific adapter type is active in environment"
  [adapter-type env]
  (contains? (get-active-adapters env) adapter-type))

;; =============================================================================
;; Configuration Validation
;; =============================================================================

(defn validate-adapter-config
  "Validate that an adapter configuration has required keys"
  [adapter-type config]
  (case adapter-type
    :sqlite (when-not (:db config)
              "SQLite configuration missing :db key")

    :postgresql (let [required-keys [:host :port :dbname :user :password]]
                  (when-let [missing (seq (remove config required-keys))]
                    (str "PostgreSQL configuration missing keys: " missing)))

    :mysql (let [required-keys [:host :port :dbname :user :password]]
             (when-let [missing (seq (remove config required-keys))]
               (str "MySQL configuration missing keys: " missing)))

    :h2 (when-not (or (:db config) (:memory config))
          "H2 configuration missing :db or :memory key")

    nil)) ; Unknown adapter type, skip validation

(defn validate-database-configs
  "Validate all database configurations for environment"
  [env]
  (let [db-configs (get-database-configs env)
        active-configs (:active db-configs)
        errors (atom [])]

    (doseq [[config-key config] active-configs]
      (let [adapter-type (config-key->adapter-type config-key)]
        (when-let [error (validate-adapter-config adapter-type config)]
          (swap! errors conj {:adapter adapter-type
                              :config-key config-key
                              :error error}))))

    (if (empty? @errors)
      {:valid? true
       :message "All database configurations are valid"}
      {:valid? false
       :errors @errors
       :message (str "Configuration validation failed: " (count @errors) " errors found")})))

;; =============================================================================
;; Configuration-to-Database-Config Conversion
;; =============================================================================

(defn- sqlite-config->db-config
  "Convert SQLite configuration to database adapter config"
  [config]
  {:adapter :sqlite
   :database-path (:db config)
   :pool (:pool config {})})

(defn- postgresql-config->db-config
  "Convert PostgreSQL configuration to database adapter config"
  [config]
  {:adapter :postgresql
   :host (:host config)
   :port (:port config)
   :name (:dbname config)
   :username (:user config)
   :password (:password config)
   :pool (:pool config {})})

(defn- mysql-config->db-config
  "Convert MySQL configuration to database adapter config"
  [config]
  {:adapter :mysql
   :host (:host config)
   :port (:port config)
   :name (:dbname config)
   :username (:user config)
   :password (:password config)
   :pool (:pool config {})})

(defn- h2-config->db-config
  "Convert H2 configuration to database adapter config"
  [config]
  {:adapter :h2
   :database-path (or (:db config) "mem:testdb")
   :pool (:pool config {})})

(defn config->db-config
  "Convert configuration entry to database adapter config"
  [config-key config]
  (let [adapter-type (config-key->adapter-type config-key)]
    (case adapter-type
      :sqlite (sqlite-config->db-config config)
      :postgresql (postgresql-config->db-config config)
      :mysql (mysql-config->db-config config)
      :h2 (h2-config->db-config config)
      (throw (IllegalArgumentException.
              (str "Unknown adapter type for config key: " config-key))))))

(defn get-active-db-configs
  "Get database adapter configurations for all active databases in environment"
  [env]
  (let [db-configs (get-database-configs env)
        active-configs (:active db-configs)]
    (into {}
          (map (fn [[config-key config]]
                 [config-key (config->db-config config-key config)]))
          active-configs)))

(defn get-active-db-config
  "Get the first active database configuration for environment.

   This is used by the migration system which operates on a single database.
   If multiple databases are configured, returns the first one.

   Args:
     None (uses detected environment)

   Returns:
     Database adapter configuration map with :datasource

   Throws:
     Exception if no active database is configured"
  []
  (let [env (detect-environment)
        db-configs (get-active-db-configs env)]
    (when (empty? db-configs)
      (throw (ex-info "No active database configured"
                      {:environment env})))
    (let [[config-key config] (first db-configs)
          ;; Create a datasource for the database configuration
          datasource (factory/create-datasource config)]
      (assoc config
             :datasource datasource
             :database-type (name (:adapter config))
             :config-key config-key))))

;; =============================================================================
;; Test Helper Functions (for compatibility with test suite)
;; =============================================================================

(defn get-active-adapters-from-config
  "Get active adapter configurations from config map"
  [config-map]
  (:active config-map {}))

(defn get-inactive-adapters
  "Get inactive adapter configurations from config map"
  [config-map]
  (:inactive config-map {}))

(defn get-all-database-configs
  "Get all database configurations (active + inactive) from config map"
  [config-map]
  (merge (:inactive config-map {}) (:active config-map {})))

(defn valid-config-structure?
  "Validate that config has required structure"
  [config]
  (and (map? config)
       (contains? config :active)
       (contains? config :inactive)))

(defn valid-adapter-config?
  "Validate adapter configuration (wrapper for validate-adapter-config)"
  [adapter-key config]
  (let [adapter-type (config-key->adapter-type adapter-key)]
    (nil? (validate-adapter-config adapter-type config))))

(defn merge-configs
  "Merge two configuration maps"
  [base-config override-config]
  (merge-with merge base-config override-config))

;; =============================================================================
;; Configuration Summary and Debugging
;; =============================================================================

(defn config-summary
  "Get summary of database configuration for environment"
  [env]
  (let [db-configs (get-database-configs env)
        active-adapters (get-active-adapters env)
        validation (validate-database-configs env)]
    {:environment env
     :active-adapters (vec (sort active-adapters))
     :active-count (count (:active db-configs))
     :inactive-count (count (:inactive db-configs))
     :validation validation
     :configs {:active (keys (:active db-configs))
               :inactive (keys (:inactive db-configs))}}))

(defn print-config-summary
  "Print formatted configuration summary for debugging"
  [env]
  (let [summary (config-summary env)]
    (println "\n=== Database Configuration Summary ===")
    (println "Environment:" (:environment summary))
    (println "Active Adapters:" (:active-adapters summary))
    (println "Active Configs:" (:active-count summary))
    (println "Inactive Configs:" (:inactive-count summary))
    (println "Validation:" (if (get-in summary [:validation :valid?]) "✅ VALID" "❌ INVALID"))

    (when-not (get-in summary [:validation :valid?])
      (println "Validation Errors:")
      (doseq [error (get-in summary [:validation :errors])]
        (println " -" (:adapter error) ":" (:error error))))

    (println "\nActive Database Configs:")
    (doseq [config-key (get-in summary [:configs :active])]
      (println " -" config-key))

    (println "\nInactive Database Configs:")
    (doseq [config-key (get-in summary [:configs :inactive])]
      (println " -" config-key))
    (println "=======================================\n")))