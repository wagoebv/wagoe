(ns wagoe.platform.shell.adapters.database.utils.schema-test
  "Unit tests for Malli-to-DDL schema generation utilities.

   Tests cover:
   - Column type mapping across database dialects (H2, PostgreSQL, SQLite)
   - [:maybe ...] unwrapping in field extraction
   - DDL generation from Malli schemas
   - Index generation from schema analysis"
  (:require [wagoe.platform.shell.adapters.database.utils.schema :as schema]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def h2-ctx
  "Minimal H2 context for DDL generation tests (no actual DB connection needed)."
  {:adapter (h2/new-adapter)})

;; Access private fns via var for targeted unit tests
(def ^:private malli-type->column-type #'schema/malli-type->column-type)
(def ^:private extract-field-info #'schema/extract-field-info)

;; =============================================================================
;; Column Type Mapping Tests
;; =============================================================================

(deftest ^:unit malli-type->column-type-h2-test
  (testing "Malli type to H2 column type mapping"
    (testing "Basic types"
      (is (= "UUID" (malli-type->column-type h2-ctx :uuid)))
      (is (= "VARCHAR(255)" (malli-type->column-type h2-ctx :string)))
      (is (= "INTEGER" (malli-type->column-type h2-ctx :int)))
      (is (= "BOOLEAN" (malli-type->column-type h2-ctx :boolean))))

    (testing "Timestamp type (inst? symbol)"
      (is (= "TIMESTAMP WITH TIME ZONE" (malli-type->column-type h2-ctx 'inst?))))

    (testing "Timestamp type (inst? function)"
      (is (= "TIMESTAMP WITH TIME ZONE" (malli-type->column-type h2-ctx inst?))))

    (testing "Enum type"
      (is (= "VARCHAR(50)" (malli-type->column-type h2-ctx :enum))))

    (testing "Numeric types"
      (is (= "DOUBLE PRECISION" (malli-type->column-type h2-ctx :double))))

    (testing "JSON/Map type"
      (is (= "CLOB" (malli-type->column-type h2-ctx :map))))

    (testing "Text type"
      (is (= "CLOB" (malli-type->column-type h2-ctx :text))))

    (testing "Regex type (email pattern etc.)"
      (is (= "VARCHAR(255)" (malli-type->column-type h2-ctx :re))))

    (testing "Unknown type falls back to VARCHAR(255)"
      (is (= "VARCHAR(255)" (malli-type->column-type h2-ctx :unknown-type))))))

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
      (is (.contains ddl "payload CLOB NOT NULL")))))

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
      (is (.contains ddl "metadata CLOB NOT NULL")))))

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

(deftest ^:unit generate-indexes-ddl-test
  (testing "Indexes generated for foreign key fields"
    (let [malli-schema [:map
                        [:id :uuid]
                        [:tenant-id :uuid]
                        [:created-at 'inst?]]
          indexes (schema/generate-indexes-ddl h2-ctx "items" malli-schema)]
      (is (vector? indexes))
      (is (some #(.contains % "idx_items_tenant_id") indexes))
      (is (some #(.contains % "idx_items_created_at") indexes)))))
