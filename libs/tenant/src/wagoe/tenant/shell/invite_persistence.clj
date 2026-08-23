(ns wagoe.tenant.shell.invite-persistence
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.persistence-interceptors :as persistence-interceptors]
            [wagoe.tenant.ports :as ports]
            [cheshire.core :as json]
            [clojure.set])
  (:import [java.sql Timestamp]))

(defn- parse-json-value [value]
  (cond
    (nil? value) nil
    (map? value) value
    (string? value) (json/parse-string value true)
    (= "org.postgresql.util.PGobject" (.getName (class value))) (some-> (.getValue value) (json/parse-string true))
    :else value))

(defn- ->instant [value]
  (cond
    (nil? value) nil
    (instance? java.time.Instant value) value
    (instance? Timestamp value) (.toInstant ^Timestamp value)
    :else value))

(defn- invite-entity->db
  [invite-entity]
  (-> invite-entity
      (update :id type-conversion/uuid->string)
      (update :tenant-id type-conversion/uuid->string)
      (update :role type-conversion/keyword->string)
      (update :status type-conversion/keyword->string)
      (update :accepted-by-user-id #(when % (type-conversion/uuid->string %)))
      (update :metadata #(when % (json/generate-string %)))
      (clojure.set/rename-keys {:tenant-id :tenant_id
                                :token-hash :token_hash
                                :expires-at :expires_at
                                :accepted-at :accepted_at
                                :revoked-at :revoked_at
                                :accepted-by-user-id :accepted_by_user_id
                                :created-at :created_at
                                :updated-at :updated_at})))

(defn db->invite-entity
  [db-record]
  (when db-record
    (-> db-record
        (clojure.set/rename-keys {:tenant_id :tenant-id
                                  :token_hash :token-hash
                                  :expires_at :expires-at
                                  :accepted_at :accepted-at
                                  :revoked_at :revoked-at
                                  :accepted_by_user_id :accepted-by-user-id
                                  :created_at :created-at
                                  :updated_at :updated-at})
        (update :id type-conversion/string->uuid)
        (update :tenant-id type-conversion/string->uuid)
        (update :role type-conversion/string->keyword)
        (update :status type-conversion/string->keyword)
        (update :accepted-by-user-id #(when % (type-conversion/string->uuid %)))
        (update :expires-at ->instant)
        (update :accepted-at ->instant)
        (update :revoked-at ->instant)
        (update :created-at ->instant)
        (update :updated-at ->instant)
        (update :metadata parse-json-value))))

(defn lock-pending-invite-by-token-hash
  [ctx token-hash]
  (some-> (db/execute-one! ctx ["SELECT * FROM public.tenant_member_invites WHERE token_hash = ? AND status = 'pending' FOR UPDATE"
                                token-hash])
          db->invite-entity))

(defrecord DatabaseInviteRepository [ctx logger error-reporter]
  ports/ITenantInviteRepository

  (find-invite-by-id [_ invite-id]
    (persistence-interceptors/execute-persistence-operation
     :find-invite-by-id
     {:invite-id invite-id}
     (fn [{:keys [params]}]
       (let [query {:select [:*]
                    :from [:public.tenant_member_invites]
                    :where [:= :id (type-conversion/uuid->string (:invite-id params))]}]
         (db->invite-entity (db/execute-one! ctx query))))
     ctx))

  (find-pending-invite-by-token-hash [_ token-hash]
    (persistence-interceptors/execute-persistence-operation
     :find-pending-invite-by-token-hash
     {:token-hash token-hash}
     (fn [{:keys [params]}]
       (let [query {:select [:*]
                    :from [:public.tenant_member_invites]
                    :where [:and
                            [:= :token_hash (:token-hash params)]
                            [:= :status "pending"]]}]
         (db->invite-entity (db/execute-one! ctx query))))
     ctx))

  (find-pending-invite-by-email-and-tenant [_ tenant-id email]
    (persistence-interceptors/execute-persistence-operation
     :find-pending-invite-by-email-and-tenant
     {:tenant-id tenant-id :email email}
     (fn [{:keys [params]}]
       (let [query {:select [:*]
                    :from [:public.tenant_member_invites]
                    :where [:and
                            [:= :tenant_id (type-conversion/uuid->string (:tenant-id params))]
                            [:= :email (:email params)]
                            [:= :status "pending"]]}]
         (db->invite-entity (db/execute-one! ctx query))))
     ctx))

  (find-invites-by-tenant [_ tenant-id {:keys [limit offset status]
                                        :or {limit 50 offset 0}}]
    (persistence-interceptors/execute-persistence-operation
     :find-invites-by-tenant
     {:tenant-id tenant-id :limit limit :offset offset :status status}
     (fn [{:keys [params]}]
       (let [{:keys [tenant-id limit offset status]} params
             query (cond-> {:select [:*]
                            :from [:public.tenant_member_invites]
                            :where [:= :tenant_id (type-conversion/uuid->string tenant-id)]
                            :order-by [[:created_at :desc]]
                            :limit limit
                            :offset offset}
                     status (update :where (fn [w]
                                             [:and w [:= :status (type-conversion/keyword->string status)]])))]
         (mapv db->invite-entity (db/execute-query! ctx query))))
     ctx))

  (create-invite [_ invite-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-invite
     {:invite-id (:id invite-entity)}
     (fn [_]
       (let [query {:insert-into :public.tenant_member_invites
                    :values [(invite-entity->db invite-entity)]}]
         (db/execute-update! ctx query)
         invite-entity))
     ctx))

  (update-invite [_ invite-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-invite
     {:invite-id (:id invite-entity)}
     (fn [_]
       (let [db-record (invite-entity->db invite-entity)
             updates (select-keys db-record [:status
                                             :accepted_at
                                             :revoked_at
                                             :accepted_by_user_id
                                             :updated_at])
             query {:update :public.tenant_member_invites
                    :set updates
                    :where [:= :id (:id db-record)]}]
         (db/execute-update! ctx query)
         invite-entity))
     ctx)))

(defn create-invite-repository
  [ctx logger error-reporter]
  (->DatabaseInviteRepository ctx logger error-reporter))
