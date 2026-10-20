(ns wagoe.user.shell.group-service
  "IUserGroupService: groups, members, roles and assignments through one
   place, so each change is checked and audited. Reads go to the database
   every time."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.config :as config]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.user.shell.service :as user-service]))

;; =============================================================================
;; Settings
;; =============================================================================

(defn- config-error [message data]
  (ex-info message (merge {:type :configuration-error} data)))

(defn- schema-of
  "The Malli schema `x` is, or a symbol names. Throws :configuration-error."
  [k x]
  (let [v (if (symbol? x) (config/resolve-symbol x) x)]
    (try (m/schema v)
         (catch Exception e
           (throw (config-error (str ":wagoe/user :groups " k " is not a Malli schema: " (ex-message e))
                                {:key k}))))))

(defn prepare-settings
  "`:wagoe/user :groups` checked and resolved, for the service. Throws
   :configuration-error, so a bad declaration fails boot."
  [groups]
  (when-let [explanation (m/explain user-schema/GroupSettings (or groups {}))]
    (throw (config-error (str "Invalid :wagoe/user :groups: " (pr-str (me/humanize explanation)))
                         {:errors (me/humanize explanation)})))
  {:permissions  (some->> (:permissions groups) (schema-of :permissions))
   :scope-schema (some->> (:scope groups) (schema-of :scope))})

(defn declared-permissions
  "The `:permissions` enum of prepared `groups` settings, as a declaration for
   the permission registry. A schema that is not an enum lists nothing. Read
   until 2.0, when modules declare their own."
  [{:keys [permissions]}]
  (when (and permissions (= :enum (m/type permissions)))
    {:permissions (into {}
                        (comp (filter keyword?)
                              (map (fn [p] [p {:label (group-core/permission-str p) :source :groups-schema}])))
                        (m/children permissions))}))

(defn declared-scopes
  "The keys of a `:scope` map schema in prepared `groups` settings, as scope
   keys for the permission registry, `:wagoe/entity` read as `:entity`.
   Another schema declares none. Read until 2.0, like `:permissions`."
  [{:keys [scope-schema]}]
  (when (and scope-schema (= :map (m/type scope-schema)))
    {:scopes (into {}
                   (map (fn [[k props child]]
                          [k (cond-> {:label (name k) :schema child :source :groups-schema}
                               (:wagoe/entity props) (assoc :entity (:wagoe/entity props)))]))
                   (m/children scope-schema))}))

(defn with-registry-scopes
  "`settings` checking a grant's scope against the scope keys in `registry`
   (`wagoe.platform.core.scope/scope-schema`) when it declares any; else
   against the `:groups :scope` schema, or any map, as before."
  [settings registry]
  (let [scopes (some-> registry authz/scopes)]
    (cond-> settings
      (seq scopes) (assoc :scope-schema (m/schema (scope/scope-schema scopes))))))

;; =============================================================================
;; Service
;; =============================================================================

(defn- not-found [message data]
  (ex-info message (merge {:type :not-found} data)))

(defn- refused [message data]
  (ex-info message (merge {:type :validation-error} data)))

(defn- existing-group [group-repository group-id]
  (or (ports/find-group group-repository group-id)
      (throw (not-found "No such group" {:group-id group-id}))))

(defn- not-everyone!
  "Throw for `group-id` being Everyone, whose members and name are not kept."
  [group-id]
  (when (group-core/everyone? group-id)
    (throw (refused "Everyone is built in: every signed-in user is a member" {:field :group}))))

(defn- group-data
  "`group`'s name and description, trimmed and checked."
  [group]
  (let [data (cond-> {:name (str/trim (str (:name group)))}
               (some? (:description group))
               (assoc :description (not-empty (str/trim (str (:description group))))))]
    (when-let [explanation (m/explain user-schema/GroupRequest data)]
      (throw (ex-info "Invalid group" {:type   :validation-error
                                       :errors (me/humanize explanation)})))
    data))

(defn- code-roles [registry]
  (if registry (authz/roles registry) {}))

(defn- all-roles
  "Every role by name, managed ones from `repo` and code ones from `registry`,
   {role {:permissions #{..} :source ..}}. A code role shadows a managed one
   of its name."
  [repo registry]
  (merge (into {} (map (fn [r] [(:name r) (assoc r :source :managed)])) (ports/find-roles repo))
         (update-vals (code-roles registry) #(assoc % :source :code))))

(defn- group-grants [repo registry group-id]
  (group-core/user-grants (ports/find-assignments repo group-id) (all-roles repo registry)))

(defn- audit!
  "Record `action` for each of `members`, or, for a change to Everyone, once
   for the actor: it reaches every user. A failed write warns and does not
   undo the change, as for the user service's other entries, unless
   `strict?`: then it throws, rolling back the transaction it is in."
  ([audit-repository action group members grants]
   (audit! audit-repository action group members grants false))
  ([audit-repository action group members grants strict?]
   (let [ctx     (user-service/effective-audit-context {})
         members (if (group-core/everyone? (:id group))
                   (when (:actor-id ctx) [{:id (:actor-id ctx) :email (:actor-email ctx)}])
                   members)]
     (doseq [entry (audit-core/group-audit-entries action ctx group members grants)]
       (try (ports/create-audit-log audit-repository entry)
            (catch Exception e
              (when strict? (throw e))
              (log/warn e "Failed to write a group audit entry" {:action action :group-id (:id group)})))))))

(defn- checked-grant
  "`grant` as stored, its scope {} when it has none. Throws :validation-error
   when the declared schemas refuse it, carrying `data`."
  [settings grant data]
  (let [grant {:permission (:permission grant) :scope (or (:scope grant) {})}]
    (when-let [{:keys [error]} (group-core/grant-refusal grant settings)]
      (throw (ex-info (:message error) (merge error data))))
    grant))

(defn- checked-assignment
  "`assignment` as stored, its scope {} when it has none. Throws
   :validation-error naming an unknown role or a scope the schema refuses."
  [settings roles assignment data]
  (let [a {:role (:role assignment) :scope (or (:scope assignment) {})}]
    (when-let [{:keys [error]} (group-core/assignment-refusal a roles settings)]
      (throw (ex-info (:message error) (merge error data))))
    a))

(defn- legacy-role!
  "The managed role a grant of `permission` becomes, created when missing."
  [repo registry permission]
  (or (get (all-roles repo registry) permission)
      (ports/insert-role repo {:name        permission
                               :description "Migrated from a group grant"
                               :permissions #{permission}})))

(defn- user-id-of [user]
  (if (map? user) (:id user) user))

(defn- provider-groups
  "The group names the membership hook gives `user`, a map or an id."
  [membership user-repository user]
  (when membership
    (when-let [user (if (map? user) user (ports/find-user-by-id user-repository user))]
      (set (membership user)))))

(defn- find-role
  "The managed role named `role-name`, or nil."
  [repo role-name]
  (some #(when (= role-name (:name %)) %) (ports/find-roles repo)))

(defn- role-data
  "`role` trimmed and checked against the registry. Throws :validation-error."
  [registry role]
  (let [data (cond-> (select-keys role [:name :description :permissions])
               (string? (:name role)) (update :name group-core/parse-name)
               (sequential? (:permissions role)) (update :permissions set))]
    (when-let [explanation (m/explain user-schema/RoleRequest data)]
      (throw (refused "Invalid role" {:errors (me/humanize explanation)})))
    (when-let [{:keys [error]} (group-core/role-refusal data (code-roles registry)
                                                        (if registry (authz/permissions registry) any?))]
      (throw (ex-info (:message error) error)))
    data))

(defn- role-holders
  "The users who hold the managed role `role-name` through a stored group, and
   the groups that hold it, for auditing a change to it."
  [repo role-name]
  (for [a (ports/find-role-assignments repo role-name)]
    {:group   {:id (:group-id a) :name (:group-name a)}
     :scope   (:scope a)
     :members (when-not (group-core/everyone? (:group-id a)) (ports/find-members repo (:group-id a)))}))

(defn- audit-role-change!
  "Audit what changing managed role `role-name` from `before` to `after`
   permissions does to every group holding it."
  [repo audit-repository role-name before after]
  (doseq [{:keys [group scope members]} (role-holders repo role-name)
          [action ps] [[:grant-remove (remove (set after) before)] [:grant-add (remove (set before) after)]]
          :when (seq ps)]
    (audit! audit-repository action group members
            (for [p (sort ps)] {:permission p :scope scope :role role-name}))))

;; =============================================================================
;; Import
;; =============================================================================

(defn- import-spec
  "`spec` checked and trimmed: its roles against `roles` and `settings`, its
   grants turned into roles, its members looked up. Throws :validation-error
   naming the group."
  [user-repository settings roles spec]
  (when-let [explanation (m/explain user-schema/GroupImport spec)]
    (throw (refused "Invalid group import" {:group  (:name spec)
                                            :errors (me/humanize explanation)})))
  (let [data   (group-data spec)
        grants (mapv #(checked-grant settings % {:group (:name data)}) (:grants spec))
        ;; A grant's role is made in the transaction, if it is missing.
        roles  (into roles (map (fn [g] [(:permission g) {:permissions #{(:permission g)}}])) grants)]
    (assoc data
           :grants  grants
           :roles   (into (mapv #(checked-assignment settings roles % {:group (:name data)}) (:roles spec))
                          (map group-core/grant->assignment)
                          grants)
           :members (mapv #(or (ports/find-user-by-id user-repository %)
                               (throw (refused "No such user" {:field :members :group (:name data) :user-id %})))
                          (distinct (:members spec))))))

(defn- import-group!
  "Upsert `spec` through `repo`, auditing each user whose rights change in
   the order the single changes would: leaving, roles removed, roles added,
   joining. Audit failures throw, so the import rolls back."
  [repo registry audit-repository existing {:keys [members roles grants] :as spec} replace?]
  (when (and existing (seq members))
    (not-everyone! (:id existing)))
  (doseq [{:keys [permission]} grants] (legacy-role! repo registry permission))
  (let [data      (select-keys spec [:name :description])
        group     (cond
                    (nil? existing) (ports/insert-group repo data)
                    (and (contains? data :description) (not= (:description data) (:description existing)))
                    (ports/modify-group repo (assoc data :id (:id existing)))
                    ;; find-groups' counts are from before the import.
                    :else (dissoc existing :member-count :role-count))
        known     (all-roles repo registry)
        grants-of #(group-core/user-grants % known)
        held      (ports/find-assignments repo (:id group))
        {:keys [join leave stay add remove]}
        (group-core/import-changes {:members (ports/find-members repo (:id group)) :held held}
                                   {:members members :roles roles}
                                   replace?)]
    (doseq [user leave] (ports/remove-member repo (:id group) (:id user)))
    (doseq [a remove] (ports/remove-assignment repo (:id group) (:id a)))
    (let [added (mapv #(ports/insert-assignment repo (assoc % :group-id (:id group))) add)]
      (doseq [user join]
        ;; False is a concurrent import adding them first. Stop: on PostgreSQL
        ;; the refused row has aborted the transaction.
        (when-not (ports/insert-member repo (:id group) (:id user))
          (throw (ex-info "The group changed during the import; run it again"
                          {:type :conflict :group (:name group)}))))
      (when (seq leave) (audit! audit-repository :group-leave group leave (grants-of held) true))
      (when (seq remove) (audit! audit-repository :grant-remove group stay (grants-of remove) true))
      (when (seq added) (audit! audit-repository :grant-add group stay (grants-of added) true))
      (when (seq join) (audit! audit-repository :group-join group join
                               (grants-of (ports/find-assignments repo (:id group))) true))
      {:group         group
       :created?      (nil? existing)
       :joined        (mapv :id join)
       :left          (mapv :id leave)
       :roles-added   added
       :roles-removed remove})))

;; =============================================================================
;; Migration
;; =============================================================================

(defn- assign-once! [repo group-id role scope]
  (when-not (some #(= [role scope] (group-core/assignment-key %)) (ports/find-assignments repo group-id))
    (ports/insert-assignment repo {:group-id group-id :role role :scope scope})))

(defn- migrate-grants!
  "Each grant becomes an assignment of the managed role of its permission."
  [repo registry]
  (doseq [{:keys [id group-id] :as grant} (ports/find-legacy-grants repo)
          :let [{:keys [role scope]} (group-core/grant->assignment grant)]]
    (legacy-role! repo registry role)
    (assign-once! repo group-id role scope)
    (ports/remove-legacy-grant repo id)))

(defn- migrate-viewers!
  "Users still stored as viewers join Readers, which holds every module's
   viewer role, and keep no role."
  [repo registry]
  (when-let [viewers (seq (ports/find-legacy-viewers repo))]
    (let [readers (or (some #(when (= "Readers" (:name %)) %) (ports/find-groups repo))
                      (ports/insert-group repo {:name "Readers" :description "Read-only users"}))]
      (doseq [id viewers] (ports/insert-member repo (:id readers) id))
      (doseq [role (group-core/module-roles (code-roles registry) "viewer")]
        (assign-once! repo (:id readers) role {}))
      (ports/clear-legacy-roles repo))))

(defn- assert-sources!
  "Throw :configuration-error when a stored assignment takes a scope value from
   a `:user/<name>` source no module registers: it would give no rights."
  [repo registry]
  (let [known   (authz/scope-sources registry)
        unknown (into (sorted-set)
                      (for [g (ports/find-groups repo)
                            a (ports/find-assignments repo (:id g))
                            v (vals (:scope a))
                            :when (and (scope/source? v) (not (contains? known v)))]
                        v))]
    (when (seq unknown)
      (throw (ex-info (str "Group roles take scope values from " (str/join ", " unknown)
                           ", which no module registers under :scope-sources")
                      {:type :configuration-error :sources (vec unknown)})))))

(defn migrate!
  "ADR-041's migration: under :enforce nobody may do less than under :off, and
   nobody becomes an admin. Idempotent; runs at boot.

   The first time, Everyone is created and, when there are users, given every
   module's editor role. Viewers join Readers. Group grants become
   assignments of a managed role named after their permission."
  [{:keys [group-repository registry]}]
  (ports/ensure-tables group-repository)
  (try
    (ports/in-transaction group-repository
                          (fn [repo]
                            (when (and (ports/ensure-everyone repo) (ports/any-users? repo))
                              (doseq [role (group-core/module-roles (code-roles registry) "editor")]
                                (assign-once! repo group-core/everyone-id role {})))
                            (migrate-viewers! repo registry)
                            (migrate-grants! repo registry)))
    (catch clojure.lang.ExceptionInfo e
      ;; Another node created Everyone first and migrated.
      (if (= [:conflict :unique] ((juxt :type :constraint) (ex-data e)))
        (log/info "Roles were migrated by another node")
        (throw e))))
  (when registry
    (when-let [legacy (seq (sort (remove (authz/permissions registry)
                                         (mapcat :permissions (ports/find-roles group-repository)))))]
      (log/warn "Managed roles hold permissions no module declares; they still count" {:permissions (vec legacy)}))
    (assert-sources! group-repository registry)))

;; =============================================================================
;; Service
;; =============================================================================

(defrecord UserGroupService [group-repository user-repository audit-repository settings registry membership]
  ports/IUserGroupService

  (list-groups [_]
    (ports/find-groups group-repository))

  (get-group [_ group-id]
    (when-let [group (ports/find-group group-repository group-id)]
      (assoc group
             :everyone? (group-core/everyone? group-id)
             :members (if (group-core/everyone? group-id) [] (ports/find-members group-repository group-id))
             :roles   (ports/find-assignments group-repository group-id))))

  (create-group [_ group]
    (ports/insert-group group-repository (group-data group)))

  (update-group [_ group]
    (not-everyone! (:id group))
    (or (ports/modify-group group-repository (assoc (group-data group) :id (:id group)))
        (throw (not-found "No such group" {:group-id (:id group)}))))

  (delete-group [_ group-id]
    (not-everyone! group-id)
    (if-let [group (ports/find-group group-repository group-id)]
      (let [members (ports/find-members group-repository group-id)
            grants  (when (seq members) (group-grants group-repository registry group-id))
            removed (ports/remove-group group-repository group-id)]
        (when (and removed (seq members))
          (audit! audit-repository :group-leave group members grants))
        removed)
      false))

  (add-group-member [_ group-id user-id]
    (not-everyone! group-id)
    (let [group (existing-group group-repository group-id)
          user  (or (ports/find-user-by-id user-repository user-id)
                    (throw (not-found "No such user" {:user-id user-id})))
          added (ports/insert-member group-repository group-id (:id user))]
      (when added
        (audit! audit-repository :group-join group [user] (group-grants group-repository registry group-id)))
      added))

  (remove-group-member [_ group-id user-id]
    (let [removed (ports/remove-member group-repository group-id user-id)]
      ;; A soft-deleted user holds nothing, so leaving changes nothing to audit.
      (when-let [user (and removed (ports/find-user-by-id user-repository user-id))]
        (audit! audit-repository :group-leave (ports/find-group group-repository group-id) [user]
                (group-grants group-repository registry group-id)))
      removed))

  (list-roles [_]
    (->> (all-roles group-repository registry)
         (map (fn [[k r]] (assoc (select-keys r [:description :permissions :source :module :id]) :name k)))
         (sort-by (comp str :name))
         vec))

  (create-role [_ role]
    (ports/insert-role group-repository (role-data registry role)))

  (update-role [_ role]
    (let [data   (role-data registry role)
          before (find-role group-repository (:name data))]
      (when-not before
        (throw (not-found "No such role" {:role (:name data)})))
      (ports/modify-role group-repository data)
      (audit-role-change! group-repository audit-repository (:name data) (:permissions before) (:permissions data))
      (assoc before :description (:description data) :permissions (:permissions data))))

  (delete-role [_ role-name]
    (if-let [role (find-role group-repository role-name)]
      (do (audit-role-change! group-repository audit-repository role-name (:permissions role) #{})
          (ports/remove-role group-repository role-name))
      false))

  (add-group-role [_ group-id assignment]
    (let [group  (existing-group group-repository group-id)
          roles  (all-roles group-repository registry)
          a      (checked-assignment settings roles assignment nil)
          stored (ports/insert-assignment group-repository (assoc a :group-id group-id))]
      (audit! audit-repository :grant-add group (ports/find-members group-repository group-id)
              (group-core/user-grants [stored] roles))
      stored))

  (remove-group-role [_ group-id assignment-id]
    (let [group (ports/find-group group-repository group-id)
          a     (when group
                  (some #(when (= (str assignment-id) (str (:id %))) %)
                        (ports/find-assignments group-repository group-id)))
          grants  (when a (group-core/user-grants [a] (all-roles group-repository registry)))
          removed (boolean (and a (ports/remove-assignment group-repository group-id (:id a))))]
      (when removed
        (audit! audit-repository :grant-remove group (ports/find-members group-repository group-id) grants))
      removed))

  (add-group-grant [this group-id grant]
    (existing-group group-repository group-id)
    (let [{:keys [permission scope]} (checked-grant settings grant nil)]
      (legacy-role! group-repository registry permission)
      (let [a (ports/add-group-role this group-id {:role permission :scope scope})]
        (assoc a :permission permission))))

  (remove-group-grant [this group-id grant-id]
    (ports/remove-group-role this group-id grant-id))

  (user-grants [_ user]
    (if-let [id (user-id-of user)]
      ;; One query: the assignments carry their managed role's permissions.
      (let [assignments (ports/find-user-assignments group-repository id
                                                     (provider-groups membership user-repository user))]
        (group-core/user-grants assignments
                                (merge (into {} (keep (fn [{:keys [role permissions]}]
                                                        (when permissions [role {:permissions permissions}])))
                                             assignments)
                                       (code-roles registry))))
      []))

  (user-groups [_ user]
    ;; None for an unknown or soft-deleted user, as find-user-groups gives.
    (if-let [user (if (map? user) user (some->> user (ports/find-user-by-id user-repository)))]
      (let [stored   (ports/find-user-groups group-repository (:id user))
            provided (provider-groups membership user-repository user)
            ids      (set (map :id stored))]
        (into (mapv #(assoc % :source :member) stored)
              (for [g (ports/find-groups group-repository)
                    :when (and (not (ids (:id g)))
                               (or (group-core/everyone? (:id g)) (contains? provided (:name g))))]
                (assoc (dissoc g :member-count :role-count)
                       :source (if (group-core/everyone? (:id g)) :everyone :provider)))))
      []))

  (permission-holders [_ permission]
    ;; A string would lose its first character to permission-str and match nobody.
    (when-not (keyword? permission)
      (throw (ex-info "A permission is a keyword" {:type :validation-error :field :permission})))
    (let [roles (group-core/roles-holding (all-roles group-repository registry) permission)]
      (into [] (comp (map #(-> % (dissoc :role) (assoc :permission permission))) (distinct))
            (ports/find-role-holders group-repository roles))))

  (import-groups! [_ groups {:keys [replace?]}]
    (ports/ensure-tables group-repository)
    (let [roles (all-roles group-repository registry)
          specs (mapv #(import-spec user-repository settings roles %) groups)]
      (when-let [twice (first (for [[n xs] (group-by :name specs) :when (next xs)] n))]
        (throw (refused "A group is listed twice" {:group twice})))
      (ports/in-transaction group-repository
                            (fn [repo]
                              (let [by-name (group-by :name (ports/find-groups repo))]
                                (mapv (fn [{:keys [name] :as spec}]
                                        (let [[existing more] (by-name name)]
                                          (when more
                                            (throw (ex-info "Several groups have this name" {:type :conflict :group name})))
                                          (import-group! repo registry audit-repository existing spec replace?)))
                                      specs)))))))

(defn create-group-service
  "A group service. `settings` is what `prepare-settings` returns. `opts`:
   `:registry`, the permission registry its code roles come from, and
   `:membership`, the hook `(fn [user] #{group-name …})` that adds groups an
   identity provider names."
  ([group-repository user-repository audit-repository settings]
   (create-group-service group-repository user-repository audit-repository settings {}))
  ([group-repository user-repository audit-repository settings {:keys [registry membership]}]
   (->UserGroupService group-repository user-repository audit-repository settings registry membership)))
