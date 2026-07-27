(ns wagoe.admin.shell.schema-repository-test
  "Integration tests for schema repository with real database.

   Tests cover:
   - Fetching table metadata from H2 database
   - Building entity configurations with auto-detection + manual overrides
   - Entity discovery (allowlist mode)
   - Multi-database support via adapter protocols"
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.adapters.database.common.execution :as db]
            [clojure.test :refer [deftest is testing use-fixtures]]))

^{:kaocha.testable/meta {:integration true :admin true}}

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def test-db-config
  "H2 in-memory database configuration for testing"
  {:adapter :h2
   :database-path "mem:schema_repo_test;DB_CLOSE_DELAY=-1"
   :pool {:minimum-idle 1
          :maximum-pool-size 3}})

(defonce ^:dynamic *db-ctx* nil)

(defn create-test-tables!
  "Create various test tables to test schema introspection"
  [db-ctx]
  ;; Simple table with basic types
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS simple_table (
           id UUID PRIMARY KEY,
           name VARCHAR(255) NOT NULL,
           active BOOLEAN NOT NULL DEFAULT true,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"})

  ;; Table with all common types
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS complex_table (
           id UUID PRIMARY KEY,
           email VARCHAR(255) NOT NULL UNIQUE,
           password_hash VARCHAR(255) NOT NULL,
           bio TEXT,
           age INTEGER,
           salary DECIMAL(10,2),
           birth_date DATE,
           last_login TIMESTAMP,
           active BOOLEAN NOT NULL DEFAULT true,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TIMESTAMP,
           deleted_at TIMESTAMP)"})

  ;; Table without soft delete
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS hard_delete_table (
           id UUID PRIMARY KEY,
           name VARCHAR(255) NOT NULL,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"})

  ;; Table for testing nullable vs non-nullable
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS nullable_table (
           id UUID PRIMARY KEY,
           required_field VARCHAR(255) NOT NULL,
           optional_field VARCHAR(255),
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"}))

(defn drop-test-tables!
  "Drop test tables"
  [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS simple_table"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS complex_table"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS hard_delete_table"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS nullable_table"}))

(defn setup-test-database!
  "Set up test database"
  []
  (let [db-ctx (db-factory/db-context test-db-config)]
    (create-test-tables! db-ctx)
    (alter-var-root #'*db-ctx* (constantly db-ctx))))

(defn teardown-test-database!
  "Tear down test database"
  []
  (when *db-ctx*
    (drop-test-tables! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx* (constantly nil))))

(use-fixtures :once
  (fn [f]
    (setup-test-database!)
    (f)
    (teardown-test-database!)))

;; =============================================================================
;; Table Metadata Fetching Tests
;; =============================================================================

(deftest ^:integration fetch-table-metadata-test
  (testing "Fetch table metadata from H2 database"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (testing "Fetch simple table metadata"
        (let [metadata (ports/fetch-table-metadata repo :simple-table)]
          (is (sequential? metadata))
          (is (>= (count metadata) 4)) ; id, name, active, created_at

          ;; Verify column names present (adapter returns :name not :column-name)
          (let [column-names (set (map :name metadata))]
            (is (contains? column-names "id"))
            (is (contains? column-names "name"))
            (is (contains? column-names "active"))
            (is (contains? column-names "created_at")))))

      (testing "Fetch complex table metadata"
        (let [metadata (ports/fetch-table-metadata repo :complex-table)]
          (is (>= (count metadata) 12))

          ;; Verify diverse column types (adapter returns :name not :column-name)
          (let [column-names (set (map :name metadata))]
            (is (contains? column-names "email"))
            (is (contains? column-names "password_hash"))
            (is (contains? column-names "bio"))
            (is (contains? column-names "age"))
            (is (contains? column-names "salary"))
            (is (contains? column-names "birth_date"))
            (is (contains? column-names "deleted_at")))))

      (testing "Fetch non-existent table throws exception"
        (is (thrown? Exception
                     (ports/fetch-table-metadata repo :nonexistent-table)))))))

;; =============================================================================
;; Entity Configuration Building Tests
;; =============================================================================

(deftest ^:integration get-entity-config-auto-detection-test
  (testing "Auto-detect entity configuration from database schema"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:complex-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :complex-table)]

      (testing "Basic entity metadata"
        (is (= :complex-table (:table-name entity-config)))
        (is (string? (:label entity-config)))
        (is (= :id (:primary-key entity-config))))

      (testing "Fields auto-detected with correct types"
        (let [fields (:fields entity-config)]
          (is (map? fields))

          ;; UUID field
          (let [id-field (:id fields)]
            (is (= :uuid (:type id-field)))
            (is (true? (:readonly id-field))))

          ;; String fields
          (let [email-field (:email fields)]
            (is (= :string (:type email-field)))
            (is (= :email-input (:widget email-field))))

          (let [password-field (:password-hash fields)]
            (is (= :string (:type password-field)))
            (is (= :password-input (:widget password-field))))

          ;; Text field
          (let [bio-field (:bio fields)]
            (is (= :string (:type bio-field)))
            (is (= :textarea (:widget bio-field))))

          ;; Numeric fields
          (let [age-field (:age fields)]
            (is (= :int (:type age-field)))
            (is (= :number-input (:widget age-field))))

          (let [salary-field (:salary fields)]
            (is (= :decimal (:type salary-field)))
            (is (= :number-input (:widget salary-field))))

          ;; Date/time fields
          (let [birth-date-field (:birth-date fields)]
            (is (= :date (:type birth-date-field)))
            (is (= :date-input (:widget birth-date-field))))

          (let [last-login-field (:last-login fields)]
            (is (= :instant (:type last-login-field)))
            (is (= :datetime-input (:widget last-login-field))))

          ;; Boolean field
          (let [active-field (:active fields)]
            (is (= :boolean (:type active-field)))
            (is (= :checkbox (:widget active-field))))))

      (testing "Auto-detected list fields"
        (let [list-fields (:list-fields entity-config)]
          (is (vector? list-fields))
          (is (> (count list-fields) 0))
          ;; Should include visible, non-hidden fields
          (is (some #{:email} list-fields))
          ;; Boolean fields like :active are excluded from list view by default
          (is (not (some #{:active} list-fields)))))

      (testing "Auto-detected search fields (text fields only)"
        (let [search-fields (:search-fields entity-config)]
          (is (vector? search-fields))
          ;; Should include text/string fields
          (is (some #{:email} search-fields))
          ;; Should not include non-text fields
          (is (not (some #{:active} search-fields)))
          (is (not (some #{:age} search-fields)))))

      (testing "Auto-detected hidden fields"
        (let [hidden-fields (:hide-fields entity-config)]
          (is (set? hidden-fields))
          ;; password_hash should be hidden, deleted_at is readonly not hidden
          (is (contains? hidden-fields :password-hash))
          (is (not (contains? hidden-fields :deleted-at)))))

      (testing "Auto-detected readonly fields"
        (let [readonly-fields (:readonly-fields entity-config)]
          (is (set? readonly-fields))
          ;; id, created_at, updated_at should be readonly
          (is (contains? readonly-fields :id))
          (is (contains? readonly-fields :created-at))
          (is (contains? readonly-fields :updated-at))))

      (testing "Soft delete detection"
        ;; complex_table has deleted_at column
        (is (true? (:soft-delete entity-config)))))))

(deftest ^:integration get-entity-config-manual-overrides-test
  (testing "Manual configuration overrides auto-detected values"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:complex-table}}
                  :entities {:complex-table {:label "Custom Users"
                                             :list-fields [:email :active]
                                             :search-fields [:email]
                                             :hide-fields #{:password-hash :salary}
                                             :readonly-fields #{:id :email :created-at}}}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :complex-table)]

      (testing "Manual label overrides auto-detected"
        (is (= "Custom Users" (:label entity-config))))

      (testing "Manual list fields override auto-detected"
        (is (= [:email :active] (:list-fields entity-config))))

      (testing "Manual search fields override auto-detected"
        (is (= [:email] (:search-fields entity-config))))

      (testing "Manual hidden fields merge with auto-detected"
        (let [hidden-fields (:hide-fields entity-config)]
          (is (contains? hidden-fields :password-hash))
          (is (contains? hidden-fields :salary))
          ;; deleted-at is readonly, not hidden
          (is (not (contains? hidden-fields :deleted-at))))) ; Auto-detected

      (testing "Manual readonly fields merge with auto-detected"
        (let [readonly-fields (:readonly-fields entity-config)]
          (is (contains? readonly-fields :id))
          (is (contains? readonly-fields :email)) ; Manual override
          (is (contains? readonly-fields :created-at))))))

  (testing "Entity configuration without manual overrides"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :simple-table)]

      ;; Should have auto-detected label
      (is (string? (:label entity-config)))
      ;; Should have auto-detected fields
      (is (map? (:fields entity-config))))))

(deftest ^:integration get-entity-config-soft-vs-hard-delete-test
  (testing "Soft delete detection based on deleted_at column"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:complex-table :hard-delete-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (testing "Table with deleted_at has soft delete"
        (let [complex-config (ports/get-entity-config repo :complex-table)]
          (is (true? (:soft-delete complex-config)))))

      (testing "Table without deleted_at has hard delete only"
        (let [hard-delete-config (ports/get-entity-config repo :hard-delete-table)]
          (is (false? (:soft-delete hard-delete-config))))))))

(deftest ^:integration get-entity-config-nullable-fields-test
  (testing "Nullable vs required field detection"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:nullable-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :nullable-table)]

      (testing "Required field marked as required (not nullable)"
        (let [required-field (get-in entity-config [:fields :required-field])]
          (is (true? (:required required-field)))))

      (testing "Optional field marked as not required (nullable)"
        (let [optional-field (get-in entity-config [:fields :optional-field])]
          (is (false? (:required optional-field))))))))

;; =============================================================================
;; Entity Discovery Tests
;; =============================================================================

(deftest ^:integration list-available-entities-allowlist-test
  (testing "Entity discovery with allowlist mode"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table :complex-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (testing "Returns only entities in allowlist"
        (let [entities (ports/list-available-entities repo)]
          (is (= 2 (count entities)))
          (is (contains? (set entities) :simple-table))
          (is (contains? (set entities) :complex-table))
          ;; Should not include hard_delete_table or nullable_table
          (is (not (contains? (set entities) :hard-delete-table)))
          (is (not (contains? (set entities) :nullable-table)))))))

  (testing "Empty allowlist returns no entities"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entities (ports/list-available-entities repo)]
      (is (= 0 (count entities))))))

(deftest ^:integration list-available-entities-unsupported-modes-test
  (testing "Denylist mode not yet implemented (Week 2+)"
    (let [config {:entity-discovery {:mode :denylist
                                     :denylist #{:simple-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Denylist mode not yet implemented"
           (ports/list-available-entities repo)))))

  (testing "All mode not yet implemented (Week 2+)"
    (let [config {:entity-discovery {:mode :all}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"All mode not yet implemented"
           (ports/list-available-entities repo))))))

;; =============================================================================
;; Entity Validation Tests
;; =============================================================================

(deftest ^:integration validate-entity-exists-test
  (testing "Validate entity is in allowlist"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table :complex-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (testing "Entity in allowlist exists"
        (is (true? (ports/validate-entity-exists repo :simple-table)))
        (is (true? (ports/validate-entity-exists repo :complex-table))))

      (testing "Entity not in allowlist does not exist"
        (is (false? (ports/validate-entity-exists repo :hard-delete-table)))
        (is (false? (ports/validate-entity-exists repo :nonexistent-table)))))))

;; =============================================================================
;; Entity Label Tests
;; =============================================================================

(deftest ^:integration get-entity-label-test
  (testing "Get entity label"
    (testing "With manual label in config"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :entities {:simple-table {:label "Custom Label"}}}
            repo (schema-repo/create-schema-repository *db-ctx* config)]

        (is (= "Custom Label" (ports/get-entity-label repo :simple-table)))))

    (testing "With auto-generated label"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :entities {}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            label (ports/get-entity-label repo :simple-table)]
        (is (string? label))
        (is (> (count label) 0))))))

;; =============================================================================
;; Multi-Database Support Tests
;; =============================================================================

(deftest ^:integration multi-database-support-test
  (testing "Schema repository works with different database adapters"
    ;; Week 1: Only H2 tested
    ;; Week 2+: Test with PostgreSQL, MySQL, SQLite

    (testing "H2 adapter (current)"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :entities {}}
            repo (schema-repo/create-schema-repository *db-ctx* config)]

        ;; Verify adapter protocol works with H2
        (is (some? (ports/fetch-table-metadata repo :simple-table)))
        (is (some? (ports/get-entity-config repo :simple-table)))))))

;; =============================================================================
;; UI Config Merging Tests
;; =============================================================================

(deftest ^:integration get-entity-config-ui-merging-test
  (testing "Global and entity-level :ui config merging"
    (testing "Entity :ui overrides global :ui"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :ui {:field-grouping {:other-label "Global Other"}}
                    :entities {:simple-table {:ui {:field-grouping {:other-label "Entity Other"}}}}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            entity-config (ports/get-entity-config repo :simple-table)]
        (is (= "Entity Other" (get-in entity-config [:ui :field-grouping :other-label])))))

    (testing "Global :ui is used when entity has no override"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :ui {:field-grouping {:other-label "Global Other"}}
                    :entities {}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            entity-config (ports/get-entity-config repo :simple-table)]
        (is (= "Global Other" (get-in entity-config [:ui :field-grouping :other-label])))))

    (testing "Entity partial :ui merges with global :ui"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :ui {:field-grouping {:other-label "Global Other"}
                         :theme "dark"}
                    :entities {:simple-table {:ui {:theme "light"}}}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            entity-config (ports/get-entity-config repo :simple-table)]
        ;; Entity overrides theme
        (is (= "light" (get-in entity-config [:ui :theme])))
        ;; Global :field-grouping is preserved
        (is (= "Global Other" (get-in entity-config [:ui :field-grouping :other-label])))))

    (testing "Entity :field-grouping overrides global :field-grouping"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :ui {:field-grouping {:other-label "Global Other"
                                          :show-empty-groups false}}
                    :entities {:simple-table {:ui {:field-grouping {:other-label "Custom Other"}}}}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            entity-config (ports/get-entity-config repo :simple-table)]
        ;; Entity overrides :other-label
        (is (= "Custom Other" (get-in entity-config [:ui :field-grouping :other-label])))
        ;; Global :show-empty-groups is preserved via merge
        (is (= false (get-in entity-config [:ui :field-grouping :show-empty-groups])))))

    (testing "Empty config results in empty :ui"
      (let [config {:entity-discovery {:mode :allowlist
                                       :allowlist #{:simple-table}}
                    :entities {}}
            repo (schema-repo/create-schema-repository *db-ctx* config)
            entity-config (ports/get-entity-config repo :simple-table)]
        ;; :ui should be present but may be empty
        (is (map? (get entity-config :ui {})))))))

;; =============================================================================
;; Field Order Tests
;; =============================================================================

(deftest ^:integration get-entity-config-field-order-test
  (testing "Field order reorders editable-fields and detail-fields via get-entity-config"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:complex-table}}
                  :entities {:complex-table {:field-order [:email :active :bio :age :salary]}}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :complex-table)]

      (testing "Editable fields are reordered according to :field-order"
        (let [editable-fields (:editable-fields entity-config)
              order-fields [:email :active :bio :age :salary]]
          ;; Fields in :field-order should appear first in order
          (when (seq editable-fields)
            (let [in-order (filterv (set order-fields) editable-fields)
                  ;; Check that fields from field-order appear in that order
                  positions (map #(.indexOf (vec editable-fields) %) in-order)]
              ;; Positions should be strictly increasing (ordered correctly)
              (is (= positions (sort positions)))))))

      (testing "Detail fields are reordered according to :field-order"
        (let [detail-fields (:detail-fields entity-config)
              order-fields [:email :active :bio :age :salary]]
          (when (seq detail-fields)
            (let [in-order (filterv (set order-fields) detail-fields)
                  positions (map #(.indexOf (vec detail-fields) %) in-order)]
              (is (= positions (sort positions)))))))))

  (testing "Field order with partial specification"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table}}
                  :entities {:simple-table {:field-order [:name :active]}}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :simple-table)]

      (testing "Specified fields appear first, remaining fields follow"
        (let [editable-fields (:editable-fields entity-config)]
          (when (and (seq editable-fields)
                     (some #{:name} editable-fields))
            ;; :name should appear before any unspecified fields
            (let [name-idx (.indexOf (vec editable-fields) :name)
                  active-idx (.indexOf (vec editable-fields) :active)]
              (when (and (>= name-idx 0) (>= active-idx 0))
                (is (< name-idx active-idx)))))))))

  (testing "Entity without :field-order preserves auto-detected order"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:simple-table}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)
          entity-config (ports/get-entity-config repo :simple-table)]
      ;; Should have fields (order is auto-detected)
      (is (vector? (:editable-fields entity-config)))
      (is (vector? (:detail-fields entity-config))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:integration error-handling-test
  (testing "Fetch metadata for non-existent table"
    (let [config {:entity-discovery {:mode :allowlist
                                     :allowlist #{:nonexistent}}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Table not found"
           (ports/fetch-table-metadata repo :nonexistent)))))

  (testing "Invalid entity discovery mode"
    (let [config {:entity-discovery {:mode :invalid-mode}
                  :entities {}}
          repo (schema-repo/create-schema-repository *db-ctx* config)]

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid entity discovery mode"
           (ports/list-available-entities repo))))))
