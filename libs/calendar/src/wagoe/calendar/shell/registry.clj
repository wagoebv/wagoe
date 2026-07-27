(ns wagoe.calendar.shell.registry
  "Load-time registry of event type definitions.

   The registry is mutable process state, so it lives in the shell — the core
   (wagoe.calendar.core.event) stays pure (helper functions only).
   Definitions are registered at namespace load via the `defevent` macro and
   read at runtime via `get-event-type` / `list-event-types`."
  (:require [clojure.string]))

;; =============================================================================
;; Global definition registry (in-process)
;; =============================================================================

(defonce ^:private registry-atom (atom {}))

;; =============================================================================
;; Registry operations
;; =============================================================================

(defn register-event-type!
  "Register an event type definition in the in-process registry.

   Args:
     definition - EventDef map

   Returns the definition map."
  [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-event-type
  "Look up an event type definition by id.

   Returns the definition map or nil if not found."
  [id]
  (get @registry-atom id))

(defn list-event-types
  "Return a vector of all registered event type ids."
  []
  (vec (keys @registry-atom)))

(defn clear-registry!
  "Reset the registry to an empty map.

   Use in tests to avoid inter-test pollution."
  []
  (reset! registry-atom {}))

;; =============================================================================
;; defevent macro
;; =============================================================================

(defmacro defevent
  "Define and register an event type.

   The body is a map literal that must satisfy EventDef schema.
   After macro expansion the definition is automatically registered in the
   in-process registry so it is available via `get-event-type`.

   Example:

     (defevent appointment-event
       {:id    :appointment
        :label \"Appointment\"
        :schema [:map
                 [:patient-id :uuid]
                 [:room       :string]]})

   The var `appointment-event` is bound to the definition map.
   The event type is registered under :appointment."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-event-type! ~sym)
     ~sym))
