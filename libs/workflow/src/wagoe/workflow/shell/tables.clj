(ns wagoe.workflow.shell.tables
  "Workflow's tables in a tenant schema. The tenant module runs this as a
   tenant migration, with search_path on the tenant schema alone, so each
   tenant's instances and audit live in its own schema."
  (:require [wagoe.platform.database :as db]
            [wagoe.workflow.shell.entity-uuid :as entity-uuid]
            [wagoe.workflow.shell.unique-instances :as unique-instances]))

(def migration-resource
  "The one definition of the tables, which the boot runs as well."
  "wagoe/workflow/migrations/20260926100000-workflow-tables.up.sql")

(defn ensure-tables!
  "Create the tables in the connection's schema, with the unique index and
   entity_uuid the later public migrations add."
  [connectable]
  (db/execute-migration-ddl! (db/context-of connectable) migration-resource)
  (unique-instances/ensure-unique! connectable)
  (entity-uuid/ensure-entity-uuid! connectable))

(defn up
  "Migratus entry point."
  [config]
  (ensure-tables! (db/migration-connectable config)))

(defn down
  "Nothing: dropping a tenant's tables would drop its instances."
  [_config])
