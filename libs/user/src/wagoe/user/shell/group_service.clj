(ns wagoe.user.shell.group-service
  "IUserGroupService: groups, members, roles and assignments through one
   place, so each change is checked and audited. Reads go to the database
   every time."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.config :as config]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.core.grant :as grant-core]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.user.shell.service :as user-service])
  (:import (java.time Instant)))

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
                          [k (cond-> {:label (group-core/scope-key-label k props) :schema child :source :groups-schema}
                               (:wagoe/entity props) (assoc :entity (:wagoe/entity props)))]))
                   (m/children scope-schema))}))

(defn with-registry-permissions
  "`settings` checking a grant's permission against `registry` when
   `:groups :permissions` declares none. Without either, any keyword passes."
  [settings registry]
  (cond-> settings
    (and registry (nil? (:permissions settings)))
    (assoc :permissions (m/schema [:fn {:error/message "should be a permission a module declares"}
                                   #(authz/permission? registry %)]))))

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

(defn- roles-of
  "Every role, as `all-roles`, or the snapshot `with-roles` took."
  [svc]
  (or (::roles svc) (all-roles (:group-repository svc) (:registry svc))))

(defn with-roles
  "`svc` reading every role once, for a page of previews and grant options
   that changes nothing. A group service of another kind is returned as it is."
  [svc]
  (if (:group-repository svc) (assoc svc ::roles (roles-of svc)) svc))

(defn- audit!
  "Record `action` for each of `members`, or, for a change to Everyone, once,
   its target the Everyone group: it reaches every user (BOU-862). `opts`: `:diffs`, as `user-diffs`
   gives them, add what the change did to each member's effective rights; for
   Everyone, counted per permission; `:metadata` adds to each entry's. A failed write warns and does not undo the
   change, as for the user service's other entries, unless `:strict?`: then it
   throws, rolling back the transaction it is in."
  ([audit-repository action group members grants]
   (audit! audit-repository action group members grants {}))
  ([audit-repository action group members grants {:keys [diffs strict? metadata]}]
   (let [ctx       (user-service/effective-audit-context {})
         everyone? (group-core/everyone? (:id group))
         ;; Everyone's entry is filed under the group, where every user's rights timeline reads it
         members   (if everyone?
                     [{:id group-core/everyone-id :email group-core/everyone-name}]
                     members)
         effective (if everyone?
                     (when (seq diffs)
                       {(str group-core/everyone-id) (group-core/permission-counts diffs)})
                     (into {} (keep (fn [d] (when-let [id (get-in d [:user :id])] [(str id) d]))) diffs))]
     (doseq [entry (audit-core/group-audit-entries action ctx group members grants effective)]
       (try (ports/create-audit-log audit-repository (update entry :metadata merge metadata))
            (catch Exception e
              (when strict? (throw e))
              (log/warn e "Failed to write a group audit entry" {:action action :group-id (:id group)})))))))

(defn- checked-grant
  "`grant` as stored, its scope {} when it has none. Throws :validation-error
   when the declared schemas refuse it or it names a code role, carrying
   `data`."
  [settings registry grant data]
  (let [grant {:permission (:permission grant) :scope (or (:scope grant) {})}]
    (when-let [{:keys [error]} (group-core/grant-refusal grant settings (code-roles registry))]
      (throw (ex-info (:message error) (merge error data))))
    grant))

(defn- checked-expiry!
  "`expires-at`, any inst (a #inst reads as a Date), as the Instant stored;
   nil for never. Throws :validation-error for one that is no inst, has
   passed, or that the repository cannot store: it lacks IAccessExpiry."
  [svc expires-at]
  (when (some? expires-at)
    (when-not (:expiry? svc)
      (throw (refused "This group repository keeps no expiry dates" {:field :expires-at})))
    (when-not (inst? expires-at)
      (throw (refused "Not a date" {:field :expires-at})))
    (let [t (if (instance? Instant expires-at) expires-at (Instant/ofEpochMilli (inst-ms expires-at)))]
      (when-let [{:keys [error]} (group-core/expiry-refusal t (Instant/now))]
        (throw (ex-info (:message error) error)))
      t)))

(defn- checked-assignment
  "`assignment` as stored, its scope {} when it has none. Throws
   :validation-error naming an unknown role or a scope the schema refuses."
  [settings roles assignment data]
  (let [a {:role (:role assignment) :scope (or (:scope assignment) {})}]
    (when-let [{:keys [error]} (group-core/assignment-refusal a roles settings)]
      (throw (ex-info (:message error) (merge error data))))
    a))

(defn- user-id-of [user]
  (if (map? user) (:id user) user))

(defn- provider-groups
  "The group names the membership hook gives `user`, a map or an id."
  [membership user-repository user]
  (when membership
    (when-let [user (if (map? user) user (ports/find-user-by-id user-repository user))]
      (set (membership user)))))

(defn active-users
  "Every active user, by email, through `xf`, read a page at a time so only
   what `xf` keeps is held."
  [user-repository xf]
  (let [page 500]
    (loop [offset 0 acc []]
      (let [users (:users (ports/find-users user-repository {:limit page :offset offset :filter-active true
                                                             :sort-by :email :sort-direction :asc}))
            acc   (into acc xf users)]
        (if (< (count users) page) acc (recur (+ offset page) acc))))))

(defn- find-role
  "The managed role named `role-name`, or nil."
  [repo role-name]
  (some #(when (= role-name (:name %)) %) (ports/find-roles repo)))

(defn- legacy-role!
  "The managed role a grant of `permission` becomes, created when missing.
   Throws :validation-error when a code role has its name: assigning that
   would hand out the code role's permissions."
  [repo registry permission]
  (when (contains? (code-roles registry) permission)
    (throw (refused "A module ships a role of this name: assign the role"
                    {:field :permission :permission permission})))
  (or (find-role repo permission)
      (ports/insert-role repo {:name        permission
                               :description "Migrated from a group grant"
                               :permissions #{permission}})))

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
  "Every assignment of one of `role-names`, with its group's members unless
   `members?` is false; Everyone lists none."
  ([repo role-names] (role-holders repo role-names true))
  ([repo role-names members?]
   (let [members (memoize #(ports/find-members repo %))]
     (->> (mapcat #(ports/find-role-assignments repo %) (set role-names))
          (map (fn [{:keys [group-id] :as a}]
                 (let [everyone? (group-core/everyone? group-id)]
                   (cond-> (assoc (select-keys a [:role :group-id :group-name :scope :expires-at]) :everyone? everyone?)
                     members? (assoc :members (if everyone? [] (members group-id)))))))
          (sort-by (juxt :group-name (comp str :role)))
          vec))))

(defn- unique-by-id [users]
  (vals (into {} (map (juxt (comp str :id) identity)) users)))

;; =============================================================================
;; Effective rights diff (BOU-852)
;; =============================================================================

(defn- stored-resolve
  "Rights resolved without a registry: each scope value stands for itself."
  [_user rights]
  (mapv (fn [r] (update r :scope #(update-vals (or % {}) hash-set))) rights))

(defn- without-group [group-id assignments]
  (into [] (remove #(access/same-id? group-id (:group-id %))) assignments))

(defn- without-one
  "`assignments` less one of `a`'s role and scope in its group."
  [a assignments]
  (let [[before [_ & after]] (split-with #(not (and (access/same-id? (:group-id a) (:group-id %))
                                                    (= (group-core/assignment-key a) (group-core/assignment-key %))))
                                         assignments)]
    (into (vec before) after)))

(defn- reached
  "The users a role of `group-id` reaches by name: its members, none for
   Everyone, whose reach `:everyone?` on the plan stands for."
  [{:keys [group-repository]} group-id]
  (if (group-core/everyone? group-id) [] (ports/find-members group-repository group-id)))

(defn- change-plan
  "What `change` (see `IRightsPreview`) does, read before it is made: the
   `:group` or `:role` it changes, the `:users` it reaches and the one it names
   as `:focal`, `:everyone?` when it reaches every active user too, the
   group's `:grants` it gives or takes, the `:touched`
   permissions when they are not those grants', `:roles` before and
   `:roles-after`, and `:update`, what it does to a user's assignments.
   Throws as making the change would."
  [{:keys [group-repository user-repository registry settings membership] :as svc} {:keys [op group-id user-id role] :as change}]
  (let [roles (roles-of svc)
        ;; The user a preview names when the change names none (BOU-853)
        plan  {:roles roles :roles-after roles :update identity :named-id user-id}]
    (case op
      :add-member
      (let [_     (not-everyone! group-id)
            until (checked-expiry! svc (:expires-at change))
            group (existing-group group-repository group-id)
            user  (or (ports/find-user-by-id user-repository user-id)
                      (throw (not-found "No such user" {:user-id user-id})))
            held  (ports/find-assignments group-repository group-id)
            grants (group-core/user-grants held roles)]
        (assoc plan :group group :users [user] :focal user :grants grants :update #(into % held)
               :expires-at until))

      (:remove-member :delete-group)
      (let [_      (when (= :delete-group op) (not-everyone! group-id))
            group  (ports/find-group group-repository group-id)
            user   (when (and group (= :remove-member op)) (ports/find-user-by-id user-repository user-id))
            grants (if group (group-core/user-grants (ports/find-assignments group-repository group-id) roles) [])
            ;; An identity provider still naming the group keeps its roles
            kept?  (and user (contains? (provider-groups membership user-repository user) (:name group)))]
        (assoc plan :group group :focal user :grants grants
               :users (cond user                               [user]
                            (and group (= :delete-group op))   (ports/find-members group-repository group-id)
                            :else                              [])
               :update (if kept? identity (partial without-group group-id))))

      :new-group
      (let [user (or (ports/find-user-by-id user-repository user-id)
                     (throw (not-found "No such user" {:user-id user-id})))
            a    (checked-assignment settings roles (:assignment change) nil)
            _    (checked-expiry! svc (:expires-at change))]
        (assoc plan :users [user] :focal user :grants (group-core/user-grants [a] roles) :update #(conj % a)))

      :add-role
      (let [group (existing-group group-repository group-id)
            until (checked-expiry! svc (get-in change [:assignment :expires-at]))
            a     (cond-> (assoc (checked-assignment settings roles (:assignment change) nil) :group-id group-id)
                    until (assoc :expires-at until))
            grants (group-core/user-grants [a] roles)]
        (assoc plan :group group :users (reached svc group-id) :everyone? (group-core/everyone? group-id)
               :assignment a :grants grants :update #(conj % a)))

      :remove-role
      (let [group (ports/find-group group-repository group-id)
            a     (when group
                    (some #(when (access/same-id? (:assignment-id change) (:id %)) %)
                          (ports/find-assignments group-repository group-id)))
            grants (if a (group-core/user-grants [a] roles) [])]
        (assoc plan :group group :users (if a (reached svc group-id) []) :assignment a :grants grants
               :everyone? (boolean (and a (group-core/everyone? group-id)))
               :update (partial without-one a)))

      (:update-role :delete-role)
      (let [before  (or (find-role group-repository role)
                        (throw (not-found "No such role" {:role role})))
            after   (when (= :update-role op)
                      (:permissions (role-data registry {:name role :description (:description change)
                                                         :permissions (:permissions change)})))
            holders (role-holders group-repository [role])]
        (assoc plan :role before :holders holders
               :users (unique-by-id (mapcat :members holders))
               :everyone? (boolean (some :everyone? holders))
               :touched (set (concat (remove (set after) (:permissions before))
                                     (remove (set (:permissions before)) after)))
               :roles-after (if after (assoc-in roles [role :permissions] (set after)) (dissoc roles role)))))))

(defn- stored-holdings
  "What stored memberships and Everyone give of `role-names`, by
   `role-holders`, so Everyone costs a row and not one per user: `:everyone`,
   its assignments, `:by-user` {user-id-string [assignment]} the other
   groups', `:members` of those groups and `:groups?` whether there are any."
  [repo role-names]
  (let [{everyone true others false} (group-by :everyone? (role-holders repo role-names))]
    {:everyone (mapv #(dissoc % :members) everyone)
     :by-user  (reduce (fn [acc a] (let [a' (dissoc a :members)]
                                     (reduce #(update %1 (str (:id %2)) (fnil conj []) a') acc (:members a))))
                       {} others)
     :members  (unique-by-id (mapcat :members others))
     :groups?  (boolean (seq others))}))

(defn- active-count [user-repository]
  (:total-count (ports/find-users user-repository {:limit 1 :offset 0 :filter-active true})))

(defn- user-diffs
  "How the change `plan` describes alters each reached user's effective
   rights, [{:user {:id :email} :added :removed}]: their rights before and
   after, of the touched permissions, resolved as the authorization
   interceptor resolves them and compared by `scope/rights-diff`. The focal
   user's, and an inactive user's, are read with their identity provider's
   groups, one query each; the others' from the groups holding a touched
   role, a query per group, and the groups the membership hook names, one
   query per group.

   A plan reaching Everyone diffs the users another group gives a touched
   role and the one it names, and the rest as one diff with their `:count`: they
   hold only Everyone's (BOU-862). Unless a membership hook may put them in
   such a group, or Everyone's scope takes a value from the user: then every
   active user is read. None when no permission is touched."
  [{:keys [group-repository user-repository membership] :as svc}
   {:keys [focal grants roles roles-after update everyone? named-id] :as plan}]
  (when-let [touched (not-empty (or (:touched plan) (into #{} (map :permission) grants)))]
    (let [resolve  (or (:resolve svc) stored-resolve)
          ;; find-members says :active?, a user record :active
          active?  #(not (false? (get % :active? (:active %))))
          focal?   #(access/same-id? (:id focal) (:id %))
          ;; Read one by one
          own?     #(or (focal? %) (not (active? %)))
          ;; Unread when every user is read one by one
          stored   (delay (stored-holdings group-repository
                                           (into #{} (mapcat #(concat (group-core/roles-holding roles %)
                                                                      (group-core/roles-holding roles-after %)))
                                                 touched)))
          scan?    (and everyone?
                        (or (and membership (:groups? @stored))
                            (some scope/source? (mapcat (comp vals :scope)
                                                        (cons (:assignment plan) (:everyone @stored))))))
          users    (cond
                     scan?     (active-users user-repository (map identity))
                     everyone? (let [users (unique-by-id (concat (:users plan) (filter active? (:members @stored))))]
                                 (if (or (nil? named-id) (some #(access/same-id? named-id (:id %)) users))
                                   users
                                   (concat users (some-> (ports/find-user-by-id user-repository named-id) vector))))
                     :else     (:users plan))
          ;; A provider group's assignments, by name, read once per group
          provided (when (and membership (some (complement own?) users))
                     (let [ids (delay (into {} (map (juxt :name :id)) (ports/find-groups group-repository)))]
                       (memoize #(some->> (get @ids %) (ports/find-assignments group-repository)))))
          ;; find-members gives part of a user, without :active
          record   #(if (contains? % :active) % (ports/find-user-by-id user-repository (:id %)))
          rights   (fn [assignments roles]
                     (filterv (comp touched :permission) (group-core/user-grants assignments roles)))
          diff     (fn [user-map before after]
                     (scope/rights-diff (resolve user-map before) (resolve user-map after)))
          baseline (when (and everyone? (not scan?))
                     (let [n (- (active-count user-repository) (count (filter active? users)))]
                       (when (pos? n) n)))]
      (cond-> (mapv (fn [user]
                      (let [focal?   (focal? user)
                            own?     (own? user)
                            mine     (when-not own?
                                       (into (:everyone @stored) (get-in @stored [:by-user (str (:id user))])))
                            full     (when (and provided (not own?)) (record user))
                            held     (cond
                                       own?
                                       (ports/find-user-assignments group-repository (:id user)
                                                                    (provider-groups membership user-repository
                                                                                     (if focal? user (:id user))))
                                       full
                                       (let [ids (set (map (comp str :group-id) mine))]
                                         (into mine (comp (mapcat provided) (remove (comp ids str :group-id)))
                                               (provider-groups membership user-repository full)))
                                       :else mine)
                            before   (rights held roles)
                            after    (rights (update held) roles-after)
                            ;; A source resolver reads the user, so give it the whole record
                            user-map (if (and (not focal?) (some scope/source? (mapcat (comp vals :scope) (concat before after))))
                                       (or full (record user) user)
                                       user)]
                        (assoc (diff user-map before after) :user (select-keys user [:id :email]))))
                    users)
        baseline
        (conj (let [held (:everyone @stored)]
                (assoc (diff {} (rights held roles) (rights (update held) roles-after)) :count baseline)))))))

(defn- audit-role-change!
  "Audit what changing managed role `role-name` from `before` to `after`
   permissions does to every group holding it, the entries sharing a
   `:change-id` so a user's rights timeline shows the edit once."
  [audit-repository role-name holders before after diffs]
  (let [metadata {:change-id (str (random-uuid)) :role (group-core/permission-str role-name)}]
    (doseq [{:keys [group-id group-name scope members]} holders
            [action ps] [[:grant-remove (remove (set after) before)] [:grant-add (remove (set before) after)]]
            :when (seq ps)]
      (audit! audit-repository action {:id group-id :name group-name} members
              (for [p (sort ps)] {:permission p :scope scope :role role-name})
              {:diffs diffs :metadata metadata}))))

;; =============================================================================
;; Import
;; =============================================================================

(defn- import-spec
  "`spec` checked and trimmed: its roles against `roles` and `settings`, its
   grants turned into roles, its members looked up. Throws :validation-error
   naming the group."
  [user-repository settings registry roles spec]
  (when-let [explanation (m/explain user-schema/GroupImport spec)]
    (throw (refused "Invalid group import" {:group  (:name spec)
                                            :errors (me/humanize explanation)})))
  (let [data   (group-data spec)
        grants (mapv #(checked-grant settings registry % {:group (:name data)}) (:grants spec))
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
  (when (and existing (or (seq members) (contains? spec :description)))
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
      (when (seq leave) (audit! audit-repository :group-leave group leave (grants-of held) {:strict? true}))
      (when (seq remove) (audit! audit-repository :grant-remove group stay (grants-of remove) {:strict? true}))
      (when (seq added) (audit! audit-repository :grant-add group stay (grants-of added) {:strict? true}))
      (when (seq join) (audit! audit-repository :group-join group join
                               (grants-of (ports/find-assignments repo (:id group))) {:strict? true}))
      {:group         group
       :created?      (nil? existing)
       :joined        (mapv :id join)
       :left          (mapv :id leave)
       :roles-added   added
       :roles-removed remove})))

;; =============================================================================
;; Migration
;; =============================================================================

(defn- assign-once!
  ([repo group-id role scope] (assign-once! repo group-id role scope nil))
  ([repo group-id role scope id]
   (when-not (some #(= [role scope] (group-core/assignment-key %)) (ports/find-assignments repo group-id))
     (ports/insert-assignment repo (cond-> {:group-id group-id :role role :scope scope} id (assoc :id id))))))

(defn- migrate-grants!
  "Each grant not yet migrated becomes an assignment of the managed role of
   its permission, keeping its id so remove-group-grant still finds it, and is
   marked. The row stays for nodes on the previous version; marked, an
   assignment removed later is not made again."
  [repo registry grants]
  (doseq [{:keys [id group-id] :as grant} grants
          :let [{:keys [role scope]} (group-core/grant->assignment grant)]]
    (legacy-role! repo registry role)
    (assign-once! repo group-id role scope id)
    (ports/mark-legacy-grant-migrated repo id)))

(defn- migrate-viewers!
  "`viewers` join Readers, which holds `roles`, and keep no role."
  [repo viewers roles]
  (when (seq viewers)
    (let [readers (or (some #(when (= "Readers" (:name %)) %) (ports/find-groups repo))
                      (ports/insert-group repo {:name "Readers" :description "Read-only users"}))
          ;; A refused duplicate would abort the transaction on PostgreSQL.
          members (set (map :id (ports/find-members repo (:id readers))))]
      (doseq [id viewers :when (not (members id))] (ports/insert-member repo (:id readers) id))
      (doseq [role roles]
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

(defn- shadowed-grant?
  "Whether legacy `grant` is not yet migrated and named after a code role."
  [registry grant]
  (and (nil? (:migrated-at grant)) (contains? (code-roles registry) (:permission grant))))

(defn migration-plan
  "What `migrate!` changes, read without writing: {:everyone-roles (nil when
   Everyone exists or only admins do) :viewers [user-id] :readers-roles
   :grants [legacy grant not yet migrated]}, but not one named after a code
   role: that stays unmigrated, and `migrate!` logs it. Neither group gets a
   role holding a permission something already checks as a grant
   (`gated-permissions`): that would hand it to every user."
  [{:keys [group-repository registry settings]}]
  (let [grants  (ports/find-legacy-grants group-repository)
        gated   (group-core/gated-permissions (when registry (authz/permissions registry))
                                              (:permissions (declared-permissions settings))
                                              grants)
        viewers (ports/find-legacy-viewers group-repository)]
    {:everyone-roles (when (and (nil? (ports/find-group group-repository group-core/everyone-id))
                                (ports/any-non-admin-users? group-repository))
                       (vec (group-core/module-roles (code-roles registry) "editor" gated)))
     :viewers        viewers
     :readers-roles  (when (seq viewers) (vec (group-core/module-roles (code-roles registry) "viewer" gated)))
     :grants         (filterv #(and (nil? (:migrated-at %)) (not (shadowed-grant? registry %))) grants)}))

(defn migrate!
  "ADR-041's migration: under :enforce nobody may do less than under :off, and
   nobody becomes an admin. Idempotent; runs at boot.

   The first time, Everyone is created and, when there are users other than
   admins, given every module's editor role. Viewers join Readers. Group
   grants become assignments of a managed role named after their permission.
   `migration-plan` decides each, so what `wagoe upgrade authorization check`
   prints is what runs."
  [{:keys [group-repository registry] :as svc}]
  (ports/ensure-tables group-repository)
  (try
    (ports/in-transaction group-repository
                          (fn [repo]
                            (let [{:keys [everyone-roles viewers readers-roles grants]}
                                  (migration-plan (assoc svc :group-repository repo))]
                              (ports/ensure-everyone repo)
                              (doseq [role everyone-roles]
                                (assign-once! repo group-core/everyone-id role {}))
                              (migrate-viewers! repo viewers readers-roles)
                              (migrate-grants! repo registry grants))))
    (catch clojure.lang.ExceptionInfo e
      ;; Another node created Everyone or Readers first and migrated.
      (if (= :conflict (:type (ex-data e)))
        (log/info "Roles were migrated by another node")
        (throw e))))
  (when registry
    (when-let [shadowed (seq (into (sorted-set) (comp (filter #(shadowed-grant? registry %)) (map :permission))
                                   (ports/find-legacy-grants group-repository)))]
      (log/warn "Group grants named after a code role are not migrated; assign the role or a managed one"
                {:permissions (vec shadowed)}))
    (when-let [legacy (seq (sort (remove (authz/permissions registry)
                                         (mapcat :permissions (ports/find-roles group-repository)))))]
      (log/warn "Managed roles hold permissions no module declares; they still count" {:permissions (vec legacy)}))
    (assert-sources! group-repository registry)))

;; =============================================================================
;; Expiry (BOU-856)
;; =============================================================================

(defn- expiry-metadata [expires-at]
  (when expires-at {:expires-at (str expires-at)}))

(defn- add-member!
  "Add the user to the group, until `expires-at` unless nil, auditing the join."
  [{:keys [group-repository audit-repository] :as svc} group-id user-id expires-at]
  (let [{:keys [group focal grants expires-at] :as plan}
        (change-plan svc {:op :add-member :group-id group-id :user-id user-id :expires-at expires-at})
        diffs (user-diffs svc plan)
        added (if expires-at
                (ports/insert-member-until group-repository group-id (:id focal) expires-at)
                (ports/insert-member group-repository group-id (:id focal)))]
    (when added
      (audit! audit-repository :group-join group [focal] grants
              {:diffs diffs :metadata (expiry-metadata expires-at)}))
    added))

(defn- offer
  "`option` with the preview of `change`, or with :refused, the refusal's
   ex-data, when the change would be refused: one bad option leaves the rest."
  [svc option change]
  (try (assoc option :preview (ports/preview-change svc change))
       (catch clojure.lang.ExceptionInfo e
         (let [refusal (ex-data e)]
           (if (#{:validation-error :not-found} (:type refusal))
             (assoc option :refused refusal)
             (throw e))))))

(defn- create-grant-group!
  "The group of one the grant wizard offers, made in one transaction."
  [svc group user-id assignment expires-at]
  ;; Refused before anything is written, as the wizard previewed it. No role
  ;; changes here, so the three checks share one read of them.
  (let [svc (with-roles svc)]
    (change-plan svc {:op :new-group :user-id user-id :assignment assignment :expires-at expires-at})
    (ports/in-transaction (:group-repository svc)
                          (fn [repo]
                            (let [svc (assoc svc :group-repository repo)
                                  g   (ports/insert-group repo (group-data group))]
                              (add-member! svc (:id g) user-id expires-at)
                              (ports/add-group-role svc (:id g) assignment)
                              g)))))

(defn- expire!
  "Remove what expired by `now`, auditing each as a leave or a removed role
   without an effective-rights diff: reads ignored it from the moment it
   expired, so removing it changes no one's rights."
  [{:keys [group-repository user-repository audit-repository registry]} now]
  (let [{:keys [members assignments] :as removed} (ports/remove-expired group-repository now)
        roles    (all-roles group-repository registry)
        ;; Per group, once: several rows of one group expire together
        group-of (memoize #(or (ports/find-group group-repository %) {:id %}))
        grants   (memoize #(group-core/user-grants (ports/find-assignments group-repository %) roles))
        members-of (memoize #(when-not (group-core/everyone? %) (ports/find-members group-repository %)))
        expired  #(assoc (expiry-metadata %) :reason "expired")]
    (doseq [{:keys [group-id user-id expires-at]} members
            ;; A soft-deleted user held nothing through it
            :let [user (ports/find-user-by-id user-repository user-id)]
            :when user]
      (audit! audit-repository :group-leave (group-of group-id) [user] (grants group-id)
              {:metadata (expired expires-at)}))
    (doseq [{:keys [group-id expires-at] :as a} assignments]
      (audit! audit-repository :grant-remove (group-of group-id) (members-of group-id) (group-core/user-grants [a] roles)
              {:metadata (expired expires-at)}))
    (when (or (seq members) (seq assignments))
      (log/info "Removed expired group access" {:members (count members) :assignments (count assignments)}))
    removed))

;; =============================================================================
;; Service
;; =============================================================================

(defrecord UserGroupService [group-repository user-repository audit-repository settings registry membership]
  authz/IRights
  ;; What the platform's authorization interceptor reads, once per request.
  (rights [this user]
    (ports/user-grants this user))

  ports/IAccessExplain
  (explain-access [this user permission record]
    (let [explain (or (:explain this)
                      (throw (ex-info "Explaining access needs the permission registry"
                                      {:type :configuration-error})))
          ;; Grants and groups both ask the membership hook: ask it once
          svc     (cond-> this membership (update :membership memoize))
          result  (explain svc user permission record)
          groups  (into {} (map (juxt :id identity)) (ports/user-groups svc user))
          sourced #(let [{:keys [source expires-at]} (get groups (:group-id %))]
                     (assoc % :membership source :membership-expires-at expires-at))]
      (cond-> (-> result
                  (update :paths (partial mapv sourced))
                  (update :misses (partial mapv #(update % :right sourced))))
        (not (:allowed? result))
        (assoc :alternatives
               (role-holders group-repository
                             (group-core/roles-holding (roles-of this) permission)
                             false)))))

  ports/IRoleAssignments
  (all-assignments [_]
    (role-holders group-repository (keys (all-roles group-repository registry)) false))

  ports/IRightsPreview
  (preview-change [this change]
    (let [{:keys [focal group] :as plan} (change-plan this change)
          ;; A role change names no user, unless the change does (BOU-853)
          summary (group-core/diff-summary (user-diffs this plan) (or (:id focal) (:user-id change)))]
      (cond-> (assoc summary :everyone? (boolean (or (:everyone? plan) (some-> group :id group-core/everyone?))))
        ;; No permission touched: the user the change names still gains nothing
        (and focal (nil? (:user summary)))
        (assoc :user {:user (select-keys focal [:id :email]) :added [] :removed []}))))

  ports/IGrantOptions
  (grant-options [this user permission scope]
    (let [;; Every preview asks the membership hook for this user, and reads the roles: once
          this    (cond-> (with-roles this) membership (update :membership memoize))
          roles   (::roles this)
          explain (ports/explain-access this user permission (if (empty? scope) :everywhere scope))
          role    (grant-core/smallest-role roles permission)
          exact?  (= #{permission} (get-in roles [role :permissions]))
          a       {:role role :scope scope}
          offer   (partial offer this)
          mine    (filterv #(= :member (:source %)) (ports/user-groups this user))
          mine?   (into #{} (map (comp str :id)) mine)]
      {:explain explain
       :role    role
       :exact?  exact?
       :options (when-not (:allowed? explain)
                  (grant-core/rank
                   (concat
                    (for [[gid [{:keys [group-name]}]] (group-by :group-id (:alternatives explain))
                          :when (not (or (group-core/everyone? gid) (mine? (str gid))))]
                      (offer {:kind :join :group {:id gid :name group-name}}
                             {:op :add-member :group-id gid :user-id (:id user)}))
                    (when role
                      (for [g mine]
                        (offer {:kind :narrow :group (select-keys g [:id :name]) :assignment a}
                               {:op :add-role :group-id (:id g) :assignment a :user-id (:id user)})))
                    (when-not exact? [{:kind :new-role}])
                    (when role
                      [(offer {:kind :new-group :assignment a} {:op :new-group :user-id (:id user) :assignment a})]))
                   permission scope))}))

  ports/IGrantGroup
  (create-grant-group [this group user-id assignment expires-at]
    (create-grant-group! this group user-id assignment expires-at))

  ports/ITimeBoundAccess
  (add-group-member-until [this group-id user-id expires-at]
    (add-member! this group-id user-id expires-at))

  (expire-access! [this now]
    (if (:expiry? this)
      (expire! this now)
      {:members [] :assignments []}))

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

  (delete-group [this group-id]
    (let [{:keys [group users] :as plan} (change-plan this {:op :delete-group :group-id group-id})]
      (if group
        (let [diffs   (user-diffs this plan)
              removed (ports/remove-group group-repository group-id)]
          (when (and removed (seq users))
            (audit! audit-repository :group-leave group users (:grants plan) {:diffs diffs}))
          removed)
        false)))

  (add-group-member [this group-id user-id]
    (add-member! this group-id user-id nil))

  (remove-group-member [this group-id user-id]
    (let [{:keys [group focal grants] :as plan} (change-plan this {:op :remove-member :group-id group-id :user-id user-id})
          diffs   (user-diffs this plan)
          removed (ports/remove-member group-repository group-id user-id)]
      ;; A soft-deleted user holds nothing, so leaving changes nothing to audit.
      (when (and removed focal)
        (audit! audit-repository :group-leave group [focal] grants {:diffs diffs}))
      removed))

  (list-roles [_]
    (->> (all-roles group-repository registry)
         (map (fn [[k r]] (assoc (select-keys r [:description :permissions :source :module :id]) :name k)))
         (sort-by (comp str :name))
         vec))

  (create-role [_ role]
    (ports/insert-role group-repository (role-data registry role)))

  (update-role [this role]
    (let [data  (role-data registry role)
          {before :role :keys [holders] :as plan}
          (change-plan this {:op :update-role :role (:name data) :description (:description data)
                             :permissions (:permissions data)})
          diffs (user-diffs this plan)]
      (ports/modify-role group-repository data)
      (audit-role-change! audit-repository (:name data) holders (:permissions before) (:permissions data) diffs)
      (assoc before :description (:description data) :permissions (:permissions data))))

  (delete-role [this role-name]
    (if (find-role group-repository role-name)
      (let [{role :role :keys [holders] :as plan} (change-plan this {:op :delete-role :role role-name})]
        (audit-role-change! audit-repository role-name holders (:permissions role) #{} (user-diffs this plan))
        (ports/remove-role group-repository role-name))
      false))

  (add-group-role [this group-id assignment]
    (let [{:keys [group users assignment grants] :as plan}
          (change-plan this {:op :add-role :group-id group-id :assignment assignment})
          diffs  (user-diffs this plan)
          stored (ports/insert-assignment group-repository assignment)]
      (audit! audit-repository :grant-add group users grants
              {:diffs diffs :metadata (expiry-metadata (:expires-at assignment))})
      stored))

  (remove-group-role [this group-id assignment-id]
    (let [{:keys [group users assignment grants] :as plan}
          (change-plan this {:op :remove-role :group-id group-id :assignment-id assignment-id})
          diffs   (user-diffs this plan)
          removed (boolean (and assignment (ports/remove-assignment group-repository group-id (:id assignment))))]
      (when removed
        (audit! audit-repository :grant-remove group users grants {:diffs diffs}))
      removed))

  (add-group-grant [this group-id grant]
    (existing-group group-repository group-id)
    (let [{:keys [permission scope]} (checked-grant settings registry grant nil)]
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
      (into [] (comp (map #(-> % (select-keys [:user-id :scope]) (assoc :permission permission))) (distinct))
            (ports/find-role-holders group-repository roles))))

  (role-holders [_ role-names]
    (role-holders group-repository role-names))

  (provider-members [_ group-id]
    (let [group (ports/find-group group-repository group-id)]
      (if (and membership group (not (group-core/everyone? group-id)))
        (active-users user-repository
                      (comp (filter #(some #{(:name group)} (membership %)))
                            (map #(select-keys % [:id :email :name]))))
        [])))

  (import-groups! [_ groups {:keys [replace?]}]
    (ports/ensure-tables group-repository)
    (let [roles (all-roles group-repository registry)
          specs (mapv #(import-spec user-repository settings registry roles %) groups)]
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
   identity provider names, and `:explain` and `:resolve`,
   `wagoe.platform.shell.authorization/explain` and `resolve-rights` with the
   registry applied."
  ([group-repository user-repository audit-repository settings]
   (create-group-service group-repository user-repository audit-repository settings {}))
  ([group-repository user-repository audit-repository settings {:keys [registry membership explain resolve]}]
   (cond-> (assoc (->UserGroupService group-repository user-repository audit-repository
                                      (with-registry-permissions settings registry) registry membership)
                  ;; Asked once: a repository without it keeps no expiry (Convention 5)
                  :expiry? (satisfies? ports/IAccessExpiry group-repository))
     explain (assoc :explain explain)
     resolve (assoc :resolve resolve))))
