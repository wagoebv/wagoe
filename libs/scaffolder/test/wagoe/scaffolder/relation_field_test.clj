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
            [wagoe.scaffolder.schema :as schema]))

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
