(ns wagoe.admin.core.permissions
  "Who may do what in the admin, as pure decisions.

   Under `:wagoe/authorization :mode :off` and `:report` today's rule
   decides: the `:admin` role. Under `:enforce` the user's rights do
   (ADR-041 §6): `admin.<entity>/read` to see an entity, `admin.<entity>/write`
   to change it, each held in scopes the entity can check. A platform admin
   holds everything, everywhere.

   The shell puts the request's rights on the user map under `::access`
   (`with-access`); a user without it is decided by the role."
  (:require [wagoe.platform.core.scope :as scope]))

;; =============================================================================
;; Role-Based Permission Checks
;; =============================================================================

(defn has-role?
  "Check if user has a specific role.

   Args:
     user: User entity map with :role keyword
     required-role: Role keyword to check for

   Returns:
     Boolean true if user has the required role

   Examples:
     (has-role? {:role :admin} :admin)    ;=> true
     (has-role? {:role :user} :admin)     ;=> false
     (has-role? nil :admin)               ;=> false"
  [user required-role]
  (boolean (and user
                (= (:role user) required-role))))

(defn is-admin?
  "Check if user has admin role.

   Args:
     user: User entity map

   Returns:
     Boolean true if user is an admin

   Examples:
     (is-admin? {:role :admin})  ;=> true
     (is-admin? {:role :user})   ;=> false
     (is-admin? nil)             ;=> false"
  [user]
  (has-role? user :admin))

(defn is-authenticated?
  "Check if user is authenticated (has any role).

   Args:
     user: User entity map (can be nil)

   Returns:
     Boolean true if user exists and has a role

   Examples:
     (is-authenticated? {:role :admin})  ;=> true
     (is-authenticated? {:role :user})   ;=> true
     (is-authenticated? nil)             ;=> false
     (is-authenticated? {})              ;=> false"
  [user]
  (and user
       (:role user)))

;; =============================================================================
;; Rights (ADR-041 §6)
;; =============================================================================

(defn with-access
  "`user` carrying `access`: {:mode :off|:report|:enforce, :platform-admin? bool,
   :rights resolved rights [{:permission kw :scope {key #{value}}}]}."
  [user access]
  (when user (assoc user ::access access)))

(defn has-access?
  "Whether `user` already carries their access."
  [user]
  (contains? user ::access))

(defn mode
  "The authorization mode `user` is decided under, `:off` without access."
  [user]
  (get-in user [::access :mode] :off))

(defn- right
  "The permission's action `action` needs: deleting is writing."
  [action]
  (if (= :delete action) :write action))

(defn entity-permission
  "`admin.<entity>/read` or `admin.<entity>/write`, the one `action` needs."
  [entity-name action]
  (keyword (str "admin." (name entity-name)) (name (right action))))

(defn scopes
  "The scopes in which `user`'s rights let them take `action` (:read or :write)
   on `entity-name`, `[{}]` for everywhere. Only scopes `entity-config` can
   check count: each key mapped under its `:scope` to a field or a relation
   condition. So an entity mapping none admits only unscoped holders."
  [user entity-name action entity-config]
  (let [{:keys [platform-admin? rights]} (::access user)
        fields (:scope entity-config)]
    (if platform-admin?
      [{}]
      (filterv #(every? (partial contains? fields) (keys %))
               (scope/scopes-for rights (entity-permission entity-name action))))))

(defn scope-fields
  "{scope-key field} for the keys `entity-config` maps to a field of its own,
   not a relation condition."
  [entity-config]
  (into {} (filter (comp keyword? val)) (:scope entity-config)))

(defn- field-scopes
  "Those of `scopes` a record's own values can be checked against."
  [scopes entity-config]
  (let [fields (scope-fields entity-config)]
    (filterv #(every? fields (keys %)) scopes)))

(defn- by-rights?
  "Whether `user`'s rights admit `action` on `entity-name`. `:write` creates and
   edits, so only scopes a record's values can be checked against count;
   `:delete` checks the record in the database, so all write scopes do."
  [user entity-name action entity-config]
  (let [scopes (scopes user entity-name (right action) entity-config)]
    (boolean (seq (if (= :write action) (field-scopes scopes entity-config) scopes)))))

(defn- allows?
  "Whether `user` may take `action` (:read, :write or :delete) on
   `entity-name`: by rights under `:enforce`, else by the `:admin` role."
  [user entity-name action entity-config]
  (if (= :enforce (mode user))
    (by-rights? user entity-name action entity-config)
    (is-admin? user)))

(defn report-refusal
  "Under `:report`, the permission `:enforce` would refuse `user` where the
   role admits them; else nil."
  [user entity-name action entity-config]
  (when (and (= :report (mode user))
             (is-admin? user)
             (not (by-rights? user entity-name action entity-config)))
    (entity-permission entity-name action)))

(defn- reads-any-entity?
  "Whether `user` may read one of `entity-configs`, {entity-name config}, in a
   scope that entity can check: read held only in scopes no entity maps
   admits nothing. A platform admin needs no entity."
  [user entity-configs]
  (boolean (or (get-in user [::access :platform-admin?])
               (some (fn [[entity-name config]] (by-rights? user entity-name :read config))
                     entity-configs))))

(defn report-access-refusal
  "Under `:report`, whether `:enforce` would keep `user` out of the admin
   where the role lets them in. `entity-configs` as `can-access-admin?`."
  [user entity-configs]
  (and (= :report (mode user)) (is-admin? user) (not (reads-any-entity? user entity-configs))))

(defn record-in-scope?
  "Whether `record` (or the record a write would leave behind) lies inside
   one of `scopes`. Only fields can be checked on a record: a scope on a key
   the entity maps to a relation condition admits none, unless one of
   `scopes` holds everywhere."
  [scopes entity-config record]
  (or (scope/everywhere? scopes)
      (scope/in-scope? (field-scopes scopes entity-config)
                       (update-vals (scope-fields entity-config) #(get record %)))))

;; =============================================================================
;; Admin Access Control
;; =============================================================================

(defn can-access-admin?
  "Determine if user can access the admin interface at all.

   Under :enforce, `admin.<entity>/read` on one of `entity-configs` in a
   scope that entity can check; else the :admin role.

   Args:
     user: User entity map
     entity-configs: {entity-name entity-config}, the admin's entities

   Returns:
     Boolean true if user can access admin

   Examples:
     (can-access-admin? {:role :admin} nil)    ;=> true
     (can-access-admin? {:role :user} nil)     ;=> false
     (can-access-admin? nil nil)               ;=> false"
  ([user]
   (can-access-admin? user nil))
  ([user entity-configs]
   (if (= :enforce (mode user))
     (reads-any-entity? user entity-configs)
     (is-admin? user))))

(defn explain-admin-access-denial
  "Provide human-readable explanation for why admin access was denied.

   Args:
     user: User entity map
     entity-configs: as `can-access-admin?`

   Returns:
     Map with denial reason and suggested action:
     {:denied true
      :reason \"User does not have admin role\"
      :user-role :user
      :required-role :admin}

   Examples:
     (explain-admin-access-denial {:role :user})
     (explain-admin-access-denial nil)"
  ([user]
   (explain-admin-access-denial user nil))
  ([user entity-configs]
   (cond
     (not user)
     {:denied true
      :reason "User not authenticated"
      :required-role :admin}

     (= :enforce (mode user))
     (if (reads-any-entity? user entity-configs)
       {:denied false :reason "Access granted"}
       {:denied true
        :reason "User may read no entity in the admin"
        :user-id (:id user)
        :required-permission :admin.<entity>/read})

     (not (:role user))
     {:denied true
      :reason "User has no assigned role"
      :user-id (:id user)
      :required-role :admin}

     (not (is-admin? user))
     {:denied true
      :reason "User does not have admin role"
      :user-role (:role user)
      :required-role :admin}

     :else
     {:denied false
      :reason "Access granted"})))

;; =============================================================================
;; Entity-Level Permissions
;; =============================================================================

(defn can-view-entity?
  "Determine if user can view/list records from an entity.

   `admin.<entity>/read` under :enforce, else the :admin role.

   Args:
     user: User entity map
     entity-name: Keyword entity name (e.g., :users, :orders)
     entity-config: Optional entity configuration map

   Returns:
     Boolean true if user can view entity

   Examples:
     (can-view-entity? {:role :admin} :users nil)   ;=> true
     (can-view-entity? {:role :user} :users nil)    ;=> false"
  ([user entity-name]
   (can-view-entity? user entity-name nil))
  ([user entity-name entity-config]
   (allows? user entity-name :read entity-config)))

(defn can-create-entity?
  "Determine if user can create new records for an entity.

   `admin.<entity>/write` under :enforce, else the :admin role.

   Args:
     user: User entity map
     entity-name: Keyword entity name
     entity-config: Optional entity configuration map

   Returns:
     Boolean true if user can create records

   Examples:
     (can-create-entity? {:role :admin} :users nil)   ;=> true
     (can-create-entity? {:role :user} :users nil)    ;=> false"
  ([user entity-name]
   (can-create-entity? user entity-name nil))
  ([user entity-name entity-config]
   ;; `:permissions {:create false}` is for entities another module must
   ;; create, such as tenants (BOU-534).
   (and (allows? user entity-name :write entity-config)
        (not (false? (get-in entity-config [:permissions :create]))))))

(defn can-edit-entity?
  "Determine if user can edit existing records for an entity.

   `admin.<entity>/write` under :enforce, else the :admin role.

   Args:
     user: User entity map
     entity-name: Keyword entity name
     entity-config: Optional entity configuration map
     record: Optional specific record being edited

   Returns:
     Boolean true if user can edit records

   Examples:
     (can-edit-entity? {:role :admin} :users nil nil)  ;=> true
     (can-edit-entity? {:role :user} :users nil nil)   ;=> false"
  ([user entity-name]
   (can-edit-entity? user entity-name nil nil))
  ([user entity-name entity-config]
   (can-edit-entity? user entity-name entity-config nil))
  ([user entity-name entity-config _record]
   (allows? user entity-name :write entity-config)))

(defn can-delete-entity?
  "Determine if user can delete records from an entity.

   `admin.<entity>/write` under :enforce, else the :admin role.

   Args:
     user: User entity map
     entity-name: Keyword entity name
     entity-config: Optional entity configuration map
     record: Optional specific record being deleted

   Returns:
     Boolean true if user can delete records

   Examples:
     (can-delete-entity? {:role :admin} :users nil nil)  ;=> true
     (can-delete-entity? {:role :user} :users nil nil)   ;=> false"
  ([user entity-name]
   (can-delete-entity? user entity-name nil nil))
  ([user entity-name entity-config]
   (can-delete-entity? user entity-name entity-config nil))
  ([user entity-name entity-config _record]
   (allows? user entity-name :delete entity-config)))

(defn can-bulk-delete-entity?
  "Determine if user can perform bulk delete operations.

   Week 1: Any admin can bulk delete (same as single delete).
   Week 2+: Separate permission for bulk operations due to risk.

   Args:
     user: User entity map
     entity-name: Keyword entity name
     entity-config: Optional entity configuration map

   Returns:
     Boolean true if user can bulk delete

   Examples:
     (can-bulk-delete-entity? {:role :admin} :users nil)  ;=> true
     (can-bulk-delete-entity? {:role :user} :users nil)   ;=> false"
  ([user entity-name]
   (can-bulk-delete-entity? user entity-name nil))
  ([user entity-name entity-config]
   ; Week 1: Same as single delete
   ; Week 2+: Check entity-config :permissions :bulk-delete
   (can-delete-entity? user entity-name entity-config)))

;; =============================================================================
;; Entity Filtering - Remove Inaccessible Entities
;; =============================================================================

(defn filter-visible-entities
  "Filter list of entities to only those user can view.

   Those `can-view-entity?` admits, given each one's config.

   Args:
     user: User entity map
     entity-names: Set or vector of entity name keywords
     entity-configs: Optional map of entity-name -> entity-config

   Returns:
     Vector of entity names user can access

   Examples:
     (filter-visible-entities {:role :admin} #{:users :orders} nil)
     ;=> [:users :orders]

     (filter-visible-entities {:role :user} #{:users :orders} nil)
     ;=> []"
  ([user entity-names]
   (filter-visible-entities user entity-names nil))
  ([user entity-names entity-configs]
   (filterv #(can-view-entity? user % (get entity-configs %)) entity-names)))

(defn get-accessible-entities-count
  "Count how many entities user can access.

   Args:
     user: User entity map
     entity-names: Set or vector of entity names
     entity-configs: Optional map of entity configs

   Returns:
     Integer count of accessible entities

   Examples:
     (get-accessible-entities-count {:role :admin} #{:users :orders} nil)
     ;=> 2

     (get-accessible-entities-count {:role :user} #{:users :orders} nil)
     ;=> 0"
  ([user entity-names]
   (get-accessible-entities-count user entity-names nil))
  ([user entity-names entity-configs]
   (count (filter-visible-entities user entity-names entity-configs))))

;; =============================================================================
;; Permission Metadata - For UI Rendering
;; =============================================================================

(defn get-entity-permissions
  "Get all permission flags for an entity for a specific user.

   Returns a comprehensive map of what the user can do with the entity,
   useful for conditionally rendering UI elements (buttons, links, etc.).

   Args:
     user: User entity map
     entity-name: Keyword entity name
     entity-config: Optional entity configuration map

   Returns:
     Map with permission flags:
     {:can-view true
      :can-create true
      :can-edit true
      :can-delete true
      :can-bulk-delete true
      :can-export false        ; Week 2+
      :can-import false}       ; Week 2+

   Examples:
     (get-entity-permissions {:role :admin} :users nil)
     ;=> {:can-view true :can-create true :can-edit true
     ;    :can-delete true :can-bulk-delete true}"
  ([user entity-name]
   (get-entity-permissions user entity-name nil))
  ([user entity-name entity-config]
   {:can-view (can-view-entity? user entity-name entity-config)
    :can-create (can-create-entity? user entity-name entity-config)
    :can-edit (can-edit-entity? user entity-name entity-config)
    :can-delete (can-delete-entity? user entity-name entity-config)
    :can-bulk-delete (can-bulk-delete-entity? user entity-name entity-config)
    ; Week 2+ features
    :can-export false
    :can-import false}))

(defn get-admin-permissions
  "Get global admin permissions for a user.

   Returns what the user can do across the admin interface as a whole.

   Args:
     user: User entity map
     entity-configs: as `can-access-admin?`

   Returns:
     Map with global permission flags:
     {:can-access-admin true
      :can-view-audit-log false   ; Week 2+
      :can-manage-users false}    ; Week 2+

   Examples:
     (get-admin-permissions {:role :admin} nil)
     ;=> {:can-access-admin true}"
  ([user]
   (get-admin-permissions user nil))
  ([user entity-configs]
   {:can-access-admin (can-access-admin? user entity-configs)
    ; Week 2+ features
    :can-view-audit-log false
    :can-manage-users false}))
