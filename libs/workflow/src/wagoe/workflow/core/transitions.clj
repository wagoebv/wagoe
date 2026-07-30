(ns wagoe.workflow.core.transitions
  "Pure business logic for workflow transition decisions.

   All functions are pure — no I/O, no side effects.
   Guards and side-effect dispatch rely on data maps, not concrete impls.")

;; =============================================================================
;; Transition lookup
;; =============================================================================

(defn find-transition-def
  "Find the transition definition matching a from-state and transition name.

   Args:
     definition  - WorkflowDefinition map
     from-state  - keyword  (current state)
     transition  - keyword  (transition name, derived from :to when :name absent)

   Returns:
     TransitionDef map or nil"
  [definition from-state transition]
  (some (fn [t]
          (when (and (= (:from t) from-state)
                     (= (or (:name t) (:to t)) transition))
            t))
        (:transitions definition)))

(defn allowed-transitions
  "Return all transition definitions reachable from the current state.

   Args:
     definition    - WorkflowDefinition map
     current-state - keyword

   Returns:
     Vector of TransitionDef maps"
  [definition current-state]
  (filterv #(= (:from %) current-state) (:transitions definition)))

(defn allowed-transition-names
  "Return the names of all transitions reachable from the current state.

   Args:
     definition    - WorkflowDefinition map
     current-state - keyword

   Returns:
     Vector of keywords (transition :name or :to when :name absent)"
  [definition current-state]
  (mapv (fn [t] (or (:name t) (:to t)))
        (allowed-transitions definition current-state)))

;; =============================================================================
;; Availability check
;; =============================================================================

(defn transition-exists?
  "Check whether the given transition is defined from the current state.

   Args:
     definition    - WorkflowDefinition map
     current-state - keyword
     transition    - keyword

   Returns:
     true / false"
  [definition current-state transition]
  (some? (find-transition-def definition current-state transition)))

;; =============================================================================
;; Permission check
;; =============================================================================

(defn check-permissions
  "Verify that the actor has at least one of the required roles.

   Args:
     transition-def - TransitionDef map
     actor-roles    - Collection of keywords the actor holds (may be nil/empty)

   Returns:
     {:allowed? true}
     or
     {:allowed? false
      :reason   :insufficient-permissions
      :required [:finance :admin]
      :provided [...]}"
  [transition-def actor-roles]
  (let [required (:required-permissions transition-def)]
    (if (or (empty? required)
            (some (set required) (or actor-roles [])))
      {:allowed? true}
      {:allowed?  false
       :reason    :insufficient-permissions
       :required  required
       :provided  (vec (or actor-roles []))})))

;; =============================================================================
;; Guard evaluation
;; =============================================================================

(defn evaluate-guard
  "Evaluate a named guard function against the transition context.

   The guard registry is a plain map of keyword -> (fn [context] boolean?).
   Guards are optional; absence means the transition is always allowed.

   Args:
     transition-def - TransitionDef map
     guard-registry - Map of guard-key -> guard-fn (may be nil/empty)
     context        - Arbitrary context map passed through the transition

   Returns:
     {:allowed? true}
     or
     {:allowed? false
      :reason   :guard-rejected
      :guard    :guard-key}"
  [transition-def guard-registry context]
  (let [guard-key (:guard transition-def)]
    (if (nil? guard-key)
      {:allowed? true}
      (let [guard-fn (get guard-registry guard-key)]
        (if (nil? guard-fn)
          ;; Guard key defined but no function registered — fail safe
          {:allowed? false
           :reason   :guard-not-registered
           :guard    guard-key}
          (if (guard-fn context)
            {:allowed? true}
            {:allowed? false
             :reason   :guard-rejected
             :guard    guard-key}))))))

;; =============================================================================
;; Composite can-transition? check
;; =============================================================================

(defn can-transition?
  "Full pre-flight check for a transition request.

   Runs, in order:
     1. Existence — is the transition defined from the current state?
     2. Permissions — does the actor have a required role?
     3. Guard       — does the guard function approve the context?

   Args:
     definition     - WorkflowDefinition map
     current-state  - keyword
     transition     - keyword
     actor-roles    - Collection of keywords (may be nil)
     guard-registry - Map of guard-key -> (fn [ctx] bool?) (may be nil)
     context        - Arbitrary context map (may be nil)

   Returns:
     {:allowed? true :transition-def <TransitionDef>}
     or
     {:allowed? false :reason <keyword> ...}"
  [definition current-state transition actor-roles guard-registry context]
  (let [t-def (find-transition-def definition current-state transition)]
    (cond
      (nil? t-def)
      {:allowed? false
       :reason   :transition-not-found
       :from     current-state
       :transition transition}

      :else
      (let [perm-result (check-permissions t-def actor-roles)]
        (if-not (:allowed? perm-result)
          perm-result
          (let [guard-result (evaluate-guard t-def guard-registry context)]
            (if-not (:allowed? guard-result)
              guard-result
              {:allowed?        true
               :transition-def  t-def})))))))

;; =============================================================================
;; Destination state
;; =============================================================================

(defn destination-state
  "Return the target state for a transition definition.

   Args:
     transition-def - TransitionDef map

   Returns:
     keyword (:to value)"
  [transition-def]
  (:to transition-def))

(defn side-effects
  "Return the list of side-effect keys for a transition definition.

   Args:
     transition-def - TransitionDef map

   Returns:
     Vector of keywords (may be empty)"
  [transition-def]
  (or (:side-effects transition-def) []))

;; =============================================================================
;; Available-transitions with enabled/disabled status
;; =============================================================================

(defn available-transitions-with-status
  "Return all transitions reachable from current-state, each annotated with
   :enabled? and, when disabled, a :reason keyword.

   Runs the full can-transition? check (existence ✓, permissions, guard) for
   every reachable transition and returns a summary vector suitable for driving
   UI buttons or API responses.

   Args:
     definition     - WorkflowDefinition map
     current-state  - keyword
     actor-roles    - Collection of keywords (may be nil)
     guard-registry - Map of guard-key -> (fn [ctx] bool?) (may be nil)
     context        - Context map (may be nil)

   Returns:
     Vector of maps:
       {:id       keyword    ; transition identifier (name or :to)
        :to       keyword    ; target state
        :label    string?    ; optional, from :label on the transition def
        :enabled? boolean
        :reason   keyword?}  ; only present when :enabled? is false"
  [definition current-state actor-roles guard-registry context]
  (mapv
   (fn [t]
     (let [t-name (or (:name t) (:to t))
           check  (can-transition?
                   definition current-state t-name
                   actor-roles guard-registry context)]
       (cond-> {:id       t-name
                :to       (:to t)
                :enabled? (:allowed? check)}
         (:label t)          (assoc :label (:label t))
         (not (:allowed? check)) (assoc :reason (:reason check)))))
   (allowed-transitions definition current-state)))
