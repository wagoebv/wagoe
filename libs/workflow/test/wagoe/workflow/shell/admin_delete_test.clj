(ns wagoe.workflow.shell.admin-delete-test
  "Deleting an entity in the admin, against the real workflow store (BOU-563):
   a hard delete removes its instances in the admin's own transaction, a soft
   delete keeps them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as admin-service]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.admin-adapter :as admin-adapter]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.workflow.shell.persistence :as persistence])
  (:import [java.time Instant]))

(defn- config [soft?]
  {:entity-discovery {:mode :allowlist :allowlist #{:wd-invoices}}
   :entities         {:wd-invoices {:label "Invoices" :soft-delete soft?
                                    :workflow {:entity-type :invoice}}}})

(def ^:private ^:dynamic *db* nil)

(defn- h2 [db-name]
  (let [ctx (db-factory/db-context {:adapter :h2 :database-path (str "mem:" db-name ";DB_CLOSE_DELAY=-1")})]
    (ig/init-key :wagoe/workflow-db-schema {:ctx ctx})
    ctx))

(use-fixtures :each
  (fn [f]
    (let [ctx (h2 "workflow_admin_delete")]
      (db/execute-update! ctx {:raw "CREATE TABLE wd_invoices (id UUID PRIMARY KEY, number VARCHAR(20),
                                                               deleted_at TIMESTAMP)"})
      (try (binding [*db* ctx] (f))
           (finally
             (db/execute-update! ctx {:raw "DROP ALL OBJECTS"})
             (db-factory/close-db-context! ctx))))))

(defn- invoice-with-workflow!
  "An invoice, and a workflow instance with one audit entry in `store`."
  [store]
  (let [id       (random-uuid)
        instance (random-uuid)
        now      (Instant/now)]
    (db/execute-update! *db* {:raw (str "INSERT INTO wd_invoices (id, number) VALUES ('" id "', 'INV')")})
    (ports/save-instance! store {:id instance :workflow-id :invoice-workflow :entity-type :invoice
                                 :entity-id id :current-state :entered :created-at now :updated-at now})
    (ports/save-audit-entry! store {:id (random-uuid) :instance-id instance :workflow-id :invoice-workflow
                                    :entity-type :invoice :entity-id id :transition :deliver
                                    :from-state :entered :to-state :delivered :occurred-at now})
    {:id id :instance instance}))

(defn- service [soft? store]
  (let [cfg (config soft?)
        sp  (schema-repo/create-schema-repository *db* cfg)]
    (admin-service/create-admin-service *db* sp nil nil cfg nil
                                        (admin-adapter/create-entity-workflows store))))

(defn- invoice [id]
  (db/execute-one! *db* {:select [:*] :from [:wd_invoices] :where [:= :id (str id)]}))

(deftest ^:integration a-hard-delete-removes-the-instance-and-its-audit
  (let [store (persistence/create-workflow-store (:datasource *db*))
        {:keys [id instance]} (invoice-with-workflow! store)]
    (is (true? (admin-ports/delete-entity (service false store) :wd-invoices id)))
    (is (nil? (invoice id)))
    (is (nil? (ports/find-instance store instance)))
    (is (empty? (ports/find-audit-log store instance)))))

(deftest ^:integration a-soft-delete-keeps-the-instance-and-its-audit
  ;; A soft delete can be undone; the workflow history has to survive it.
  (let [store (persistence/create-workflow-store (:datasource *db*))
        {:keys [id instance]} (invoice-with-workflow! store)]
    (is (true? (admin-ports/delete-entity (service true store) :wd-invoices id)))
    (is (some? (:deleted-at (invoice id))))
    (is (= :entered (:current-state (ports/find-instance store instance))))
    (is (= 1 (count (ports/find-audit-log store instance))))))

(deftest ^:integration a-failed-workflow-removal-rolls-back-the-entity-delete
  (let [store (persistence/create-workflow-store (:datasource *db*))
        {:keys [id instance]} (invoice-with-workflow! store)]
    ;; The audit table gone: removing the instance fails inside the delete.
    (db/execute-update! *db* {:raw "ALTER TABLE workflow_audit RENAME TO workflow_audit_away"})
    (try
      (is (thrown? Exception (admin-ports/delete-entity (service false store) :wd-invoices id)))
      (finally
        (db/execute-update! *db* {:raw "ALTER TABLE workflow_audit_away RENAME TO workflow_audit"})))
    (is (some? (invoice id)) "the invoice is still there")
    (is (some? (ports/find-instance store instance)))))

(deftest ^:integration a-store-on-another-database-is-removed-after-the-fact
  ;; Not atomic, and documented as such: the admin's transaction cannot reach
  ;; another datasource.
  (let [other (h2 "workflow_admin_delete_other")]
    (try
      (let [store (persistence/create-workflow-store (:datasource other))
            {:keys [id instance]} (invoice-with-workflow! store)]
        (is (true? (admin-ports/delete-entity (service false store) :wd-invoices id)))
        (is (nil? (invoice id)))
        (testing "the instance on the other database is still removed"
          (is (nil? (ports/find-instance store instance)))))
      (finally
        (db/execute-update! other {:raw "DROP ALL OBJECTS"})
        (db-factory/close-db-context! other)))))
