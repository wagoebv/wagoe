(ns wagoe.admin.core.schema-introspection
  "Pure functions for database schema introspection and entity configuration.

   This namespace contains pure business logic for transforming raw database
   metadata into UI-friendly entity configurations. All functions are pure
   (no side effects) and testable without database connections.

   Key responsibilities:
   - Parse database column metadata into field configurations
   - Infer appropriate UI widgets from database types
   - Detect relationships and foreign keys
   - Merge auto-detected configuration with manual overrides
   - Generate sensible defaults for labels and field ordering"
  (:require
   [clojure.string :as str]
   [wagoe.core.utils.case-conversion :as case-conversion]))

;; =============================================================================
;; Type Mapping - SQL Types to Logical Field Types
;; =============================================================================

(def sql-type->field-type
  "Mapping from SQL/database types to logical field types.

   Keys are lowercase type names as strings (normalized from database).
   Values are field type keywords used in entity configuration."
  {"uuid" :uuid
   "char(36)" :uuid           ; UUID as string in some databases
   "varchar" :string
   "character varying" :string
   "text" :text
   "longtext" :text
   "mediumtext" :text
   "integer" :int
   "int" :int
   "bigint" :int
   "smallint" :int
   "tinyint" :int
   "serial" :int
   "bigserial" :int
   "decimal" :decimal
   "numeric" :decimal
   "real" :decimal
   "double" :decimal
   "double precision" :decimal
   "float" :decimal
   "money" :decimal
   "boolean" :boolean
   "bool" :boolean
   "bit" :boolean
   "timestamp" :instant
   "timestamp without time zone" :instant
   "timestamp with time zone" :instant
   "timestamptz" :instant
   "datetime" :instant
   "date" :date
   "time" :time
   "json" :json
   "jsonb" :json
   "clob" :json
   "blob" :binary
   "bytea" :binary
   "binary" :binary})

(defn normalize-sql-type
  "Normalize SQL type string to lowercase without size/precision.

   Args:
     sql-type: String SQL type (e.g., 'VARCHAR(255)', 'DECIMAL(10,2)')

   Returns:
     Normalized lowercase type string without size

   Examples:
     (normalize-sql-type \"VARCHAR(255)\")      ;=> \"varchar\"
     (normalize-sql-type \"DECIMAL(10,2)\")    ;=> \"decimal\"
     (normalize-sql-type \"INTEGER\")          ;=> \"integer\"
     (normalize-sql-type \"TIMESTAMP\")        ;=> \"timestamp\""
  [sql-type]
  (when sql-type
    (-> sql-type
        str/lower-case
        (str/replace #"\(.*\)" "")  ; Remove size/precision
        str/trim)))

(defn infer-field-type
  "Infer logical field type from SQL/database type.

   Args:
     sql-type: String SQL type from database metadata
     field-name: (Optional) Keyword field name for heuristics

   Returns:
     Keyword field type (:uuid, :string, :int, etc.)
     Defaults to :string if type is not recognized

   Examples:
     (infer-field-type \"VARCHAR(255)\")      ;=> :string
     (infer-field-type \"INTEGER\")          ;=> :int
     (infer-field-type \"BOOLEAN\")          ;=> :boolean
     (infer-field-type \"TIMESTAMP\")        ;=> :instant
     (infer-field-type \"TEXT\" :created-at) ;=> :instant (heuristic)
     (infer-field-type \"TEXT\" :email)      ;=> :string (heuristic)
     (infer-field-type \"UNKNOWN_TYPE\")     ;=> :string"
  ([sql-type] (infer-field-type sql-type nil))
  ([sql-type field-name]
   (let [normalized (normalize-sql-type sql-type)
         base-type (get sql-type->field-type normalized :string)]
     ;; Apply heuristics if field is text type
     (if (and (= base-type :text) field-name)
       (let [name-str (name field-name)]
         (cond
           ;; Timestamp fields stored as text (ISO 8601 strings)
           (or (str/ends-with? name-str "-at")
               (str/ends-with? name-str "-until")
               (str/includes? name-str "login")
               (str/includes? name-str "timestamp"))
           :instant

           ;; Email fields
           (or (str/includes? name-str "email")
               (str/includes? name-str "mail"))
           :string

           ;; Role/status/enum-like fields
           (or (str/includes? name-str "role")
               (str/includes? name-str "status")
               (str/includes? name-str "type")
               (str/includes? name-str "format"))
           :string

           ;; Default: keep as text for very long content
           :else :text))
       base-type))))

;; =============================================================================
;; Widget Inference - Field Types to UI Widgets
;; =============================================================================

(defn infer-widget-for-field
  "Infer appropriate UI widget for a field based on its characteristics.

   Args:
     field-name: Keyword field name (used for heuristics)
     field-type: Keyword field type (:uuid, :string, :int, etc.)
     sql-type: Original SQL type string (for additional context)

   Returns:
     Keyword widget type (:text-input, :email-input, :checkbox, etc.)

   Examples:
     (infer-widget-for-field :email :string \"VARCHAR\")     ;=> :email-input
     (infer-widget-for-field :password :string \"VARCHAR\")  ;=> :password-input
     (infer-widget-for-field :active :boolean \"BOOLEAN\")   ;=> :checkbox
      (infer-widget-for-field :created-at :instant \"TIMESTAMP\") ;=> :datetime-input"
  [field-name field-type _sql-type]
  (if-not field-name
    :text-input
    (let [field-name-str (name field-name)
          field-name-lower (str/lower-case field-name-str)]
      (cond
        ; Special field name heuristics
        (str/includes? field-name-lower "email") :email-input
        (str/includes? field-name-lower "password") :password-input
        (or (str/includes? field-name-lower "url")
            (str/includes? field-name-lower "website")) :url-input
        (str/includes? field-name-lower "color") :color-input
        (str/includes? field-name-lower "description") :textarea
        (str/includes? field-name-lower "bio") :textarea
        (str/includes? field-name-lower "notes") :textarea
        (str/includes? field-name-lower "content") :textarea
        (and (str/ends-with? field-name-lower "-date")
             (not (str/includes? field-name-lower "format"))) :date-input

        ; Type-based widget selection
        (= field-type :uuid) :text-input
        (= field-type :string) :text-input
        (= field-type :text) :textarea
        (= field-type :int) :number-input
        (= field-type :decimal) :number-input
        (= field-type :boolean) :checkbox
        (= field-type :instant) :datetime-input
        (= field-type :date) :date-input
        (= field-type :enum) :select
        (= field-type :json) :textarea
        (= field-type :binary) :file-input

        ; Default fallback
        :else :text-input))))

;; =============================================================================
;; Field Classification - Readonly, Hidden, Required
;; =============================================================================

(def common-readonly-fields
  "Set of field names that are typically readonly (in kebab-case)."
  #{:id :created-at :updated-at :created-by :updated-by
    :deleted-at :deleted-by :version :revision})

(def common-hidden-fields
  "Set of field names that are typically hidden in admin UI (in kebab-case)."
  #{:password-hash :password-encrypted :secret :token
    :api-key :private-key :salt :hash
    :search-vector :tsv :fts-vector})

(defn should-be-readonly?
  "Determine if field should be readonly based on characteristics.

   Args:
     field-name: Keyword field name
     is-primary-key?: Boolean indicating if field is primary key

   Returns:
     Boolean true if field should be readonly

   Examples:
     (should-be-readonly? :id true)          ;=> true
     (should-be-readonly? :created-at false) ;=> true
     (should-be-readonly? :name false)       ;=> false"
  [field-name is-primary-key?]
  (or is-primary-key?
      (contains? common-readonly-fields field-name)))

(defn should-be-hidden?
  "Determine if field should be hidden in admin UI.

   Args:
     field-name: Keyword field name

   Returns:
     Boolean true if field should be hidden

   Examples:
     (should-be-hidden? :password-hash) ;=> true
     (should-be-hidden? :email)         ;=> false"
  [field-name]
  (contains? common-hidden-fields field-name))

(defn should-be-searchable?
  "Determine if field should be searchable.

   Args:
     field-type: Keyword field type
     field-name: Keyword field name

   Returns:
     Boolean true if field should be searchable

   Examples:
     (should-be-searchable? :string :email) ;=> true
     (should-be-searchable? :text :description) ;=> true
     (should-be-searchable? :binary :avatar) ;=> false"
  [field-type field-name]
  (and (contains? #{:string :text} field-type)
       (not (should-be-hidden? field-name))))

(defn should-be-sortable?
  "Determine if field should be sortable.

   Args:
     field-type: Keyword field type

   Returns:
     Boolean true if field should be sortable

   Examples:
     (should-be-sortable? :string) ;=> true
     (should-be-sortable? :int)    ;=> true
     (should-be-sortable? :json)   ;=> false"
  [field-type]
  (contains? #{:uuid :string :text :int :decimal :boolean :instant :date :enum} field-type))

(defn should-be-in-list-view?
  "Determine if field should be shown in table list view.
   
   Some fields are better shown only in detail/edit views, not in the
   compact table list view.
   
   Args:
     field-name: Keyword field name
     field-type: Keyword field type
     
   Returns:
     Boolean true if field should be in list view
     
   Examples:
     (should-be-in-list-view? :email :string)   ;=> true
     (should-be-in-list-view? :active :boolean) ;=> false
     (should-be-in-list-view? :notes :text)     ;=> false"
  [field-name field-type]
  (let [name-str (name field-name)]
    (not (or
          ;; Boolean flags that are better as badges or in detail view
          (and (= field-type :boolean)
               (or (= field-name :active)
                   (str/starts-with? name-str "is-")
                   (str/starts-with? name-str "has-")
                   (str/starts-with? name-str "send-")))

          ;; Very long text fields
          (and (= field-type :text)
               (or (str/includes? name-str "description")
                   (str/includes? name-str "content")
                   (str/includes? name-str "notes")
                   (str/includes? name-str "body")))

          ;; Technical fields
          (str/includes? name-str "hash")
          (str/includes? name-str "secret")
          (str/includes? name-str "token")
          (str/includes? name-str "backup-codes")))))

;; =============================================================================
;; Label Generation - Field Names to Display Labels
;; =============================================================================

(defn humanize-field-name
  "Convert field name to human-readable label.

   Converts kebab-case or snake_case to Title Case.

   Args:
     field-name: Keyword field name

   Returns:
     String display label

   Examples:
     (humanize-field-name :email)        ;=> \"Email\"
     (humanize-field-name :first-name)   ;=> \"First Name\"
     (humanize-field-name :created_at)   ;=> \"Created At\"
     (humanize-field-name :mfa-enabled)  ;=> \"Mfa Enabled\""
  [field-name]
  (-> (name field-name)
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn humanize-entity-name
  "Convert entity name to human-readable label (pluralized).

   Args:
     entity-name: Keyword entity name

   Returns:
     String display label

   Examples:
     (humanize-entity-name :user)    ;=> \"Users\"
     (humanize-entity-name :item)    ;=> \"Items\"
     (humanize-entity-name :category) ;=> \"Categories\""
  [entity-name]
  (let [base-name (name entity-name)
        humanized (-> base-name
                      (str/replace #"[-_]" " ")
                      str/capitalize)]
    ; Simple pluralization (can be improved with inflection library)
    (cond
      (str/ends-with? humanized "y") (str (subs humanized 0 (dec (count humanized))) "ies")
      (str/ends-with? humanized "s") humanized
      :else (str humanized "s"))))

;; =============================================================================
;; Core Parsing - Database Metadata to Field Configurations
;; =============================================================================

(defn parse-column-metadata
  "Parse single database column into field configuration.

   Args:
     column-meta: Map with column metadata from database:
                  {:name \"email\"
                   :type \"varchar\"
                   :not-null true
                   :default nil
                   :primary-key false}

   Returns:
     Field configuration map:
     {:name :email
      :label \"Email\"
      :type :string
      :widget :email-input
      :required true
      :readonly false
      :hidden false
      :searchable true
      :sortable true
      :filterable true}

   Example:
     (parse-column-metadata {:name \"email\"
                            :type \"VARCHAR(255)\"
                            :not-null true
                            :primary-key false})"
  [column-meta]
  (let [;; Convert database column name (snake_case) to internal field name (kebab-case)
        field-name (-> (:name column-meta)
                       case-conversion/snake-case->kebab-case-string
                       keyword)
        sql-type (:type column-meta)
        field-type (infer-field-type sql-type field-name)  ; Pass field-name for heuristics
        widget (infer-widget-for-field field-name field-type sql-type)
        is-primary-key? (:primary-key column-meta false)
        is-not-null? (:not-null column-meta false)]
    {:name field-name
     :label (humanize-field-name field-name)
     :type field-type
     :widget widget
     :required (and is-not-null? (not is-primary-key?))
     :readonly (should-be-readonly? field-name is-primary-key?)
     :hidden (or (should-be-hidden? field-name)
                 (str/includes? (str/lower-case (str sql-type)) "tsvector"))
     :searchable (should-be-searchable? field-type field-name)
     :sortable (should-be-sortable? field-type)
     :filterable (should-be-sortable? field-type)
     :primary-key is-primary-key?
     :default-value (:default column-meta)}))

(defn parse-table-metadata
  "Parse database table metadata into entity configuration.

   Takes raw database column metadata and produces a complete entity
   configuration with sensible defaults for all fields.

   Args:
     table-name: Keyword table name
     columns-meta: Vector of column metadata maps from database

   Returns:
     Entity configuration map with:
     - Field configurations for all columns
     - Default field lists (list, detail, editable, etc.)
     - Primary key identification
     - Auto-generated entity label

   Example:
     (parse-table-metadata :users
       [{:name \"id\" :type \"UUID\" :primary-key true}
        {:name \"email\" :type \"VARCHAR(255)\" :not-null true}
        {:name \"created_at\" :type \"TIMESTAMP\" :not-null true}])"
  [table-name columns-meta]
  (let [fields (mapv parse-column-metadata columns-meta)
        fields-by-name (into {} (map (juxt :name identity) fields))
        primary-key (->> fields
                         (filter :primary-key)
                         first
                         :name
                         (or :id))
        visible-fields (->> fields
                            (remove :hidden)
                            (mapv :name))
        readonly-field-names (->> fields
                                  (filter :readonly)
                                  (map :name)
                                  set)
        hidden-field-names (->> fields
                                (filter :hidden)
                                (map :name)
                                set)
        editable-fields (->> visible-fields
                             (remove readonly-field-names)
                             vec)
        search-fields (->> fields
                           (filter :searchable)
                           (mapv :name))
        list-fields (->> visible-fields
                         (filter (fn [field-name]
                                   (let [field-config (get fields-by-name field-name)]
                                     (should-be-in-list-view? field-name (:type field-config)))))
                         (take 5)  ; Default to first 5 suitable fields
                         vec)]
    {:label (humanize-entity-name table-name)
     :table-name table-name
     :primary-key primary-key
     :fields fields-by-name
     :list-fields list-fields
     :detail-fields visible-fields
     :search-fields search-fields
     :editable-fields editable-fields
     :hide-fields hidden-field-names
     :readonly-fields readonly-field-names
     :default-sort primary-key
     :default-sort-dir :desc
     :soft-delete (contains? fields-by-name :deleted-at)}))

;; =============================================================================
;; Configuration Merging - Auto-detected + Manual Overrides
;; =============================================================================

(defn merge-field-config
  "Merge auto-detected field config with manual overrides.

   Args:
     auto-config: Field configuration from schema introspection
     manual-config: Manual overrides map (can be nil)

   Returns:
     Merged field configuration with manual overrides applied

   Example:
     (merge-field-config {:name :email :widget :text-input}
                        {:widget :email-input :required true})"
  [auto-config manual-config]
  (if manual-config
    (merge auto-config manual-config)
    auto-config))

(defn merge-fields-config
  "Merge all field configurations with manual overrides.

   Args:
     auto-fields: Map of field-name -> auto-detected field config
     manual-fields: Map of field-name -> manual field config (can be nil)

   Returns:
     Merged fields map

   Example:
     (merge-fields-config {:email {...} :name {...}}
                         {:email {:widget :email-input}})"
  [auto-fields manual-fields]
  (if manual-fields
    (let [;; Merge manual overrides into every auto-detected field
          merged-auto (into {}
                            (for [[field-name auto-config] auto-fields]
                              [field-name (merge-field-config auto-config (get manual-fields field-name))]))
          ;; Also include fields that only exist in the manual config (e.g. cross-table
          ;; fields from :query-overrides JOINs that aren't in the primary table).
          manual-only (into {}
                            (for [[field-name manual-config] manual-fields
                                  :when (not (contains? auto-fields field-name))]
                              [field-name manual-config]))]
      (merge merged-auto manual-only))
    auto-fields))

(defn build-entity-config
  "Build complete entity configuration by merging auto-detected with manual.

   This is the main function that combines schema introspection results
   with user-provided configuration overrides.

   Args:
     auto-config: Entity configuration from parse-table-metadata
     manual-config: Manual configuration overrides (can be nil or partial)

   Returns:
     Complete merged entity configuration

   Example:
     (build-entity-config
       {:label \"Users\" :fields {...} :list-fields [...]}
       {:label \"System Users\" :list-fields [:email :name :role]})"
  [auto-config manual-config]
  (if manual-config
    (-> auto-config
        (merge (dissoc manual-config :fields))  ; Merge all except :fields
        (assoc :fields (merge-fields-config
                        (:fields auto-config)
                        (:fields manual-config))))
    auto-config))

;; =============================================================================
;; Field Ordering
;; =============================================================================

(defn apply-field-order
  "Apply preferred field ordering to a vector of fields.

   Uses stable sorting: fields in :field-order come first (in that order),
   remaining fields are appended in their original order.

   Args:
     fields: Vector of field keywords to reorder
     field-order: Optional vector of preferred field order

   Returns:
     Reordered vector of field keywords

   Example:
     (apply-field-order [:c :a :b :d] [:a :b])
     ;=> [:a :b :c :d]

     (apply-field-order [:email :name :role :active] [:role :email])
     ;=> [:role :email :name :active]"
  [fields field-order]
  (if (seq field-order)
    (let [field-set (set fields)
          ;; Fields from field-order that exist in fields (in order)
          ordered (filterv field-set field-order)
          ;; Remaining fields not in field-order (preserve original order)
          ordered-set (set ordered)
          remaining (filterv #(not (ordered-set %)) fields)]
      (into ordered remaining))
    fields))

(defn apply-field-order-to-config
  "Apply :field-order to :editable-fields and :detail-fields in entity config.

   If :field-order is present in the config, reorders :editable-fields and
   :detail-fields accordingly. Does not modify :list-fields (those have their
   own explicit ordering).

   Args:
     entity-config: Entity configuration map

   Returns:
     Entity configuration with reordered field vectors

   Example:
     (apply-field-order-to-config
       {:editable-fields [:c :a :b]
        :detail-fields [:c :a :b :d]
        :field-order [:a :b :c]})"
  [entity-config]
  (if-let [field-order (:field-order entity-config)]
    (-> entity-config
        (update :editable-fields #(apply-field-order % field-order))
        (update :detail-fields #(apply-field-order % field-order)))
    entity-config))

;; =============================================================================
;; Malli Schema Enum Extraction
;; =============================================================================

(defn extract-enum-fields-from-malli-schema
  "Extract enum field configurations from a raw Malli :map schema.

   Walks the map children and returns a partial field config for every field
   whose schema is [:enum v1 v2 ...].  Works on raw Malli schema data (no
   compilation / malli.core dependency needed).

   Args:
     schema: Raw Malli schema data, expected to be a :map vector such as
             [:map {} [:role [:enum :admin :user]] [:theme {:optional true}
                                                    [:enum :light :dark]]]

   Returns:
     Map of field-name keyword → {:type :enum :widget :select :options [...]}
     Options are [value label] pairs where label is a humanised string.
     Returns {} when schema is nil or not a :map schema.

   Example:
     (extract-enum-fields-from-malli-schema
       [:map {} [:role [:enum :admin :user :viewer]]])
     ;=> {:role {:type :enum :widget :select
     ;           :options [[:admin \"Admin\"] [:user \"User\"] [:viewer \"Viewer\"]]}}"
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (let [;; Skip the optional properties map that may follow :map
          tail (rest schema)
          children (if (and (seq tail) (map? (first tail)))
                     (rest tail)
                     tail)]
      (into {}
            (for [entry children
                  :when (vector? entry)
                  :let [field-key (first entry)
                        ;; Entry is either [key schema] or [key props schema]
                        rest-entry (rest entry)
                        field-schema (if (and (>= (count rest-entry) 2)
                                              (map? (first rest-entry)))
                                       (second rest-entry)
                                       (first rest-entry))]
                  :when (and (vector? field-schema)
                             (= :enum (first field-schema)))]
              (let [enum-values (rest field-schema)
                    options (mapv (fn [v]
                                    [v (-> (name v)
                                           (str/replace #"[-_]" " ")
                                           str/capitalize)])
                                  enum-values)]
                [field-key {:type :enum
                            :widget :select
                            :options options}]))))))

;; =============================================================================
;; Relationship Detection (Week 2)
;; =============================================================================

(defn- pluralize
  "Simple pluralization for entity names.

   Args:
     word: Singular word string

   Returns:
     Pluralized word

   Example:
     (pluralize \"user\") => \"users\"
     (pluralize \"category\") => \"categories\""
  [word]
  (let [word-str (name word)]
    (cond
      ; Special cases
      (str/ends-with? word-str "y")
      (str (subs word-str 0 (dec (count word-str))) "ies")

      (str/ends-with? word-str "s")
      (str word-str "es")

      ; Default: add 's'
      :else
      (str word-str "s"))))

(defn detect-foreign-keys
  "Detect foreign key relationships from field names.

   Week 2: Heuristic based on field naming conventions.
   Week 3+: Could enhance with actual database foreign key constraints.

   Args:
     fields-by-name: Map of field-name -> field-config

   Returns:
     Vector of relationship maps:
     [{:field :user-id
       :references-entity :users
       :references-field :id
       :display-field :name}]

   Example:
      (detect-foreign-keys {:user-id {...} :category-id {...}})"
  [fields-by-name]
  (reduce-kv
   (fn [acc field-name _field-config]
     (let [field-str (name field-name)]
       (if (or (str/ends-with? field-str "-id")
               (str/ends-with? field-str "_id"))
         ; This is a foreign key field
         (let [; Extract the entity name (e.g., "user-id" -> "user")
               base-name (if (str/ends-with? field-str "-id")
                           (subs field-str 0 (- (count field-str) 3))
                           (subs field-str 0 (- (count field-str) 3)))
               ; Pluralize to get entity name (e.g., "user" -> "users")
               entity-name (keyword (pluralize base-name))
               ; Guess display field based on common naming conventions
               display-field (if (= base-name "user")
                               :email  ; Users typically display by email
                               :name)] ; Most entities have a name field
           (conj acc {:field field-name
                      :references-entity entity-name
                      :references-field :id
                      :display-field display-field}))
         ; Not a foreign key field
         acc)))
   []
   fields-by-name))

(defn detect-relationships
  "Detect all relationships for an entity configuration.

   Week 2: Detects belongs-to relationships from foreign key fields.
   Week 3+: Could add has-many and has-one detection.

   Args:
     entity-config: Entity configuration map

   Returns:
     Entity configuration with :relationships key added

   Example:
     (detect-relationships {:label \"Orders\" :fields {...}})"
  [entity-config]
  (let [fields (:fields entity-config)
        foreign-keys (detect-foreign-keys fields)
        ; Convert foreign keys to belongs-to relationships
        belongs-to (mapv (fn [fk]
                           {:type :belongs-to
                            :field (:field fk)
                            :entity (:references-entity fk)
                            :foreign-key (:field fk)
                            :display-field (:display-field fk)})
                         foreign-keys)]
    (assoc entity-config
           :relationships {:belongs-to belongs-to
                           :has-many []   ; Week 3+: Inverse relationships
                           :has-one []})))  ; Week 3+: One-to-one relationships

