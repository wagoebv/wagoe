(ns wagoe.workflow.shell.registry
  "Load-time registry of workflow definitions.

   The registry is mutable process state, so it lives in the shell — the core
   (wagoe.workflow.core.machine) stays pure (state-machine introspection
   only). Definitions are registered at namespace load via the `defworkflow`
   macro and read at runtime by the workflow service/wiring.

   No business logic here — just the definition registry, the `defworkflow`
   convenience macro, and the IWorkflowRegistry adapter."
  (:require [wagoe.workflow.schema :as schema]
            [wagoe.workflow.ports :as ports]))

;; =============================================================================
;; Global definition registry (in-process)
;; =============================================================================

;; Module-level registry atom. Holds a map of workflow-id -> definition.
(defonce ^:private registry-atom (atom {}))

;; =============================================================================
;; defworkflow macro
;; =============================================================================

(defmacro defworkflow
  "Define and register a workflow.

   The body is a map literal that must satisfy WorkflowDefinition schema.
   After macro expansion the definition is automatically registered in the
   in-process registry so it is available via `get-workflow`.

   Example:

     (defworkflow order-workflow
       {:id             :order-workflow
        :initial-state  :pending
        :description    \"E-commerce order lifecycle\"
        :states         #{:pending :paid :shipped :delivered :cancelled}
        :transitions    [{:from :pending  :to :paid
                          :required-permissions [:finance :admin]}
                         {:from :paid     :to :shipped}
                         {:from :shipped  :to :delivered}
                         {:from :pending  :to :cancelled}
                         {:from :paid     :to :cancelled}]})

  The var `order-workflow` is bound to the definition map.
  The workflow is registered under :order-workflow."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-workflow! ~sym)
     ~sym))

;; =============================================================================
;; Registry operations
;; =============================================================================

(defn register-workflow!
  "Register a workflow definition in the in-process registry.

   Args:
     definition - WorkflowDefinition map

   Returns:
     :workflow-id keyword

   Throws:
     ex-info with :type :validation-error when definition is invalid"
  [definition]
  (when-not (schema/valid-workflow-definition? definition)
    (throw (ex-info "Invalid workflow definition"
                    {:type    :validation-error
                     :errors  (schema/explain-workflow-definition definition)
                     :message "Workflow definition does not satisfy schema"})))
  (swap! registry-atom assoc (:id definition) definition)
  (:id definition))

(defn get-workflow
  "Retrieve a registered workflow definition by id.

   Args:
     workflow-id - keyword

   Returns:
     WorkflowDefinition map or nil"
  [workflow-id]
  (get @registry-atom workflow-id))

(defn list-workflows
  "List the ids of all registered workflows.

   Returns:
     Vector of keyword ids"
  []
  (vec (keys @registry-atom)))

(defn unregister-workflow!
  "Remove a workflow definition from the in-process registry.
   Primarily useful for tests.

   Args:
     workflow-id - keyword

   Returns:
     true if removed, false if not found"
  [workflow-id]
  (let [existed? (contains? @registry-atom workflow-id)]
    (swap! registry-atom dissoc workflow-id)
    existed?))

(defn clear-registry!
  "Remove all workflow definitions from the in-process registry.
   Use only in tests."
  []
  (reset! registry-atom {}))

;; =============================================================================
;; IWorkflowRegistry record
;; =============================================================================

(defrecord WorkflowRegistry []
  ports/IWorkflowRegistry

  (register-workflow! [_ definition]
    (register-workflow! definition))

  (get-workflow [_ workflow-id]
    (get-workflow workflow-id))

  (list-workflows [_]
    (list-workflows)))

(defn create-workflow-registry
  "Create a WorkflowRegistry backed by the module-level atom.

   Returns:
     WorkflowRegistry implementing IWorkflowRegistry"
  []
  (->WorkflowRegistry))
