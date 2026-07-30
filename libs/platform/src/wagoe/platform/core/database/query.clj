(ns wagoe.platform.core.database.query
  "Pure functions for SQL query building and transformation.
   
   All functions in this namespace are pure - they take data and return data
   without side effects. No I/O, logging, or state mutation."
  (:require [wagoe.core.utils.case-conversion :as case-conv]
            [clojure.string]
            [honey.sql :as sql]))

;; =============================================================================
;; Constants
;; =============================================================================

(def default-pagination-limit
  "Default number of results to return when no limit is specified."
  20)

(def min-pagination-limit
  "Minimum allowed pagination limit."
  1)

(def max-pagination-limit
  "Maximum allowed pagination limit to prevent excessive memory usage."
  1000)

;; =============================================================================
;; Dialect Mapping
;; =============================================================================

(defn adapter-dialect->honey-dialect
  "Map adapter dialect keyword to HoneySQL-supported dialect.
   
   Pure function: deterministic mapping with no side effects.
   
   Args:
     adapter-dialect: Keyword dialect from adapter (:sqlite, :postgresql, etc.)
     
   Returns:
     HoneySQL dialect keyword or nil for ANSI SQL
     
   Example:
     (adapter-dialect->honey-dialect :sqlite) => nil
     (adapter-dialect->honey-dialect :postgresql) => :postgresql"
  [adapter-dialect]
  (case adapter-dialect
    :sqlite nil        ; SQLite uses default (ANSI)
    :h2 :ansi         ; H2 uses ANSI SQL
    adapter-dialect)) ; Others use the same name (e.g., :postgresql, :mysql)

;; =============================================================================
;; Query Formatting
;; =============================================================================

(defn- convert-identifier
  "Convert kebab-case identifier to snake_case for database.
   Only converts keywords, leaves strings unchanged to avoid corrupting data values.
   
   Pure function: deterministic string transformation.
   
   Args:
     x: Identifier (keyword only - strings pass through unchanged)
     
   Returns:
     snake_case identifier for keywords, original value for everything else"
  [x]
  (if (keyword? x)
    (case-conv/kebab-case->snake-case-keyword x)
    x))

(defn- convert-query-identifiers
  "Walk through HoneySQL query map and convert table/column identifiers to snake_case.
   This handles table names, column names, etc. at the database boundary.
   
   IMPORTANT: Only converts VALUES (table names, column names), NOT KEYS (HoneySQL clauses).
   HoneySQL clauses like :insert-into, :select, :from must remain kebab-case.
   Only converts keywords, NOT strings (to avoid corrupting string data values).
   
   Pure function: transforms nested data structure recursively.
   
   Args:
     query-map: HoneySQL query map with kebab-case identifiers
     
   Returns:
     HoneySQL query map with snake_case table/column names"
  [query-map]
  (cond
    ;; For maps, convert values but NOT keys (keys are HoneySQL clauses)
    (map? query-map)
    (reduce-kv
     (fn [acc k v]
       ;; Keep key as-is (HoneySQL clause like :insert-into)
       ;; But convert the value recursively
       (assoc acc k (convert-query-identifiers v)))
     {}
     query-map)
    
    ;; For vectors, convert identifier keywords only (not strings - those are data)
    (vector? query-map)
    (mapv (fn [item]
            (cond
              (keyword? item) (convert-identifier item)
              ;; Don't convert strings - they might be data values
              ;; Don't convert maps - they need recursive conversion
              (or (map? item) (vector? item) (list? item))
              (convert-query-identifiers item)
              :else item))
          query-map)
    
    ;; For lists (like where clauses), convert identifier keywords only
    (list? query-map)
    (map (fn [item]
           (cond
             (keyword? item) (convert-identifier item)
             ;; Don't convert strings - they might be data values
             (or (map? item) (vector? item) (list? item))
             (convert-query-identifiers item)
             :else item))
         query-map)
    
    ;; For bare keywords (shouldn't happen at top level), convert
    (keyword? query-map)
    (convert-identifier query-map)
    
    ;; Leave everything else unchanged (strings, numbers, booleans, UUIDs, timestamps, etc.)
    :else query-map))

(defn format-sql
  "Format HoneySQL query map to SQL string with parameters.
   Converts kebab-case identifiers to snake_case at database boundary.
   
   Pure function: transforms data structure to SQL without executing it.
   
   Args:
     adapter-dialect: Database dialect keyword
     query-map: HoneySQL query map (with kebab-case identifiers)
     
   Returns:
     Vector of [sql-string & parameters]
     
   Example:
     (format-sql :postgresql {:select [:*] :from [:users]})
     => [\"SELECT * FROM users\"]
     
     (format-sql :postgresql {:select [:*] :from [:user-profiles]})
     => [\"SELECT * FROM user_profiles\"]"
  [adapter-dialect query-map]
  (let [converted-query (convert-query-identifiers query-map)]
    (if-let [honey-dialect (adapter-dialect->honey-dialect adapter-dialect)]
      (sql/format converted-query {:dialect honey-dialect :quoted false})
      (sql/format converted-query {:quoted false}))))

(defn format-sql-with-opts
  "Format HoneySQL query map with custom options.
   Converts kebab-case identifiers to snake_case at database boundary.
   
   Pure function: transforms data with configuration.
   
   Args:
     adapter-dialect: Database dialect keyword
     query-map: HoneySQL query map (with kebab-case identifiers)
     opts: Additional HoneySQL formatting options
     
   Returns:
     Vector of [sql-string & parameters]"
  [adapter-dialect query-map opts]
  (let [converted-query (convert-query-identifiers query-map)
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

(defn build-pagination
  "Build LIMIT/OFFSET clause with safe bounds checking.
   
   Pure function: validates and constrains pagination parameters.
   
   Args:
     options: Map with optional :limit and :offset keys
     
   Returns:
     Map with sanitized :limit and :offset values
     
   Example:
     (build-pagination {:limit 50 :offset 100})
     => {:limit 50 :offset 100}
     
     (build-pagination {:limit 5000})
     => {:limit 1000 :offset 0}  ; Clamped to max"
  [options]
  (let [limit (get options :limit default-pagination-limit)
        offset (get options :offset 0)]
    {:limit (min (max limit min-pagination-limit) max-pagination-limit)
     :offset (max offset 0)}))

(defn build-ordering
  "Build ORDER BY clause from sort options.
   
   Pure function: constructs ordering specification.
   
   Args:
     options: Map with optional :sort-by and :sort-direction keys
     default-field: Default field keyword to sort by
     
   Returns:
     Vector of [field direction] pairs for HoneySQL
     
   Example:
     (build-ordering {:sort-by :created-at :sort-direction :desc} :id)
     => [[:created-at :desc]]"
  [options default-field]
  (let [sort-field (get options :sort-by default-field)
        raw-direction (get options :sort-direction :asc)
        direction (if (#{:asc :desc} raw-direction) raw-direction :asc)]
    [[sort-field direction]]))

(defn build-where-filters
  "Build WHERE clause conditions from filter map with proper type conversions.
   
   Pure function: transforms filter map to HoneySQL conditions with type handling.
   Converts kebab-case field names to snake_case for database compatibility.
   
   Args:
     filters: Map of kebab-case field keywords -> value filters
   
   Returns:
     HoneySQL WHERE clause vector with snake_case field names or nil if no filters
   
   Type Handling:
     - UUID: converted to string for DB storage
     - Keyword values: converted to string for DB storage  
     - Boolean: passed as-is (adapter handles DB-specific conversion)
     - Sequential values: used with IN clause, items converted if needed
     - nil: used with IS NULL clause
   
   Field Name Handling:
     - Kebab-case keywords (e.g., :tenant-id) converted to snake_case (e.g., :tenant_id)
   
   Example:
     (build-where-filters {:name \"John\" :active true})
     => [:and [:= :name \"John\"] [:= :active true]]
     
     (build-where-filters {:role [:admin :user]})
     => [:and [:in :role [\"admin\" \"user\"]]]
     
     (build-where-filters {:tenant-id #uuid \"...\" :deleted-at nil})
     => [:and [:= :tenant_id \"...\"] [:is :deleted_at nil]]"
  [filters]
  (when (seq filters)
    (let [kebab->snake (fn [kw]
                         (keyword (clojure.string/replace (name kw) #"-" "_")))
          convert-value (fn [value]
                          (cond
                            (uuid? value) (str value)
                            (keyword? value) (name value)
                            (sequential? value) (mapv #(cond
                                                         (uuid? %) (str %)
                                                         (keyword? %) (name %)
                                                         :else %) value)
                            :else value))
          conditions (for [[field value] filters]
                       (let [db-field (kebab->snake field)
                             converted-value (convert-value value)]
                         (cond
                           (nil? value) [:is db-field nil]
                           (sequential? converted-value) [:in db-field converted-value]
                           :else [:= db-field converted-value])))]
      (if (= 1 (count conditions))
        (first conditions)
        (vec (cons :and conditions))))))
