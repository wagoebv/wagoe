(ns wagoe.platform.shell.adapters.database.common.core
  "Common database operations - main coordination module.

   This namespace serves as the main coordinator for common database
   functionality, bringing together specialized modules for different
   aspects of database operations that work across all adapter types.

   The common functionality has been refactored into specialized namespaces:
   - common.connection: Connection pool management with HikariCP
   - common.execution: Query execution with logging and error handling
   - common.schema: Schema introspection and DDL execution
   - common.utils: Database information and convenience functions
   - common.core: Main coordination module (this namespace)
   
   Pure query building functions are now in wagoe.platform.core.database.query

   This modular structure provides:
   - Better organization with focused responsibilities
   - Easier maintenance and testing
   - Consistent patterns across all database adapters
   - Clear separation of concerns"
  (:require [wagoe.platform.shell.adapters.database.common.connection :as connection]
            [wagoe.platform.shell.adapters.database.common.execution :as execution]
            [wagoe.platform.shell.adapters.database.common.schema :as schema]
            [wagoe.platform.shell.adapters.database.common.utils :as utils]))

;; =============================================================================
;; Connection Pool Management (Re-exported from connection)
;; =============================================================================

(def create-connection-pool connection/create-connection-pool)
(def close-connection-pool! connection/close-connection-pool!)

;; =============================================================================
;; Context Validation (Re-exported from execution)
;; =============================================================================

(def db-context? execution/db-context?)
(def validate-context execution/validate-context)
(def validate-adapter execution/validate-adapter)

;; =============================================================================
;; Query Execution (Re-exported from execution)
;; =============================================================================

(def execute-query! execution/execute-query!)
(def execute-one! execution/execute-one!)
(def execute-update! execution/execute-update!)
(def execute-batch! execution/execute-batch!)

;; =============================================================================
;; Transaction Management (Re-exported from execution)
;; =============================================================================

(def with-transaction* execution/with-transaction*)

(defmacro with-transaction
  "Macro for database transaction management with consistent error handling.

   Args:
     binding: [tx-var ctx]
     body: Expressions to execute within transaction

   Example:
     (with-transaction [tx ctx]
       (execute-update! tx query1)
       (execute-update! tx query2))"
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))

;; =============================================================================
;; Schema Management (Re-exported from schema)
;; =============================================================================

(def table-exists? schema/table-exists?)
(def get-table-info schema/get-table-info)
(def execute-ddl! schema/execute-ddl!)
(def create-index-if-not-exists! schema/create-index-if-not-exists!)

;; =============================================================================
;; Query Building and Formatting (Re-exported from utils)
;; Note: Pure query building is now in wagoe.platform.core.database.query
;; =============================================================================

(def format-sql utils/format-sql)
(def format-sql* utils/format-sql*)
(def build-where-clause utils/build-where-clause)
(def build-pagination utils/build-pagination)
(def build-ordering utils/build-ordering)

;; =============================================================================
;; Database Information (Re-exported from utils)
;; =============================================================================

(def database-info utils/database-info)
(def list-tables utils/list-tables)
