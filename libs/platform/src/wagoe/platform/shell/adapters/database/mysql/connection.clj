(ns wagoe.platform.shell.adapters.database.mysql.connection
  "MySQL connection management utilities."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
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
;; Connection Configuration
;; =============================================================================

(def ^:private default-sql-mode
  "Default SQL mode for MySQL connections."
  "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION")

;; =============================================================================
;; Connection Initialization
;; =============================================================================

(defn initialize!
  "Initialize MySQL connection with optimized settings.

   Args:
     datasource: MySQL datasource
     db-config: Database configuration map

   Returns:
     nil - side effects only"
  [datasource db-config]
  (log-debug "Initializing MySQL connection settings")
  (try
    ;; Set SQL mode for strict behavior (configurable, parameterized for safety)
    (let [sql-mode (get db-config :sql-mode default-sql-mode)]
      (when (seq sql-mode)
        (jdbc/execute! datasource ["SET SESSION sql_mode = ?" sql-mode])))

    ;; Set timezone to UTC for consistency
    (jdbc/execute! datasource ["SET SESSION time_zone = '+00:00'"])

    ;; Set character set to UTF8 for proper Unicode handling
    (jdbc/execute! datasource ["SET NAMES utf8mb4"])

    (log-debug "MySQL connection initialized successfully")
    (catch Exception e
      (log-warn "Failed to initialize some MySQL settings" {:error (.getMessage e)}))))

(defn build-jdbc-url
  "Build MySQL JDBC URL from configuration.

   Args:
     db-config: Database configuration map with :host, :port, :name

   Returns:
     String - JDBC URL with MySQL-specific parameters"
  [{:keys [host port name connection-params]}]
  (let [base-url (str "jdbc:mysql://" host ":" port "/" name)
        ;; Common MySQL connection parameters for consistency and security
        default-params {:serverTimezone "UTC"
                        :useSSL "true"
                        :requireSSL "false"
                        :verifyServerCertificate "false"
                        :useUnicode "true"
                        :characterEncoding "utf8"
                        :zeroDateTimeBehavior "convertToNull"}
        custom-params (or connection-params {})
        all-params (merge default-params custom-params)
        param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) all-params))]
    (str base-url "?" param-str)))

(defn pool-defaults
  "Get MySQL-optimized connection pool defaults.

   Returns:
     Map - HikariCP pool configuration"
  []
  {:minimum-idle 5
   :maximum-pool-size 15
   :connection-timeout-ms 30000
   :idle-timeout-ms 600000
   :max-lifetime-ms 1800000})

(defn create-database-url
  "Create MySQL JDBC URL from components.

   Args:
     host: Database hostname
     port: Database port
     database: Database name
     options: Optional map of connection parameters

   Returns:
     JDBC URL string"
  [host port database & [options]]
  (let [base-url (str "jdbc:mysql://" host ":" port "/" database)
        default-params {:serverTimezone "UTC"
                        :useSSL "true"
                        :requireSSL "false"}
        params (merge default-params options)
        param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))]
    (if (seq params)
      (str base-url "?" param-str)
      base-url)))
