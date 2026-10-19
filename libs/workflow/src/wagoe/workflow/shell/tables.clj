(ns wagoe.workflow.shell.tables
  "Workflow's tables: their one definition, and where a tenant's are. A tenant
   schema gets them from the same migrations public does, which the tenant
   module runs in each tenant schema."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.database :as db]
            [wagoe.workflow.shell.entity-uuid :as entity-uuid]
            [wagoe.workflow.shell.unique-instances :as unique-instances]))

(def migration-resource
  "The one definition of the tables, which the boot runs as well."
  "wagoe/workflow/migrations/20260926100000-workflow-tables.up.sql")

(def boot-changes
  "What later migrations add to workflow_instances, which the boot adds too,
   and how to tell each is there."
  [{:what     "the unique index on (workflow_id, entity_type, entity_id) (BOU-581)"
    :present? unique-instances/present?
    :ensure!  unique-instances/ensure-unique!}
   {:what     "the entity_uuid column (BOU-589)"
    :present? entity-uuid/present?
    :ensure!  entity-uuid/ensure-entity-uuid!}])

(defn- tables-in?
  "Whether `schema` holds workflow_instances, on `conn`. Asked each time: a
   remembered yes outlives a schema dropped and provisioned again, on this
   node or another, and a missing table then resolves to public's."
  [conn schema]
  (:present (jdbc/execute-one! conn ["SELECT to_regclass(?) IS NOT NULL AS present"
                                     (str schema ".workflow_instances")]
                               {:builder-fn rs/as-unqualified-lower-maps})))

(defn- refusal [message data]
  (ex-info message (merge {:type :configuration-error :message message} data)))

(defn refuse-shared-tables!
  "Throw a :configuration-error rather than let tenant work reach public's
   tables: work in a tenant (`db/*tenant-id*`, `db/*tenant-schema*`) that is
   not on that tenant's transaction (another database's, none, or H2 under
   :allow-h2?, which has no schemas), and a tenant schema without workflow's
   tables (a failed tenant migration)."
  [connectable]
  (let [conn   (db/tx-connection connectable)
        schema db/*tenant-schema*
        tenant db/*tenant-id*]
    (cond
      ;; The thread's transaction is the one bound to `schema`; a caller's own
      ;; transaction on another connection is on public's search_path.
      (and (or schema tenant)
           (not (and conn schema (identical? conn (db/current-transaction)))))
      (throw (refusal (str "Tenant " (or tenant schema) " work is not running on its tenant's"
                           " transaction, and workflow will not use the shared tables")
                      {:tenant-id (some-> tenant str) :schema schema}))

      (and schema (not (tables-in? conn schema)))
      (throw (refusal (str "Schema " schema " has no workflow tables; run the tenant migrations")
                      {:schema schema})))))
