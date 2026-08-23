(ns wagoe.platform.shell.adapters.database.sqlite.core
  "SQLite database adapter - main entry point implementing the DBAdapter protocol.

   This namespace serves as the main entry point for SQLite database
   functionality, implementing the DBAdapter protocol and coordinating
   specialized modules for different aspects of SQLite operations.

   The adapter has been refactored into 5 specialized namespaces:
   - sqlite.connection: Connection management and PRAGMA settings
   - sqlite.query: SQLite-specific query building and boolean conversion
   - sqlite.metadata: Table introspection and schema information
   - sqlite.utils: Utility functions and DDL helpers
   - sqlite.core: Main adapter implementation and coordination (this namespace)

   Key Features:
   - SQLite-optimized connection management with PRAGMAs
   - Database-specific query building (LIKE for strings, boolean->int)
   - Schema introspection via sqlite_master and PRAGMA table_info
   - Integration with shared type conversion utilities

   SQLite-Specific Optimizations:
   - Text-based UUID and timestamp storage
   - Boolean as integer (0/1) representation
   - WAL mode, synchronous settings, and other PRAGMAs
   - Connection pool tuning for embedded usage

   This namespace maintains backward compatibility by serving as the main
   entry point that other code can require directly."
  (:require [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.sqlite.connection :as connection]
            [wagoe.platform.shell.adapters.database.sqlite.metadata :as metadata]
            [wagoe.platform.shell.adapters.database.sqlite.query :as query]
            [wagoe.platform.shell.adapters.database.sqlite.utils :as utils]
            [wagoe.platform.shell.adapters.database.common.core :refer [with-transaction*]]))

;; =============================================================================
;; SQLite Adapter Implementation
;; =============================================================================

(defrecord SQLiteAdapter []
  protocols/DBAdapter

  (dialect [_]
    nil)  ; SQLite uses HoneySQL's default dialect

  (jdbc-driver [_]
    "org.sqlite.JDBC")

  (jdbc-url [_ db-config]
    (connection/build-jdbc-url db-config))

  (pool-defaults [_]
    (connection/pool-defaults))

  (init-connection! [_ datasource db-config]
    (connection/initialize! datasource db-config))

  (build-where [_ filters]
    (query/build-where-clause filters))

  (boolean->db [_ boolean-value]
    (query/boolean->db boolean-value))

  (db->boolean [_ db-value]
    (query/db->boolean db-value))

  (table-exists? [_ datasource table-name]
    (metadata/table-exists? datasource table-name))

  (get-table-info [_ datasource table-name]
    (metadata/get-table-info datasource table-name)))

;; =============================================================================
;; Constructor Function
;; =============================================================================

(defn new-adapter
  "Create new SQLite adapter instance.

   Returns:
     SQLite adapter implementing DBAdapter protocol"
  []
  (->SQLiteAdapter))

;; =============================================================================
;; SQLite-Specific Utilities (Re-exported)
;; =============================================================================

;; URL building
(def create-database-url utils/create-database-url)

;; DDL helpers
(def boolean-column-type utils/boolean-column-type)
(def uuid-column-type utils/uuid-column-type)
(def varchar-uuid-column-type utils/varchar-uuid-column-type)
(def timestamp-column-type utils/timestamp-column-type)
(def integer-primary-key utils/integer-primary-key)
(def autoincrement-primary-key utils/autoincrement-primary-key)

;; Performance and maintenance
(def explain-query utils/explain-query)
(def analyze-database utils/analyze-database)
(def vacuum-database utils/vacuum-database)
(def reindex-database utils/reindex-database)

;; Database information
(def database-info utils/database-info)
(def list-tables utils/list-tables)
(def list-indexes utils/list-indexes)

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defmacro with-transaction
  "Macro for SQLite transaction management with consistent error handling.

   Args:
     binding: [tx-var datasource]
     body: Expressions to execute within transaction

   Example:
     (with-transaction [tx datasource]
       (execute-update! tx query1)
       (execute-update! tx query2))"
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))

;; =============================================================================
;; Backward-Compatible Legacy Functions
;; =============================================================================

(defn initialize-sqlite-pragmas!
  "Legacy function for initializing SQLite PRAGMA settings.

   Deprecated: Use the new modular adapter instead.

   Args:
     datasource: Database connection or connection pool
     custom-pragmas: Optional vector of additional PRAGMA statements"
  ([datasource]
   (initialize-sqlite-pragmas! datasource []))
  ([datasource custom-pragmas]
   ;; Delegate to the connection module
   (connection/initialize! datasource {} custom-pragmas)))
