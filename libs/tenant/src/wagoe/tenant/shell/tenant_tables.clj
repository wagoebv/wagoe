(ns wagoe.tenant.shell.tenant-tables
  "The one definition of tenant's tables (BOU-551): the Malli schemas, built in
   each engine's own types. Boot (`initialize-tenant-schema!`) and the library
   migration both run `ensure-tables!`, so an installation that migrated and
   one that only booted have the same tables."
  (:require [wagoe.platform.database :as db]
            [wagoe.tenant.schema :as tenant-schema]))

(def ^:private indexes
  "[name table columns unique?] the generated DDL does not make. Created
   separately, unless already there, so a table that exists gets them too.
   A deleted tenant keeps its slug: its schema is not dropped, and the schema
   name derives from the slug (BOU-576)."
  [["uk_tenants_slug" "tenants" ["slug"] true]
   ["uk_tenants_schema_name" "tenants" ["schema_name"] true]
   ["uk_tenant_memberships_tenant_user" "tenant_memberships" ["tenant_id" "user_id"] true]
   ["uk_tenant_member_invites_token_hash" "tenant_member_invites" ["token_hash"] true]
   ["idx_tenant_member_invites_email" "tenant_member_invites" ["email"] false]])

(defn ensure-tables!
  "Create the tenant tables and their indexes, unless they are there."
  [ctx]
  (db/initialize-tables-from-schemas! ctx {"tenants" tenant-schema/Tenant
                                           "tenant_memberships" tenant-schema/TenantMembership
                                           "tenant_member_invites" tenant-schema/TenantInvite})
  (doseq [[index table columns unique?] indexes]
    (db/create-index-if-not-exists! ctx index table columns {:unique? unique?})))

(defn up
  "Migratus entry point."
  [config]
  (ensure-tables! (db/context-of (db/migration-connectable config))))

(defn down
  "Nothing. The tables may predate this migration, created at boot and holding
   data, and the runner calls down to back out a failed up on MySQL and H2."
  [_config])
