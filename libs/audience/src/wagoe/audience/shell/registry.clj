(ns wagoe.audience.shell.registry
  "Load-time registry of audience segment definitions and the `defaudience` macro.

   The registry is mutable process state, so it lives in the shell — the audience
   core namespaces (compiler, composition, filter, ui) stay pure. Definitions are
   registered at namespace load via the `defaudience` macro (validated against the
   AudienceDefinition schema) and read at runtime by the audience service."
  (:require [wagoe.audience.schema :as schema]
            [malli.core :as m]))

;; =============================================================================
;; In-process registry
;; =============================================================================

(defonce ^:private registry (atom {}))

(def ^:private audience-definition-validator (m/validator schema/AudienceDefinition))
(def ^:private audience-definition-explainer (m/explainer schema/AudienceDefinition))

(defn register-audience!
  "Register an audience definition in the in-process registry.

   Validates the definition against AudienceDefinition schema before
   registration. Throws ex-info on invalid input.

   Args:
     definition - AudienceDefinition map (must contain :id keyword)

   Returns:
     definition"
  [definition]
  (when-not (audience-definition-validator definition)
    (throw (ex-info "Invalid audience definition"
                    {:type   :validation-error
                     :errors (audience-definition-explainer definition)
                     :id     (:id definition)})))
  (let [id (:id definition)]
    (swap! registry assoc id definition)
    definition))

(defn get-audience
  "Retrieve a registered audience definition by id.

   Args:
     id - keyword

   Returns:
     AudienceDefinition map, or nil if not registered"
  [id]
  (get @registry id))

(defn list-audiences
  "Return the ids of all registered audience definitions.

   Returns:
     Sequence of keywords"
  []
  (keys @registry))

(defn clear-registry!
  "Remove all registered audience definitions.
   Primarily used in tests to reset state between test runs.

   Returns:
     empty map"
  []
  (reset! registry {}))

;; =============================================================================
;; defaudience macro
;; =============================================================================

(defmacro defaudience
  "Define an audience segment and register it in the in-process registry.

   Creates a Var named `sym` bound to `definition-map` and registers it
   so it can be looked up by its :id at runtime.

   Example:
     (defaudience free-users
       {:id      :free-users
        :label   \"Free plan users\"
        :filters [{:type :plan :field :plan :op :eq :value \"free\"}]})

   The defined audience is immediately accessible via get-audience."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-audience! ~sym)
     ~sym))
