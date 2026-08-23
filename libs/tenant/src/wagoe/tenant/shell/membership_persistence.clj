(ns wagoe.tenant.shell.membership-persistence
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.persistence-interceptors :as persistence-interceptors]
            [wagoe.tenant.ports :as ports]
            [clojure.set]))

;; =============================================================================
;; Entity Transformations
;; =============================================================================

(defn- membership-entity->db
  "Convert a membership entity (kebab-case, typed) to a DB record (snake_case, strings).
   Timestamp columns are passed as java.time.Instant — next.jdbc maps them to TIMESTAMPTZ."
  [membership-entity]
  (-> membership-entity
      (update :id type-conversion/uuid->string)
      (update :tenant-id type-conversion/uuid->string)
      (update :user-id type-conversion/uuid->string)
      (update :role type-conversion/keyword->string)
      (update :status type-conversion/keyword->string)
      (clojure.set/rename-keys {:tenant-id   :tenant_id
                                :user-id     :user_id
                                :invited-at  :invited_at
                                :accepted-at :accepted_at
                                :created-at  :created_at
                                :updated-at  :updated_at})))

(defn- db->membership-entity
  "Convert a DB record (snake_case, Instants from next.jdbc) to a membership entity (kebab-case, typed).
   Timestamp columns come back as java.time.Instant from next.jdbc TIMESTAMPTZ columns."
  [db-record]
  (when db-record
    (-> db-record
        (clojure.set/rename-keys {:tenant_id   :tenant-id
                                  :user_id     :user-id
                                  :invited_at  :invited-at
                                  :accepted_at :accepted-at
                                  :created_at  :created-at
                                  :updated_at  :updated-at})
        (update :id type-conversion/string->uuid)
        (update :tenant-id type-conversion/string->uuid)
        (update :user-id type-conversion/string->uuid)
        (update :role type-conversion/string->keyword)
        (update :status type-conversion/string->keyword))))

;; =============================================================================
;; Repository Implementation
;; =============================================================================

(defrecord DatabaseMembershipRepository [ctx logger error-reporter]
  ports/ITenantMembershipRepository

  (find-membership-by-id [_this membership-id]
    (persistence-interceptors/execute-persistence-operation
     :find-membership-by-id
     {:membership-id membership-id}
     (fn [{:keys [params]}]
       (let [query {:select [:*]
                    :from   [:public.tenant_memberships]
                    :where  [:= :id (type-conversion/uuid->string (:membership-id params))]}]
         (db->membership-entity (db/execute-one! ctx query))))
     ctx))

  (find-membership-by-user-and-tenant [_this user-id tenant-id]
    (persistence-interceptors/execute-persistence-operation
     :find-membership-by-user-and-tenant
     {:user-id user-id :tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [query {:select [:*]
                    :from   [:public.tenant_memberships]
                    :where  [:and
                             [:= :user_id (type-conversion/uuid->string (:user-id params))]
                             [:= :tenant_id (type-conversion/uuid->string (:tenant-id params))]]}]
         (db->membership-entity (db/execute-one! ctx query))))
     ctx))

  (find-memberships-by-tenant [_this tenant-id {:keys [limit offset status]
                                                :or   {limit 50 offset 0}}]
    (persistence-interceptors/execute-persistence-operation
     :find-memberships-by-tenant
     {:tenant-id tenant-id :limit limit :offset offset :status status}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id limit offset status]} params
             query (cond-> {:select   [:*]
                            :from     [:public.tenant_memberships]
                            :where    [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                            :order-by [[:created_at :desc]]
                            :limit    limit
                            :offset   offset}
                     status (update :where (fn [w]
                                             [:and w [:= :status (type-conversion/keyword->string status)]])))]
         (mapv db->membership-entity (db/execute-query! ctx query))))
     ctx))

  (find-memberships-by-user [_this user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-memberships-by-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [query {:select   [:*]
                    :from     [:public.tenant_memberships]
                    :where    [:= :user_id (type-conversion/uuid->string (:user-id params))]
                    :order-by [[:created_at :desc]]}]
         (mapv db->membership-entity (db/execute-query! ctx query))))
     ctx))

  (create-membership [_this membership-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-membership
     {:membership-id (:id membership-entity)}
     (fn [_]
       (let [db-record (membership-entity->db membership-entity)
             query     {:insert-into :public.tenant_memberships
                        :values      [db-record]}]
         (db/execute-update! ctx query)
         membership-entity))
     ctx))

  (update-membership [_this membership-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-membership
     {:membership-id (:id membership-entity)}
     (fn [_]
       (let [db-record (membership-entity->db membership-entity)
             updates   (select-keys db-record [:role :status :accepted_at :updated_at])
             query     {:update :public.tenant_memberships
                        :set    updates
                        :where  [:= :id (:id db-record)]}]
         (db/execute-update! ctx query)
         membership-entity))
     ctx))

  (membership-exists? [_this user-id tenant-id]
    (persistence-interceptors/execute-persistence-operation
     :membership-exists?
     {:user-id user-id :tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [query {:select [1]
                    :from   [:public.tenant_memberships]
                    :where  [:and
                             [:= :user_id (type-conversion/uuid->string (:user-id params))]
                             [:= :tenant_id (type-conversion/uuid->string (:tenant-id params))]]}]
         (boolean (db/execute-one! ctx query))))
     ctx)))

(defn create-membership-repository
  [ctx logger error-reporter]
  (->DatabaseMembershipRepository ctx logger error-reporter))
