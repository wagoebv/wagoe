(ns wagoe.workflow.shell.service-test
  "Service-level tests for the WorkflowService.

   Uses in-memory doubles for IWorkflowStore and IWorkflowRegistry
   to exercise the full transition orchestration without a real DB."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.service :as service])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; In-memory store double
;; =============================================================================

(defrecord MemoryStore [instances audit-log]
  ports/IWorkflowStore

  (save-instance! [_ instance]
    (swap! instances assoc (:id instance) instance)
    instance)

  (find-instance [_ instance-id]
    (get @instances instance-id))

  (find-instance-by-entity [_ entity-type entity-id]
    (first (filter #(and (= entity-type (:entity-type %))
                         (= entity-id (:entity-id %)))
                   (vals @instances))))

  (update-instance-state! [this instance-id new-state]
    (let [updated (-> (ports/find-instance this instance-id)
                      (assoc :current-state new-state
                             :updated-at (Instant/now)))]
      (swap! instances assoc instance-id updated)
      updated))

  (save-audit-entry! [_ entry]
    (swap! audit-log conj entry)
    entry)

  (find-audit-log [_ instance-id]
    (filterv #(= instance-id (:instance-id %)) @audit-log))

  (list-instances [_ opts]
    (let [{:keys [workflow-id entity-type current-state limit offset]
           :or {limit 50 offset 0}} opts
          all-instances (vals @instances)
          filtered (cond->> all-instances
                     workflow-id   (filter #(= workflow-id (:workflow-id %)))
                     entity-type   (filter #(= entity-type (:entity-type %)))
                     current-state (filter #(= current-state (:current-state %))))
          sorted (sort-by (comp str :updated-at) #(compare %2 %1) filtered)]
      (vec (take limit (drop offset sorted))))))

(defn create-memory-store []
  (->MemoryStore (atom {}) (atom [])))

;; =============================================================================
;; Test workflow
;; =============================================================================

(def ^:private order-def
  {:id             :order-workflow
   :initial-state  :pending
   :states         #{:pending :paid :shipped :cancelled}
   :transitions    [{:from :pending :to :paid
                     :required-permissions [:finance :admin]}
                    {:from :paid    :to :shipped}
                    {:from :pending :to :cancelled}
                    {:from :paid    :to :cancelled
                     :side-effects [:notify-cancellation]}]})

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *store* nil)
(def ^:dynamic *registry* nil)
(def ^:dynamic *service* nil)

(defn with-clean-system [f]
  (registry/clear-registry!)
  (registry/register-workflow! order-def)
  (let [store    (create-memory-store)
        registry (registry/create-workflow-registry)
        svc      (service/create-workflow-service store registry)]
    (binding [*store* store *registry* registry *service* svc]
      (f)))
  (registry/clear-registry!))

(use-fixtures :each with-clean-system)

;; =============================================================================
;; start-workflow!
;; =============================================================================

(deftest ^:unit start-workflow-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})]

    (testing "returns an instance with initial-state"
      (is (= :order-workflow (:workflow-id instance)))
      (is (= :order (:entity-type instance)))
      (is (= entity-id (:entity-id instance)))
      (is (= :pending (:current-state instance)))
      (is (some? (:id instance))))

    (testing "persists instance in store"
      (is (some? (ports/find-instance *store* (:id instance)))))

    (testing "throws not-found for unknown workflow"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Workflow definition not found"
                            (ports/start-workflow! *service*
                                                   {:workflow-id :ghost
                                                    :entity-type :order
                                                    :entity-id   entity-id}))))))

;; =============================================================================
;; transition! — happy path
;; =============================================================================

(deftest ^:unit transition-happy-path-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})
        result    (ports/transition! *service*
                                     {:instance-id (:id instance)
                                      :transition  :paid
                                      :actor-roles [:admin]})]

    (testing "returns success"
      (is (:success? result)))

    (testing "updated instance has new state"
      (is (= :paid (:current-state (:instance result)))))

    (testing "audit entry is created"
      (let [entry (:audit-entry result)]
        (is (= (:id instance) (:instance-id entry)))
        (is (= :pending (:from-state entry)))
        (is (= :paid (:to-state entry)))
        (is (= :paid (:transition entry)))))

    (testing "current-state accessor reflects new state"
      (is (= :paid (ports/current-state *service* (:id instance)))))

    (testing "audit-log contains one entry"
      (is (= 1 (count (ports/audit-log *service* (:id instance))))))))

;; =============================================================================
;; transition! — rejection paths
;; =============================================================================

(deftest ^:unit transition-rejected-permissions-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})
        result    (ports/transition! *service*
                                     {:instance-id (:id instance)
                                      :transition  :paid
                                      :actor-roles [:user]})]

    (testing "returns failure"
      (is (false? (:success? result))))

    (testing "error type is :insufficient-permissions"
      (is (= :insufficient-permissions (get-in result [:error :type]))))

    (testing "state is unchanged after rejection"
      (is (= :pending (ports/current-state *service* (:id instance)))))

    (testing "no audit entry created"
      (is (empty? (ports/audit-log *service* (:id instance)))))))

(deftest ^:unit transition-not-found-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})
        result    (ports/transition! *service*
                                     {:instance-id (:id instance)
                                      :transition  :shipped  ; not reachable from :pending
                                      :actor-roles [:admin]})]

    (testing "returns failure"
      (is (false? (:success? result))))

    (testing "error type is :transition-not-found"
      (is (= :transition-not-found (get-in result [:error :type]))))))

(deftest ^:unit transition-instance-not-found-test
  (let [ghost-id (UUID/randomUUID)]
    (testing "throws not-found when instance does not exist"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Workflow instance not found"
                            (ports/transition! *service*
                                               {:instance-id ghost-id
                                                :transition  :paid
                                                :actor-roles [:admin]}))))))

;; =============================================================================
;; audit-log accumulates across transitions
;; =============================================================================

(deftest ^:unit audit-log-accumulates-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})]

    (ports/transition! *service* {:instance-id (:id instance)
                                  :transition  :paid
                                  :actor-roles [:admin]})
    (ports/transition! *service* {:instance-id (:id instance)
                                  :transition  :shipped
                                  :actor-roles []})

    (let [log (ports/audit-log *service* (:id instance))]
      (testing "two entries are recorded"
        (is (= 2 (count log))))
      (testing "entries are in order"
        (is (= :pending (:from-state (first log))))
        (is (= :paid (:from-state (second log))))))))

;; =============================================================================
;; available-transitions
;; =============================================================================

(deftest ^:unit available-transitions-test
  (let [entity-id (UUID/randomUUID)
        instance  (ports/start-workflow! *service*
                                         {:workflow-id :order-workflow
                                          :entity-type :order
                                          :entity-id   entity-id})]

    (testing "returns enriched transitions including :id and :enabled?"
      (let [ts (ports/available-transitions *service* (:id instance) [:admin] nil)]
        (is (= 2 (count ts)))  ; :paid and :cancelled from :pending
        (is (every? #(contains? % :id) ts))
        (is (every? #(contains? % :enabled?) ts))))

    (testing "marks transitions as disabled when actor lacks permission"
      (let [ts    (ports/available-transitions *service* (:id instance) [:user] nil)
            by-id (into {} (map (fn [t] [(:id t) t]) ts))]
        (is (false? (:enabled? (get by-id :paid))))
        (is (= :insufficient-permissions (:reason (get by-id :paid))))
        (is (true?  (:enabled? (get by-id :cancelled))))))

    (testing "returns empty vector for non-existent instance"
      (is (empty? (ports/available-transitions *service* (UUID/randomUUID) [:admin] nil))))))

;; =============================================================================
;; Lifecycle hooks
;; =============================================================================

(def ^:private hook-workflow-base
  {:id            :hook-workflow
   :initial-state :draft
   :states        #{:draft :approved :rejected}
   :transitions   [{:from :draft :to :approved}
                   {:from :draft :to :rejected}]})

(deftest ^:unit lifecycle-hooks-test
  (let [enter-calls (atom [])
        any-calls   (atom [])]
    (registry/register-workflow!
     (assoc hook-workflow-base
            :hooks {:on-enter-approved [(fn [inst _ae _ctx]
                                          (swap! enter-calls conj (:current-state inst)))]
                    :on-any-transition [(fn [_inst _ae _ctx]
                                          (swap! any-calls conj true))]}))
    (let [hook-store (create-memory-store)
          hook-svc   (service/create-workflow-service hook-store *registry* nil nil)
          instance   (ports/start-workflow! hook-svc
                                            {:workflow-id :hook-workflow
                                             :entity-type :document
                                             :entity-id   (UUID/randomUUID)})]
      (ports/transition! hook-svc
                         {:instance-id (:id instance)
                          :transition  :approved
                          :actor-roles []})
      (testing ":on-enter-<to-state> hook fires after successful transition"
        (is (= 1 (count @enter-calls)))
        (is (= :approved (first @enter-calls))))
      (testing ":on-any-transition hook fires after every successful transition"
        (is (= 1 (count @any-calls)))))))

;; =============================================================================
;; process-auto-transitions!
;; =============================================================================

(def ^:private auto-workflow-def
  {:id            :auto-workflow
   :initial-state :pending
   :states        #{:pending :processing :done}
   :transitions   [{:from :pending :to :processing :auto? true}
                   {:from :processing :to :done}]})

(deftest ^:unit process-auto-transitions-test
  (registry/register-workflow! auto-workflow-def)
  (let [auto-store (create-memory-store)
        auto-svc   (service/create-workflow-service auto-store *registry* nil nil)
        i1 (ports/start-workflow! auto-svc {:workflow-id :auto-workflow
                                            :entity-type :task
                                            :entity-id   (UUID/randomUUID)})
        i2 (ports/start-workflow! auto-svc {:workflow-id :auto-workflow
                                            :entity-type :task
                                            :entity-id   (UUID/randomUUID)})]

    (testing "all instances in the from-state are transitioned automatically"
      (let [result (ports/process-auto-transitions! auto-svc :auto-workflow)]
        (is (= 2 (:processed result)))
        (is (= 0 (:failed result)))
        (is (= 2 (:attempted result)))))

    (testing "instances are in the target state after processing"
      (is (= :processing (ports/current-state auto-svc (:id i1))))
      (is (= :processing (ports/current-state auto-svc (:id i2)))))

    (testing "returns zero counts for unknown workflow"
      (let [result (ports/process-auto-transitions! auto-svc :ghost-workflow)]
        (is (= 0 (:processed result)))
        (is (= 0 (:attempted result)))))))
