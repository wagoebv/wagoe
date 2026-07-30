(ns wagoe.workflow.core.audit
  "Pure constructors for workflow audit trail entries.

   No I/O — entries are plain data maps. Persistence lives in the shell layer.")

;; =============================================================================
;; Audit entry creation
;; =============================================================================

(defn create-audit-entry
  "Construct an immutable AuditEntry from a completed transition.

   Args:
     entry-id       - UUID (provided by caller — no randomness here)
     instance       - WorkflowInstance map (pre-transition state)
     transition-def - TransitionDef map (the executed transition)
     actor-id       - UUID or nil
     actor-roles    - Vector of keywords or nil
     context        - Context map or nil
     now            - java.time.Instant (provided by caller)

   Returns:
     AuditEntry map"
  [entry-id instance transition-def actor-id actor-roles context now]
  (cond-> {:id          entry-id
           :instance-id (:id instance)
           :workflow-id (:workflow-id instance)
           :entity-type (:entity-type instance)
           :entity-id   (:entity-id instance)
           :transition  (or (:name transition-def) (:to transition-def))
           :from-state  (:current-state instance)
           :to-state    (:to transition-def)
           :occurred-at now}
    actor-id    (assoc :actor-id actor-id)
    actor-roles (assoc :actor-roles (vec actor-roles))
    context     (assoc :context context)))

;; =============================================================================
;; Audit summary helpers
;; =============================================================================

(defn audit-summary
  "Return a concise summary map suitable for logging or display.

   Args:
     entry - AuditEntry map

   Returns:
     Summary map with key fields"
  [entry]
  {:id          (:id entry)
   :instance-id (:instance-id entry)
   :transition  (:transition entry)
   :from-state  (:from-state entry)
   :to-state    (:to-state entry)
   :actor-id    (:actor-id entry)
   :occurred-at (:occurred-at entry)})
