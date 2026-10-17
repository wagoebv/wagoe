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
  (:require [wagoe.platform.shell.adapters.database.common.connection :as connection]
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

(def create-destination
  "The directory migratus actually reads `project-migration-dir` from, as a path."
  migrations/create-destination)

(def shadowed-migration-dirs
  "Project migrations no command will read because another source captures the
   name — or nil. `migrate up` refuses while this is non-nil."
  migrations/shadowed-migration-dirs)
