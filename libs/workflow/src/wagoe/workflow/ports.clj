(ns wagoe.workflow.ports
  "Port definitions for the wagoe-workflow library.

   Three protocols cover the full lifecycle:

   IWorkflowStore    — persistence of instances and audit log
   IWorkflowEngine   — high-level transition orchestration
   IWorkflowRegistry — in-process registry of workflow definitions")

;; =============================================================================
;; IWorkflowStore  — persistence
;; =============================================================================

(defprotocol IWorkflowStore
  "Protocol for persisting workflow instances and their audit trail."

  (save-instance! [this instance]
    "Persist a new workflow instance.

     Args:
       instance - WorkflowInstance map (id already set by caller)

     Returns:
       Saved instance with server-side fields (created-at, updated-at)")

  (find-instance [this instance-id]
    "Find a workflow instance by its id.

     Args:
       instance-id - UUID

     Returns:
       WorkflowInstance map or nil")

  (find-instance-by-entity [this entity-type entity-id]
    "Find the active workflow instance for a domain entity.

     Args:
       entity-type - keyword  (e.g. :order)
       entity-id   - UUID

     Returns:
       WorkflowInstance map or nil")

  (update-instance-state! [this instance-id new-state]
    "Update the current-state of an existing instance.

     Args:
       instance-id - UUID
       new-state   - keyword (must be a valid state in the workflow definition)

     Returns:
       Updated WorkflowInstance")

  (save-audit-entry! [this entry]
    "Persist an immutable audit entry for a completed transition.

     Args:
       entry - AuditEntry map (id already set by caller)

     Returns:
       Saved AuditEntry")

  (find-audit-log [this instance-id]
    "Return the full audit log for an instance, ordered chronologically.

     Args:
       instance-id - UUID

     Returns:
       Vector of AuditEntry maps")

  (list-instances [this opts]
    "List workflow instances with optional filtering and pagination.

     Args:
       opts - map with optional keys:
               :workflow-id   - filter by workflow keyword
               :entity-type   - filter by entity type keyword
               :current-state - filter by current state keyword
               :limit         - max results (default 50)
               :offset        - pagination offset (default 0)

     Returns:
       Vector of WorkflowInstance maps, newest first"))

;; =============================================================================
;; IWorkflowEngine  — orchestration
;; =============================================================================

(defprotocol IWorkflowEngine
  "Protocol for executing workflow transitions."

  (start-workflow! [this input]
    "Create a new workflow instance in its initial state.

     Args:
       input - WorkflowInstanceInput map:
               {:workflow-id :order-workflow
                :entity-type :order
                :entity-id   #uuid \"...\"
                :metadata    {}}

     Returns:
       Created WorkflowInstance")

  (transition! [this request]
    "Execute a workflow transition.

     Args:
       request - TransitionRequest map:
                 {:instance-id  #uuid \"...\"
                  :transition   :ship
                  :actor-id     #uuid \"...\"
                  :actor-roles  [:admin]
                  :context      {}}

     Returns:
       TransitionResult map:
         {:success?    true
          :instance    <updated WorkflowInstance>
          :audit-entry <AuditEntry>}

       On failure:
         {:success? false
          :error    {:type :forbidden :message \"...\"}}")

  (current-state [this instance-id]
    "Return the current state keyword for a workflow instance.

     Args:
       instance-id - UUID

     Returns:
       Keyword or nil")

  (audit-log [this instance-id]
    "Return the audit log for a workflow instance.

     Args:
       instance-id - UUID

     Returns:
       Vector of AuditEntry maps, oldest first")

  (available-transitions [this instance-id actor-roles context]
    "Return all transitions reachable from the current state, annotated with
     :enabled? and, when blocked, a :reason keyword.

     Runs full permission + guard checks for each reachable transition so UIs
     can render buttons as enabled or disabled accordingly.

     Args:
       instance-id - UUID
       actor-roles - Collection of keywords (may be nil)
       context     - Context map passed to guards (may be nil)

     Returns:
       Vector of maps:
         {:id keyword :to keyword :label string? :enabled? boolean :reason keyword?}
       Empty vector when instance not found.")

  (process-auto-transitions! [this workflow-id]
    "Process all auto-transitions (:auto? true) for a workflow.

     Finds every instance currently in a state that has an auto-transition
     and attempts to execute that transition.  Intended to be called from a
     scheduled job (e.g. wagoe-jobs cron worker).

     Args:
       workflow-id - keyword

     Returns:
       {:processed int   ; transitions that succeeded
        :attempted int   ; total instances considered
        :failed    int}  ; transitions that were rejected or threw"))

;; =============================================================================
;; IWorkflowRegistry  — definition management
;; =============================================================================

(defprotocol IWorkflowRegistry
  "Protocol for managing workflow definitions at runtime."

  (register-workflow! [this definition]
    "Register a workflow definition.

     Args:
       definition - WorkflowDefinition map

     Returns:
       :workflow-id keyword")

  (get-workflow [this workflow-id]
    "Retrieve a registered workflow definition.

     Args:
       workflow-id - keyword

     Returns:
       WorkflowDefinition map or nil")

  (list-workflows [this]
    "List all registered workflow ids.

     Returns:
       Vector of keyword ids"))
