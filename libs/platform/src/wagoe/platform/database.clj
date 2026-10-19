(ns wagoe.platform.database
  "Executing queries against a configured database — platform's data-access API.

   The sanctioned surface for every module's persistence layer. Modules used to
   require `wagoe.platform.shell.adapters.database.common.core` instead, which
   is platform's shell: 20 sites across tenant, user, admin, workflow and
   devtools reaching into another module's internals, carried as the largest
   entry in `.wagoe/check-ports.edn` (BOU-303).

   Deliberately narrower than what it delegates to. Connection-pool lifecycle,
   raw SQL formatting and adapter construction stay internal — a module needs
   to run a query, not to build a pool. `wagoe.platform.ports.database` is the
   other half: what an adapter must implement.

   Not a protocol, and that is the point. There is one way to execute a
   HoneySQL map against a JDBC connection, and the differences between engines
   live behind the `DBAdapter` port. A second protocol here would name a seam
   nothing implements — the mistake ADR-037 removed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.platform.core.database.migration-sql :as migration-sql]
            [wagoe.platform.ports.database :as db-ports]
            [wagoe.platform.shell.adapters.database.common.connection :as connection]
            [wagoe.platform.shell.adapters.database.common.execution :as execution]
            [wagoe.observability.core.sql-redaction :as redaction]
            [wagoe.platform.shell.adapters.database.common.schema :as schema]
            [wagoe.platform.shell.adapters.database.common.utils :as utils]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.adapters.database.utils.schema :as schema-utils]
            [wagoe.platform.shell.database.migrations :as migrations]))

;; =============================================================================
;; Query execution
;; =============================================================================

(def execute-query!
  "Run a query and return every row."
  execution/execute-query!)

(def execute-one!
  "Run a query and return the first row, or nil."
  execution/execute-one!)

(def execute-update!
  "Run an insert/update/delete. Returns the update count."
  execution/execute-update!)

(def execute-batch!
  "Run one statement over many parameter sets."
  execution/execute-batch!)

;; Retention purges call this: one DELETE of a year's rows holds its locks and
;; undo for all of them at once, and blocks writers until it finishes.
(defn delete-in-batches!
  "Delete the rows of `table` matching `where`, at most `batch-size` per
   statement, rows picked by `id-column`. Portable: MySQL takes no LIMIT in an
   IN subquery and PostgreSQL no DELETE … LIMIT, so each batch selects its ids
   first. Returns the number deleted."
  ([ctx table where] (delete-in-batches! ctx table where {}))
  ([ctx table where {:keys [id-column batch-size] :or {id-column :id batch-size 1000}}]
   (loop [total 0]
     (let [ids (mapv (comp val first)
                     (execution/execute-query! ctx {:select [id-column] :from [table] :where where :limit batch-size}))]
       (if (empty? ids)
         total
         (let [n (execution/execute-update! ctx {:delete-from table :where [:in id-column ids]})]
           ;; Zero means a concurrent purge took them all.
           (if (or (< (count ids) batch-size) (zero? n))
             (+ total n)
             (recur (+ total n)))))))))

;; =============================================================================
;; Which engine
;; =============================================================================

;; A function, not an alias, so a stub of factory/engine-of reaches its callers.
(defn engine-of
  "Which engine a DataSource or Connection speaks: :postgresql, :mysql,
   :sqlite or :h2. For code holding no db-context; with one, ask its adapter
   (ADR-039)."
  [db]
  (factory/engine-of db))

(defn context-of
  "A db-context for a DataSource or Connection: it and the adapter for its
   engine, for code that holds a connection and must build DDL, such as a
   migration."
  [db]
  {:datasource db :adapter (factory/adapter-for (factory/engine-of db))})

(defn migration-connectable
  "What a Clojure migration runs on: migratus's open connection, which SQLite
   needs used rather than a second one, or else its datasource."
  [config]
  (let [conn (:conn config)]
    (if (instance? java.sql.Connection conn) conn (:datasource (:db config)))))

;; =============================================================================
;; Transactions
;; =============================================================================

(def with-transaction*
  "Call `f` with a transaction context, committing or rolling back around it."
  execution/with-transaction*)

(defn current-transaction
  "The connection of the transaction `with-transaction` holds open on this
   thread, or nil. For a port that takes a caller's transaction explicitly."
  []
  execution/*transaction-datasource*)

(defn tx-connection
  "The connection behind `tx` when it is an open transaction: a next.jdbc
   connection, or a platform transaction context. nil for anything else, a
   datasource included, so a port that joins a caller's transaction can
   refuse what would autocommit."
  [tx]
  (let [conn (if (map? tx) (or (:datasource tx) (:connectable tx)) tx)]
    (when (and (instance? java.sql.Connection conn)
               (not (.getAutoCommit ^java.sql.Connection conn)))
      conn)))

(defn connectable-for
  "What code holding `datasource` runs a statement on: `datasource` when it is
   an open transaction already, else the platform transaction open on this
   thread on it, else `datasource`. A tenant request's transaction is bound to
   the tenant's schema, so a store that queries through this reads the
   tenant's tables."
  [datasource]
  (if (tx-connection datasource)
    datasource
    (or (execution/transaction-on datasource) datasource)))

(defn in-savepoint
  "Call `f`, and on failure roll back only what it did on `conn`: PostgreSQL
   refuses every later statement of a transaction after a failed one. A nil
   `conn` just calls `f`."
  [^java.sql.Connection conn f]
  (if-not conn
    (f)
    (let [sp (.setSavepoint conn)]
      (try
        (let [result (f)]
          (.releaseSavepoint conn sp)
          result)
        (catch Exception e
          (try (.rollback conn sp)
               (catch java.sql.SQLException re (.addSuppressed e re)))
          (throw e))))))

(defmacro with-transaction
  "Run `body` in a transaction, bound to the first name in `binding`.

     (with-transaction [tx ctx]
       (execute-update! tx query1)
       (execute-update! tx query2))"
  [binding & body]
  `(with-transaction* ~(second binding)
     (fn [~(first binding)]
       ~@body)))

;; =============================================================================
;; Schema
;; =============================================================================

(def table-exists?
  "Whether `table` exists in this database."
  schema/table-exists?)

(def get-table-info
  "Column metadata for `table`."
  schema/get-table-info)

(def execute-ddl!
  "Run a DDL statement."
  schema/execute-ddl!)

(def quote-identifier
  "An identifier quoted for the engine, as stored."
  schema/quote-identifier)

(def create-index-if-not-exists!
  "Create an index unless it is already there."
  schema/create-index-if-not-exists!)

(def execute-ddl-if-absent!
  "Run DDL, counting \"already exists\" as done."
  schema/execute-ddl-if-absent!)

(defn execute-migration-ddl!
  "Run the statements of the migration at classpath `resource`, for a module
   that creates its tables at boot as well as through `migrate up`. On MySQL
   they are rewritten as `migrate up` does, and an index that exists counts
   as done, since the rewrite drops IF NOT EXISTS."
  [ctx resource]
  (let [mysql?     (= :mysql (db-ports/engine (:adapter ctx)))
        execute!   (if mysql? execute-ddl-if-absent! execute-ddl!)
        statements (->> (str/split (slurp (or (io/resource resource)
                                              (throw (ex-info (str "Migration missing from the classpath: " resource)
                                                              {:type :internal-error :resource resource}))))
                                   #"--;;")
                        ;; Without comment lines, as migratus runs it: the MySQL
                        ;; rewrite reads a statement from its first word.
                        (map #(str/trim (str/replace % #"(?m)^\s*--.*$" "")))
                        (remove str/blank?))]
    (doseq [statement (cond-> statements mysql? migration-sql/for-mysql)]
      (execute! ctx statement))))

(def drop-index-if-exists!
  "Drop an index if it is there."
  schema/drop-index-if-exists!)

(def add-columns-if-not-exist!
  "Add the columns a table lacks, reading it once."
  schema/add-columns-if-not-exist!)

(def add-column-if-not-exists!
  "Add a column unless it is already there."
  schema/add-column-if-not-exists!)

(def drop-column-if-exists!
  "Drop a column if it is there."
  schema/drop-column-if-exists!)

;; =============================================================================
;; Query building
;;
;; Helpers for assembling the HoneySQL map the functions above take. Pure —
;; `wagoe.platform.core.database.query` is where the rest of them live.
;; =============================================================================

(def build-where-clause utils/build-where-clause)
(def build-pagination   utils/build-pagination)
(def build-ordering     utils/build-ordering)

;; =============================================================================
;; Introspection
;; =============================================================================

(def database-info
  "Engine, version and connection details for this context."
  utils/database-info)

(def list-tables
  "Every table name in this database."
  utils/list-tables)

;; =============================================================================
;; Context
;; =============================================================================

(def db-context?
  "Whether `x` is a database context this namespace can use."
  execution/db-context?)

;; =============================================================================
;; Connections
;; =============================================================================

(defn unpooled-connection
  "A new connection to the database `ctx` is connected to, outside its pool.
   Tenant migrations need one: they set search_path for the whole session,
   which must not go back to the pool. The caller closes it."
  ^java.sql.Connection [ctx]
  (connection/unpooled-connection (:datasource ctx)))

;; =============================================================================
;; Schema creation from Malli
;; =============================================================================

(def redacted-exception
  "An exception fit to report or log: no SQLException in its chain quotes the
   values sent (BOU-630)."
  redaction/redacted-exception)

(def loggable-message
  "An exception's message, or a summary when the driver's quotes the values sent."
  redaction/loggable-message)

(def initialize-tables-from-schemas!
  "Create the tables described by a collection of Malli schemas, if absent, and
   add the fields an existing one lacks."
  schema-utils/initialize-tables-from-schemas!)

;; =============================================================================
;; Migrations
;; =============================================================================

(def project-migration-dir
  "The name a project's own migrations are read from."
  migrations/project-migration-dir)

(def discover-migration-dirs
  "Every migration directory: the project's first, then each library's."
  migrations/discover-migration-dirs)

(def discover-tenant-migration-dirs
  "The libraries' migration directories that run inside every tenant schema."
  migrations/discover-tenant-migration-dirs)

(def refuse-duplicate-migration-ids!
  "Throw when two migrations in the directories share an id, naming both."
  migrations/refuse-duplicate-migration-ids!)

(def create-destination
  "The directory migratus actually reads `project-migration-dir` from, as a path."
  migrations/create-destination)

(def shadowed-migration-dirs
  "Project migrations no command will read because another source captures the
   name — or nil. `migrate up` refuses while this is non-nil."
  migrations/shadowed-migration-dirs)
