(ns wagoe.scaffolder.child-workflow-test
  "An invoice created with line items that have a workflow of their own
   (BOU-578). Their workflows start after the invoice's transaction commits:
   inside it, SQLite made the workflow store wait on the transaction's lock,
   and a rollback left instances for rows that were never written."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.workflow.shell.module-wiring]))

(def ^:private svc (service/create-scaffolder-service))

(defn- module! [base]
  (let [dir (#'multi/temp-dir)
        run #(with-out-str (cli/run-cli! svc (into % ["--base-ns" base "--output-dir" (.getPath dir)])))]
    (run ["generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"])
    (run ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
          "--field" "description:string:required" "--workflow" "state:open>done"])
    (#'multi/load-and-test! dir)
    dir))

(defn- statements [sql]
  (->> (str/split sql #"--;;")
       (map #(str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %))))
       (map str/trim)
       (remove str/blank?)))

(defn- boot [dir base ctx]
  (doseq [[path sql] (#'multi/files-under dir)
          :when (str/ends-with? path ".up.sql")
          s (statements sql)]
    (jdbc/execute! (:datasource ctx) [s]))
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)
        graph (:components (build {:enabled? true} {:config {:active {:wagoe/workflow {}}}}))]
    (ig/init (merge (walk/postwalk-replace {(ig/ref :wagoe/db-context) ctx} graph)
                    {:wagoe/workflow-db-schema {:ctx ctx :profile :test}
                     :wagoe/workflow {:db-ctx ctx :db-schema (ig/ref :wagoe/workflow-db-schema)
                                      :guard-registry {}}}))))

(defn- instances [ctx]
  (jdbc/execute! (:datasource ctx) ["SELECT entity_type, current_state FROM workflow_instances"]))

(deftest ^:integration line-items-with-a-workflow-are-created-with-their-invoice
  (let [base "bou578c"
        dir  (module! base)]
    (doseq [[label ctx] [["sqlite" (db-factory/db-context {:adapter :sqlite
                                                           :database-path (.getPath (java.io.File/createTempFile "childwf" ".db"))
                                                           :pool {:minimum-idle 1 :maximum-pool-size 4}})]
                         ["h2" (db-factory/db-context {:adapter :h2 :database-path (str "mem:childwf" (System/nanoTime))
                                                       :pool {:minimum-idle 1 :maximum-pool-size 4}})]]]
      (testing label
        (let [system (boot dir base ctx)
              call   (#'multi/http-caller (:api (:wagoe/billing-routes system)))
              line   {:description "x"}]
          (try
            (let [resp (call :post "/invoices" {:number "A-1" :invoice-line-items [line line]})]
              (is (= 201 (:status resp)) (pr-str resp))
              (is (= ["open" "open"] (map :state (get-in resp [:body :invoice-line-items])))))
            (is (= 2 (count (instances ctx))) "each line item has its workflow")
            (testing "a line the database refuses leaves no row, and no workflow"
              (jdbc/execute! (:datasource ctx)
                             [(if (= "sqlite" label)
                                (str "CREATE TRIGGER refuse BEFORE INSERT ON invoice_line_items"
                                     " WHEN NEW.description = 'refuse' BEGIN SELECT RAISE(ABORT, 'refused'); END")
                                "ALTER TABLE invoice_line_items ADD CONSTRAINT refuse CHECK (description <> 'refuse')")])
              (let [resp (call :post "/invoices" {:number "A-2"
                                                  :invoice-line-items [line (assoc line :description "refuse")]})]
                (is (<= 400 (:status resp)) (pr-str resp)))
              (is (= 2 (count (instances ctx)))))
            (testing "a workflow that cannot start takes its invoice and line items with it"
              (let [rows #(:n (jdbc/execute-one! (:datasource ctx) [(str "SELECT COUNT(*) AS n FROM " %)]))
                    before [(rows "invoices") (rows "invoice_line_items")]]
                (jdbc/execute! (:datasource ctx) ["ALTER TABLE workflow_instances RENAME TO workflow_instances_away"])
                (is (<= 500 (:status (call :post "/invoices" {:number "A-3" :invoice-line-items [line]}))))
                (is (= before [(rows "invoices") (rows "invoice_line_items")]))))
            (finally
              (ig/halt! system)
              (db-factory/close-db-context! ctx))))))))
