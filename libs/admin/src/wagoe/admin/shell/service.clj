(ns wagoe.admin.shell.service
  "Admin service implementation for CRUD operations on entities.

   This service provides database-agnostic CRUD operations on any entity
   managed by the admin interface. It coordinates between schema providers,
   permission checks, and database operations.

   Responsibilities:
   - Execute CRUD operations with observability
   - Apply pagination, filtering, sorting
   - Validate permissions and entity access
   - Transform data between DB and application formats
   - Handle soft/hard deletes based on schema"
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.platform.shell.adapters.database.common.execution :as db]
   [wagoe.platform.shell.persistence-interceptors :as persist-interceptors]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.utils.case-conversion :as case-conversion]
   [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Database Boundary Helpers
;; =============================================================================

(defn prepare-values-for-db
  "Convert all typed values (UUID, Instant) to strings for database storage.
   
   This ensures that at the database boundary, all complex types are converted
   to their string representations. This is critical for database compatibility
   and follows the principle that type conversions happen at system edges.
   
   Args:
     m: Map with potentially typed values
     
   Returns:
     Map with all UUIDs and Instants converted to strings
     
   Example:
     (prepare-values-for-db {:id (UUID/randomUUID) 
                             :created-at (Instant/now)
                             :name \"John\"})
     ;=> {:id \"123e4567-...\" :created-at \"2024-01-10T...\" :name \"John\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [converted-value (cond
                                         (instance? UUID v) (type-conversion/uuid->string v)
                                         (instance? Instant v) (type-conversion/instant->string v)
                                         :else v)]
                   (assoc acc k converted-value)))
               {} m)))

;; =============================================================================
;; Query Building Helpers
;; =============================================================================

(defn build-pagination
  "Build pagination clause with safe bounds checking.

   Args:
     options: Map with :limit, :offset, or :page and :page-size
     config: Admin configuration map with pagination defaults

   Returns:
     Map with :limit and :offset

   Examples:
     (build-pagination {:limit 20 :offset 10} config)
     (build-pagination {:page 2 :page-size 25} config)"
  [options config]
  (let [default-limit (get-in config [:pagination :default-page-size] 20)
        max-limit (get-in config [:pagination :max-page-size] 200)
        limit (or (:limit options) (:page-size options) default-limit)
        offset (or (:offset options)
                   (when (:page options)
                     (* (dec (:page options 1))
                        (or (:page-size options) default-limit)))
                   0)
        ; Clamp limits to reasonable ranges
        safe-limit (min (max limit 1) max-limit)
        safe-offset (max offset 0)]
    {:limit safe-limit
     :offset safe-offset}))

(defn build-ordering
  "Build ORDER BY clause.

   Args:
     sort-field: Keyword field to sort by
     sort-dir: :asc or :desc
     default-field: Fallback field if sort-field not provided

   Returns:
     Vector for HoneySQL :order-by clause

   Example:
     (build-ordering :email :asc :id) ;=> [[:email :asc]]"
  [sort-field sort-dir default-field]
  (let [field (or sort-field default-field)
        direction (or sort-dir :asc)]
    [[field direction]]))

(defn build-search-where
  "Build WHERE clause for case-insensitive text search across multiple fields.

   Args:
     search-term: String to search for
     search-fields: Vector of field keywords to search in

   Returns:
     HoneySQL WHERE clause (nil if no search term)

   Example:
     (build-search-where john [:email :name])
     => [:or [:ilike :email percent-john-percent]
             [:ilike :name percent-john-percent]]"
  [search-term search-fields]
  (when (and search-term (seq search-fields))
    (let [search-pattern (str "%" search-term "%")]
      (vec (cons :or
                 (mapv (fn [field]
                         [:ilike field search-pattern])
                       search-fields))))))

(defn build-filter-where
  "Build WHERE clause for field-specific filters.

   Week 1: Simple equality filters
   Week 2: Advanced operators (gt, lt, contains, in, between, etc.)

   Args:
     filters: Map of field -> value (Week 1) or field -> filter-map (Week 2)
              Week 1: {:role :admin :active true}
              Week 2: {:created-at {:op :gte :value \"2024-01-01\"}
                       :status {:op :in :values [:active :pending]}}

   Returns:
     HoneySQL WHERE clause (nil if no filters)

   Example:
     (build-filter-where {:role :admin :active true})
     ;=> [:and [:= :role :admin] [:= :active true]]

     (build-filter-where {:price {:op :gte :value 100}
                          :status {:op :in :values [:active :pending]}})
     ;=> [:and [:>= :price 100] [:in :status [:active :pending]]]"
  [filters]
  (when (seq filters)
    (let [clauses (mapv (fn [[field filter-value]]
                          (if (map? filter-value)
                            ; Week 2: Advanced filter with operator
                            (let [{:keys [op value values min max]} filter-value]
                              (case op
                                :eq          [:= field value]
                                :ne          [:!= field value]
                                :gt          [:> field value]
                                :gte         [:>= field value]
                                :lt          [:< field value]
                                :lte         [:<= field value]
                                :contains    [:ilike field (str "%" value "%")]
                                :starts-with [:ilike field (str value "%")]
                                :ends-with   [:ilike field (str "%" value)]
                                :in          [:in field (vec values)]
                                :not-in      [:not-in field (vec values)]
                                :is-null     [:= field nil]
                                :is-not-null [:!= field nil]
                                :between     [:and [:>= field min] [:<= field max]]
                                ; Default: equality
                                [:= field value]))
                            ; Week 1: Simple equality filter (backward compatible)
                            [:= field filter-value]))
                        filters)]
      (if (= 1 (count clauses))
        (first clauses)
        (vec (cons :and clauses))))))

(defn combine-where-clauses
  "Combine multiple WHERE clauses with AND.

   Args:
     clauses: Vector of WHERE clause expressions (nils are filtered)

   Returns:
     Combined WHERE clause or nil

   Example:
     (combine-where-clauses [[:= :active true] nil [:like :email \"%@example.com%\"]])
     ;=> [:and [:= :active true] [:like :email \"%@example.com%\"]]"
  [clauses]
  (let [non-nil-clauses (remove nil? clauses)]
    (when (seq non-nil-clauses)
      (if (= 1 (count non-nil-clauses))
        (first non-nil-clauses)
        (vec (cons :and non-nil-clauses))))))

;; =============================================================================
;; Query Config Resolution
;; =============================================================================

(defn- resolve-query-config
  "Extract query configuration from entity-config, applying :query-overrides if present.
   Entities without :query-overrides behave exactly as before.

   For split-table entities: ensures the select clause covers ALL known fields,
   not just those explicitly listed.  When :split-table-update is configured and
   a :join is present, any field in :fields that is missing from the explicit
   :select is appended using the appropriate table alias so the admin UI always
   sees every editable column."
  [entity-config]
  (let [overrides   (:query-overrides entity-config {})
        table-name  (:table-name entity-config)
        split-cfg   (:split-table-update entity-config)
        raw-select  (:select overrides)
        ;; For split-table entities, ensure all fields are selected
        select-clause (if (and split-cfg raw-select (:join overrides))
                        (let [secondary-fields (:secondary-fields split-cfg #{})
                              field-aliases    (:field-aliases overrides {})
                              ;; Collect field names already covered by explicit select OR field-aliases.
                              ;; Strip table alias prefix and convert snake_case → kebab-case to match
                              ;; internal field keys (e.g. :a.password_hash → :password-hash).
                              already-selected (into #{}
                                                     (map (fn [col]
                                                            (let [s (name col)
                                                                  bare (last (str/split s #"\."))]
                                                              (keyword (case-conversion/snake-case->kebab-case-string bare)))))
                                                     (concat raw-select (keys field-aliases)))
                              ;; Fields in entity config that are NOT yet in select
                              all-fields      (keys (:fields entity-config))
                              missing         (remove already-selected all-fields)
                              ;; Derive table aliases and determine which alias maps to the
                              ;; split-table's :secondary-table.
                              ;; Expected shape: :from [[:table :alias] ...], :join [[:table :alias] ...]
                              from-entry      (first (:from overrides))
                              join-entry      (first (:join overrides))
                              from-alias      (when (vector? from-entry) (second from-entry))
                              join-alias      (when (vector? join-entry) (second join-entry))
                              ;; Match :secondary-table to :from or :join to determine alias mapping
                              secondary-table (:secondary-table split-cfg)
                              from-table      (when (vector? from-entry) (first from-entry))
                              join-table      (when (vector? join-entry) (first join-entry))
                              secondary-alias (cond
                                                (= secondary-table from-table) from-alias
                                                (= secondary-table join-table) join-alias
                                                :else from-alias)
                              primary-alias   (if (= secondary-alias from-alias)
                                                join-alias
                                                from-alias)
                              ;; Build qualified column references for missing fields.
                              ;; :secondary-fields get the alias of :secondary-table,
                              ;; all other fields get the other table's alias.
                              extra           (mapv (fn [field]
                                                      (let [col-name (case-conversion/kebab-case->snake-case-string (name field))
                                                            alias (if (contains? secondary-fields field)
                                                                    secondary-alias
                                                                    primary-alias)]
                                                        (if alias
                                                          (keyword (str (name alias) "." col-name))
                                                          (keyword col-name))))
                                                    missing)]
                          (into (vec raw-select) extra))
                        (or raw-select [:*]))]
    {:from-clause       (or (:from overrides) [table-name])
     :select-clause     select-clause
     :join-clause       (:join overrides)
     :field-aliases     (:field-aliases overrides {})
     :soft-delete-table (or (:soft-delete-table overrides) table-name)}))

;; =============================================================================
;; Split-Table Update Helpers
;; =============================================================================

(defn- split-update-data
  "Partition prepared-data into primary-table and secondary-table maps.
   Each half gets an updated-at timestamp when the entity config declares one.
   Halves with no fields are returned as empty maps (callers should skip empty UPDATEs)."
  [prepared-data secondary-fields entity-fields now-str]
  (let [secondary-data (select-keys prepared-data secondary-fields)
        primary-data   (apply dissoc prepared-data secondary-fields)
        add-ts         (fn [m]
                         (cond-> m
                           (and (seq m) (contains? entity-fields :updated-at))
                           (assoc :updated-at now-str)))]
    {:primary-data   (add-ts primary-data)
     :secondary-data (add-ts secondary-data)}))

;; =============================================================================
;; Admin Service Implementation
;; =============================================================================

(defrecord AdminService [db-ctx schema-provider logger error-reporter config]
  ports/IAdminService

  (list-entities [_ entity-name options]
    (persist-interceptors/execute-persistence-operation
     :admin-list-entities
     {:entity (name entity-name)
      :limit (:limit options)
      :offset (:offset options)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             soft-delete? (:soft-delete entity-config false)
             search-term (:search options)
             search-fields (:search-fields entity-config)
             filters (:filters options)
             sort-field (:sort options)
             sort-dir (:sort-dir options)
             default-sort (:default-sort entity-config :id)

             {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)

             ; Resolve field aliases for WHERE and ORDER BY
             resolved-search-fields (mapv #(get field-aliases % %) search-fields)
             resolved-sort-field    (when sort-field (get field-aliases sort-field sort-field))
             resolved-default-sort  (get field-aliases default-sort default-sort)
             soft-delete-field      (get field-aliases :deleted-at :deleted_at)

             resolved-filters (when filters
                                (into {} (map (fn [[field spec]]
                                                [(get field-aliases field field) spec])
                                              filters)))

             ; Build query components
             search-where (build-search-where search-term resolved-search-fields)
             filter-where (build-filter-where resolved-filters)
             ; Exclude soft-deleted records if entity uses soft delete
             soft-delete-where (when soft-delete? [:= soft-delete-field nil])
             where-clause (combine-where-clauses [search-where filter-where soft-delete-where])
             ordering (build-ordering resolved-sort-field sort-dir resolved-default-sort)
             pagination (build-pagination options config)

              ; Build list query
             list-query (cond-> {:select select-clause
                                 :from from-clause
                                 :order-by ordering
                                 :limit (:limit pagination)
                                 :offset (:offset pagination)}
                          join-clause  (assoc :join join-clause)
                          where-clause (assoc :where where-clause))

              ; Build count query
             count-query (cond-> {:select [[:%count.* :total]]
                                  :from from-clause}
                           join-clause  (assoc :join join-clause)
                           where-clause (assoc :where where-clause))

              ; Execute queries
             records (db/execute-query! db-ctx list-query)
             count-result (db/execute-one! db-ctx count-query)
             total-count (:total count-result 0)

              ; Keys arrive kebab-case from the execution layer builder-fn
             kebab-records records

              ; Calculate pagination metadata
             page-size (:limit pagination)
             page-number (inc (quot (:offset pagination) page-size))
             total-pages (int (Math/ceil (/ total-count (double page-size))))]

         {:records kebab-records
          :total-count total-count
          :page-size page-size
          :page-number page-number
          :total-pages total-pages}))
     db-ctx))

  (get-entity [_ entity-name id]
    (persist-interceptors/execute-persistence-operation
     :admin-get-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             primary-key (:primary-key entity-config :id)
             soft-delete? (:soft-delete entity-config false)
             ;; Convert UUID to string for PostgreSQL compatibility
             id-str (type-conversion/uuid->string id)
             {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
             id-field          (get field-aliases :id primary-key)
             soft-delete-field (get field-aliases :deleted-at :deleted_at)
             query (cond-> {:select select-clause
                            :from from-clause
                            :where (if soft-delete?
                                     [:and [:= id-field id-str] [:= soft-delete-field nil]]
                                     [:= id-field id-str])}
                     join-clause (assoc :join join-clause))
             db-result (db/execute-one! db-ctx query)]
          ; Keys arrive kebab-case from the execution layer builder-fn
         db-result))
     db-ctx))

  (create-entity [_ entity-name data]
    ;; Fail-fast: split-table entities cannot be created via the generic admin
    ;; flow because only the primary table would be written (leaving orphaned
    ;; rows). Such entities must expose a dedicated create flow via
    ;; :create-redirect-url. This check is lifted OUT of the persistence
    ;; operation so the persistence interceptor does not log a rejected
    ;; business-rule violation as a "database operation failed" error.
    (let [entity-config (ports/get-entity-config schema-provider entity-name)]
      (when-let [split-cfg (:split-table-update entity-config)]
        (throw (ex-info "Cannot create split-table entity via generic admin flow"
                        {:type :cannot-create-split-table-entity
                         :entity-name entity-name
                         :split-config split-cfg
                         :create-redirect-url (:create-redirect-url entity-config)}))))
    (persist-interceptors/execute-persistence-operation
     :admin-create-entity
     {:entity (name entity-name)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             readonly-fields (:readonly-fields entity-config #{})

              ; Remove only readonly fields from input (not hide-fields!)
              ; hide-fields are for display only, data can still be provided
             sanitized-data (apply dissoc data readonly-fields)

               ; Add generated ID and timestamps - only set columns that exist in the table
             now-str (type-conversion/instant->string (Instant/now))
             generated-id (UUID/randomUUID)
             id-str (type-conversion/uuid->string generated-id)
             entity-fields (:fields entity-config)
             prepared-data (cond-> (assoc sanitized-data :id id-str)
                             (contains? entity-fields :created-at) (assoc :created-at now-str)
                             (contains? entity-fields :updated-at) (assoc :updated-at now-str))

              ; Convert all typed values (UUID, Instant) to strings for database
             db-ready-data (prepare-values-for-db prepared-data)

              ; Convert kebab-case keys to snake_case for database
             db-data (case-conversion/kebab-case->snake-case-map db-ready-data)

              ; Insert without RETURNING (H2 compatibility)
             insert-query {:insert-into table-name
                           :values [db-data]}
             _ (db/execute-one! db-ctx insert-query)

              ; Fetch the created record
             select-query {:select [:*]
                           :from [table-name]
                           :where [:= primary-key id-str]}
             db-result (db/execute-one! db-ctx select-query)]

          ; Keys arrive kebab-case from the execution layer builder-fn
         db-result))
     db-ctx))

  (update-entity [_ entity-name id data]
    (persist-interceptors/execute-persistence-operation
     :admin-update-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             readonly-fields (:readonly-fields entity-config #{})

              ; Remove only readonly and ID fields from update (not hide-fields!)
              ; hide-fields are for display only, data can still be provided
             sanitized-data (apply dissoc data (conj readonly-fields primary-key))

               ; Only set timestamp columns that exist in the table
             now-str (type-conversion/instant->string (Instant/now))
             entity-fields (:fields entity-config)
             prepared-data (cond-> sanitized-data
                             (contains? entity-fields :updated-at) (assoc :updated-at now-str))

              ; Convert all typed values (UUID, Instant) to strings for database
             db-ready-data (prepare-values-for-db prepared-data)

              ; Convert kebab-case keys to snake_case for database
             db-data (case-conversion/kebab-case->snake-case-map db-ready-data)

              ;; Convert UUID to string for PostgreSQL compatibility
             id-str (type-conversion/uuid->string id)

             split-cfg (:split-table-update entity-config)

             _ (if split-cfg
                 (let [{:keys [secondary-table secondary-fields]} split-cfg
                       {:keys [primary-data secondary-data]}
                       (split-update-data sanitized-data secondary-fields entity-fields now-str)
                       primary-db   (-> primary-data
                                        prepare-values-for-db
                                        case-conversion/kebab-case->snake-case-map)
                       secondary-db (-> secondary-data
                                        prepare-values-for-db
                                        case-conversion/kebab-case->snake-case-map)]
                   (db/with-transaction* db-ctx
                     (fn [tx]
                       (when (seq primary-db)
                         (db/execute-update! tx {:update table-name
                                                 :set    primary-db
                                                 :where  [:= primary-key id-str]}))
                       (when (seq secondary-db)
                         (db/execute-update! tx {:update secondary-table
                                                 :set    secondary-db
                                                 :where  [:= primary-key id-str]})))))
                 ;; non-split path
                 (db/execute-update! db-ctx {:update table-name
                                             :set    db-data
                                             :where  [:= primary-key id-str]}))

              ; Fetch the updated record using join-aware query
             {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
             id-field (get field-aliases :id primary-key)
             select-query (cond-> {:select select-clause
                                   :from from-clause
                                   :where [:= id-field id-str]}
                            join-clause (assoc :join join-clause))
             db-result (db/execute-one! db-ctx select-query)]

          ; Keys arrive kebab-case from the execution layer builder-fn
         db-result))
     db-ctx))

  (update-entity-field [_ entity-name id field value]
    ; Week 2: Single field update for inline editing
    ; More efficient than full entity update - only validates and updates one field
    (persist-interceptors/execute-persistence-operation
     :admin-update-entity-field
     {:entity (name entity-name) :id id :field (name field)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             readonly-fields (set (:readonly-fields entity-config))
             field-config (get-in entity-config [:fields field])]

         ; Validate field exists and is not readonly
         (when-not field-config
           (throw (ex-info "Field does not exist"
                           {:type :validation-error
                            :field field
                            :entity entity-name})))

         (when (contains? readonly-fields field)
           (throw (ex-info "Cannot update readonly field"
                           {:type :readonly-field
                            :field field
                            :entity entity-name})))

         ; Validate required fields
         (when (and (:required field-config) (nil? value))
           (throw (ex-info "Field is required"
                           {:type :validation-error
                            :field field
                            :errors {field ["Field is required"]}})))

         ; Prepare single field update with updated-at timestamp
         (let [now-str (type-conversion/instant->string (Instant/now))
               update-data {field value :updated-at now-str}

               ; Convert typed values to database format
               db-ready-data (prepare-values-for-db update-data)

               ; Convert to snake_case for database
               db-data (case-conversion/kebab-case->snake-case-map db-ready-data)

               ; Convert ID to string
               id-str (type-conversion/uuid->string id)

               ; Determine which table owns this field (handles split-table entities)
               split-cfg (:split-table-update entity-config)
               effective-table (if (and split-cfg
                                        (contains? (:secondary-fields split-cfg) field))
                                 (:secondary-table split-cfg)
                                 table-name)

               ; Execute update against the correct table
               update-query {:update effective-table
                             :set db-data
                             :where [:= primary-key id-str]}
               _ (db/execute-update! db-ctx update-query)

               ; Fetch updated record using join-aware query
               {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
               id-field (get field-aliases :id primary-key)
               select-query (cond-> {:select select-clause
                                     :from from-clause
                                     :where [:= id-field id-str]}
                              join-clause (assoc :join join-clause))
               db-result (db/execute-one! db-ctx select-query)]

           ; Keys arrive kebab-case from the execution layer builder-fn
           db-result)))
     db-ctx))

  (delete-entity [_ entity-name id]
    (persist-interceptors/execute-persistence-operation
     :admin-delete-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             soft-delete? (:soft-delete entity-config false)
             ;; Convert UUID to string for PostgreSQL compatibility
             id-str (type-conversion/uuid->string id)
             {:keys [soft-delete-table]} (resolve-query-config entity-config)
             split-cfg (:split-table-update entity-config)]

         (if soft-delete?
           ;; Soft delete: Set deleted-at timestamp and optionally active=false
           (let [now-str (type-conversion/instant->string (Instant/now))
                 has-active-field? (contains? (:fields entity-config) :active)
                 secondary-fields (when split-cfg (:secondary-fields split-cfg #{}))]

             (if (and split-cfg secondary-fields (contains? secondary-fields :active) has-active-field?)
               ;; Split-table: active lives on secondary table, deleted_at may be on both
               (db/with-transaction* db-ctx
                 (fn [tx]
                   (let [secondary-table (:secondary-table split-cfg)
                         ;; Secondary table gets active=false + deleted_at
                         secondary-data (case-conversion/kebab-case->snake-case-map
                                         {:deleted-at now-str :active false})
                         ;; Primary table gets deleted_at only
                         primary-data (case-conversion/kebab-case->snake-case-map
                                       {:deleted-at now-str})]
                     (db/execute-update! tx {:update secondary-table
                                             :set    secondary-data
                                             :where  [:= primary-key id-str]})
                     (pos? (db/execute-update! tx {:update table-name
                                                   :set    primary-data
                                                   :where  [:= primary-key id-str]})))))
               ;; Non-split: update single table
               (let [soft-delete-data-kebab (cond-> {:deleted-at now-str}
                                              has-active-field? (assoc :active false))
                     soft-delete-data (case-conversion/kebab-case->snake-case-map soft-delete-data-kebab)
                     query {:update soft-delete-table
                            :set soft-delete-data
                            :where [:= primary-key id-str]}]
                 (pos? (db/execute-update! db-ctx query)))))

           ;; Hard delete: Permanent removal
           (let [query {:delete-from table-name
                        :where [:= primary-key id-str]}]
             (pos? (db/execute-update! db-ctx query))))))
     db-ctx))

  (count-entities [_ entity-name filters]
    (persist-interceptors/execute-persistence-operation
     :admin-count-entities
     {:entity (name entity-name)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             soft-delete? (:soft-delete entity-config false)
             {:keys [from-clause join-clause field-aliases]} (resolve-query-config entity-config)
             soft-delete-field (get field-aliases :deleted-at :deleted_at)
             filter-where (build-filter-where filters)
             ; Exclude soft-deleted records if entity uses soft delete
             soft-delete-where (when soft-delete? [:= soft-delete-field nil])
             where-clause (combine-where-clauses [filter-where soft-delete-where])
             query (cond-> {:select [[:%count.* :total]]
                            :from from-clause}
                     join-clause  (assoc :join join-clause)
                     where-clause (assoc :where where-clause))
             result (db/execute-one! db-ctx query)]
         (:total result 0)))
     db-ctx))

  (validate-entity-data [_ entity-name data]
    ; Week 1: Simple validation - check required fields present
    ; Week 2+: Full Malli schema validation
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          fields (:fields entity-config)
          readonly-fields (set (:readonly-fields entity-config))
          errors (reduce-kv
                  (fn [errs field-name field-config]
                    ; Skip validation for readonly fields (auto-generated by database)
                    ; Boolean fields default to false when absent (unchecked checkbox)
                    (if (and (:required field-config)
                             (not (contains? readonly-fields field-name))
                             (not= (:type field-config) :boolean)
                             (nil? (get data field-name)))
                      (assoc errs field-name ["Field is required"])
                      errs))
                  {}
                  fields)]
      (if (empty? errors)
        {:valid? true :data data}
        {:valid? false :errors errors})))

  (bulk-delete-entities [_ entity-name ids]
    (persist-interceptors/execute-persistence-operation
     :admin-bulk-delete-entities
     {:entity (name entity-name) :count (count ids)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             soft-delete? (:soft-delete entity-config false)
             {:keys [soft-delete-table]} (resolve-query-config entity-config)
             split-cfg (:split-table-update entity-config)

             ;; Convert UUIDs to strings at database boundary
             id-strings (mapv type-conversion/uuid->string ids)
             now-str (type-conversion/instant->string (Instant/now))

             has-active-field? (contains? (:fields entity-config) :active)
             secondary-fields (when split-cfg (:secondary-fields split-cfg #{}))

             affected-count
             (if (and soft-delete? split-cfg secondary-fields
                      (contains? secondary-fields :active) has-active-field?)
               ;; Split-table bulk soft-delete: update both tables
               (db/with-transaction* db-ctx
                 (fn [tx]
                   (let [secondary-table (:secondary-table split-cfg)
                         secondary-data (case-conversion/kebab-case->snake-case-map
                                         {:deleted-at now-str :active false})
                         primary-data (case-conversion/kebab-case->snake-case-map
                                       {:deleted-at now-str})]
                     (db/execute-update! tx {:update secondary-table
                                             :set    secondary-data
                                             :where  [:in primary-key id-strings]})
                     (db/execute-update! tx {:update table-name
                                             :set    primary-data
                                             :where  [:in primary-key id-strings]}))))
               ;; Non-split path
               (if soft-delete?
                 (let [soft-delete-data-kebab (cond-> {:deleted-at now-str}
                                                has-active-field? (assoc :active false))
                       soft-delete-data (case-conversion/kebab-case->snake-case-map soft-delete-data-kebab)]
                   (db/execute-update! db-ctx {:update soft-delete-table
                                               :set soft-delete-data
                                               :where [:in primary-key id-strings]}))
                 (db/execute-update! db-ctx {:delete-from table-name
                                             :where [:in primary-key id-strings]})))]

         {:success-count affected-count
          :failed-count (- (count ids) affected-count)
          :errors []}))
     db-ctx))

  (list-related-entities [_ _parent-entity-name parent-id relationship]
    (persist-interceptors/execute-persistence-operation
     :admin-list-related-entities
     {:parent-id parent-id :entity (name (:entity relationship))}
     (fn [{:keys [_params]}]
       (let [table   (:table relationship)
             fk-col  (case-conversion/kebab-case->snake-case-keyword (:foreign-key relationship))
             query   {:select [:*]
                      :from   [table]
                      :where  [:= fk-col (str parent-id)]}
             results (db/execute-query! db-ctx query)]
         results))
     db-ctx)))

;; =============================================================================
;; Factory Function
;; =============================================================================

(defn create-admin-service
  "Create new AdminService instance.

   Args:
     db-ctx: Database context map with :adapter and :datasource
     schema-provider: ISchemaProvider implementation
     logger: Logger instance for operation logging
     error-reporter: Error reporter for exception tracking
     config: Admin configuration map with pagination settings

   Returns:
     AdminService instance implementing IAdminService"
  [db-ctx schema-provider logger error-reporter config]
  (->AdminService db-ctx schema-provider logger error-reporter config))
