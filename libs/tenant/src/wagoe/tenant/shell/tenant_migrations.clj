(ns wagoe.tenant.shell.tenant-migrations
  "Per-tenant schema migration fan-out.

   The platform `migrate` runs the migration set against the public schema. Each
   tenant lives in its own `tenant_<slug>` schema, so the same migrations must
   also run inside every tenant schema — otherwise a schema change lands in
   public but not in existing tenants, and queries executed under the tenant
   search_path fail with missing relation/column errors (the drift this fixes).

   Each tenant run pins the JDBC connection's search_path to the tenant schema
   via the PostgreSQL `currentSchema` connection property, so migratus creates
   its own `schema_migrations` ledger inside that schema and applies DDL there.

   Because migrations run once per tenant schema, tenant-run migrations must be
   idempotent / tenant-scoped."
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.tenant.core.tenant :as tenant-core]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus]))

(def default-tenant-migration-dir
  "Classpath-relative directory holding tenant-scoped migrations — the set that
   runs inside every tenant schema (must only touch tenant-scoped tables). Kept
   separate from the public `migrations/` set, which runs once against public."
  "migrations-tenant")

(defn tenant-migratus-config
  "Migratus config that applies migrations inside a single tenant schema.

   Pins `currentSchema` to the tenant schema ONLY (no `,public` fallback). This
   is deliberate: with a `<schema>,public` search_path migratus's
   `CREATE TABLE IF NOT EXISTS schema_migrations` resolves to the existing
   `public.schema_migrations`, so every tenant would share the public ledger and
   the second tenant would skip migrations it never actually ran. Tenant-only
   search_path gives each schema its own ledger and full isolation.

   The consequence: migrations run here must be tenant-scoped — they may only
   reference tables that exist in the tenant schema. Point `:migration-dir` at a
   tenant-scoped migration set, not the full public migration set."
  [db-cfg schema-name migration-dir]
  {:store                :database
   :migration-dir        migration-dir
   :init-in-transaction? false
   :migration-table-name "schema_migrations"
   :db                   {:dbtype        "postgresql"
                          :host          (:host db-cfg)
                          :port          (:port db-cfg)
                          :dbname        (:name db-cfg)
                          :user          (:username db-cfg)
                          :password      (:password db-cfg)
                          :currentSchema schema-name}})

(defn migrate-tenant!
  "Run all pending tenant-scoped migrations inside one tenant schema.

   `db-cfg` is a database config map (as from `get-active-db-config`) carrying
   :host/:port/:name/:username/:password. `migration-dir` is a classpath-relative
   tenant migration directory. Throws on a malformed schema name."
  ([db-cfg schema-name]
   (migrate-tenant! db-cfg schema-name default-tenant-migration-dir))
  ([db-cfg schema-name migration-dir]
   (when-not (tenant-core/valid-schema-name? schema-name)
     (throw (ex-info "Unsafe tenant schema name"
                     {:type :validation-error :schema-name schema-name})))
   (log/info "Migrating tenant schema" {:schema schema-name :migration-dir migration-dir})
   (migratus/migrate (tenant-migratus-config db-cfg schema-name migration-dir))
   schema-name))

(defn migrate-all-tenants!
  "Fan out pending tenant-scoped migrations across the given tenant schemas.

   Continues past a failing schema so one bad tenant does not block the rest;
   returns {:schemas-migrated [...] :errors {schema-name message}}. Callers get
   the schema list from `provisioning/list-tenant-schemas` (kept out of this ns
   to avoid a dependency cycle with provisioning)."
  ([schemas] (migrate-all-tenants! (db-config/get-active-db-config) schemas))
  ([db-cfg schemas] (migrate-all-tenants! db-cfg schemas default-tenant-migration-dir))
  ([db-cfg schemas migration-dir]
   (log/info "Fanning out migrations to tenant schemas" {:count (count schemas)})
   (reduce
    (fn [acc schema]
      (try
        (migrate-tenant! db-cfg schema migration-dir)
        (update acc :schemas-migrated conj schema)
        (catch Exception e
          (log/error e "Tenant migration failed" {:schema schema})
          (assoc-in acc [:errors schema] (.getMessage e)))))
    {:schemas-migrated [] :errors {}}
    schemas)))
