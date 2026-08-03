(ns wagoe.scaffolder.core.generators-test
  "Unit tests for pure scaffolder generator functions.
   Asserts that generated file content contains expected strings."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.scaffolder.core.generators :as gen]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(def ^:private base-field-required
  {:field-name-kebab "name"
   :field-name-snake "name"
   :malli-type ":string"
   :field-required true
   :field-unique false
   :sql-type "VARCHAR(255)"})

(def ^:private base-field-optional
  {:field-name-kebab "description"
   :field-name-snake "description"
   :malli-type ":string"
   :field-required false
   :field-unique false
   :sql-type "TEXT"})

(def ^:private base-ctx
  {:module-name "product"
   :entities
   [{:entity-name "Product"
     :entity-lower "product"
     :entity-kebab "product"
     :entity-table "products"
     :fields [base-field-required base-field-optional]}]})

;; =============================================================================
;; generate-field-schema
;; =============================================================================

(deftest ^:unit generate-field-schema-test
  (testing "required field has no :optional true"
    (let [output (gen/generate-field-schema base-field-required)]
      (is (str/includes? output ":name"))
      (is (str/includes? output ":string"))
      (is (not (str/includes? output ":optional")))))

  (testing "optional field includes :optional true"
    (let [output (gen/generate-field-schema base-field-optional)]
      (is (str/includes? output ":description"))
      (is (str/includes? output ":optional true"))
      (is (str/includes? output ":string")))))

;; =============================================================================
;; generate-schema-file
;; =============================================================================

(deftest ^:unit generate-schema-file-test
  (let [output (gen/generate-schema-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.schema")))

    (testing "contains entity schema definition"
      (is (str/includes? output "(def Product")))

    (testing "contains field schemas"
      (is (str/includes? output ":name"))
      (is (str/includes? output ":description")))

    (testing "contains standard timestamp fields"
      (is (str/includes? output ":created-at"))
      (is (str/includes? output ":updated-at")))

    (testing "contains Create and Update request schemas"
      (is (str/includes? output "CreateProductRequest"))
      (is (str/includes? output "UpdateProductRequest")))

    (testing "contains validation functions"
      (is (str/includes? output "validate-product"))
      (is (str/includes? output "explain-product")))))

;; =============================================================================
;; generate-ports-file
;; =============================================================================

(deftest ^:unit generate-ports-file-test
  (let [output (gen/generate-ports-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.ports")))

    (testing "contains repository protocol"
      (is (str/includes? output "IProductRepository")))

    (testing "contains service protocol"
      (is (str/includes? output "IProductService")))

    (testing "contains CRUD method names"
      (is (str/includes? output "find-by-id"))
      (is (str/includes? output "find-all"))
      (is (str/includes? output "create"))
      (is (str/includes? output "delete")))))

;; =============================================================================
;; generate-core-file
;; =============================================================================

(deftest ^:unit generate-core-file-test
  (let [output (gen/generate-core-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.core.")))

    (testing "contains prepare-new function"
      (is (str/includes? output "prepare-new-product")))

    (testing "generated core creation is pure"
      (is (str/includes? output "[data entity-id current-time]"))
      (is (str/includes? output "{:id entity-id"))
      (is (not (str/includes? output "java.util.UUID/randomUUID"))))

    (testing "generated core does not inline forbidden runtime access"
      (doseq [forbidden ["UUID/randomUUID"
                         "Instant/now"
                         "LocalDate/now"
                         "LocalDateTime/now"
                         "OffsetDateTime/now"
                         "ZonedDateTime/now"
                         "ZoneId/systemDefault"
                         "System/currentTimeMillis"
                         "ProcessHandle/current"]]
        (is (not (str/includes? output forbidden))
            (str "generated core should not contain " forbidden))))

    (testing "contains apply-update function"
      (is (str/includes? output "apply-product-update")))

    (testing "contains validate function"
      (is (str/includes? output "validate-product")))))

;; =============================================================================
;; generate-migration-field
;; =============================================================================

(deftest ^:unit generate-migration-field-test
  (testing "required field has NOT NULL"
    (let [output (gen/generate-migration-field base-field-required)]
      (is (str/includes? output "name"))
      (is (str/includes? output "VARCHAR(255)"))
      (is (str/includes? output "NOT NULL"))))

  (testing "optional field does not have NOT NULL"
    (let [output (gen/generate-migration-field base-field-optional)]
      (is (not (str/includes? output "NOT NULL")))))

  (testing "unique field includes UNIQUE"
    (let [unique-field (assoc base-field-required :field-unique true)
          output (gen/generate-migration-field unique-field)]
      (is (str/includes? output "UNIQUE")))))

;; =============================================================================
;; generate-migration-file
;; =============================================================================

(deftest ^:unit generate-migration-file-test
  (let [output (gen/generate-migration-file base-ctx "005")]

    (testing "contains migration number"
      (is (str/includes? output "005")))

    (testing "contains CREATE TABLE statement"
      (is (str/includes? output "CREATE TABLE IF NOT EXISTS products")))

    (testing "contains id primary key"
      (is (str/includes? output "id UUID PRIMARY KEY")))

    (testing "contains standard timestamp columns"
      (is (str/includes? output "created_at"))
      (is (str/includes? output "updated_at")))

    (testing "contains index creation"
      (is (str/includes? output "CREATE INDEX")))))

;; =============================================================================
;; generate-ui-file
;; =============================================================================

(deftest ^:unit generate-ui-file-test
  (let [output (gen/generate-ui-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.core.ui")))

    (testing "contains list page function"
      (is (str/includes? output "product-list-page")))))

;; =============================================================================
;; generate-service-file
;; =============================================================================

(deftest ^:unit generate-service-file-test
  (let [output (gen/generate-service-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.shell.service")))

    (testing "contains service record"
      (is (str/includes? output "ProductService")))

    (testing "shell owns runtime generation"
      (is (str/includes? output "(defn- current-time"))
      (is (str/includes? output "(defn- generate-product-id"))
      (is (str/includes? output "(UUID/randomUUID)"))
      (is (str/includes? output "(Instant/now)"))
      (is (str/includes? output "(core/prepare-new-product data (generate-product-id) (current-time))")))

    (testing "contains factory function"
      (is (str/includes? output "create-service")))))

;; =============================================================================
;; generate-persistence-file
;; =============================================================================

(deftest ^:unit generate-persistence-file-test
  (let [output (gen/generate-persistence-file base-ctx)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.product.shell.persistence")))

    (testing "contains database repository record"
      (is (str/includes? output "DatabaseProductRepository")))

    (testing "contains factory function"
      (is (str/includes? output "create-repository")))))

;; =============================================================================
;; generate-add-field-migration
;; =============================================================================

(deftest ^:unit generate-add-field-migration-test
  (let [field {:name "price" :type :decimal :required true :unique false}
        output (gen/generate-add-field-migration "product" "Product" field "006")]

    (testing "contains migration number"
      (is (str/includes? output "006")))

    (testing "contains ALTER TABLE"
      (is (str/includes? output "ALTER TABLE")))

    (testing "contains ADD COLUMN"
      (is (str/includes? output "ADD COLUMN")))))

;; =============================================================================
;; generate-adapter-file
;; =============================================================================

(deftest ^:unit generate-adapter-file-test
  (let [methods [{:name "get-value" :args ["key"]}
                 {:name "set-value" :args ["key" "value"]}]
        output (gen/generate-adapter-file "cache" "ICache" "redis" methods)]

    (testing "contains correct namespace"
      (is (str/includes? output "(ns wagoe.cache.shell.adapters.redis")))

    (testing "contains record definition"
      (is (str/includes? output "RedisCache")))

    (testing "contains all method stubs"
      (is (str/includes? output "get-value"))
      (is (str/includes? output "set-value")))

    (testing "contains factory function"
      (is (str/includes? output "create-redis-cache")))))

;; =============================================================================
;; base-ns parameterization (BOU-205)
;; =============================================================================

(deftest ^:unit base-ns-parameterizes-module-namespaces
  (testing "default base-ns is wagoe (behavior unchanged)"
    (is (str/includes? (gen/generate-schema-file base-ctx) "(ns wagoe.product.schema"))
    (is (str/includes? (gen/generate-service-file base-ctx) "(ns wagoe.product.shell.service"))
    (is (str/includes? (gen/generate-service-file base-ctx) "[wagoe.product.ports")))

  (testing "custom base-ns drives the module ns and its internal requires"
    (let [ctx (assoc base-ctx :base-ns "myapp")]
      (is (str/includes? (gen/generate-schema-file ctx) "(ns myapp.product.schema"))
      (is (str/includes? (gen/generate-service-file ctx) "(ns myapp.product.shell.service"))
      (is (str/includes? (gen/generate-service-file ctx) "[myapp.product.ports"))
      (is (str/includes? (gen/generate-persistence-file ctx) "(ns myapp.product.shell.persistence"))
      (is (str/includes? (gen/generate-core-test-file ctx) "(ns myapp.product.core"))
      (testing "but framework requires stay under wagoe.platform"
        (is (str/includes? (gen/generate-persistence-file ctx) "wagoe.platform")))))

  (testing "a dotted base-ns keeps its dots in the namespace"
    (is (str/includes? (gen/generate-schema-file (assoc base-ctx :base-ns "com.acme"))
                       "(ns com.acme.product.schema"))))

;; =============================================================================
;; Generated tests must pass the gates the generated project runs (BOU-264)
;; =============================================================================

;; `bb check` runs check:test-tags and check:placeholder-tests in generated
;; projects. The scaffolder emitted untagged deftests and an `(is true)`, so
;; `bb check` failed the moment a user scaffolded their first module — while
;; AGENTS.md claimed the scaffolder "auto-generates unit test skeletons with
;; correct metadata". Nothing here asserted it, so nothing caught it.

(def ^:private generated-test-files
  [["core"        gen/generate-core-test-file]
   ["service"     gen/generate-service-test-file]
   ["persistence" gen/generate-persistence-test-file]])

(deftest ^:unit generated-tests-carry-exactly-one-pyramid-tag
  (doseq [[label f] generated-test-files]
    (testing (str label " test file")
      (let [output (f base-ctx)
            deftests (re-seq #"\(deftest\s+([^\s]+)" output)
            tags     (re-seq #"\^:(unit|integration|contract)\b" output)]
        (is (seq deftests) (str label ": generated no deftest at all"))
        (is (= (count deftests) (count tags))
            (str label ": " (count deftests) " deftest(s) but " (count tags)
                 " pyramid tag(s) — check:test-tags requires exactly one each"))))))

(deftest ^:unit generated-tests-contain-no-placeholder-assertions
  (doseq [[label f] generated-test-files]
    (testing (str label " test file")
      (let [output (f base-ctx)]
        (is (not (re-find #"\(is\s+true\s*\)" output))
            (str label ": emits (is true), which check:placeholder-tests rejects"))))))

(deftest ^:unit generated-tests-use-what-they-require
  (testing "persistence test asserts through both of its requires"
    ;; It required persistence and ports and then asserted (is true), so
    ;; clj-kondo flagged both as unused — a warning, and `bb check` fails the
    ;; linting step on warnings.
    (let [output (gen/generate-persistence-test-file base-ctx)]
      (is (re-find #"persistence/" output))
      (is (re-find #"ports/" output)))))
