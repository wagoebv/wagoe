(ns wagoe.platform.shell.adapters.database.h2.core
  "H2 database adapter - main entry point implementing the DBAdapter protocol.

   This namespace serves as the main entry point for H2 database
   functionality, implementing the DBAdapter protocol and coordinating
   specialized modules for different aspects of H2 operations.

   The adapter has been refactored into 5 specialized namespaces:
   - h2.connection: Connection management and H2-specific settings
   - h2.query: H2-specific query building and boolean handling
   - h2.metadata: Table introspection and schema information
   - h2.utils: Utility functions and DDL helpers
   - h2.core: Main adapter implementation and coordination (this namespace)

   Key Features:
   - PostgreSQL compatibility mode for easier migration
   - In-memory databases for fast testing
   - File-based databases for development
   - Standard SQL features with good performance
   - Native boolean support (no conversion needed)

   H2-Specific Optimizations:
   - PostgreSQL compatibility mode by default
   - Proper timezone handling
   - Case-insensitive identifiers for compatibility
   - Optimized connection pool settings for embedded usage"
  (:require [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [wagoe.platform.shell.adapters.database.h2.connection :as connection]
            [wagoe.platform.shell.adapters.database.h2.metadata :as metadata]
            [wagoe.platform.shell.adapters.database.h2.query :as query]
            [wagoe.platform.shell.adapters.database.h2.utils :as utils]
            [wagoe.platform.shell.adapters.database.common.core :refer [with-transaction*]]))

;; =============================================================================
;; H2 Adapter Implementation
;; =============================================================================

(defrecord H2Adapter []
  protocols/DBAdapter

  (dialect [_]
    :ansi)  ; H2 uses HoneySQL's ANSI SQL dialect

  (jdbc-driver [_]
    "org.h2.Driver")

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
  "Create new H2 adapter instance.

   Returns:
     H2 adapter implementing DBAdapter protocol"
  []
  (->H2Adapter))

;; =============================================================================
;; H2-Specific Utilities (Re-exported)
;; =============================================================================

;; Connection and URL building
(def in-memory-url connection/in-memory-url)
(def file-url connection/file-url)

;; DDL helpers
(def boolean-column-type utils/boolean-column-type)
(def uuid-column-type utils/uuid-column-type)
(def timestamp-column-type utils/timestamp-column-type)
(def auto-increment-primary-key utils/auto-increment-primary-key)

;; Performance and maintenance
(def explain-query utils/explain-query)
(def analyze-table utils/analyze-table)

;; Database information
(def show-tables utils/show-tables)
(def show-indexes utils/show-indexes)

;; Development and testing utilities
(def create-test-context utils/create-test-context)

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defmacro with-transaction
  "Macro for H2 transaction management with consistent error handling.

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
