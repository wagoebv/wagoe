(ns wagoe.devtools.core.prototype-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.core.prototype :as prototype]))

(def sample-spec
  {:fields {:customer [:string {:min 1}]
            :amount   [:decimal {:min 0}]
            :status   [:enum [:draft :sent :paid]]
            :due-date :date}
   :endpoints [:crud :list]})

(deftest ^:unit build-scaffold-context-test
  (testing "maps prototype spec to scaffolder-compatible context"
    (let [ctx (prototype/build-scaffold-context "invoice" sample-spec)]
      (is (= "invoice" (:module-name ctx)))
      (is (vector? (:entities ctx)))
      (is (= 1 (count (:entities ctx))))
      (let [entity (first (:entities ctx))]
        (is (= "invoice" (:entity-name entity)))
        (is (= 4 (count (:fields entity))))
        (let [first-field (first (:fields entity))]
          (is (contains? first-field :field-name-kebab))
          (is (contains? first-field :malli-type)))))))

(deftest ^:unit endpoints-to-generators-test
  (testing "maps endpoint keywords to generator function names"
    (let [generators (prototype/endpoints-to-generators [:crud :list :search])]
      (is (contains? (set generators) :schema))
      (is (contains? (set generators) :ports))
      (is (contains? (set generators) :core))
      (is (contains? (set generators) :persistence))
      (is (contains? (set generators) :service))
      (is (contains? (set generators) :http)))))

(deftest ^:unit build-migration-spec-test
  (testing "converts field spec to migration column definitions"
    (let [columns (prototype/build-migration-spec "invoice" (:fields sample-spec))]
      (is (vector? columns))
      (is (>= (count columns) 4))
      (is (some #(= :id (:name %)) columns)))))

(deftest ^:unit field-type-mapping-test
  (testing "maps Malli types to SQL types"
    (is (= "VARCHAR(255)" (prototype/malli->sql-type [:string {:min 1}])))
    (is (= "DECIMAL" (prototype/malli->sql-type [:decimal {:min 0}])))
    (is (= "DATE" (prototype/malli->sql-type :date)))
    (is (= "VARCHAR(50)" (prototype/malli->sql-type [:enum [:draft :sent :paid]])))
    (is (= "TEXT" (prototype/malli->sql-type :text)))
    (is (= "TEXT" (prototype/malli->sql-type :map)))
    (is (= "TEXT" (prototype/malli->sql-type :json)))))

(deftest ^:unit optional-unique-field-test
  (testing "optional field sets field-required false in scaffold context"
    (let [spec {:fields [[:nickname [:string {:optional true}]]]
                :endpoints [:crud]}
          ctx  (prototype/build-scaffold-context "user" spec)
          field (first (get-in ctx [:entities 0 :fields]))]
      (is (false? (:field-required field)))))

  (testing "unique field sets field-unique true in scaffold context"
    (let [spec {:fields [[:email [:email {:unique true}]]]
                :endpoints [:crud]}
          ctx  (prototype/build-scaffold-context "user" spec)
          field (first (get-in ctx [:entities 0 :fields]))]
      (is (true? (:field-unique field)))))

  (testing "migration respects optional (nullable) and unique"
    (let [fields [[:nickname [:string {:optional true}]]
                  [:email [:email {:unique true}]]
                  [:name :string]]
          columns (prototype/build-migration-spec "user" fields)
          nick-col (first (filter #(= :nickname (:name %)) columns))
          email-col (first (filter #(= :email (:name %)) columns))
          name-col (first (filter #(= :name (:name %)) columns))]
      (is (false? (:not-null nick-col)) "optional field should be nullable")
      (is (true? (:unique email-col)) "unique field should have unique constraint")
      (is (true? (:not-null name-col)) "required field should be NOT NULL")
      (is (nil? (:unique name-col)) "non-unique field should not have unique key"))))

(deftest ^:unit enum-form-test
  (testing "standard Malli enum [:enum :a :b :c] produces correct enum-values"
    (let [spec {:fields {:status [:enum :draft :sent :paid]}
                :endpoints [:crud]}
          ctx  (prototype/build-scaffold-context "test" spec)
          field (first (get-in ctx [:entities 0 :fields]))]
      (is (= [:enum :draft :sent :paid] (:malli-type field)))))

  (testing "nested vector enum [:enum [:a :b :c]] also works"
    (let [spec {:fields {:status [:enum [:draft :sent :paid]]}
                :endpoints [:crud]}
          ctx  (prototype/build-scaffold-context "test" spec)
          field (first (get-in ctx [:entities 0 :fields]))]
      (is (= [:enum :draft :sent :paid] (:malli-type field))))))
