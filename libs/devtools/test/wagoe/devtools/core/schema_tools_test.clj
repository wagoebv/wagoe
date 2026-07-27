(ns wagoe.devtools.core.schema-tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.devtools.core.schema-tools :as schema-tools]))

(deftest ^:unit format-schema-tree-test
  (testing "renders field names and types for a flat :map schema"
    (let [schema [:map
                  [:id :uuid]
                  [:email [:string {:min 1}]]
                  [:role [:enum :admin :user :viewer]]]
          result (schema-tools/format-schema-tree schema)]
      (is (str/includes? result ":id"))
      (is (str/includes? result ":uuid"))
      (is (str/includes? result ":email"))
      (is (str/includes? result ":role"))
      (is (str/includes? result ":map"))))

  (testing "marks optional fields with (optional)"
    (let [schema [:map
                  [:id :uuid]
                  [:nickname {:optional true} :string]]
          result (schema-tools/format-schema-tree schema)]
      (is (str/includes? result "(optional)"))
      (is (not (str/includes? (str/replace result ":nickname" "") "(optional)(optional)")))))

  (testing "handles nested :map schema with indentation"
    (let [schema [:map
                  [:id :uuid]
                  [:address [:map
                             [:street :string]
                             [:city :string]]]]
          result (schema-tools/format-schema-tree schema)]
      (is (str/includes? result ":address"))
      (is (str/includes? result ":street"))
      (is (str/includes? result ":city")))))

(deftest ^:unit schema-diff-test
  (let [schema-a [:map [:id :uuid] [:name :string] [:email :string]]
        schema-b [:map [:id :uuid] [:email [:string {:min 5}]] [:role :keyword]]
        diff     (schema-tools/schema-diff schema-a schema-b)]
    (testing "detects added fields"
      (is (contains? (:added diff) :role)))

    (testing "detects removed fields"
      (is (contains? (:removed diff) :name)))

    (testing "detects changed fields"
      (is (contains? (:changed diff) :email)))

    (testing "does not flag unchanged fields as changed"
      (is (not (contains? (:changed diff) :id))))

    (testing "returns empty map for identical schemas"
      (is (= {} (schema-tools/schema-diff schema-a schema-a))))

    (testing "detects optionality change"
      (let [s1 [:map [:name :string]]
            s2 [:map [:name {:optional true} :string]]
            d  (schema-tools/schema-diff s1 s2)]
        (is (contains? (:changed d) :name))))

    (testing "detects nested map field change"
      (let [s1 [:map [:address [:map [:city :string] [:zip :string]]]]
            s2 [:map [:address [:map [:city :string] [:zip :int]]]]
            d  (schema-tools/schema-diff s1 s2)]
        (is (contains? (:changed d) :address))))))

(deftest ^:unit format-schema-diff-test
  (testing "returns identical message for empty diff"
    (is (= "Schemas are identical." (schema-tools/format-schema-diff {}))))

  (testing "shows Added section for added fields"
    (let [diff   {:added {:role ":keyword"}}
          result (schema-tools/format-schema-diff diff)]
      (is (str/includes? result "Added"))
      (is (str/includes? result ":role"))))

  (testing "shows Removed section for removed fields"
    (let [diff   {:removed {:name ":string"}}
          result (schema-tools/format-schema-diff diff)]
      (is (str/includes? result "Removed"))
      (is (str/includes? result ":name"))))

  (testing "shows Changed section for changed fields"
    (let [diff   {:changed {:email {:from ":string" :to "[:string {:min 5}]"}}}
          result (schema-tools/format-schema-diff diff)]
      (is (str/includes? result "Changed"))
      (is (str/includes? result ":email"))))

  (testing "formats a combined diff with all three sections"
    (let [diff   {:added   {:role ":keyword"}
                  :removed {:name ":string"}
                  :changed {:email {:from ":string" :to "[:string {:min 5}]"}}}
          result (schema-tools/format-schema-diff diff)]
      (is (str/includes? result "Added"))
      (is (str/includes? result "Removed"))
      (is (str/includes? result "Changed")))))

(deftest ^:unit generate-example-test
  (testing "generates a map with the expected keys"
    (let [schema [:map [:id :uuid] [:name :string] [:active :boolean]]
          result (schema-tools/generate-example schema)]
      (is (map? result))
      (is (contains? result :id))
      (is (contains? result :name))
      (is (contains? result :active))))

  (testing "generates uuid for :uuid field"
    (let [schema [:map [:id :uuid]]
          result (schema-tools/generate-example schema)]
      (is (uuid? (:id result)))))

  (testing "generates boolean for :boolean field"
    (let [schema [:map [:active :boolean]]
          result (schema-tools/generate-example schema)]
      (is (boolean? (:active result))))))
