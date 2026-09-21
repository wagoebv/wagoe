(ns wagoe.scaffolder.relation-field-test
  "`--field invoice:relation:references=invoice` — a field that points at
   another entity.

   There was no such type, so a scaffolded child entity got no foreign key:
   the column, the REFERENCES clause and its index were written by hand every
   time (BOU-480)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli]
            [malli.core :as m]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.schema :as schema]
            [wagoe.scaffolder.shell.service :as service]))

(defn- parse [spec]
  (cli/parse-field-spec spec))

;; =============================================================================
;; The field spec
;; =============================================================================

(deftest ^:unit a-relation-names-the-entity-it-references
  (testing "references= is parsed"
    (is (= {:name :invoice :type :relation :required false :unique false
            :references "invoice" :on-delete :cascade}
           (parse "invoice:relation:references=invoice"))))

  (testing "required and on-delete ride along"
    (is (= {:name :invoice :type :relation :required true :unique false
            :references "invoice" :on-delete :restrict}
           (parse "invoice:relation:references=invoice:required:on-delete=restrict"))))

  (testing "a relation without references= is refused, like an enum without values"
    (let [result (parse "invoice:relation")]
      (is (:error result))
      (is (str/includes? (:error result) "references="))))

  (testing "references= is only meaningful on a relation"
    (is (:error (parse "name:string:references=invoice"))))

  (testing "an unknown on-delete is refused rather than pasted into the DDL"
    (let [result (parse "invoice:relation:references=invoice:on-delete=explode")]
      (is (:error result))
      (is (str/includes? (:error result) "explode"))))

  (testing "required with on-delete=set-null is refused — the two contradict"
    ;; `invoice_id UUID NOT NULL ... ON DELETE SET NULL` is accepted by the
    ;; database and then fails on the first parent delete: the FK action sets
    ;; a column the table forbids to be null. Refused here rather than
    ;; silently dropping one of the two things the caller asked for.
    (let [result (parse "invoice:relation:references=invoice:required:on-delete=set-null")]
      (is (:error result))
      (is (str/includes? (:error result) "set-null"))))

  (testing "required is fine with the actions that block the delete instead"
    (doseq [action ["cascade" "restrict" "no-action"]]
      (is (nil? (:error (parse (str "invoice:relation:references=invoice:required:on-delete=" action))))
          action))))

(deftest ^:unit a-relation-target-has-to-be-an-identifier
  ;; `references` was checked for blankness and then interpolated straight into
  ;; DDL. `invoice);drop/**/table/**/users;--` survives pascal->kebab intact,
  ;; closes the CREATE TABLE at `invoice)` and leaves a DROP behind it — in a
  ;; file someone then runs with `bb migrate up`. Reachable from
  ;; `bb scaffold ai`, where the spec comes from a model, and from the MCP
  ;; scaffold-module tool, where it comes from an agent (BOU-480 review).
  (testing "an entity name is letters, digits and single hyphens"
    (doseq [ok ["invoice" "Invoice" "InvoiceLineItem" "invoice-line-item"]]
      (is (nil? (:error (parse (str "x:relation:references=" ok)))) ok)))

  (testing "anything else is refused before it reaches the DDL"
    (doseq [bad ["invoice);drop/**/table/**/users;--"
                 "invoice users"
                 "invoice;"
                 "invoice(id)"
                 "-invoice"
                 "1invoice"]]
      (let [result (parse (str "x:relation:references=" bad))]
        (is (:error result) (str "accepted " (pr-str bad)))))))

(deftest ^:unit a-target-whose-table-is-not-the-default-plural
  ;; `references=` names an entity, and the table is derived by pluralising it.
  ;; An entity that declared `:plural "people"` has table `people`, so a
  ;; relation to it generated a foreign key to `persons`, which nothing
  ;; creates (BOU-480 review).
  (testing "the derivation is the default, and it is wrong here"
    (is (= "persons" (template/relation-table "person"))))

  (testing "references-table= says what the table actually is"
    (is (= {:name :owner :type :relation :required false :unique false
            :references "person" :references-table "people" :on-delete :cascade}
           (parse "owner:relation:references=person:references-table=people"))))

  (testing "and that is what reaches the DDL"
    (let [ctx (template/build-module-context
               {:module-name "hr"
                :entities [{:name "Badge"
                            :fields [{:name :owner :type :relation
                                      :references "person" :references-table "people"}]}]})]
      (is (str/includes? (gen/generate-migration-file ctx "1")
                         "REFERENCES people(id)"))))

  (testing "an override that is not a table identifier is refused"
    (doseq [bad ["people;drop table x" "People" "people(id)" ""]]
      (is (:error (parse (str "owner:relation:references=person:references-table=" bad)))
          (str "accepted " (pr-str bad)))))

  (testing "references-table= is only meaningful on a relation"
    (is (:error (parse "name:string:references-table=people")))))

;; =============================================================================
;; Adding a relation to an entity that already exists
;; =============================================================================
;;
;; `bb scaffold field` is the other half of the contract: a field type that
;; only works while a module is being created is a field type you cannot reach
;; once you have a module (BOU-480 review).

(deftest ^:unit the-field-command-accepts-a-relation
  (let [parse-field (fn [args] (clojure.tools.cli/parse-opts args cli/field-options))
        run (fn [args] (let [{:keys [options errors]} (parse-field args)]
                         (if errors
                           {:errors errors}
                           (let [[ok? errs] (cli/validate-field-options options)]
                             (if ok? {:ok options} {:errors errs})))))]

    (testing "--type relation is a type the command knows"
      (is (:ok (run ["--module-name" "inv" "--entity" "Line" "--name" "invoice"
                     "--type" "relation" "--references" "invoice"]))))

    (testing "and it needs its target, like --type enum needs its values"
      (let [{:keys [errors]} (run ["--module-name" "inv" "--entity" "Line"
                                   "--name" "invoice" "--type" "relation"])]
        (is (seq errors))
        (is (some #(str/includes? % "--references") errors) (pr-str errors))))

    (testing "--references on a field that is not a relation is refused"
      (let [{:keys [errors]} (run ["--module-name" "inv" "--entity" "Line" "--name" "title"
                                   "--type" "string" "--references" "invoice"])]
        (is (some #(str/includes? % "--references") errors) (pr-str errors))))

    (testing "--required with --on-delete set-null is refused here too"
      (let [{:keys [errors]} (run ["--module-name" "inv" "--entity" "Line" "--name" "invoice"
                                   "--type" "relation" "--references" "invoice"
                                   "--required" "--on-delete" "set-null"])]
        (is (some #(str/includes? % "set-null") errors) (pr-str errors))))

    (testing "and a target that is not an identifier never reaches the DDL"
      (let [{:keys [errors]} (run ["--module-name" "inv" "--entity" "Line" "--name" "invoice"
                                   "--type" "relation"
                                   "--references" "invoice);drop/**/table/**/users;--"])]
        (is (seq errors))))))

(deftest ^:unit adding-a-relation-writes-its-foreign-key
  ;; The migration emitted `ADD COLUMN invoice_id UUID` and stopped: no
  ;; REFERENCES, no ON DELETE, no index. A relation added to an existing
  ;; entity had none of the referential integrity the same field gets when the
  ;; module is generated.
  (let [sql (gen/generate-add-field-migration
             "invoicing" "InvoiceLineItem"
             {:name :invoice :type :relation :references "invoice"
              :on-delete :cascade :required true}
             "20260921000000")]

    (testing "the column carries its constraint"
      (is (str/includes? sql "ALTER TABLE invoice_line_items ADD COLUMN invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE;")
          sql))

    (testing "and the foreign key gets the same index module generation gives it"
      (is (str/includes? sql "CREATE INDEX IF NOT EXISTS idx_invoice_line_items_invoice_id ON invoice_line_items(invoice_id);")
          sql)))

  (testing "a non-relation field is unchanged — no constraint, no index"
    (let [sql (gen/generate-add-field-migration
               "invoicing" "InvoiceLineItem"
               {:name :discount :type :decimal} "1")]
      ;; NOT NULL because build-field-context defaults :required to true.
      (is (str/includes? sql "ADD COLUMN discount DECIMAL(19,4) NOT NULL;"))
      (is (not (str/includes? sql "REFERENCES")))
      (is (not (str/includes? sql "CREATE INDEX")))))

  (testing "the down migration drops the column the up added"
    ;; The up adds `invoice_id`; the down was built from the raw field name and
    ;; dropped `invoice`, so the rollback failed on a column that never
    ;; existed — the same up/down split as the table name (BOU-480).
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "wagoe-rel-field" (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (ports/generate-module (service/create-scaffolder-service)
                               {:module-name "invoicing" :base-ns "app"
                                :entities [{:name "InvoiceLineItem"
                                            :fields [{:name :quantity :type :int}]}]
                                :output-dir (.getPath dir)})
        (let [result (ports/add-field (service/create-scaffolder-service)
                                      {:module-name "invoicing" :base-ns "app"
                                       :entity "InvoiceLineItem"
                                       :field {:name :invoice :type :relation
                                               :references "invoice" :required true}
                                       :output-dir (.getPath dir)})
              down   (->> (:files result)
                          (filter #(str/ends-with? (:path %) ".down.sql"))
                          first)]
          (is (true? (:success result)) (pr-str (:errors result)))
          (is (str/includes? (or (:content down) (slurp (:path down)))
                             "DROP COLUMN invoice_id;")
              (or (:content down) (slurp (:path down)))))
        (finally (doseq [f (reverse (file-seq dir))] (.delete f))))))

  (testing "references-table= is honoured here too"
    (is (str/includes? (gen/generate-add-field-migration
                        "hr" "Badge"
                        {:name :owner :type :relation :references "person"
                         :references-table "people"}
                        "1")
                       "REFERENCES people(id)"))))

(deftest ^:unit add-field-validates-the-field-it-is-given
  ;; `generate-module` checks every field against the request schema; add-field
  ;; checked nothing, so the CLI and the MCP tool were covered when creating a
  ;; module and neither was when adding to one. Validated in the service rather
  ;; than in each caller: it is the one place every path goes through
  ;; (BOU-480 review).
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "wagoe-add-field-validation"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        svc (service/create-scaffolder-service)
        add (fn [field]
              (ports/add-field svc {:module-name "invoicing" :base-ns "app"
                                    :entity "InvoiceLineItem" :field field
                                    :output-dir (.getPath dir)}))]
    (try
      (ports/generate-module svc {:module-name "invoicing" :base-ns "app"
                                  :entities [{:name "InvoiceLineItem"
                                              :fields [{:name :quantity :type :int}]}]
                                  :output-dir (.getPath dir)})

      (testing "a relation naming no target — it wrote a bare UUID column"
        (is (false? (:success (add {:name :invoice :type :relation})))))

      (testing "an unknown on-delete — it silently became CASCADE"
        (is (false? (:success (add {:name :invoice :type :relation
                                    :references "invoice" :on-delete :explode})))))

      (testing "required with set-null — the migration failed on parent delete"
        (is (false? (:success (add {:name :invoice :type :relation
                                    :references "invoice" :on-delete :set-null
                                    :required true})))))

      (testing "a target that is not an identifier"
        (is (false? (:success (add {:name :invoice :type :relation
                                    :references "invoice);drop table users;--"})))))

      (testing "an enum with no values, which was equally unchecked here"
        (is (false? (:success (add {:name :status :type :enum})))))

      (testing "and a field that is fine still goes through"
        (is (true? (:success (add {:name :invoice :type :relation
                                   :references "invoice" :on-delete :restrict
                                   :required true})))))
      (finally (doseq [f (reverse (file-seq dir))] (.delete f))))))

(deftest ^:unit the-request-schema-refuses-the-contradiction-too
  ;; The CLI is not the only way in — the MCP tool and any direct
  ;; `generate-module` caller are validated by the schema alone, and would
  ;; otherwise still write DDL that cannot survive a parent delete.
  (let [valid? (fn [field] (m/validate schema/FieldDefinition field))]
    (is (not (valid? {:name :invoice :type :relation :references "invoice"
                      :on-delete :set-null :required true})))

    (testing "and the target identifier, which the CLI is not the only way to set"
      (is (not (valid? {:name :invoice :type :relation
                        :references "invoice);drop/**/table/**/users;--"})))
      (is (not (valid? {:name :invoice :type :relation :references "invoice"
                        :references-table "people;drop table x"})))
      (is (valid? {:name :invoice :type :relation :references "invoice"
                   :references-table "people"})))
    (is (valid? {:name :invoice :type :relation :references "invoice"
                 :on-delete :set-null :required false}))
    (is (valid? {:name :invoice :type :relation :references "invoice"
                 :on-delete :cascade :required true}))))

(deftest ^:unit the-cli-accepts-a-relation-field
  (let [opts (:options (clojure.tools.cli/parse-opts
                        ["--field" "invoice:relation:references=invoice"]
                        cli/generate-options))]
    (is (= ["invoice:relation:references=invoice"] (:field opts)))))

;; =============================================================================
;; What it generates
;; =============================================================================

(def ^:private ctx
  (template/build-module-context
   {:module-name "invoicing"
    :base-ns     "app"
    :entities    [{:name   "InvoiceLineItem"
                   :fields [{:name :invoice :type :relation :references "invoice"
                             :on-delete :cascade :required true}
                            {:name :quantity :type :int :required true}]}]}))

(deftest ^:unit the-migration-carries-the-foreign-key
  (let [migration (gen/generate-migration-file ctx "20260921000000")]

    (testing "the column is the field name with _id, and it references the target"
      (is (str/includes? migration "invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE")
          migration))

    (testing "and a foreign key gets an index — it is what every join and cascade reads"
      (is (str/includes? migration
                         "CREATE INDEX IF NOT EXISTS idx_invoice_line_items_invoice_id ON invoice_line_items(invoice_id);")
          migration))))

(deftest ^:unit on-delete-is-honoured
  (let [ctx-for (fn [on-delete]
                  (template/build-module-context
                   {:module-name "invoicing"
                    :entities    [{:name   "Line"
                                   :fields [{:name :invoice :type :relation
                                             :references "invoice" :on-delete on-delete}]}]}))]
    (is (str/includes? (gen/generate-migration-file (ctx-for :restrict) "1") "ON DELETE RESTRICT"))
    (is (str/includes? (gen/generate-migration-file (ctx-for :set-null) "1") "ON DELETE SET NULL"))))

(deftest ^:unit the-schema-types-the-relation-as-a-uuid
  (let [schema (gen/generate-schema-file ctx)]
    (is (str/includes? schema "[:invoice-id :uuid]")
        (str "the relation is not a uuid key in the entity schema:\n" schema))))
