(ns wagoe.platform.shell.adapters.database.utils.schema-test
  "Unit tests for Malli-to-DDL schema generation utilities.

   Tests cover:
   - Column type mapping across database dialects (H2, PostgreSQL, SQLite)
   - [:maybe ...] unwrapping in field extraction
   - DDL generation from Malli schemas
   - Index generation from schema analysis"
  (:require [wagoe.platform.shell.adapters.database.utils.schema :as schema]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.adapters.database.mysql.core :as mysql]
            [wagoe.platform.shell.adapters.database.postgresql.core :as postgresql]
            [wagoe.platform.shell.adapters.database.sqlite.core :as sqlite]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def h2-ctx
  "Minimal H2 context for DDL generation tests (no actual DB connection needed)."
  {:adapter (h2/new-adapter)})

;; Access private fns via var for targeted unit tests
(def ^:private column-type #'schema/column-type)
(def ^:private extract-field-info #'schema/extract-field-info)

;; =============================================================================
;; Column Type Mapping Tests
;; =============================================================================

(def ^:private expected-types
  "Malli type -> [H2 SQLite PostgreSQL MySQL]. What the builder made before
   BOU-610, but CLOB on H2, which reads back as a JdbcClob."
  (let [ts "TIMESTAMP WITH TIME ZONE"]
    {:uuid         ["UUID" "CHAR(36)" "UUID" "CHAR(36)"]
     :string       ["VARCHAR(255)" "TEXT" "VARCHAR(255)" "VARCHAR(255)"]
     :re           ["VARCHAR(255)" "TEXT" "VARCHAR(255)" "VARCHAR(255)"]
     :unknown-type ["VARCHAR(255)" "TEXT" "VARCHAR(255)" "VARCHAR(255)"]
     :int          ["INTEGER" "INTEGER" "INTEGER" "INT"]
     :bigint       ["BIGINT" "INTEGER" "BIGINT" "BIGINT"]
     :double       ["DOUBLE PRECISION" "REAL" "DOUBLE PRECISION" "DOUBLE"]
     :boolean      ["BOOLEAN" "INTEGER" "BOOLEAN" "TINYINT(1)"]
     :text         ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :enum         ["VARCHAR(50)" "TEXT" "VARCHAR(50)" "VARCHAR(50)"]
     :map          ["VARCHAR" "TEXT" "JSONB" "JSON"]
     ;; Serialised, so longer than a string: never VARCHAR(255) (BOU-715).
     :map-of       ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :vector       ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :sequential   ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :set          ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :tuple        ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     :any          ["VARCHAR" "TEXT" "TEXT" "LONGTEXT"]
     inst?         [ts "TEXT" ts "DATETIME(6)"]
     'inst?        [ts "TEXT" ts "DATETIME(6)"]}))

(deftest ^:unit the-builder-asks-the-adapter-for-every-type
  ;; One table answers, so a fresh table and an added column agree (BOU-610).
  (doseq [[malli-type types] expected-types
          [engine make expected] (map vector [:h2 :sqlite :postgresql :mysql]
                                      [h2/new-adapter sqlite/new-adapter postgresql/new-adapter mysql/new-adapter]
                                      types)]
    (is (= expected (column-type {:adapter (make)} {:type malli-type :properties {}}))
        (str engine " " malli-type))))

(deftest ^:unit a-string-gets-the-length-its-schema-allows
  (let [ddl-type (fn [make field] (column-type {:adapter (make)} (extract-field-info field)))]
    (doseq [make [h2/new-adapter postgresql/new-adapter mysql/new-adapter]]
      (is (= "VARCHAR(320)" (ddl-type make [:email [:string {:min 3 :max 320}]])))
      (is (= "VARCHAR(255)" (ddl-type make [:name [:string {:min 1}]])))
      (is (= "VARCHAR(255)" (ddl-type make [:schema-name [:string {:max 63}]])) "never shorter than the default")
      (is (= (ddl-type make [:x :text]) (ddl-type make [:note [:string {:max 5000}]]))
          "past the longest VARCHAR it is TEXT")
      (is (= (ddl-type make [:x :text]) (ddl-type make [:codes {:optional true} [:maybe [:vector :string]]]))))
    (is (= "TEXT" (ddl-type sqlite/new-adapter [:email [:string {:max 320}]])))))

;; =============================================================================
;; Field Extraction Tests ([:maybe ...] unwrapping)
;; =============================================================================

(deftest ^:unit extract-field-info-basic-test
  (testing "Extract field info from simple field definitions"
    (let [result (extract-field-info [:id :uuid])]
      (is (= "id" (:name result)))
      (is (= :uuid (:type result)))
      (is (false? (:optional? result))))

    (let [result (extract-field-info [:name :string])]
      (is (= "name" (:name result)))
      (is (= :string (:type result))))))

(deftest ^:unit extract-field-info-with-properties-test
  (testing "Extract field info with properties map"
    (let [result (extract-field-info [:email {:optional true} :string])]
      (is (= "email" (:name result)))
      (is (= :string (:type result)))
      (is (true? (:optional? result))))))

(deftest ^:unit extract-field-info-maybe-unwrapping-test
  (testing "[:maybe X] is unwrapped correctly"
    (testing "[:maybe inst?] → type is inst?"
      (let [result (extract-field-info [:created-at [:maybe 'inst?]])]
        (is (= "created-at" (:name result)))
        (is (= 'inst? (:type result)))
        (is (true? (:optional? result)))
        (is (= 'inst? (:schema result)))))

    (testing "[:maybe :string] → type is :string"
      (let [result (extract-field-info [:bio [:maybe :string]])]
        (is (= "bio" (:name result)))
        (is (= :string (:type result)))
        (is (true? (:optional? result)))))

    (testing "[:maybe [:enum :admin :user]] → type is :enum, schema preserves enum values"
      (let [result (extract-field-info [:role [:maybe [:enum :admin :user :viewer]]])]
        (is (= "role" (:name result)))
        (is (= :enum (:type result)))
        (is (true? (:optional? result)))
        (is (= [:enum :admin :user :viewer] (:schema result)))))))

(deftest ^:unit extract-field-info-enum-test
  (testing "Enum field without [:maybe] wrapper"
    (let [result (extract-field-info [:status [:enum :active :inactive]])]
      (is (= "status" (:name result)))
      (is (= :enum (:type result)))
      (is (= [:enum :active :inactive] (:schema result))))))

;; =============================================================================
;; DDL Generation Integration Tests
;; =============================================================================

(deftest ^:unit generate-table-ddl-basic-test
  (testing "Generate DDL for a simple schema"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:name :string]
                        [:active :boolean]]
          ddl (schema/generate-table-ddl h2-ctx "test_items" malli-schema)]
      (is (string? ddl))
      (is (.contains ddl "CREATE TABLE IF NOT EXISTS test_items"))
      (is (.contains ddl "id UUID NOT NULL PRIMARY KEY"))
      (is (.contains ddl "name VARCHAR(255) NOT NULL"))
      (is (.contains ddl "active BOOLEAN NOT NULL")))))

(deftest ^:unit generate-table-ddl-with-timestamps-test
  (testing "DDL with inst? timestamp fields"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:created-at inst?]
                        [:updated-at [:maybe inst?]]]
          ddl (schema/generate-table-ddl h2-ctx "events" malli-schema)]
      (is (.contains ddl "created_at TIMESTAMP WITH TIME ZONE NOT NULL"))
      (is (.contains ddl "updated_at TIMESTAMP WITH TIME ZONE")))))

(deftest ^:unit generate-table-ddl-with-text-test
  (testing "DDL with text field"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:payload :text]]
          ddl (schema/generate-table-ddl h2-ctx "payloads" malli-schema)]
      (is (.contains ddl "payload VARCHAR NOT NULL")))))

(deftest ^:unit generate-table-ddl-with-enum-test
  (testing "DDL with enum field includes CHECK constraint"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:role [:enum :admin :user :viewer]]]
          ddl (schema/generate-table-ddl h2-ctx "roles" malli-schema)]
      (is (.contains ddl "role VARCHAR(50)"))
      (is (.contains ddl "CHECK(role IN ('admin', 'user', 'viewer'))")))))

(deftest ^:unit generate-table-ddl-with-json-test
  (testing "DDL with map/JSON field"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:metadata :map]]
          ddl (schema/generate-table-ddl h2-ctx "configs" malli-schema)]
      (is (.contains ddl "metadata VARCHAR NOT NULL")))))

(deftest ^:unit generate-table-ddl-with-references-and-column-type-test
  (let [ddl (schema/generate-table-ddl h2-ctx "members"
                                       [:map
                                        [:id :uuid]
                                        [:group-id {:references :groups :on-delete :cascade} :uuid]
                                        [:owner-id {:references "owners" :on-delete :set-null} [:maybe :uuid]]
                                        [:tag-id :uuid]
                                        [:scope {:column-type :text} :map]])]
    (is (.contains ddl "CONSTRAINT fk_members_group_id FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE"))
    (is (.contains ddl "CONSTRAINT fk_members_owner_id FOREIGN KEY (owner_id) REFERENCES owners(id) ON DELETE SET NULL"))
    (is (not (.contains ddl "fk_members_tag_id")) "an -id field without :references gets no key")
    (is (.contains ddl "scope VARCHAR NOT NULL") ":column-type wins over the Malli type")))

(deftest ^:unit tables-are-created-after-the-ones-they-reference
  (let [order (map first (#'schema/creation-order
                          {"grants"  [:map [:id :uuid] [:group-id {:references :groups} :uuid]]
                           "members" [:map [:id :uuid] [:group-id {:references :groups} :uuid]
                                      [:user-id {:references :auth_users} :uuid]]
                           "groups"  [:map [:id :uuid] [:parent-id {:references :groups} [:maybe :uuid]]]}))]
    (is (= "groups" (first order)) "a reference outside the map, or to itself, does not hold a table back")
    (is (= #{"grants" "members"} (set (rest order))))))

(deftest ^:unit generate-table-ddl-with-double-test
  (testing "DDL with double precision field"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:price :double]]
          ddl (schema/generate-table-ddl h2-ctx "products" malli-schema)]
      (is (.contains ddl "price DOUBLE PRECISION NOT NULL")))))

;; =============================================================================
;; Index Generation Tests
;; =============================================================================

(deftest ^:unit generate-indexes-test
  (testing "Indexes generated for foreign key and timestamp fields"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:tenant-id :uuid]
                        [:created-at 'inst?]]]
      (is (= [["idx_items_tenant_id" "items" ["tenant_id"]]
              ["idx_items_created_at" "items" ["created_at"]]]
             (schema/generate-indexes "items" malli-schema))))))
