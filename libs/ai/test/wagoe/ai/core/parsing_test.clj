(ns wagoe.ai.core.parsing-test
  (:require [wagoe.ai.core.parsing :as parsing]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit parse-json-response-test
  (testing "parses plain JSON"
    (let [result (parsing/parse-json-response "{\"key\": \"value\"}")]
      (is (= "value" (:key result)))))

  (testing "parses JSON wrapped in code fences"
    (let [result (parsing/parse-json-response "```json\n{\"key\": \"value\"}\n```")]
      (is (= "value" (:key result)))))

  (testing "returns error map for invalid JSON"
    (let [result (parsing/parse-json-response "not json at all")]
      (is (contains? result :error))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/parse-json-response nil)))))

(deftest ^:unit parse-module-spec-test
  (testing "parses valid module spec JSON"
    (let [json   "{\"module-name\": \"product\", \"entity\": \"Product\", \"fields\": [{\"name\": \"price\", \"type\": \"decimal\", \"required\": true, \"unique\": false}], \"http\": true, \"web\": true}"
          result (parsing/parse-module-spec json)]
      (is (= "product" (:module-name result)))
      (is (= "Product" (:entity result)))
      (is (= 1 (count (:fields result))))
      (is (= "price" (:name (first (:fields result)))))
      (is (= "decimal" (:type (first (:fields result)))))
      (is (true? (:http result)))
      (is (true? (:web result)))))

  (testing "returns error for missing module-name"
    (let [result (parsing/parse-module-spec "{\"entity\": \"Product\", \"fields\": []}")]
      (is (contains? result :error))))

  (testing "defaults invalid field type to string"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"invalid\"}]}"
          result (parsing/parse-module-spec json)]
      (is (= "string" (:type (first (:fields result)))))))

  (testing "defaults required to true"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"string\"}]}"
          result (parsing/parse-module-spec json)]
      (is (true? (:required (first (:fields result)))))))

  (testing "defaults unique to false"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"x\", \"type\": \"string\"}]}"
          result (parsing/parse-module-spec json)]
      (is (false? (:unique (first (:fields result)))))))

  (testing "preserves enum-values for enum fields"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"status\", \"type\": \"enum\", \"enum-values\": [\"active\", \"inactive\", \"archived\"]}]}"
          result (parsing/parse-module-spec json)
          field  (first (:fields result))]
      (is (= "enum" (:type field)))
      (is (= ["active" "inactive" "archived"] (:enum-values field)))))

  (testing "enum field without enum-values gets nil"
    (let [json   "{\"module-name\": \"p\", \"entity\": \"P\", \"fields\": [{\"name\": \"status\", \"type\": \"enum\"}]}"
          result (parsing/parse-module-spec json)
          field  (first (:fields result))]
      (is (= "enum" (:type field)))
      (is (nil? (:enum-values field))))))

(deftest ^:unit module-spec->cli-args-test
  (testing "generates correct CLI args for generate command"
    (let [spec {:module-name "product"
                :entity      "Product"
                :fields      [{:name "price" :type "decimal" :required true :unique false}]
                :http        true
                :web         true}
          args (parsing/module-spec->cli-args spec)]
      (is (= "generate" (first args)))
      (is (some #{"product"} args))
      (is (some #{"Product"} args))
      (is (some #{"--field"} args))
      (is (some #(str/includes? % "price") args))))

  (testing "adds --no-http when http is false"
    (let [spec {:module-name "p" :entity "P" :fields [] :http false :web true}
          args (parsing/module-spec->cli-args spec)]
      (is (some #{"--no-http"} args))))

  (testing "adds --no-web when web is false"
    (let [spec {:module-name "p" :entity "P" :fields [] :http true :web false}
          args (parsing/module-spec->cli-args spec)]
      (is (some #{"--no-web"} args)))))

(deftest ^:unit parse-sql-response-test
  (testing "parses SQL response JSON"
    (let [json   "{\"honeysql\": \"{:select [:*]}\", \"explanation\": \"selects all\", \"raw-sql\": \"SELECT *\"}"
          result (parsing/parse-sql-response json)]
      (is (= "{:select [:*]}" (:honeysql result)))
      (is (= "selects all" (:explanation result)))
      (is (= "SELECT *" (:raw-sql result))))))

(deftest ^:unit parse-generated-tests-test
  (testing "strips markdown code fences"
    (let [result (parsing/parse-generated-tests "```clojure\n(ns foo-test)\n```")]
      (is (= "(ns foo-test)" result))))

  (testing "returns trimmed plain Clojure"
    (let [result (parsing/parse-generated-tests "  (ns foo-test)  ")]
      (is (= "(ns foo-test)" result))))

  (testing "returns nil for nil input"
    (is (nil? (parsing/parse-generated-tests nil)))))
