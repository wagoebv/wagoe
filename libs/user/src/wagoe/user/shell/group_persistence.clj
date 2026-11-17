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

(def ^:dynamic *tenant*
  "The tenant the repository works in, as {:id t}; unbound, no tenant's groups
   and roles. The group pages bind the caller's tenant, a user's grants the
   request's (BOU-996). Bind it through `group-service/with-tenant`."
  nil)

(defn- tenant-cond
  "`col` kept to the bound tenant, nil meaning no tenant."
  [col]
  (if-let [id (:id *tenant*)] [:= col (id-str id)] [:is col nil]))

(defn- and-tenant [where col]
  [:and where (tenant-cond col)])

(defn- tenant-groups
  "The ids of the bound tenant's groups, as a subquery."
  []
  {:select [:id] :from [:user_groups] :where (tenant-cond :tenant_id)})

(defn- in-tenant!
  "Throw :not-found unless group `group-id` is the bound tenant's."
  [ctx group-id]
  (when-not (db/execute-one! ctx {:select [:id] :from [:user_groups] :where (and-tenant [:= :id (id-str group-id)] :tenant_id)})
    (throw (ex-info "No such group" {:type :not-found :group-id group-id}))))

(defn- tenant-value []
  (some-> *tenant* :id id-str))

(defn- everyone []
  (str (group/everyone-id-of (some-> *tenant* :id))))

(defn- everyone-cond
  "`col` naming the Everyone that counts in the bound tenant: its own, else,
   until it has one, no tenant's, which stays its template (BOU-996)."
  [col]
  (let [own (everyone) none (str group/everyone-id)]
    (if (= own none)
      [:= col none]
      [:or [:= col own]
       [:and [:= col none] [:not [:exists {:select [1] :from [:user_groups] :where [:= :id own]}]]]])))

(def ^:private role-in-groups-tenant
  "An assignment's role, managed in its group's tenant: no tenant's for a group of none."
  [:or [:= :r.tenant_id :g.tenant_id] [:and [:= :r.tenant_id nil] [:= :g.tenant_id nil]]])

(defn- with-expiry
  "`m` with the row's :expires-at, when it has one."
  [m row]
  (if-let [t (:expires-at row)] (assoc m :expires-at (tc/string->instant t)) m))

(defn- current
  "`table`, user_group_members or user_group_roles, less its expired rows, as
   a FROM source: an expired row counts for nothing from that moment, before
   `remove-expired` deletes it (BOU-856). Every read goes through it."
  [table]
  {:select [:*] :from [table] :where [:or [:= :expires_at nil] [:> :expires_at (Instant/now)]]})

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
   :from   [[(current :user_group_members) :m]]
   :join   [[:auth_users :a] [:= :a.id :m.user_id]]
   :where  [:and [:= :m.group_id :g.id] [:= :a.deleted_at nil]]})

(defn- role-count []
  {:select [[[:count :*]]]
   :from   [[(current :user_group_roles) :t]]
   :where  [:= :t.group_id :g.id]})

(defn- delete-expired!
  "Delete `rows` of `table` by id while still expired at `now`. Returns those
   this call deleted, so each is audited once: a re-add or another node may
   have removed one meanwhile."
  [ctx table rows now]
  (filterv #(pos? (db/execute-update! ctx {:delete-from table
                                           :where       [:and [:= :id (id-str (:id %))] [:<= :expires_at now]]}))
           rows))

(defn- expired-rows [ctx now]
  (let [expired [:<= :expires_at now]]
    {:members     (mapv (fn [row] (with-expiry {:id       (tc/string->uuid (:id row))
                                                :group-id (tc/string->uuid (:group-id row))
                                                :user-id  (tc/string->uuid (:user-id row))}
                                    row))
                        (db/execute-query! ctx {:select [:id :group_id :user_id :expires_at]
                                                :from   [:user_group_members]
                                                :where  expired}))
     :assignments (mapv db->assignment (db/execute-query! ctx {:select [:*] :from [:user_group_roles]
                                                               :where  expired}))}))

(defn- remove-expired-rows! [ctx {:keys [members assignments]} now]
  {:members     (delete-expired! ctx :user_group_members members now)
   :assignments (delete-expired! ctx :user_group_roles assignments now)})

(defn- unique-violation?
  "Whether `e` is the :conflict the execution layer makes of a unique key."
  [e]
  (= [:conflict :unique] ((juxt :type :constraint) (ex-data e))))

(defn- insert-member! [ctx group-id user-id expires-at]
  (in-tenant! ctx group-id)
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
                                     :where  (and-tenant [:= :id (id-str group-id)] :tenant_id)})))

  (find-groups [_]
    (mapv db->group (db/execute-query! ctx {:select   [:g.id :g.name :g.description :g.created_at :g.updated_at
                                                       [(member-count) :member-count]
                                                       [(role-count) :role-count]]
                                            :from     [[:user_groups :g]]
                                            :where    (tenant-cond :g.tenant_id)
                                            :order-by [[:g.name :asc]]})))

  (insert-group [_ group]
    (let [stored (assoc (select-keys group [:name :description])
                        :id (UUID/randomUUID) :created-at (Instant/now) :updated-at nil)]
      (name-taken! #(db/execute-update! ctx {:insert-into :user_groups
                                             :values      [{:id          (tc/uuid->string (:id stored))
                                                            :tenant_id   (tenant-value)
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
                                                       :where  (and-tenant [:= :id (id-str (:id group))] :tenant_id)})))
      (ports/find-group this (:id group))))

  (remove-group [_ group-id]
    ;; Explicit, where the keys' cascade would do: SQLite enforces keys only
    ;; on connections that ask.
    (let [id (id-str group-id)]
      ;; Another tenant's group is as missing: nothing of it goes
      (when (db/execute-one! ctx {:select [:id] :from [:user_groups] :where (and-tenant [:= :id id] :tenant_id)})
        (db/with-transaction [tx ctx]
          (db/execute-update! tx {:delete-from :user_group_grants :where [:= :group_id id]})
          (db/execute-update! tx {:delete-from :user_group_roles :where [:= :group_id id]})
          (db/execute-update! tx {:delete-from :user_group_members :where [:= :group_id id]})
          (pos? (db/execute-update! tx {:delete-from :user_groups :where [:= :id id]}))))))

  (find-members [_ group-id]
    (mapv (fn [row] (with-expiry {:id (tc/string->uuid (:id row)) :email (:email row) :name (:name row)
                                  :active? (protocols/db->boolean (:adapter ctx) (:active row))}
                      row))
          (db/execute-query! ctx {:select   [:a.id :a.email :u.name :a.active :m.expires_at]
                                  :from     [[(current :user_group_members) :m]]
                                  :join     [[:auth_users :a] [:= :a.id :m.user_id]]
                                  :left-join [[:users :u] [:= :u.id :m.user_id]]
                                  :where    [:and [:= :m.group_id (id-str group-id)] [:= :a.deleted_at nil]
                                             [:in :m.group_id (tenant-groups)]]
                                  :order-by [[:a.email :asc]]})))

  (insert-member [_ group-id user-id]
    (insert-member! ctx group-id user-id nil))

  (remove-member [_ group-id user-id]
    ;; An expired row is left to remove-expired, which audits it as the leave
    (pos? (db/execute-update! ctx {:delete-from :user_group_members
                                   :where       [:and [:= :group_id (id-str group-id)]
                                                 [:= :user_id (id-str user-id)]
                                                 [:in :group_id (tenant-groups)]
                                                 [:or [:= :expires_at nil] [:> :expires_at (Instant/now)]]]})))

  (find-roles [_]
    (mapv db->role (db/execute-query! ctx {:select [:*] :from [:user_roles] :where (tenant-cond :tenant_id)
                                           :order-by [[:name :asc]]})))

  (insert-role [_ role]
    (let [stored (assoc (select-keys role [:name :description :permissions])
                        :id (UUID/randomUUID) :created-at (Instant/now) :updated-at nil)]
      (try (db/execute-update! ctx {:insert-into :user_roles
                                    :values      [{:id          (tc/uuid->string (:id stored))
                                                   :tenant_id   (tenant-value)
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
                                   :where  (and-tenant [:= :name (group/permission-str (:name role))] :tenant_id)})))

  (remove-role [_ role-name]
    (let [role (group/permission-str role-name)]
      (db/with-transaction [tx ctx]
        ;; Its assignments in the bound tenant's groups only: another tenant's
        ;; role of the name is another role
        (db/execute-update! tx {:delete-from :user_group_roles
                                :where       [:and [:= :role role] [:in :group_id (tenant-groups)]]})
        (pos? (db/execute-update! tx {:delete-from :user_roles :where (and-tenant [:= :name role] :tenant_id)})))))

  (find-assignments [_ group-id]
    (mapv db->assignment (db/execute-query! ctx {:select   [:*]
                                                 :from     [[(current :user_group_roles) :a]]
                                                 :where    [:and [:= :a.group_id (id-str group-id)]
                                                            [:in :a.group_id (tenant-groups)]]
                                                 :order-by [[:created_at :asc] [:id :asc]]})))

  (find-role-assignments [_ role-name]
    (mapv db->assignment (db/execute-query! ctx {:select   [:a.* [:g.name :group-name]]
                                                 :from     [[(current :user_group_roles) :a]]
                                                 :join     [[:user_groups :g] [:= :g.id :a.group_id]]
                                                 :where    (and-tenant [:= :a.role (group/permission-str role-name)] :g.tenant_id)
                                                 :order-by [[:g.name :asc]]})))

  (insert-assignment [_ assignment]
    (in-tenant! ctx (:group-id assignment))
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
                                                 [:= :group_id (id-str group-id)]
                                                 [:in :group_id (tenant-groups)]]})))

  (find-user-assignments [_ user-id group-names]
    ;; One query: the groups the user was added to, Everyone, and those an
    ;; identity provider names, with a managed role's permissions.
    (let [id (id-str user-id)]
      (mapv db->assignment
            (db/execute-query! ctx {:select    [:a.id :a.group_id :a.role :a.scope :a.created_at :a.expires_at
                                                [:g.name :group-name] [:r.permissions :role-permissions]]
                                    :from      [[(current :user_group_roles) :a]]
                                    :join      [[:user_groups :g] [:= :g.id :a.group_id]]
                                    :left-join [[:user_roles :r] [:and [:= :r.name :a.role] role-in-groups-tenant]]
                                    :where     [:and (live-user id)
                                                [:or (everyone-cond :g.id)
                                                 (and-tenant [:in :g.id {:select [:m.group_id]
                                                                         :from   [[(current :user_group_members) :m]]
                                                                         :where  [:= :m.user_id id]}]
                                                             :g.tenant_id)
                                                 ;; A provider's group: the tenant's of that name, else no
                                                 ;; tenant's, as before tenants had groups (BOU-996)
                                                 (when (seq group-names)
                                                   [:and [:in :g.name (vec group-names)]
                                                    [:or (tenant-cond :g.tenant_id)
                                                     [:and [:= :g.tenant_id nil]
                                                      [:not [:exists {:select [1] :from [[:user_groups :t]]
                                                                      :where  [:and [:= :t.name :g.name] (tenant-cond :t.tenant_id)]}]]]]])]]
                                    :order-by  [[:g.name :asc] [:a.created_at :asc]]}))))

  (find-role-holders [_ role-names]
    (if (empty? role-names)
      []
      (let [active [:= :a.active (protocols/boolean->db (:adapter ctx) true)]
            roles  [:in :r.role (mapv group/permission-str role-names)]]
        (mapv (fn [row] {:user-id  (tc/string->uuid (:user-id row))
                         :group-id (tc/string->uuid (:group-id row))
                         :role     (keyword (:role row))
                         :scope    (db->scope row)})
              (db/execute-query!
               ;; UNION ALL: a scope is TEXT, which not every engine compares;
               ;; permission-holders makes them distinct.
               ctx {:union-all [{:select [:m.user_id :r.group_id :r.role :r.scope]
                                 :from   [[(current :user_group_roles) :r]]
                                 :join   [[(current :user_group_members) :m] [:= :m.group_id :r.group_id]
                                          [:auth_users :a] [:= :a.id :m.user_id]
                                          [:user_groups :g] [:= :g.id :r.group_id]]
                                 :where  (and-tenant [:and roles [:= :a.deleted_at nil] active] :g.tenant_id)}
                                ;; Everyone: every user of its tenant, without a membership row.
                                {:select [[:a.id :user_id] :r.group_id :r.role :r.scope]
                                 :from   [[(current :user_group_roles) :r]]
                                 :join   [[:auth_users :a] (everyone-cond :r.group_id)]
                                 :where  (and-tenant [:and roles [:= :a.deleted_at nil] active] :a.tenant_id)}]})))))

  (ensure-everyone [_]
    (if (db/execute-one! ctx {:select [:id] :from [:user_groups] :where [:= :id (everyone)]})
      false
      (let [now (Instant/now)]
        ;; An application's own group of that name steps aside, renamed.
        (when-let [{:keys [id]} (db/execute-one! ctx {:select [:id] :from [:user_groups]
                                                      :where  (and-tenant [:= :name group/everyone-name] :tenant_id)})]
          (db/execute-update! ctx {:update :user_groups
                                   :set    {:name       (str group/everyone-name " (" (subs (str id) 0 8) ")")
                                            :updated_at now}
                                   :where  [:= :id id]}))
        (db/execute-update! ctx {:insert-into :user_groups
                                 :values      [{:id          (everyone)
                                                :tenant_id   (tenant-value)
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
                                            :join     [[(current :user_group_members) :m] [:= :m.group_id :g.id]
                                                       [:auth_users :a] [:= :a.id :m.user_id]]
                                            :where    (and-tenant [:and [:= :m.user_id (id-str user-id)] [:= :a.deleted_at nil]]
                                                                  :g.tenant_id)
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

  ports/ITenantGroups
  (groups-with-tenant-members [_]
    (mapv (fn [row] {:group-id (tc/string->uuid (:group-id row)) :tenant-id (tc/string->uuid (:tenant-id row))})
          (db/execute-query! ctx {:select-distinct [[:g.id :group-id] [:a.tenant_id :tenant-id]]
                                  :from            [[:user_groups :g]]
                                  :join            [[:user_group_members :m] [:= :m.group_id :g.id]
                                                    [:auth_users :a] [:= :a.id :m.user_id]]
                                  :where           [:and [:= :g.tenant_id nil] [:not= :a.tenant_id nil]
                                                    [:<> :g.id (str group/everyone-id)]]})))

  (group-tenant [_ group-id]
    (some-> (db/execute-one! ctx {:select [:tenant_id] :from [:user_groups] :where [:= :id (id-str group-id)]})
            :tenant-id tc/string->uuid))

  (move-tenant-members [_ from-group-id to-group-id tenant-id]
    ;; One already in the copy keeps that membership; the old one goes
    (db/execute-update! ctx {:delete-from :user_group_members
                             :where       [:and [:= :group_id (id-str from-group-id)]
                                           [:in :user_id {:select [:user_id] :from [:user_group_members]
                                                          :where  [:= :group_id (id-str to-group-id)]}]]})
    (db/execute-update! ctx {:update :user_group_members
                             :set    {:group_id (id-str to-group-id)}
                             :where  [:and [:= :group_id (id-str from-group-id)]
                                      [:in :user_id {:select [:id] :from [:auth_users]
                                                     :where  [:= :tenant_id (id-str tenant-id)]}]]}))

  ports/IAllAssignments
  (find-all-assignments [_]
    (mapv db->assignment (db/execute-query! ctx {:select   [:a.* [:g.name :group-name]]
                                                 :from     [[(current :user_group_roles) :a]]
                                                 :join     [[:user_groups :g] [:= :g.id :a.group_id]]
                                                 :where    (tenant-cond :g.tenant_id)})))

  ports/IAccessExpiry
  (insert-member-until [_ group-id user-id expires-at]
    (insert-member! ctx group-id user-id expires-at))

  (remove-expired [_ now]
    (-> (remove-expired-rows! ctx (expired-rows ctx now) now)
        (update :members (partial mapv #(dissoc % :id)))))

  ports/IMembershipEnd
  (end-member-at [_ group-id user-id expires-at]
    (pos? (db/execute-update! ctx {:update :user_group_members
                                   :set    {:expires_at expires-at}
                                   :where  [:and [:= :group_id (id-str group-id)]
                                            [:= :user_id (id-str user-id)]
                                            [:in :group_id (tenant-groups)]
                                            [:or [:= :expires_at nil] [:> :expires_at expires-at]]]})))

  ports/IExpiredAccess
  (find-expired [_ now]
    (expired-rows ctx now))

  (remove-expired-rows [_ expired now]
    (remove-expired-rows! ctx expired now)))

(defn create-group-repository [ctx]
  (->DatabaseUserGroupRepository ctx))
