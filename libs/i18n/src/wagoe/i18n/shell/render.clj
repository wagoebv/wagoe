(ns wagoe.i18n.shell.render
  "Hiccup renderer that resolves [:t ...] markers to strings.

   SHELL FUNCTION: calls hiccup2.core/html (I/O boundary for HTML output).

   Marker syntax:
     [:t :key]                  → (t-fn :key)
     [:t :key {:param val}]     → (t-fn :key {:param val})
     [:t :key {:n 3} 3]         → (t-fn :key {:n 3} 3)
     [:t :key nil 3]            → (t-fn :key {} 3)

   Dev mode wraps each resolved marker in:
     [:span {:data-i18n (str key)} translated-string]"
  (:require [hiccup2.core :as h]))

;; =============================================================================
;; Marker detection
;; =============================================================================

(defn- i18n-marker?
  "Returns true if node is an i18n translation marker [:t :key ...]."
  [node]
  (and (vector? node)
       (= :t (first node))
       (keyword? (second node))))

;; =============================================================================
;; Marker resolution
;; =============================================================================

(defn- resolve-marker
  "Resolve a single [:t ...] marker to a string using t-fn.

   Args:
     node - vector of the form [:t key] or [:t key params] or [:t key params n]
     t-fn - translation function (key params? n?) → string
     dev? - if true, wrap in [:span {:data-i18n ...}]

   Returns:
     String (or dev-mode span vector)"
  [node t-fn dev?]
  (let [[_ key params n] node
        params (or params {})
        result (if n
                 (t-fn key params n)
                 (if (seq params)
                   (t-fn key params)
                   (t-fn key)))]
    (if dev?
      [:span {:data-i18n (str key)} result]
      result)))

;; =============================================================================
;; Tree transform (hand-rolled walk with structural sharing)
;; =============================================================================

;; A generic clojure.walk/postwalk rebuilds every node of the tree even when
;; nothing changes, which dominates render cost on large pages. This transform
;; is specialized for Hiccup data (vectors / seqs / maps; everything else is a
;; leaf) and returns the ORIGINAL node whenever no descendant changed, so
;; untouched subtrees (e.g. big marker-free table bodies) are shared, not copied.

(declare ^:private transform)

(defn- transform-vector
  "Transform each element of vector v. Returns v itself when nothing changed."
  [v t-fn dev?]
  (let [n (count v)]
    (loop [i   0
           acc nil] ; transient copy, created lazily on the first change
      (if (< i n)
        (let [old (nth v i)
              new (transform old t-fn dev?)]
          (cond
            (some? acc)          (recur (inc i) (conj! acc new))
            (identical? old new) (recur (inc i) nil)
            :else                (recur (inc i)
                                        (conj! (reduce conj! (transient []) (subvec v 0 i))
                                               new))))
        (if (some? acc)
          (with-meta (persistent! acc) (meta v))
          v)))))

(defn- transform-seq
  "Transform each element of seq s. Returns s itself when nothing changed."
  [s t-fn dev?]
  (loop [xs  (seq s)
         i   0
         acc nil] ; transient copy, created lazily on the first change
    (if xs
      (let [old (first xs)
            new (transform old t-fn dev?)]
        (cond
          (some? acc)          (recur (next xs) (inc i) (conj! acc new))
          (identical? old new) (recur (next xs) (inc i) nil)
          :else                (recur (next xs) (inc i)
                                      (conj! (reduce conj! (transient []) (take i s))
                                             new))))
      (if (some? acc)
        (or (seq (persistent! acc)) ())
        s))))

(defn- transform-map
  "Transform keys and values of map m. Returns m itself when nothing changed."
  [m t-fn dev?]
  (reduce-kv
   (fn [acc k v]
     (let [k' (transform k t-fn dev?)
           v' (transform v t-fn dev?)]
       (cond
         (and (identical? k k')
              (identical? v v')) acc
         (identical? k k')       (assoc acc k v')
         :else                   (-> acc (dissoc k) (assoc k' v')))))
   m
   m))

(defn- transform
  "Recursively resolve [:t ...] markers in a Hiccup node with structural sharing."
  [node t-fn dev?]
  (cond
    ;; Transform the marker's children first (postwalk semantics: nested
    ;; markers inside params resolve before the enclosing marker), then
    ;; resolve the marker itself.
    (i18n-marker? node) (resolve-marker (transform-vector node t-fn dev?) t-fn dev?)
    (vector? node)      (transform-vector node t-fn dev?)
    (seq? node)         (transform-seq node t-fn dev?)
    (map? node)         (transform-map node t-fn dev?)
    :else               node))

(defn resolve-markers
  "Walk a Hiccup tree and resolve all [:t ...] markers.

   Pure-ish function — does not call html, safe to use in core if t-fn is pure.

   Subtrees without markers are returned as-is (structural sharing), so only
   the path from the root to each marker is rebuilt.

   Args:
     hiccup - Hiccup data structure (nested vectors/maps)
     t-fn   - translation function (key params? n?) → string
     opts   - (optional) map with :dev? boolean

   Returns:
     Hiccup structure with markers replaced by strings (or dev-mode spans)"
  ([hiccup t-fn]
   (resolve-markers hiccup t-fn {}))
  ([hiccup t-fn {:keys [dev?]}]
   (transform hiccup t-fn dev?)))

;; =============================================================================
;; Render
;; =============================================================================

(defn render
  "Resolve markers in a Hiccup tree and return an HTML string.

   Calls resolve-markers then hiccup2.core/html.

   Args:
     hiccup - Hiccup data structure
     t-fn   - translation function (key params? n?) → string
     opts   - (optional) map with :dev? boolean

   Returns:
     HTML string"
  ([hiccup t-fn]
   (render hiccup t-fn {}))
  ([hiccup t-fn opts]
   (if (string? hiccup)
     hiccup
     (let [resolved  (resolve-markers hiccup t-fn opts)
           full-page? (and (vector? resolved) (= :html (first resolved)))
           html-str  (str (h/html resolved))]
       (if full-page?
         (str "<!DOCTYPE html>" html-str)
         html-str)))))
