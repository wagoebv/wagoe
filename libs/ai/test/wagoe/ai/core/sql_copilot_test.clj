(ns wagoe.ai.core.sql-copilot-test
  (:require [wagoe.ai.core.sql-copilot :as sut]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def tables
  {"invoices"           {"id" "UUID PRIMARY KEY" "number" "VARCHAR(50) NOT NULL" "status" "VARCHAR(20)"}
   "invoice_line_items" {"id" "UUID" "invoice_id" "UUID REFERENCES invoices(id)" "line_total" "DECIMAL(10,2) DEFAULT 0"}
   "users"              {"id" "UUID" "email" "TEXT"}})

(deftest ^:unit render-schema-test
  (testing "one line per table, types without constraints, named tables first"
    (is (= ["invoice_line_items: id UUID, invoice_id UUID, line_total DECIMAL(10,2)"
            "invoices: id UUID, number VARCHAR(50), status VARCHAR(20)"
            "users: email TEXT, id UUID"]
           (str/split-lines (sut/render-schema tables "invoices with their line items"))))
    (is (str/starts-with? (sut/render-schema tables "users by email") "users:")))

  (testing "cut off at the budget, saying how much was left out"
    (is (= ["users: email TEXT, id UUID" "(2 more tables not shown)"]
           (str/split-lines (sut/render-schema tables "users" 40)))))

  (testing "nil without tables"
    (is (nil? (sut/render-schema {} "anything")))))

(deftest ^:unit check-answer-accepts-the-real-schema
  (let [ok #(contains? (sut/check-answer % tables) :sql)]
    (testing "qualified columns, kebab-cased names, select aliases"
      (is (ok (pr-str {:select   [:invoices/id [[:count :invoice-line-items/id] :n]
                                  [[:sum :invoice-line-items.line-total] :total]]
                       :from     [:invoices]
                       :left-join [:invoice-line-items [:= :invoices/id :invoice-line-items/invoice-id]]
                       :where    [:= :invoices/status "entered"]
                       :group-by [:invoices/id]
                       :order-by [[:total :desc]]}))))
    (testing "table aliases"
      (is (ok (pr-str {:select [:i/number :li/line-total]
                       :from   [[:invoices :i]]
                       :join   [[:invoice-line-items :li] [:= :i/id :li/invoice-id]]}))))
    (testing "CTEs and subqueries"
      (is (ok (pr-str {:with [[:recent {:select [:id] :from [:invoices]}]]
                       :select [:r/id] :from [[:recent :r]]})))
      (is (ok (pr-str {:select [:*] :from [:users]
                       :where  [:in :id {:select [:invoice-id] :from [:invoice-line-items]}]}))))
    (testing "an INSERT's column list, and a list after a subquery, are columns"
      (is (ok (pr-str {:insert-into :invoices :columns [:id :number :status]
                       :values [["a" "1" "entered"]]})))
      (is (ok (pr-str {:select [[[:coalesce {:select [:number] :from [:invoices]} :email]]]
                       :from   [:users]}))))))

(deftest ^:unit check-answer-refuses-invented-names
  (testing "BOU-513: the answer from the ticket, every name singularised"
    (let [r (sut/check-answer
             (pr-str {:select    [:invoice/id [[:sum :line-item/amount]]]
                      :from      [:invoice]
                      :left-join [:invoice-line-item [:= :invoice/id :line-item/invoice-id]]})
             tables)]
      (is (= ["table invoice does not exist" "table invoice_line_item does not exist"
              "table line_item does not exist"]
             (:errors r)))
      (is (nil? (:sql r)))))

  (testing "a column the table does not have"
    (is (= ["column invoice_line_items.amount does not exist"]
           (:errors (sut/check-answer (pr-str {:select [:invoice-line-items/amount]
                                               :from   [:invoice-line-items]})
                                      tables))))
    (is (= ["column total is in none of invoices"]
           (:errors (sut/check-answer (pr-str {:select [:total] :from [:invoices]}) tables))))
    (is (= ["column invoices.line_total does not exist"]
           (:errors (sut/check-answer (pr-str {:select [:i/line-total] :from [[:invoices :i]]}) tables)))))

  (testing "a column of a real table the query does not select from"
    (is (= ["table users is not in the query's FROM or JOIN"]
           (:errors (sut/check-answer (pr-str {:select [:users/email] :from [:invoices]}) tables)))))

  (testing "a table known by name only: the table is checked, its columns are not"
    (let [t (assoc tables "jobs" nil)]
      (is (contains? (sut/check-answer (pr-str {:select [:jobs/anything :status] :from [:jobs]}) t) :sql))
      (is (= ["table job does not exist"]
             (:errors (sut/check-answer (pr-str {:select [:*] :from [:job]}) t))))
      (is (= "jobs: (columns not listed)" (sut/render-schema {"jobs" nil} "")))))

  (testing "an answer that is not HoneySQL"
    (is (= ["the honeysql value is not a readable EDN map"]
           (:errors (sut/check-answer "SELECT * FROM invoices" tables))))
    (is (= ["the honeysql value is not a readable EDN map"]
           (:errors (sut/check-answer "{:select" tables))))))
