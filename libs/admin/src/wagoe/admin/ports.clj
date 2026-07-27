(ns wagoe.admin.ports
  "Admin module port definitions (abstract interfaces).

   This namespace defines all ports (abstract interfaces) that the admin module
   needs to provide auto-generated CRUD interfaces and administrative operations.
   These ports follow Boundary's hexagonal architecture pattern, allowing core
   business logic to remain pure and testable while enabling flexible adapter
   implementations.

   Port Categories:
   - Schema Introspection Ports (ISchemaProvider)
   - Admin Service Ports (IAdminService)
   - Action Executor Ports (IActionExecutor)

   Each port is implemented by adapters in the shell layer, enabling dependency
   inversion and supporting multiple database backends (PostgreSQL, SQLite, MySQL, H2).")

;; =============================================================================
;; Schema Introspection Ports
;; =============================================================================

(defprotocol ISchemaProvider
  "Database schema introspection and entity configuration interface.

   This port abstracts database schema discovery and entity metadata, supporting:
   - Database-agnostic schema introspection
   - Entity configuration management (auto-detected + manual overrides)
   - Table and column metadata extraction
   - Entity discovery and filtering (allowlist/denylist)

   The schema provider bridges the gap between raw database metadata and
   UI-friendly entity configurations used by the admin interface."

  (fetch-table-metadata [this table-name]
    "Retrieve raw database metadata for a table.

     Args:
       table-name: Keyword or string name of the table to introspect

     Returns:
       Vector of column metadata maps:
       [{:name \"id\"
         :type \"uuid\"
         :not-null true
         :default nil
         :primary-key true}
        {:name \"email\"
         :type \"varchar\"
         :not-null true
         :default nil
         :primary-key false}]

     Throws:
       - ExceptionInfo with :type :table-not-found if table doesn't exist

     Example:
       (fetch-table-metadata provider :users)")

  (get-entity-config [this entity-name]
    "Get complete entity configuration for admin UI generation.

     Merges auto-detected database schema with manual configuration overrides
     to produce a complete entity configuration ready for UI rendering.

     Args:
       entity-name: Keyword entity name (typically matches table name)

     Returns:
       Entity configuration map:
       {:label \"Users\"
        :table-name :users
        :primary-key :id
        :fields {:id {:type :uuid :widget :text-input :readonly true}
                 :email {:type :string :widget :email-input :required true}
                 :role {:type :enum :widget :select :options [:admin :user]}
                 :active {:type :boolean :widget :checkbox}}
        :list-fields [:email :name :role :active :created-at]
        :search-fields [:email :name]
        :editable-fields [:name :email :role :active]
        :hide-fields #{:password-hash :deleted-at}
        :readonly-fields #{:id :created-at :updated-at}}

     Example:
       (get-entity-config provider :users)")

  (list-available-entities [this]
    "List all entities available in the admin interface.

     Filters entities based on discovery mode (allowlist, denylist, or all)
     configured in the admin module settings.

     Returns:
       Set of keyword entity names that should be accessible:
       #{:users :items :orders}

     Example:
       (list-available-entities provider)")

  (get-entity-label [this entity-name]
    "Get display label for an entity.

     Args:
       entity-name: Keyword entity name

     Returns:
       String display label for UI (e.g., \"Users\" for :users)

     Example:
       (get-entity-label provider :users)  ;=> \"Users\"")

  (validate-entity-exists [this entity-name]
    "Check if entity is valid and accessible.

     Args:
       entity-name: Keyword entity name to validate

     Returns:
       Boolean indicating if entity exists and is accessible in admin

     Example:
       (validate-entity-exists provider :users)  ;=> true"))

;; =============================================================================
;; Admin Service Ports
;; =============================================================================

(defprotocol IAdminService
  "Admin CRUD operations service interface.

   This port defines the service layer interface for generic CRUD operations
   on any entity managed by the admin interface. The service layer coordinates
   between pure business logic (core) and I/O operations (repositories),
   providing database-agnostic admin operations.

   Service layer responsibilities:
   - Execute CRUD operations on arbitrary entities
   - Handle pagination, filtering, and sorting
   - Enforce business rules and validation
   - Manage transaction boundaries
   - Provide observability (logging, metrics, errors)"

  (list-entities [this entity-name options]
    "List entities with pagination, filtering, and sorting.

     Retrieves paginated list of records from the specified entity table
     with support for search, filtering, and sorting.

     Args:
       entity-name: Keyword entity name (e.g., :users, :items)
       options: Map with query options:
                {:limit 50                     ; Max records per page
                 :offset 0                     ; Skip first N records
                 :sort :email                  ; Field to sort by
                 :sort-dir :asc                ; :asc or :desc
                 :search \"john\"                ; Search term
                 :filters {:role :admin        ; Field-specific filters
                          :active true}}

     Returns:
       Map with records and pagination metadata:
       {:records [{:id ... :email ... :name ...}
                  {:id ... :email ... :name ...}]
        :total-count 150
        :page-size 50
        :page-number 1
        :total-pages 3}

     Throws:
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist
       - ExceptionInfo with :type :invalid-sort-field if sort field invalid

     Example:
       (list-entities service :users {:limit 20 :sort :email :sort-dir :asc})")

  (get-entity [this entity-name id]
    "Retrieve single entity record by ID.

     Args:
       entity-name: Keyword entity name
       id: Record ID (UUID, integer, or string depending on entity)

     Returns:
       Entity record map or nil if not found
       {:id uuid :email \"user@example.com\" :name \"John\" ...}

     Throws:
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist

     Example:
       (get-entity service :users \"550e8400-e29b-41d4-a716-446655440000\")")

  (create-entity [this entity-name data]
    "Create new entity record with validation.

     Validates input data against entity schema, applies business rules,
     and creates new record with auto-generated ID and timestamps.

     Args:
       entity-name: Keyword entity name
       data: Map with entity data (ID and timestamps auto-generated)
             {:email \"new@example.com\"
              :name \"New User\"
              :role :user
              :active true}

     Returns:
       Created entity record with generated ID and timestamps
       {:id uuid :email \"new@example.com\" :created-at instant ...}

     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist
       - ExceptionInfo with :type :duplicate-key for unique constraint violations

     Example:
       (create-entity service :users {:email \"new@example.com\"
                                      :name \"New User\"
                                      :role :user})")

  (update-entity [this entity-name id data]
    "Update existing entity record with validation.

     Validates input data, applies business rules, and updates record.
     Automatically sets updated-at timestamp.

     Args:
       entity-name: Keyword entity name
       id: Record ID to update
       data: Map with fields to update (partial update supported)
             {:name \"Updated Name\"
              :active false}

     Returns:
       Updated entity record with new updated-at timestamp

     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist
       - ExceptionInfo with :type :record-not-found if ID doesn't exist
       - ExceptionInfo with :type :readonly-field if trying to update readonly field

      Example:
        (update-entity service :users user-id {:name \"Updated Name\"})")

  (update-entity-field [this entity-name id field value]
    "Update a single field on an entity (for inline editing).

     Validates the single field value and updates only that field.
     More efficient than full entity update for inline editing use cases.

     Args:
       entity-name: Keyword entity name
       id: Record ID to update
       field: Keyword field name to update
       value: New value for the field

     Returns:
       Updated entity record

     Throws:
       - ExceptionInfo with :type :validation-error for invalid field value
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist
       - ExceptionInfo with :type :record-not-found if ID doesn't exist
       - ExceptionInfo with :type :readonly-field if field is readonly

     Example:
       (update-entity-field service :users user-id :name \"New Name\")")

  (delete-entity [this entity-name id]
    "Delete entity record (soft or hard based on schema).

     Performs soft delete (sets deleted-at) if entity has deleted-at column,
     otherwise performs hard delete (permanent removal).

     Args:
       entity-name: Keyword entity name
       id: Record ID to delete

     Returns:
       Boolean indicating success

     Throws:
       - ExceptionInfo with :type :entity-not-found if entity doesn't exist
       - ExceptionInfo with :type :record-not-found if ID doesn't exist
       - ExceptionInfo with :type :deletion-not-allowed for protected records

     Example:
       (delete-entity service :users user-id)")

  (count-entities [this entity-name filters]
    "Count entities matching filters (for pagination).

     Args:
       entity-name: Keyword entity name
       filters: Optional map with filter criteria
                {:role :admin :active true}

     Returns:
       Integer count of matching records

     Example:
       (count-entities service :users {:active true})")

  (validate-entity-data [this entity-name data]
    "Validate entity data without persisting.

     Useful for client-side validation, preview, or testing.

     Args:
       entity-name: Keyword entity name
       data: Map with entity data to validate

     Returns:
       Validation result map:
       {:valid? true :data transformed-data} or
       {:valid? false :errors {:email [\"Invalid format\"]}}

     Example:
       (validate-entity-data service :users {:email \"invalid-email\"})")

  (bulk-delete-entities [this entity-name ids]
    "Delete multiple entity records in single transaction.

     Args:
       entity-name: Keyword entity name
       ids: Vector of record IDs to delete

     Returns:
       Map with deletion results:
       {:success-count 5 :failed-count 0 :errors []}

     Example:
       (bulk-delete-entities service :users [id1 id2 id3])")

  (list-related-entities [this parent-entity-name parent-id relationship]
    "Fetch related records for a has-many relationship.

     Args:
       parent-entity-name: keyword — the owning entity (e.g. :orders)
       parent-id:          string  — ID of the parent record
       relationship:       map     — {:entity :order-items :table :order_items
                                      :foreign-key :order-id :label \"...\" :fields [...]}
     Returns:
       Vector of records (kebab-case keys) or []"))

;; =============================================================================
;; Action Executor Ports
;; =============================================================================

(defprotocol IActionExecutor
  "Custom action executor for entity-specific operations.

   This port enables extensibility through custom actions beyond basic CRUD.
   Actions can be registered for specific entities and executed through the
   admin interface (e.g., 'Send Welcome Email', 'Export to CSV', 'Publish Draft').

   Week 1: Stub protocol for future implementation
   Week 2+: Full implementation with action registry and execution"

  (execute-action [this action-name entity-name ids data]
    "Execute custom action on one or more entity records.

     Args:
       action-name: Keyword action identifier (e.g., :send-email, :export-csv)
       entity-name: Keyword entity name
       ids: Vector of record IDs to apply action to
       data: Optional map with action-specific parameters

     Returns:
       Action result map:
       {:success? true
        :message \"Action completed successfully\"
        :affected-count 3
        :results [...]}

     Throws:
       - ExceptionInfo with :type :action-not-found if action doesn't exist
       - ExceptionInfo with :type :action-not-allowed for permission issues

     Example:
       (execute-action executor :send-welcome-email :users [user-id] {})")

  (list-available-actions [this entity-name]
    "List available custom actions for an entity.

     Args:
       entity-name: Keyword entity name

     Returns:
       Vector of action metadata maps:
       [{:name :send-welcome-email
         :label \"Send Welcome Email\"
         :description \"Send welcome email to selected users\"
         :bulk? true
         :requires-confirmation? false
         :parameters [{:name :template-id :type :string}]}]

     Example:
       (list-available-actions executor :users)")

  (register-action [this entity-name action-config]
    "Register custom action for an entity.

     Args:
       entity-name: Keyword entity name
       action-config: Action configuration map with handler function

     Returns:
       Boolean indicating success

     Example:
       (register-action executor :users
         {:name :send-welcome-email
          :label \"Send Welcome Email\"
          :handler (fn [ids data] ...)})")

  (unregister-action [this entity-name action-name]
    "Remove custom action registration.

     Args:
       entity-name: Keyword entity name
       action-name: Keyword action identifier

     Returns:
       Boolean indicating success

     Example:
       (unregister-action executor :users :send-welcome-email)"))
