(ns wagoe.admin.core.permissions
  "Pure permission logic for admin access control.

   This namespace contains pure business logic for determining admin access
   permissions. All functions are pure (no side effects) and testable without
   database or session state.

   Week 1 Implementation:
   - Simple role-based access control (RBAC) using :admin role
   - All permission checks verify user has :admin role
   - Foundation for more complex permissions in Week 2+

   Week 2+ Roadmap:
   - Entity-level permissions (some entities admin-only, others viewable by all)
   - Action-level permissions (some users can view but not edit)
   - Field-level permissions (hide sensitive fields for certain roles)
   - Permission groups and hierarchies

   Key responsibilities:
   - Determine if user can access admin interface
   - Determine if user can perform operations on entities
   - Filter entity lists based on permissions
   - Provide permission metadata for UI rendering")

;; =============================================================================
;; Role-Based Permission Checks (Week 1)
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
;; Admin Access Control (Week 1 - Simple RBAC)
;; =============================================================================

(defn can-access-admin?
  "Determine if user can access the admin interface at all.

   Week 1: Requires :admin role.
   Week 2+: Configurable required role(s) from admin config.

   Args:
     user: User entity map
     admin-config: Optional admin configuration map
                   (not used in Week 1, reserved for future)

   Returns:
     Boolean true if user can access admin

   Examples:
     (can-access-admin? {:role :admin} nil)    ;=> true
     (can-access-admin? {:role :user} nil)     ;=> false
     (can-access-admin? nil nil)               ;=> false"
  ([user]
   (can-access-admin? user nil))
  ([user _admin-config]
   ; Week 1: Simple admin role check
   ; Week 2+: Check against admin-config :require-role
   (is-admin? user)))

(defn explain-admin-access-denial
  "Provide human-readable explanation for why admin access was denied.

   Args:
     user: User entity map

   Returns:
     Map with denial reason and suggested action:
     {:denied true
      :reason \"User does not have admin role\"
      :user-role :user
      :required-role :admin}

   Examples:
     (explain-admin-access-denial {:role :user})
     (explain-admin-access-denial nil)"
  [user]
  (cond
    (not user)
    {:denied true
     :reason "User not authenticated"
     :required-role :admin}

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
     :reason "Access granted"}))

;; =============================================================================
;; Entity-Level Permissions (Week 1 - Simple, Week 2+ - Granular)
;; =============================================================================

(defn can-view-entity?
  "Determine if user can view/list records from an entity.

   Week 1: Any admin can view all entities.
   Week 2+: Entity-specific view permissions from config.

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
  ([user _entity-name _entity-config]
   ; Week 1: Admin can view all entities
   ; Week 2+: Check entity-config :permissions :view
   (is-admin? user)))

(defn can-create-entity?
  "Determine if user can create new records for an entity.

   Week 1: Any admin can create in all entities.
   Week 2+: Entity-specific create permissions from config.

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
  ([user _entity-name _entity-config]
   ; Week 1: Admin can create in all entities
   ; Week 2+: Check entity-config :permissions :create
   (is-admin? user)))

(defn can-edit-entity?
  "Determine if user can edit existing records for an entity.

   Week 1: Any admin can edit all entities.
   Week 2+: Entity-specific edit permissions from config.

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
  ([user _entity-name _entity-config _record]
   ; Week 1: Admin can edit all entities
   ; Week 2+: Check entity-config :permissions :edit
   ;          Check record-level permissions (e.g., user can only edit own records)
   (is-admin? user)))

(defn can-delete-entity?
  "Determine if user can delete records from an entity.

   Week 1: Any admin can delete from all entities.
   Week 2+: Entity-specific delete permissions from config.

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
  ([user _entity-name _entity-config _record]
   ; Week 1: Admin can delete from all entities
   ; Week 2+: Check entity-config :permissions :delete
   ;          Check for protected records (e.g., system users)
   (is-admin? user)))

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

   Week 1: If user is admin, returns all entities; otherwise empty.
   Week 2+: Filter based on per-entity view permissions.

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
  ([user entity-names _entity-configs]
   (if (is-admin? user)
     (vec entity-names)
     [])))

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
     admin-config: Optional admin configuration map

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
  ([user admin-config]
   {:can-access-admin (can-access-admin? user admin-config)
    ; Week 2+ features
    :can-view-audit-log false
    :can-manage-users false}))
