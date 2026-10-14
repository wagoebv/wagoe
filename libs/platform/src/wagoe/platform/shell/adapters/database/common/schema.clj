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
  (let [cause (execution/redacted-cause e)]
    (ex-info "DDL execution failed"
             {:type           :database-error
              :adapter        (protocols/dialect (:adapter ctx))
              :statement      statement
              :original-error (ex-message cause)}
             cause)))

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
        (let [error (ddl-error ctx ddl-statement e)]
          (log/error "DDL execution failed"
                     {:adapter (protocols/dialect (:adapter ctx))
                      :statement ddl-statement
                      :error (:original-error (ex-data error))
                      :exception-type (type e)})
          (throw error))))))

(defn- claims?
  [ctx capability]
  (execution/validate-context ctx)
  (contains? (protocols/capabilities (:adapter ctx)) capability))

(defn quote-identifier
  "`ident` quoted for `engine`, as stored: backticks on MySQL, double quotes
   elsewhere, so a name with capitals or a reserved word survives."
  [engine ident]
  (if (= :mysql engine)
    (str "`" (str/replace ident "`" "``") "`")
    (str "\"" (str/replace ident "\"" "\"\"") "\"")))

(defn- if-exists-ddl!
  "Run `with-clause` where `native?`, else `without`, counting a failure
   `done?` recognises as success."
  [ctx native? with-clause without done?]
  (if native?
    (execute-ddl! ctx with-clause)
    ;; Not through execute-ddl!, which logs every failure as an ERROR.
    (try
      (jdbc/execute! (execution/current-datasource ctx) [without])
      (catch java.sql.SQLException e
        (when-not (done? (execution/reported e))
          (throw (ddl-error ctx without e)))))))

(defn create-index-if-not-exists!
  "Create an index unless it is there (BOU-607). A unique index the rows break
   is a :conflict naming the table and columns.

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
       (if-exists-ddl! ctx (claims? ctx :index-if-not-exists) (create "IF NOT EXISTS ") (create "")
                       constraint/already-exists?)
       (catch Exception e
         (throw (if (and unique? (= :unique (some-> (execution/reported e) constraint/violation :kind)))
                  (ex-info (str "Cannot make " cols " unique on " (name table)
                                ": rows already share a value. Remove the duplicates, then run again.")
                           {:type :conflict :table (name table) :columns (mapv name columns)}
                           e)
                  e)))))))

(defn drop-index-if-exists!
  "Drop an index if it is there (BOU-607)."
  [ctx index-name table]
  (if-exists-ddl! ctx (claims? ctx :index-if-not-exists)
                  (str "DROP INDEX IF EXISTS " index-name)
                  (str "DROP INDEX " index-name " ON " (name table))
                  constraint/already-gone?))

(defn add-columns-if-not-exist!
  "Add each [column definition] in `columns` that `table` lacks, `definition`
   being what follows the name. Read first because any ALTER locks, even one IF
   NOT EXISTS makes a no-op; a duplicate the read missed counts as done (BOU-616).

   Example:
     (add-columns-if-not-exist! ctx :users [[:nickname \"VARCHAR(50)\"]])"
  [ctx table columns]
  (let [native? (claims? ctx :column-if-not-exists)
        present (set (map :name (get-table-info ctx table)))]
    (doseq [[column definition] columns
            :when (not (present (str/lower-case (name column))))
            :let  [stmt (fn [clause] (str "ALTER TABLE " (name table) " ADD COLUMN " clause
                                          (name column) " " definition))]]
      (log/info "Adding missing column" {:table (name table) :column (name column)})
      (if-exists-ddl! ctx native? (stmt "IF NOT EXISTS ") (stmt "") constraint/already-exists?))))

(defn add-column-if-not-exists!
  "Add `column` to `table` unless it is there.

   Example:
     (add-column-if-not-exists! ctx :users :nickname \"VARCHAR(50)\")"
  [ctx table column definition]
  (add-columns-if-not-exist! ctx table [[column definition]]))

(defn drop-column-if-exists!
  "Drop `column` from `table` if it is there. No read first, as add does: SQLite
   would not list a generated column, so the drop would be skipped."
  [ctx table column]
  (let [stmt (fn [clause] (str "ALTER TABLE " (name table) " DROP COLUMN " clause (name column)))]
    (if-exists-ddl! ctx (claims? ctx :column-if-not-exists) (stmt "IF EXISTS ") (stmt "")
                    constraint/already-gone?)))
