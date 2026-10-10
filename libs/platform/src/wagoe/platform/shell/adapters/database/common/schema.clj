(ns wagoe.platform.shell.adapters.database.common.schema
  "Common schema management and DDL utilities."
  (:require [wagoe.platform.core.database.constraint :as constraint]
            [wagoe.platform.ports.database :as protocols]
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

(defn- ddl-error [ctx statement ^Exception e]
  (ex-info "DDL execution failed"
           {:type           :database-error
            :adapter        (protocols/dialect (:adapter ctx))
            :statement      statement
            :original-error (.getMessage e)}
           e))

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
  ;; #"\s+", not #"\\s+", which matched a backslash: the "preview" was the
  ;; whole statement, logged at INFO on every boot (BOU-588).
  (let [statement-preview (str/join " " (take 5 (str/split (str/trim ddl-statement) #"\s+")))]
    (log/debug "Executing DDL statement"
               {:adapter (protocols/dialect (:adapter ctx))
                :statement-preview statement-preview})
    (try
      (let [result (jdbc/execute! (execution/current-datasource ctx) [ddl-statement])]
        (log/debug "DDL statement executed successfully"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :statement-preview statement-preview})
        result)
      (catch Exception e
        (log/error "DDL execution failed"
                   {:adapter (protocols/dialect (:adapter ctx))
                    :statement ddl-statement
                    :error (.getMessage e)
                    :exception-type (type e)})
        (throw (ddl-error ctx ddl-statement e))))))

(defn create-index-if-not-exists!
  "Create an index unless it is there. IF NOT EXISTS where the adapter claims
   :index-if-not-exists; elsewhere (MySQL) the CREATE runs and \"already there\"
   counts as done, so a second call does nothing (BOU-607). A unique index the
   rows break is a :conflict naming the table and columns.

   Example:
     (create-index-if-not-exists! ctx \"idx_users_email\" :users [:email])
     (create-index-if-not-exists! ctx \"uk_users_email\" :users [:email] {:unique? true})"
  ([ctx index-name table columns]
   (create-index-if-not-exists! ctx index-name table columns {}))
  ([ctx index-name table columns {:keys [unique?]}]
   (execution/validate-context ctx)
   (let [native? (contains? (protocols/capabilities (:adapter ctx)) :index-if-not-exists)
         cols    (str/join ", " (map name columns))
         ddl     (str "CREATE " (when unique? "UNIQUE ") "INDEX " (when native? "IF NOT EXISTS ")
                      index-name " ON " (name table) " (" cols ")")]
     (try
       (if native?
         (execute-ddl! ctx ddl)
         ;; Not through execute-ddl!, which logs every failure: an index that is
         ;; already there would be an ERROR line on each boot.
         (try
           (jdbc/execute! (execution/current-datasource ctx) [ddl])
           (catch java.sql.SQLException e
             (when-not (constraint/already-exists? (execution/reported e))
               (throw (ddl-error ctx ddl e))))))
       (catch Exception e
         (throw (if (and unique? (= :unique (some-> (execution/reported e) constraint/violation :kind)))
                  (ex-info (str "Cannot make " cols " unique on " (name table)
                                ": rows already share a value. Remove the duplicates, then run again.")
                           {:type :conflict :table (name table) :columns (mapv name columns)}
                           e)
                  e)))))))
