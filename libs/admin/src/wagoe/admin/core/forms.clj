(ns wagoe.admin.core.forms
  "What an entity form shows, which of a form's errors it cannot show next
   to a field, and the child rows a parent is created with (BOU-570)."
  (:require [wagoe.admin.core.schema-introspection :as introspection]
            [clojure.string :as str]))

(defn shown-fields
  "The fields the entity form renders as a visible input, its many-to-many
   keys included."
  [entity-config]
  (-> #{}
      (into (remove #(= :hidden (get-in entity-config [:fields % :widget])))
            (:editable-fields entity-config))
      (into (map :key) (:many-to-many entity-config))))

(defn off-form-errors
  "The entries of `errors` ({field [message]}) on fields the form does not
   show, so no input can carry them."
  [entity-config errors]
  (let [shown (shown-fields entity-config)]
    (into {} (remove (fn [[field _]] (contains? shown field))) errors)))

(def remove-file-param
  "Names each file field whose stored file the form removes."
  "__remove")

(defn no-file?
  "A file input left empty: the browser still sends the part, with no name."
  [v]
  (if (map? v) (str/blank? (:filename v)) (and (string? v) (str/blank? v))))

;; =============================================================================
;; Creating a parent with its children
;; =============================================================================

(defn- kebab-keyword [k]
  (keyword (str/replace (name k) "_" "-")))

(defn nested-relationships
  "The has-many entries of `entity-config` whose children are created in the
   parent's create form: editable, with a :min, and a child the admin can
   create from a form. Each gets the child's config as :entity-config, its
   foreign key as :fk, and as :fields the child's editable fields without it.
   `entity-configs` maps entity name to config."
  [entity-config entity-configs]
  (vec (for [rel   (:has-many entity-config)
             :let  [child (get entity-configs (:entity rel))
                    fk    (some-> (:foreign-key rel) kebab-keyword)]
             :when (and (:editable rel) (pos-int? (:min rel)) child fk
                        (not (:create-redirect-url child))
                        (not (:split-table-update child))
                        (not (false? (get-in child [:permissions :create]))))]
         (assoc rel
                :entity-config child
                :fk fk
                :fields (vec (remove #{fk} (:editable-fields child)))))))

;; Nine digits at most, so every index reads as a long.
(def ^:private child-param-pattern #"__child\.([^.]+)\.(\d{1,9})\.(.+)")

(defn child-param
  "The form parameter of `field` in child row `index` of `entity`."
  [entity index field]
  (str "__child." (name entity) "." index "." (name field)))

(defn split-child-params
  "`params` as [parent-params rows]: the parent's own fields, and
   {child-entity [[index {field value}] ...]} in index order, with field
   names as strings, as a form submits them. A `__child.` parameter whose
   index is not a small number is dropped."
  [params]
  (let [[parent rows] (reduce-kv
                       (fn [[parent rows] k v]
                         (if-let [[_ entity index field] (re-matches child-param-pattern (name k))]
                           [parent (update-in rows [(keyword entity) index] assoc field v)]
                           [(cond-> parent
                              (not (str/starts-with? (name k) "__child.")) (assoc k v))
                            rows]))
                       [{} {}]
                       params)]
    [parent
     ;; Numerically, not as strings: row 10 comes after row 2.
     (update-vals rows #(vec (sort-by (comp parse-long key) %)))]))

(defn- blank-row?
  "Nothing typed: a checkbox always submits a value, so it does not count. A
   file input left empty sends a part with no file name."
  [rel values]
  (every? (fn [[field v]]
            (let [v (if (vector? v) (last v) v)]
              (or (= :boolean (get-in rel [:entity-config :fields (keyword field) :type]))
                  (no-file? v) (str/blank? (str v)))))
          values))

(defn filled-rows
  "The rows of `rel` in `rows` with something typed in them."
  [rel rows]
  (vec (remove (fn [[_ values]] (blank-row? rel values)) (get rows (:entity rel)))))

(defn too-few
  "{child-entity {:min :count :label}} for each of `rels` given fewer filled
   rows than its :min."
  [rels rows]
  (into {}
        (for [rel   rels
              :let  [n (count (filled-rows rel rows))]
              :when (< n (:min rel))]
          [(:entity rel) {:min (:min rel) :count n :label (:label rel)}])))

(def max-child-rows
  "The most rows of one has-many a create takes."
  500)

(defn too-many
  "{child-entity {:max :count :label}} for each of `rels` given more rows
   than `max-child-rows`, blank ones included: each is parsed."
  [rels rows]
  (into {}
        (for [rel   rels
              :let  [n (count (get rows (:entity rel)))]
              :when (> n max-child-rows)]
          [(:entity rel) {:max max-child-rows :count n :label (:label rel)}])))

(defn duplicate-row?
  "Whether `values` hold two rows sent under one index: a field sent twice.
   A checkbox sends its hidden \"false\" and, when checked, \"true\"."
  [rel values]
  (boolean
   (some (fn [[field v]]
           (and (vector? v)
                (not (and (= :boolean (get-in rel [:entity-config :fields (keyword field) :type]))
                          (= 2 (count v))
                          (= #{"false" "true"} (set v))))))
         values)))

;; =============================================================================
;; Many-to-many links (BOU-778)
;; =============================================================================

(def links-param
  "Names each many-to-many key on the form, so one with nothing chosen,
   which submits nothing of its own, still empties its links."
  "__links")

(defn- param [params k]
  (if (contains? params k) (get params k) (get params (keyword k))))

(defn- as-values [v]
  (cond (nil? v) [] (sequential? v) (vec v) :else [v]))

(defn split-link-params
  "`params` as [params links]: the params without the many-to-many keys of
   `entity-config`, and {key [id ...]} for each key submitted or named in
   `links-param`, empty when nothing is chosen. The service cleans the ids."
  [entity-config params]
  (let [named (set (map str (as-values (param params links-param))))
        keys  (map (comp name :key) (:many-to-many entity-config))
        sent  (filter #(or (named %) (some? (param params %))) keys)]
    [(apply dissoc params links-param (keyword links-param) (mapcat (juxt identity keyword) keys))
     (into {} (map (fn [k] [(keyword k) (as-values (param params k))])) sent)]))

;; =============================================================================
;; Removing a stored file (BOU-811)
;; =============================================================================

(defn split-remove-params
  "`params` as [params removed]: without `remove-file-param`, and the file
   fields it names."
  [entity-config params]
  [(dissoc params remove-file-param (keyword remove-file-param))
   (filterv #(introspection/upload-field? (get-in entity-config [:fields %]))
            (map keyword (as-values (param params remove-file-param))))])

(defn clear-removed
  "`data` with each `removed` file cleared, unless the form chose a new one."
  [data removed]
  (reduce (fn [acc field] (cond-> acc (not (contains? acc field)) (assoc field nil))) data removed))
