(ns wagoe.tenant.shell.audit-persistence
  "`ITenantAuditRepository` on `tenant_audit_log`."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.database :as db]
            [wagoe.tenant.ports :as ports]))

(defn- entry->db [entry]
  {:id          (type-conversion/uuid->string (:id entry))
   :tenant_id   (type-conversion/uuid->string (:tenant-id entry))
   :action      (type-conversion/keyword->string (:action entry))
   :actor_id    (some-> (:actor-id entry) type-conversion/uuid->string)
   :actor_email (:actor-email entry)
   :ip_address  (:ip-address entry)
   :user_agent  (:user-agent entry)
   :created_at  (:created-at entry)})

(defn- db->entry [row]
  {:id          (type-conversion/string->uuid (:id row))
   :tenant-id   (type-conversion/string->uuid (:tenant-id row))
   :action      (type-conversion/string->keyword (:action row))
   :actor-id    (some-> (:actor-id row) type-conversion/string->uuid)
   :actor-email (:actor-email row)
   :ip-address  (:ip-address row)
   :user-agent  (:user-agent row)
   :created-at  (type-conversion/string->instant (:created-at row))})

(defrecord DatabaseTenantAuditRepository [ctx]
  ports/ITenantAuditRepository
  (record-tenant-audit! [_ entry]
    (db/execute-update! ctx {:insert-into :public.tenant_audit_log :values [(entry->db entry)]})
    entry)

  (find-tenant-audit [_ tenant-id {:keys [limit offset]}]
    (mapv db->entry
          (db/execute-query! ctx {:select   [:*]
                                  :from     [:public.tenant_audit_log]
                                  :where    [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                                  :order-by [[:created_at :desc] [:id :asc]]
                                  :limit    (or limit 100)
                                  :offset   (or offset 0)}))))

(defn create-tenant-audit-repository
  "The tenant audit log on db-context `ctx`."
  [ctx]
  (->DatabaseTenantAuditRepository ctx))
