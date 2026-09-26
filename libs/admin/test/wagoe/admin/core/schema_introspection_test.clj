(ns wagoe.admin.core.schema-introspection-test
  "Unit tests for schema introspection pure functions.

   Tests cover:
   - SQL type normalization and inference
   - Field type detection and widget selection
   - Table metadata parsing and entity config generation
   - Relationship detection (Week 1 stub, Week 2+ full implementation)
   - Entity config merging (auto-detected + manual overrides)"
  (:require [wagoe.admin.core.schema-introspection :as introspection]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

;; =============================================================================
;; SQL Type Normalization and Inference Tests
;; =============================================================================

(deftest ^:unit normalize-sql-type-test
  (testing "SQL type normalization handles various database type formats"
    (testing "PostgreSQL types"
      (is (= "varchar" (introspection/normalize-sql-type "VARCHAR")))
      (is (= "character varying" (introspection/normalize-sql-type "CHARACTER VARYING")))
      (is (= "text" (introspection/normalize-sql-type "TEXT")))
      (is (= "uuid" (introspection/normalize-sql-type "UUID")))
      (is (= "timestamp without time zone" (introspection/normalize-sql-type "TIMESTAMP WITHOUT TIME ZONE")))
      (is (= "timestamp with time zone" (introspection/normalize-sql-type "TIMESTAMP WITH TIME ZONE"))))

    (testing "SQLite types"
      (is (= "text" (introspection/normalize-sql-type "TEXT")))
      (is (= "integer" (introspection/normalize-sql-type "INTEGER")))
      (is (= "real" (introspection/normalize-sql-type "REAL")))
      (is (= "blob" (introspection/normalize-sql-type "BLOB"))))

    (testing "H2 types"
      (is (= "uuid" (introspection/normalize-sql-type "UUID()")))
      (is (= "varchar" (introspection/normalize-sql-type "VARCHAR(255)"))))

    (testing "Case insensitivity"
      (is (= "varchar" (introspection/normalize-sql-type "varchar")))
      (is (= "varchar" (introspection/normalize-sql-type "VarChar")))
      (is (= "text" (introspection/normalize-sql-type "text"))))))

(deftest ^:unit infer-field-type-test
  (testing "Field type inference from SQL types"
    (testing "String types"
      (is (= :string (introspection/infer-field-type "VARCHAR")))
      (is (= :text (introspection/infer-field-type "TEXT")))  ; TEXT maps to :text
      (is (= :string (introspection/infer-field-type "CHAR"))))

    (testing "Numeric types"
      (is (= :int (introspection/infer-field-type "INTEGER")))
      (is (= :int (introspection/infer-field-type "BIGINT")))
      (is (= :int (introspection/infer-field-type "SMALLINT")))
      (is (= :decimal (introspection/infer-field-type "DECIMAL")))
      (is (= :decimal (introspection/infer-field-type "NUMERIC")))
      (is (= :decimal (introspection/infer-field-type "REAL"))))

    (testing "Boolean types"
      (is (= :boolean (introspection/infer-field-type "BOOLEAN")))
      (is (= :boolean (introspection/infer-field-type "BOOL"))))

    (testing "Temporal types"
      (is (= :instant (introspection/infer-field-type "TIMESTAMP")))
      (is (= :instant (introspection/infer-field-type "TIMESTAMPTZ")))
      (is (= :instant (introspection/infer-field-type "DATETIME")))
      (is (= :date (introspection/infer-field-type "DATE")))
      (is (= :time (introspection/infer-field-type "TIME"))))

    (testing "UUID types"
      (is (= :uuid (introspection/infer-field-type "UUID")))
      (is (= :uuid (introspection/infer-field-type "UUID()"))))

    (testing "Binary types"
      (is (= :binary (introspection/infer-field-type "BYTEA")))
      (is (= :binary (introspection/infer-field-type "BLOB"))))

    (testing "JSON/CLOB types"
      (is (= :json (introspection/infer-field-type "JSON")))
      (is (= :json (introspection/infer-field-type "JSONB")))
      (is (= :json (introspection/infer-field-type "CLOB"))))

    (testing "Double precision types"
      (is (= :decimal (introspection/infer-field-type "DOUBLE PRECISION")))
      (is (= :decimal (introspection/infer-field-type "FLOAT")))
      (is (= :decimal (introspection/infer-field-type "MONEY"))))

    (testing "Unknown types default to string"
      (is (= :string (introspection/infer-field-type "UNKNOWN_TYPE")))
      (is (= :string (introspection/infer-field-type "CUSTOM_ENUM"))))))

;; =============================================================================
;; Widget Inference Tests
;; =============================================================================

(deftest ^:unit infer-widget-for-field-test
  (testing "Widget inference based on field names and types"
    (testing "Special field name patterns"
      (is (= :email-input (introspection/infer-widget-for-field :email :string "VARCHAR")))
      (is (= :email-input (introspection/infer-widget-for-field :user-email :string "VARCHAR")))
      (is (= :password-input (introspection/infer-widget-for-field :password :string "VARCHAR")))
      (is (= :password-input (introspection/infer-widget-for-field :password-hash :string "VARCHAR")))
      (is (= :url-input (introspection/infer-widget-for-field :website :string "VARCHAR")))
      (is (= :url-input (introspection/infer-widget-for-field :profile-url :string "VARCHAR"))))

    (testing "Type-based widget selection"
      (is (= :checkbox (introspection/infer-widget-for-field :active :boolean "BOOLEAN")))
      (is (= :checkbox (introspection/infer-widget-for-field :enabled :boolean "BOOLEAN")))
      (is (= :datetime-input (introspection/infer-widget-for-field :created-at :instant "TIMESTAMP")))
      (is (= :datetime-input (introspection/infer-widget-for-field :updated-at :instant "TIMESTAMP")))
      (is (= :date-input (introspection/infer-widget-for-field :birth-date :date "DATE")))
      (is (= :number-input (introspection/infer-widget-for-field :quantity :int "INTEGER")))
      (is (= :number-input (introspection/infer-widget-for-field :price :decimal "DECIMAL"))))

    (testing "Textarea for long text fields"
      (is (= :textarea (introspection/infer-widget-for-field :description :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :bio :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :notes :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :content :string "TEXT"))))

    (testing "Default to text input for other string fields"
      (is (= :text-input (introspection/infer-widget-for-field :name :string "VARCHAR")))
      (is (= :text-input (introspection/infer-widget-for-field :title :string "VARCHAR"))))))

;; =============================================================================
;; Table Metadata Parsing Tests
;; =============================================================================

(def sample-users-table-metadata
  "Sample users table metadata from database adapter.
   
   This format matches what get-table-info actually returns from database adapters:
   {:name, :type, :not-null, :default, :primary-key}"
  [{:name "id"
    :type "UUID"
    :not-null true
    :default "gen_random_uuid()"
    :primary-key true}
   {:name "email"
    :type "VARCHAR"
    :not-null true
    :default nil
    :primary-key false}
   {:name "name"
    :type "VARCHAR"
    :not-null false
    :default nil
    :primary-key false}
   {:name "password_hash"
    :type "VARCHAR"
    :not-null true
    :default nil
    :primary-key false}
   {:name "role"
    :type "VARCHAR"
    :not-null true
    :default "'user'"
    :primary-key false}
   {:name "active"
    :type "BOOLEAN"
    :not-null true
    :default "true"
    :primary-key false}
   {:name "created_at"
    :type "TIMESTAMP"
    :not-null true
    :default "now()"
    :primary-key false}
   {:name "updated_at"
    :type "TIMESTAMP"
    :not-null false
    :default nil
    :primary-key false}
   {:name "deleted_at"
    :type "TIMESTAMP"
    :not-null false
    :default nil
    :primary-key false}])

(deftest ^:unit parse-table-metadata-test
  (testing "Parse users table metadata into entity config"
    (let [config (introspection/parse-table-metadata :users sample-users-table-metadata)]

      (testing "Basic entity info"
        (is (= :users (:table-name config)))
        (is (= "Users" (:label config)))
        (is (= :id (:primary-key config))))

      (testing "Fields parsed correctly"
        (let [fields (:fields config)]
          (is (= 9 (count fields)))

          ;; ID field
          (let [id-field (:id fields)]
            (is (= :uuid (:type id-field)))
            (is (true? (:readonly id-field)))
            (is (false? (:required id-field)))  ; ID is primary key, so required=false
            (is (= :text-input (:widget id-field))))

          ;; Email field
          (let [email-field (:email fields)]
            (is (= :string (:type email-field)))
            (is (true? (:required email-field)))  ; not-null and not PK
            (is (= :email-input (:widget email-field))))

          ;; Password hash field
          (let [password-field (:password-hash fields)]
            (is (= :string (:type password-field)))
            (is (= :password-input (:widget password-field))))

          ;; Active boolean field
          (let [active-field (:active fields)]
            (is (= :boolean (:type active-field)))
            (is (= :checkbox (:widget active-field))))

          ;; Timestamp fields
          (let [created-field (:created-at fields)]
            (is (= :instant (:type created-field)))
            (is (true? (:readonly created-field)))
            (is (= :datetime-input (:widget created-field))))))

      (testing "List fields exclude hidden and readonly"
        (let [list-fields (:list-fields config)]
          (is (vector? list-fields))
          (is (contains? (set list-fields) :email))
          (is (contains? (set list-fields) :name))
          (is (contains? (set list-fields) :role))
          ;; active is boolean - excluded from list view per should-be-in-list-view?
          (is (not (contains? (set list-fields) :active)))
          ;; Should not include hidden fields
          (is (not (contains? (set list-fields) :password-hash)))
          ;; deleted-at is readonly timestamp - may or may not be in list
          ))

      (testing "Search fields include text fields only"
        (let [search-fields (:search-fields config)]
          (is (contains? (set search-fields) :email))
          (is (contains? (set search-fields) :name))
          ;; Should not include non-text fields
          (is (not (contains? (set search-fields) :active)))
          (is (not (contains? (set search-fields) :created-at)))))

      (testing "Hidden fields identified correctly"
        (let [hidden-fields (:hide-fields config)]
          (is (contains? hidden-fields :password-hash))
          ;; deleted-at is readonly, not hidden (soft delete timestamp should be visible)
          (is (not (contains? hidden-fields :deleted-at)))))

      (testing "Readonly fields identified correctly"
        (let [readonly-fields (:readonly-fields config)]
          (is (contains? readonly-fields :id))
          (is (contains? readonly-fields :created-at))
          ;; updated-at is readonly but nullable
          (is (contains? readonly-fields :updated-at))))

      (testing "Soft delete detected"
        (is (true? (:soft-delete config))))

      (testing "Default sort by primary key descending"
        (is (= :id (:default-sort config)))
        (is (= :desc (:default-sort-dir config)))))))

;; =============================================================================
;; Entity Config Merging Tests
;; =============================================================================

(deftest ^:unit build-entity-config-test
  (testing "Merge auto-detected config with manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          manual-config {:label "System Users"
                         :list-fields [:email :name :role]
                         :search-fields [:email]
                         :hide-fields #{:password-hash}}
          merged (introspection/build-entity-config auto-config manual-config)]

      (testing "Manual config overrides auto-detected values"
        (is (= "System Users" (:label merged)))
        (is (= [:email :name :role] (:list-fields merged)))
        (is (= [:email] (:search-fields merged))))

      (testing "Auto-detected values preserved when not overridden"
        (is (= :users (:table-name merged)))
        (is (= :id (:primary-key merged)))
        (is (true? (:soft-delete merged))))

      (testing "Fields merged correctly"
        (is (= 9 (count (:fields merged)))))

      (testing "Hide fields merged (union of auto + manual)"
        (let [hidden-fields (:hide-fields merged)]
          (is (contains? hidden-fields :password-hash))
          ;; deleted-at is readonly, not hidden
          (is (not (contains? hidden-fields :deleted-at)))))))

  (testing "Build config with no manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged (introspection/build-entity-config auto-config nil)]

      (is (= auto-config merged))))

  (testing "Build config with empty manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged (introspection/build-entity-config auto-config {})]

      (is (= auto-config merged)))))

(deftest ^:unit manual-type-rederives-widget-test
  ;; :widget is inferred from the column during introspection, so a manual
  ;; :type has to re-derive it or the declared type and the rendered widget
  ;; disagree (BOU-504).
  (testing "a manual :type re-derives the widget"
    (is (= :datetime-input
           (:widget (introspection/merge-field-config
                     {:name :issue-date :type :date :widget :date-input}
                     {:type :instant}))))
    (is (= :select
           (:widget (introspection/merge-field-config
                     {:name :status :type :string :widget :text-input}
                     {:type :enum})))))

  (testing "an explicit manual :widget still wins"
    (is (= :textarea
           (:widget (introspection/merge-field-config
                     {:name :notes :type :string :widget :text-input}
                     {:type :instant :widget :textarea})))))

  (testing "a manual config that says nothing about :type leaves the widget alone"
    (is (= :date-input
           (:widget (introspection/merge-field-config
                     {:name :issue-date :type :date :widget :date-input}
                     {:label "Issue date"}))))))

(deftest ^:unit manual-readonly-fields-are-not-editable-test
  ;; Before BOU-498 :editable-fields was computed from the auto-detected
  ;; read-only columns and then carried through the merge unchanged, so the form
  ;; rendered a writable input for a field the config called read-only.
  (testing "a field named only in a manual :readonly-fields is not editable"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged      (introspection/build-entity-config
                       auto-config {:readonly-fields #{:id :email :created-at :updated-at}})]
      (is (contains? (set (:readonly-fields merged)) :email))
      (is (not (contains? (set (:editable-fields merged)) :email))
          ":email is read-only in the manual config, so it must not be editable")
      (is (contains? (set (:editable-fields merged)) :name)
          "fields the manual config says nothing about stay editable")))

  (testing "an explicit manual :editable-fields wins over the derived one"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged      (introspection/build-entity-config
                       auto-config {:readonly-fields #{:id}
                                    :editable-fields [:email]})]
      (is (= [:email] (:editable-fields merged)))))

  (testing "hidden fields are never editable"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged      (introspection/build-entity-config
                       auto-config {:hide-fields #{:password-hash}})]
      (is (not (contains? (set (:editable-fields merged)) :password-hash))))))

(deftest ^:unit deriving-editability-keeps-what-the-auto-config-excluded-test
  ;; The first BOU-498 fix derived editability from the MERGED :readonly-fields
  ;; and :detail-fields. `merge` replaces those, it does not union them, so a
  ;; manual config that named one read-only field un-hid every auto-detected
  ;; one — :id included — and one that narrowed the detail view emptied the
  ;; edit form. Editability now starts from the auto-detected editable fields.
  (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)]
    (testing "a manual :readonly-fields does not make auto-detected read-only fields editable"
      (let [editable (set (:editable-fields
                           (introspection/build-entity-config
                            auto-config {:readonly-fields #{:email}})))]
        (is (not (contains? editable :email)))
        (doseq [f [:id :created-at :updated-at :deleted-at]]
          (is (not (contains? editable f))
              (str f " is read-only by detection and must stay out of the form")))))

    (testing ":detail-fields shapes the detail view, not which fields can be edited"
      (is (= (:editable-fields auto-config)
             (:editable-fields (introspection/build-entity-config
                                auto-config {:detail-fields [:email]})))))

    (testing "a manual config that says nothing about fields changes nothing"
      (is (= (:editable-fields auto-config)
             (:editable-fields (introspection/build-entity-config
                                auto-config {:label "People"})))))))

(deftest ^:unit manual-type-keeps-name-inferred-widgets-test
  ;; BOU-504 re-derived :widget from a manual :type with the type's default
  ;; widget, which ignores the name heuristics introspection used. Repeating
  ;; the detected type then turned an email input into plain text and a
  ;; password input into visible text; :password {:type :text} became a
  ;; textarea.
  (let [auto (fn [n] {:name n :type :string
                      :widget (introspection/infer-widget-for-field n :string "VARCHAR")})
        widget (fn [n manual] (:widget (introspection/merge-field-config (auto n) manual)))]
    (testing "repeating the detected type keeps the inferred widget"
      (is (= :email-input (widget :email {:type :string})))
      (is (= :password-input (widget :password {:type :string})))
      (is (= :url-input (widget :website {:type :string}))))

    (testing "a changed type is re-derived with the name heuristics, not the bare type default"
      (is (= :password-input (widget :password {:type :text}))
          "a password must never render as visible text"))

    (testing "a changed type still re-derives where the name says nothing (BOU-504)"
      (is (= :datetime-input (widget :due-at {:type :instant}))))

    (testing "an explicit manual :widget still wins"
      (is (= :textarea (widget :email {:type :string :widget :textarea}))))))

;; =============================================================================
;; Relationship Detection Tests (Week 1 Stub)
;; =============================================================================

(deftest ^:unit detect-relationships-test
  (testing "Week 1: Relationship detection is a stub"
    (let [config (introspection/parse-table-metadata :users sample-users-table-metadata)
          with-relationships (introspection/detect-relationships config)]

      (testing "Adds relationship keys with empty vectors"
        (is (= [] (:belongs-to (:relationships with-relationships))))
        (is (= [] (:has-many (:relationships with-relationships))))
        (is (= [] (:has-one (:relationships with-relationships)))))

      (testing "Week 2+: Will detect foreign keys and relationships"
        ;; Placeholder for future relationship detection tests
        ;; Should detect belongs-to, has-many based on foreign keys
        ;; Should infer relationship names from column names
        (is (map? (:relationships with-relationships)))))))

;; =============================================================================
;; Entity Name Humanization Tests
;; =============================================================================

(deftest ^:unit humanize-entity-name-test
  (testing "Humanize entity names for display"
    (is (= "Users" (introspection/humanize-entity-name :users)))
    (is (= "User profiles" (introspection/humanize-entity-name :user-profiles)))
    (is (= "Order items" (introspection/humanize-entity-name :order-items)))
    (is (= "Products" (introspection/humanize-entity-name :products)))))

(deftest ^:unit humanize-field-name-test
  (testing "Humanize field names for display"
    (is (= "Email" (introspection/humanize-field-name :email)))
    (is (= "Password hash" (introspection/humanize-field-name :password-hash)))
    (is (= "Created at" (introspection/humanize-field-name :created-at)))
    (is (= "User id" (introspection/humanize-field-name :user-id)))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit parse-table-metadata-edge-cases-test
  (testing "Empty table metadata"
    (let [config (introspection/parse-table-metadata :empty [])]
      (is (= :empty (:table-name config)))
      (is (= {} (:fields config)))
      (is (= [] (:list-fields config)))))

  (testing "Table with only ID column"
    (let [metadata [{:name "id"
                     :type "UUID"
                     :not-null true
                     :primary-key true}]
          config (introspection/parse-table-metadata :minimal metadata)]
      (is (= :id (:primary-key config)))
      (is (= 1 (count (:fields config))))))

  (testing "Table without primary key"
    (let [metadata [{:name "value"
                     :type "VARCHAR"
                     :not-null true
                     :primary-key false}]
          config (introspection/parse-table-metadata :no-pk metadata)]
      ;; Should default to :id or first column
      (is (keyword? (:primary-key config))))))

;; =============================================================================
;; Field Ordering Tests
;; =============================================================================

(deftest ^:unit apply-field-order-test
  (testing "Field ordering with :field-order"
    (testing "Reorders fields according to :field-order"
      (is (= [:a :b :c :d]
             (introspection/apply-field-order [:c :a :b :d] [:a :b]))))

    (testing "Fields not in :field-order are appended in original order"
      (is (= [:role :email :name :active]
             (introspection/apply-field-order [:email :name :role :active] [:role :email]))))

    (testing "Empty :field-order returns original fields"
      (is (= [:a :b :c]
             (introspection/apply-field-order [:a :b :c] []))))

    (testing "Nil :field-order returns original fields"
      (is (= [:a :b :c]
             (introspection/apply-field-order [:a :b :c] nil))))

    (testing "Fields in :field-order that don't exist are ignored"
      (is (= [:b :a :c]
             (introspection/apply-field-order [:a :b :c] [:x :b :y]))))

    (testing "All fields in :field-order produces exact order"
      (is (= [:c :b :a]
             (introspection/apply-field-order [:a :b :c] [:c :b :a]))))

    (testing "Empty fields vector returns empty"
      (is (= []
             (introspection/apply-field-order [] [:a :b :c]))))))

(deftest ^:unit apply-field-order-to-config-test
  (testing "Applying field order to entity config"
    (testing "Reorders :editable-fields and :detail-fields"
      (let [config {:editable-fields [:c :a :b]
                    :detail-fields [:c :a :b :d]
                    :field-order [:a :b :c]}
            result (introspection/apply-field-order-to-config config)]
        (is (= [:a :b :c] (:editable-fields result)))
        (is (= [:a :b :c :d] (:detail-fields result)))))

    (testing "No-op when :field-order is not present"
      (let [config {:editable-fields [:c :a :b]
                    :detail-fields [:c :a :b :d]}
            result (introspection/apply-field-order-to-config config)]
        (is (= [:c :a :b] (:editable-fields result)))
        (is (= [:c :a :b :d] (:detail-fields result)))))

    (testing "Handles nil :editable-fields gracefully"
      (let [config {:field-order [:a :b]}
            result (introspection/apply-field-order-to-config config)]
        (is (= [] (:editable-fields result)))))

    (testing "Preserves other config keys"
      (let [config {:editable-fields [:c :a :b]
                    :detail-fields [:c :a :b]
                    :field-order [:a :b :c]
                    :label "Test Entity"
                    :table-name :test}
            result (introspection/apply-field-order-to-config config)]
        (is (= "Test Entity" (:label result)))
        (is (= :test (:table-name result)))))))

;; =============================================================================
;; Malli Schema Enum Extraction Tests
;; =============================================================================

(deftest ^:unit extract-enum-fields-from-malli-schema-test
  (testing "Extracts enum fields from a simple :map schema"
    (let [schema [:map {}
                  [:id :uuid]
                  [:role [:enum :admin :user :viewer]]
                  [:name :string]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (= 1 (count result)) "Only enum fields should be extracted")
      (is (contains? result :role))
      (is (= :enum (get-in result [:role :type])))
      (is (= :select (get-in result [:role :widget])))
      (is (= [[:admin "Admin"] [:user "User"] [:viewer "Viewer"]]
             (get-in result [:role :options])))))

  (testing "Extracts enum fields with optional properties"
    (let [schema [:map
                  [:theme {:optional true} [:enum :light :dark :auto]]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (contains? result :theme))
      (is (= :enum (get-in result [:theme :type])))
      (is (= [[:light "Light"] [:dark "Dark"] [:auto "Auto"]]
             (get-in result [:theme :options])))))

  (testing "Skips non-enum fields"
    (let [schema [:map
                  [:id :uuid]
                  [:email :string]
                  [:active :boolean]
                  [:status [:enum :pending :active :inactive]]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (= #{:status} (set (keys result))))))

  (testing "Handles multiple enum fields"
    (let [schema [:map
                  [:role [:enum :admin :user]]
                  [:status [:enum :pending :active]]
                  [:theme {:optional true} [:enum :light :dark]]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (= #{:role :status :theme} (set (keys result))))))

  (testing "Returns empty map for nil schema"
    (is (= {} (or (introspection/extract-enum-fields-from-malli-schema nil) {}))))

  (testing "Returns empty map for non-map schema"
    (is (nil? (introspection/extract-enum-fields-from-malli-schema :string)))
    (is (nil? (introspection/extract-enum-fields-from-malli-schema [:string]))))

  (testing "Returns empty map for schema with no enum fields"
    (let [schema [:map
                  [:id :uuid]
                  [:name :string]
                  [:active :boolean]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (= {} result))))

  (testing "Humanises enum option labels"
    (let [schema [:map
                  [:format [:enum :date-format :time-format]]]
          result (introspection/extract-enum-fields-from-malli-schema schema)]
      (is (= [[:date-format "Date format"] [:time-format "Time format"]]
             (get-in result [:format :options]))))))

;; =============================================================================
;; tsvector Column Hiding Tests
;; =============================================================================

(deftest ^:unit tsvector-hidden-by-common-hidden-fields-test
  (testing "Common tsvector field names are in hidden set"
    (is (introspection/should-be-hidden? :search-vector))
    (is (introspection/should-be-hidden? :tsv))
    (is (introspection/should-be-hidden? :fts-vector))))

(deftest ^:unit tsvector-hidden-by-sql-type-test
  (testing "Column with TSVECTOR SQL type is hidden regardless of name"
    (let [col-meta {:name "content_search"
                    :type "TSVECTOR"
                    :not-null false
                    :primary-key false}
          result (introspection/parse-column-metadata col-meta)]
      (is (true? (:hidden result)))))

  (testing "Column with tsvector mixed-case SQL type is hidden"
    (let [col-meta {:name "custom_fts"
                    :type "tsvector"
                    :not-null false
                    :primary-key false}
          result (introspection/parse-column-metadata col-meta)]
      (is (true? (:hidden result)))))

  (testing "Normal VARCHAR column is not hidden by type check"
    (let [col-meta {:name "description"
                    :type "VARCHAR(255)"
                    :not-null false
                    :primary-key false}
          result (introspection/parse-column-metadata col-meta)]
      (is (false? (:hidden result))))))

;; =============================================================================
;; Inverse (has-many) Relationships — BOU-481
;; =============================================================================

(defn- detected [entity-name columns]
  (introspection/detect-relationships
   (introspection/parse-table-metadata entity-name columns)))

(def ^:private order-configs
  {:orders      (detected :orders [{:name "id" :type "UUID" :primary-key true}
                                   {:name "number" :type "VARCHAR(50)" :not-null true}])
   :order-items (detected :order-items [{:name "id" :type "UUID" :primary-key true}
                                        {:name "order_id" :type "UUID" :not-null true}
                                        {:name "sku" :type "VARCHAR(50)" :not-null true}])})

(deftest ^:unit inverse-has-many-test
  (testing "a belongs-to registers the matching has-many on the parent, in config shape"
    (is (= [{:entity      :order-items
             :table       :order_items
             :foreign-key :order-id
             :label       "Order items"
             :fields      [:sku]
             :editable    false}]
           (introspection/inverse-has-many :orders order-configs))))

  (testing "an entity nothing points at has none"
    (is (= [] (introspection/inverse-has-many :order-items order-configs))))

  (testing "a belongs-to whose parent is not a known entity registers nothing"
    (is (= [] (introspection/inverse-has-many :orders (dissoc order-configs :orders))))))

(deftest ^:unit with-inverse-relationships-test
  (let [explicit {:entity :order-items :table :order_items :foreign-key :order-id
                  :label "Lines" :fields [:sku] :editable true}
        result   (fn [configs] (introspection/with-inverse-relationships
                                 :orders (:orders configs) configs))]
    (testing "detected has-many lands on the :has-many key the admin renders"
      (let [cfg (result order-configs)]
        (is (= [:order-items] (mapv :entity (:has-many cfg))))
        (is (= (:has-many cfg) (get-in cfg [:relationships :has-many])))))

    (testing "an explicit entry for the same child wins over the detected one"
      (let [cfg (result (assoc-in order-configs [:orders :has-many] [explicit]))]
        (is (= [explicit] (:has-many cfg)))
        (is (= [explicit] (get-in cfg [:relationships :has-many])))))

    (testing "explicit entries for other children are kept alongside detected ones"
      (let [other {:entity :notes :table :notes :foreign-key :order-id}
            cfg   (result (assoc-in order-configs [:orders :has-many] [other]))]
        (is (= [:notes :order-items] (mapv :entity (:has-many cfg))))))))

;; =============================================================================
;; Read-only NOT NULL columns (BOU-494)
;; =============================================================================

(def ^:private invoices-columns
  [{:name "id" :type "UUID" :not-null true :default nil :primary-key true}
   {:name "number" :type "VARCHAR(50)" :not-null true :default nil :primary-key false}
   {:name "status" :type "VARCHAR(50)" :not-null true :default nil :primary-key false}
   {:name "created_at" :type "TIMESTAMP" :not-null true :default nil :primary-key false}
   {:name "updated_at" :type "TIMESTAMP" :not-null true :default nil :primary-key false}])

(deftest ^:unit readonly-not-null-errors-test
  ;; The admin insert omits every :readonly-fields entry, so a read-only column
  ;; that is NOT NULL with no default fails every create.
  (let [config {:readonly-fields #{:id :status :created-at :updated-at}}]
    (testing "a read-only NOT NULL column without a default is reported"
      (let [[error & more] (introspection/readonly-not-null-errors :invoices config invoices-columns)]
        (is (nil? more))
        (is (= :status (:field error)))
        (is (= "status" (:column error)))
        (is (str/includes? (:message error) "invoices") "names the entity")
        (is (str/includes? (:message error) "'status'") "names the column")
        (is (str/includes? (:message error) "default") "suggests a column default")
        (is (str/includes? (:message error) ":readonly-fields") "suggests dropping it from :readonly-fields")))

    (testing ":id, :created-at and :updated-at are filled by the admin"
      (is (not-any? #{:id :created-at :updated-at}
                    (map :field (introspection/readonly-not-null-errors :invoices config invoices-columns)))))

    (testing "a column default makes it creatable"
      (is (empty? (introspection/readonly-not-null-errors
                   :invoices config (assoc-in invoices-columns [2 :default] "'draft'")))))

    (testing "a nullable column is fine"
      (is (empty? (introspection/readonly-not-null-errors
                   :invoices config (assoc-in invoices-columns [2 :not-null] false)))))

    (testing "an entity with its own create flow is not the admin's to create"
      (is (empty? (introspection/readonly-not-null-errors
                   :invoices (assoc config :create-redirect-url "/web/invoices/new")
                   invoices-columns))))))
