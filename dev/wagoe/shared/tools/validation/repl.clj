(ns wagoe.shared.tools.validation.repl
  "REPL helpers for working with validation: registry inspection, quick schema checks,
  timing utilities, and simple GraphViz export of rules.

  Pure helpers prefer returning data/strings; any I/O (writing files) is left to the caller."
  (:require [malli.core :as m]
            [wagoe.platform.shell.validation-registry :as reg]
            [wagoe.core.validation.snapshot :as snap]
            [clojure.string :as str]))

;; ----------------------------------------------------------------------------
;; Registry helpers
;; ----------------------------------------------------------------------------

(defn list-rules
  "List registered rules, optionally filtered by module or category.
  (list-rules) -> vector of rules
  (list-rules {:module :user})
  (list-rules {:category :schema})"
  ([] (reg/get-all-rules))
  ([{:keys [module category field]}]
   (cond
     module   (reg/get-rules-by-module module)
     category (reg/get-rules-by-category category)
     field    (reg/get-rules-for-field field)
     :else    (reg/get-all-rules))))

(defn stats
  "Registry statistics summary."
  []
  (reg/registry-stats))

(defn conflicts
  "Find potentially conflicting rules (same category and fields)."
  []
  (reg/conflicting-rules))

;; ----------------------------------------------------------------------------
;; Schema helpers (generic)
;; ----------------------------------------------------------------------------

(defn validate
  "m/validate wrapper. Returns true/false."
  [schema data]
  (m/validate schema data))

(defn explain
  "m/explain wrapper. Returns explain data structure."
  [schema data]
  (m/explain schema data))

;; ----------------------------------------------------------------------------
;; Timing / profiling helpers
;; ----------------------------------------------------------------------------

(defn time-call
  "Time a function call, returning {:ms elapsed-ms :result x}."
  [f & args]
  (let [t0 (System/nanoTime)
        res (apply f args)
        t1 (System/nanoTime)]
    {:ms (/ (- t1 t0) 1e6)
     :result res}))

;; ----------------------------------------------------------------------------
;; Snapshot helpers (pure: return strings)
;; ----------------------------------------------------------------------------

(defn snapshot->edn
  "Capture a value with optional metadata and return deterministic EDN string."
  ([value] (snapshot->edn value {}))
  ([value {:keys [seed meta]}]
   (-> (snap/capture value {:schema-version "1.0" :seed seed :meta meta})
       (snap/stable-serialize))))

(defn compare-snapshots
  "Compare two values as snapshots; return {:equal? bool :diff vector :pretty string}."
  [expected actual]
  (let [e (snap/capture expected {:schema-version "1.0"})
        a (snap/capture actual {:schema-version "1.0"})
        cmp (snap/compare-snapshots e a)]
    (assoc cmp :pretty (snap/format-diff cmp))))

;; ----------------------------------------------------------------------------
;; GraphViz (DOT) export (basic)
;; ----------------------------------------------------------------------------

(defn rules->dot
  "Produce a simple GraphViz DOT string of rules grouped by module.
  Options: {:modules #{:user :billing}} to filter."
  ([]
   (rules->dot {}))
  ([{:keys [modules]}]
   (let [rules (list-rules (when modules {:module (first modules)}))
         grouped (group-by :module (if modules
                                     (filter #(contains? (set modules) (:module %)) rules)
                                     rules))
         esc (fn [s] (-> s str (str/replace "\"" "\\\"")))]
     (str "digraph ValidationRules {\n"
          "  rankdir=LR;\n"
          (apply str
                 (for [[m rs] grouped]
                   (str "  subgraph cluster_" (name m) " {\n"
                        "    label=\"" (esc (name m)) "\";\n"
                        (apply str (for [{:keys [rule-id category]} rs]
                                     (str "    \"" (esc (name rule-id)) "\" [shape=box, label=\""
                                          (esc (str (name rule-id) "\n(" (name category) ")")) "\"];\n")))
                        "  }\n")))
          "}\n"))))
