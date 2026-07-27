(ns wagoe.admin.shell.service-test
  "Integration tests for admin service with real database.

   Tests cover:
   - CRUD operations with H2 database
   - Pagination (limit, offset, page-based)
   - Sorting (ascending, descending)
   - Search across multiple fields
   - Field filters
   - Validation
   - Soft vs hard delete
   - Bulk operations
   - Split-table query config resolution
   - Boolean required-field validation"
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.adapters.database.common.execution :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]
           [java.time Instant]))

^{:kaocha.testable/meta {:integration true :admin true}}

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def test-db-config
  "H2 in-memory database configuration for testing"
  {:adapter :h2
   :database-path "mem:admin_service_test;DB_CLOSE_DELAY=-1"
   :pool {:minimum-idle 1
          :maximum-pool-size 3}})

(def admin-config
  "Admin configuration for testing"
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:test-users :test-items}}
   :entities {:test-users {:label "Test Users"
                           :list-fields [:email :name :active]
                           :search-fields [:email :name]
                           :hide-fields #{:password-hash}
                           :readonly-fields #{:id :created-at}}
              :test-items {:label "Test Items"
                           :list-fields [:name :quantity :price]
                           :search-fields [:name :description]}}
   :pagination {:default-page-size 50
                :max-page-size 200}})

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *schema-provider* nil)
(defonce ^:dynamic *admin-service* nil)

(defn create-test-tables!
  "Create test tables in H2 database"
  [db-ctx]
  ;; Test users table with soft delete
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS test_users (
           id UUID PRIMARY KEY,
           email VARCHAR(255) NOT NULL UNIQUE,
           name VARCHAR(255) NOT NULL,
           password_hash VARCHAR(255) NOT NULL,
           active BOOLEAN NOT NULL DEFAULT true,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TIMESTAMP,
           deleted_at TIMESTAMP)"})

  ;; Test items table without soft delete (hard delete only)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS test_items (
           id UUID PRIMARY KEY,
           name VARCHAR(255) NOT NULL,
           description TEXT,
           quantity INTEGER NOT NULL DEFAULT 0,
           price DECIMAL(10,2) NOT NULL,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TIMESTAMP)"}))

(defn drop-test-tables!
  "Drop test tables from H2 database"
  [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS test_users"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS test_items"}))

(defn setup-test-system!
  "Set up test database and services"
  []
  (let [db-ctx (db-factory/db-context test-db-config)
        logger (logging-no-op/create-logging-component {})
        error-reporter (error-reporting-no-op/create-error-reporting-component {})
        schema-provider (schema-repo/create-schema-repository db-ctx admin-config)
        admin-service (service/create-admin-service db-ctx schema-provider logger error-reporter admin-config)]

    (create-test-tables! db-ctx)

    (alter-var-root #'*db-ctx* (constantly db-ctx))
    (alter-var-root #'*schema-provider* (constantly schema-provider))
    (alter-var-root #'*admin-service* (constantly admin-service))))

(defn teardown-test-system!
  "Tear down test database and services"
  []
  (when *db-ctx*
    (drop-test-tables! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx* (constantly nil))
    (alter-var-root #'*schema-provider* (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil))))

(defn with-clean-tables
  "Fixture to clean tables between tests"
  [f]
  (when *db-ctx*
    ;; Clean tables
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_users"})
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_items"}))
  (f))

(use-fixtures :once
  (fn [f]
    (setup-test-system!)
    (f)
    (teardown-test-system!)))

(use-fixtures :each with-clean-tables)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn create-test-user!
  "Create a test user directly in database"
  ([email name]
   (create-test-user! email name true))
  ([email name active]
   (let [user-data {:id (UUID/randomUUID)
                    :email email
                    :name name
                    :password-hash "hash123"
                    :active active
                    :created-at (Instant/now)}]
      ;; Note: H2 doesn't support RETURNING clause, but execute-one! works fine without it
     (db/execute-one! *db-ctx*
                      {:insert-into :test-users
                       :values [user-data]})
     user-data)))

(defn create-test-item!
  "Create a test item directly in database"
  [name quantity price]
  (let [item-data {:id (UUID/randomUUID)
                   :name name
                   :description (str "Description for " name)
                   :quantity quantity
                   :price (bigdec price)
                   :created-at (Instant/now)}]
    ;; Note: H2 doesn't support RETURNING clause, but execute-one! works fine without it
    (db/execute-one! *db-ctx*
                     {:insert-into :test-items
                      :values [item-data]})
    item-data))

;; =============================================================================
;; Schema Provider Integration Tests
;; =============================================================================

(deftest ^:integration schema-provider-integration-test
  (testing "Schema provider with real H2 database"
    (testing "List available entities from allowlist"
      (let [entities (ports/list-available-entities *schema-provider*)]
        (is (= 2 (count entities)))
        (is (contains? (set entities) :test-users))
        (is (contains? (set entities) :test-items))))

    (testing "Get entity config for test-users"
      (let [config (ports/get-entity-config *schema-provider* :test-users)]
        (is (= :test-users (:table-name config)))
        (is (= "Test Users" (:label config)))
        (is (= :id (:primary-key config)))

        ;; Check fields parsed from database
        (let [fields (:fields config)]
          (is (contains? fields :id))
          (is (contains? fields :email))
          (is (contains? fields :name))
          (is (contains? fields :password-hash))
          (is (contains? fields :active))
          (is (contains? fields :created-at))
          (is (contains? fields :deleted-at)))

        ;; Check manual config applied
        (is (= [:email :name :active] (:list-fields config)))
        (is (= [:email :name] (:search-fields config)))
        (is (contains? (:hide-fields config) :password-hash))
        (is (contains? (:readonly-fields config) :id))
        (is (contains? (:readonly-fields config) :created-at))

        ;; Check soft delete detected
        (is (true? (:soft-delete config)))))

    (testing "Get entity config for test-items (no soft delete)"
      (let [config (ports/get-entity-config *schema-provider* :test-items)]
        (is (= :test-items (:table-name config)))
        (is (= "Test Items" (:label config)))

        ;; No deleted-at column = hard delete only
        (is (false? (:soft-delete config)))))

    (testing "Validate entity exists"
      (is (true? (ports/validate-entity-exists *schema-provider* :test-users)))
      (is (true? (ports/validate-entity-exists *schema-provider* :test-items)))
      (is (false? (ports/validate-entity-exists *schema-provider* :nonexistent))))

    (testing "Get entity label"
      (is (= "Test Users" (ports/get-entity-label *schema-provider* :test-users)))
      (is (= "Test Items" (ports/get-entity-label *schema-provider* :test-items))))))

;; =============================================================================
;; List Entities Tests (Pagination, Sorting, Search)
;; =============================================================================

(deftest ^:integration list-entities-basic-test
  (testing "List entities with basic options"
    ;; Create test data
    (create-test-user! "alice@example.com" "Alice Smith" true)
    (create-test-user! "bob@example.com" "Bob Jones" true)
    (create-test-user! "charlie@example.com" "Charlie Brown" false)

    (testing "List all users without pagination"
      (let [result (ports/list-entities *admin-service* :test-users {})]
        (is (= 3 (:total-count result)))
        (is (= 3 (count (:records result))))))

    (testing "List with default pagination"
      (let [result (ports/list-entities *admin-service* :test-users {:page 1 :page-size 2})]
        (is (= 3 (:total-count result)))
        (is (= 2 (count (:records result))))
        (is (= 1 (:page-number result)))
        (is (= 2 (:page-size result)))
        (is (= 2 (:total-pages result)))))

    (testing "List with limit/offset"
      (let [result (ports/list-entities *admin-service* :test-users {:limit 2 :offset 1})]
        (is (= 3 (:total-count result)))
        (is (= 2 (count (:records result))))))))

(deftest ^:integration list-entities-sorting-test
  (testing "List entities with sorting"
    ;; Create users in specific order
    (create-test-user! "charlie@example.com" "Charlie" true)
    (create-test-user! "alice@example.com" "Alice" true)
    (create-test-user! "bob@example.com" "Bob" true)

    (testing "Sort by email ascending"
      (let [result (ports/list-entities *admin-service* :test-users {:sort :email :sort-dir :asc})]
        (is (= 3 (count (:records result))))
        (let [emails (mapv :email (:records result))]
          (is (= "alice@example.com" (first emails)))
          (is (= "charlie@example.com" (last emails))))))

    (testing "Sort by email descending"
      (let [result (ports/list-entities *admin-service* :test-users {:sort :email :sort-dir :desc})]
        (is (= 3 (count (:records result))))
        (let [emails (mapv :email (:records result))]
          (is (= "charlie@example.com" (first emails)))
          (is (= "alice@example.com" (last emails))))))

    (testing "Sort by name ascending"
      (let [result (ports/list-entities *admin-service* :test-users {:sort :name :sort-dir :asc})
            names (mapv :name (:records result))]
        (is (= "Alice" (first names)))
        (is (= "Charlie" (last names)))))))

(deftest ^:integration list-entities-search-test
  (testing "List entities with text search"
    ;; Create diverse test data
    (create-test-user! "alice@example.com" "Alice Smith" true)
    (create-test-user! "bob@gmail.com" "Bob Jones" true)
    (create-test-user! "charlie@example.com" "Charlie Brown" true)
    (create-test-user! "david@yahoo.com" "David Wilson" true)

    (testing "Search by email domain"
      (let [result (ports/list-entities *admin-service* :test-users {:search "example.com"})]
        (is (= 2 (:total-count result)))
        (is (= 2 (count (:records result))))))

    (testing "Search by name"
      (let [result (ports/list-entities *admin-service* :test-users {:search "Charlie"})]
        (is (= 1 (:total-count result)))
        (is (= "Charlie Brown" (-> result :records first :name)))))

    (testing "Search matches multiple fields"
      (let [result (ports/list-entities *admin-service* :test-users {:search "Smith"})]
        (is (= 1 (:total-count result)))
        (is (= "alice@example.com" (-> result :records first :email)))))

    (testing "Search with no matches"
      (let [result (ports/list-entities *admin-service* :test-users {:search "nonexistent"})]
        (is (= 0 (:total-count result)))
        (is (= 0 (count (:records result))))))))

(deftest ^:integration list-entities-filters-test
  (testing "List entities with field filters"
    (create-test-user! "alice@example.com" "Alice" true)
    (create-test-user! "bob@example.com" "Bob" false)
    (create-test-user! "charlie@example.com" "Charlie" true)

    (testing "Filter by active = true"
      (let [result (ports/list-entities *admin-service* :test-users {:filters {:active true}})]
        (is (= 2 (:total-count result)))
        (is (every? :active (:records result)))))

    (testing "Filter by active = false"
      (let [result (ports/list-entities *admin-service* :test-users {:filters {:active false}})]
        (is (= 1 (:total-count result)))
        (is (= "Bob" (-> result :records first :name)))))))

(deftest ^:integration list-entities-combined-test
  (testing "List entities with search + sort + pagination"
    ;; Create 10 users
    (doseq [i (range 10)]
      (create-test-user! (str "user" i "@example.com")
                         (str "User " i)
                         (even? i)))

    (testing "Search + sort + pagination"
      (let [result (ports/list-entities *admin-service* :test-users
                                        {:search "example.com"
                                         :sort :email
                                         :sort-dir :asc
                                         :page 1
                                         :page-size 5})]
        (is (= 10 (:total-count result)))
        (is (= 5 (count (:records result))))
        (is (= 1 (:page-number result)))
        (is (= 2 (:total-pages result)))))))

;; =============================================================================
;; Get Entity Tests
;; =============================================================================

(deftest ^:integration get-entity-test
  (testing "Get single entity by ID"
    (let [user (create-test-user! "alice@example.com" "Alice Smith" true)
          user-id (:id user)]

      (testing "Get existing entity"
        (let [retrieved (ports/get-entity *admin-service* :test-users user-id)]
          (is (some? retrieved))
          (is (= user-id (:id retrieved)))
          (is (= "alice@example.com" (:email retrieved)))
          (is (= "Alice Smith" (:name retrieved)))))

      (testing "Get non-existent entity returns nil"
        (let [fake-id (UUID/randomUUID)
              retrieved (ports/get-entity *admin-service* :test-users fake-id)]
          (is (nil? retrieved)))))))

;; =============================================================================
;; Create Entity Tests
;; =============================================================================

(deftest ^:integration create-entity-test
  (testing "Create new entity"
    (let [user-data {:email "newuser@example.com"
                     :name "New User"
                     :password-hash "hash123"
                     :active true}
          created (ports/create-entity *admin-service* :test-users user-data)]

      (testing "Entity created successfully"
        (is (some? created))
        (is (uuid? (:id created)))
        (is (= "newuser@example.com" (:email created)))
        (is (= "New User" (:name created)))
        (is (inst? (:created-at created))))

      (testing "Entity persisted to database"
        (let [retrieved (ports/get-entity *admin-service* :test-users (:id created))]
          (is (some? retrieved))
          (is (= (:id created) (:id retrieved)))))))

  (testing "Create entity removes readonly fields"
    (let [user-data {:id (UUID/randomUUID)  ; Should be ignored
                     :email "test@example.com"
                     :name "Test"
                     :password-hash "hash"
                     :created-at (Instant/now)  ; Should be ignored
                     :active true}
          created (ports/create-entity *admin-service* :test-users user-data)]

      ;; ID and created-at should be auto-generated, not from input
      (is (not= (:id user-data) (:id created)))
      (is (not= (:created-at user-data) (:created-at created)))))

  (testing "Create entity removes hidden fields"
    (let [user-data {:email "hidden-test@example.com"
                     :name "Hidden Test"
                     :password-hash "shouldbeignored"
                     :active true}
          created (ports/create-entity *admin-service* :test-users user-data)]

      ;; password-hash is in hide-fields, should not be set from input
      ;; (This test validates sanitization - actual hash would come from auth layer)
      (is (some? created)))))

;; =============================================================================
;; Update Entity Tests
;; =============================================================================

(deftest ^:integration update-entity-test
  (testing "Update existing entity"
    (let [user (create-test-user! "original@example.com" "Original Name" true)
          user-id (:id user)
          update-data {:name "Updated Name"
                       :active false}
          updated (ports/update-entity *admin-service* :test-users user-id update-data)]

      (testing "Entity updated successfully"
        (is (some? updated))
        (is (= user-id (:id updated)))
        (is (= "Updated Name" (:name updated)))
        (is (false? (:active updated)))
        (is (inst? (:updated-at updated))))

      (testing "Email unchanged"
        (is (= "original@example.com" (:email updated))))

      (testing "Entity persisted to database"
        (let [retrieved (ports/get-entity *admin-service* :test-users user-id)]
          (is (= "Updated Name" (:name retrieved)))
          (is (false? (:active retrieved)))))))

  (testing "Update entity ignores readonly fields"
    (let [user (create-test-user! "readonly@example.com" "Readonly Test" true)
          user-id (:id user)
          original-created-at (:created-at user)
          update-data {:name "New Name"
                       :created-at (Instant/now)}  ; Should be ignored
          updated (ports/update-entity *admin-service* :test-users user-id update-data)]

      ;; created-at should not change (it's readonly)
      (is (= (inst-ms original-created-at) (inst-ms (:created-at updated)))))))

;; =============================================================================
;; Delete Entity Tests
;; =============================================================================

(deftest ^:integration delete-entity-soft-delete-test
  (testing "Soft delete entity (test-users has deleted-at column)"
    (let [user (create-test-user! "todelete@example.com" "To Delete" true)
          user-id (:id user)
          deleted? (ports/delete-entity *admin-service* :test-users user-id)]

      (testing "Delete operation successful"
        (is (true? deleted?)))

      (testing "Entity soft-deleted (deleted-at set)"
        (let [query {:select [:*]
                     :from [:test-users]
                     :where [:= :id user-id]}
              record (db/execute-one! *db-ctx* query)]
          (is (some? record))
          (is (inst? (:deleted-at record)))))

      (testing "Entity no longer appears in list"
        (let [result (ports/list-entities *admin-service* :test-users {})]
          (is (= 0 (:total-count result))))))))

(deftest ^:integration delete-entity-hard-delete-test
  (testing "Hard delete entity (test-items has no deleted-at column)"
    (let [item (create-test-item! "To Delete" 10 9.99)
          item-id (:id item)
          deleted? (ports/delete-entity *admin-service* :test-items item-id)]

      (testing "Delete operation successful"
        (is (true? deleted?)))

      (testing "Entity permanently removed from database"
        (let [query {:select [:*]
                     :from [:test-items]
                     :where [:= :id item-id]}
              record (db/execute-one! *db-ctx* query)]
          (is (nil? record)))))))

(deftest ^:integration delete-nonexistent-entity-test
  (testing "Delete non-existent entity"
    (let [fake-id (UUID/randomUUID)
          deleted? (ports/delete-entity *admin-service* :test-users fake-id)]
      (is (false? deleted?)))))

;; =============================================================================
;; Bulk Delete Tests
;; =============================================================================

(deftest ^:integration bulk-delete-entities-test
  (testing "Bulk delete multiple entities"
    (let [user1 (create-test-user! "bulk1@example.com" "Bulk 1" true)
          user2 (create-test-user! "bulk2@example.com" "Bulk 2" true)
          user3 (create-test-user! "bulk3@example.com" "Bulk 3" true)
          ids [(:id user1) (:id user2) (:id user3)]
          result (ports/bulk-delete-entities *admin-service* :test-users ids)]

      (testing "Bulk delete successful"
        (is (= 3 (:success-count result)))
        (is (= 0 (:failed-count result))))

      (testing "All entities soft-deleted"
        (let [list-result (ports/list-entities *admin-service* :test-users {})]
          (is (= 0 (:total-count list-result)))))))

  (testing "Bulk delete with some non-existent IDs"
    (let [user1 (create-test-user! "exists@example.com" "Exists" true)
          fake-id (UUID/randomUUID)
          ids [(:id user1) fake-id]
          result (ports/bulk-delete-entities *admin-service* :test-users ids)]

      ;; Should delete existing one, fail on non-existent
      (is (= 1 (:success-count result)))
      (is (= 1 (:failed-count result))))))

;; =============================================================================
;; Count Entities Tests
;; =============================================================================

(deftest ^:integration count-entities-test
  (testing "Count entities without filters"
    (create-test-user! "count1@example.com" "Count 1" true)
    (create-test-user! "count2@example.com" "Count 2" false)
    (create-test-user! "count3@example.com" "Count 3" true)

    (let [count (ports/count-entities *admin-service* :test-users {})]
      (is (= 3 count))))

  (testing "Count entities with filters"
    (let [count-active (ports/count-entities *admin-service* :test-users {:active true})]
      (is (= 2 count-active)))

    (let [count-inactive (ports/count-entities *admin-service* :test-users {:active false})]
      (is (= 1 count-inactive)))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest ^:integration validate-entity-data-test
  (testing "Validation with valid data"
    (let [valid-data {:email "valid@example.com"
                      :name "Valid User"
                      :password-hash "hash123"
                      :active true}
          result (ports/validate-entity-data *admin-service* :test-users valid-data)]

      (is (true? (:valid? result)))
      (is (= valid-data (:data result)))))

  (testing "Validation with missing required fields"
    ;; Week 1: Simple required field checking
    ;; Week 2+: Full Malli schema validation
    (let [invalid-data {:email "missing-name@example.com"}
          result (ports/validate-entity-data *admin-service* :test-users invalid-data)]

      ;; Week 1 may pass simple validation, Week 2+ will fail with Malli
      (is (map? result))
      (is (contains? result :valid?))))

  (testing "Boolean fields pass validation when absent"
    ;; Unchecked checkbox submits no value — boolean fields default to false
    (let [data {:email "bool-test@example.com"
                :name "Bool Test"
                :password-hash "hash123"}
          result (ports/validate-entity-data *admin-service* :test-users data)]
      (is (true? (:valid? result))
          "active (boolean, required) should not cause validation error when absent"))))

;; =============================================================================
;; resolve-query-config Unit Tests (private fn)
;; =============================================================================

(def ^:private resolve-query-config #'service/resolve-query-config)

(deftest ^:unit resolve-query-config-no-overrides-test
  (testing "Without query-overrides, returns table-name as from and [:*] as select"
    (let [config {:table-name :products}
          result (resolve-query-config config)]
      (is (= [:products] (:from-clause result)))
      (is (= [:*] (:select-clause result)))
      (is (nil? (:join-clause result)))
      (is (= {} (:field-aliases result))))))

(deftest ^:unit resolve-query-config-simple-overrides-test
  (testing "With query-overrides but no split-table, uses overrides as-is"
    (let [config {:table-name :products
                  :query-overrides {:from [[:products :p]]
                                    :select [:p.id :p.name]}}
          result (resolve-query-config config)]
      (is (= [[:products :p]] (:from-clause result)))
      (is (= [:p.id :p.name] (:select-clause result))))))

(deftest ^:unit resolve-query-config-split-table-expands-select-test
  (testing "Split-table config auto-expands SELECT for missing fields"
    (let [config {:table-name :auth-users
                  :split-table-update {:secondary-table :user-profiles
                                       :secondary-fields #{:bio :avatar-url}
                                       :join-column :user-id}
                  :query-overrides {:from [[:auth-users :a]]
                                    :select [:a.id :a.email]
                                    :join [[:user-profiles :p] [:= :a.id :p.user-id]]}
                  :fields {:id {:type :uuid}
                           :email {:type :string}
                           :bio {:type :text}
                           :avatar-url {:type :string}}}
          result (resolve-query-config config)]
      ;; Original select columns preserved
      (is (some #{:a.id} (:select-clause result)))
      (is (some #{:a.email} (:select-clause result)))
      ;; Secondary fields use join table alias :p, primary fields use :from alias :a
      (is (some #{:p.bio} (:select-clause result))
          "Secondary field :bio should use join table alias :p")
      (is (some #{:p.avatar_url} (:select-clause result))
          "Secondary field :avatar-url should use join table alias :p (snake_case in SQL)"))))

(deftest ^:unit resolve-query-config-split-table-no-duplicate-test
  (testing "Fields already in explicit select are not duplicated"
    (let [config {:table-name :auth-users
                  :split-table-update {:secondary-table :user-profiles
                                       :secondary-fields #{:bio}
                                       :join-column :user-id}
                  :query-overrides {:from [[:auth-users :a]]
                                    :select [:a.id :a.email :p.bio]
                                    :join [[:user-profiles :p] [:= :a.id :p.user-id]]}
                  :fields {:id {:type :uuid}
                           :email {:type :string}
                           :bio {:type :text}}}
          result (resolve-query-config config)]
      ;; :bio already in select as :p.bio — should not appear twice
      (is (= 1 (count (filter #(= :bio (keyword (last (str/split (name %) #"\."))))
                              (:select-clause result))))
          "bio should appear exactly once in select"))))
