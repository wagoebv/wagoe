(ns wagoe.platform.shell.adapters.database.h2.connection
  "H2 connection management utilities."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            [next.jdbc :as jdbc]))

(defn- log-debug [msg]
  (let [logger (log-impl/get-logger log/*logger-factory* *ns*)]
    (when (log-impl/enabled? logger :debug)
      (log/log* logger :debug nil msg))))

(defn- log-warn [msg data]
  (let [logger (log-impl/get-logger log/*logger-factory* *ns*)]
    (when (log-impl/enabled? logger :warn)
      (log/log* logger :warn nil (print-str msg data)))))

;; =============================================================================
;; Configuration Constants
;; =============================================================================

(def ^:private h2-connection-options
  "H2 connection options for PostgreSQL compatibility and consistency."
  "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")

;; =============================================================================
;; Connection Validation
;; =============================================================================

(defn- validate-datasource
  "Validate datasource parameter.

   Args:
     datasource: Datasource to validate

   Returns:
     datasource if valid

   Throws:
     IllegalArgumentException if invalid"
  [datasource]
  (when (nil? datasource)
    (throw (IllegalArgumentException. "Datasource cannot be nil")))
  datasource)

;; =============================================================================
;; Connection Initialization
;; =============================================================================

(defn initialize!
  "Initialize H2 connection with optimized settings.

   Args:
     datasource: H2 datasource
     db-config: Database configuration map

   Returns:
     nil - side effects only"
  [datasource _db-config]
  (validate-datasource datasource)
  (log-debug "Initializing H2 connection settings")
  (try
    ;; Set timezone to UTC for consistency
    (jdbc/execute! datasource ["SET TIME ZONE '+00:00'"])

    ;; Enable referential integrity (foreign keys)
    (jdbc/execute! datasource ["SET REFERENTIAL_INTEGRITY TRUE"])

    (log-debug "H2 connection initialized successfully")
    (catch Exception e
      ;; Log warning but don't fail - these are optional optimization settings
      ;; Connection will still work even if these settings fail
      (log-warn "Failed to initialize some H2 settings (connection will still work)"
                {:error (.getMessage e)
                 :error-type (type e)}))))

(defn build-jdbc-url
  "Build H2 JDBC URL from configuration.

   Args:
     db-config: Database configuration map with :database-path

   Returns:
     String - JDBC URL for H2"
  [db-config]
  (let [database-path (:database-path db-config)]
    (str "jdbc:h2:" database-path ";" h2-connection-options)))

(defn pool-defaults
  "Get H2-optimized connection pool defaults.

   Returns:
     Map - HikariCP pool configuration optimized for embedded usage"
  []
  {:minimum-idle          1
   :maximum-pool-size     10
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})

;; =============================================================================
;; H2-Specific URL Builders
;; =============================================================================

(defn in-memory-url
  "Create H2 in-memory database URL.

   Args:
     db-name: Database name (optional, defaults to 'testdb')

   Returns:
     JDBC URL for H2 in-memory database

   Example:
     (in-memory-url) ;; => \"jdbc:h2:mem:testdb;...\"
     (in-memory-url \"mytest\") ;; => \"jdbc:h2:mem:mytest;...\""
  ([]
   (in-memory-url "testdb"))
  ([db-name]
   (str "jdbc:h2:mem:" db-name ";" h2-connection-options)))

(defn file-url
  "Create H2 file-based database URL.

   Args:
     file-path: Path to database file (without .mv.db extension)

   Returns:
     JDBC URL for H2 file database

   Example:
     (file-url \"./data/app\") ;; => \"jdbc:h2:./data/app;...\""
  [file-path]
  (str "jdbc:h2:" file-path ";" h2-connection-options))
