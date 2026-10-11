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

(defn- native?
  [ctx capability]
  (contains? (protocols/capabilities (:adapter ctx)) capability))

(defn- execute-tolerating!
  "Run `ddl`, counting a failure `done?` recognises as success. Not through
   execute-ddl!, which logs every failure: an index already there would be an
   ERROR line on each boot."
  [ctx ddl done?]
  (try
    (jdbc/execute! (execution/current-datasource ctx) [ddl])
    (catch java.sql.SQLException e
      (when-not (done? (execution/reported e))
        (throw (ddl-error ctx ddl e))))))

(defn- if-exists-ddl!
  "`with-clause` where the adapter claims `capability`, else `without` run
   with the failure `done?` recognises counted as success."
  [ctx capability with-clause without done?]
  (execution/validate-context ctx)
  (if (native? ctx capability)
    (execute-ddl! ctx with-clause)
    (execute-tolerating! ctx without done?)))

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
   (let [cols   (str/join ", " (map name columns))
         create (fn [clause] (str "CREATE " (when unique? "UNIQUE ") "INDEX " clause
                                  index-name " ON " (name table) " (" cols ")"))]
     (try
       (if-exists-ddl! ctx :index-if-not-exists (create "IF NOT EXISTS ") (create "")
                       constraint/already-exists?)
       (catch Exception e
         (throw (if (and unique? (= :unique (some-> (execution/reported e) constraint/violation :kind)))
                  (ex-info (str "Cannot make " cols " unique on " (name table)
                                ": rows already share a value. Remove the duplicates, then run again.")
                           {:type :conflict :table (name table) :columns (mapv name columns)}
                           e)
                  e)))))))

(defn drop-index-if-exists!
  "Drop an index if it is there. DROP INDEX IF EXISTS where the adapter claims
   :index-if-not-exists; MySQL, which has neither, names the table."
  [ctx index-name table]
  (if-exists-ddl! ctx :index-if-not-exists
                  (str "DROP INDEX IF EXISTS " index-name)
                  (str "DROP INDEX " index-name " ON " (name table))
                  constraint/already-gone?))

(defn add-column-if-not-exists!
  "Add `column` to `table` unless it is there. `definition` is what follows the
   column name: its type, and any default or generation clause.

   Example:
     (add-column-if-not-exists! ctx :users :nickname \"VARCHAR(50)\")"
  [ctx table column definition]
  (let [add (fn [clause] (str "ALTER TABLE " (name table) " ADD COLUMN " clause
                              (name column) " " definition))]
    (if-exists-ddl! ctx :column-if-not-exists (add "IF NOT EXISTS ") (add "")
                    constraint/already-exists?)))

(defn drop-column-if-exists!
  "Drop `column` from `table` if it is there."
  [ctx table column]
  (let [drop (fn [clause] (str "ALTER TABLE " (name table) " DROP COLUMN " clause (name column)))]
    (if-exists-ddl! ctx :column-if-not-exists (drop "IF EXISTS ") (drop "")
                    constraint/already-gone?)))
