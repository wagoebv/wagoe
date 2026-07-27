(ns wagoe.platform.shell.adapters.database.postgresql.utils
  "PostgreSQL utility functions and DDL helpers."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Validation Utilities
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
;; URL Building
;; =============================================================================

(defn create-database-url
  "Create PostgreSQL JDBC URL from components.

   Args:
     host: Database hostname
     port: Database port
     database: Database name
     options: Optional map of connection parameters

   Returns:
     JDBC URL string"
  [host port database & [options]]
  (let [base-url (str "jdbc:postgresql://" host ":" port "/" database)
        params (merge {:stringtype "unspecified"} options)
        param-str (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))]
    (if (seq params)
      (str base-url "?" param-str)
      base-url)))

;; =============================================================================
;; DDL Type Helpers
;; =============================================================================

(defn boolean-column-type
  "Get PostgreSQL boolean column type definition."
  []
  "BOOLEAN")

(defn uuid-column-type
  "Get PostgreSQL UUID column type definition."
  []
  "UUID")

(defn varchar-uuid-column-type
  "Get PostgreSQL varchar UUID column type definition for compatibility."
  []
  "VARCHAR(36)")

(defn timestamp-column-type
  "Get PostgreSQL timestamp column type definition."
  []
  "TIMESTAMP WITH TIME ZONE")

(defn serial-primary-key
  "Get PostgreSQL serial primary key definition."
  []
  "SERIAL PRIMARY KEY")

(defn bigserial-primary-key
  "Get PostgreSQL bigserial primary key definition."
  []
  "BIGSERIAL PRIMARY KEY")

;; =============================================================================
;; Performance and Maintenance
;; =============================================================================

(defn explain-query
  "Get PostgreSQL query execution plan."
  ([datasource query-map]
   (explain-query datasource query-map {}))
  ([datasource query-map options]
   (validate-datasource datasource)
   (when (or (nil? query-map) (not (map? query-map)))
     (throw (IllegalArgumentException. "query-map must be a non-nil map")))
   (let [[sql & params] (sql/format query-map)
         explain-opts (str/join ", "
                                (for [[k v] options :when v]
                                  (str/upper-case (name k))))
         explain-sql (str "EXPLAIN "
                          (when (seq explain-opts) (str "(" explain-opts ") "))
                          sql)]
     (jdbc/execute! datasource (cons explain-sql params)
                    {:builder-fn rs/as-unqualified-lower-maps}))))

(defn analyze-table
  "Update PostgreSQL table statistics for better query planning."
  ([datasource]
   (validate-datasource datasource)
   (jdbc/execute! datasource ["ANALYZE"])
   (log/debug "Analyzed all PostgreSQL tables"))
  ([datasource table-name]
   (validate-datasource datasource)
   (when (nil? table-name)
     (throw (IllegalArgumentException. "table-name cannot be nil")))
   (let [table-str (name table-name)]
     (jdbc/execute! datasource [(str "ANALYZE " table-str)])
     (log/debug "Analyzed PostgreSQL table" {:table table-str}))))

(defn vacuum-table
  "Vacuum PostgreSQL table to reclaim space and update statistics."
  ([datasource table-name]
   (vacuum-table datasource table-name {}))
  ([datasource table-name options]
   (validate-datasource datasource)
   (when (nil? table-name)
     (throw (IllegalArgumentException. "table-name cannot be nil")))
   (let [table-str (name table-name)
         vacuum-opts (str/join ", "
                               (for [[k v] options :when v]
                                 (str/upper-case (name k))))
         vacuum-sql (str "VACUUM "
                         (when (seq vacuum-opts) (str "(" vacuum-opts ") "))
                         table-str)]
     (jdbc/execute! datasource [vacuum-sql])
     (log/debug "Vacuumed PostgreSQL table" {:table table-str :options options}))))

;; =============================================================================
;; Extensions and Features
;; =============================================================================

(defn enable-extension
  "Enable PostgreSQL extension."
  [datasource extension-name]
  (validate-datasource datasource)
  (when (or (nil? extension-name) (str/blank? extension-name))
    (throw (IllegalArgumentException. "extension-name cannot be nil or blank")))
  (let [sql (str "CREATE EXTENSION IF NOT EXISTS \"" extension-name "\"")]
    (jdbc/execute! datasource [sql])
    (log/info "Enabled PostgreSQL extension" {:extension extension-name})))

(defn list-extensions
  "List installed PostgreSQL extensions."
  [datasource]
  (validate-datasource datasource)
  (let [results (jdbc/execute! datasource
                               ["SELECT extname, extversion FROM pg_extension"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:name (:extname row)
             :version (:extversion row)})
          results)))

;; =============================================================================
;; Connection Information
;; =============================================================================

(defn connection-info
  "Get PostgreSQL connection and server information."
  [datasource]
  (validate-datasource datasource)
  (let [version-result (jdbc/execute-one! datasource ["SELECT version()"]
                                          {:builder-fn rs/as-unqualified-lower-maps})
        stats-result (jdbc/execute-one! datasource
                                        ["SELECT current_database(), current_user, inet_server_addr(), inet_server_port()"]
                                        {:builder-fn rs/as-unqualified-lower-maps})]
    {:version (:version version-result)
     :database (:current_database stats-result)
     :user (:current_user stats-result)
     :server-addr (:inet_server_addr stats-result)
     :server-port (:inet_server_port stats-result)}))

(defn active-connections
  "Get information about active PostgreSQL connections."
  [datasource]
  (validate-datasource datasource)
  (let [results (jdbc/execute! datasource
                               ["SELECT pid, usename, application_name, client_addr, state, query_start FROM pg_stat_activity WHERE state = 'active'"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:pid (:pid row)
             :username (:usename row)
             :application-name (:application_name row)
             :client-addr (:client_addr row)
             :state (:state row)
             :query-start (:query_start row)})
          results)))
