(ns wagoe.platform.shell.adapters.database.postgresql.core
  "PostgreSQL database adapter implementing the DBAdapter protocol.
   
   This namespace provides PostgreSQL-specific functionality for production
   database deployments. PostgreSQL is a powerful, open-source relational
   database system with advanced features and excellent performance.
   
   Key Features:
   - Case-insensitive string matching with ILIKE
   - Native boolean support
   - Advanced SQL features and data types
   - Robust transaction support
   - Excellent performance and scalability
   
   The adapter delegates to specialized modules for:
   - Connection management (postgresql.connection)
   - Query building (postgresql.query)
   - Metadata operations (postgresql.metadata)
   - Utility functions (postgresql.utils)"
  (:require [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.postgresql.connection :as connection]
            [wagoe.platform.shell.adapters.database.postgresql.metadata :as metadata]
            [wagoe.platform.shell.adapters.database.postgresql.query :as query]
            [wagoe.platform.shell.adapters.database.postgresql.utils :as utils]))

;; =============================================================================
;; PostgreSQL Adapter Implementation
;; =============================================================================

(defrecord PostgreSQLAdapter []
  protocols/DBAdapter

  (dialect [_]
    nil) ; PostgreSQL uses HoneySQL's default dialect

  (jdbc-driver [_]
    "org.postgresql.Driver")

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
  "Create new PostgreSQL adapter instance.

   Returns:
     PostgreSQL adapter implementing DBAdapter protocol"
  []
  (->PostgreSQLAdapter))

;; =============================================================================
;; PostgreSQL-Specific Utilities (Re-exported)
;; =============================================================================

;; URL building
(def create-database-url utils/create-database-url)

;; DDL helpers
(def boolean-column-type utils/boolean-column-type)
(def uuid-column-type utils/uuid-column-type)
(def varchar-uuid-column-type utils/varchar-uuid-column-type)
(def timestamp-column-type utils/timestamp-column-type)
(def serial-primary-key utils/serial-primary-key)
(def bigserial-primary-key utils/bigserial-primary-key)

;; Performance and maintenance
(def explain-query utils/explain-query)
(def analyze-table utils/analyze-table)
(def vacuum-table utils/vacuum-table)

;; Extensions and features
(def enable-extension utils/enable-extension)
(def list-extensions utils/list-extensions)

;; Connection information
(def connection-info utils/connection-info)
(def active-connections utils/active-connections)