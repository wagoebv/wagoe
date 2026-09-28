(ns wagoe.scaffolder.min-children-test
  "An invoice has at least one line item (BOU-578). `bb scaffold entity
   --belongs-to invoice --min 1` makes the invoice's create take its line items
   in the same request and the same transaction, refuses one with fewer, and
   refuses the delete that would leave fewer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]))

(def ^:private svc (service/create-scaffolder-service))
(def ^:private temp-dir #'multi/temp-dir)
(def ^:private files-under #'multi/files-under)
(def ^:private load-and-test! #'multi/load-and-test!)
(def ^:private http-caller #'multi/http-caller)
(def ^:private h2-migrated #'multi/h2-migrated)

(defn- billing!
  "Module billing under `base`: Invoice, its line items with `line-opts`
   appended to the entity command, and payments with no minimum."
  [base line-opts]
  (let [dir (temp-dir)
        run #(with-out-str (cli/run-cli! svc (into % ["--base-ns" base "--output-dir" (.getPath dir)])))]
    (run ["generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"])
    (run (into ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
                "--field" "description:string:required" "--field" "quantity:int:required"]
               line-opts))
    (run ["entity" "--module-name" "billing" "--entity" "Payment" "--belongs-to" "invoice"
          "--field" "amount:int:required"])
    dir))

(defn- boot
  "The module's Integrant graph, as platform discovery builds it, on `db`."
  [base db]
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)]
    (ig/init (walk/postwalk-replace {(ig/ref :wagoe/db-context) db}
                                    (:components (build {:enabled? true} {}))))))

(defn- count-rows [db table]
  (:n (jdbc/execute-one! (:datasource db) [(str "SELECT COUNT(*) AS n FROM " table)])))

(deftest ^:integration an-invoice-is-created-with-its-line-items-and-never-without
  (let [dir (billing! "bou578m" ["--min" "1"])]
    (testing "the generated tests pass"
      (let [{:keys [fail error]} (load-and-test! dir)]
        (is (= 0 fail))
        (is (= 0 error))))
    (let [db     (h2-migrated dir "bou578m")
          system (boot "bou578m" db)
          call   (http-caller (:api (:wagoe/billing-routes system)))
          line   {:description "Consulting" :quantity 2}]
      (try
        (testing "without line items the create is a 400 that names them, and writes nothing"
          (let [resp (call :post "/invoices" {:number "A-1"})]
            (is (= 400 (:status resp)) (pr-str resp))
            (is (contains? (get-in resp [:body :error :details :errors]) :invoice-line-items) (pr-str resp)))
          (is (= 400 (:status (call :post "/invoices" {:number "A-1" :invoice-line-items []}))))
          (is (= 0 (count-rows db "invoices"))))

        (testing "an invalid line item is a 400 too"
          (is (= 400 (:status (call :post "/invoices" {:number "A-1" :invoice-line-items [{:quantity 2}]})))))

        (testing "with them, the invoice and its line items are created, and come back"
          (let [resp  (call :post "/invoices" {:number "A-1" :invoice-line-items [line (assoc line :quantity 3)]})
                id    (get-in resp [:body :id])
                lines (get-in resp [:body :invoice-line-items])]
            (is (= 201 (:status resp)) (pr-str resp))
            (is (= 2 (count lines)))
            (is (every? #(= id (str (:invoice-id %))) lines))
            (is (= 2 (count-rows db "invoice_line_items")))))

        (testing "more than the admin takes is a 400 too, and writes nothing"
          (let [before (count-rows db "invoices")]
            (is (= 400 (:status (call :post "/invoices" {:number "A-9" :invoice-line-items (repeat 501 line)}))))
            (is (= 400 (:status (call :post "/invoices" {:number "A-9" :invoice-line-items [line]
                                                         :payments (repeat 501 {:amount 1})}))))
            (is (= before (count-rows db "invoices")))))

        (testing "a child with no minimum may come along, or not"
          (is (= 201 (:status (call :post "/invoices" {:number "A-2" :invoice-line-items [line]
                                                       :payments [{:amount 5}]}))))
          (is (= 1 (count-rows db "payments"))))

        (testing "one transaction: a line item the database refuses takes the invoice with it"
          (let [before (count-rows db "invoices")
                resp   (call :post "/invoices" {:number "A-3"
                                                :invoice-line-items [line (assoc line :description (apply str (repeat 300 "x")))]})]
            (is (<= 400 (:status resp)) (pr-str resp))
            (is (= before (count-rows db "invoices")))))

        (testing "the last line item cannot be deleted, or moved away"
          (let [inv   (get-in (call :post "/invoices" {:number "A-4" :invoice-line-items [line line]}) [:body])
                other (get-in (call :post "/invoices" {:number "A-5" :invoice-line-items [line]}) [:body])
                [a b] (map :id (:invoice-line-items inv))]
            (is (= 204 (:status (call :delete (str "/invoice-line-items/" a) nil))))
            (let [resp (call :delete (str "/invoice-line-items/" b) nil)]
              (is (= 400 (:status resp)) (pr-str resp))
              (is (str/includes? (pr-str (:body resp)) "invoice-line-items")))
            (is (= 400 (:status (call :put (str "/invoice-line-items/" b) {:invoice-id (:id other)}))))
            (is (= 200 (:status (call :get (str "/invoice-line-items/" b) nil))) "it is still there")))
        (finally
          (ig/halt! system)
          (db-factory/close-db-context! db))))))

(deftest ^:integration without-a-minimum-the-children-are-optional
  (let [dir (billing! "bou578n" [])]
    (load-and-test! dir)
    (let [db     (h2-migrated dir "bou578n")
          system (boot "bou578n" db)
          call   (http-caller (:api (:wagoe/billing-routes system)))]
      (try
        (is (= 201 (:status (call :post "/invoices" {:number "A-1"}))))
        (let [resp (call :post "/invoices" {:number "A-2" :invoice-line-items [{:description "x" :quantity 1}]})
              line (first (get-in resp [:body :invoice-line-items]))]
          (is (= 201 (:status resp)))
          (is (= 204 (:status (call :delete (str "/invoice-line-items/" (:id line)) nil)))
              "the last one may go"))
        (finally
          (ig/halt! system)
          (db-factory/close-db-context! db))))))

(deftest ^:unit min-is-a-number-of-children-of-the-first-entity
  (let [dir (temp-dir)
        run (fn [& args]
              (let [status (atom nil)
                    err    (with-out-str
                             (binding [*err* *out*]
                               (reset! status (cli/run-cli! svc (into (vec args) ["--base-ns" "bou578u"
                                                                                  "--output-dir" (.getPath dir)])))))]
                {:status @status :err err}))]
    (run "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string")
    (testing "--min without --belongs-to"
      (let [{:keys [status err]} (run "entity" "--module-name" "billing" "--entity" "Note"
                                      "--field" "text:string" "--min" "1")]
        (is (= 1 status))
        (is (str/includes? err "--belongs-to") err)))
    (testing "--min below 1"
      (is (= 1 (:status (run "entity" "--module-name" "billing" "--entity" "Line" "--belongs-to" "invoice"
                             "--field" "text:string" "--min" "0")))))
    (testing "a parent that is not the module's first entity"
      (is (= 0 (:status (run "entity" "--module-name" "billing" "--entity" "Line" "--belongs-to" "invoice"
                             "--field" "text:string"))))
      (let [before (files-under dir)
            {:keys [status err]} (run "entity" "--module-name" "billing" "--entity" "SubLine"
                                      "--belongs-to" "line" "--field" "text:string" "--min" "1")]
        (is (= 1 status))
        (is (str/includes? err "first entity") err)
        (is (= before (files-under dir)) "nothing is written")))))

(deftest ^:integration generate-takes-a-minimum-too
  ;; The multi-entity generate, which the MCP tool calls.
  (let [dir (temp-dir)
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou578g" :output-dir (.getPath dir)
                                        :entities [{:name "Invoice" :fields [{:name :number :type :string}]}
                                                   {:name "InvoiceLineItem" :belongs-to "invoice" :min 1
                                                    :fields [{:name :quantity :type :int}]}]})]
    (is (:success r) (pr-str (:errors r)))
    (load-and-test! dir)
    (let [db     (h2-migrated dir "bou578g")
          system (boot "bou578g" db)
          call   (http-caller (:api (:wagoe/billing-routes system)))]
      (try
        (is (= 400 (:status (call :post "/invoices" {:number "A-1"}))))
        (is (= 201 (:status (call :post "/invoices" {:number "A-1" :invoice-line-items [{:quantity 1}]}))))
        (finally
          (ig/halt! system)
          (db-factory/close-db-context! db))))))

(deftest ^:integration a-field-added-to-a-child-reaches-its-parent-s-create
  ;; `bb scaffold field` on the line items: the invoice's create request has
  ;; to take it too, or its nested line items drop it and fail NOT NULL.
  (let [base "bou578f"
        dir  (billing! base ["--min" "1"])
        out  (with-out-str (cli/run-cli! svc ["field" "--module-name" "billing" "--entity" "InvoiceLineItem"
                                              "--name" "unit-price" "--type" "int" "--required"
                                              "--base-ns" base "--output-dir" (.getPath dir)]))]
    (is (str/includes? (get (files-under dir) "src/bou578f/billing/schema.clj")
                       "[:invoice-line-items [:vector {:min 1 :max 500} [:map [:description :string] [:quantity :int] [:unit-price :int]]]]")
        out)
    (let [{:keys [fail error]} (load-and-test! dir)]
      (is (= 0 fail) "the tests generated before the field still pass (BOU-581)")
      (is (= 0 error) "the tests generated before the field still pass (BOU-581)"))
    (let [db     (h2-migrated dir "bou578f")
          system (boot base db)
          call   (http-caller (:api (:wagoe/billing-routes system)))
          line   {:description "x" :quantity 1}]
      (try
        (let [resp (call :post "/invoices" {:number "A-1" :invoice-line-items [(assoc line :unit-price 250)]})]
          (is (= 201 (:status resp)) (pr-str resp))
          (is (= 250 (get-in resp [:body :invoice-line-items 0 :unit-price]))))
        (let [resp (call :post "/invoices" {:number "A-2" :invoice-line-items [line]})]
          (is (= 400 (:status resp)) (pr-str resp)))
        (finally
          (ig/halt! system)
          (db-factory/close-db-context! db))))))
