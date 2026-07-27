(ns wagoe.platform.shell.adapters.database.sqlite.utils
  "SQLite utility functions and DDL helpers."
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
  "Create SQLite JDBC URL from file path.

   Args:
     database-path: Path to SQLite database file
     options: Optional map of connection parameters

   Returns:
     JDBC URL string"
  [database-path & [options]]
  (let [base-url (str "jdbc:sqlite:" database-path)
        param-str (when (seq options)
                    (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) options)))]
    (if (seq options)
      (str base-url "?" param-str)
      base-url)))

;; =============================================================================
;; DDL Type Helpers
;; =============================================================================

(defn boolean-column-type
  "Get SQLite boolean column type definition.
   
   SQLite doesn't have native boolean type, uses INTEGER."
  []
  "INTEGER")

(defn uuid-column-type
  "Get SQLite UUID column type definition.
   
   SQLite stores UUIDs as TEXT."
  []
  "TEXT")

(defn varchar-uuid-column-type
  "Get SQLite varchar UUID column type definition."
  []
  "TEXT")

(defn timestamp-column-type
  "Get SQLite timestamp column type definition.
   
   SQLite stores timestamps as TEXT in ISO8601 format."
  []
  "TEXT")

(defn integer-primary-key
  "Get SQLite integer primary key definition.
   
   INTEGER PRIMARY KEY in SQLite is an alias for ROWID."
  []
  "INTEGER PRIMARY KEY")

(defn autoincrement-primary-key
  "Get SQLite autoincrement primary key definition.
   
   Use with caution - AUTOINCREMENT has performance implications."
  []
  "INTEGER PRIMARY KEY AUTOINCREMENT")

;; =============================================================================
;; Performance and Maintenance
;; =============================================================================

(defn explain-query
  "Get SQLite query execution plan.
   
   Args:
     datasource: SQLite datasource
     query-map: HoneySQL query map
     query-plan: Optional - use 'query-plan' for detailed analysis
     
   Returns:
     Vector of execution plan steps"
  ([datasource query-map]
   (explain-query datasource query-map false))
  ([datasource query-map query-plan?]
   (validate-datasource datasource)
   (when (or (nil? query-map) (not (map? query-map)))
     (throw (IllegalArgumentException. "query-map must be a non-nil map")))
   (let [[sql & params] (sql/format query-map)
         explain-type (if query-plan? "EXPLAIN QUERY PLAN" "EXPLAIN")
         explain-sql (str explain-type " " sql)]
     (jdbc/execute! datasource (cons explain-sql params)
                    {:builder-fn rs/as-unqualified-lower-maps}))))

(defn analyze-database
  "Update SQLite database statistics for better query planning.
   
   Args:
     datasource: SQLite datasource
     table-name: Optional specific table name
     
   Returns:
     nil - side effects only"
  ([datasource]
   (validate-datasource datasource)
   (jdbc/execute! datasource ["ANALYZE"])
   (log/debug "Analyzed SQLite database"))
  ([datasource table-name]
   (validate-datasource datasource)
   (when (nil? table-name)
     (throw (IllegalArgumentException. "table-name cannot be nil")))
   (let [table-str (name table-name)]
     (jdbc/execute! datasource [(str "ANALYZE " table-str)])
     (log/debug "Analyzed SQLite table" {:table table-str}))))

(defn vacuum-database
  "Vacuum SQLite database to reclaim space and defragment.
   
   Args:
     datasource: SQLite datasource
     into-file: Optional file path for VACUUM INTO operation
     
   Returns:
     nil - side effects only"
  ([datasource]
   (validate-datasource datasource)
   (jdbc/execute! datasource ["VACUUM"])
   (log/debug "Vacuumed SQLite database"))
  ([datasource into-file]
   (validate-datasource datasource)
   (when (str/blank? into-file)
     (throw (IllegalArgumentException. "into-file cannot be blank")))
   (jdbc/execute! datasource [(str "VACUUM INTO '" into-file "'")])
   (log/debug "Vacuumed SQLite database into file" {:file into-file})))

(defn reindex-database
  "Rebuild SQLite indexes for better performance.
   
   Args:
     datasource: SQLite datasource
     index-name: Optional specific index name
     
   Returns:
     nil - side effects only"
  ([datasource]
   (validate-datasource datasource)
   (jdbc/execute! datasource ["REINDEX"])
   (log/debug "Reindexed SQLite database"))
  ([datasource index-name]
   (validate-datasource datasource)
   (when (nil? index-name)
     (throw (IllegalArgumentException. "index-name cannot be nil")))
   (let [index-str (name index-name)]
     (jdbc/execute! datasource [(str "REINDEX " index-str)])
     (log/debug "Reindexed SQLite index" {:index index-str}))))

;; =============================================================================
;; Database Information
;; =============================================================================

(defn database-info
  "Get SQLite database information and settings.
   
   Args:
     datasource: SQLite datasource
     
   Returns:
     Map with database information"
  [datasource]
  (validate-datasource datasource)
  (let [version-result (jdbc/execute-one! datasource ["SELECT sqlite_version()"]
                                          {:builder-fn rs/as-unqualified-lower-maps})
        page-count-result (jdbc/execute-one! datasource ["PRAGMA page_count"]
                                             {:builder-fn rs/as-unqualified-lower-maps})
        page-size-result (jdbc/execute-one! datasource ["PRAGMA page_size"]
                                            {:builder-fn rs/as-unqualified-lower-maps})
        journal-mode-result (jdbc/execute-one! datasource ["PRAGMA journal_mode"]
                                               {:builder-fn rs/as-unqualified-lower-maps})]
    {:version (get version-result :sqlite_version)
     :page-count (get page-count-result :page_count)
     :page-size (get page-size-result :page_size)
     :journal-mode (get journal-mode-result :journal_mode)
     :database-size-bytes (* (get page-count-result :page_count 0)
                             (get page-size-result :page_size 0))}))

(defn list-tables
  "List all tables in SQLite database.
   
   Args:
     datasource: SQLite datasource
     
   Returns:
     Vector of table names"
  [datasource]
  (validate-datasource datasource)
  (let [results (jdbc/execute! datasource
                               ["SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv :name results)))

(defn list-indexes
  "List all indexes in SQLite database.
   
   Args:
     datasource: SQLite datasource
     table-name: Optional specific table name
     
   Returns:
     Vector of index information maps"
  ([datasource]
   (validate-datasource datasource)
   (let [results (jdbc/execute! datasource
                                ["SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name"]
                                {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:name row)
              :table (:tbl_name row)
              :sql (:sql row)})
           results)))
  ([datasource table-name]
   (validate-datasource datasource)
   (when (nil? table-name)
     (throw (IllegalArgumentException. "table-name cannot be nil")))
   (let [table-str (name table-name)
         results (jdbc/execute! datasource
                                ["SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND name NOT LIKE 'sqlite_%' ORDER BY name" table-str]
                                {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:name row)
              :table table-str
              :sql (:sql row)})
           results))))