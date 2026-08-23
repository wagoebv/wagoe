(ns wagoe.platform.shell.adapters.database.mysql.core
  "MySQL database adapter implementing the DBAdapter protocol.
   
   This namespace provides MySQL-specific functionality for production
   database deployments. MySQL is a widely-used, reliable relational
   database system with good performance and broad compatibility.
   
   Key Features:
   - LIKE-based string matching (case-insensitive by default)
   - Boolean values stored as TINYINT(1)
   - Robust connection handling with proper SSL configuration
   - Timezone and SQL mode configuration for consistency
   - Connection pool tuning for server workloads
   
   The adapter delegates to specialized modules for:
   - Connection management (mysql.connection)
   - Query building (mysql.query)
   - Metadata operations (mysql.metadata)
   - Utility functions (mysql.utils)"
  (:require [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.mysql.connection :as connection]
            [wagoe.platform.shell.adapters.database.mysql.metadata :as metadata]
            [wagoe.platform.shell.adapters.database.mysql.query :as query]
            [wagoe.platform.shell.adapters.database.mysql.utils :as utils]))

;; =============================================================================
;; MySQL Adapter Implementation
;; =============================================================================

(defrecord MySQLAdapter []
  protocols/DBAdapter

  (dialect [_]
    :mysql)

  (jdbc-driver [_]
    "com.mysql.cj.jdbc.Driver")

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
  "Create new MySQL adapter instance.

   Returns:
     MySQL adapter implementing DBAdapter protocol"
  []
  (->MySQLAdapter))

;; =============================================================================
;; MySQL-Specific Utilities (Re-exported)
;; =============================================================================

;; URL building
(def create-database-url connection/create-database-url)

;; DDL helpers
(def boolean-column-type utils/boolean-column-type)
(def uuid-column-type utils/uuid-column-type)
(def varchar-uuid-column-type utils/varchar-uuid-column-type)
(def timestamp-column-type utils/timestamp-column-type)
(def datetime-column-type utils/datetime-column-type)
(def auto-increment-primary-key utils/auto-increment-primary-key)

;; Performance and maintenance
(def explain-query utils/explain-query)
(def analyze-table utils/analyze-table)
(def optimize-table utils/optimize-table)

;; Server information
(def server-info utils/server-info)
(def show-engines utils/show-engines)
(def show-variables utils/show-variables)
(def show-processlist utils/show-processlist)
(def show-table-status utils/show-table-status)