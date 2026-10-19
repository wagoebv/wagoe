(ns wagoe.workflow.shell.tenant-isolation-test
  "Each tenant's instances live in its own schema. A tenant request runs in a
   transaction bound to that schema, and the store reads through it, so one
   tenant cannot reach another's instances. On embedded PostgreSQL, the only
   engine with tenant schemas."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.tenant-migrations :as tmig]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.http :as http]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.service :as service]))

(def ^:private order-def
  {:id            :tenant-order-workflow
   :initial-state :pending
   :states        #{:pending :paid}
   :transitions   [{:from :pending :to :paid}]})

(def ^:private db-ctx (atom nil))

(use-fixtures :once (epg/with-embedded-pg #(reset! db-ctx %)))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (registry/register-workflow! order-def)
    (try (f) (finally (registry/clear-registry!)))))

(defn- in-tenant
  "Call `f` as a tenant request does: in a transaction bound to `schema`."
  [schema f]
  (provisioning/call-in-schema @db-ctx schema (fn [_] (f))))

(defn- tenant-schema! [schema]
  (jdbc/execute! (:datasource @db-ctx) [(str "CREATE SCHEMA " schema)])
  (tmig/migrate-tenant! @db-ctx schema))

(defn- in-schema? [schema table column]
  (some? (jdbc/execute-one! (:datasource @db-ctx)
                            ["SELECT 1 FROM information_schema.columns
                              WHERE table_schema = ? AND table_name = ? AND column_name = ?"
                             schema table column])))

(deftest ^:integration a-tenant-sees-only-its-own-instances
  (let [ctx      @db-ctx
        suffix   (System/nanoTime)
        [a b]    [(str "tenant_wfa" suffix) (str "tenant_wfb" suffix)]
        store    (persistence/create-workflow-store (:datasource ctx))
        reg      (registry/create-workflow-registry)
        svc      (service/create-workflow-service store reg)
        start!   #(ports/start-workflow! svc {:workflow-id :tenant-order-workflow
                                              :entity-type :order
                                              :entity-id   (random-uuid)})]
    (migratus/migrate (mig/migratus-config (:datasource ctx)
                                           (filterv #(str/includes? % "workflow")
                                                    (mig/discover-migration-dirs))))
    (tenant-schema! a)
    (tenant-schema! b)

    (testing "a tenant schema gets the tables as public has them"
      (is (every? #(in-schema? a "workflow_instances" %) ["id" "entity_uuid"]))
      (is (in-schema? b "workflow_audit" "occurred_at")))

    (let [mine (in-tenant a start!)]
      (testing "the tenant that started it reads and moves it"
        (in-tenant a #(do (is (= mine (ports/find-instance store (:id mine))))
                          (is (:success? (ports/transition! svc {:instance-id (:id mine)
                                                                 :transition  :paid
                                                                 :actor-roles [:admin]})))
                          (is (= 1 (count (ports/audit-log svc (:id mine))))))))

      (testing "another tenant finds nothing"
        (in-tenant b #(do (is (nil? (ports/find-instance store (:id mine))))
                          (is (empty? (ports/audit-log svc (:id mine))))
                          (is (empty? (ports/list-instances store {})))
                          (is (nil? (ports/find-instance-by-entity store :order (:entity-id mine)))))))

      (testing "nor does its admin page list it"
        (let [page (in-tenant b #(http/handle-list-instances-web store reg nil {:user {:role :admin}}))]
          (is (= 200 (:status page)))
          (is (not (str/includes? (:body page) (str (:id mine)))))))

      (testing "outside any tenant, public holds none of it"
        (is (empty? (ports/list-instances store {}))))

      (testing "a single-tenant start writes to public, as before"
        (let [public (start!)]
          (is (= [(:id public)] (map :id (ports/list-instances store {}))))
          (is (nil? (in-tenant a #(ports/find-instance store (:id public))))))))))
