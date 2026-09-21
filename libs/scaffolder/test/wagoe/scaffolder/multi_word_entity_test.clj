(ns wagoe.scaffolder.multi-word-entity-test
  "An entity whose name is more than one word.

   Every derived name came off `(str/lower-case entity-name)`, which destroys
   the word boundaries PascalCase carries: `InvoiceLineItem` became
   `invoicelineitem`, and from there the table was `invoicelineitems`, the core
   file `invoicelineitem.clj`, and the service methods
   `create-invoicelineitem` (BOU-480).

   Single-word entities are unaffected — which is why every existing test
   passed while this was wrong."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]))

(def ^:private ctx
  (template/build-module-context
   {:module-name "invoicing"
    :base-ns     "app"
    :entities    [{:name   "InvoiceLineItem"
                   :fields [{:name :quantity :type :int}]}]}))

(def ^:private entity (first (:entities ctx)))

;; =============================================================================
;; The derived names
;; =============================================================================

(deftest ^:unit pascal-case-word-boundaries-survive
  (testing "the entity's own names"
    (is (= "InvoiceLineItem"    (:entity-name entity)))
    (is (= "invoice-line-item"  (:entity-kebab entity)))
    (is (= "invoice_line_item"  (:entity-snake entity)))
    (is (= "invoice-line-items" (:entity-plural entity))))

  (testing "the table, which is what the ticket was about"
    (is (= "invoice_line_items" (:entity-table entity)))
    (is (= "invoice_line_items" (:entity-plural-snake entity)))))

(deftest ^:unit a-single-word-entity-is-unchanged
  ;; The common case, and every other test in this suite. If this moves, the
  ;; fix has changed what existing users get rather than what was broken.
  (let [e (first (:entities (template/build-module-context
                             {:module-name "shop"
                              :entities    [{:name "Product" :fields []}]})))]
    (is (= "product"  (:entity-kebab e)))
    (is (= "product"  (:entity-snake e)))
    (is (= "products" (:entity-plural e)))
    (is (= "products" (:entity-table e)))))

;; =============================================================================
;; What reaches the generated files
;; =============================================================================

(deftest ^:unit the-migration-creates-the-table-the-persistence-queries
  ;; They were derived separately and disagreed: persistence built its own
  ;; table name from the PascalCase entity, so it queried `Products` while the
  ;; migration created `products`. That survived only because neither H2 nor
  ;; PostgreSQL distinguishes unquoted identifiers by case (BOU-486).
  (let [migration   (gen/generate-migration-file ctx "20260921000000")
        persistence (gen/generate-persistence-file ctx)]
    (is (str/includes? migration "CREATE TABLE IF NOT EXISTS invoice_line_items"))
    (is (str/includes? persistence ":invoice_line_items"))

    (testing "and the down migration drops that same table"
      (is (str/includes? (gen/generate-migration-down-file ctx)
                         "DROP TABLE IF EXISTS invoice_line_items")))

    (testing "no run-together name survives anywhere"
      (doseq [[label source] [["migration"   migration]
                              ["persistence" persistence]
                              ["ports"       (gen/generate-ports-file ctx)]
                              ["service"     (gen/generate-service-file ctx)]
                              ["schema"      (gen/generate-schema-file ctx)]
                              ["core"        (gen/generate-core-file ctx)]
                              ["http"        (gen/generate-http-file ctx)]]]
        (is (not (str/includes? source "invoicelineitem"))
            (str label " still carries the run-together name"))))))

(deftest ^:unit the-generated-clojure-names-read-as-clojure
  (let [ports   (gen/generate-ports-file ctx)
        service (gen/generate-service-file ctx)]
    (testing "protocol methods"
      (is (str/includes? ports "(get-invoice-line-item [this id]"))
      (is (str/includes? ports "(list-invoice-line-items [this options]")))

    (testing "and the service implements those same names"
      (is (str/includes? service "(get-invoice-line-item [_this id]"))
      (is (str/includes? service "(list-invoice-line-items [_this opts]")))))
