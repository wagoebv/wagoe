(ns wagoe.platform.core.database.seed
  "Pure logic for database seeding.

   A seed file is EDN: a map of table name -> vector of row maps.

     {:tasks [{:title \"Try the admin UI\" :done false}
              {:title \"Read AGENTS.md\"   :done true}]}

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

(defn validate-seed
  "Checks the overall shape of parsed seed data.

   Returns `{:ok data}` or `{:error {:type :validation-error :message ...}}`.
   Rejects anything that would otherwise fail deep inside the insert loop with
   a less obvious message."
  [data]
  (cond
    (not (map? data))
    {:error {:type    :validation-error
             :message "Seed file must contain a map of table -> rows."}}

    (empty? data)
    {:error {:type    :validation-error
             :message "Seed file is empty — nothing to insert."}}

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
               data))
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
   table, preserving the file's order so a seed file can express dependencies
   between tables by listing parents first."
  [data]
  (mapv (fn [[table rows]]
          {:table (table->name table)
           :rows  (mapv row->columns rows)
           :count (count rows)})
        data))
