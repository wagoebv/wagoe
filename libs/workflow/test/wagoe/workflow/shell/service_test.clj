(ns wagoe.workflow.shell.service-test
  "Service-level tests for the WorkflowService.

   Uses in-memory doubles for IWorkflowStore and IWorkflowRegistry
   to exercise the full transition orchestration without a real DB."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is use-fixtures]]
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
      (vec (take limit (drop offset sorted)))))

  (delete-instance! [_ instance-id]
    (let [existed? (contains? @instances instance-id)]
      (swap! instances dissoc instance-id)
      (swap! audit-log (fn [log] (filterv #(not= instance-id (:instance-id %)) log)))
      existed?)))

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

(deftest ^:unit transition-nil-is-refused-not-thrown
  ;; The rejection message was built with `(name transition)`, so rejecting a
  ;; nil transition threw out of the branch that exists to reject it — and the
  ;; HTTP boundary turned a refusable request into a 500 (BOU-478). The route
  ;; schema now stops a bodyless POST earlier, but the engine is callable from
  ;; anywhere and has to answer rather than throw.
  (let [instance (ports/start-workflow! *service*
                                        {:workflow-id :order-workflow
                                         :entity-type :order
                                         :entity-id   (UUID/randomUUID)})
        result   (ports/transition! *service*
                                    {:instance-id (:id instance)
                                     :transition  nil
                                     :actor-roles [:admin]})]
    (is (false? (:success? result)))
    (is (= :transition-not-found (get-in result [:error :type])))
    (is (string? (get-in result [:error :message])))))

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
;; The documented examples run (BOU-561)
;; =============================================================================

(def ^:private lib-dir
  "libs/workflow, found from this file so the test runs from any directory."
  (-> (io/resource "wagoe/workflow/shell/service_test.clj")
      io/file .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile))

(def ^:private md-block #"(?s)```clojure\n(.*?)```")
(def ^:private adoc-block #"(?s)\[source,clojure\]\n----\n(.*?)\n----")

(defn- first-block-under
  "The first code block after `heading` in the file at `path` (relative to
   libs/workflow), or nil after failing an assertion that says what is missing."
  [path heading block-re]
  (let [f (io/file lib-dir path)]
    (is (.exists f) (str path " not found under " lib-dir))
    (when (.exists f)
      (let [doc (slurp f)
            i   (.indexOf ^String doc ^String heading)]
        (is (not (neg? i))
            (str "\"" heading "\" is missing from " path
                 "; this test runs the example under it"))
        (when-not (neg? i)
          (second (re-find block-re (subs doc i))))))))

(defn- bind-effects
  "Evaluate a documented form with its side-effect symbols bound from `effects`."
  [form effects]
  ((eval `(fn [{:syms [~'notify-finance! ~'release-reservation! ~'sync-external!]}]
            ~form))
   effects))

(defn- documented-definition
  "The map a documented `defworkflow` block defines. Fails when the block's
   require does not name a namespace with `defworkflow` in it."
  [block effects]
  (let [forms (read-string (str "[" block "]"))
        req   (first (filter #(and (seq? %) (= 'require (first %))) forms))
        dw    (first (filter #(and (seq? %) (= 'defworkflow (first %))) forms))]
    (when req
      (let [ns-sym (first (second (second req)))]
        (is (some? (requiring-resolve (symbol (str ns-sym) "defworkflow")))
            (str ns-sym " has no defworkflow"))))
    (is (some? dw) "the example has no defworkflow form")
    (some-> dw (nth 2) (bind-effects effects))))

(deftest ^:unit the-documented-definitions-register
  (doseq [[path heading re] [["AGENTS.md" "## Defining a Workflow" md-block]
                             ["../../docs/modules/libraries/pages/workflow.adoc"
                              "== Defining a workflow" adoc-block]]]
    (testing path
      (let [calls      (atom [])
            record     (fn [k] (fn [& _] (swap! calls conj k)))
            definition (some-> (first-block-under path heading re)
                               (documented-definition
                                {'notify-finance! (record :notify)
                                 'sync-external!  (record :sync)}))]
        (is (= (:id definition) (registry/register-workflow! definition)))
        (let [svc      (service/create-workflow-service (create-memory-store) *registry* nil nil)
              instance (ports/start-workflow! svc {:workflow-id (:id definition)
                                                   :entity-type :order
                                                   :entity-id   (UUID/randomUUID)})]
          (is (:success? (ports/transition! svc {:instance-id (:id instance)
                                                 :transition  :paid
                                                 :actor-roles [:admin]})))
          (is (= [:notify :sync] @calls)))))))

(deftest ^:unit the-documented-hook-example-runs
  ;; The docs showed (fn [instance]); the schema wants a vector of 3-arity fns,
  ;; so the example failed to register.
  (let [calls (atom [])
        block (first-block-under "AGENTS.md" "## Lifecycle Hooks" md-block)
        hooks (:hooks (bind-effects
                       (read-string (str "{" block "}"))
                       {'notify-finance!      (fn [inst] (swap! calls conj [:notify (:current-state inst)]))
                        'release-reservation! (fn [inst] (swap! calls conj [:release (:current-state inst)]))
                        'sync-external!       (fn [ae ctx] (swap! calls conj [:sync (:to-state ae) ctx]))}))]
    (registry/register-workflow!
     {:id            :documented-hooks
      :initial-state :pending
      :states        #{:pending :paid}
      :transitions   [{:from :pending :to :paid}]
      :hooks         hooks})
    (let [svc      (service/create-workflow-service (create-memory-store) *registry* nil nil)
          instance (ports/start-workflow! svc {:workflow-id :documented-hooks
                                               :entity-type :order
                                               :entity-id   (UUID/randomUUID)})]
      (is (:success? (ports/transition! svc {:instance-id (:id instance)
                                             :transition  :paid
                                             :actor-roles []
                                             :context     {:by "test"}})))
      (is (= [[:release :paid] [:notify :paid] [:sync :paid {:by "test"}]]
             @calls)))))

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

;; =============================================================================
;; What a guard sees (BOU-571)
;; =============================================================================

(def ^:private deliver-def
  {:id            :deliver-workflow
   :initial-state :draft
   :states        #{:draft :delivered}
   :transitions   [{:from :draft :to :delivered :name :deliver :guard :ok?}]})

(defn- start-deliver!
  "A service over `definition` with `guards` as its registry, and an instance of it."
  [definition guards]
  (registry/register-workflow! definition)
  (let [svc (service/create-workflow-service (create-memory-store) *registry* nil guards)]
    [svc (ports/start-workflow! svc {:workflow-id (:id definition)
                                     :entity-type :invoice
                                     :entity-id   (UUID/randomUUID)})]))

(deftest ^:unit a-guard-sees-the-instance-and-the-context
  (let [seen           (atom nil)
        [svc instance] (start-deliver! deliver-def {:ok? (fn [in] (reset! seen in) true)})
        forged         {:id (UUID/randomUUID)}]
    (is (:success? (ports/transition! svc {:instance-id (:id instance)
                                           :transition  :deliver
                                           :context     {:by "test" :workflow/instance forged}})))
    (testing "the caller's keys stay where one-argument guards read them"
      (is (= "test" (:by @seen))))
    (testing "the instance is the stored one, not what the caller sent"
      (is (= (:id instance) (:id (:workflow/instance @seen))))
      (is (= :draft (:current-state (:workflow/instance @seen)))))
    (testing "no loader, no entity"
      (is (not (contains? @seen :workflow/entity))))
    (testing "the audit entry keeps the caller's context"
      (is (= {:by "test" :workflow/instance forged}
             (:context (first (ports/audit-log svc (:id instance)))))))))

(deftest ^:unit a-guard-loads-the-entity-through-the-workflows-loader
  (let [calls          (atom [])
        lines          (atom 0)
        definition     (assoc deliver-def
                              :entity-loader (fn [entity-type entity-id]
                                               (swap! calls conj [entity-type entity-id])
                                               {:line-count @lines})
                              :guards {:ok? (fn [{:workflow/keys [entity]}]
                                              (pos? (:line-count @entity)))})
        ;; The service's registry is what `wagoe add workflow` passes: empty.
        [svc instance] (start-deliver! definition {})]
    (testing "a guard on the definition is found, and refuses with no lines"
      (is (= :guard-rejected
             (get-in (ports/transition! svc {:instance-id (:id instance) :transition :deliver})
                     [:error :type]))))
    (testing "the loader got the instance's entity"
      (is (= [[:invoice (:entity-id instance)]] @calls)))
    (testing "available-transitions runs the same guard"
      (is (false? (:enabled? (first (ports/available-transitions svc (:id instance) [] nil))))))
    (reset! lines 2)
    (is (:success? (ports/transition! svc {:instance-id (:id instance) :transition :deliver})))))

(deftest ^:unit the-entity-is-loaded-only-when-a-guard-asks
  (let [calls          (atom 0)
        definition     (assoc deliver-def :entity-loader (fn [_ _] (swap! calls inc) {}))
        [svc instance] (start-deliver! definition {:ok? (constantly true)})]
    (is (:success? (ports/transition! svc {:instance-id (:id instance) :transition :deliver})))
    (is (zero? @calls))))

(defn- bind-symbols
  "Evaluate `form` with each symbol in `bindings` bound to its value."
  [form bindings]
  ((eval `(fn [{:syms [~@(keys bindings)]}] ~form)) bindings))

(defn- documented-guard-workflow
  "The workflow the guard example under `heading` defines, with
   `count-invoice-lines` bound to `count-fn`."
  [path heading re count-fn]
  (let [block (first-block-under path heading re)
        forms (when block (read-string (str "[" block "]")))
        d     (first (filter #(and (seq? %) (= 'def (first %))) forms))]
    (is (some? d) (str "the guard example in " path " defines nothing"))
    (some-> d (nth 2) (bind-symbols {'count-invoice-lines count-fn}))))

(deftest ^:unit the-documented-guard-example-runs
  (doseq [[path heading re] [["AGENTS.md" "## Guards" md-block]
                             ["../../docs/modules/libraries/pages/workflow.adoc"
                              "== Guards" adoc-block]]]
    (testing path
      (registry/clear-registry!)
      (let [lines      (atom {})
            definition (documented-guard-workflow path heading re
                                                  (fn [id] (count (get @lines id))))
            _          (registry/register-workflow! definition)
            svc        (service/create-workflow-service (create-memory-store) *registry* nil {})
            invoice-id (UUID/randomUUID)
            instance   (ports/start-workflow! svc {:workflow-id (:id definition)
                                                   :entity-type :invoice
                                                   :entity-id   invoice-id})
            deliver!   #(ports/transition! svc {:instance-id (:id instance)
                                                :transition  :deliver
                                                :actor-roles [:admin]})]
        (testing "an invoice with no lines is not delivered"
          (is (= :guard-rejected (get-in (deliver!) [:error :type]))))
        (swap! lines assoc invoice-id [{:qty 1}])
        (testing "one line is enough"
          (is (:success? (deliver!))))))))

;; =============================================================================
;; The documented config is what `wagoe add workflow` writes (BOU-571)
;; =============================================================================

(defn- catalogue-config
  "The `:wagoe/workflow` value `wagoe add workflow` writes, read from its catalogue."
  []
  (let [f     (io/file lib-dir "../wagoe-cli/resources/wagoe/cli/modules-catalogue.edn")
        entry (->> (:modules (read-string (slurp f)))
                   (filter #(= "workflow" (:name %)))
                   first)]
    (:wagoe/workflow (read-string (str "{" (:config-snippet entry) "}")))))

(deftest ^:unit the-documented-config-is-what-wagoe-add-writes
  (let [written (catalogue-config)
        edn     #"(?s)```edn\n(.*?)```"]
    (is (= {} written))
    (doseq [[path heading re] [["AGENTS.md" "## Integrant Wiring" edn]
                               ["README.md" "## Configuration" edn]
                               ["../../docs/modules/libraries/pages/workflow.adoc"
                                "== Configuration" #"(?s)\[source,edn\]\n----\n(.*?)\n----"]]]
      (testing path
        (is (= written
               (some-> (first-block-under path heading re)
                       read-string
                       :wagoe/workflow)))))))
