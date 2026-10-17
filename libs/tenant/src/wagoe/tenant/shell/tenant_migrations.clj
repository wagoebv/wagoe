(ns wagoe.tenant.shell.tenant-migrations
  "Per-tenant schema migration fan-out.

   The platform `migrate` runs the migration set against the public schema. Each
   tenant lives in its own `tenant_<slug>` schema, so the same migrations must
   also run inside every tenant schema — otherwise a schema change lands in
   public but not in existing tenants, and queries executed under the tenant
   search_path fail with missing relation/column errors (the drift this fixes).

   Each tenant run pins search_path on a connection of its own to the tenant
   schema, so migratus creates its own `schema_migrations` ledger inside that
   schema and applies DDL there.

   Because migrations run once per tenant schema, tenant-run migrations must be
   idempotent / tenant-scoped."
  (:require [wagoe.platform.database :as db]
            [wagoe.tenant.core.tenant :as tenant-core]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus]))

(def default-tenant-migration-dir
  "Classpath-relative directory holding tenant-scoped migrations — the set that
   runs inside every tenant schema (must only touch tenant-scoped tables). Kept
   separate from the public `migrations/` set, which runs once against public."
  "migrations-tenant")

(defn migrate-tenant!
  "Run all pending tenant-scoped migrations inside one tenant schema of the
   database `ctx` (the boot `:wagoe/db-context`) is connected to.
   `migration-dir` is a classpath-relative tenant migration directory. Throws on
   a malformed schema name.

   Runs on a connection outside the pool, so its session settings close with it
   instead of reaching the application's next query. No statement timeout:
   migrations run long (BOU-658). search_path is the tenant schema only: with a
   `,public` fallback migratus's `CREATE TABLE IF NOT EXISTS schema_migrations`
   resolves to `public.schema_migrations`, every tenant shares that ledger, and
   the second skips migrations it never ran. So migrations run here may only
   reference tables in the tenant schema."
  ([ctx schema-name]
   (migrate-tenant! ctx schema-name default-tenant-migration-dir))
  ([ctx schema-name migration-dir]
   (when-not (tenant-core/valid-schema-name? schema-name)
     (throw (ex-info "Unsafe tenant schema name"
                     {:type :validation-error :schema-name schema-name})))
   (log/info "Migrating tenant schema" {:schema schema-name :migration-dir migration-dir})
   (with-open [conn (db/unpooled-connection ctx)]
     (with-open [st (.createStatement conn)]
       (.execute st (str "SET search_path TO " schema-name))
       (.execute st "SET statement_timeout = 0"))
     (migratus/migrate {:store                :database
                        :migration-dir        migration-dir
                        :init-in-transaction? false
                        :migration-table-name "schema_migrations"
                        :db                   {:connection          conn
                                               :managed-connection? true}}))
   schema-name))

(defn migrate-all-tenants!
  "Fan out pending tenant-scoped migrations across the given tenant schemas.

   Continues past a failing schema so one bad tenant does not block the rest;
   returns {:schemas-migrated [...] :errors {schema-name message}}. Callers get
   the schema list from `provisioning/list-tenant-schemas` (kept out of this ns
   to avoid a dependency cycle with provisioning)."
  ([ctx schemas] (migrate-all-tenants! ctx schemas default-tenant-migration-dir))
  ([ctx schemas migration-dir]
   (log/info "Fanning out migrations to tenant schemas" {:count (count schemas)})
   (reduce
    (fn [acc schema]
      (try
        (migrate-tenant! ctx schema migration-dir)
        (update acc :schemas-migrated conj schema)
        (catch Exception e
          (log/error e "Tenant migration failed" {:schema schema})
          (assoc-in acc [:errors schema] (.getMessage e)))))
    {:schemas-migrated [] :errors {}}
    schemas)))
