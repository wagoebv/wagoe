(ns wagoe.scaffolder.field-default-test
  "`--field status:enum:values=entered,paid:required:default=entered` — a
   column default.

   A required column the admin marks `:readonly-fields` is left out of the
   create form, so the insert omits it and NOT NULL refuses the row. The
   database default is what keeps it creatable (BOU-494)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.schema :as schema]))

(defn- migration-for [& fields]
  (gen/generate-migration-file
   (template/build-module-context
    {:module-name "orders" :base-ns "app"
     :entities    [{:name "Order" :fields (vec fields)}]})
   "20260926000000"))

;; =============================================================================
;; The field spec
;; =============================================================================

(deftest ^:unit default-is-a-field-spec-modifier
  (testing "default= is parsed next to the other modifiers"
    (is (= {:name :status :type :enum :required true :unique false
            :enum-values [:entered :paid] :default "entered"}
           (cli/parse-field-spec "status:enum:values=entered,paid:required:default=entered"))))

  (testing "a value that does not suit the type is refused, not pasted into DDL"
    (doseq [spec ["qty:int:default=many"
                  "price:decimal:default=cheap"
                  "active:boolean:default=yes"
                  "status:enum:values=entered,paid:default=lost"
                  "invoice:relation:references=invoice:default=x"]]
      (is (:error (cli/parse-field-spec spec)) spec))))

(deftest ^:unit the-field-command-takes-a-default
  (let [{:keys [options errors]} (clojure.tools.cli/parse-opts
                                  ["--module-name" "orders" "--entity" "Order"
                                   "--name" "qty" "--type" "int" "--default" "many"]
                                  cli/field-options)]
    (is (nil? errors))
    (let [[ok? errs] (cli/validate-field-options options)]
      (is (false? ok?))
      (is (some #(str/includes? % "--default") errs) (pr-str errs)))))

(deftest ^:unit the-schema-refuses-a-default-that-does-not-suit-the-type
  ;; The MCP tool and direct callers skip the CLI parser; generate-module
  ;; validates against this schema.
  (is (m/validate schema/FieldDefinition {:name :qty :type :int :default 3}))
  (is (m/validate schema/FieldDefinition {:name :qty :type :int :default "3"}))
  (is (not (m/validate schema/FieldDefinition {:name :qty :type :int :default "many"})))
  (is (not (m/validate schema/FieldDefinition {:name :s :type :enum :enum-values [:a]
                                               :default "b"}))))

;; =============================================================================
;; The migration
;; =============================================================================

(deftest ^:unit the-migration-quotes-the-default-by-type
  (testing "strings and enums are quoted"
    (is (str/includes? (migration-for {:name :note :type :string :required false :default "n/a"})
                       "note VARCHAR(255) DEFAULT 'n/a'"))
    (is (str/includes? (migration-for {:name :status :type :enum :enum-values [:entered :paid]
                                       :required true :default "paid"})
                       "status VARCHAR(50) DEFAULT 'paid' NOT NULL")))

  (testing "a quote in the value is escaped"
    (is (str/includes? (migration-for {:name :note :type :string :required false :default "it's"})
                       "DEFAULT 'it''s'")))

  (testing "numbers and booleans are not"
    (is (str/includes? (migration-for {:name :qty :type :int :required true :default "3"})
                       "qty INTEGER DEFAULT 3 NOT NULL"))
    (is (str/includes? (migration-for {:name :price :type :decimal :required true :default "9.95"})
                       "DEFAULT 9.95 NOT NULL"))
    (is (str/includes? (migration-for {:name :active :type :boolean :required true :default "false"})
                       "active BOOLEAN DEFAULT false NOT NULL")))

  (testing "no default, no DEFAULT"
    (is (not (str/includes? (migration-for {:name :note :type :string :required true})
                            "DEFAULT")))))

(deftest ^:unit a-required-enum-defaults-to-its-first-value
  (is (str/includes? (migration-for {:name :status :type :enum
                                     :enum-values [:entered :paid] :required true})
                     "status VARCHAR(50) DEFAULT 'entered' NOT NULL"))
  (testing "but not when it is optional — null is its default"
    (is (not (str/includes? (migration-for {:name :status :type :enum
                                            :enum-values [:entered :paid] :required false})
                            "DEFAULT")))))

(deftest ^:unit adding-a-field-carries-its-default
  (is (str/includes? (gen/generate-add-field-migration
                      "orders" "Order"
                      {:name :status :type :enum :enum-values [:entered :paid]
                       :required true :default "paid"}
                      "1")
                     "ADD COLUMN status VARCHAR(50) DEFAULT 'paid' NOT NULL;")))

;; =============================================================================
;; The promise
;; =============================================================================

(deftest ^:integration an-insert-that-omits-a-required-defaulted-column-succeeds
  ;; What the admin's create form does with a :readonly-fields column.
  (let [ds  (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:dflt" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})
        sql (migration-for {:name :reference :type :string :required true}
                           {:name :status :type :enum :enum-values [:entered :paid] :required true}
                           {:name :qty :type :int :required true :default 1})]
    (jdbc/execute! ds [(first (str/split sql #";"))])
    (jdbc/execute! ds ["INSERT INTO orders (id, reference, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                       (random-uuid) "R-1"])
    (is (= {:status "entered" :qty 1}
           (jdbc/execute-one! ds ["SELECT status, qty FROM orders"]
                              {:builder-fn rs/as-unqualified-lower-maps})))))
