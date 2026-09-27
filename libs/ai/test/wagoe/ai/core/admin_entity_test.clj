(ns wagoe.ai.core.admin-entity-test
  "BOU-567: the admin-entity generator got types wrong, dropped the relations
   it was asked for, and wrote configs nothing had checked."
  (:require [wagoe.ai.core.admin-entity :as sut]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private invoices-sql
  "-- Migration 1: Create invoices table

CREATE TABLE IF NOT EXISTS invoices (
  id UUID PRIMARY KEY,
  number VARCHAR(255) NOT NULL UNIQUE,
  issue_date DATE NOT NULL,
  total_in_cents INTEGER NOT NULL,
  amount DECIMAL(10, 2),
  status VARCHAR(50) DEFAULT 'entered' NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk CHECK (total_in_cents >= 0)
);

CREATE INDEX IF NOT EXISTS idx_invoices_created_at ON invoices(created_at);")

(deftest ^:unit migration-columns-reads-create-and-alter
  (let [tables (sut/migration-columns
                [invoices-sql
                 "ALTER TABLE invoices ADD COLUMN paid_on DATE;
                  ALTER TABLE invoices DROP COLUMN amount;"])]
    (testing "column types come from CREATE TABLE, constraints skipped"
      (is (= {:id :uuid :number :string :issue-date :date :total-in-cents :int
              :status :string :created-at :instant :paid-on :date}
             (sut/column-types (get tables "invoices"))))))

  (testing "a later DROP TABLE forgets the table"
    (is (nil? (get (sut/migration-columns [invoices-sql "DROP TABLE invoices;"])
                   "invoices")))))

(deftest ^:unit sql-types-map-to-admin-types
  (is (= :date    (sut/sql-type->field-type "DATE")))
  (is (= :instant (sut/sql-type->field-type "timestamp with time zone")))
  (is (= :instant (sut/sql-type->field-type "DATETIME")))
  (is (= :int     (sut/sql-type->field-type "BIGINT")))
  (is (= :decimal (sut/sql-type->field-type "NUMERIC(10,2)")))
  (is (= :boolean (sut/sql-type->field-type "BOOLEAN")))
  (is (= :text    (sut/sql-type->field-type "TEXT")))
  (is (= :json    (sut/sql-type->field-type "JSONB")))
  (is (nil?       (sut/sql-type->field-type "GEOMETRY"))))

(def ^:private generated
  {:invoices {:label      "Invoices"
              :table-name :invoices
              :fields     {:issue-date     {:type :instant :label "Issue Date"}
                           :total-in-cents {:type :decimal :label "Total"}
                           :status         {:type :enum :label "Status"
                                            :options [[:entered "Entered"]]}
                           :number         {:label "Number"}}}})

(deftest ^:unit table-types-override-the-model
  (let [cols {:issue-date :date :total-in-cents :int :status :string :number :string}
        {:keys [config corrections]} (sut/apply-column-types (:invoices generated) cols)]
    (testing "DATE and INTEGER columns win over what the model said"
      (is (= :date (get-in config [:fields :issue-date :type])))
      (is (= :int (get-in config [:fields :total-in-cents :type]))))
    (testing "an enum over a string column is a choice, not a mistake"
      (is (= :enum (get-in config [:fields :status :type]))))
    (testing "a field without :type is left to introspection"
      (is (nil? (get-in config [:fields :number :type]))))
    (is (= #{{:field :issue-date :from :instant :to :date}
             {:field :total-in-cents :from :decimal :to :int}}
           (set corrections)))))

(deftest ^:unit without-a-table-names-decide
  (let [{:keys [config corrections]}
        (sut/infer-types {:fields {:due-date   {:type :instant}
                                   :created-at {:type :instant}
                                   :quantity   {:type :decimal}
                                   :unit-price-in-cents {:type :decimal}
                                   :price      {:type :decimal}}})]
    (is (= :date (get-in config [:fields :due-date :type])))
    (is (= :instant (get-in config [:fields :created-at :type])) "a timestamp stays one")
    (is (= :int (get-in config [:fields :quantity :type])))
    (is (= :int (get-in config [:fields :unit-price-in-cents :type])))
    (is (= :decimal (get-in config [:fields :price :type])) "a money amount stays decimal")
    (is (= 3 (count corrections)))))

(deftest ^:unit requested-relations-are-recognised
  (is (= #{:has-many :sidebar-hidden :parent-context}
         (sut/requested-keys
          "invoices; an invoice has many line items. Line items are sidebar-hidden and show a parent-context")))
  (is (= #{:has-many} (sut/requested-keys "orders with :has-many order-items")))
  (is (= #{:sidebar-hidden} (sut/requested-keys "tags, hidden from the sidebar")))
  (is (empty? (sut/requested-keys "products with name, price, status"))))

(deftest ^:unit dropped-relations-are-named
  (let [entities {:invoices {:label "I" :table-name :invoices :has-many []}
                  :lines    {:label "L" :table-name :lines :sidebar-hidden true}}]
    (is (= #{:parent-context}
           (sut/missing-keys entities #{:has-many :sidebar-hidden :parent-context})))
    (is (empty? (sut/missing-keys entities #{:has-many})))))

(deftest ^:unit the-schema-checks-what-the-admin-reads
  (testing "a config with relations passes"
    (is (nil? (sut/validation-errors
               {:invoices {:label "Invoices" :table-name :invoices
                           :fields {:issue-date {:type :date :label "Issue"}}
                           :has-many [{:entity :invoice-line-items :table :invoice_line_items
                                       :foreign-key :invoice-id :label "Lines"
                                       :fields [:description] :editable true}]}
                :invoice-line-items {:label "Lines" :table-name :invoice-line-items
                                     :sidebar-hidden true
                                     :parent-context {:label "Invoice" :fields [:number]}}}))))

  (testing "a type the admin does not know is rejected"
    (is (some? (sut/validation-errors
                {:x {:label "X" :table-name :x :fields {:mail {:type :email}}}}))))

  (testing "a has-many without its foreign key is rejected"
    (is (some? (sut/validation-errors
                {:x {:label "X" :table-name :x :has-many [{:entity :y :table :y}]}}))))

  (testing "a parent-context that is not a map is rejected"
    (is (some? (sut/validation-errors
                {:x {:label "X" :table-name :x :parent-context true}})))))

(deftest ^:unit rendering-round-trips
  (let [entity (:invoices generated)
        text   (sut/render {:invoices entity})]
    (is (= {:invoices entity} (edn/read-string text)))
    (is (str/starts-with? text "{:invoices\n {:label"))
    (testing "keys keep a readable order"
      (is (< (str/index-of text ":label") (str/index-of text ":table-name")
             (str/index-of text ":fields"))))))
