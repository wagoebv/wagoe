(ns wagoe.workflow.schema
  "Malli schemas for declarative workflow state machines.

   A workflow defines:
   - A set of named states
   - Transitions between states, with optional guards and permissions
   - Side effects triggered on transition

   A workflow instance tracks:
   - Which entity (type + id) is being managed
   - The current state of that entity
   - Metadata for guard / context evaluation"
  (:require [malli.core :as m]))

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(def TransitionDef
  "A single transition within a workflow definition."
  [:map
   [:from keyword?]
   [:to   keyword?]
   [:name {:optional true} keyword?]
   ;; Human-readable label for UIs (e.g. "Publish", "Archive").
   [:label {:optional true} :string]
   ;; When true the transition fires automatically via process-auto-transitions!.
   [:auto? {:optional true} boolean?]
   ;; Required permissions on the actor — any of these roles satisfies the check.
   ;; Empty / absent means unrestricted.
   [:required-permissions {:optional true} [:vector keyword?]]
   ;; Named guard function key; resolved at runtime via registry.
   [:guard {:optional true} keyword?]
   ;; Named side-effect keys; each invoked after a successful transition.
   [:side-effects {:optional true} [:vector keyword?]]])

(def StateConfig
  "Optional display metadata for a workflow state."
  [:map
   [:label {:optional true} :string]
   [:color {:optional true} keyword?]])

(def WorkflowDefinition
  "A complete workflow definition.

   Example:
     {:id             :order-workflow
      :initial-state  :pending
      :states         #{:pending :paid :shipped :delivered :cancelled}
      :state-config   {:pending {:label \"Pending\" :color :yellow}
                       :paid    {:label \"Paid\"    :color :green}}
      :transitions    [{:from :pending :to :paid  :label \"Mark as Paid\"
                        :required-permissions [:finance :admin]}
                       {:from :paid    :to :shipped}
                       {:from :shipped :to :delivered :auto? true :guard :tracking-delivered?}
                       {:from :pending :to :cancelled}]
      :hooks          {:on-enter-paid    [create-invoice-fn]
                       :on-any-transition [log-audit-fn]}}"
  [:map
   [:id            keyword?]
   [:initial-state keyword?]
   [:states        [:set keyword?]]
   [:transitions   [:vector TransitionDef]]
   [:description   {:optional true} :string]
   ;; Optional per-state display metadata (label, color).
   [:state-config  {:optional true} [:map-of keyword? StateConfig]]
   ;; Lifecycle hooks: keys are :on-enter-<state>, :on-exit-<state>,
   ;; or :on-any-transition.  Values are vectors of 3-arity fns
   ;; (fn [updated-instance audit-entry context] ...).
   [:hooks         {:optional true} [:map-of keyword? [:vector any?]]]])

;; =============================================================================
;; Workflow Instance
;; =============================================================================

(def WorkflowInstance
  "A running instance of a workflow, associated with a domain entity."
  [:map
   [:id            uuid?]
   [:workflow-id   keyword?]
   [:entity-type   keyword?]
   [:entity-id     uuid?]
   [:current-state keyword?]
   [:created-at    inst?]
   [:updated-at    inst?]
   [:metadata      {:optional true} [:map-of keyword? any?]]])

(def WorkflowInstanceInput
  "Input to create a new workflow instance."
  [:map
   [:workflow-id   keyword?]
   [:entity-type   keyword?]
   [:entity-id     uuid?]
   [:metadata      {:optional true} [:map-of keyword? any?]]])

;; =============================================================================
;; Transition Request / Result
;; =============================================================================

(def TransitionRequest
  "Request to perform a workflow transition."
  [:map
   [:instance-id  uuid?]
   [:transition   keyword?]
   ;; Actor performing the transition — used for permission checks and audit.
   [:actor-id     {:optional true} uuid?]
   [:actor-roles  {:optional true} [:vector keyword?]]
   ;; Arbitrary context passed to guards and side-effects.
   [:context      {:optional true} [:map-of keyword? any?]]])

(def TransitionResult
  "Result after performing a transition."
  [:map
   [:success?      :boolean]
   [:instance      {:optional true} WorkflowInstance]
   [:audit-entry   {:optional true} [:map-of keyword? any?]]
   [:error         {:optional true} [:map
                                     [:type    keyword?]
                                     [:message :string]]]])

;; =============================================================================
;; Audit Entry
;; =============================================================================

(def AuditEntry
  "An immutable record of a single workflow transition."
  [:map
   [:id            uuid?]
   [:instance-id   uuid?]
   [:workflow-id   keyword?]
   [:entity-type   keyword?]
   [:entity-id     uuid?]
   [:transition    keyword?]
   [:from-state    keyword?]
   [:to-state      keyword?]
   [:actor-id      {:optional true} uuid?]
   [:actor-roles   {:optional true} [:vector keyword?]]
   [:context       {:optional true} [:map-of keyword? any?]]
   [:occurred-at   inst?]])

;; =============================================================================
;; Validation helpers
;; =============================================================================

(def ^:private workflow-definition-validator (m/validator WorkflowDefinition))
(def ^:private workflow-definition-explainer (m/explainer WorkflowDefinition))
(def ^:private workflow-instance-validator (m/validator WorkflowInstance))
(def ^:private audit-entry-validator (m/validator AuditEntry))

(defn valid-workflow-definition?
  "Returns true when the given map is a valid WorkflowDefinition."
  [def]
  (workflow-definition-validator def))

(defn valid-workflow-instance?
  "Returns true when the given map is a valid WorkflowInstance."
  [instance]
  (workflow-instance-validator instance))

(defn valid-audit-entry?
  "Returns true when the given map is a valid AuditEntry."
  [entry]
  (audit-entry-validator entry))

(defn explain-workflow-definition
  "Returns human-readable validation errors for a WorkflowDefinition."
  [def]
  (workflow-definition-explainer def))
