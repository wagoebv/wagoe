(ns wagoe.workflow.core.machine
  "Pure workflow state-machine introspection.

   Helpers for reading the shape of a WorkflowDefinition map (states, initial
   state, transitions). No mutable state and no I/O.

   The definition registry and the `defworkflow` macro live in the shell
   (wagoe.workflow.shell.registry) — this namespace holds no mutable state.")

;; =============================================================================
;; Introspection helpers (pure)
;; =============================================================================

(defn states
  "Return the set of states defined in the workflow.

   Args:
     definition - WorkflowDefinition map

   Returns:
     Set of keyword states"
  [definition]
  (:states definition))

(defn initial-state
  "Return the initial state of the workflow.

   Args:
     definition - WorkflowDefinition map

   Returns:
     Keyword"
  [definition]
  (:initial-state definition))

(defn transitions
  "Return all transition definitions for the workflow.

   Args:
     definition - WorkflowDefinition map

   Returns:
     Vector of TransitionDef maps"
  [definition]
  (:transitions definition))
