(ns wagoe.search.core.index
  "Pure document construction and filter-key conversion for search.

   Key functions:
   - defsearch             — bind a SearchDefinition to a var (data only)
   - collect-definitions   — the definitions a :wagoe/search component holds
   - api-enabled?          — whether the generic search API serves an index
   - build-document*       — construct a SearchDocument from field values
   - filter-key->json-key  — convert a filter keyword to a snake_case JSON key"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Definitions
;; =============================================================================

(defmacro defsearch
  "Bind `definition` to `var-name`. Registers nothing: hand the var to
   `:wagoe/search`, under `:indexes` in config or a module's `:search` part."
  [var-name definition]
  `(def ~var-name ~definition))

(defn api-enabled?
  "True when the generic search API may serve `definition`: it sets `:api? true`
   or an `:api` map (which may hold an `:authorize` hook)."
  [definition]
  (or (true? (:api? definition)) (map? (:api definition))))

(defn collect-definitions
  "The definitions a search component holds: `indexes` from its config and
   `contributed` by modules, both maps of index id -> definition. A definition
   without `:id` takes its key.

   Returns `{:definitions {…}}`, or `{:error {…}}` when an id is set twice or a
   definition's `:id` disagrees with its key."
  [indexes contributed]
  (let [clash      (some (set (keys indexes)) (keys contributed))
        entries    (concat indexes contributed)
        [k d]      (some (fn [[k d :as e]] (when (and (:id d) (not= k (:id d))) e)) entries)]
    (cond
      clash
      {:error {:type    :configuration-error
               :id      clash
               :message (str "Search index " clash " is defined both under :indexes and by a module.")}}

      k
      {:error {:type    :configuration-error
               :id      k
               :message (str "Search index under " k " has :id " (:id d) ".")}}

      :else
      {:definitions (into {} (map (fn [[k d]] [k (assoc d :id k)])) entries)})))

;; =============================================================================
;; Filter key conversion (shared with persistence layer)
;; =============================================================================

(defn filter-key->json-key
  "Convert a filter keyword to a snake_case JSON object key.

   :tenant-id -> \"tenant_id\"
   :status    -> \"status\"

   Used consistently by both build-document (storage) and the SQL
   query builders (retrieval) so that stored and queried keys always match."
  [k]
  (str/replace (name k) "-" "_"))

;; =============================================================================
;; Document Construction
;; =============================================================================

(defn build-document*
  "Construct a SearchDocument from a SearchDefinition and field values using explicit runtime inputs.

   Distributes field values to the correct weight buckets (A/B/C/D).
   Concatenates all text into :content-all for fallback LIKE search.

   Args:
     definition   - SearchDefinition map
     entity-id    - UUID
     field-values - map of field-name -> string or seq value
                    Seqs are joined with a space.
     opts         - map with required :id and :updated-at, optional :metadata

   Returns:
     SearchDocument map ready for ISearchStore.upsert-document!"
  [definition entity-id field-values & [opts]]
  (let [index-id    (:id definition)
        entity-type (:entity-type definition)
        language    (name (or (:language definition) :english))
        fields      (:fields definition)
        ;; Accumulate values per weight bucket
        weight-map  (reduce
                     (fn [acc {:keys [name weight]}]
                       (let [weight-key (keyword (str "weight-"
                                                      (str/lower-case
                                                       (clojure.core/name weight))))
                             raw-val    (get field-values name)
                             str-val    (cond
                                          (nil? raw-val)        nil
                                          (sequential? raw-val) (str/join " " (map str raw-val))
                                          :else                 (str raw-val))]
                         (if str-val
                           (update acc weight-key
                                   (fn [existing]
                                     (if existing
                                       (str existing " " str-val)
                                       str-val)))
                           acc)))
                     {}
                     fields)
        content-all (->> [:weight-a :weight-b :weight-c :weight-d]
                         (keep #(get weight-map %))
                         (str/join " "))]
    (cond-> {:id          (:id opts)
             :index-id    index-id
             :entity-type entity-type
             :entity-id   entity-id
             :language    language
             :weight-a    (get weight-map :weight-a "")
             :weight-b    (get weight-map :weight-b "")
             :weight-c    (get weight-map :weight-c "")
             :weight-d    (get weight-map :weight-d "")
             :content-all content-all
             :updated-at  (:updated-at opts)}
      (:metadata opts)
      (assoc :metadata (:metadata opts))
      ;; Store filter values so they can be used as WHERE-clause predicates.
      (seq (:filter-values opts))
      (assoc :filters (:filter-values opts)))))
