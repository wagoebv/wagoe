(ns wagoe.devtools.core.schema-tools
  "Pure functions for Malli schema exploration: pretty-printing, diffing, and example generation.
   No I/O, no side effects — schema in, formatted strings or data out."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- child-key
  "Extract the keyword name from a Malli map child tuple."
  [child]
  (first child))

(defn- child-props
  "Extract optional properties map from a Malli map child tuple.
   Returns nil when the tuple has no properties (2-element form)."
  [child]
  (when (= 3 (count child))
    (second child)))

(defn- child-schema
  "Extract the schema from a Malli map child tuple.
   Handles both 2-element [key schema] and 3-element [key props schema] forms."
  [child]
  (if (= 3 (count child))
    (nth child 2)
    (second child)))

(defn- optional?
  "Return true when the child props mark the field as optional."
  [child]
  (true? (:optional (child-props child))))

(defn- schema-type-str
  "Return a human-readable type string for a parsed Malli schema.
   Includes schema properties (e.g. {:min 5}) when present so that
   constrained schemas ([:string {:min 5}]) differ from plain (:string)."
  [s]
  (let [t     (m/type s)
        props (m/properties s)]
    (case t
      :enum  (str "[:enum " (str/join " " (m/children s)) "]")
      :map   ":map"
      :maybe (str "[:maybe " (schema-type-str (first (m/children s))) "]")
      (if (seq props)
        (str "[" t " " props "]")
        (str t)))))

;; =============================================================================
;; format-schema-tree
;; =============================================================================

(defn- format-map-schema
  "Recursively render a parsed :map schema as indented lines."
  [s indent]
  (let [prefix (apply str (repeat indent " "))
        child-prefix (apply str (repeat (+ indent 2) " "))
        children (m/children s)]
    (str/join "\n"
              (into [(str prefix ":map")]
                    (map (fn [child]
                           (let [k     (child-key child)
                                 cs    (child-schema child)
                                 opt   (optional? child)
                                 ctype (m/type cs)
                                 opt-str (if opt " (optional)" "")]
                             (if (= :map ctype)
                               (str child-prefix (pr-str k) opt-str "\n"
                                    (format-map-schema cs (+ indent 4)))
                               (str child-prefix (pr-str k) " " (schema-type-str cs) opt-str))))
                         children)))))

(defn format-schema-tree
  "Render a Malli schema as a readable indented tree.
   For :map schemas, shows each field name, its type, and '(optional)' when applicable.
   Handles nested :map schemas with increased indentation."
  [schema]
  (let [s (m/schema schema)]
    (if (= :map (m/type s))
      (format-map-schema s 0)
      (schema-type-str s))))

;; =============================================================================
;; schema-diff
;; =============================================================================

(defn- field-signature
  "Return a comparable signature string for a schema field child tuple.
   Includes optionality and the full recursive form so nested changes
   and optional/required flips are detected."
  [child]
  (let [opt (if (optional? child) "optional " "required ")
        cs  (child-schema child)]
    (str opt (pr-str (m/form cs)))))

(defn- extract-field-types
  "Return a map of {field-key signature-string} for all top-level fields in a :map schema.
   Signatures include optionality and the full schema form for accurate diffing."
  [schema]
  (let [s (m/schema schema)]
    (into {}
          (map (fn [child]
                 [(child-key child) (field-signature child)])
               (m/children s)))))

(defn schema-diff
  "Compare two :map schemas.
   Returns {:added {key type} :removed {key type} :changed {key {:from type :to type}}}."
  [schema-a schema-b]
  (let [fields-a (extract-field-types schema-a)
        fields-b (extract-field-types schema-b)
        keys-a   (set (keys fields-a))
        keys-b   (set (keys fields-b))
        added    (->> (set/difference keys-b keys-a)
                      (into {} (map (fn [k] [k (get fields-b k)]))))
        removed  (->> (set/difference keys-a keys-b)
                      (into {} (map (fn [k] [k (get fields-a k)]))))
        changed  (->> (set/intersection keys-a keys-b)
                      (filter (fn [k] (not= (get fields-a k) (get fields-b k))))
                      (into {} (map (fn [k] [k {:from (get fields-a k)
                                                :to   (get fields-b k)}]))))]
    (cond-> {}
      (seq added)   (assoc :added added)
      (seq removed) (assoc :removed removed)
      (seq changed) (assoc :changed changed))))

;; =============================================================================
;; format-schema-diff
;; =============================================================================

(defn format-schema-diff
  "Format a diff map (as returned by schema-diff) as a readable string.
   Sections: 'Added:' (+), 'Removed:' (-), 'Changed:' (~).
   Returns 'Schemas are identical.' when diff is empty."
  [diff]
  (if (empty? diff)
    "Schemas are identical."
    (let [sections
          (cond-> []
            (:added diff)
            (into (into ["Added:"]
                        (map (fn [[k t]] (str "  + " (pr-str k) " " t))
                             (:added diff))))

            (:removed diff)
            (into (into ["Removed:"]
                        (map (fn [[k t]] (str "  - " (pr-str k) " " t))
                             (:removed diff))))

            (:changed diff)
            (into (into ["Changed:"]
                        (map (fn [[k {:keys [from to]}]]
                               (str "  ~ " (pr-str k) " " from " -> " to))
                             (:changed diff)))))]
      (str/join "\n" sections))))

;; =============================================================================
;; generate-example
;; =============================================================================

(defn generate-example
  "Generate a single example value from a Malli schema.
   Pass an integer seed for deterministic output."
  ([schema] (generate-example schema 1))
  ([schema seed]
   (mg/generate (m/schema schema) {:seed seed})))
