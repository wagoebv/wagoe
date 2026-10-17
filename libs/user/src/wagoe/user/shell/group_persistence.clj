(ns wagoe.user.shell.group-persistence
  "IUserGroupRepository on `user_groups`, `user_group_members` and
   `user_group_grants`, which `initialize-user-schema!` creates. A scope is
   stored as EDN text, so a uuid or keyword in it reads back as one."
  (:require [clojure.edn :as edn]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.user.ports :as ports])
  (:import (java.time Instant)
           (java.util UUID)))

(defn- id-str [id] (tc/uuid->string (tc/string->uuid (str id))))

(defn- db->group [row]
  (when row
    (cond-> {:id          (tc/string->uuid (:id row))
             :name        (:name row)
             :description (:description row)
             :created-at  (tc/string->instant (:created-at row))
             :updated-at  (tc/string->instant (:updated-at row))}
      (contains? row :member-count) (assoc :member-count (long (:member-count row)))
      (contains? row :grant-count)  (assoc :grant-count (long (:grant-count row))))))

(defn- permission->db [kw] (subs (str kw) 1))

(defn- db->grant [row]
  (cond-> {:id         (tc/string->uuid (:id row))
           :group-id   (tc/string->uuid (:group-id row))
           :permission (keyword (:permission row))
           :scope      (edn/read-string (str (:scope row)))
           :created-at (tc/string->instant (:created-at row))}
    (contains? row :group-name) (assoc :group-name (:group-name row))))

(defn- counted
  "A subquery counting `table`'s rows for the group, live users only for
   memberships."
  [table]
  (if (= table :user_group_members)
    {:select [[[:count :*]]]
     :from   [[:user_group_members :m]]
     :join   [[:auth_users :a] [:= :a.id :m.user_id]]
     :where  [:and [:= :m.group_id :g.id] [:= :a.deleted_at nil]]}
    {:select [[[:count :*]]]
     :from   [[table :t]]
     :where  [:= :t.group_id :g.id]}))

(defn- member? [ctx group-id user-id]
  (some? (db/execute-one! ctx {:select [:id]
                               :from   [:user_group_members]
                               :where  [:and [:= :group_id (id-str group-id)]
                                        [:= :user_id (id-str user-id)]]})))

(defrecord DatabaseUserGroupRepository [ctx]
  ports/IUserGroupRepository

  (find-group [_ group-id]
    (db->group (db/execute-one! ctx {:select [:*]
                                     :from   [:user_groups]
                                     :where  [:= :id (id-str group-id)]})))

  (find-groups [_]
    (mapv db->group (db/execute-query! ctx {:select   [:g.id :g.name :g.description :g.created_at :g.updated_at
                                                       [(counted :user_group_members) :member-count]
                                                       [(counted :user_group_grants) :grant-count]]
                                            :from     [[:user_groups :g]]
                                            :order-by [[:g.name :asc]]})))

  (insert-group [_ group]
    (let [stored (assoc (select-keys group [:name :description])
                        :id (UUID/randomUUID) :created-at (Instant/now) :updated-at nil)]
      (db/execute-update! ctx {:insert-into :user_groups
                               :values      [{:id          (tc/uuid->string (:id stored))
                                              :name        (:name stored)
                                              :description (:description stored)
                                              :created_at  (tc/instant->string (:created-at stored))}]})
      stored))

  (modify-group [this group]
    (when (pos? (db/execute-update! ctx {:update :user_groups
                                         :set    {:name        (:name group)
                                                  :description (:description group)
                                                  :updated_at  (tc/instant->string (Instant/now))}
                                         :where  [:= :id (id-str (:id group))]}))
      (ports/find-group this (:id group))))

  (remove-group [_ group-id]
    ;; Explicit, where the keys' cascade would do: SQLite enforces keys only
    ;; on connections that ask.
    (let [id (id-str group-id)]
      (db/with-transaction [tx ctx]
        (db/execute-update! tx {:delete-from :user_group_grants :where [:= :group_id id]})
        (db/execute-update! tx {:delete-from :user_group_members :where [:= :group_id id]})
        (pos? (db/execute-update! tx {:delete-from :user_groups :where [:= :id id]})))))

  (find-members [_ group-id]
    (mapv (fn [row] {:id (tc/string->uuid (:id row)) :email (:email row) :name (:name row)})
          (db/execute-query! ctx {:select   [:a.id :a.email :u.name]
                                  :from     [[:user_group_members :m]]
                                  :join     [[:auth_users :a] [:= :a.id :m.user_id]]
                                  :left-join [[:users :u] [:= :u.id :m.user_id]]
                                  :where    [:and [:= :m.group_id (id-str group-id)] [:= :a.deleted_at nil]]
                                  :order-by [[:a.email :asc]]})))

  (insert-member [_ group-id user-id]
    (if (member? ctx group-id user-id)
      false
      (try
        (db/execute-update! ctx {:insert-into :user_group_members
                                 :values      [{:id         (tc/uuid->string (UUID/randomUUID))
                                                :group_id   (id-str group-id)
                                                :user_id    (id-str user-id)
                                                :created_at (tc/instant->string (Instant/now))}]})
        true
        ;; Two requests adding the same member: the unique key refuses one.
        (catch Exception e
          (if (member? ctx group-id user-id) false (throw e))))))

  (remove-member [_ group-id user-id]
    (pos? (db/execute-update! ctx {:delete-from :user_group_members
                                   :where       [:and [:= :group_id (id-str group-id)]
                                                 [:= :user_id (id-str user-id)]]})))

  (find-grants [_ group-id]
    (mapv db->grant (db/execute-query! ctx {:select   [:*]
                                            :from     [:user_group_grants]
                                            :where    [:= :group_id (id-str group-id)]
                                            :order-by [[:created_at :asc] [:id :asc]]})))

  (insert-grant [_ grant]
    (let [stored (assoc (select-keys grant [:group-id :permission :scope])
                        :id (UUID/randomUUID) :created-at (Instant/now))]
      (db/execute-update! ctx {:insert-into :user_group_grants
                               :values      [{:id         (tc/uuid->string (:id stored))
                                              :group_id   (id-str (:group-id stored))
                                              :permission (permission->db (:permission stored))
                                              :scope      (pr-str (:scope stored))
                                              :created_at (tc/instant->string (:created-at stored))}]})
      stored))

  (remove-grant [_ group-id grant-id]
    (pos? (db/execute-update! ctx {:delete-from :user_group_grants
                                   :where       [:and [:= :id (id-str grant-id)]
                                                 [:= :group_id (id-str group-id)]]})))

  (find-user-grants [_ user-id]
    (mapv db->grant (db/execute-query! ctx {:select   [:gr.id :gr.group_id :gr.permission :gr.scope :gr.created_at
                                                       [:g.name :group-name]]
                                            :from     [[:user_group_grants :gr]]
                                            :join     [[:user_groups :g] [:= :g.id :gr.group_id]
                                                       [:user_group_members :m] [:= :m.group_id :gr.group_id]
                                                       [:auth_users :a] [:= :a.id :m.user_id]]
                                            :where    [:and [:= :m.user_id (id-str user-id)] [:= :a.deleted_at nil]]
                                            :order-by [[:g.name :asc] [:gr.created_at :asc]]})))

  (find-user-groups [_ user-id]
    (mapv db->group (db/execute-query! ctx {:select   [:g.id :g.name :g.description :g.created_at :g.updated_at]
                                            :from     [[:user_groups :g]]
                                            :join     [[:user_group_members :m] [:= :m.group_id :g.id]]
                                            :where    [:= :m.user_id (id-str user-id)]
                                            :order-by [[:g.name :asc]]}))))

(defn create-group-repository [ctx]
  (->DatabaseUserGroupRepository ctx))
