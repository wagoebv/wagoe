(ns wagoe.scaffolder.integrity-off-test
  "A generated test that refers to rows it does not create switches H2's
   referential integrity off. Every connection the pool opens switches it back
   on, so a second connection opened after that failed the insert at random
   (BOU-654)."
  (:require [clojure.test :refer [deftest is]]
            [wagoe.platform.database :as db]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.workflow.shell.module-wiring])
  (:import [com.zaxxer.hikari HikariDataSource]))

(def ^:private svc (service/create-scaffolder-service))

(defn- open-every-connection!
  "Make the pool open all the connections it may, as a busy one would."
  [ctx]
  (let [^HikariDataSource ds (:datasource ctx)
        held (doall (repeatedly (.getMaximumPoolSize ds) #(.getConnection ds)))]
    (run! #(.close ^java.sql.Connection %) held)))

(deftest ^:integration integrity-stays-off-whatever-connection-the-insert-gets
  (let [base "bou654"
        dir  (#'multi/temp-dir)
        run  (fn [& args]
               (with-out-str (cli/run-cli! svc (into (vec args) ["--base-ns" base "--output-dir" (.getPath dir)]))))]
    (run "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"
         "--workflow" "status:draft>sent")
    (run "entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
         "--field" "description:string:required" "--workflow" "state:open>done")
    (run "field" "--module-name" "billing" "--entity" "Invoice" "--name" "customer" "--type" "relation"
         "--references" "invoice" "--required")
    (let [execute-ddl! db/execute-ddl!
          {:keys [fail error]} (with-redefs [db/execute-ddl! (fn [ctx sql]
                                                               (let [r (execute-ddl! ctx sql)]
                                                                 (open-every-connection! ctx)
                                                                 r))]
                                 (#'multi/load-and-test! dir))]
      (is (= 0 fail))
      (is (= 0 error)))))
