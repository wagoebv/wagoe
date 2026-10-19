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
            [wagoe.jobs.core.job] ; the service resolves create-job from it at run time
            [wagoe.jobs.ports :as jobs-ports]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.tenant-migrations :as tmig]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.http :as http]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.service :as service]))

(def ^:private hook-calls
  "What each :on-any-transition hook saw: the bound tenant, and the instance
   as the store reads it."
  (atom []))

(def ^:private order-def
  {:id            :tenant-order-workflow
   :initial-state :pending
   :states        #{:pending :paid}
   :transitions   [{:from :pending :to :paid :side-effects [:notify-paid]}]})

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
        ;; By id and state: PostgreSQL keeps microseconds, the JVM may have nanos.
        (in-tenant a #(do (is (= (select-keys mine [:id :current-state])
                                 (select-keys (ports/find-instance store (:id mine)) [:id :current-state])))
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

(defn- migrate-public! [ctx]
  (migratus/migrate (mig/migratus-config (:datasource ctx)
                                         (filterv #(str/includes? % "workflow")
                                                  (mig/discover-migration-dirs)))))

(defn- refusal
  "The ex-data of what `f` throws, or nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ^:integration a-tenant-schema-runs-the-public-series
  (let [schema (str "tenant_wfs" (System/nanoTime))]
    (migrate-public! @db-ctx)
    (tenant-schema! schema)
    (is (= #{20260926100000 20260926100100 20260927200000 20260928100000}
           (->> (jdbc/execute! (:datasource @db-ctx)
                               [(str "SELECT id FROM " schema ".schema_migrations")])
                (map :schema_migrations/id)
                (filter #(<= 20260926100000 % 20260928100000))
                set))
        "workflow's public migrations, so a new one reaches tenants with no twin")))

(deftest ^:integration tenant-work-never-falls-back-to-public
  (let [ctx   @db-ctx
        bare  (str "tenant_wfbare" (System/nanoTime))
        store (persistence/create-workflow-store (:datasource ctx))]
    (migrate-public! ctx)
    (jdbc/execute! (:datasource ctx) [(str "CREATE SCHEMA " bare)])

    (testing "a tenant schema without the tables is refused, not read through to public"
      (let [data (refusal #(in-tenant bare (fn [] (ports/list-instances store {}))))]
        (is (= :configuration-error (:type data)))
        (is (= bare (:schema data)))))

    (testing "tenant work outside a tenant schema is refused"
      (is (= :configuration-error
             (:type (refusal #(binding [db/*tenant-id* (random-uuid)]
                                (ports/list-instances store {}))))))
      (is (= :configuration-error
             (:type (refusal #(binding [db/*tenant-id* (random-uuid)]
                                (db/with-transaction* ctx (fn [_] (ports/list-instances store {})))))))
          "on a transaction in public"))

    (testing "in a tenant, a caller's own transaction on another connection is refused"
      (let [schema (str "tenant_wfown" (System/nanoTime))]
        (tenant-schema! schema)
        (is (= :configuration-error
               (:type (refusal #(in-tenant schema
                                           (fn []
                                             (with-open [^java.sql.Connection own (jdbc/get-connection (:datasource ctx))]
                                               (.setAutoCommit own false)
                                               (ports/list-instances (ports/store-in-tx store own) {}))))))))))))

(deftest ^:integration hooks-and-jobs-run-in-the-tenant
  (let [ctx      @db-ctx
        schema   (str "tenant_wfj" (System/nanoTime))
        tenant   (random-uuid)
        store    (persistence/create-workflow-store (:datasource ctx))
        enqueued (atom [])
        queue    #_{:clj-kondo/ignore [:missing-protocol-method]}
                 (reify
                   jobs-ports/IJobQueue
                   (enqueue-job! [_ _ job] (swap! enqueued conj [:plain job]) (:id job))
                   jobs-ports/ITransactionalJobQueue
                   (enqueue-in-tx! [_ _ _ job] (swap! enqueued conj [:in-tx job]) (:id job)))
        svc      (service/create-workflow-service store (registry/create-workflow-registry) queue)]
    (reset! hook-calls [])
    (registry/register-workflow!
     (assoc order-def :hooks {:on-any-transition
                              [(fn [inst _ _]
                                 (swap! hook-calls conj [db/*tenant-id*
                                                         (:current-state (ports/find-instance store (:id inst)))]))]}))
    (migrate-public! ctx)
    (tenant-schema! schema)
    (binding [db/*tenant-id* tenant]
      (in-tenant schema
                 #(let [inst (ports/start-workflow! svc {:workflow-id :tenant-order-workflow
                                                        :entity-type :order
                                                        :entity-id   (random-uuid)})]
                    (is (:success? (ports/transition! svc {:instance-id (:id inst)
                                                           :transition  :paid
                                                           :actor-roles [:admin]}))))))
    (testing "a hook runs with the tenant bound, and reads the instance in its schema"
      (is (= [[tenant :paid]] @hook-calls)))
    (testing "the job joins the request's transaction and carries the tenant"
      (is (= [[:in-tx {:tenant-id (str tenant)}]]
             (map (fn [[how job]] [how (:metadata job)]) @enqueued))))))
