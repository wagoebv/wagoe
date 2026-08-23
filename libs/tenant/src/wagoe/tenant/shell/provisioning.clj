(ns wagoe.tenant.shell.provisioning
  "Tenant provisioning service for creating PostgreSQL schemas.
   
   This namespace handles the provisioning of tenant-specific database schemas
   in PostgreSQL. It creates schema structures dynamically for new tenants.
   
   Key Responsibilities:
   - Create PostgreSQL schema for tenant
   - Copy table structures from public schema
   - Initialize tenant-specific data
   - Validate schema creation success
   
   Usage:
     (require '[wagoe.tenant.shell.provisioning :as provisioning])
     (provisioning/provision-tenant! ctx tenant-entity)
   
   Constraints:
   - Only works with PostgreSQL (other databases ignored)
   - Schema names must be valid PostgreSQL identifiers
   - Provisioning is idempotent (safe to call multiple times)
   - Does not copy data from public schema (only structure)"
  (:require [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.tenant.core.tenant :as tenant-core]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.tenant-migrations :as tenant-migrations]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; DDL safety
;; =============================================================================

(defn- assert-safe-schema-name!
  "Guard the DDL boundary: schema names are interpolated into DDL (they cannot
   be parameterized), so reject anything that is not a well-formed tenant schema
   identifier before it reaches a statement string."
  [schema-name]
  (when-not (tenant-core/valid-schema-name? schema-name)
    (throw (ex-info "Unsafe tenant schema name"
                    {:type :validation-error
                     :field :schema-name
                     :schema-name schema-name}))))

;; =============================================================================
;; Schema Structure Introspection
;; =============================================================================

(defn- get-table-ddl
  "Get CREATE TABLE DDL for a table by introspecting its structure.
   
   Args:
     ctx: Database context
     table-name: Name of table to introspect (string)
     target-schema: Target schema name (default: public)
   
   Returns:
     String DDL statement to recreate the table"
  [ctx table-name target-schema]
  (let [columns (db/get-table-info ctx table-name)
        column-defs (map (fn [col]
                           (let [col-name (:name col)
                                 col-type (:type col)
                                 not-null (if (:not-null col) " NOT NULL" "")
                                 pk (if (:primary-key col) " PRIMARY KEY" "")
                                 default (if-let [d (:default col)]
                                           (str " DEFAULT " d)
                                           "")]
                             (str col-name " " col-type not-null pk default)))
                         columns)
        columns-str (str/join ",\n  " column-defs)]
    (str "CREATE TABLE IF NOT EXISTS " target-schema "." table-name " (\n  "
         columns-str
         "\n)")))

(def ^:private tenant-scoped-tables
  "Public tables whose structure belongs in tenant schemas.

   Shared platform/auth tables must stay in `public` and must never be
   provisioned into `tenant_<slug>` schemas."
  #{"contractors"
    "contracts"
    "assignments"
    "timesheets"
    "invoices"
    "compliance_snapshots"
    "compliance_changes"
    "vbar_assessments"
    "import_batches"})

(defn- get-public-tables
  "Get list of all tables in public schema.
   
   Args:
     ctx: Database context
   
   Returns:
     Vector of table names (strings)"
  [ctx]
  (let [query "SELECT table_name 
               FROM information_schema.tables 
               WHERE table_schema = 'public' 
                 AND table_type = 'BASE TABLE'
                 AND table_name NOT IN ('flyway_schema_history', 'migratus_migrations')
               ORDER BY table_name"]
    (->> (db/execute-query! ctx [query])
         (map :table-name)
         (filter tenant-scoped-tables)
         vec)))

;; =============================================================================
;; Schema Provisioning Operations
;; =============================================================================

(defn- schema-exists?
  "Check if PostgreSQL schema exists.
   
   Args:
     ctx: Database context
     schema-name: Schema name to check (string)
   
   Returns:
     Boolean - true if schema exists, false if not found or query fails
   
   Error Handling:
     - Returns false if database query fails
     - Returns false if result is nil (database connection issues)
     - Logs errors for troubleshooting"
  [ctx schema-name]
  (try
    (let [query ["SELECT COUNT(*) as count 
                  FROM information_schema.schemata 
                  WHERE schema_name = ?" schema-name]
          result (db/execute-one! ctx query)]
      (and result (> (:count result) 0)))
    (catch Exception e
      (log/error e "Failed to check schema existence"
                 {:schema-name schema-name
                  :error-type (type e)
                  :error-message (.getMessage e)})
      false)))

(defn- postgresql-context?
  "Return true when the database context points at PostgreSQL.

   Some parts of the platform still report PostgreSQL via a nil HoneySQL
   dialect because PostgreSQL is the library default. Fall back to the JDBC
   driver so provisioning and schema switching still work in real runtimes."
  [ctx]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        driver (some-> adapter protocols/jdbc-driver)]
    (or (= :postgresql dialect)
        (= "org.postgresql.Driver" driver))))

(defn- create-schema!
  "Create PostgreSQL schema if it doesn't exist.
   
   Args:
     ctx: Database context
     schema-name: Schema name to create (string)
   
   Returns:
     nil
   
   Throws:
     Exception if schema creation fails"
  [ctx schema-name]
  (assert-safe-schema-name! schema-name)
  (when-not (schema-exists? ctx schema-name)
    (log/info "Creating tenant schema" {:schema schema-name})
    (db/execute-ddl! ctx (str "CREATE SCHEMA " schema-name)))
  (log/debug "Schema verified" {:schema schema-name :exists? true}))

(defn- copy-table-structure!
  "Copy table structure from public schema to tenant schema.
   
   Args:
     ctx: Database context
     table-name: Table name to copy (string)
     target-schema: Target schema name (string)
   
   Returns:
     nil
   
   Throws:
     Exception if table creation fails"
  [ctx table-name target-schema]
  (assert-safe-schema-name! target-schema)
  (log/debug "Copying table structure" {:table table-name :target-schema target-schema})
  (let [ddl (get-table-ddl ctx table-name target-schema)]
    (db/execute-ddl! ctx ddl)))

(defn- populate-tenant-schema!
  "Build a freshly created tenant schema by running the tenant-scoped migration
   set into it, so new tenants converge on the same migration path used to keep
   existing tenants up to date. Replaces copying table structure from public,
   which only ever copied whole tables and never picked up column/index deltas."
  [schema-name]
  (assert-safe-schema-name! schema-name)
  (tenant-migrations/migrate-tenant! (db/get-active-db-config) schema-name))

(defn- validate-provisioning
  "Validate that tenant schema was provisioned successfully.
   
   Args:
     ctx: Database context
     schema-name: Schema name to validate (string)
   
   Returns:
     {:valid? boolean
      :schema-name string
      :table-count int
      :errors vector}
   
   Throws:
     Does not throw - returns validation result"
  [ctx schema-name]
  (try
    (if-not (schema-exists? ctx schema-name)
      {:valid? false
       :schema-name schema-name
       :errors ["Schema does not exist after provisioning"]}
      (let [query ["SELECT COUNT(*) as count 
                    FROM information_schema.tables 
                    WHERE table_schema = ?" schema-name]
            result (db/execute-one! ctx query)
            table-count (:count result)]
        (if (zero? table-count)
          {:valid? false
           :schema-name schema-name
           :table-count table-count
           :errors ["No tables found in schema after provisioning"]}
          {:valid? true
           :schema-name schema-name
           :table-count table-count
           :errors []})))
    (catch Exception e
      {:valid? false
       :schema-name schema-name
       :errors [(str "Validation failed: " (.getMessage e))]})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn provision-tenant!
  "Provision tenant database schema.
   
   This creates a PostgreSQL schema for the tenant and copies the
   table structure from the public schema. It is idempotent and safe
   to call multiple times.
   
   Steps:
   1. Check if tenant already has schema
   2. Create schema if it doesn't exist
   3. Copy table structures from public schema
   4. Validate provisioning success
   
   Args:
     ctx: Database context with :datasource and :adapter
     tenant-entity: Tenant entity map with :schema-name
   
   Returns:
     {:success? boolean
      :schema-name string
      :table-count int
      :message string
      :errors vector (only if success? false)}
   
   Throws:
     ex-info with :type :validation-error if tenant entity is invalid
     ex-info with :type :not-supported if database is not PostgreSQL
     ex-info with :type :provisioning-error if provisioning fails
   
   Example:
     (provision-tenant! ctx {:schema-name \"tenant_acme_corp\"})
     => {:success? true 
         :schema-name \"tenant_acme_corp\" 
         :table-count 5
         :message \"Tenant schema provisioned successfully\"}"
  [ctx tenant-entity]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)
        schema-name (:schema-name tenant-entity)]

    ;; Validate inputs
    (when-not schema-name
      (throw (ex-info "Tenant entity missing :schema-name"
                      {:type :validation-error
                       :field :schema-name
                       :tenant-entity tenant-entity})))

    (assert-safe-schema-name! schema-name)

    (when-not (postgresql-context? ctx)
      (log/warn "Tenant provisioning only supported for PostgreSQL, skipping"
                {:dialect dialect :schema-name schema-name})
      (throw (ex-info "Tenant provisioning only supported for PostgreSQL"
                      {:type :not-supported
                       :dialect dialect
                       :message "Tenant provisioning requires PostgreSQL database"})))

    ;; Check if already provisioned
    (if (schema-exists? ctx schema-name)
      (do
        (log/info "Tenant schema already exists, skipping provisioning"
                  {:schema-name schema-name})
        (let [validation (validate-provisioning ctx schema-name)]
          (if (:valid? validation)
            {:success? true
             :schema-name schema-name
             :table-count (:table-count validation)
             :message "Tenant schema already provisioned"}
            (throw (ex-info "Tenant schema exists but validation failed"
                            {:type :provisioning-error
                             :schema-name schema-name
                             :validation validation})))))

      ;; Provision new schema
      (try
        (log/info "Starting tenant provisioning" {:schema-name schema-name})

        ;; Create schema
        (create-schema! ctx schema-name)

        ;; Build the schema by running the tenant-scoped migration set into it
        (populate-tenant-schema! schema-name)

        ;; Validate
        (let [validation (validate-provisioning ctx schema-name)]
          (if (:valid? validation)
            (do
              (log/info "Tenant provisioning completed successfully"
                        {:schema-name schema-name
                         :table-count (:table-count validation)})
              {:success? true
               :schema-name schema-name
               :table-count (:table-count validation)
               :message "Tenant schema provisioned successfully"})
            (throw (ex-info "Tenant provisioning validation failed"
                            {:type :provisioning-error
                             :schema-name schema-name
                             :validation validation}))))

        (catch Exception e
          (log/error e "Tenant provisioning failed" {:schema-name schema-name})
          ;; Attempt cleanup on failure
          (try
            (db/execute-ddl! ctx (str "DROP SCHEMA IF EXISTS " schema-name " CASCADE"))
            (log/info "Cleaned up failed provisioning" {:schema-name schema-name})
            (catch Exception cleanup-e
              (log/error cleanup-e "Failed to cleanup after provisioning failure"
                         {:schema-name schema-name})))

          (throw (ex-info "Tenant provisioning failed"
                          {:type :provisioning-error
                           :schema-name schema-name
                           :cause (.getMessage e)}
                          e)))))))

(defn tenant-provisioned?
  "Check if a tenant's database schema has been provisioned.
   Returns true if the schema exists in PostgreSQL, false otherwise.
   Returns false for non-PostgreSQL databases."
  [ctx tenant-entity]
  (let [schema-name (:schema-name tenant-entity)]
    (when-not schema-name
      (throw (ex-info "Tenant entity missing :schema-name"
                      {:type :validation-error
                       :field :schema-name})))
    (if (postgresql-context? ctx)
      (schema-exists? ctx schema-name)
      false)))

(defn list-tenant-schemas
  "List all tenant schemas in the database.
   Returns a vector of schema name strings matching the tenant_* pattern.
   Returns empty vector for non-PostgreSQL databases."
  [ctx]
  (if (postgresql-context? ctx)
    (let [query ["SELECT schema_name FROM information_schema.schemata
                  WHERE schema_name LIKE 'tenant_%'
                  ORDER BY schema_name"]]
      (->> (db/execute-query! ctx query)
           (mapv :schema-name)))
    []))

(defn sync-tenant-schemas!
  "Backfill every existing tenant_<slug> schema with all tenant-scoped tables.

   `provision-tenant!` only copies the tenant-scoped tables into a schema at
   creation time and returns early when the schema already exists. So when
   `tenant-scoped-tables` gains entries in a release, previously-provisioned
   tenants do NOT get the new tables, and any job or repository using them under
   the tenant search_path fails with a missing relation. This is the
   upgrade/sync path: run it on deploy after extending `tenant-scoped-tables`.

   For each existing tenant schema it re-copies the public table structures.
   `get-table-ddl` emits `CREATE TABLE IF NOT EXISTS`, so existing tables are
   left untouched and only missing ones are created — fully idempotent. No-op
   (and empty result) on non-PostgreSQL databases.

   Returns {:schemas-synced [schema-name ...] :tables [table-name ...]}."
  [ctx]
  (if (postgresql-context? ctx)
    (let [schemas (list-tenant-schemas ctx)
          tables  (get-public-tables ctx)]
      (doseq [schema schemas]
        (log/info "Syncing tenant schema tables"
                  {:schema schema :table-count (count tables)})
        (doseq [table tables]
          (copy-table-structure! ctx table schema)))
      {:schemas-synced schemas :tables tables})
    {:schemas-synced [] :tables []}))

(defn deprovision-tenant!
  "Deprovision tenant database schema.
   
   WARNING: This is destructive and will delete all tenant data.
   Use with caution.
   
   Args:
     ctx: Database context
     tenant-entity: Tenant entity map with :schema-name
   
   Returns:
     {:success? boolean
      :schema-name string
      :message string}
   
   Throws:
     ex-info with :type :validation-error if tenant entity is invalid
     ex-info with :type :deprovisioning-error if operation fails"
  [ctx tenant-entity]
  (let [schema-name (:schema-name tenant-entity)]

    (when-not schema-name
      (throw (ex-info "Tenant entity missing :schema-name"
                      {:type :validation-error
                       :field :schema-name
                       :tenant-entity tenant-entity})))

    (assert-safe-schema-name! schema-name)

    (if-not (schema-exists? ctx schema-name)
      {:success? true
       :schema-name schema-name
       :message "Tenant schema does not exist (already deprovisioned)"}

      (try
        (log/warn "Deprovisioning tenant schema (destructive operation)"
                  {:schema-name schema-name})
        (db/execute-ddl! ctx (str "DROP SCHEMA " schema-name " CASCADE"))
        (log/info "Tenant schema deprovisioned successfully"
                  {:schema-name schema-name})
        {:success? true
         :schema-name schema-name
         :message "Tenant schema deprovisioned successfully"}

        (catch Exception e
          (log/error e "Tenant deprovisioning failed" {:schema-name schema-name})
          (throw (ex-info "Tenant deprovisioning failed"
                          {:type :deprovisioning-error
                           :schema-name schema-name
                           :cause (.getMessage e)}
                          e)))))))

;; =============================================================================
;; Tenant Context Execution
;; =============================================================================

(defn- rethrow-tenant-body-error!
  "Classify an exception thrown by a `with-tenant-schema` body (ZZP-99).

   A domain error deliberately thrown inside the body already carries a `:type`
   that HTTP error-mapping layers switch on (`:not-found`, `:forbidden`,
   `:validation-error`, `:conflict`, …). Rethrow such a typed `ex-info` UNWRAPPED
   so tenant-scoped code surfaces the correct status instead of a generic 500.
   Only a genuinely untyped exception (an infra failure) is wrapped as
   `:tenant-context-error`, preserving the original as the cause. Always throws,
   and always logs the schema first — the wrapper is what carries the schema
   name to a reader, so the branch that does not wrap has to put it in the log."
  [e tenant-schema-name]
  ;; Logged either way. This used to log only on the wrapping branch, on the
  ;; assumption that a typed exception is a domain error the caller expects —
  ;; and then BOU-323 typed the database adapters, so a SQL failure inside a
  ;; tenant schema started taking the rethrow branch and lost the one line that
  ;; said which schema it happened in.
  (log/error e "Error executing in tenant schema context"
             {:schema tenant-schema-name :error (.getMessage e)})
  (if (and (instance? clojure.lang.ExceptionInfo e) (:type (ex-data e)))
    (throw e)
    (throw (ex-info "Tenant context execution failed"
                    {:type :tenant-context-error
                     :schema-name tenant-schema-name
                     :cause (.getMessage e)}
                    e))))

(defn with-tenant-schema
  "Execute function f with database search_path set to tenant schema.
   
   This ensures all database operations within f execute in the tenant's
   schema context. The search_path is restored after execution.
   
   Usage:
     (with-tenant-schema ctx \"tenant_acme\"
       (fn [tx]
         ;; All queries here use tenant_acme schema
         (jdbc/execute! tx [\"SELECT * FROM users\"])))
   
   Args:
     ctx: Database context with :datasource
     tenant-schema-name: Name of tenant schema (e.g., \"tenant_acme\")
     f: Function accepting transaction/connection (fn [tx] -> result)
   
   Returns:
     Result of f
   
   Notes:
     - Only works with PostgreSQL
     - search_path is transaction-scoped (isolated per connection)
     - Falls back to public schema if error
     - Schema must exist before calling this function"
  [ctx tenant-schema-name f]
  (assert-safe-schema-name! tenant-schema-name)
  (when-not (postgresql-context? ctx)
    (throw (ex-info "Tenant schema context only supported for PostgreSQL"
                    {:type :unsupported-database
                     :database-type (:database-type ctx)
                     :dialect (some-> (:adapter ctx) protocols/dialect)})))

  (db/with-transaction [tx ctx]
    (try
      ;; Set search_path for this transaction
      ;; Format: "SET search_path TO tenant_schema, public"
      ;; This ensures tenant tables are found first, with public as fallback
      (db/execute-query! tx [(str "SET search_path TO " tenant-schema-name ", public")])
      (log/debug "Set search_path for transaction"
                 {:schema tenant-schema-name})

      ;; Execute function in tenant context
      (f tx)

      (catch Exception e
        (rethrow-tenant-body-error! e tenant-schema-name)))))

;; =============================================================================
;; Tenant Schema Provider (Protocol Implementation)
;; =============================================================================

(defrecord TenantSchemaProvider []
  ports/ITenantSchemaProvider

  (with-tenant-schema [_ db-ctx schema-name f]
    (with-tenant-schema db-ctx schema-name f))

  (tenant-provisioned? [_ db-ctx tenant-entity]
    (tenant-provisioned? db-ctx tenant-entity))

  (list-tenant-schemas [_ db-ctx]
    (list-tenant-schemas db-ctx)))

(defn create-tenant-schema-provider
  "Create a new tenant schema provider instance.
   
   This provider implements the ITenantSchemaProvider protocol, allowing
   other modules (like jobs) to execute code in tenant schema contexts
   without directly depending on implementation details.
   
   Usage:
     (def provider (create-tenant-schema-provider))
     (ports/with-tenant-schema provider db-ctx \"tenant_acme_corp\"
       (fn [tx]
         (jdbc/execute! tx [\"SELECT * FROM users\"])))"
  []
  (->TenantSchemaProvider))
