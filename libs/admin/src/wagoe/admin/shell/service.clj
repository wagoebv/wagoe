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
   - Handle soft/hard deletes, children included, as the config says"
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.platform.database :as db]
   [wagoe.platform.shell.persistence-interceptors :as persist-interceptors]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.utils.case-conversion :as case-conversion]
   [wagoe.admin.core.db-errors :as db-errors]
   [wagoe.admin.core.schema-introspection :as introspection]
   [wagoe.admin.core.forms :as forms]
   [wagoe.events.core.event :as event]
   [wagoe.events.ports :as events]
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Database Wagoe Helpers
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

(defn- not-null-violation
  "`e` as a :validation-error on the field whose NOT NULL constraint the
   database enforced, or nil when it is not such a violation (BOU-494)."
  [e]
  (when-let [column (some (comp db-errors/not-null-violation-column ex-message)
                          (take-while some? (iterate ex-cause e)))]
    (let [field (keyword (case-conversion/snake-case->kebab-case-string column))]
      (ex-info (str "Field is required: " (name field))
               {:type   :validation-error
                :field  field
                :errors {field ["Field is required"]}}
               e))))

(defn- foreign-key-violation
  "`e` as a :validation-error when the database refused a reference to a row
   that does not exist: on its field where the driver names the column, and
   without one where it does not (SQLite). Nil otherwise (BOU-540)."
  [e]
  (when-let [{:keys [column]} (some (comp db-errors/foreign-key-violation ex-message)
                                    (take-while some? (iterate ex-cause e)))]
    (let [field (some-> column case-conversion/snake-case->kebab-case-string keyword)]
      (ex-info (if field
                 (str "No such record: " (name field))
                 "A referenced record does not exist")
               (cond-> {:type :validation-error :errors {}}
                 field (assoc :field field :errors {field ["No such record"]}))
               e))))

;; =============================================================================
;; Children and deletes (BOU-563)
;; =============================================================================

(defn- child-config
  "The child's admin config, or nil when the child is only a table."
  [schema-provider relationship]
  (when (ports/validate-entity-exists schema-provider (:entity relationship))
    (ports/get-entity-config schema-provider (:entity relationship))))

(defn- related-query
  "The children of `parent-id` in `relationship`, read the way the child's own
   list reads them: through its :query-overrides and, unless
   `include-deleted?`, its soft delete. Returns the query without :select, and
   the child's id column."
  [schema-provider parent-id relationship & [include-deleted?]]
  (let [child-cfg (child-config schema-provider relationship)
        {:keys [from-clause select-clause join-clause field-aliases]}
        (if child-cfg
          (resolve-query-config child-cfg)
          {:from-clause [(:table relationship)] :select-clause [:*] :field-aliases {}})
        ;; Without an alias, qualify from :select: in a join a bare column
        ;; that both tables carry is ambiguous.
        qualify   (fn [field]
                    (let [col (case-conversion/kebab-case->snake-case-keyword field)]
                      (or (get field-aliases field)
                          (some #(when (and (keyword? %)
                                            (str/ends-with? (name %) (str "." (name col))))
                                   %)
                                select-clause)
                          col)))
        fk-where  [:= (qualify (:foreign-key relationship)) (str parent-id)]]
    {:child-cfg     child-cfg
     :select-clause select-clause
     :qualify       qualify
     :id-column     (qualify (:primary-key child-cfg :id))
     :query         (cond-> {:from  from-clause
                             :where (if (and (:soft-delete child-cfg false) (not include-deleted?))
                                      [:and fk-where [:= (qualify :deleted-at) nil]]
                                      fk-where)}
                      join-clause (assoc :join join-clause))}))

(defn- live-records
  "The live records among `ids`, in one query, read the way `get-entity`
   reads one."
  [db-ctx schema-provider entity-name ids]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)
        {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
        id-field      (get field-aliases :id (:primary-key entity-config :id))
        in-ids        [:in id-field (mapv type-conversion/uuid->string ids)]]
    (if (empty? ids)
      []
      (db/execute-query! db-ctx
                         (cond-> {:select select-clause
                                  :from   from-clause
                                  :where  (if (:soft-delete entity-config false)
                                            [:and in-ids [:= (get field-aliases :deleted-at :deleted_at) nil]]
                                            in-ids)}
                           join-clause (assoc :join join-clause))))))

(defn- fk-field [relationship]
  (keyword (str/replace (name (:foreign-key relationship)) "_" "-")))

(defn- minimums
  "The has-many entries with a :min whose children are `entity-name`, each
   with its parent's config. An entity whose config cannot be read has no say."
  [schema-provider entity-name]
  (for [parent (ports/list-available-entities schema-provider)
        :let   [parent-cfg (try (ports/get-entity-config schema-provider parent)
                                (catch clojure.lang.ExceptionInfo _ nil))]
        rel    (:has-many parent-cfg)
        :when  (and (= entity-name (:entity rel)) (pos-int? (:min rel)))]
    (assoc rel :parent-cfg parent-cfg)))

(defn- check-minimums!
  "Refuse to delete `records` of `entity-name` when that would leave a parent
   with fewer children than its has-many :min. Read before the delete, not
   in its transaction: two concurrent deletes can both pass."
  [db-ctx schema-provider entity-name records]
  (doseq [rel                       (minimums schema-provider entity-name)
          [parent-id doomed]        (group-by #(get % (fk-field rel)) records)
          :when                     (some? parent-id)
          :let                      [{:keys [query]} (related-query schema-provider parent-id rel)
                                     live (:total (db/execute-one! db-ctx (assoc query :select [[:%count.* :total]])) 0)
                                     left (- live (count doomed))]]
    (when (< left (:min rel))
      (throw (ex-info (str (:label rel (name entity-name)) ": at least " (:min rel)
                           " required per " (:label (:parent-cfg rel)) ", this would leave " (max left 0))
                      {:type        :conflict
                       :entity-name entity-name
                       :parent-id   parent-id
                       :min         (:min rel)})))))

(defn- on-delete
  "What a delete of the parent does to these children: refuse while they
   exist, unless the has-many says `:on-delete :cascade`. A has-many written
   only to show children, or detected from a foreign key (users of a tenant,
   who may belong to others), must not start deleting them."
  [relationship]
  (:on-delete relationship :restrict))

(defn- assert-unrestricted!
  "Refuse to delete `ids` of `entity-name` while a restricting has-many has
   live children, naming the child and how many."
  [db-ctx schema-provider entity-name ids]
  (let [cfg (ports/get-entity-config schema-provider entity-name)]
    (doseq [rel   (:has-many cfg)
            :when (= :restrict (on-delete rel))
            :let  [n (reduce + (for [id ids
                                     :let [{:keys [query]} (related-query schema-provider (str id) rel)]]
                                 (:total (db/execute-one! db-ctx (assoc query :select [[:%count.* :total]])) 0)))]
            :when (pos? n)]
      (throw (ex-info (str "Cannot delete " (:label cfg (name entity-name)) ": "
                           n " " (:label rel (name (:entity rel))) " still refer to it")
                      {:type        :conflict
                       :entity-name entity-name
                       :child       (:entity rel)
                       :count       n})))))

(defn- soft-deletable? [entity-config]
  (or (:soft-delete entity-config false) (contains? (:fields entity-config) :deleted-at)))

(defn- delete-row!
  "Delete one record, soft or hard. Returns true when a row changed."
  [tx entity-config id-str soft? now-str]
  (let [table-name  (:table-name entity-config)
        primary-key (:primary-key entity-config :id)
        {:keys [soft-delete-table]} (resolve-query-config entity-config)
        split-cfg   (:split-table-update entity-config)
        has-active? (contains? (:fields entity-config) :active)
        secondary   (when split-cfg (:secondary-fields split-cfg #{}))
        by-id       [:= primary-key id-str]
        update!     (fn [table data]
                      (db/execute-update! tx {:update table
                                              :set    (case-conversion/kebab-case->snake-case-map data)
                                              :where  by-id}))]
    (cond
      (not soft?)
      (pos? (db/execute-update! tx {:delete-from table-name :where by-id}))

      ;; Split-table: active lives on the secondary table, deleted_at on both.
      (and split-cfg (contains? secondary :active) has-active?)
      (do (update! (:secondary-table split-cfg) {:deleted-at now-str :active false})
          (pos? (update! table-name {:deleted-at now-str})))

      :else
      (pos? (update! soft-delete-table (cond-> {:deleted-at now-str} has-active? (assoc :active false)))))))

;; One delete as it runs: the transaction, whether it is soft, the time it
;; stamps, whether to read each row before deleting it (for the events), and
;; the rows already visited.
(defrecord ^:private Deletion [tx soft? now-str priors? seen])

(defn- delete-tree!
  "Delete `id` of `entity-name` and, first, its cascading has-many children,
   which follow the parent: removed with a hard delete, given a deleted_at with
   a soft one. A restricting has-many with live children refuses it, and the
   transaction rolls back what was already deleted. A soft delete leaves a
   child with no deleted_at column as it is, since its parent row stays.
   Returns {:entity :id :prior} per row removed; :prior only with priors?."
  [{:keys [tx soft? now-str priors? seen] :as deletion} schema-provider entity-name id]
  (let [cfg    (ports/get-entity-config schema-provider entity-name)
        id-str (str id)]
    (if (or (@seen [entity-name id-str]) (and soft? (not (soft-deletable? cfg))))
      []
      (do
        (vswap! seen conj [entity-name id-str])
        (assert-unrestricted! tx schema-provider entity-name [id-str])
        (let [children (doall
                        (mapcat (fn [rel]
                                  (let [{:keys [child-cfg query id-column]}
                                        (related-query schema-provider id-str rel (not soft?))]
                                    (cond
                                      child-cfg
                                      (mapcat #(delete-tree! deletion schema-provider (:entity rel) (:child-id %))
                                              (db/execute-query! tx (assoc query :select [[id-column :child-id]])))

                                      (not soft?)
                                      (do (db/execute-update! tx {:delete-from (:table rel) :where (:where query)})
                                          [])

                                      :else [])))
                                (filter #(= :cascade (on-delete %)) (:has-many cfg))))
              ;; Read in the transaction, just before the row goes: exactly
              ;; the row that is deleted, which a read before it cannot promise.
              prior    (when priors? (first (live-records tx schema-provider entity-name [id-str])))]
          (if (delete-row! tx cfg id-str soft? now-str)
            (conj (vec children) {:entity entity-name :id id-str :prior prior})
            children))))))

(defn- remove-workflows!
  "Remove the workflow instances of the `removed` rows whose entity has a
   workflow, in the delete's transaction: a failure rolls the delete back. A
   store on another datasource removes them outside it (see the port)."
  [workflows db-ctx tx schema-provider removed]
  (doseq [{:keys [entity id]} removed
          :let  [entity-type (get-in (ports/get-entity-config schema-provider entity) [:workflow :entity-type])]
          :when entity-type]
    (ports/remove-entity-workflows! workflows (assoc tx :transaction-of (:datasource db-ctx))
                                    entity-type (parse-uuid id))))

(defn- delete-records!
  "Delete `ids` of `entity-name` with their children, in one transaction.
   Returns {:entity :id :prior} per row removed, children included. Workflow
   instances go only with a hard delete: a soft one can be undone, and their
   history with it."
  [db-ctx schema-provider workflows entity-name ids & [priors?]]
  (let [soft? (:soft-delete (ports/get-entity-config schema-provider entity-name) false)]
    (db/with-transaction* db-ctx
      (fn [tx]
        (let [deletion (->Deletion tx soft? (type-conversion/instant->string (Instant/now))
                                   priors? (volatile! #{}))
              removed  (vec (mapcat #(delete-tree! deletion schema-provider entity-name %) ids))]
          (when (and workflows (not soft?))
            (remove-workflows! workflows db-ctx tx schema-provider removed))
          removed)))))

(defn- roots-removed
  "How many of `ids` are among the `removed` rows."
  [entity-name ids removed]
  (count (filter (set (map (juxt :entity :id) removed))
                 (distinct (map #(vector entity-name (str %)) ids)))))

;; =============================================================================
;; Admin Service Implementation
;; =============================================================================

(defprotocol ^:private IRemovingDelete
  ;; Not a port: how PublishingAdminService learns every row a delete took,
  ;; cascaded children included, to publish one event for each.
  (delete-removing [this entity-name ids]
    "Delete like bulk-delete-entities; return {:entity :id :prior} per row removed."))

(defn- checked-delete!
  "Refuse by :min or a restricting has-many, then delete. The refusals are
   checked before the operation, like the split-table create check, so they
   are not logged as failed database operations."
  [{:keys [db-ctx schema-provider workflows]} entity-name ids priors?]
  (check-minimums! db-ctx schema-provider entity-name
                   (live-records db-ctx schema-provider entity-name ids))
  (assert-unrestricted! db-ctx schema-provider entity-name ids)
  (persist-interceptors/execute-persistence-operation
   :admin-delete-entities
   {:entity (name entity-name) :count (count ids)}
   (fn [{:keys [_params]}]
     (delete-records! db-ctx schema-provider workflows entity-name ids priors?))
   db-ctx))

;; =============================================================================
;; Creates
;; =============================================================================

(defn- insert-record!
  "Insert `data` as a new row of `entity-config` on `conn` (a db-ctx or a
   transaction) and return the row as read back."
  [conn entity-config data]
  (let [table-name    (:table-name entity-config)
        primary-key   (:primary-key entity-config :id)
        entity-fields (:fields entity-config)
        ;; Only read-only fields are removed: :hide-fields are for display,
        ;; and a request may still supply them. An empty field whose column
        ;; has a default is left out, so the database fills it: an explicit
        ;; NULL would not get the default (BOU-570).
        sanitized     (into {}
                            (remove (fn [[k v]]
                                      (and (nil? v) (some? (get-in entity-fields [k :default-value])))))
                            (apply dissoc data (:readonly-fields entity-config #{})))
        now-str       (type-conversion/instant->string (Instant/now))
        id-str        (type-conversion/uuid->string (UUID/randomUUID))
        prepared      (cond-> (assoc sanitized :id id-str)
                        (contains? entity-fields :created-at) (assoc :created-at now-str)
                        (contains? entity-fields :updated-at) (assoc :updated-at now-str))
        db-data       (case-conversion/kebab-case->snake-case-map (prepare-values-for-db prepared))]
    ;; Without RETURNING, for H2.
    (try
      (db/execute-one! conn {:insert-into table-name :values [db-data]})
      (catch Exception e
        (throw (or (not-null-violation e) (foreign-key-violation e) e))))
    (db/execute-one! conn {:select [:*] :from [table-name] :where [:= primary-key id-str]})))

(defn- nested-relationships
  "The has-many entries of `entity-config` created with it, as
   `forms/nested-relationships` finds them."
  [schema-provider entity-config]
  (forms/nested-relationships entity-config
                              (into {} (for [rel (:has-many entity-config)]
                                         [(:entity rel) (child-config schema-provider rel)]))))

(defn- assert-children-acceptable!
  "Refuse children of an entity the parent does not create them for, and a
   has-many given fewer rows than its :min. Checked before the operation, so
   a refusal is not logged as a failed database operation."
  [entity-name rels children]
  (let [by-entity (into {} (map (juxt :entity identity)) rels)
        unknown   (vec (remove by-entity (keys children)))
        short     (into {} (for [rel  rels
                                 :let [n (count (get children (:entity rel)))]
                                 :when (< n (:min rel))]
                             [(:entity rel) {:min (:min rel) :count n :label (:label rel)}]))]
    (when (seq unknown)
      (throw (ex-info (str "Not created with " (name entity-name) ": " (str/join ", " (map name unknown)))
                      {:type :validation-error :errors {} :entities unknown})))
    (when (seq short)
      (throw (ex-info (str/join "; " (for [[_ {:keys [label min count]}] short]
                                       (str label ": at least " min " required, " count " given")))
                      {:type :validation-error :errors {} :too-few short})))
    by-entity))

(defn- insert-children!
  "Insert each row of `children` on `tx` with `parent-id` as its foreign key.
   A refused row says which it was."
  [tx by-entity parent-id children]
  (vec (for [[entity rows] children
             [index row]   (map-indexed vector rows)
             :let [{:keys [entity-config fk]} (by-entity entity)]]
         {:entity entity
          :record (try
                    (insert-record! tx entity-config (assoc row fk parent-id))
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (ex-message e)
                                      (assoc (ex-data e) :child {:entity entity :index index})
                                      e))))})))

(defrecord AdminService [db-ctx schema-provider logger error-reporter config workflows]
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
       (insert-record! db-ctx (ports/get-entity-config schema-provider entity-name) data))
     db-ctx))

  (create-entity-with-children [_ entity-name data children]
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          by-entity     (assert-children-acceptable! entity-name
                                                     (nested-relationships schema-provider entity-config)
                                                     children)]
      (persist-interceptors/execute-persistence-operation
       :admin-create-entity-with-children
       {:entity (name entity-name)}
       (fn [{:keys [_params]}]
         (db/with-transaction* db-ctx
           (fn [tx]
             (let [parent (insert-record! tx entity-config data)]
               {:record   parent
                :children (insert-children! tx by-entity
                                            (get parent (:primary-key entity-config :id))
                                            children)}))))
       db-ctx)))

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

             _ (try
                 (if split-cfg
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
                 (catch Exception e
                   (throw (or (not-null-violation e) (foreign-key-violation e) e))))

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
               _ (try
                   (db/execute-update! db-ctx update-query)
                   (catch Exception e
                     (throw (or (not-null-violation e) (foreign-key-violation e) e))))

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

  (delete-entity [this entity-name id]
    (pos? (roots-removed entity-name [id] (checked-delete! this entity-name [id] false))))

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
          ;; Without its primary key the record is one a create is about to
          ;; write, and the database fills what it has a default for (BOU-570).
          entity-config (cond-> entity-config
                          (nil? (get data (:primary-key entity-config :id)))
                          introspection/for-create)
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

  (bulk-delete-entities [this entity-name ids]
    (let [deleted (roots-removed entity-name ids (checked-delete! this entity-name ids false))]
      {:success-count deleted
       :failed-count  (- (count ids) deleted)
       :errors        []}))

  (list-related-entities [_ _parent-entity-name parent-id relationship]
    (persist-interceptors/execute-persistence-operation
     :admin-list-related-entities
     {:parent-id parent-id :entity (name (:entity relationship))}
     (fn [{:keys [_params]}]
       (let [{:keys [child-cfg select-clause qualify query]} (related-query schema-provider parent-id relationship)]
         (db/execute-query! db-ctx
                            (cond-> (assoc query
                                           :select   select-clause
                                           ;; The child list's own order, so a limited
                                           ;; panel is that list's first page.
                                           :order-by [[(qualify (:default-sort child-cfg :id)) :asc]])
                              (:limit relationship) (assoc :limit (:limit relationship))))))
     db-ctx)))

(extend-type AdminService
  IRemovingDelete
  (delete-removing [this entity-name ids]
    (checked-delete! this entity-name ids true)))

;; =============================================================================
;; Lifecycle events (BOU-492)
;; =============================================================================

(defn- publish-lifecycle!
  "Publish `type` for a write that has committed.

   A failure is logged, not raised: the row is already written, and a 500
   would tell the user it was not. Hidden fields stay out of the payload,
   since an event can leave the process. Returns true when published."
  [publisher schema-provider type entity-name id attrs & [prior]]
  (let [hidden  (:hide-fields (ports/get-entity-config schema-provider entity-name))
        strip   #(apply dissoc % hidden)
        payload (cond-> {:entity entity-name :id id :attrs (strip attrs)}
                  prior (assoc :prior (strip prior)))
        result  (try
                  (events/publish! publisher :admin
                                   (event/event {:id           (random-uuid)
                                                 :type         type
                                                 :source       :admin
                                                 :payload      payload
                                                 :published-at (Instant/now)}))
                  (catch Exception e
                    {:error {:type :events/publish-failed :message (ex-message e)}}))]
    (if (:error result)
      (log/warn "admin lifecycle event not published"
                {:type type :entity entity-name :id id :error (:error result)})
      true)))

(defn- publish-deleted!
  "One deleted event per prior, stopping at the first failure: each can cost
   the broker's timeout on the request thread, so an outage during a bulk
   delete would otherwise hold it for a timeout per row."
  [publisher schema-provider entity-name primary-key priors]
  (loop [[prior & more] priors]
    (when prior
      (if (publish-lifecycle! publisher schema-provider :admin/entity-deleted
                              entity-name (get prior primary-key) prior)
        (recur more)
        (when (seq more)
          (log/warn "admin lifecycle events skipped after a failed publish"
                    {:entity entity-name :skipped (count more)}))))))

(defn- publish-removed!
  "One deleted event per removed row, cascaded children included (BOU-563),
   stopping at the first failure like publish-deleted!."
  [publisher schema-provider removed]
  (loop [[{:keys [entity id prior]} & more] (filter :prior removed)]
    (when prior
      (let [pk (:primary-key (ports/get-entity-config schema-provider entity) :id)]
        (if (publish-lifecycle! publisher schema-provider :admin/entity-deleted
                                entity (or (get prior pk) (parse-uuid id)) prior)
          (recur more)
          (when (seq more)
            (log/warn "admin lifecycle events skipped after a failed publish"
                      {:entity entity :skipped (count more)})))))))

(defn- delete-entity-then-publish
  "For an inner service that cannot say what it removed: the record read
   before, published when the delete reports success."
  [inner schema-provider publisher entity-name id]
  (let [prior    (ports/get-entity inner entity-name id)
        deleted? (ports/delete-entity inner entity-name id)]
    (when (and deleted? prior)
      (publish-lifecycle! publisher schema-provider :admin/entity-deleted
                          entity-name id prior))
    deleted?))

(defn- bulk-delete-then-publish
  [inner db-ctx schema-provider publisher entity-name ids]
  ;; The adapter returns a count, not which rows it deleted. When that is
  ;; not the number read, another request got to some of them first and
  ;; publishes those itself, so nothing is published here rather than a
  ;; duplicate.
  (let [priors  (live-records db-ctx schema-provider entity-name ids)
        result  (ports/bulk-delete-entities inner entity-name ids)
        deleted (:success-count result 0)]
    (cond
      (and (pos? deleted) (= deleted (count priors)))
      (publish-deleted! publisher schema-provider entity-name
                        (:primary-key (ports/get-entity-config schema-provider entity-name) :id)
                        priors)

      (pos? deleted)
      (log/warn "admin lifecycle events skipped: bulk delete count differs from the records read"
                {:entity entity-name :deleted deleted :read (count priors)}))
    result))

(defrecord PublishingAdminService [inner db-ctx schema-provider publisher]
  ;; Wraps the service only when a bus is configured, so an application
  ;; without one runs exactly the code it did before. Each write publishes
  ;; after it returns, which is after it committed. Reads for :prior happen
  ;; only here, for the same reason.
  ports/IAdminService
  (list-entities [_ entity-name options] (ports/list-entities inner entity-name options))
  (get-entity [_ entity-name id] (ports/get-entity inner entity-name id))
  (count-entities [_ entity-name filters] (ports/count-entities inner entity-name filters))
  (validate-entity-data [_ entity-name data] (ports/validate-entity-data inner entity-name data))
  (list-related-entities [_ parent-entity-name parent-id relationship]
    (ports/list-related-entities inner parent-entity-name parent-id relationship))

  (create-entity [_ entity-name data]
    (let [record (ports/create-entity inner entity-name data)]
      (publish-lifecycle! publisher schema-provider :admin/entity-created
                          entity-name (:id record) record)
      record))

  (create-entity-with-children [_ entity-name data children]
    (let [{:keys [record] :as created} (ports/create-entity-with-children inner entity-name data children)]
      (publish-lifecycle! publisher schema-provider :admin/entity-created
                          entity-name (:id record) record)
      (doseq [{child :entity child-record :record} (:children created)]
        (publish-lifecycle! publisher schema-provider :admin/entity-created
                            child (:id child-record) child-record))
      created))

  (update-entity [_ entity-name id data]
    (let [prior  (ports/get-entity inner entity-name id)
          record (ports/update-entity inner entity-name id data)]
      (when record
        (publish-lifecycle! publisher schema-provider :admin/entity-updated
                            entity-name id record prior))
      record))

  (update-entity-field [_ entity-name id field value]
    (let [prior  (ports/get-entity inner entity-name id)
          record (ports/update-entity-field inner entity-name id field value)]
      (when record
        (publish-lifecycle! publisher schema-provider :admin/entity-updated
                            entity-name id record prior))
      record))

  (delete-entity [_ entity-name id]
    (if (satisfies? IRemovingDelete inner)
      (let [removed (delete-removing inner entity-name [id])]
        (publish-removed! publisher schema-provider removed)
        (pos? (roots-removed entity-name [id] removed)))
      (delete-entity-then-publish inner schema-provider publisher entity-name id)))

  (bulk-delete-entities [_ entity-name ids]
    (if (satisfies? IRemovingDelete inner)
      (let [removed (delete-removing inner entity-name ids)
            deleted (roots-removed entity-name ids removed)]
        (publish-removed! publisher schema-provider removed)
        {:success-count deleted :failed-count (- (count ids) deleted) :errors []})
      (bulk-delete-then-publish inner db-ctx schema-provider publisher entity-name ids))))

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
     event-publisher: optional wagoe.events IEventPublisher (BOU-492)
     workflows: optional IEntityWorkflows; deletes remove workflow instances (BOU-563)

   Returns:
     AdminService instance implementing IAdminService"
  ([db-ctx schema-provider logger error-reporter config]
   (create-admin-service db-ctx schema-provider logger error-reporter config nil))
  ([db-ctx schema-provider logger error-reporter config event-publisher]
   (create-admin-service db-ctx schema-provider logger error-reporter config event-publisher nil))
  ([db-ctx schema-provider logger error-reporter config event-publisher workflows]
   (cond-> (->AdminService db-ctx schema-provider logger error-reporter config workflows)
     event-publisher (->PublishingAdminService db-ctx schema-provider event-publisher))))
