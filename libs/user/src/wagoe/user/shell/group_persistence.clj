(ns wagoe.user.shell.group-persistence
  "IUserGroupRepository on `user_groups`, `user_group_members`, `user_roles`
   and `user_group_roles`, which `initialize-user-schema!` creates, and the
   `user_group_grants` they replace. A scope and a role's permissions are
   stored as EDN text, so a uuid or keyword in them reads back as one."
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

(defn- with-expiry
  "`m` with the row's :expires-at, when it has one."
  [m row]
  (if-let [t (:expires-at row)] (assoc m :expires-at (tc/string->instant t)) m))

(defn- unexpired
  "Whether `column`, an expires_at, has not passed: an expired row counts for
   nothing from that moment, before `remove-expired` deletes it (BOU-856)."
  [column]
  [:or [:= column nil] [:> column (Instant/now)]])

(defn- db->group [row]
  (when row
    (cond-> (with-expiry {:id          (tc/string->uuid (:id row))
                          :name        (:name row)
                          :description (:description row)
                          :created-at  (tc/string->instant (:created-at row))
                          :updated-at  (tc/string->instant (:updated-at row))}
              row)
      (contains? row :member-count) (assoc :member-count (long (:member-count row)))
      (contains? row :role-count)   (assoc :role-count (long (:role-count row))))))

(defn- db->scope [row]
  (edn/read-string (str (:scope row))))

(defn- db->grant [row]
  {:id          (tc/string->uuid (:id row))
   :group-id    (tc/string->uuid (:group-id row))
   :permission  (keyword (:permission row))
   :scope       (db->scope row)
   :migrated-at (tc/string->instant (:migrated-at row))})

(defn- db->role [row]
  {:id          (tc/string->uuid (:id row))
   :name        (keyword (:name row))
   :description (:description row)
   :permissions (set (edn/read-string (str (:permissions row))))
   :created-at  (tc/string->instant (:created-at row))
   :updated-at  (tc/string->instant (:updated-at row))})

(defn- db->assignment [row]
  (cond-> (with-expiry {:id         (tc/string->uuid (:id row))
                        :group-id   (tc/string->uuid (:group-id row))
                        :role       (keyword (:role row))
                        :scope      (db->scope row)
                        :created-at (tc/string->instant (:created-at row))}
            row)
    (contains? row :group-name)     (assoc :group-name (:group-name row))
    (some? (:role-permissions row)) (assoc :permissions (set (edn/read-string (str (:role-permissions row)))))))

(defn- live-user [user-id]
  [:exists {:select [1] :from [:auth_users]
            :where  [:and [:= :id user-id] [:= :deleted_at nil]]}])

(defn- permissions-str [permissions]
  (pr-str (vec (sort permissions))))

(defn- member-count
  "The group's live members, as a subquery of `find-groups`."
  []
  {:select [[[:count :*]]]
   :from   [[:user_group_members :m]]
   :join   [[:auth_users :a] [:= :a.id :m.user_id]]
   :where  [:and [:= :m.group_id :g.id] [:= :a.deleted_at nil] (unexpired :m.expires_at)]})

(defn- role-count []
  {:select [[[:count :*]]]
   :from   [[:user_group_roles :t]]
   :where  [:and [:= :t.group_id :g.id] (unexpired :t.expires_at)]})

(defn- delete-expired!
  "Delete `rows` of `table` by id while still expired at `now`. Returns those
   this call deleted, so each is audited once: a re-add or another node may
   have removed one meanwhile."
  [ctx table rows now]
  (filterv #(pos? (db/execute-update! ctx {:delete-from table
                                           :where       [:and [:= :id (:id %)] [:<= :expires_at now]]}))
           rows))

(defn- unique-violation?
  "Whether `e` is the :conflict the execution layer makes of a unique key."
  [e]
  (= [:conflict :unique] ((juxt :type :constraint) (ex-data e))))

(defn- insert-member! [ctx group-id user-id expires-at]
  ;; An expired membership of the pair would refuse the row as a duplicate.
  (db/execute-update! ctx {:delete-from :user_group_members
                           :where       [:and [:= :group_id (id-str group-id)] [:= :user_id (id-str user-id)]
                                         [:<= :expires_at (Instant/now)]]})
  (try
    (db/execute-update! ctx {:insert-into :user_group_members
                             :values      [{:id         (tc/uuid->string (UUID/randomUUID))
                                            :group_id   (id-str group-id)
                                            :user_id    (id-str user-id)
                                            :created_at (Instant/now)
                                            :expires_at expires-at}]})
    true
    ;; Read nothing after: in a PostgreSQL transaction the refusal aborts it.
    (catch clojure.lang.ExceptionInfo e
      (cond
        (unique-violation? e) false
        ;; The group or user went meanwhile: a foreign key refused the row.
        (= :conflict (:type (ex-data e))) (throw (ex-info "Could not add the member" {:type :conflict} e))
        :else (throw e)))))

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
                                                       [(member-count) :member-count]
                                                       [(role-count) :role-count]]
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
        (db/execute-update! tx {:delete-from :user_group_roles :where [:= :group_id id]})
        (db/execute-update! tx {:delete-from :user_group_members :where [:= :group_id id]})
        (pos? (db/execute-update! tx {:delete-from :user_groups :where [:= :id id]})))))

  (find-members [_ group-id]
    (mapv (fn [row] (with-expiry {:id (tc/string->uuid (:id row)) :email (:email row) :name (:name row)} row))
          (db/execute-query! ctx {:select   [:a.id :a.email :u.name :m.expires_at]
                                  :from     [[:user_group_members :m]]
                                  :join     [[:auth_users :a] [:= :a.id :m.user_id]]
                                  :left-join [[:users :u] [:= :u.id :m.user_id]]
                                  :where    [:and [:= :m.group_id (id-str group-id)] [:= :a.deleted_at nil]
                                             (unexpired :m.expires_at)]
                                  :order-by [[:a.email :asc]]})))

  (insert-member [_ group-id user-id]
    (insert-member! ctx group-id user-id nil))

  (remove-member [_ group-id user-id]
    (pos? (db/execute-update! ctx {:delete-from :user_group_members
                                   :where       [:and [:= :group_id (id-str group-id)]
                                                 [:= :user_id (id-str user-id)]]})))

  (find-roles [_]
    (mapv db->role (db/execute-query! ctx {:select [:*] :from [:user_roles] :order-by [[:name :asc]]})))

  (insert-role [_ role]
    (let [stored (assoc (select-keys role [:name :description :permissions])
                        :id (UUID/randomUUID) :created-at (Instant/now) :updated-at nil)]
      (try (db/execute-update! ctx {:insert-into :user_roles
                                    :values      [{:id          (tc/uuid->string (:id stored))
                                                   :name        (group/permission-str (:name stored))
                                                   :description (:description stored)
                                                   :permissions (permissions-str (:permissions stored))
                                                   :created_at  (:created-at stored)}]})
           (catch clojure.lang.ExceptionInfo e
             (throw (if (unique-violation? e)
                      (ex-info "Another role has this name" {:type :conflict :field :name} e)
                      e))))
      stored))

  (modify-role [_ role]
    (pos? (db/execute-update! ctx {:update :user_roles
                                   :set    {:description (:description role)
                                            :permissions (permissions-str (:permissions role))
                                            :updated_at  (Instant/now)}
                                   :where  [:= :name (group/permission-str (:name role))]})))

  (remove-role [_ role-name]
    (let [role (group/permission-str role-name)]
      (db/with-transaction [tx ctx]
        (db/execute-update! tx {:delete-from :user_group_roles :where [:= :role role]})
        (pos? (db/execute-update! tx {:delete-from :user_roles :where [:= :name role]})))))

  (find-assignments [_ group-id]
    (mapv db->assignment (db/execute-query! ctx {:select   [:*]
                                                 :from     [:user_group_roles]
                                                 :where    [:and [:= :group_id (id-str group-id)]
                                                            (unexpired :expires_at)]
                                                 :order-by [[:created_at :asc] [:id :asc]]})))

  (find-role-assignments [_ role-name]
    (mapv db->assignment (db/execute-query! ctx {:select   [:a.* [:g.name :group-name]]
                                                 :from     [[:user_group_roles :a]]
                                                 :join     [[:user_groups :g] [:= :g.id :a.group_id]]
                                                 :where    [:and [:= :a.role (group/permission-str role-name)]
                                                            (unexpired :a.expires_at)]
                                                 :order-by [[:g.name :asc]]})))

  (insert-assignment [_ assignment]
    (let [stored (assoc (select-keys assignment [:group-id :role :scope :expires-at])
                        :id (or (:id assignment) (UUID/randomUUID)) :created-at (Instant/now))]
      (db/execute-update! ctx {:insert-into :user_group_roles
                               :values      [{:id         (tc/uuid->string (:id stored))
                                              :group_id   (id-str (:group-id stored))
                                              :role       (group/permission-str (:role stored))
                                              :scope      (pr-str (:scope stored))
                                              :created_at (:created-at stored)
                                              :expires_at (:expires-at stored)}]})
      stored))

  (remove-assignment [_ group-id assignment-id]
    (pos? (db/execute-update! ctx {:delete-from :user_group_roles
                                   :where       [:and [:= :id (id-str assignment-id)]
                                                 [:= :group_id (id-str group-id)]]})))

  (find-user-assignments [_ user-id group-names]
    ;; One query: the groups the user was added to, Everyone, and those an
    ;; identity provider names, with a managed role's permissions.
    (let [id (id-str user-id)]
      (mapv db->assignment
            (db/execute-query! ctx {:select    [:a.id :a.group_id :a.role :a.scope :a.created_at :a.expires_at
                                                [:g.name :group-name] [:r.permissions :role-permissions]]
                                    :from      [[:user_group_roles :a]]
                                    :join      [[:user_groups :g] [:= :g.id :a.group_id]]
                                    :left-join [[:user_roles :r] [:= :r.name :a.role]]
                                    :where     [:and (live-user id) (unexpired :a.expires_at)
                                                (cond-> [:or [:= :g.id (str group/everyone-id)]
                                                         [:in :g.id {:select [:group_id] :from [:user_group_members]
                                                                     :where  [:and [:= :user_id id]
                                                                              (unexpired :expires_at)]}]]
                                                  (seq group-names) (conj [:in :g.name (vec group-names)]))]
                                    :order-by  [[:g.name :asc] [:a.created_at :asc]]}))))

  (find-role-holders [_ role-names]
    (if (empty? role-names)
      []
      (let [active [:and [:= :a.active (protocols/boolean->db (:adapter ctx) true)] (unexpired :r.expires_at)]
            roles  [:in :r.role (mapv group/permission-str role-names)]]
        (mapv (fn [row] {:user-id  (tc/string->uuid (:user-id row))
                         :group-id (tc/string->uuid (:group-id row))
                         :role     (keyword (:role row))
                         :scope    (db->scope row)})
              (db/execute-query!
               ;; UNION ALL: a scope is TEXT, which not every engine compares;
               ;; permission-holders makes them distinct.
               ctx {:union-all [{:select [:m.user_id :r.group_id :r.role :r.scope]
                                 :from   [[:user_group_roles :r]]
                                 :join   [[:user_group_members :m] [:= :m.group_id :r.group_id]
                                          [:auth_users :a] [:= :a.id :m.user_id]]
                                 :where  [:and roles [:= :a.deleted_at nil] active (unexpired :m.expires_at)]}
                                ;; Everyone: every user, without a membership row.
                                {:select [[:a.id :user_id] :r.group_id :r.role :r.scope]
                                 :from   [[:user_group_roles :r]]
                                 :join   [[:auth_users :a] [:= :r.group_id (str group/everyone-id)]]
                                 :where  [:and roles [:= :a.deleted_at nil] active]}]})))))

  (ensure-everyone [_]
    (if (db/execute-one! ctx {:select [:id] :from [:user_groups] :where [:= :id (str group/everyone-id)]})
      false
      (let [now (Instant/now)]
        ;; An application's own group of that name steps aside, renamed.
        (when-let [{:keys [id]} (db/execute-one! ctx {:select [:id] :from [:user_groups]
                                                      :where  [:= :name group/everyone-name]})]
          (db/execute-update! ctx {:update :user_groups
                                   :set    {:name       (str group/everyone-name " (" (subs (str id) 0 8) ")")
                                            :updated_at now}
                                   :where  [:= :id id]}))
        (db/execute-update! ctx {:insert-into :user_groups
                                 :values      [{:id          (str group/everyone-id)
                                                :name        group/everyone-name
                                                :description "Every signed-in user"
                                                :created_at  now}]})
        true)))

  (find-legacy-grants [_]
    (mapv db->grant (db/execute-query! ctx {:select [:*] :from [:user_group_grants]
                                            :order-by [[:created_at :asc] [:id :asc]]})))

  (mark-legacy-grant-migrated [_ grant-id]
    (pos? (db/execute-update! ctx {:update :user_group_grants
                                   :set    {:migrated_at (Instant/now)}
                                   :where  [:= :id (id-str grant-id)]})))

  (find-legacy-viewers [_]
    (mapv (comp tc/string->uuid :id)
          (db/execute-query! ctx {:select [:u.id] :from [[:users :u]]
                                  :join   [[:auth_users :a] [:= :a.id :u.id]]
                                  :where  [:and [:= :u.role "viewer"] [:= :a.deleted_at nil]]})))

  (clear-legacy-roles [_]
    ;; "user" is how the column stores no role.
    (db/execute-update! ctx {:update :users :set {:role "user"} :where [:= :role "viewer"]}))

  (any-non-admin-users? [_]
    (some? (db/execute-one! ctx {:select [:u.id] :from [[:users :u]]
                                 :where  [:or [:= :u.role nil] [:<> :u.role "admin"]]
                                 :limit  1})))

  (find-user-groups [_ user-id]
    (mapv db->group (db/execute-query! ctx {:select   [:g.id :g.name :g.description :g.created_at :g.updated_at
                                                       :m.expires_at]
                                            :from     [[:user_groups :g]]
                                            :join     [[:user_group_members :m] [:= :m.group_id :g.id]
                                                       [:auth_users :a] [:= :a.id :m.user_id]]
                                            :where    [:and [:= :m.user_id (id-str user-id)] [:= :a.deleted_at nil]
                                                       (unexpired :m.expires_at)]
                                            :order-by [[:g.name :asc]]})))

  (ensure-tables [_]
    ;; What the migration runs, so an import in an application's migration
    ;; need not be ordered after the user library's. Only when needed: it
    ;; also scans user_sessions.
    (when-not (db/table-exists? ctx "user_group_roles")
      (persistence/initialize-user-schema! ctx))
    nil)

  (in-transaction [this f]
    (db/with-transaction [tx ctx]
      (f (assoc this :ctx tx))))

  ports/IAccessExpiry
  (insert-member-until [_ group-id user-id expires-at]
    (insert-member! ctx group-id user-id expires-at))

  (remove-expired [_ now]
    (let [expired [:<= :expires_at now]]
      {:members     (mapv (fn [row] (with-expiry {:group-id (tc/string->uuid (:group-id row))
                                                  :user-id  (tc/string->uuid (:user-id row))}
                                      row))
                          (delete-expired! ctx :user_group_members
                                           (db/execute-query! ctx {:select [:id :group_id :user_id :expires_at]
                                                                   :from   [:user_group_members]
                                                                   :where  expired})
                                           now))
       :assignments (mapv db->assignment
                          (delete-expired! ctx :user_group_roles
                                           (db/execute-query! ctx {:select [:*] :from [:user_group_roles]
                                                                   :where  expired})
                                           now))})))

(defn create-group-repository [ctx]
  (->DatabaseUserGroupRepository ctx))
