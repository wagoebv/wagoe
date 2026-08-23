(ns wagoe.platform.shell.adapters.database.common.query
  "Common query building and formatting utilities."
  (:require [wagoe.platform.ports.database :as protocols]
            [wagoe.core.utils.case-conversion :as case-conv]
            [honey.sql :as sql]
            [clojure.walk :as walk]))

;; =============================================================================
;; Configuration Constants
;; =============================================================================

(def ^:private default-pagination-limit
  "Default number of results to return when no limit is specified."
  20)

(def ^:private min-pagination-limit
  "Minimum allowed pagination limit."
  1)

(def ^:private max-pagination-limit
  "Maximum allowed pagination limit to prevent excessive memory usage."
  1000)

;; =============================================================================
;; HoneySQL Formatting
;; =============================================================================

(defn- convert-identifier
  "Convert kebab-case identifier to snake_case for database.
   Only converts keywords and strings, leaves other values unchanged."
  [x]
  (cond
    (keyword? x) (case-conv/kebab-case->snake-case-keyword x)
    (string? x) (case-conv/kebab-case->snake-case-string x)
    :else x))

(defn- convert-query-identifiers
  "Walk through HoneySQL query map and convert all identifiers to snake_case.
   This handles table names, column names, etc. at the database boundary."
  [query-map]
  (walk/postwalk
   (fn [x]
     (cond
       ;; Convert keywords (table names, column names)
       (keyword? x) (convert-identifier x)
       ;; Convert vectors of keywords (e.g., [:table :column])
       (and (vector? x) (every? keyword? x))
       (mapv convert-identifier x)
       ;; Leave everything else unchanged
       :else x))
   query-map))

(defn- adapter-dialect->honey-dialect
  "Map adapter dialect to HoneySQL-supported dialect.

   Args:
     adapter-dialect: Keyword dialect from adapter

   Returns:
     HoneySQL dialect keyword or nil for default (ANSI)"
  [adapter-dialect]
  (case adapter-dialect
    :sqlite nil        ; SQLite uses default (ANSI)
    :h2 :ansi         ; H2 uses ANSI SQL
    adapter-dialect)) ; Others use the same name (e.g., :postgresql, :mysql)

(defn format-sql
  "Format HoneySQL query map using adapter's dialect.
   Converts kebab-case identifiers to snake_case at database boundary.

   Args:
     adapter: Database adapter
     query-map: HoneySQL query map (with kebab-case identifiers)

   Returns:
     Vector of [sql & params]

   Example:
     (format-sql adapter {:select [:*] :from [:users]})"
  [adapter query-map]
  (let [converted-query (convert-query-identifiers query-map)]
    (if-let [adapter-dialect (protocols/dialect adapter)]
      (let [honey-dialect (adapter-dialect->honey-dialect adapter-dialect)]
        (if honey-dialect
          (sql/format converted-query {:dialect honey-dialect :quoted false})
          (sql/format converted-query {:quoted false})))
      (sql/format converted-query {:quoted false}))))

(defn format-sql*
  "Format HoneySQL query map with custom options.
   Converts kebab-case identifiers to snake_case at database boundary.

   Args:
     adapter: Database adapter
     query-map: HoneySQL query map (with kebab-case identifiers)
     opts: Additional formatting options

   Returns:
     Vector of [sql & params]"
  [adapter query-map opts]
  (let [converted-query (convert-query-identifiers query-map)
        adapter-dialect (protocols/dialect adapter)
        honey-dialect (adapter-dialect->honey-dialect adapter-dialect)
        ;; Always disable quoting and merge in dialect if present
        base-opts (merge {:quoted false} opts)
        dialect-opts (if honey-dialect
                       (assoc base-opts :dialect honey-dialect)
                       base-opts)]
    (sql/format converted-query dialect-opts)))

;; =============================================================================
;; Query Building Utilities
;; =============================================================================

(defn build-where-clause
  "Build dynamic WHERE clause from filter map using adapter-specific logic.

   Args:
     adapter: Database adapter
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause or nil

   Example:
     (build-where-clause adapter {:name \"John\" :active true :role [:admin :user]})"
  [adapter filters]
  (when (seq filters)
    (protocols/build-where adapter filters)))

(defn build-pagination
  "Build LIMIT/OFFSET clause from pagination options with safe defaults.

   Args:
     options: Map with :limit and :offset keys

   Returns:
     Map with sanitized :limit and :offset values

   Example:
     (build-pagination {:limit 50 :offset 100})"
  [options]
  (let [limit (get options :limit default-pagination-limit)
        offset (get options :offset 0)]
    {:limit (min (max limit min-pagination-limit) max-pagination-limit)
     :offset (max offset 0)}))

(defn build-ordering
  "Build ORDER BY clause from sort options.

   Args:
     options: Map with :sort-by and :sort-direction keys
     default-field: Default field to sort by (keyword)

   Returns:
     Vector of [field direction] pairs

   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)"
  [options default-field]
  (let [sort-field (get options :sort-by default-field)
        raw-direction (get options :sort-direction :asc)
        direction (if (#{:asc :desc} raw-direction) raw-direction :asc)]
    [[sort-field direction]]))
