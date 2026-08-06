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

;; =============================================================================
;; Generated source must satisfy the gates the generated project runs (BOU-267)
;; =============================================================================

(deftest ^:unit ports-file-declares-each-method-name-once
  ;; defprotocol interns its methods as vars in the namespace, so two protocols
  ;; in one ns cannot share a method name — the second silently wins. The
  ;; repository declared `update-<entity>` and so did the service, leaving
  ;; ports/update-<entity> with the service arity [this id data]. Loading the
  ;; generated module said so out loud:
  ;;   Warning: protocol #'…/IProductService is overwriting method
  ;;   update-product of protocol IProductRepository
  (testing "no method name appears in both protocols"
    (let [output  (gen/generate-ports-file base-ctx)
          methods (map second (re-seq #"(?m)^\s{2}\((\S+)\s+\[this" output))
          dupes   (->> methods frequencies (filter #(> (val %) 1)) (map key))]
      (is (seq methods) "generated no protocol methods at all")
      (is (empty? dupes)
          (str "method name(s) declared in more than one protocol: "
               (vec dupes) " — the later defprotocol overwrites the earlier")))))

(defn- repository-protocol-methods
  "Method names declared by the *repository* protocol only.

   Scoping matters: ports.clj holds both protocols, and the first version of
   this test compared against every method in the file. `list-<plural>` is
   declared there — by the service protocol — so the service calling it on the
   repository looked legitimate and the test passed with the bug present."
  [ports-out]
  (let [repo-block (-> ports-out
                       (str/split #"(?m)^;; Service Ports")
                       first)]
    (set (map second (re-seq #"(?m)^\s{2}\((\S+)\s+\[this" repo-block)))))

(deftest ^:unit service-calls-methods-the-repository-port-declares
  (testing "the service implementation only calls repository methods that exist"
    ;; It called (.list-<plural> repository opts), but the repository port has
    ;; find-all — so listing blew up at runtime the first time anyone tried it.
    (let [declared (repository-protocol-methods (gen/generate-ports-file base-ctx))
          called   (set (map second (re-seq #"\(\.(\S+)\s+repository"
                                            (gen/generate-service-file base-ctx))))
          missing  (remove declared called)]
      (is (seq called) "the service calls nothing on its repository")
      (is (contains? declared "find-all")
          "sanity: the repository block should contain find-all — if not, the
           block-splitting above has drifted and this test proves nothing")
      (is (empty? missing)
          (str "service calls " (vec missing)
               " on the repository, which its port does not declare")))))

(deftest ^:unit generated-source-has-no-unused-this-bindings
  (testing "record method bodies that ignore `this` name it `_this`"
    ;; clj-kondo warns on unused bindings and exits non-zero on warnings, so
    ;; `bb check` fails its linting step in the generated project.
    (doseq [[label f] [["service"     gen/generate-service-file]
                       ["persistence" gen/generate-persistence-file]]]
      (let [output (f base-ctx)]
        (is (not (re-find #"\(\S+\s+\[this[\s\]]" output))
            (str label ": has a method binding `this` that its body never uses; "
                 "name it _this"))))))

;; =============================================================================
;; BOU-275: every target schema is checked, not the file as a whole
;; =============================================================================
;;
;; `:already-present` was decided by searching the whole source for the field
;; name, so one schema could answer for the others: with the field in the entity
;; schema and a hand-restructured CreateXRequest, this reported that nothing
;; remained to be done while both request schemas still lacked it — the
;; unsynchronised Malli set of AGENTS.md pitfall 6, reported as success.

(def ^:private generated-schema
  "(ns app.widget.schema)

(def Widget
  [:map {:title \"Widget\"}
   [:id :uuid]])

(def CreateWidgetRequest
  [:map {:title \"Create Widget Request\"}
   [:id :uuid]])

(def UpdateWidgetRequest
  [:map {:title \"Update Widget Request\"}
   [:id :uuid]])
")

(def ^:private colour {:name :colour :type :string :required false})

(defn- hand-restructure
  "Rewrite one def into a shape the inserter cannot place a field in."
  [source schema-name]
  (str/replace source
               (re-pattern (str "\\(def " schema-name "\\n  \\[:map \\{:title \"[^\"]+\"\\}\\n   \\[:id :uuid\\]\\]\\)"))
               (str "(def " schema-name "\n  (m/schema [:map [:id :uuid]]))")))

(deftest ^:unit add-field-to-schema-covers-every-target
  (testing "all three schemas are edited, and nothing is left unreachable"
    (let [r (gen/add-field-to-schema generated-schema "Widget" colour)]
      (is (= :updated (:status r)))
      (is (= ["Widget" "CreateWidgetRequest" "UpdateWidgetRequest"] (:schemas r)))
      (is (empty? (:unreachable r)))))

  (testing "re-running is a no-op only when every target already has the field"
    (let [once  (gen/add-field-to-schema generated-schema "Widget" colour)
          twice (gen/add-field-to-schema (:content once) "Widget" colour)]
      (is (= :already-present (:reason twice)))
      (is (empty? (:unreachable twice)))))

  (testing "a target that cannot be edited is named, even when others succeeded"
    ;; Partial success is still partial: two of three schemas updated is a set
    ;; that does not agree with itself, and the caller has to be told.
    (let [src (hand-restructure generated-schema "UpdateWidgetRequest")
          r   (gen/add-field-to-schema src "Widget" colour)]
      (is (= :updated (:status r)))
      (is (= ["Widget" "CreateWidgetRequest"] (:schemas r)))
      (is (= ["UpdateWidgetRequest"] (:unreachable r))
          "silence here leaves the request schema without the field")))

  (testing "the field being in one schema does not answer for the others"
    ;; The reported case: entity has it, request schemas are unreachable.
    ;; A file-wide search found the field and reported :already-present.
    (let [src (-> generated-schema
                  (hand-restructure "CreateWidgetRequest")
                  (hand-restructure "UpdateWidgetRequest")
                  (str/replace "[:map {:title \"Widget\"}\n   [:id :uuid]])"
                               "[:map {:title \"Widget\"}\n   [:id :uuid]\n   [:colour {:optional true} :string]])"))
          r   (gen/add-field-to-schema src "Widget" colour)]
      (is (= :skipped (:status r)))
      (is (= :unrecognised-shape (:reason r))
          "not :already-present — the request schemas still lack the field")
      (is (= ["CreateWidgetRequest" "UpdateWidgetRequest"] (:unreachable r))))))

(deftest ^:unit insert-schema-entry-distinguishes-its-outcomes
  (testing "inserted, present and unrecognised are three different answers"
    (let [entry "[:colour {:optional true} :string]"]
      (is (= :inserted (:status (gen/insert-schema-entry generated-schema "Widget" entry))))
      (is (= :present
             (:status (gen/insert-schema-entry
                       (:content (gen/insert-schema-entry generated-schema "Widget" entry))
                       "Widget" entry)))
          "already there — nothing to do")
      (is (= :unrecognised
             (:status (gen/insert-schema-entry generated-schema "NoSuchSchema" entry)))
          "cannot be done — which is not the same as nothing to do"))))
