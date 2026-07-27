(ns wagoe.workflow.shell.persistence-test
  "Integration tests for WorkflowStore persistence layer.

   Uses H2 in-memory database (PostgreSQL compatibility mode) to verify
   all IWorkflowStore operations against a real JDBC datasource:
   - save-instance! / find-instance / find-instance-by-entity
   - update-instance-state!
   - save-audit-entry! / find-audit-log"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.persistence :as persistence]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.util UUID]
           [java.time Instant]
           [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Test database setup
;; =============================================================================

(def ^:private test-datasource (atom nil))
(def ^:private test-store (atom nil))

(defn- setup-test-db []
  (let [^HikariDataSource ds (connection/->pool
                               com.zaxxer.hikari.HikariDataSource
                               {:jdbcUrl  "jdbc:h2:mem:workflow-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                                :username "sa"
                                :password ""})]
    (reset! test-datasource ds)

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS workflow_instances (
                      id            TEXT NOT NULL PRIMARY KEY,
                      workflow_id   TEXT NOT NULL,
                      entity_type   TEXT NOT NULL,
                      entity_id     TEXT NOT NULL,
                      current_state TEXT NOT NULL,
                      created_at    TEXT NOT NULL,
                      updated_at    TEXT NOT NULL,
                      metadata      TEXT
                    )"])

    (jdbc/execute! ds
                   ["CREATE INDEX IF NOT EXISTS idx_workflow_instances_entity
                      ON workflow_instances (entity_type, entity_id)"])

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS workflow_audit (
                      id          TEXT NOT NULL PRIMARY KEY,
                      instance_id TEXT NOT NULL REFERENCES workflow_instances(id),
                      workflow_id TEXT NOT NULL,
                      entity_type TEXT NOT NULL,
                      entity_id   TEXT NOT NULL,
                      transition  TEXT NOT NULL,
                      from_state  TEXT NOT NULL,
                      to_state    TEXT NOT NULL,
                      actor_id    TEXT,
                      actor_roles TEXT,
                      context     TEXT,
                      occurred_at TEXT NOT NULL
                    )"])

    (jdbc/execute! ds
                   ["CREATE INDEX IF NOT EXISTS idx_workflow_audit_instance_id
                      ON workflow_audit (instance_id)"])

    (reset! test-store (persistence/create-workflow-store ds))))

(defn- teardown-test-db []
  (when-let [ds @test-datasource]
    (try
      (jdbc/execute! ds ["DROP ALL OBJECTS"])
      (.close ^HikariDataSource ds)
      (catch Exception _e nil))
    (reset! test-datasource nil)
    (reset! test-store nil)))

(defn- db-fixture [test-fn]
  (setup-test-db)
  (try
    (test-fn)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; =============================================================================
;; Test data helpers
;; =============================================================================

(defn- make-instance
  ([]
   (make-instance {}))
  ([overrides]
   (let [now (Instant/now)]
     (merge {:id            (UUID/randomUUID)
             :workflow-id   :order-workflow
             :entity-type   :order
             :entity-id     (UUID/randomUUID)
             :current-state :pending
             :created-at    now
             :updated-at    now}
            overrides))))

(defn- make-audit-entry [instance]
  {:id          (UUID/randomUUID)
   :instance-id (:id instance)
   :workflow-id (:workflow-id instance)
   :entity-type (:entity-type instance)
   :entity-id   (:entity-id instance)
   :transition  :paid
   :from-state  :pending
   :to-state    :paid
   :actor-roles [:admin]
   :occurred-at (Instant/now)})

;; =============================================================================
;; save-instance! / find-instance
;; =============================================================================

(deftest ^:integration save-and-find-instance-test
  (testing "saves and retrieves a workflow instance"
    (let [inst    (make-instance)
          saved   (ports/save-instance! @test-store inst)
          found   (ports/find-instance @test-store (:id inst))]

      (is (= (:id inst) (:id saved)))
      (is (= (:workflow-id inst) (:workflow-id found)))
      (is (= (:entity-type inst) (:entity-type found)))
      (is (= (:entity-id inst) (:entity-id found)))
      (is (= :pending (:current-state found)))
      (is (inst? (:created-at found)))
      (is (inst? (:updated-at found)))))

  (testing "returns nil for unknown instance"
    (is (nil? (ports/find-instance @test-store (UUID/randomUUID)))))

  (testing "saves instance with metadata"
    (let [inst  (make-instance {:metadata {:priority :high :region "eu"}})
          _     (ports/save-instance! @test-store inst)
          found (ports/find-instance @test-store (:id inst))]

      (is (= :high (get-in found [:metadata :priority])))
      (is (= "eu"  (get-in found [:metadata :region]))))))

;; =============================================================================
;; find-instance-by-entity
;; =============================================================================

(deftest ^:integration find-instance-by-entity-test
  (testing "finds instance by entity-type and entity-id"
    (let [entity-id (UUID/randomUUID)
          inst      (make-instance {:entity-type :order :entity-id entity-id})
          _         (ports/save-instance! @test-store inst)
          found     (ports/find-instance-by-entity @test-store :order entity-id)]

      (is (some? found))
      (is (= (:id inst) (:id found)))
      (is (= entity-id (:entity-id found)))))

  (testing "returns nil when entity has no instance"
    (is (nil? (ports/find-instance-by-entity @test-store :order (UUID/randomUUID))))))

;; =============================================================================
;; update-instance-state!
;; =============================================================================

(deftest ^:integration update-instance-state-test
  (testing "updates current-state and returns refreshed instance"
    (let [inst    (make-instance {:current-state :pending})
          _       (ports/save-instance! @test-store inst)
          updated (ports/update-instance-state! @test-store (:id inst) :paid)]

      (is (= :paid (:current-state updated)))
      (is (inst? (:updated-at updated)))))

  (testing "persisted state change is visible on subsequent find"
    (let [inst (make-instance {:current-state :pending})
          _    (ports/save-instance! @test-store inst)
          _    (ports/update-instance-state! @test-store (:id inst) :shipped)
          re-fetched (ports/find-instance @test-store (:id inst))]

      (is (= :shipped (:current-state re-fetched))))))

;; =============================================================================
;; save-audit-entry! / find-audit-log
;; =============================================================================

(deftest ^:integration save-and-find-audit-entry-test
  (testing "saves an audit entry and retrieves it via find-audit-log"
    (let [inst  (make-instance)
          _     (ports/save-instance! @test-store inst)
          entry (make-audit-entry inst)
          saved (ports/save-audit-entry! @test-store entry)
          log   (ports/find-audit-log @test-store (:id inst))]

      (is (= (:id entry) (:id saved)))
      (is (= 1 (count log)))
      (let [e (first log)]
        (is (= (:id inst)      (:instance-id e)))
        (is (= :pending        (:from-state e)))
        (is (= :paid           (:to-state e)))
        (is (= :paid           (:transition e)))
        (is (= [:admin]        (:actor-roles e)))
        (is (inst?             (:occurred-at e))))))

  (testing "returns empty vector when no audit entries exist"
    (let [inst (make-instance)
          _    (ports/save-instance! @test-store inst)]
      (is (empty? (ports/find-audit-log @test-store (:id inst))))))

  (testing "audit entries are ordered chronologically"
    (let [inst  (make-instance)
          _     (ports/save-instance! @test-store inst)
          now   (Instant/now)
          e1    (assoc (make-audit-entry inst)
                       :id (UUID/randomUUID)
                       :from-state :pending :to-state :paid
                       :occurred-at now)
          e2    (assoc (make-audit-entry inst)
                       :id (UUID/randomUUID)
                       :from-state :paid :to-state :shipped
                       :occurred-at (.plusSeconds now 10))
          _     (ports/save-audit-entry! @test-store e1)
          _     (ports/save-audit-entry! @test-store e2)
          log   (ports/find-audit-log @test-store (:id inst))]

      (is (= 2 (count log)))
      (is (= :pending (:from-state (first log))))
      (is (= :paid    (:from-state (second log))))))

  (testing "saves audit entry with optional actor-id and context"
    (let [actor-id (UUID/randomUUID)
          inst     (make-instance)
          _        (ports/save-instance! @test-store inst)
          entry    (assoc (make-audit-entry inst)
                          :actor-id actor-id
                          :context {:reason "payment-received" :amount 99.99})
          _        (ports/save-audit-entry! @test-store entry)
          found    (first (ports/find-audit-log @test-store (:id inst)))]

      (is (= actor-id (:actor-id found)))
      (is (= "payment-received" (get-in found [:context :reason]))))))

;; =============================================================================
;; list-instances
;; =============================================================================

(deftest ^:integration list-instances-test
  (testing "returns all instances when no filters applied"
    (dotimes [_ 3]
      (ports/save-instance! @test-store (make-instance)))
    (let [result (ports/list-instances @test-store {})]
      (is (= 3 (count result)))
      (is (every? #(= :order-workflow (:workflow-id %)) result))))

  (testing "filters by workflow-id"
    (let [other (make-instance {:workflow-id :invoice-workflow})
          _     (ports/save-instance! @test-store other)
          result (ports/list-instances @test-store {:workflow-id :order-workflow})]
      (is (every? #(= :order-workflow (:workflow-id %)) result))))

  (testing "filters by entity-type"
    (let [inv (make-instance {:entity-type :invoice})
          _   (ports/save-instance! @test-store inv)
          result (ports/list-instances @test-store {:entity-type :order})]
      (is (every? #(= :order (:entity-type %)) result))))

  (testing "filters by current-state"
    (let [inst (make-instance {:current-state :shipped})
          _    (ports/save-instance! @test-store inst)
          result (ports/list-instances @test-store {:current-state :shipped})]
      (is (= 1 (count result)))
      (is (= :shipped (:current-state (first result))))))

  (testing "respects limit and offset"
    (let [all (ports/list-instances @test-store {})
          page1 (ports/list-instances @test-store {:limit 2 :offset 0})
          page2 (ports/list-instances @test-store {:limit 2 :offset 2})]
      (is (>= (count all) 2))
      (is (= 2 (count page1)))
      (is (not= (:id (first page1)) (:id (first page2)))))))
