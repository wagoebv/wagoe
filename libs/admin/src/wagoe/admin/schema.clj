(ns wagoe.admin.schema
  "Malli schemas for admin module configuration and entities.

   This namespace defines all schemas used by the admin module for:
   - Admin module configuration and settings
   - Entity configuration and metadata
   - Field-level configuration
   - Validation and transformation

   All schemas follow Malli specifications and include documentation
   for auto-generated validation and error messages."
  (:require
   [malli.core :as m]))

;; =============================================================================
;; Admin Configuration Schemas
;; =============================================================================

(def EntityDiscoveryMode
  "Schema for entity discovery mode configuration.

   Controls which database tables are exposed in the admin interface:
   - :allowlist - Only entities explicitly listed are accessible (most secure)
   - :denylist - All entities except those listed are accessible
   - :all - All database tables are accessible (least secure, development only)"
  [:enum {:title "Entity Discovery Mode"
          :description "Controls which entities are exposed in admin interface"}
   :allowlist
   :denylist
   :all])

(def EntityDiscoveryConfig
  "Schema for entity discovery configuration.

   Defines how the admin interface discovers and exposes database entities."
  [:map {:title "Entity Discovery Configuration"}
   [:mode EntityDiscoveryMode]
   [:allowlist {:optional true
                :description "Set of entity names to expose (used when mode is :allowlist)"}
    [:set :keyword]]
   [:denylist {:optional true
               :description "Set of entity names to hide (used when mode is :denylist)"}
    [:set :keyword]]])

(def PaginationConfig
  "Schema for pagination configuration."
  [:map {:title "Pagination Configuration"}
   [:default-page-size {:description "Default number of records per page"}
    [:int {:min 1 :max 1000}]]
   [:max-page-size {:description "Maximum allowed records per page"}
    [:int {:min 1 :max 1000}]]])

(def FieldWidget
  "Schema for field widget types.

   Determines which UI component is used to render a field in forms."
  [:enum {:title "Field Widget Type"
          :description "UI widget type for field rendering"}
   :text-input       ; Regular text input
   :email-input      ; Email-specific input with validation
   :password-input   ; Password input (hidden characters)
   :number-input     ; Numeric input
   :checkbox         ; Boolean checkbox
   :select           ; Dropdown select
   :multiselect      ; Multiple selection dropdown
   :textarea         ; Multi-line text input
   :date-input       ; Date picker
   :datetime-input   ; Date and time picker
   :file-input       ; File upload
   :color-input      ; Color picker
   :url-input        ; URL input with validation
   :hidden])         ; Hidden field (not displayed in forms)

(def FieldType
  "Schema for field data types.

   Maps database column types to logical field types."
  [:enum {:title "Field Type"
          :description "Logical data type of field"}
   :uuid            ; UUID type
   :string          ; Variable-length string
   :int             ; Integer number
   :decimal         ; Decimal number
   :boolean         ; Boolean true/false
   :instant         ; Timestamp (java.time.Instant)
   :date            ; Date only (java.time.LocalDate)
   :enum            ; Enumeration (fixed set of values)
   :json            ; JSON data
   :text            ; Long-form text
   :binary])        ; Binary data

(def FieldConfig
  "Schema for individual field configuration.

   Defines how a single entity field is displayed and validated in the admin interface."
  [:map {:title "Field Configuration"}
   [:name {:description "Field name (matches database column)"}
    :keyword]
   [:label {:optional true
            :description "Display label for UI (defaults to humanized field name)"}
    :string]
   [:type {:description "Logical data type"}
    FieldType]
   [:widget {:description "UI widget for rendering"}
    FieldWidget]
   [:required {:optional true
               :description "Whether field is required for creation/update"}
    :boolean]
   [:readonly {:optional true
               :description "Whether field can be edited"}
    :boolean]
   [:hidden {:optional true
             :description "Whether field is hidden in all views"}
    :boolean]
   [:searchable {:optional true
                 :description "Whether field can be used in search"}
    :boolean]
   [:sortable {:optional true
               :description "Whether field can be used for sorting"}
    :boolean]
   [:width {:optional true
            :description "Relative column-width weight for list view (overrides type-based default; higher = wider)"}
    [:int {:min 1}]]
   [:filterable {:optional true
                 :description "Whether field can be used in filters"}
    :boolean]
   [:default-value {:optional true
                    :description "Default value for new records"}
    :any]
   [:options {:optional true
              :description "Options for select/multiselect widgets"}
    [:vector [:tuple :keyword :string]]] ; [[:admin "Administrator"] [:user "Regular User"]]
   [:min {:optional true
          :description "Minimum value (for numbers) or length (for strings)"}
    :int]
   [:max {:optional true
          :description "Maximum value (for numbers) or length (for strings)"}
    :int]
   [:pattern {:optional true
              :description "Regex pattern for validation"}
    :string]
   [:help-text {:optional true
                :description "Help text displayed in forms"}
    :string]
   [:placeholder {:optional true
                  :description "Placeholder text for inputs"}
    :string]])

(def FieldGroupingConfig
  "Schema for field grouping configuration.

   Controls how ungrouped fields are labeled in grouped forms."
  [:map {:title "Field Grouping Configuration"
         :closed true}
   [:other-label {:optional true
                  :description "Label for the 'other' group containing ungrouped fields (default: 'Other')"}
    :string]])

(def UIConfig
  "Schema for UI-specific configuration.

   Controls visual and layout options for forms and views."
  [:map {:title "UI Configuration"
         :closed true}
   [:field-grouping {:optional true
                     :description "Field grouping display configuration"}
    FieldGroupingConfig]])

(def FieldGroup
  "Schema for a single field group.

   Defines a logical grouping of fields for forms and detail views."
  [:map {:title "Field Group"}
   [:id {:description "Unique identifier for the group"}
    :keyword]
   [:label {:description "Display label for the group header"}
    :string]
   [:fields {:description "Ordered vector of field names in this group"}
    [:vector :keyword]]])

(def EntityConfig
  "Schema for entity configuration.

   Defines how an entire entity (database table) is displayed and managed
   in the admin interface. Merges auto-detected schema with manual overrides."
  [:map {:title "Entity Configuration"}
   [:label {:description "Display label for entity (e.g., 'Users', 'Products')"}
    :string]
   [:table-name {:description "Database table name"}
    :keyword]
   [:primary-key {:optional true
                  :description "Primary key field name (defaults to :id)"}
    :keyword]
   [:list-fields {:optional true
                  :description "Fields to show in list view (defaults to all except hidden)"}
    [:vector :keyword]]
   [:detail-fields {:optional true
                    :description "Fields to show in detail view (defaults to all except hidden)"}
    [:vector :keyword]]
   [:search-fields {:optional true
                    :description "Fields that can be searched"}
    [:vector :keyword]]
   [:editable-fields {:optional true
                      :description "Fields that can be edited (defaults to non-readonly fields)"}
    [:vector :keyword]]
   [:hide-fields {:optional true
                  :description "Fields to completely hide (e.g., password-hash, deleted-at)"}
    [:set :keyword]]
   [:readonly-fields {:optional true
                      :description "Fields that cannot be edited (e.g., id, created-at)"}
    [:set :keyword]]
   [:fields {:optional true
             :description "Field-specific configuration overrides"}
    [:map-of :keyword FieldConfig]]
   [:field-order {:optional true
                  :description "Preferred ordering of fields in forms and detail views"}
    [:vector :keyword]]
   [:field-groups {:optional true
                   :description "Logical groupings of fields for forms and detail views"}
    [:vector FieldGroup]]
   [:default-sort {:optional true
                   :description "Default sort field"}
    :keyword]
   [:default-sort-dir {:optional true
                       :description "Default sort direction"}
    [:enum :asc :desc]]
   [:icon {:optional true
           :description "Icon identifier for UI"}
    :string]
   [:description {:optional true
                  :description "Entity description for documentation"}
    :string]
   [:soft-delete {:optional true
                  :description "Whether entity supports soft delete (has deleted-at column)"}
    :boolean]
   [:ui {:optional true
         :description "UI-specific configuration overrides for this entity"}
    UIConfig]])

(def AdminConfig
  "Schema for complete admin module configuration.

   Top-level configuration that controls all aspects of the admin interface."
  [:map {:title "Admin Configuration"}
   [:enabled? {:description "Whether admin interface is enabled"}
    :boolean]
   [:base-path {:description "Base URL path for admin interface (e.g., '/web/admin')"}
    :string]
   [:require-role {:description "User role required to access admin (e.g., :admin)"}
    [:enum :admin :user :viewer]]
   [:entity-discovery {:description "Entity discovery and filtering configuration"}
    EntityDiscoveryConfig]
   [:entities {:optional true
               :description "Entity-specific configuration overrides"}
    [:map-of :keyword EntityConfig]]
   [:pagination {:description "Pagination settings"}
    PaginationConfig]
   [:ui {:optional true
         :description "Global UI configuration (can be overridden per-entity)"}
    UIConfig]
   [:theme {:optional true
            :description "UI theme configuration"}
    [:map {:closed true}
     [:name {:optional true} :string]
     [:primary-color {:optional true} :string]
     [:logo-url {:optional true} :string]]]
   [:features {:optional true
               :description "Feature flags for admin functionality"}
    [:map {:closed true}
     [:bulk-actions {:optional true} :boolean]
     [:export-csv {:optional true} :boolean]
     [:import-csv {:optional true} :boolean]
     [:audit-log {:optional true} :boolean]
     [:custom-actions {:optional true} :boolean]]]])

;; =============================================================================
;; Query Parameter Schemas
;; =============================================================================

(def FilterOperator
  "Schema for advanced filter operators (Week 2).

   Operators for field-specific filtering with type-appropriate operations."
  [:enum {:title "Filter Operator"
          :description "Comparison operator for field filtering"}
   :eq          ; Equal (=)
   :ne          ; Not equal (!=)
   :gt          ; Greater than (>)
   :gte         ; Greater than or equal (>=)
   :lt          ; Less than (<)
   :lte         ; Less than or equal (<=)
   :contains    ; String contains (LIKE %value%)
   :starts-with ; String starts with (LIKE value%)
   :ends-with   ; String ends with (LIKE %value)
   :in          ; Value in list (IN (...))
   :not-in      ; Value not in list (NOT IN (...))
   :is-null     ; Field is NULL
   :is-not-null ; Field is not NULL
   :between])   ; Value between two values (BETWEEN x AND y)

(def FieldFilter
  "Schema for a single field filter (Week 2).

   Represents a filter on one field with an operator and value(s)."
  [:map {:title "Field Filter"}
   [:op {:description "Filter operator"}
    FilterOperator]
   [:value {:optional true
            :description "Filter value (not needed for is-null/is-not-null)"}
    :any]
   [:values {:optional true
             :description "Multiple values (for :in, :not-in operators)"}
    [:vector :any]]
   [:min {:optional true
          :description "Minimum value (for :between operator)"}
    :any]
   [:max {:optional true
          :description "Maximum value (for :between operator)"}
    :any]])

(def ListEntitiesOptions
  "Schema for list entities query parameters."
  [:map {:title "List Entities Options"}
   [:limit {:optional true
            :description "Maximum records per page"}
    [:int {:min 1 :max 1000}]]
   [:offset {:optional true
             :description "Number of records to skip"}
    [:int {:min 0}]]
   [:page {:optional true
           :description "Page number (1-indexed)"}
    [:int {:min 1}]]
   [:sort {:optional true
           :description "Field to sort by"}
    :keyword]
   [:sort-dir {:optional true
               :description "Sort direction"}
    [:enum :asc :desc]]
   [:search {:optional true
             :description "Search term (simple LIKE search)"}
    [:string {:min 1}]]
   [:filters {:optional true
              :description "Advanced field-specific filters (Week 2)"}
    [:map-of :keyword FieldFilter]]])

;; =============================================================================
;; Action Schemas
;; =============================================================================

(def ActionParameter
  "Schema for custom action parameter definition."
  [:map {:title "Action Parameter"}
   [:name :keyword]
   [:label :string]
   [:type FieldType]
   [:required :boolean]
   [:default-value {:optional true} :any]])

(def ActionConfig
  "Schema for custom action configuration."
  [:map {:title "Action Configuration"}
   [:name :keyword]
   [:label :string]
   [:description {:optional true} :string]
   [:icon {:optional true} :string]
   [:bulk? {:description "Whether action can be applied to multiple records"}
    :boolean]
   [:requires-confirmation? {:description "Whether action requires user confirmation"}
    :boolean]
   [:parameters {:optional true
                 :description "Action-specific parameters"}
    [:vector ActionParameter]]
   [:handler {:description "Action handler function (not validated by schema)"}
    :any]]) ; Function, validated at runtime

;; =============================================================================
;; Compiled Validators (compiled once at load time for performance)
;; =============================================================================

(def ^:private admin-config-validator (m/validator AdminConfig))
(def ^:private admin-config-explainer (m/explainer AdminConfig))
(def ^:private entity-config-validator (m/validator EntityConfig))
(def ^:private entity-config-explainer (m/explainer EntityConfig))
(def ^:private list-entities-options-validator (m/validator ListEntitiesOptions))
(def ^:private list-entities-options-explainer (m/explainer ListEntitiesOptions))

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn validate-admin-config
  "Validate admin configuration against schema.

   Args:
     config: Admin configuration map

   Returns:
     {:valid? true :data transformed-data} or
     {:valid? false :errors validation-errors}

   Example:
     (validate-admin-config {:enabled? true :base-path \"/admin\" ...})"
  [config]
  (if (admin-config-validator config)
    {:valid? true :data config}
    {:valid? false :errors (admin-config-explainer config)}))

(defn validate-entity-config
  "Validate entity configuration against schema.

   Args:
     config: Entity configuration map

   Returns:
     {:valid? true :data transformed-data} or
     {:valid? false :errors validation-errors}

   Example:
     (validate-entity-config {:label \"Users\" :table-name :users ...})"
  [config]
  (if (entity-config-validator config)
    {:valid? true :data config}
    {:valid? false :errors (entity-config-explainer config)}))

(defn validate-list-options
  "Validate list entities options against schema.

   Args:
     options: Query options map

   Returns:
     {:valid? true :data transformed-data} or
     {:valid? false :errors validation-errors}

   Example:
     (validate-list-options {:limit 50 :offset 0 :sort :email})"
  [options]
  (if (list-entities-options-validator options)
    {:valid? true :data options}
    {:valid? false :errors (list-entities-options-explainer options)}))

;; =============================================================================
;; Default Configurations
;; =============================================================================

(def default-admin-config
  "Default admin configuration values."
  {:enabled? false
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{}}
   :pagination {:default-page-size 50
                :max-page-size 200}
   :ui {:field-grouping {:other-label "Other"}}
   :features {:bulk-actions true
              :export-csv false
              :import-csv false
              :audit-log true
              :custom-actions false}})

(def default-field-widgets
  "Default widget mappings for field types."
  {:uuid :text-input
   :string :text-input
   :int :number-input
   :decimal :number-input
   :boolean :checkbox
   :instant :datetime-input
   :date :date-input
   :enum :select
   :json :textarea
   :text :textarea
   :binary :file-input})

(defn get-default-widget
  "Get default widget for field type.

   Args:
     field-type: Keyword field type

   Returns:
     Keyword widget type

   Example:
     (get-default-widget :string)  ;=> :text-input
     (get-default-widget :boolean) ;=> :checkbox"
  [field-type]
  (get default-field-widgets field-type :text-input))
