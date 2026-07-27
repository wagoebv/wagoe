(ns wagoe.workflow.shell.service
  "Workflow engine service.

   Orchestrates the full transition lifecycle:
     1. Load workflow definition from registry
     2. Load workflow instance from store
     3. Validate transition (existence, permissions, guards)
     4. Persist new state + audit entry
     5. Enqueue side-effect jobs (if job-queue provided)

   Implements IWorkflowEngine."
  (:require [wagoe.workflow.ports :as ports]
            [wagoe.workflow.core.machine :as machine]
            [wagoe.workflow.core.transitions :as transitions]
            [wagoe.workflow.core.audit :as audit]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Lifecycle hook execution
;; =============================================================================

(defn- fire-hooks!
  "Invoke lifecycle hooks declared in the workflow definition.

   Fires three stages in order:
     1. :on-exit-<from-state>   — leaving the previous state
     2. :on-enter-<to-state>    — entering the new state
     3. :on-any-transition      — fires on every successful transition

   Each hook fn receives [updated-instance audit-entry context].
   Exceptions are caught and logged; they do not abort the transition."
  [definition from-state to-state instance audit-entry context]
  (let [hooks     (get definition :hooks {})
        exit-key  (keyword (str "on-exit-" (name from-state)))
        enter-key (keyword (str "on-enter-" (name to-state)))
        any-key   :on-any-transition
        run!      (fn [stage-key]
                    (doseq [f (get hooks stage-key [])]
                      (try
                        (f instance audit-entry context)
                        (catch Exception e
                          (log/warn e "Lifecycle hook failed"
                                    {:stage       stage-key
                                     :instance-id (:id instance)})))))]
    (run! exit-key)
    (run! enter-key)
    (run! any-key)))

;; =============================================================================
;; Side-effect dispatch
;; =============================================================================

(defn- dispatch-side-effects!
  "Enqueue jobs for each declared side-effect key after a successful transition.

   Args:
     job-queue      - IJobQueue or nil
     side-effect-keys - Vector of keywords
     instance       - Updated WorkflowInstance
     audit-entry    - AuditEntry
     context        - Context map

   Returns:
     nil"
  [job-queue side-effect-keys instance audit-entry context]
  (when (and job-queue (seq side-effect-keys))
    (doseq [effect-key side-effect-keys]
      (try
        ;; We import the jobs port lazily to avoid a hard compile-time dependency.
        ;; The jobs library is an optional runtime companion.
        (let [jobs-ports-ns (the-ns 'wagoe.jobs.ports)
              enqueue-fn    (ns-resolve jobs-ports-ns 'enqueue-job!)
              job-ns        (the-ns 'wagoe.jobs.core.job)
              create-fn     (ns-resolve job-ns 'create-job)]
          (when (and enqueue-fn create-fn)
            (let [job (create-fn {:job-type effect-key
                                  :args     {:instance-id  (str (:id instance))
                                             :workflow-id  (name (:workflow-id instance))
                                             :from-state   (name (:from-state audit-entry))
                                             :to-state     (name (:to-state audit-entry))
                                             :context      (or context {})}}
                                 (UUID/randomUUID)
                                 (Instant/now))]
              (enqueue-fn job-queue :default job))))
        (catch Exception e
          (log/warn e "Failed to enqueue side-effect job"
                    {:effect effect-key :instance-id (:id instance)}))))))

;; =============================================================================
;; WorkflowService record
;; =============================================================================

(defrecord WorkflowService [store registry job-queue guard-registry]
  ports/IWorkflowEngine

  (start-workflow! [_ input]
    (let [workflow-id  (:workflow-id input)
          definition   (ports/get-workflow registry workflow-id)]
      (when (nil? definition)
        (throw (ex-info "Workflow definition not found"
                        {:type        :not-found
                         :workflow-id workflow-id
                         :message     (str "No workflow registered with id: " (name workflow-id))})))
      (let [now      (Instant/now)
            instance {:id            (UUID/randomUUID)
                      :workflow-id   workflow-id
                      :entity-type   (:entity-type input)
                      :entity-id     (:entity-id input)
                      :current-state (machine/initial-state definition)
                      :created-at    now
                      :updated-at    now
                      :metadata      (or (:metadata input) {})}]
        (log/info "Starting workflow instance"
                  {:workflow-id workflow-id
                   :entity-type (:entity-type input)
                   :entity-id   (:entity-id input)})
        (ports/save-instance! store instance))))

  (transition! [_ request]
    (let [instance-id (:instance-id request)
          transition  (:transition request)
          actor-id    (:actor-id request)
          actor-roles (:actor-roles request)
          context     (:context request)
          instance    (ports/find-instance store instance-id)]
      (when (nil? instance)
        (throw (ex-info "Workflow instance not found"
                        {:type        :not-found
                         :instance-id instance-id
                         :message     "Workflow instance does not exist"})))

      (let [workflow-id (:workflow-id instance)
            definition  (ports/get-workflow registry workflow-id)]
        (when (nil? definition)
          (throw (ex-info "Workflow definition not found for instance"
                          {:type        :not-found
                           :workflow-id workflow-id
                           :instance-id instance-id
                           :message     (str "Definition missing: " (name workflow-id))})))

        (let [check (transitions/can-transition?
                     definition
                     (:current-state instance)
                     transition
                     actor-roles
                     guard-registry
                     context)]

          (if-not (:allowed? check)
            (do
              (log/info "Workflow transition rejected"
                        {:instance-id instance-id
                         :transition  transition
                         :reason      (:reason check)})
              {:success? false
               :error    {:type    (or (:reason check) :forbidden)
                          :message (case (:reason check)
                                     :transition-not-found
                                     (str "Transition '" (name transition)
                                          "' is not allowed from state '"
                                          (name (:current-state instance)) "'")
                                     :insufficient-permissions
                                     (str "Actor does not have required permissions: "
                                          (mapv name (:required check)))
                                     :guard-rejected
                                     (str "Guard '" (name (:guard check)) "' rejected the transition")
                                     :guard-not-registered
                                     (str "Guard '" (name (:guard check)) "' is not registered")
                                     "Transition not allowed")}})

            (let [t-def        (:transition-def check)
                  to-state     (transitions/destination-state t-def)
                  side-fx-keys (transitions/side-effects t-def)
                  now          (Instant/now)
                  entry-id     (UUID/randomUUID)
                  audit-entry  (audit/create-audit-entry
                                entry-id instance t-def
                                actor-id actor-roles context now)
                  updated-instance (ports/update-instance-state! store instance-id to-state)]

              (ports/save-audit-entry! store audit-entry)
              (fire-hooks! definition (:current-state instance) to-state
                           updated-instance audit-entry context)
              (dispatch-side-effects! job-queue side-fx-keys updated-instance audit-entry context)

              (log/info "Workflow transition completed"
                        {:instance-id instance-id
                         :transition  transition
                         :from        (:current-state instance)
                         :to          to-state})

              {:success?    true
               :instance    updated-instance
               :audit-entry audit-entry}))))))

  (current-state [_ instance-id]
    (:current-state (ports/find-instance store instance-id)))

  (audit-log [_ instance-id]
    (ports/find-audit-log store instance-id))

  (available-transitions [_ instance-id actor-roles context]
    (let [instance   (ports/find-instance store instance-id)
          definition (when instance
                       (ports/get-workflow registry (:workflow-id instance)))]
      (if (nil? instance)
        []
        (transitions/available-transitions-with-status
         definition (:current-state instance)
         actor-roles guard-registry context))))

  (process-auto-transitions! [this workflow-id]
    (let [definition (ports/get-workflow registry workflow-id)]
      (if (nil? definition)
        {:processed 0 :attempted 0 :failed 0}
        (let [auto-ts (filter :auto? (:transitions definition))
              result  (atom {:processed 0 :attempted 0 :failed 0})]
          (doseq [t-def auto-ts]
            (let [instances (ports/list-instances
                             store
                             {:workflow-id   workflow-id
                              :current-state (:from t-def)
                              :limit         100})]
              (doseq [instance instances]
                (swap! result update :attempted inc)
                (let [t-name (or (:name t-def) (:to t-def))]
                  (try
                    (let [tr (ports/transition!
                              this
                              {:instance-id (:id instance)
                               :transition  t-name
                               :actor-roles [:system]
                               :context     (merge
                                             (or (:metadata instance) {})
                                             {:auto-transition? true})})]
                      (if (:success? tr)
                        (swap! result update :processed inc)
                        (swap! result update :failed inc)))
                    (catch Exception e
                      (log/warn e "Auto-transition failed"
                                {:instance-id (:id instance)
                                 :workflow-id workflow-id})
                      (swap! result update :failed inc)))))))
          @result)))))

(defn create-workflow-service
  "Create a WorkflowService.

   Args:
     store          - IWorkflowStore implementation
     registry       - IWorkflowRegistry implementation
     job-queue      - IJobQueue or nil (optional; required for side-effects)
     guard-registry - Map of guard-key -> (fn [ctx] bool?) or nil

   Returns:
     WorkflowService implementing IWorkflowEngine"
  ([store registry]
   (create-workflow-service store registry nil nil))
  ([store registry job-queue]
   (create-workflow-service store registry job-queue nil))
  ([store registry job-queue guard-registry]
   (->WorkflowService store registry job-queue (or guard-registry {}))))
