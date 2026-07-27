(ns wagoe.audience.core.composition
  "AND/OR/NOT composition of audience segment result sets.
   FC/IS rule: pure functions only — no I/O, no side effects.

   Well-formedness (unknown operator, NOT-inside-OR, circular/unknown/invalid
   segment refs) is checked up front by `explain-composition`; the shell
   validates a composition tree before evaluating it and raises the typed error
   at the HTTP boundary. The evaluation functions below therefore assume a valid
   tree and never throw — their error branches fail safe (an empty result set,
   and the cycle guard still terminates)."
  (:require [clojure.set :as set]))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare compose-results)
(declare resolve-and-compose*)

;; =============================================================================
;; Internal helpers — compose-results
;; =============================================================================

(defn- resolve-node
  "Return the {:user-ids #{...}} for a single child node.
   Handles plain result maps, :not wrappers, and nested composition trees."
  [node]
  (cond
    ;; Negation wrapper — resolve inner node, return :not key so AND can subtract
    (:not node)
    {:not (:user-ids (resolve-node (:not node)))}

    ;; Leaf result already carries :user-ids
    (:user-ids node)
    node

    ;; Nested composition (contains :and / :or)
    :else
    {:user-ids (compose-results node)}))

;; =============================================================================
;; Public API — compose-results
;; =============================================================================

(defn compose-results
  "Evaluate a composition tree whose leaves are {:user-ids #{...}} maps.

   Supported operators (as map keys):
     :and  — intersection of all positive children; subtract :not children
     :or   — union of all children

   Returns: a set of user IDs."
  [tree]
  (cond
    (:and tree)
    (let [children  (map resolve-node (:and tree))
          positives (filter :user-ids children)
          negations (filter :not children)
          base      (if (seq positives)
                      (reduce set/intersection (map :user-ids positives))
                      #{})
          excluded  (apply set/union (map :not negations))]
      (set/difference base excluded))

    (:or tree)
    ;; NOT-inside-OR is rejected by explain-composition; here a stray :not child
    ;; simply contributes no :user-ids rather than throwing.
    (let [children (map resolve-node (:or tree))]
      (apply set/union (keep :user-ids children)))

    :else #{}))

;; =============================================================================
;; Internal helpers — ref resolution
;; =============================================================================

(defn- resolve-node-with-lookup
  "Resolve a single child node, substituting {:ref id} leaves via lookup.
   visited  - set of segment ids already on the resolution stack (cycle guard)
   lookup   - (fn [id] -> segment-map-or-nil)"
  [node lookup visited]
  (cond
    (:ref node)
    (let [id (:ref node)]
      ;; The cycle guard still terminates the recursion; unknown/invalid refs
      ;; and cycles are rejected up front by explain-composition, so here they
      ;; resolve to an empty set rather than throwing.
      (if (contains? visited id)
        {:user-ids #{}}
        (let [segment (lookup id)]
          (cond
            (nil? segment)      {:user-ids #{}}
            (:user-ids segment) segment
            (:compose segment)  {:user-ids (resolve-and-compose* (:compose segment) lookup (conj visited id))}
            :else               {:user-ids #{}}))))

    (:not node)
    {:not (:user-ids (resolve-node-with-lookup (:not node) lookup visited))}

    (:user-ids node)
    node

    ;; Nested composition
    :else
    {:user-ids (resolve-and-compose* node lookup visited)}))

;; =============================================================================
;; Public API — resolve-and-compose
;; =============================================================================

(defn resolve-and-compose*
  "Internal recursive implementation.
   tree     - composition map with possible {:ref ...} leaves
   lookup   - (fn [id] -> segment-map-or-nil)
   visited  - set of already-resolving ids"
  [tree lookup visited]
  (cond
    (:and tree)
    (let [children  (map #(resolve-node-with-lookup % lookup visited) (:and tree))
          positives (filter :user-ids children)
          negations (filter :not children)
          base      (if (seq positives)
                      (reduce set/intersection (map :user-ids positives))
                      #{})
          excluded  (apply set/union (map :not negations))]
      (set/difference base excluded))

    (:or tree)
    (let [children (map #(resolve-node-with-lookup % lookup visited) (:or tree))]
      (apply set/union (keep :user-ids children)))

    :else #{}))

(defn resolve-and-compose
  "Evaluate a composition tree that may contain {:ref :keyword} leaves.

   Refs are resolved via lookup:
     (fn [id] -> {:user-ids #{...}} | {:compose {...}} | nil)

   Assumes the tree has been validated by `explain-composition` (the shell does
   this and raises on error). Returns a set of user IDs; malformed input fails
   safe to an empty set and always terminates.

   Returns: a set of user IDs."
  [tree lookup]
  (resolve-and-compose* tree lookup #{}))

;; =============================================================================
;; Validation (pure) — the shell calls this before evaluation and raises the
;; typed error at the HTTP boundary. Mirrors the resolution traversal so the
;; evaluation functions above can stay throw-free.
;; =============================================================================

(declare explain-node)

(defn- explain-tree
  "First {:error {...}} in a composition tree, or nil when well-formed."
  [tree lookup visited]
  (cond
    (:and tree)
    (some #(explain-node % lookup visited) (:and tree))

    (:or tree)
    (or (when (some :not (:or tree))
          {:error {:type    :composition-error
                   :message "NOT is not supported directly inside OR — wrap in AND"
                   :tree    tree}})
        (some #(explain-node % lookup visited) (:or tree)))

    :else
    {:error {:type :composition-error :message "Unknown composition operator" :tree tree}}))

(defn- explain-node
  "First {:error {...}} for a single child node, or nil when well-formed."
  [node lookup visited]
  (cond
    (:ref node)
    (let [id (:ref node)]
      (if (contains? visited id)
        {:error {:type :circular-reference :id id :message "Circular segment reference"}}
        (let [segment (lookup id)]
          (cond
            (nil? segment)      {:error {:type :unknown-segment-ref :id id :message "Unknown segment ref"}}
            (:user-ids segment) nil
            (:compose segment)  (explain-tree (:compose segment) lookup (conj visited id))
            :else               {:error {:type :invalid-segment :id id
                                         :message "Segment has neither :user-ids nor :compose"}}))))

    (:not node)      (explain-node (:not node) lookup visited)
    (:user-ids node) nil
    :else            (explain-tree node lookup visited)))

(defn explain-composition
  "Return {:error {...}} for the first structural or reference problem in the
   composition tree (unknown operator, NOT-inside-OR, unknown/circular/invalid
   segment ref), or nil when the tree is well-formed and every ref resolves.
   Pure; `lookup` is (fn [id] -> segment-map-or-nil)."
  [tree lookup]
  (explain-tree tree lookup #{}))
