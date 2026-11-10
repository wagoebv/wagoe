(ns wagoe.workflow.shell.tables-test
  "A tenant's transaction stays its own with another database's nested inside
   it (BOU-972)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.workflow.shell.tables :as tables])
  (:import (java.util UUID)))

(defn- h2-ctx []
  (factory/db-context (factory/h2-config (str "mem:wf-tables-" (UUID/randomUUID) ";DB_CLOSE_DELAY=-1"))))

(defn- refused? [connectable]
  (try (tables/refuse-shared-tables! connectable) false
       (catch clojure.lang.ExceptionInfo e
         (= :configuration-error (:type (ex-data e))))))

(deftest ^:integration a-tenant-transaction-with-another-nested-is-not-refused
  (let [tenant-db (h2-ctx)
        other     (h2-ctx)]
    (try
      ;; H2 has no to_regclass; the tables are there
      (with-redefs-fn {#'tables/tables-in? (constantly true)}
        #(db/with-transaction [tx tenant-db]
           (binding [db/*tenant-schema* "tenant_a"]
             (db/with-transaction [_ other]
               (testing "the tenant's transaction, though not the innermost"
                 (is (not (refused? tx))))
               (testing "a caller's own connection is still refused"
                 (with-open [^java.sql.Connection own (.getConnection ^javax.sql.DataSource (:datasource tenant-db))]
                   (.setAutoCommit own false)
                   (is (refused? own))))))))
      (finally
        (run! factory/close-db-context! [tenant-db other])))))
