(ns wagoe.workflow.shell.tables
  "Workflow's tables: their one definition, and where a tenant's are. A tenant
   schema gets them from the same migrations public does, which the tenant
   module runs in each tenant schema."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as db-ports]
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

(defn- first-schema
  "The schema a search_path puts first, unquoted, `$user` skipped."
  [search-path]
  (->> (str/split (str search-path) #",")
       (map #(str/replace (str/trim %) "\"" ""))
       (remove #{"" "$user"})
       first))

(defn- tenant-schema
  "The schema a transaction's search_path puts ahead of public, and whether
   workflow_instances is in it; nil on public."
  [conn]
  (let [{:keys [path found]}
        (jdbc/execute-one! conn
                           ["SELECT current_setting('search_path') AS path,
                                    (SELECT n.nspname FROM pg_class c
                                       JOIN pg_namespace n ON n.oid = c.relnamespace
                                      WHERE c.oid = to_regclass('workflow_instances')) AS found"]
                           {:builder-fn rs/as-unqualified-lower-maps})
        schema (first-schema path)]
    (when-not (contains? #{nil "public"} schema)
      {:schema schema :tables? (= schema found)})))

(defn- refusal [message data]
  (ex-info message (merge {:type :configuration-error :message message} data)))

(defn refuse-shared-tables!
  "Throw a :configuration-error rather than let tenant work reach public's
   tables: on a transaction bound to a tenant schema that lacks workflow's
   tables (a failed tenant migration), and for work `db/*tenant-id*` assigns
   to a tenant that does not run in a tenant schema (no transaction on this
   store's database, or a database without schemas, H2 under :allow-h2?)."
  [connectable]
  (let [conn   (db/tx-connection connectable)
        tenant db/*tenant-id*]
    (when (or conn tenant)
      (let [schemas?                 (some-> conn db/context-of :adapter (db-ports/capable? :schemas))
            {:keys [schema tables?]} (when schemas? (tenant-schema conn))]
        (cond
          (and schema (not tables?))
          (throw (refusal (str "Schema " schema " has no workflow tables; run the tenant migrations")
                          {:schema schema}))

          (and tenant (nil? schema))
          (throw (refusal (str "Tenant " tenant " work is not running in a tenant schema,"
                               " and workflow will not use the shared tables")
                          {:tenant-id (str tenant)})))))))
