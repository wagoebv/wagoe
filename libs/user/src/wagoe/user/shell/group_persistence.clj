(ns wagoe.user.shell.group-persistence
  "IUserGroupRepository on `user_groups`, `user_group_members` and
   `user_group_grants`, which `initialize-user-schema!` creates. A scope is
   stored as EDN text, so a uuid or keyword in it reads back as one."
  (:require [clojure.edn :as edn]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence])
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

(defn- db->scope [row]
  (edn/read-string (str (:scope row))))

(defn- db->grant [row]
  (cond-> {:id         (tc/string->uuid (:id row))
           :group-id   (tc/string->uuid (:group-id row))
           :permission (keyword (:permission row))
           :scope      (db->scope row)
           :created-at (tc/string->instant (:created-at row))}
    (contains? row :group-name) (assoc :group-name (:group-name row))))

(def ^:private member-count
  "The group's live members, as a subquery of `find-groups`."
  {:select [[[:count :*]]]
   :from   [[:user_group_members :m]]
   :join   [[:auth_users :a] [:= :a.id :m.user_id]]
   :where  [:and [:= :m.group_id :g.id] [:= :a.deleted_at nil]]})

(def ^:private grant-count
  {:select [[[:count :*]]]
   :from   [[:user_group_grants :t]]
   :where  [:= :t.group_id :g.id]})

(defn- unique-violation?
  "Whether `e` is the :conflict the execution layer makes of a unique key."
  [e]
  (= [:conflict :unique] ((juxt :type :constraint) (ex-data e))))

(defn- name-taken!
  "Run `f`, a write to user_groups, turning its unique name refused into a
   :conflict on :name the admin form shows."
  [f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (throw (if (unique-violation? e)
                  (ex-info "Another group has this name" {:type :conflict :field :name} e)
                  e)))))

(defrecord DatabaseUserGroupRepository [ctx]
  ports/IUserGroupRepository

  (find-group [_ group-id]
    (db->group (db/execute-one! ctx {:select [:*]
                                     :from   [:user_groups]
                                     :where  [:= :id (id-str group-id)]})))

  (find-groups [_]
    (mapv db->group (db/execute-query! ctx {:select   [:g.id :g.name :g.description :g.created_at :g.updated_at
                                                       [member-count :member-count]
                                                       [grant-count :grant-count]]
                                            :from     [[:user_groups :g]]
                                            :order-by [[:g.name :asc]]})))

  (insert-group [_ group]
    (let [stored (assoc (select-keys group [:name :description])
                        :id (UUID/randomUUID) :created-at (Instant/now) :updated-at nil)]
      (name-taken! #(db/execute-update! ctx {:insert-into :user_groups
                                             :values      [{:id          (tc/uuid->string (:id stored))
                                                            :name        (:name stored)
                                                            :description (:description stored)
                                                            :created_at  (:created-at stored)}]}))
      stored))

  (modify-group [this group]
    (when (pos? (name-taken! #(db/execute-update! ctx {:update :user_groups
                                                       :set    (cond-> {:name       (:name group)
                                                                        :updated_at (Instant/now)}
                                                                 (contains? group :description)
                                                                 (assoc :description (:description group)))
                                                       :where  [:= :id (id-str (:id group))]})))
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
    (try
      (db/execute-update! ctx {:insert-into :user_group_members
                               :values      [{:id         (tc/uuid->string (UUID/randomUUID))
                                              :group_id   (id-str group-id)
                                              :user_id    (id-str user-id)
                                              :created_at (Instant/now)}]})
      true
      ;; Read nothing after: in a PostgreSQL transaction the refusal aborts it.
      (catch clojure.lang.ExceptionInfo e
        (cond
          (unique-violation? e) false
          ;; The group or user went meanwhile: a foreign key refused the row.
          (= :conflict (:type (ex-data e))) (throw (ex-info "Could not add the member" {:type :conflict} e))
          :else (throw e)))))

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
                                              :permission (group/permission-str (:permission stored))
                                              :scope      (pr-str (:scope stored))
                                              :created_at (:created-at stored)}]})
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
                                            :join     [[:user_group_members :m] [:= :m.group_id :g.id]
                                                       [:auth_users :a] [:= :a.id :m.user_id]]
                                            :where    [:and [:= :m.user_id (id-str user-id)] [:= :a.deleted_at nil]]
                                            :order-by [[:g.name :asc]]})))

  (find-permission-holders [_ permission]
    (->> (db/execute-query! ctx {:select   [:m.user_id :gr.scope]
                                 :from     [[:user_group_grants :gr]]
                                 :join     [[:user_group_members :m] [:= :m.group_id :gr.group_id]
                                            [:auth_users :a] [:= :a.id :m.user_id]]
                                 :where    [:and [:= :gr.permission (group/permission-str permission)]
                                            [:= :a.deleted_at nil]
                                            [:= :a.active (protocols/boolean->db (:adapter ctx) true)]]
                                 :order-by [[:m.user_id :asc]]})
         ;; Distinct here: a scope is TEXT, which not every engine compares.
         (into [] (comp (map (fn [row] {:user-id    (tc/string->uuid (:user-id row))
                                        :permission permission
                                        :scope      (db->scope row)}))
                        (distinct)))))

  (ensure-tables [_]
    ;; What the migration runs, so an import in an application's migration
    ;; need not be ordered after the user library's. Only when needed: it
    ;; also scans user_sessions.
    (when-not (db/table-exists? ctx "user_group_grants")
      (persistence/initialize-user-schema! ctx))
    nil)

  (in-transaction [this f]
    (db/with-transaction [tx ctx]
      (f (assoc this :ctx tx)))))

(defn create-group-repository [ctx]
  (->DatabaseUserGroupRepository ctx))
