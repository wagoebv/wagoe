(ns wagoe.platform.shell.adapters.database.common.schema
  "Common schema management and DDL utilities."
  (:require [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [wagoe.platform.shell.adapters.database.common.execution :as execution]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]))

;; =============================================================================
;; Schema Introspection
;; =============================================================================

(defn table-exists?
  "Check if a table exists using adapter-specific introspection.

   Args:
     ctx: Database context
     table-name: String or keyword table name

   Returns:
     Boolean - true if table exists

   Example:
     (table-exists? ctx :users)"
  [ctx table-name]
  (execution/validate-context ctx)
  (protocols/table-exists? (:adapter ctx) (execution/current-datasource ctx) table-name))

(defn get-table-info
  "Get column information for a table using adapter-specific introspection.

   Args:
     ctx: Database context
     table-name: String or keyword table name

   Returns:
     Vector of column info maps

   Example:
     (get-table-info ctx :users)"
  [ctx table-name]
  (execution/validate-context ctx)
  (protocols/get-table-info (:adapter ctx) (execution/current-datasource ctx) table-name))

;; =============================================================================
;; DDL Execution
;; =============================================================================

(defn execute-ddl!
  "Execute DDL statement with logging.

   Args:
     ctx: Database context
     ddl-statement: String DDL statement

   Returns:
     Execution result

   Example:
     (execute-ddl! ctx \"CREATE TABLE users (id TEXT PRIMARY KEY)\")"
  [ctx ddl-statement]
  (execution/validate-context ctx)
  (let [statement-preview (str/join " " (take 5 (str/split ddl-statement #"\\s+")))]
    (log/debug "Executing DDL statement"
               {:adapter (protocols/dialect (:adapter ctx))
                :statement-preview statement-preview})
    (try
      (let [result (jdbc/execute! (execution/current-datasource ctx) [ddl-statement])]
        (log/info "DDL statement executed successfully"
                  {:adapter (protocols/dialect (:adapter ctx))
                   :statement-preview statement-preview})
        result)
      (catch Exception e
        (log/error "DDL execution failed"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :statement ddl-statement
                    :error (.getMessage e)
                    :exception-type (type e)})
        (throw (ex-info "DDL execution failed"
                        {:type :database-error
                         :adapter (protocols/dialect (:adapter ctx))
                         :statement ddl-statement
                         :original-error (.getMessage e)}
                        e))))))

(defn create-index-if-not-exists!
  "Create index with IF NOT EXISTS support when available.

   Args:
     ctx: Database context
     index-name: String index name
     table: String or keyword table name
     columns: Vector of column names

   Returns:
     Execution result

   Example:
     (create-index-if-not-exists! ctx \"idx_users_email\" :users [:email])"
  [ctx index-name table columns]
  (execution/validate-context ctx)
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        table-str (name table)
        cols-str (str/join ", " (map name columns))
        ;; Most databases support IF NOT EXISTS for indexes
        if-not-exists (case dialect
                        :mysql ""  ; MySQL doesn't support IF NOT EXISTS for indexes
                        "IF NOT EXISTS ")
        ddl (str "CREATE INDEX " if-not-exists index-name " ON " table-str " (" cols-str ")")]
    (execute-ddl! ctx ddl)))
