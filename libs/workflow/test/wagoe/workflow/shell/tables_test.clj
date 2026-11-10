(ns wagoe.workflow.shell.tables-test
  "Tenant work runs on the tenant's transaction, whichever other database's is
   open around or inside it (BOU-972)."
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

(defn- in-tenant
  "Call `(f tx)` on a transaction on `ctx` bound as tenant's `call-in-schema`
   binds it; H2 has no search_path to set."
  [ctx f]
  (db/with-transaction [tx ctx]
    (binding [db/*tenant-schema*      "tenant_a"
              db/*tenant-transaction* (db/tx-connection tx)]
      (f tx))))

(deftest ^:integration tenant-work-takes-the-tenants-transaction-only
  (let [tenant-db (h2-ctx)
        other     (h2-ctx)]
    (try
      ;; H2 has no to_regclass; the tables are there
      (with-redefs-fn {#'tables/tables-in? (constantly true)}
        (fn []
          (testing "another database's nested inside it"
            (in-tenant tenant-db
                       (fn [tx]
                         (db/with-transaction [inner other]
                           (is (not (refused? tx)) "the tenant's, though not the innermost")
                           (is (refused? inner) "the nested one's")))))
          (testing "another database's open around it"
            (db/with-transaction [outer other]
              (in-tenant tenant-db
                         (fn [tx]
                           (is (not (refused? tx)))
                           (is (refused? outer) "the outer one's")))))
          (testing "a caller's own connection"
            (in-tenant tenant-db
                       (fn [_]
                         (with-open [^java.sql.Connection own (.getConnection ^javax.sql.DataSource (:datasource tenant-db))]
                           (.setAutoCommit own false)
                           (is (refused? own))))))))
      (finally
        (run! factory/close-db-context! [tenant-db other])))))
