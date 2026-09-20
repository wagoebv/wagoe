(ns wagoe.scaffolder.core.template-test
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [wagoe.scaffolder.core.template :as template]))

(deftest ^:unit kebab->pascal-test
  (testing "converts kebab-case to PascalCase"
    (is (= "UserProfile" (template/kebab->pascal "user-profile")))
    (is (= "Customer" (template/kebab->pascal "customer")))
    (is (= "OrderItem" (template/kebab->pascal "order-item")))))

(deftest ^:unit kebab->snake-test
  (testing "converts kebab-case to snake_case"
    (is (= "user_profile" (template/kebab->snake "user-profile")))
    (is (= "customer" (template/kebab->snake "customer")))
    (is (= "order_item" (template/kebab->snake "order-item")))))

(deftest ^:unit pluralize-test
  (testing "pluralizes singular nouns"
    (is (= "customers" (template/pluralize "customer")))
    (is (= "users" (template/pluralize "user")))))

(deftest ^:unit field-type->malli-test
  (testing "converts field types to Malli schemas"
    (is (= :string (template/field-type->malli {:type :string})))
    (is (= :uuid (template/field-type->malli {:type :uuid})))
    (is (= [:enum :active :inactive]
           (template/field-type->malli {:type :enum :enum-values [:active :inactive]})))

    (testing "decimal is BigDecimal, not a double — the type people scaffold money with"
      (let [schema (template/field-type->malli {:type :decimal})]
        (is (= 'decimal? schema))
        (is (m/validate schema 19.99M))
        (is (not (m/validate schema 19.99)))))))

(deftest ^:unit field-type->sql-test
  (testing "converts field types to SQL types"
    (is (= "VARCHAR(255)" (template/field-type->sql {:type :string})))
    (is (= "TEXT" (template/field-type->sql {:type :text})))
    (is (= "UUID" (template/field-type->sql {:type :uuid})))
    (is (= "INTEGER" (template/field-type->sql {:type :int})))
    (is (= "BOOLEAN" (template/field-type->sql {:type :boolean})))
    (is (= "VARCHAR(255)" (template/field-type->sql {:type :email})))
    (is (= "VARCHAR(50)" (template/field-type->sql {:type :enum})))
    (is (= "TIMESTAMPTZ" (template/field-type->sql {:type :inst})))
    (is (= "JSONB" (template/field-type->sql {:type :json})))
    ;; Not DOUBLE PRECISION: `--field price:decimal` is what anyone reaches
    ;; for when scaffolding money, and binary floating point is the wrong
    ;; representation for it (BOU-477).
    (is (= "DECIMAL(19,4)" (template/field-type->sql {:type :decimal})))))

(deftest ^:unit build-entity-context-test
  (testing "builds entity context for templates"
    (let [entity-def {:name "Customer"
                      :fields [{:name :email :type :email :required true}
                               {:name :name :type :string :required true}]}
          ctx (template/build-entity-context entity-def "customer")]
      (is (= "Customer" (:entity-name ctx)))
      (is (= "customer" (:entity-lower ctx)))
      (is (= "customer" (:entity-kebab ctx)))
      (is (= "customer" (:entity-snake ctx)))
      (is (= "customers" (:entity-plural ctx)))
      (is (= "customers" (:entity-table ctx)))
      (is (= 2 (count (:fields ctx)))))))

(deftest ^:unit build-module-context-test
  (testing "builds complete module context"
    (let [request {:module-name "customer"
                   :entities [{:name "Customer"
                               :fields [{:name :email :type :email}]}]
                   :interfaces {:http true}
                   :features {:audit true}}
          ctx (template/build-module-context request)]
      (is (= "customer" (:module-name ctx)))
      (is (= "Customer" (:module-pascal ctx)))
      (is (= 1 (count (:entities ctx))))
      (is (true? (get-in ctx [:interfaces :http])))
      (is (true? (get-in ctx [:features :audit]))))))
