(ns wagoe.platform.shell.adapters.database.postgresql.connection
  "PostgreSQL connection management utilities."
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
;; Connection Configuration
;; =============================================================================

(def ^:private default-application-name
  "Default application name for PostgreSQL connections."
  "wagoe-app")

(def ^:private default-statement-timeout-ms
  "Default statement timeout in milliseconds (30 seconds)."
  30000)

;; =============================================================================
;; Connection Initialization
;; =============================================================================

(defn initialize!
  "Verify PostgreSQL connectivity after pool creation.

   Session settings (application_name, timezone, statement_timeout) are applied
   per-connection via JDBC URL properties in build-jdbc-url — a SET here would
   only reach the single pooled connection that happens to execute it.

   Args:
     datasource: PostgreSQL datasource
     db-config: Database configuration map

   Returns:
     nil - side effects only"
  [datasource _db-config]
  (try
    (jdbc/execute! datasource ["SELECT 1"])
    (log-debug "PostgreSQL connection verified")
    (catch Exception e
      (log-warn "PostgreSQL connectivity check failed"
                {:error (.getMessage e)
                 :error-type (type e)}))))

(defn build-jdbc-url
  "Build PostgreSQL JDBC URL from configuration.

   Session settings ride on the URL so every physical connection in the pool
   gets them (unlike SET statements, which only affect one connection):
   - ApplicationName: connection identification in pg_stat_activity
   - options: statement_timeout guard + UTC timezone
   - reWriteBatchedInserts: batches multi-row INSERTs into single statements

   Args:
     db-config: Database configuration map with :host, :port, :name and
                optional :application-name, :statement-timeout-ms

   Returns:
     String - JDBC URL with PostgreSQL-specific parameters"
  [{:keys [host port name application-name statement-timeout-ms]}]
  (let [timeout (or statement-timeout-ms default-statement-timeout-ms)
        options (cond-> "-c%20TimeZone=UTC"
                  (pos? timeout) (str "%20-c%20statement_timeout=" timeout))]
    (str "jdbc:postgresql://" host ":" port "/" name
         "?stringtype=unspecified"
         "&ApplicationName=" (or application-name default-application-name)
         "&reWriteBatchedInserts=true"
         "&options=" options)))

(defn pool-defaults
  "Get PostgreSQL-optimized connection pool defaults.
   
   Returns:
     Map - HikariCP pool configuration"
  []
  {:minimum-idle 5
   :maximum-pool-size 20
   :connection-timeout-ms 30000
   :idle-timeout-ms 600000
   :max-lifetime-ms 1800000})