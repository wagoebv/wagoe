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

   Everything in this namespace is pure. Validation returns typed error values
   rather than throwing — the shell decides how to present them."
  (:require [wagoe.core.utils.case-conversion :as cc]))

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

                   (not (apply = (map (comp set keys) rows)))
                   (table-error table
                                (str "All rows for a table must have the same keys — "
                                     "a partial row would insert NULLs silently."))

                   :else nil))
               (entries data)))
        {:ok data})))

(defn row->columns
  "Converts one kebab-case row map into its snake_case persistence form."
  [row]
  (cc/kebab-case->snake-case-map row))

(defn table->name
  "Persistence name for a seed table key: :audit-logs -> \"audit_logs\"."
  [table]
  (cc/kebab-case->snake-case-string (name table)))

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
