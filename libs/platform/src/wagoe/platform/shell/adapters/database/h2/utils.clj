(ns wagoe.platform.shell.adapters.database.h2.utils
  "H2 utility functions and DDL helpers."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.util UUID)))

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
;; Constants
;; =============================================================================

(def ^:private default-schema
  "Default schema name for H2 database queries."
  "public")

;; =============================================================================
;; DDL Type Helpers
;; =============================================================================

(defn boolean-column-type
  "Get H2 boolean column type definition."
  []
  "BOOLEAN")

(defn uuid-column-type
  "Get H2 UUID column type definition.

   H2 has native UUID support, but we use VARCHAR for compatibility."
  []
  "VARCHAR(36)")

(defn timestamp-column-type
  "Get H2 timestamp column type definition."
  []
  "TIMESTAMP")

(defn auto-increment-primary-key
  "Get H2 auto-increment primary key definition."
  []
  "BIGINT AUTO_INCREMENT PRIMARY KEY")

;; =============================================================================
;; Performance and Maintenance
;; =============================================================================

(defn explain-query
  "Get H2 query execution plan.

   Args:
     datasource: H2 datasource
     query-map: HoneySQL query map

   Returns:
     Vector of execution plan rows"
  [datasource query-map]
  (validate-datasource datasource)
  (when (or (nil? query-map) (not (map? query-map)))
    (throw (IllegalArgumentException. "query-map must be a non-nil map")))
  (let [[sql & params] (sql/format query-map {:dialect :h2})
        ;; H2 EXPLAIN requires the SQL to be part of the statement, not a parameter
        explain-sql (str "EXPLAIN " sql)]
    (jdbc/execute! datasource (cons explain-sql params)
                   {:builder-fn rs/as-unqualified-lower-maps})))

(defn analyze-table
  "Update H2 table statistics for better query planning.

   Args:
     datasource: H2 datasource
     table-name: Table name to analyze"
  [datasource table-name]
  (validate-datasource datasource)
  (when (nil? table-name)
    (throw (IllegalArgumentException. "table-name cannot be nil")))
  (let [table-str (name table-name)]
    ;; H2 ANALYZE TABLE requires table name in the SQL, cannot be parameterized
    (jdbc/execute! datasource [(str "ANALYZE TABLE " table-str)])
    (log/debug "Analyzed H2 table" {:table table-str})))

;; =============================================================================
;; Database Information
;; =============================================================================

(defn show-tables
  "List all tables in H2 database.

   Args:
     datasource: H2 datasource

   Returns:
     Vector of table names"
  [datasource]
  (validate-datasource datasource)
  (let [query {:select [:table_name]
               :from [:information_schema.tables]
               :where [:= :table_schema (str/upper-case default-schema)]}
        results (jdbc/execute! datasource
                               (sql/format query {:dialect :ansi})
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv #(str/lower-case (:table_name %)) results)))

(defn show-indexes
  "List all indexes in H2 database.

   Args:
     datasource: H2 datasource
     table-name: Optional table name to filter indexes

   Returns:
     Vector of index information"
  ([datasource]
   (show-indexes datasource nil))
  ([datasource table-name]
   (validate-datasource datasource)
   (let [base-condition [:= :table_schema (str/upper-case default-schema)]
         where-clause (if table-name
                        [:and base-condition [:= :table_name (str/upper-case (name table-name))]]
                        base-condition)
         query {:select [:index_name :table_name :non_unique :column_name]
                :from [:information_schema.indexes]
                :where where-clause}
         results (jdbc/execute! datasource
                                (sql/format query {:dialect :ansi})
                                {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:index-name  (str/lower-case (:index_name row))
              :table-name  (str/lower-case (:table_name row))
              :unique      (not (:non_unique row))
              :column-name (str/lower-case (:column_name row))})
           results))))

;; =============================================================================
;; Development and Testing Utilities
;; =============================================================================

(defn create-test-context
  "Create H2 in-memory database context for testing.

   Args:
     db-name: Optional database name (defaults to random UUID)

   Returns:
     Database context ready for testing"
  ([]
   (create-test-context (str "test_" (UUID/randomUUID))))
  ([db-name]
   (let [adapter   ((requiring-resolve 'wagoe.platform.shell.adapters.database.h2.core/new-adapter))
         db-config {:adapter       :h2
                    :database-path (str "mem:" db-name)}]
     {:adapter    adapter
      :datasource ((requiring-resolve 'wagoe.platform.shell.adapters.database.common.core/create-connection-pool)
                   adapter db-config)})))
