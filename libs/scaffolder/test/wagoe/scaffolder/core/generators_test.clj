(ns wagoe.scaffolder.core.generators-test
  "Unit tests for pure scaffolder generator functions.
   Asserts that generated file content contains expected strings."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.scaffolder.core.template :as template]
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

;; =============================================================================
;; BOU-275: update requests are partials
;; =============================================================================
;;
;; A required field belongs in the entity and create schemas. In
;; Update<Entity>Request it means every caller doing a partial update must now
;; send the new field or fail validation — for a field that did not exist a
;; moment ago. libs/scaffolder/AGENTS.md documents update requests as marking
;; every field optional.
;;
;; Both commands got this wrong the same way: module generation interpolated one
;; field list into all three schemas, and `add-field` applied one entry to all
;; three targets.

(def ^:private required-field {:name :sku :type :string :required true})

(deftest ^:unit required-fields-stay-optional-in-update-requests
  (testing "add-field: required in the entity and create schemas, optional in update"
    (let [r   (gen/add-field-to-schema generated-schema "Widget" required-field)
          out (:content r)
          entry-in (fn [schema-name]
                     (let [block (re-find (re-pattern (str "(?s)\\(def " schema-name "\\n.*?\\]\\)")) out)]
                       ;; Non-greedy: a greedy match ran on to the map's closing bracket.
                       (second (re-find #"(\[:sku.*?\])" block))))]
      (is (= :updated (:status r)))
      (is (= "[:sku :string]" (entry-in "Widget"))
          "the entity really does require it")
      (is (= "[:sku :string]" (entry-in "CreateWidgetRequest"))
          "and so does creation")
      (is (= "[:sku {:optional true} :string]" (entry-in "UpdateWidgetRequest"))
          "but an update is a partial — requiring it here breaks every existing caller")))

  (testing "generate-field-schema: the same rule at module generation"
    (let [ctx (template/build-field-context required-field)]
      (is (= "   [:sku :string]" (gen/generate-field-schema ctx)))
      (is (= "   [:sku {:optional true} :string]" (gen/generate-field-schema ctx true)))))

  (testing "an already-optional field is unaffected either way"
    (let [ctx (template/build-field-context {:name :note :type :string :required false})]
      (is (= (gen/generate-field-schema ctx) (gen/generate-field-schema ctx true))))))

(deftest ^:unit generated-update-schema-marks-fields-optional
  (testing "a generated module's update request accepts a partial"
    ;; The same `field-schemas` string was interpolated into the entity, create
    ;; and update schemas, so `--field name:string:required` produced a required
    ;; entry in UpdateXRequest.
    (let [src (gen/generate-schema-file
               {:base-ns "app"
                :module-name "widget"
                :entities [{:entity-name "Widget"
                            :fields [(template/build-field-context required-field)]}]})
          update-block (re-find #"(?s)\(def UpdateWidgetRequest\n.*?\]\)" src)
          create-block (re-find #"(?s)\(def CreateWidgetRequest\n.*?\]\)" src)]
      (is (str/includes? create-block "[:sku :string]")
          "creation requires it")
      (is (str/includes? update-block "[:sku {:optional true} :string]")
          "updating does not"))))

(deftest ^:unit a-required-entry-in-the-update-schema-is-not-done
  ;; Presence was checked by key alone, so an entry that exists in the wrong
  ;; shape counted as nothing-to-do. A project generated before update requests
  ;; became partials carries the required form there; rerunning
  ;; `bb scaffold field --required` reported "field is already in every schema"
  ;; and left partial updates requiring the field.
  (let [required-entry "[:sku :string]"
        optional-entry "[:sku {:optional true} :string]"
        with-sku (fn [update-entry]
                   (-> generated-schema
                       (str/replace "(def Widget\n  [:map {:title \"Widget\"}\n   [:id :uuid]])"
                                    (str "(def Widget\n  [:map {:title \"Widget\"}\n   [:id :uuid]\n   " required-entry "])"))
                       (str/replace "(def CreateWidgetRequest\n  [:map {:title \"Create Widget Request\"}\n   [:id :uuid]])"
                                    (str "(def CreateWidgetRequest\n  [:map {:title \"Create Widget Request\"}\n   [:id :uuid]\n   " required-entry "])"))
                       (str/replace "(def UpdateWidgetRequest\n  [:map {:title \"Update Widget Request\"}\n   [:id :uuid]])"
                                    (str "(def UpdateWidgetRequest\n  [:map {:title \"Update Widget Request\"}\n   [:id :uuid]\n   " update-entry "])"))))]

    (testing "the required form in the update schema is reported as work remaining"
      (let [r (gen/add-field-to-schema (with-sku required-entry) "Widget" required-field)]
        (is (= :skipped (:status r)))
        (is (= :requires-optional (:reason r))
            "not :already-present — partial updates are still broken")
        (is (= ["UpdateWidgetRequest"] (:wrong-shape r)))))

    (testing "the optional form is genuinely done"
      (let [r (gen/add-field-to-schema (with-sku optional-entry) "Widget" required-field)]
        (is (= :already-present (:reason r)))
        (is (empty? (:wrong-shape r)))))

    (testing "only the update schema is judged on optionality"
      ;; A tightened type or a hand-chosen optionality elsewhere is the user's
      ;; business. Flagging every difference would nag about customisation this
      ;; tool has no opinion on.
      (let [src (-> (with-sku optional-entry)
                    (str/replace "(def Widget\n  [:map {:title \"Widget\"}\n   [:id :uuid]\n   [:sku :string]])"
                                 "(def Widget\n  [:map {:title \"Widget\"}\n   [:id :uuid]\n   [:sku {:optional true} [:string {:min 3}]]])"))
            r   (gen/add-field-to-schema src "Widget" required-field)]
        (is (= :already-present (:reason r))
            "a customised entity entry is not this tool's concern")))

    (testing "an optional field is never flagged"
      ;; Nothing to enforce: the desired update entry is optional either way.
      (let [optional-field {:name :sku :type :string :required false}
            r (gen/add-field-to-schema (with-sku optional-entry) "Widget" optional-field)]
        (is (= :already-present (:reason r)))
        (is (empty? (:wrong-shape r)))))))

(deftest ^:unit def-form-range-stops-at-the-next-form
  ;; The scan for the closing line had no upper bound, so a def it could not
  ;; recognise — one ending `]))` rather than `])` — handed back the *next*
  ;; def's closing line. Asked to add a field to a hand-restructured
  ;; CreateItemRequest, it wrote the entry into UpdateItemRequest and reported
  ;; :inserted: the wrong schema, in the required form, in the one schema that
  ;; must not have it.
  (let [src (str "(ns app.item.schema)\n\n"
                 "(def CreateItemRequest\n  \"hand-restructured\"\n  (m/schema [:map [:name :string]]))\n\n"
                 "(def UpdateItemRequest\n  [:map {:title \"Update Item Request\"}\n   [:name {:optional true} :string]])\n")]

    (testing "an unrecognised def does not borrow the next one's closing line"
      (is (= {:status :unrecognised}
             (gen/insert-schema-entry src "CreateItemRequest" "[:sku :string]"))))

    (testing "and the following schema is left alone"
      (is (nil? (:content (gen/insert-schema-entry src "CreateItemRequest" "[:sku :string]")))
          "returning content here meant a write into UpdateItemRequest"))

    (testing "the last def in a file still resolves"
      ;; The bound is the next top-level form, so the final def has none.
      (is (= :inserted
             (:status (gen/insert-schema-entry src "UpdateItemRequest"
                                               "[:sku {:optional true} :string]")))))))

(deftest ^:unit mixed-schema-states-report-every-remaining-target
  ;; One schema unplaceable and another carrying the required form is the case
  ;; that went half-reported: two `assoc` clauses wrote :manual-note in turn, so
  ;; the second overwrote the first, and the skipped arm dropped the unreachable
  ;; list entirely. Following those instructions left the set unsynchronised.
  (let [src (str "(ns app.item.schema)\n\n"
                 "(def Item\n  [:map {:title \"Item\"}\n   [:id :uuid]])\n\n"
                 "(def CreateItemRequest\n  \"hand-restructured\"\n  (m/schema [:map [:id :uuid]]))\n\n"
                 "(def UpdateItemRequest\n  [:map {:title \"Update Item Request\"}\n   [:id :uuid]\n   [:sku :string]])\n")
        r   (gen/add-field-to-schema src "Item" required-field)]
    (testing "both categories survive into the result"
      (is (= ["CreateItemRequest"] (:unreachable r))
          "unplaceable, and the user has to be told")
      (is (= ["UpdateItemRequest"] (:wrong-shape r))
          "present but required, and the user has to be told that too"))))

;; =============================================================================
;; The schema edit parses the file rather than matching lines
;; =============================================================================
;;
;; The line-based inserter scanned for a closing `])` and could run past the def
;; it was asked to edit. These pin the properties that switching to rewrite-clj
;; makes structural rather than incidental.

(deftest ^:unit schema-edits-preserve-everything-they-do-not-touch
  (let [src (str "(ns app.item.schema)\n\n"
                 ";; A comment above the entity.\n"
                 "(def Item\n  \"Schema for Item entity.\"\n"
                 "  [:map {:title \"Item\"}\n"
                 "   ;; a comment inside the map\n"
                 "   [:id :uuid]   ; and a trailing one\n"
                 "   [:name :string]])\n")
        r   (gen/insert-schema-entry src "Item" "[:sku {:optional true} :string]")]

    (testing "comments survive"
      (is (str/includes? (:content r) ";; A comment above the entity."))
      (is (str/includes? (:content r) ";; a comment inside the map"))
      (is (str/includes? (:content r) "; and a trailing one")))

    (testing "the trailing newline survives"
      (is (str/ends-with? (:content r) "\n")))

    (testing "exactly one line is added"
      (is (= (inc (count (str/split-lines src)))
             (count (str/split-lines (:content r))))))

    (testing "the schema gains the entry and changes in no other way"
      ;; Semantic, not textual: the closing bracket legitimately moves onto the
      ;; new line, so the old last line is not present verbatim and should not
      ;; be asserted to be.
      (let [schema-of (fn [source]
                        (->> (read-string (str "[" source "]"))
                             (filter #(and (seq? %) (= (quote def) (first %))))
                             (filter #(= (quote Item) (second %)))
                             first last))]
        (is (= (conj (schema-of src) [:sku {:optional true} :string])
               (schema-of (:content r))))))))

(deftest ^:unit outcomes-are-one-collection
  ;; The four parallel vectors are views over a single per-target result, so
  ;; they cannot disagree about what happened to a schema.
  (let [src (str "(ns app.item.schema)\n\n"
                 "(def Item\n  [:map {:title \"Item\"}\n   [:id :uuid]])\n\n"
                 "(def CreateItemRequest\n  \"hand-restructured\"\n  (m/schema [:map [:id :uuid]]))\n\n"
                 "(def UpdateItemRequest\n  [:map {:title \"Update Item Request\"}\n   [:id :uuid]\n   [:sku :string]])\n")
        r   (gen/add-field-to-schema src "Item" required-field)]
    (testing "every target appears exactly once, in order"
      (is (= ["Item" "CreateItemRequest" "UpdateItemRequest"]
             (mapv :schema (:outcomes r))))
      (is (= [:inserted :unreachable :needs-optional]
             (mapv :status (:outcomes r)))))

    (testing "the reported vectors are derived from it"
      (is (= (mapv :schema (filter #(= :unreachable (:status %)) (:outcomes r)))
             (:unreachable r)))
      (is (= (mapv :schema (filter #(= :needs-optional (:status %)) (:outcomes r)))
             (:wrong-shape r)))
      (is (= (mapv :schema (filter #(= :inserted (:status %)) (:outcomes r)))
             (:schemas r))))))

(deftest ^:unit every-supported-field-type-can-be-inserted
  ;; The parser rewrite read entries with clojure.edn, which has no dispatch
  ;; macro for #"…". :email renders [:re {…} #"…"], so `bb scaffold field
  ;; --type email` threw `No dispatch macro for: "` — after the migration pair
  ;; had been written, leaving the column added and the schema untouched.
  ;;
  ;; Driven off the CLI's own validation set rather than a list here, so a type
  ;; added there cannot quietly go unexercised.
  (let [cli-types ["string" "text" "integer" "int" "decimal" "boolean"
                   "email" "uuid" "enum" "date" "datetime" "inst" "json"]
        type-mapping {"integer" :int "int" :int "date" :inst
                      "datetime" :inst "text" :text "json" :json}]
    (doseq [t cli-types
            :let [field {:name :probe
                         :type (get type-mapping t (keyword t))
                         :required false}
                  r (gen/add-field-to-schema generated-schema "Widget" field)]]
      (testing (str "--type " t)
        (is (= :updated (:status r)) (str t ": the schema edit must not throw"))
        (is (= 3 (count (:schemas r))))
        (is (str/includes? (:content r) ":probe"))))))

(deftest ^:unit regex-backed-entries-round-trip
  ;; :email is the type that exposed this; the entry has to survive insertion
  ;; and be found again on a rerun.
  (let [email {:name :contact :type :email :required false}
        once  (gen/add-field-to-schema generated-schema "Widget" email)]

    (testing "the regex is written verbatim"
      (is (str/includes? (:content once) "#\"^[a-zA-Z0-9._%+-]+@")))

    (testing "the result is readable Clojure, regex included"
      (let [forms  (read-string (str "[" (:content once) "]"))
            before (read-string (str "[" generated-schema "]"))]
        (is (= (count before) (count forms))
            "insertion adds an entry, never a top-level form")
        (is (some #(instance? java.util.regex.Pattern %)
                  (tree-seq coll? seq forms))
            "a mangled regex would come back as a string or fail to read")))

    (testing "a rerun finds it rather than adding it twice"
      ;; Presence detection reads the entry's first child only, so nothing else
      ;; in it has to be interpretable as a value.
      (let [twice (gen/add-field-to-schema (:content once) "Widget" email)]
        (is (= :already-present (:reason twice)))))

    (testing "a plain field can still be added alongside it"
      (let [after (gen/add-field-to-schema (:content once) "Widget"
                                           {:name :nick :type :string :required false})]
        (is (= :updated (:status after)))))))

;; =============================================================================
;; generate-module-wiring-file (BOU-309)
;; =============================================================================

(deftest ^:unit generate-module-wiring-file-test
  ;; The scaffolder emitted 13 files and not the one its own integrate step
  ;; needs, so `bb scaffold integrate` always landed on "this module has no
  ;; shell/module_wiring.clj yet — add one" and the user hand-wrote the Integrant
  ;; wiring the framework says never to hand-write.
  (let [output (gen/generate-module-wiring-file base-ctx)]

    (testing "it is the namespace integrate looks for"
      (is (str/includes? output "(ns wagoe.product.shell.module-wiring")))

    (testing "it wires the three things the generated module ships"
      (is (str/includes? output "ig/init-key :wagoe/product-repository"))
      (is (str/includes? output "ig/init-key :wagoe/product-service"))
      (is (str/includes? output "ig/init-key :wagoe/product-routes")))

    (testing "and calls the constructors the other generators emit"
      ;; persistence/create-repository and service/create-service are what
      ;; generate-persistence-file and generate-service-file define. A wiring
      ;; that called anything else would compile and fail at boot.
      (is (str/includes? output "persistence/create-repository"))
      (is (str/includes? output "service/create-service")))

    (testing "every init-key has a halt-key!"
      ;; Integrant halts what it started; a missing halt-key! is a resource left
      ;; open on every reset.
      (doseq [k ["product-repository" "product-service" "product-routes"]]
        (is (str/includes? output (str "ig/halt-key! :wagoe/" k))
            (str k " has no halt-key!"))))

    (testing "it exposes the aggregate key module discovery keys on"
      ;; BOU-311 wires a scaffolded module by finding :wagoe/<module>; without
      ;; this key the module is generated, compiled, and never reached.
      (is (str/includes? output "ig/init-key :wagoe/product")))

    (testing "the routes key takes a service, not a repository"
      ;; The `ig/ref` that supplies it lives in the system config, which
      ;; BOU-311 emits; what this file must get right is what it destructures.
      ;; Routes reaching for a repository would skip the service layer.
      (is (str/includes? output "[_ {:keys [service config]}]"))
      (is (not (str/includes? output "[_ {:keys [repository config]}]"))))))

(deftest ^:unit module-wiring-never-confuses-the-module-with-the-entity
  ;; The fixture above uses module "product" with entity "Product", so the two
  ;; render identically and every substring assertion passes whichever is
  ;; wrong. An earlier version passed entity-name into nine module slots and
  ;; emitted "Integrant wiring for the Widget module" for module `inventory`.
  (let [output (gen/generate-module-wiring-file
                {:base-ns "acme" :module-name "inventory"
                 :entities [{:entity-name "Widget" :entity-lower "widget"
                             :entity-kebab "widget" :entity-table "widgets"
                             :fields []}]})]
    (testing "the entity name appears nowhere in the wiring"
      ;; Wiring names components, and a component is the module's, not the
      ;; entity's — a module can grow a second entity without rewiring.
      (is (not (str/includes? output "Widget")) output))

    (testing "and every key is the module's"
      (doseq [k [":wagoe/inventory-repository" ":wagoe/inventory-service"
                 ":wagoe/inventory-routes" ":wagoe/inventory\n"]]
        (is (str/includes? output k) (str "missing " k))))))

(deftest ^:unit module-wiring-calls-the-routes-fn
  ;; :wagoe/http-handler folds the {:api :web :static} contribution. Wiring
  ;; anything else contributes zero routes and says nothing about it — the
  ;; module generates, compiles, boots, and serves no requests.
  (let [output (gen/generate-module-wiring-file base-ctx)]
    (is (str/includes? output "(http/product-routes service"))
    (is (not (str/includes? output "routes-normalized")))))

(deftest ^:unit module-wiring-uses-the-projects-own-base-ns
  ;; A generated project is not called wagoe. Hard-coding the prefix would emit
  ;; a namespace that does not match its own file path.
  (let [output (gen/generate-module-wiring-file (assoc base-ctx :base-ns "acme"))]
    (is (str/includes? output "(ns acme.product.shell.module-wiring"))
    (is (str/includes? output "acme.product.shell.persistence"))
    (is (not (str/includes? output "wagoe.product.shell.persistence")))))

(deftest ^:unit generated-wiring-is-loadable-clojure
  ;; The generators emit strings, so "it compiles" is not something the other
  ;; assertions can tell you — they match substrings, and a wiring file with an
  ;; unbalanced paren matches them all. Reading the whole file back is the
  ;; cheapest way to know it is Clojure at all.
  ;;
  ;; Verified further by hand against a real generated module: all four keys
  ;; init, the routes key returns 3 routes, and :enabled? false yields nil. That
  ;; needs platform on the classpath, so it lives in the first-run smoke
  ;; (BOU-312) rather than here.
  (let [output (gen/generate-module-wiring-file base-ctx)
        forms  (read-string (str "[" output "]"))]
    (is (< 8 (count forms)) "ns form plus four init-keys and four halt-keys")
    (is (= 'ns (ffirst forms)))
    (is (= 'wagoe.product.shell.module-wiring (second (first forms))))))
