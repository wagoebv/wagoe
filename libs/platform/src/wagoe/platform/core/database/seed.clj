(ns wagoe.platform.core.database.seed
  "Pure logic for database seeding.

   A seed file is EDN, in either of two shapes.

   A map of table -> rows, for the simple case:

     {:tasks [{:title \"Try the admin UI\" :done false}
              {:title \"Read AGENTS.md\"   :done true}]}

   Or a vector of [table rows] pairs, which is ordered:

     [[:users [{:email \"admin@example.com\"}]]
      [:tasks [{:title \"Owned by that user\" :user-id 1}]]]

   Insert order matters as soon as one table references another, and EDN maps
   only preserve their written order up to 8 entries — a 9th turns the literal
   into a PersistentHashMap and the order becomes hash order. Rather than let a
   seed file quietly start inserting children before parents once it grows, a
   map larger than that is rejected with a pointer to the vector form.

   Table and column names are written in kebab-case, like the rest of the
   codebase; the conversion to snake_case happens here, at the point where the
   data becomes a persistence concern.

   `id`, `created-at` and `updated-at` may be left out; see `resolve-seed`. A
   child names its parent by a symbolic id, a qualified keyword:

     [[:invoices [{:id :invoice/acme :number \"INV-1\"}]]
      [:invoice-line-items [{:invoice-id :invoice/acme :description \"Work\"}]]]

   Everything in this namespace is pure. Validation returns typed error values
   rather than throwing — the shell decides how to present them."
  (:require [clojure.string :as str]
            [wagoe.core.utils.case-conversion :as cc]))

(defn- table-error
  [table reason]
  {:error {:type    :validation-error
           :table   table
           :message reason}})

(def ^:private max-ordered-map-entries
  "Largest EDN map literal that still preserves its written order.

   Clojure reads up to 8 pairs as a PersistentArrayMap, which iterates in
   insertion order; the 9th makes it a PersistentHashMap, which does not."
  8)

(defn entries
  "Normalises either accepted shape into a seq of [table rows] pairs."
  [data]
  (if (map? data) (seq data) (seq data)))

(def ^:private defaulted-keys
  "Keys a row may leave out when its table has the column: resolve-seed fills them."
  #{:id :created-at :updated-at})

(defn validate-seed
  "Checks the overall shape of parsed seed data.

   Returns `{:ok data}` or `{:error {:type :validation-error :message ...}}`.
   Rejects anything that would otherwise fail deep inside the insert loop with
   a less obvious message — including a map too large to keep its order, which
   would otherwise insert children before parents and fail on a foreign key."
  [data]
  (cond
    (not (or (map? data) (sequential? data)))
    {:error {:type    :validation-error
             :message (str "Seed file must contain a map of table -> rows, "
                           "or a vector of [table rows] pairs.")}}

    (empty? data)
    {:error {:type    :validation-error
             :message "Seed file is empty — nothing to insert."}}

    (and (map? data) (> (count data) max-ordered-map-entries))
    {:error {:type    :validation-error
             :message (str "Seed file has " (count data) " tables as a map. EDN maps "
                           "larger than " max-ordered-map-entries " entries do not keep "
                           "their written order, so tables would be inserted in an "
                           "arbitrary order and any foreign key between them could fail.\n"
                           "  Use the ordered form instead:\n"
                           "    [[:users [{...}]]\n"
                           "     [:tasks [{...}]]]")}}

    (and (sequential? data)
         (not (every? #(and (sequential? %) (= 2 (count %))) data)))
    {:error {:type    :validation-error
             :message "Ordered seed files must be a vector of [table rows] pairs."}}

    :else
    (or (first
         (keep (fn [[table rows]]
                 (cond
                   (not (or (keyword? table) (string? table)))
                   (table-error table "Table name must be a keyword or string.")

                   (not (sequential? rows))
                   (table-error table "Rows must be a vector of maps.")

                   (empty? rows)
                   (table-error table "No rows given for this table.")

                   (not (every? map? rows))
                   (table-error table "Every row must be a map.")

                   ;; Defaulted keys are checked again once defaulted: see
                   ;; resolve-seed.
                   (not (apply = (map #(set (remove defaulted-keys (keys %))) rows)))
                   (table-error table
                                (str "All rows for a table must have the same keys — "
                                     "a partial row would insert NULLs silently."))

                   :else nil))
               (entries data)))
        {:ok data})))

(defn- keyword->text
  "A keyword value as the string a column holds: `:delivered` is a workflow
   state, and HoneySQL would read it as a column name."
  [v]
  (if (keyword? v) (subs (str v) 1) v))

(defn row->columns
  "Converts one kebab-case row map into its snake_case persistence form."
  [row]
  (update-vals (cc/kebab-case->snake-case-map row) keyword->text))

(defn table->name
  "Persistence name for a seed table key: :audit-logs -> \"audit_logs\"."
  [table]
  (cc/kebab-case->snake-case-string (name table)))

(defn- ref-problem [table id info refs]
  (cond
    (not (qualified-keyword? id))
    (str "The symbolic id " id " needs a namespace, like :" (name table) "/" (name id) ".")

    (not (:uuid-id? info))
    (str "A symbolic id needs a uuid id column, and " (table->name table) " has none.")

    (contains? refs id)
    (str "Two rows declare the id " id ".")))

(defn- declare-refs
  "{:refs {symbolic-id new-id}} for every row whose :id is a keyword, or an error."
  [pairs tables new-id]
  (reduce (fn [{:keys [refs] :as acc} [table id]]
            (if-let [why (ref-problem table id (get tables (table->name table)) refs)]
              (reduced (table-error table why))
              (assoc-in acc [:refs id] (new-id))))
          {:refs {}}
          (for [[table rows] pairs, row rows :when (keyword? (:id row))] [table (:id row)])))

(defn- id-column?
  "Whether `k` holds an id: `:id`, or a reference such as `:invoice-id`. Only
   these take symbolic ids; a namespaced keyword elsewhere is a value."
  [k]
  (or (= :id k) (str/ends-with? (name k) "-id")))

(defn- stray-ref
  "The first [table value] where a reference column holds a qualified keyword
   no row declares: a missing parent, or a typo."
  [pairs refs]
  (first (for [[table rows] pairs, row rows, [k v] row
               :when (and (not= :id k) (id-column? k) (qualified-keyword? v)
                          (not (contains? refs v)))]
           [table v])))

(defn- fill-row [row info refs now new-id]
  (let [cols (:columns info #{})
        row  (into {} (map (fn [[k v]] [k (if (id-column? k) (get refs v v) v)])) row)]
    (cond-> row
      (and (:uuid-id? info) (not (contains? row :id)))            (assoc :id (new-id))
      (and (cols "created_at") (not (contains? row :created-at))) (assoc :created-at now)
      (and (cols "updated_at") (not (contains? row :updated-at))) (assoc :updated-at now))))

(defn resolve-seed
  "Validated seed data with symbolic ids replaced and the defaults filled in,
   as [table rows] pairs, or `{:error ...}`.

   A row whose `:id` is a qualified keyword, `:invoice/acme`, gets a new id,
   and a reference column (`:invoice-id`, any `*-id`) holding that keyword
   gets the same id: that is how a child names its parent. Other columns keep
   a namespaced keyword as text. A row without `:id` gets a new one when its table's key is
   a uuid `id`, and `:created-at`/`:updated-at` get `now` when the table has
   those columns.

   `tables` is {table-name {:columns #{column-name} :uuid-id? bool}}; `new-id`
   returns a fresh id each call."
  [data {:keys [tables now new-id]}]
  (let [pairs (vec (entries data))
        {:keys [refs error]} (declare-refs pairs tables new-id)]
    (if error
      {:error error}
      (if-let [[table v] (stray-ref pairs refs)]
        (table-error table (str "No row declares the id " v ". Give its row :id " v "."))
        (let [resolved (mapv (fn [[table rows]]
                               (let [info (get tables (table->name table))]
                                 [table (mapv #(fill-row % info refs now new-id) rows)]))
                             pairs)]
          (or (some (fn [[table rows]]
                      (when-not (apply = (map (comp set keys) rows))
                        (table-error table (str "All rows for a table must have the same keys, and this "
                                                "table has no default for the ones some rows leave out."))))
                    resolved)
              {:ok resolved}))))))

(defn seed-plan
  "Turns validated seed data into an ordered insert plan.

   Returns a vector of `{:table \"tasks\" :rows [{...}] :count n}`, one entry per
   table, in the order the file lists them, so a seed file can express
   dependencies between tables by putting parents first.

   That ordering is only trustworthy because `validate-seed` has already
   rejected maps too large to preserve it — see `max-ordered-map-entries`."
  [data]
  (mapv (fn [[table rows]]
          {:table (table->name table)
           :rows  (mapv row->columns rows)
           :count (count rows)})
        (entries data)))
