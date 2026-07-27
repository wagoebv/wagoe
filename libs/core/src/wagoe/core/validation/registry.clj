(ns wagoe.core.validation.registry
  "Pure helpers for validation rule definitions.

   Rule Format:
     {:rule-id        keyword           ; Unique identifier (e.g., :user.email/required)
      :description    string            ; Human-readable description
      :category       keyword           ; :schema | :business | :cross-field | :context
      :module         keyword           ; Module name (e.g., :user, :billing)
      :fields         [keyword]         ; Affected fields
      :error-code     keyword           ; Default error code
      :validator-fn   (fn [data] ...)   ; Actual validation function
      :dependencies   [rule-id]         ; Required rules (optional)
      :metadata       map}              ; Additional metadata (optional)

   This namespace holds only pure rule-shape validation (no mutable state). The
   stateful in-process rule registry — registration, lookup, and execution
   tracking — lives in the shell at wagoe.platform.shell.validation-registry,
   so the functional core stays free of process state (FC/IS).")

;; =============================================================================
;; Rule Validation (pure)
;; =============================================================================

(defn valid-rule?
  "Check if rule definition is valid.

   Args:
     rule: Rule definition map

   Returns:
     Boolean indicating validity"
  [rule]
  (and (map? rule)
       (keyword? (:rule-id rule))
       (string? (:description rule))
       (keyword? (:category rule))
       (keyword? (:module rule))
       (vector? (:fields rule))
       (keyword? (:error-code rule))
       (fn? (:validator-fn rule))))

(defn validate-rule
  "Validate rule definition and return errors.

   Args:
     rule: Rule definition map

   Returns:
     Vector of error strings (empty if valid)"
  [rule]
  (cond-> []
    (not (map? rule))
    (conj "Rule must be a map")

    (not (keyword? (:rule-id rule)))
    (conj ":rule-id must be a keyword")

    (not (string? (:description rule)))
    (conj ":description must be a string")

    (not (contains? #{:schema :business :cross-field :context} (:category rule)))
    (conj ":category must be :schema, :business, :cross-field, or :context")

    (not (keyword? (:module rule)))
    (conj ":module must be a keyword")

    (not (and (vector? (:fields rule)) (every? keyword? (:fields rule))))
    (conj ":fields must be a vector of keywords")

    (not (keyword? (:error-code rule)))
    (conj ":error-code must be a keyword")

    (not (fn? (:validator-fn rule)))
    (conj ":validator-fn must be a function")))

;; =============================================================================
;; Conflict Detection (pure — operates on a supplied collection)
;; =============================================================================

(defn find-duplicate-rule-ids
  "Find duplicate rule IDs in a collection of rules.

   Args:
     rules: Collection of rule definitions

   Returns:
     Set of duplicate rule-ids"
  [rules]
  (let [id-frequencies (frequencies (map :rule-id rules))
        duplicates (filter #(> (val %) 1) id-frequencies)]
    (set (map key duplicates))))

(defn find-conflicting-rules
  "Find rules with overlapping fields and categories that might conflict.

   Args:
     rules: Collection of rule definitions

   Returns:
     Vector of conflict maps {:rule-ids [...] :category kw :fields set :reason string}"
  [rules]
  (let [field-groups (group-by (fn [r] [(:category r) (set (:fields r))]) rules)]
    (->> field-groups
         (filter #(> (count (val %)) 1))
         (mapv (fn [[[category fields] group]]
                 {:rule-ids (mapv :rule-id group)
                  :category category
                  :fields   fields
                  :reason   "Multiple rules with same category and fields"})))))
