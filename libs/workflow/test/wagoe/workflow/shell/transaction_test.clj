(ns wagoe.workflow.shell.transaction-test
  "The engine's tx arities: a workflow written on the caller's transaction
   commits and rolls back with the caller's own row. On the engine
   WAGOE_TEST_DB names."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.service :as service]))

(def ^:private order-def
  {:id            :tx-order-workflow
   :initial-state :pending
   :states        #{:pending :paid}
   :transitions   [{:from :pending :to :paid}]})

(def ^:dynamic *ctx* nil)
(def ^:dynamic *svc* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "workflow_tx"
      (fn [{:keys [datasource] :as ctx}]
        (migratus/migrate (mig/migratus-config datasource
                                               (filterv #(str/includes? % "workflow")
                                                        (mig/discover-migration-dirs))))
        (jdbc/execute! datasource ["CREATE TABLE orders (id VARCHAR(36) PRIMARY KEY)"])
        (registry/clear-registry!)
        (registry/register-workflow! order-def)
        (try
          (binding [*ctx* ctx
                    *svc* (service/create-workflow-service
                           (persistence/create-workflow-store datasource)
                           (registry/create-workflow-registry))]
            (f))
          (finally (registry/clear-registry!)))))))

(defn- count-of [table]
  (-> (jdbc/execute-one! (:datasource *ctx*) [(str "SELECT COUNT(*) AS n FROM " table)])
      vals first long))

(defn- create-order!
  "An order and its workflow, moved to paid, all on `tx`."
  [tx]
  (let [id (random-uuid)]
    (jdbc/execute! tx ["INSERT INTO orders (id) VALUES (?)" (str id)])
    (let [instance (ports/start-workflow! *svc* tx {:workflow-id :tx-order-workflow
                                                    :entity-type :order
                                                    :entity-id   id})]
      (is (= [:paid] (map :id (ports/available-transitions *svc* tx (:id instance) [:admin] nil))))
      (is (:success? (ports/transition! *svc* tx {:instance-id (:id instance)
                                                  :transition  :paid
                                                  :actor-roles [:admin]})))
      (is (= [] (ports/available-transitions *svc* tx (:id instance) [:admin] nil))
          "read on the transaction, which has moved it"))
    id))

(deftest ^:integration a-rolled-back-transaction-leaves-no-instance-and-no-audit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"after the writes"
                        (jdbc/with-transaction [tx (:datasource *ctx*)]
                          (create-order! tx)
                          (throw (ex-info "after the writes" {:type :internal-error})))))
  (is (= [0 0 0] (map count-of ["orders" "workflow_instances" "workflow_audit"]))))

(deftest ^:integration a-committed-transaction-keeps-both
  (let [id (jdbc/with-transaction [tx (:datasource *ctx*)] (create-order! tx))]
    (is (= 1 (count-of "orders")))
    (is (= :paid (:current-state (ports/find-instance-by-entity (:store *svc*) :order id))))
    (is (= 1 (count-of "workflow_audit")))))

(deftest ^:integration a-platform-transaction-context-is-joined-too
  (is (thrown? clojure.lang.ExceptionInfo
               (db/with-transaction [tx *ctx*]
                 (create-order! tx)
                 (throw (ex-info "roll back" {:type :internal-error})))))
  (is (= [0 0] (map count-of ["orders" "workflow_instances"])))
  (testing "and the connection it holds, as db/current-transaction answers it"
    (db/with-transaction [_ *ctx*]
      (create-order! (db/current-transaction)))
    (is (= [1 1] (map count-of ["orders" "workflow_instances"])))))

(deftest ^:integration a-second-start-in-the-transaction-returns-the-first-and-keeps-it-usable
  ;; On PostgreSQL a refused insert aborts the transaction, unless the store
  ;; rolls back to a savepoint.
  (let [id    (random-uuid)
        input {:workflow-id :tx-order-workflow :entity-type :order :entity-id id}]
    (jdbc/with-transaction [tx (:datasource *ctx*)]
      (let [first-start (ports/start-workflow! *svc* tx input)]
        (is (= (:id first-start) (:id (ports/start-workflow! *svc* tx input))))
        (jdbc/execute! tx ["INSERT INTO orders (id) VALUES (?)" (str id)])))
    (is (= [1 1] (map count-of ["orders" "workflow_instances"])))))

(deftest ^:integration a-datasource-is-refused-as-a-transaction
  (let [e (try (ports/start-workflow! *svc* (:datasource *ctx*)
                                      {:workflow-id :tx-order-workflow :entity-type :order
                                       :entity-id (random-uuid)})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :validation-error (:type (ex-data e))))
    (is (zero? (count-of "workflow_instances")))))

(deftest ^:integration a-nil-transaction-writes-on-its-own
  (let [id (random-uuid)]
    (ports/start-workflow! *svc* nil {:workflow-id :tx-order-workflow :entity-type :order :entity-id id})
    (is (some? (ports/find-instance-by-entity (:store *svc*) :order id)))))
