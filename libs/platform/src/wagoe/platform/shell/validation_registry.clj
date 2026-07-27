(ns wagoe.platform.shell.validation-registry
  "Stateful in-process registry of validation rules.

   Holds mutable process state (the rule registry and execution-tracking atoms),
   so it lives in the shell — the functional core
   (wagoe.core.validation.registry) keeps only the pure rule-shape and
   conflict helpers. Provides registration, lookup, and coverage tracking for
   validation rules across modules.

   Rule format: see wagoe.core.validation.registry."
  (:require [wagoe.core.validation.registry :as rules]))

;; =============================================================================
;; Registry State
;; =============================================================================

(defonce ^:private registry
  ;; Global validation rule registry. Structure: {rule-id rule-definition}
  (atom {}))

(defonce ^:private execution-tracking
  ;; Rule execution counts for coverage reporting. Structure: {rule-id count}
  (atom {}))

;; =============================================================================
;; Rule Registration
;; =============================================================================

(defn register-rule!
  "Register a validation rule in the global registry.

   Throws ex-info if :rule-id is missing or already registered.
   Returns the registered rule definition."
  [rule]
  (when-not (:rule-id rule)
    (throw (ex-info "Rule must have :rule-id" {:rule rule})))
  (let [rule-id (:rule-id rule)]
    (when (get @registry rule-id)
      (throw (ex-info (str "Rule already registered: " rule-id)
                      {:rule-id rule-id})))
    (swap! registry assoc rule-id rule)
    rule))

(defn register-rules!
  "Register multiple validation rules. Returns a vector of registered rules."
  [rule-defs]
  (mapv register-rule! rule-defs))

(defn unregister-rule!
  "Remove a rule from the registry (primarily for testing).
   Returns the removed rule definition or nil."
  [rule-id]
  (let [rule (get @registry rule-id)]
    (swap! registry dissoc rule-id)
    (swap! execution-tracking dissoc rule-id)
    rule))

(defn clear-registry!
  "Clear all rules and execution tracking (primarily for testing)."
  []
  (reset! registry {})
  (reset! execution-tracking {})
  {})

;; =============================================================================
;; Rule Lookup
;; =============================================================================

(defn get-rule
  "Retrieve a rule definition by id, or nil."
  [rule-id]
  (get @registry rule-id))

(defn get-all-rules
  "Get all registered rules as a vector."
  []
  (vec (vals @registry)))

(defn get-rules-by-module
  "Get all rules for a specific module keyword."
  [module]
  (filterv #(= (:module %) module) (get-all-rules)))

(defn get-rules-by-category
  "Get all rules of a specific category keyword."
  [category]
  (filterv #(= (:category %) category) (get-all-rules)))

(defn get-rules-for-field
  "Get all rules that validate a specific field keyword."
  [field]
  (filterv #(some #{field} (:fields %)) (get-all-rules)))

;; =============================================================================
;; Rule Execution Tracking (for coverage)
;; =============================================================================

(defn track-rule-execution!
  "Record that a rule was executed. Returns the updated tracking map."
  [rule-id]
  (swap! execution-tracking update rule-id (fnil inc 0)))

(defn get-execution-count
  "Get execution count for a rule (0 if never executed)."
  [rule-id]
  (get @execution-tracking rule-id 0))

(defn get-execution-stats
  "Get execution statistics for all rules:
   {:total-rules N :executed-rules N :coverage-percent F :by-rule {...}}."
  []
  (let [total-rules    (count @registry)
        executed-rules (count (filter pos? (vals @execution-tracking)))
        coverage       (if (zero? total-rules)
                         0.0
                         (* 100.0 (/ executed-rules total-rules)))]
    {:total-rules      total-rules
     :executed-rules   executed-rules
     :coverage-percent coverage
     :by-rule          @execution-tracking}))

(defn reset-execution-tracking!
  "Reset all execution tracking (for testing/benchmarking)."
  []
  (reset! execution-tracking {}))

;; =============================================================================
;; Registry Info
;; =============================================================================

(defn conflicting-rules
  "Return conflict maps for the currently-registered rules
   (delegates to the pure core/find-conflicting-rules)."
  []
  (rules/find-conflicting-rules (get-all-rules)))

(defn registry-stats
  "Get statistics about the rule registry (counts by module, category, etc.)."
  []
  (let [all (get-all-rules)]
    {:total-rules       (count all)
     :by-module         (frequencies (map :module all))
     :by-category       (frequencies (map :category all))
     :fields-count      (count (distinct (mapcat :fields all)))
     :error-codes-count (count (distinct (map :error-code all)))}))

(defn registry-ready?
  "True when the registry has at least one rule registered."
  []
  (pos? (count @registry)))
