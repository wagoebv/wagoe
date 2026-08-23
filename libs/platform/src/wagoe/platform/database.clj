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
  (:require [wagoe.platform.shell.adapters.database.common.execution :as execution]
            [wagoe.platform.shell.adapters.database.common.schema :as schema]
            [wagoe.platform.shell.adapters.database.common.utils :as utils]
            [wagoe.platform.shell.adapters.database.config :as config]
            [wagoe.platform.shell.adapters.database.utils.schema :as schema-utils]))

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

(def create-index-if-not-exists!
  "Create an index unless it is already there."
  schema/create-index-if-not-exists!)

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
;; Configuration
;; =============================================================================

(def get-active-db-config
  "The database configuration the application is running on.

   Published because tenant provisioning needs it to open a connection to a
   second database — the one case where a module legitimately asks what it is
   connected to."
  config/get-active-db-config)

;; =============================================================================
;; Schema creation from Malli
;; =============================================================================

(def initialize-tables-from-schemas!
  "Create the tables described by a collection of Malli schemas, if absent."
  schema-utils/initialize-tables-from-schemas!)
