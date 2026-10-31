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
   [wagoe.platform.ports.database :as db-ports]
   [wagoe.platform.database :as db]
   [wagoe.platform.shell.persistence-interceptors :as persist-interceptors]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.utils.case-conversion :as case-conversion]
   [wagoe.admin.core.db-errors :as db-errors]
   [wagoe.admin.core.schema-introspection :as introspection]
   [wagoe.admin.core.display :as display]
   [wagoe.admin.core.forms :as forms]
   [wagoe.admin.core.ui.filters :as filters]
   [wagoe.events.core.event :as event]
   [wagoe.events.ports :as events]
   [wagoe.platform.core.scope :as scope]
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Database Wagoe Helpers
;; =============================================================================

(defn prepare-values-for-db
  "`m` with its UUIDs as strings. An Instant is left for the adapter to encode
   (BOU-655)."
  [m]
  (when m
    (update-vals m #(if (instance? UUID %) (type-conversion/uuid->string %) %))))

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
     adapter: the database's DBAdapter, whose `like` ignores case on every engine
     search-term: String to search for
     search-fields: Vector of field keywords to search in

   Returns:
     HoneySQL WHERE clause (nil if no search term)

   Example:
     (build-search-where postgres-adapter john [:email :name])
     => [:or (like adapter :email :contains john)
             (like adapter :name :contains john)]"
  [adapter search-term search-fields]
  (when (and search-term (seq search-fields))
    (vec (cons :or
               (mapv #(db-ports/like adapter % :contains search-term) search-fields)))))

(defn build-filter-where
  "Build WHERE clause for field-specific filters.

   Week 1: Simple equality filters
   Week 2: Advanced operators (gt, lt, contains, in, between, etc.)

   Args:
     adapter: the database's DBAdapter; text operators ignore case through its `like`
     filters: Map of field -> value (Week 1) or field -> filter-map (Week 2)
              Week 1: {:role :admin :active true}
              Week 2: {:created-at {:op :gte :value \"2024-01-01\"}
                       :status {:op :in :values [:active :pending]}}

   Returns:
     HoneySQL WHERE clause (nil if no filters)

   Example:
     (build-filter-where adapter {:role :admin :active true})
     ;=> [:and [:= :role :admin] [:= :active true]]

     (build-filter-where adapter {:price {:op :gte :value 100}
                          :status {:op :in :values [:active :pending]}})
     ;=> [:and [:>= :price 100] [:in :status [:active :pending]]]"
  [adapter filters]
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
                                (:contains :starts-with :ends-with) (db-ports/like adapter field op value)
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

(defn- own-columns
  "The select of an entity read straight from its table: `*`, or with a
   binary column the columns by name, so its content is never read. A binary
   field reads as its size, when in `sized` (nil: every one), else not at all."
  [entity-config sized]
  (let [binary (into #{} (keep (fn [[f cfg]] (when (introspection/upload-field? cfg) f)))
                     (:fields entity-config))
        cols   (:table-columns entity-config)]
    (if (or (empty? binary) (empty? cols))
      [:*]
      (vec (for [field cols
                 :let  [col (case-conversion/kebab-case->snake-case-keyword field)]
                 :when (or (not (binary field)) (nil? sized) (contains? sized field))]
             (if (binary field) [[:octet_length col] col] col))))))

(defn- resolve-query-config
  "Extract query configuration from entity-config, applying :query-overrides if present.
   Entities without :query-overrides behave exactly as before, except that a
   binary column reads as its size: only for the fields in `sized` when given.

   For split-table entities: ensures the select clause covers ALL known fields,
   not just those explicitly listed.  When :split-table-update is configured and
   a :join is present, any field in :fields that is missing from the explicit
   :select is appended using the appropriate table alias so the admin UI always
   sees every editable column."
  [entity-config & [sized]]
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
                        (or raw-select
                            (if (:join overrides) [:*] (own-columns entity-config sized))))]
    {:from-clause       (or (:from overrides) [table-name])
     :select-clause     select-clause
     :join-clause       (:join overrides)
     :field-aliases     (:field-aliases overrides {})
     :soft-delete-table (or (:soft-delete-table overrides) table-name)}))

;; =============================================================================
;; Many-to-many (BOU-778)
;; =============================================================================

(defn- column-of
  "field → its column in a query read through `qc`: its alias, else the
   qualified column :select names for it, else the bare column. In a join a
   bare column that both tables carry is ambiguous."
  [{:keys [select-clause field-aliases]}]
  (fn [field]
    (let [col (case-conversion/kebab-case->snake-case-keyword field)]
      (or (get field-aliases field)
          (some #(when (and (keyword? %)
                            (str/ends-with? (name %) (str "." (name col))))
                   %)
                select-clause)
          col))))

(defn- qualified-column
  "`column-of`, with a bare column qualified by its table or alias, so it
   cannot mean a join table's column of the same name."
  [{:keys [from-clause] :as qc}]
  (let [from  (first from-clause)
        owner (name (if (vector? from) (second from) from))
        col   (column-of qc)]
    (fn [field]
      (let [c (col field)]
        (if (str/includes? (name c) ".") c (keyword (str owner "." (name c))))))))

(defn- link-column
  "Column `field` of the join table, aliased `lnk` in link queries."
  [field]
  (keyword (str "lnk." (case-conversion/kebab-case->snake-case-string (name field)))))

(defn- link-exists
  "Rows of `entity-config` that relation `rel` links to `id`."
  [entity-config rel id]
  (let [own ((qualified-column (resolve-query-config entity-config)) (:primary-key entity-config :id))]
    [:exists {:select [1]
              :from   [[(:table rel) :lnk]]
              :where  [:and [:= (link-column (:foreign-key rel)) own]
                       [:= (link-column (:other-key rel)) (str id)]]}]))

(defn- link-filters
  "`filters` as [plain-filters link-conditions]: a filter on a many-to-many
   key, \"has X\" (:has) or \"has no X\" (:has-not), becomes an EXISTS on its
   join table."
  [entity-config filters]
  (reduce-kv (fn [[plain conds] field spec]
               (if-let [rel (display/link-rel entity-config field)]
                 (let [{:keys [op value]} (if (map? spec) spec {:op :has :value spec})]
                   [plain (cond-> conds
                            (not (str/blank? (str value)))
                            (conj (let [exists (link-exists entity-config rel value)]
                                    (if (= :has-not (keyword op)) [:not exists] exists))))])
                 [(assoc plain field spec) conds]))
             [{} []]
             (or filters {})))

(defn- own-rows
  "Relation condition `f` as the rows of the entity's own table it admits, so
   its bare columns mean that table's even where the query joins another. A
   condition of nil admits nothing, not every row."
  [entity-config f]
  (let [pk     (:primary-key entity-config :id)
        pk-col (case-conversion/kebab-case->snake-case-keyword pk)
        own-pk ((qualified-column (resolve-query-config entity-config)) pk)]
    (fn [values]
      [:in own-pk {:select [pk-col] :from [(:table-name entity-config)] :where (or (f values) [:= 1 0])}])))

(defn- scope-where
  "The rows inside `scopes` (ADR-041 §4), each key's field under the entity's
   `:scope` as `column` gives it, or its relation condition."
  [entity-config column scopes]
  (scope/scope-filter scopes (update-vals (:scope entity-config)
                                          #(cond (keyword? %) (column %)
                                                 (fn? %)      (own-rows entity-config %)
                                                 :else        %))))

(defn- list-where
  "The WHERE a list applies for `options`: its search, its filters and, for a
   soft-deleting entity, live rows only. The overview's facet and summary
   queries use it too, so they count exactly the rows the list shows.
   `:scopes` keeps the rows inside them, `:ids` those records."
  [adapter entity-config qc options]
  (let [field-aliases (:field-aliases qc)
        col           (column-of qc)
        search-fields (mapv #(get field-aliases % %) (:search-fields entity-config))
        ;; A filter the entity does not offer is dropped, not run
        allowed       (into {} (filter #(filters/queryable-field? entity-config :filterable (key %)))
                            (:filters options))
        [plain links] (link-filters entity-config allowed)
        filters       (into {} (map (fn [[field spec]] [(col field) spec])) plain)]
    (combine-where-clauses
     (concat
      [(build-search-where adapter (:search options) search-fields)
       (build-filter-where adapter filters)
       (when (:soft-delete entity-config false)
         [:= (get field-aliases :deleted-at :deleted_at) nil])
       (when-let [scopes (:scopes options)]
         (scope-where entity-config #(get field-aliases % %) scopes))
       (when-let [ids (:ids options)]
         (if (seq ids)
           [:in (get field-aliases :id (:primary-key entity-config :id))
            (mapv type-conversion/uuid->string ids)]
           [:= 1 0]))]
      links))))

;; =============================================================================
;; Split-Table Update Helpers
;; =============================================================================

(defn- split-update-data
  "Partition prepared-data into primary-table and secondary-table maps.
   Each half gets an updated-at timestamp when the entity config declares one.
   Halves with no fields are returned as empty maps (callers should skip empty UPDATEs)."
  [prepared-data secondary-fields entity-fields now]
  (let [secondary-data (select-keys prepared-data secondary-fields)
        primary-data   (apply dissoc prepared-data secondary-fields)
        add-ts         (fn [m]
                         (cond-> m
                           (and (seq m) (contains? entity-fields :updated-at))
                           (assoc :updated-at now)))]
    {:primary-data   (add-ts primary-data)
     :secondary-data (add-ts secondary-data)}))

(defn- not-null-violation
  "`e` as a :validation-error on the field whose NOT NULL constraint the
   database enforced, or nil when it is not such a violation (BOU-494). The
   platform's :field first, the message for writes that bypass it (BOU-628)."
  [e]
  (let [chain (take-while some? (iterate ex-cause e))]
    (when-let [field (or (some #(let [{:keys [constraint field]} (ex-data %)]
                                  (when (= :not-null constraint) field))
                               chain)
                         (some-> (some (comp db-errors/not-null-violation-column ex-message) chain)
                                 case-conversion/snake-case->kebab-case-string
                                 keyword))]
      (ex-info (str "Field is required: " (name field))
               {:type   :validation-error
                :field  field
                :errors {field ["Field is required"]}}
               e))))

(defn- no-such-record
  "The :validation-error for a reference to a missing row, on `field` if known."
  [field e]
  (ex-info (if field (str "No such record: " (name field)) "A referenced record does not exist")
           (cond-> {:type :validation-error :errors {}}
             field (assoc :field field :errors {field ["No such record"]}))
           e))

(defn- foreign-key-violation
  "`e` as a :validation-error when the database refused a reference to a row
   that does not exist: on its field where the driver names the column, and
   without one where it does not (SQLite). Nil otherwise (BOU-540).

   The platform's :conflict first: PostgreSQL names the column only in a Detail
   its message no longer carries (BOU-626). The message is read for writes
   that did not go through the platform's execute functions."
  [e]
  (let [{:keys [type constraint field]} (ex-data e)]
    (if (and (= :conflict type) (= :foreign-key constraint))
      (no-such-record field e)
      (when-let [{:keys [column]} (some (comp db-errors/foreign-key-violation ex-message)
                                        (take-while some? (iterate ex-cause e)))]
        (no-such-record (some-> column case-conversion/snake-case->kebab-case-string keyword) e)))))

(defn- unique-violation
  "`e` as a :validation-error on the field whose unique key the database
   enforced, as the platform names it; nil otherwise (BOU-590)."
  [e]
  (let [{:keys [type constraint field]} (ex-data e)]
    (when (and (= :conflict type) (= :unique constraint) field)
      (ex-info (str "Already taken: " (name field))
               {:type   :validation-error
                :field  field
                :errors {field ["Another record already has this value"]}}
               e))))

(defn- check-violation
  "`e` as a :validation-error on the field whose CHECK constraint the database
   enforced, when the platform names one of the entity's; nil otherwise, so a
   CHECK on no one field stays a :conflict (BOU-734)."
  [entity-config e]
  (let [{:keys [type constraint field]} (ex-data e)]
    (when (and (= :conflict type) (= :check constraint) (contains? (:fields entity-config) field))
      (ex-info (str "Not an allowed value: " (name field))
               {:type :validation-error :field field :errors {field ["Not an allowed value"]}}
               e))))

(defn- refused-write
  "`e`, a failed write, as the error the admin shows on the form when the
   database refused it on a constraint; `e` itself otherwise."
  [entity-config e]
  (or (not-null-violation e) (foreign-key-violation e) (unique-violation e)
      (check-violation entity-config e) e))

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
        {:keys [from-clause select-clause join-clause] :as qc}
        (if child-cfg
          ;; A panel of rows: a file's size only where it is a column
          (resolve-query-config child-cfg (set (concat (:list-fields child-cfg) (:fields relationship))))
          {:from-clause [(:table relationship)] :select-clause [:*] :field-aliases {}})
        qualify   (column-of qc)
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

(defn- file-size
  "A binary column's size in bytes, however it was read: as its size, as
   bytes, as H2's java.sql.Blob, or as text SQLite stored in a BLOB column."
  [v]
  (cond (bytes? v)                  (alength ^bytes v)
        (instance? java.sql.Blob v) (.length ^java.sql.Blob v)
        (string? v)                 (alength (.getBytes ^String v "UTF-8"))
        :else                       (long v)))

(defn- typed-record
  "`row` with its :uuid fields as UUIDs and its :instant fields as Instants,
   whatever the driver returned: MySQL a CHAR(36) as text and a DATETIME as a
   LocalDateTime, H2 an OffsetDateTime (BOU-652). A value that does not parse,
   such as SQLite's `2026-01-01 10:00:00`, is left as read. A :binary field
   holds its size in bytes, never its content."
  [entity-config row]
  (when row
    (reduce-kv (fn [m k {:keys [type] :as cfg}]
                 (let [v (get m k)]
                   (cond-> m
                     (and (introspection/upload-field? cfg) (some? v))
                     (assoc k (file-size v))

                     (and (= :uuid type) (string? v))
                     (assoc k (or (type-conversion/string->uuid v) v))

                     (and (= :instant type) (some? v) (not (inst? v)))
                     (assoc k (or (type-conversion/string->instant v) v)))))
               row
               (:fields entity-config))))

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
      (mapv #(typed-record entity-config %)
            (db/execute-query! db-ctx
                               (cond-> {:select select-clause
                                        :from   from-clause
                                        :where  (if (:soft-delete entity-config false)
                                                  [:and in-ids [:= (get field-aliases :deleted-at :deleted_at) nil]]
                                                  in-ids)}
                                 join-clause (assoc :join join-clause)))))))

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
  [tx entity-config id-str soft? now]
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
      (do (update! (:secondary-table split-cfg) {:deleted-at now :active false})
          (pos? (update! table-name {:deleted-at now})))

      :else
      (pos? (update! soft-delete-table (cond-> {:deleted-at now} has-active? (assoc :active false)))))))

;; One delete as it runs: the transaction, whether it is soft, the time it
;; stamps, whether to read each row before deleting it (for the events), and
;; the rows already visited.
(defrecord ^:private Deletion [tx soft? now priors? seen])

(defn- remove-links!
  "Delete the join rows of records `ids` of `entity-config`, on `tx`."
  [tx entity-config ids]
  (when (seq ids)
    (doseq [{:keys [table foreign-key]} (:many-to-many entity-config)]
      (db/execute-update! tx {:delete-from table
                              :where [:in (case-conversion/kebab-case->snake-case-keyword foreign-key)
                                      (mapv str ids)]}))))

(defn- delete-tree!
  "Delete `id` of `entity-name` and, first, its cascading has-many children,
   which follow the parent: removed with a hard delete, given a deleted_at with
   a soft one. A restricting has-many with live children refuses it, and the
   transaction rolls back what was already deleted. A soft delete leaves a
   child with no deleted_at column as it is, since its parent row stays.
   Returns {:entity :id :prior} per row removed; :prior only with priors?."
  [{:keys [tx soft? now priors? seen] :as deletion} schema-provider entity-name id]
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
          ;; Its links go with a hard delete, as cascaded children do (BOU-778)
          (when-not soft?
            (remove-links! tx cfg [id-str]))
          (if (delete-row! tx cfg id-str soft? now)
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
        (let [deletion (->Deletion tx soft? (Instant/now)
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

(defn- inside-scopes
  "Those of `ids` of `entity-config` that lie inside `scopes`."
  [tx entity-config ids scopes]
  (let [{:keys [from-clause join-clause] :as qc} (resolve-query-config entity-config)
        col (qualified-column qc)
        pk  (col (:primary-key entity-config :id))]
    (mapv (comp str :id)
          (db/execute-query! tx (cond-> {:select [[pk :id]]
                                         :from   from-clause
                                         :where  [:and [:in pk (mapv str ids)] (scope-where entity-config col scopes)]}
                                  join-clause (assoc :join join-clause))))))

(defn- sync-links!
  "Make the links of record `id` in each relation of `links` exactly those
   ids, on `tx`: the missing join rows added, the others removed. A link to
   a soft-deleted record stays: the form cannot show it, so its absence from
   `ids` is no choice to remove it. Nor does one outside the relation's
   scopes in `links`' `:scopes` metadata, the records its user may read."
  [tx schema-provider entity-config id links]
  (let [id-str (str id)
        now    (Instant/now)
        scopes (:scopes (meta links))]
    (doseq [[k ids] links
            :let  [{:keys [table foreign-key other-key through entity]} (display/link-rel entity-config k)
                   tcfg    (ports/get-entity-config schema-provider entity)
                   jcfg    (ports/get-entity-config schema-provider through)
                   jpk     (:primary-key jcfg :id)
                   fk-col  (case-conversion/kebab-case->snake-case-keyword foreign-key)
                   oth-col (case-conversion/kebab-case->snake-case-keyword other-key)
                   current (set (map (comp str :other)
                                     (db/execute-query! tx {:select [[oth-col :other]] :from [table]
                                                            :where  [:= fk-col id-str]})))
                   wanted  (set ids)
                   gone    (let [g (vec (remove wanted current))
                                 g (if (and (seq g) (:soft-delete tcfg false))
                                     (mapv #(str (get % (:primary-key tcfg :id)))
                                           (live-records tx schema-provider entity g))
                                     g)]
                             (if (and (seq g) (contains? scopes k))
                               (inside-scopes tx tcfg g (get scopes k))
                               g))
                   added   (remove current wanted)]]
      (try
        (when (seq gone)
          (db/execute-update! tx {:delete-from table :where [:and [:= fk-col id-str] [:in oth-col gone]]}))
        (when (seq added)
          (db/execute-update!
           tx {:insert-into table
             :values (for [other added]
                       (case-conversion/kebab-case->snake-case-map
                        (cond-> {foreign-key id-str other-key other}
                          ;; A surrogate key the database does not number
                          (and (not (#{foreign-key other-key} jpk))
                               (#{:uuid :string} (get-in jcfg [:fields jpk :type])))
                          (assoc jpk (str (UUID/randomUUID)))
                          (contains? (:fields jcfg) :created-at) (assoc :created-at now)
                          (contains? (:fields jcfg) :updated-at) (assoc :updated-at now))))}))
        (catch Exception e
          (throw (refused-write jcfg e)))))))

;; =============================================================================
;; Entity writers (BOU-665)
;; =============================================================================

(defn- entity-writer
  "The IEntityWriter `entity-config` names under :writer, or nil without one."
  [writers entity-config]
  (when-let [k (:writer entity-config)]
    (or (get writers k)
        (throw (ex-info (str "No admin writer " k " is wired")
                        {:type :configuration-error :writer k})))))

(defn- writable-data
  "`data` without the read-only fields, those a field's own `:readonly` makes
   read-only too, and the primary key. A disabled checkbox still sends its
   hidden \"false\"."
  [entity-config data]
  (apply dissoc data (:primary-key entity-config :id)
         (concat (:readonly-fields entity-config)
                 (keep (fn [[k field]] (when (:readonly field) k)) (:fields entity-config)))))

(defn- written-update!
  "Update through `writer`, and with it the record's `links`, in one
   transaction when there are any: the writer's own transactions join it.
   Returns the record read back. A duplicate or a CHECK is shown on its
   field, as the SQL path shows it (BOU-673)."
  [{:keys [db-ctx schema-provider] :as svc} writer entity-config entity-name id data links]
  (let [update! #(try
                   (ports/update-record! writer id (writable-data entity-config data))
                   (catch clojure.lang.ExceptionInfo e
                     (throw (or (unique-violation e) (check-violation entity-config e) e))))]
    (if (seq links)
      (db/with-transaction* db-ctx
        (fn [tx]
          (update!)
          (sync-links! tx schema-provider entity-config id links)))
      (update!))
    (ports/get-entity svc entity-name id)))

(defn- written-delete!
  "Delete `ids` through `writer`, all or none (BOU-673). Returns
   {:entity :id :prior} per record deleted, like delete-records!."
  [{:keys [db-ctx schema-provider]} writer entity-config entity-name ids priors?]
  (let [soft?   (:soft-delete entity-config false)
        priors  (when priors?
                  (into {} (map (juxt #(str (get % (:primary-key entity-config :id))) identity))
                        (live-records db-ctx schema-provider entity-name ids)))
        delete! #(ports/delete-records! writer ids {:soft? soft?})]
    (mapv (fn [id] {:entity entity-name :id id :prior (get priors id)})
          (if (or soft? (empty? (:many-to-many entity-config)))
            (delete!)
            ;; A hard delete takes the links, as the SQL path does (BOU-779)
            (db/with-transaction* db-ctx
              (fn [tx]
                (remove-links! tx entity-config ids)
                (delete!)))))))

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
  [{:keys [db-ctx schema-provider workflows writers] :as svc} entity-name ids priors?]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (if-let [writer (entity-writer writers entity-config)]
      (written-delete! svc writer entity-config entity-name ids priors?)
      (do
        (check-minimums! db-ctx schema-provider entity-name
                         (live-records db-ctx schema-provider entity-name ids))
        (assert-unrestricted! db-ctx schema-provider entity-name ids)
        (persist-interceptors/execute-persistence-operation
         :admin-delete-entities
         {:entity (name entity-name) :count (count ids)}
         (fn [{:keys [_params]}]
           (delete-records! db-ctx schema-provider workflows entity-name ids priors?))
         db-ctx)))))

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
        now           (Instant/now)
        id-str        (type-conversion/uuid->string (UUID/randomUUID))
        prepared      (cond-> (assoc sanitized :id id-str)
                        (contains? entity-fields :created-at) (assoc :created-at now)
                        (contains? entity-fields :updated-at) (assoc :updated-at now))
        db-data       (case-conversion/kebab-case->snake-case-map (prepare-values-for-db prepared))]
    ;; Without RETURNING, for H2.
    (try
      (db/execute-one! conn {:insert-into table-name :values [db-data]})
      (catch Exception e
        (throw (refused-write entity-config e))))
    (typed-record entity-config
                  (db/execute-one! conn {:select (own-columns entity-config nil) :from [table-name]
                                         :where  [:= primary-key id-str]}))))

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
    (when-let [many (not-empty (forms/too-many rels children))]
      (throw (ex-info (str/join "; " (for [[_ {:keys [label max count]}] many]
                                       (str label ": at most " max " allowed, " count " given")))
                      {:type :validation-error :errors {} :too-many many})))
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

;; =============================================================================
;; Linked records (BOU-778)
;; =============================================================================

(defn- with-from
  "`q` reading from the entity the way its own list does."
  [q {:keys [from-clause join-clause]} where]
  (cond-> (assoc q :from from-clause)
    join-clause (assoc :join join-clause)
    where       (assoc :where where)))

(defn- linked-records*
  "{owner-id-string {:items [{:id :title}] :total n}}: what `rel` links each
   of `owner-ids` to, live rows only, by title. With `per-owner`, the first
   few of each, in one query (ROW_NUMBER, on all four engines). With
   `scopes`, only the linked rows inside them."
  [db-ctx schema-provider rel owner-ids per-owner scopes]
  (when (seq owner-ids)
    (let [tcfg  (ports/get-entity-config schema-provider (:entity rel))
          tpk   (:primary-key tcfg :id)
          qc    (resolve-query-config tcfg)
          col   (qualified-column qc)
          title (col (:title-field tcfg tpk))
          owner (link-column (:foreign-key rel))
          where (cond-> [:and [:in owner (mapv str owner-ids)]]
                  (:soft-delete tcfg false) (conj [:= (col :deleted-at) nil])
                  scopes                    (conj (scope-where tcfg col scopes)))
          base  (fn [select]
                  (-> {:select select}
                      (with-from qc where)
                      (update :join #(into (vec %) [[(:table rel) :lnk] [:= (link-column (:other-key rel)) (col tpk)]]))))
          rows  (db/execute-query!
                 db-ctx
                 (if per-owner
                   {:select   [:owner :id :title :total]
                    :from     [[(base [[owner :owner] [(col tpk) :id] [title :title]
                                       [[:over [[:row_number] {:partition-by [owner]
                                                               :order-by     [[title :asc] [(col tpk) :asc]]}]]
                                        :rn]
                                       [[:over [[:count :*] {:partition-by [owner]}]] :total]])
                                :ranked]]
                    :where    [:<= :rn per-owner]
                    :order-by [[:title :asc] [:id :asc]]}
                   (assoc (base [[owner :owner] [(col tpk) :id] [title :title]])
                          :order-by [[title :asc] [(col tpk) :asc]])))
          totals (frequencies (map (comp str :owner) rows))]
      (reduce (fn [acc {:keys [owner id title total]}]
                (let [k (str owner)]
                  (-> acc
                      (update-in [k :items] (fnil conj []) {:id    (str id)
                                                            :title (if (str/blank? (some-> title str)) (str id) (str title))})
                      (assoc-in [k :total] (or total (get totals k 0))))))
              {}
              rows))))

(defn- assert-links!
  "Refuse `links` naming a relation `entity-config` does not have, or a
   record the relation cannot link: unknown, deleted, or outside the
   relation's scopes in `links`' `:scopes` metadata. Checked before the
   write, so a refusal is not logged as a failed database operation."
  [db-ctx schema-provider entity-config links]
  (doseq [[k ids] links
          :let  [rel (display/link-rel entity-config k)]]
    (when-not rel
      (throw (ex-info (str "No many-to-many " (name k))
                      {:type :validation-error :errors {k [[:t :admin/links-unknown]]}})))
    (let [wanted (set ids)
          tcfg   (ports/get-entity-config schema-provider (:entity rel))
          pk     (:primary-key tcfg :id)
          live   (mapv #(str (get % pk))
                       (when (seq wanted)
                         (live-records db-ctx schema-provider (:entity rel) (vec wanted))))
          scopes (:scopes (meta links))
          ;; A record the user may not read is as unknown as a missing one
          found  (set (if (and (seq live) (contains? scopes k))
                        (inside-scopes db-ctx tcfg live (get scopes k))
                        live))]
      (when-not (= wanted found)
        (throw (ex-info (str "Unknown " (name (:entity rel)) ": " (str/join ", " (remove found wanted)))
                        {:type :validation-error :errors {k [[:t :admin/links-unknown]]}}))))))

(defn- links-of
  "`data` as [data links]: the record's own fields, and its `:admin/links`
   with blank ids dropped, carrying its `:admin/link-scopes` as `:scopes`
   metadata for `sync-links!`."
  [data]
  [(dissoc data :admin/links :admin/link-scopes)
   (with-meta (update-vals (:admin/links data) #(into [] (comp (map str) (remove str/blank?) (distinct)) %))
              {:scopes (:admin/link-scopes data)})])

;; =============================================================================
;; List Overview (ADR-040)
;; =============================================================================

;; What a list page shows besides its rows, in queries bounded by the config:
;; one per relation column, one per has-many column, one for the facet and one
;; per summary. Each part is optional — a failing query costs the page that
;; part, logged, never the page.

(defn- id-strings
  "The distinct non-nil values of `k` in `records`, as strings: how the
   overview keys them, and how the admin compares ids across engines."
  [records k]
  (into [] (comp (keep #(get % k)) (map str) (distinct)) records))

(defn- param-value
  "A config value as a query parameter. HoneySQL reads a keyword as a column,
   so an enum value written `:overdue` in `:summary` becomes \"overdue\"."
  [v]
  (if (keyword? v) (name v) v))

(defn- param-filters
  [filters]
  (into {} (map (fn [[field spec]]
                  [field (if (map? spec)
                           (cond-> spec
                             (contains? spec :value)  (update :value param-value)
                             (contains? spec :values) (update :values #(mapv param-value %)))
                           (param-value spec))]))
        filters))

(defn- overview-part
  "`(f)`, or nil when it throws: logged, and the page renders without it."
  [entity-name part f]
  (try (f)
       (catch Exception e
         (log/warn e "admin overview query failed" {:entity entity-name :part part})
         nil)))

(defn- relation-titles
  "{id-string title} for the targets of relation `field` on this page. With
   `scopes`, only targets inside them: the others keep showing their id."
  [db-ctx schema-provider entity-config records field scopes]
  (let [rel    (get (display/belongs-to-fields entity-config) field)
        target (:entity rel)
        ids    (id-strings records field)]
    (when (and target (seq ids) (ports/validate-entity-exists schema-provider target))
      (let [tcfg  (ports/get-entity-config schema-provider target)
            tpk   (:primary-key tcfg :id)
            title (:title-field tcfg)
            qc    (resolve-query-config tcfg)
            col   (column-of qc)]
        (when (and title (not= title tpk))
          (into {}
                ;; A blank title would render an empty, invisible link; the cell
                ;; shows the id instead
                (keep (fn [{:keys [id title]}]
                        (when-not (str/blank? (some-> title str))
                          [(str id) (str title)])))
                (db/execute-query! db-ctx
                                   (with-from {:select [[(col tpk) :id] [(col title) :title]]}
                                     qc (cond-> [:and [:in (col tpk) ids]]
                                          scopes (conj (scope-where tcfg col scopes)))))))))))

(defn- has-many-counts
  "{parent-id-string n} for has-many column `column` on this page."
  [db-ctx schema-provider entity-config records column]
  (let [rel (get (display/has-many-columns entity-config) column)
        ids (id-strings records (:primary-key entity-config :id))]
    (when (and rel (seq ids))
      (let [{:keys [child-cfg qualify query]} (related-query schema-provider nil rel)
            fk    (qualify (fk-field rel))
            where (if (:soft-delete child-cfg false)
                    [:and [:in fk ids] [:= (qualify :deleted-at) nil]]
                    [:in fk ids])]
        (into {}
              (map (juxt (comp str :parent) :n))
              (db/execute-query! db-ctx
                                 (cond-> {:select   [[fk :parent] [:%count.* :n]]
                                          :from     (:from query)
                                          :where    where
                                          :group-by [fk]}
                                   (:join query) (assoc :join (:join query)))))))))

(defn- facet-counts
  "{value-string n} for the facet over the filtered set, the facet's own
   filter left out so every tab keeps its count. A nil value counts under nil."
  [db-ctx entity-config qc options]
  (when-let [facet (:facet entity-config)]
    (let [col   ((column-of qc) facet)
          where (list-where (:adapter db-ctx) entity-config qc
                            (update options :filters dissoc facet))]
      ;; `value` is reserved in H2, hence facet-value
      (into {}
            (map (fn [{:keys [facet-value n]}]
                   [(some-> facet-value param-value str) n]))
            (db/execute-query! db-ctx
                               (-> {:select [[col :facet-value] [:%count.* :n]] :group-by [col]}
                                   (with-from qc where)))))))

(defn- summary-values
  "Each `:summary` entry's aggregate over the filtered set, narrowed by its
   own `:where`."
  [db-ctx entity-name entity-config qc options]
  (let [col     (column-of qc)
        base    (list-where (:adapter db-ctx) entity-config qc options)]
    (vec
     (for [{:keys [label agg field where] :or {agg :count}} (take 4 (:summary entity-config))
           :let [count? (or (= agg :count) (nil? field))
                 expr  (if count? :%count.* [agg (col field)])
                 extra (build-filter-where (:adapter db-ctx)
                                           (into {} (map (fn [[f spec]] [(col f) spec]))
                                                 (param-filters where)))
                 value (overview-part entity-name [:summary label]
                                      #(:v (db/execute-one!
                                            db-ctx
                                            (with-from {:select [[expr :v]]} qc
                                              (combine-where-clauses [base extra])))))]]
       {:label label
        :value value
        :field field
        :role  (if count? :number (get-in entity-config [:display-roles field] :number))}))))

(def ^:private links-per-cell
  "How many linked records a list cell names before \"+N\"."
  3)

(defn- list-overview*
  [db-ctx schema-provider entity-name options records]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)
        roles         (:display-roles entity-config)
        ;; One record's fields (the drawer) instead of the list's columns: then
        ;; there is no list to count facets or summaries over
        record?       (contains? options :overview/fields)
        columns       (if record? (:overview/fields options) (:list-fields entity-config))
        of-role       (fn [role] (filter #(= role (get roles %)) columns))
        qc            (resolve-query-config entity-config)
        ;; {column result} for `columns`, leaving out a part that failed
        per-column    (fn [part columns f]
                        (into {} (keep (fn [c]
                                         (some->> (overview-part entity-name [part c] #(f c))
                                                  (vector c))))
                              columns))
        facet         (:facet entity-config)]
    {:now     (Instant/now)
     :titles  (per-column :titles (of-role :relation)
                          #(relation-titles db-ctx schema-provider entity-config records %
                                            (get-in options [:overview/title-scopes %])))
     :counts  (per-column :counts (of-role :count)
                          #(has-many-counts db-ctx schema-provider entity-config records %))
     :links   (per-column :links (of-role :links)
                          #(linked-records* db-ctx schema-provider (display/link-rel entity-config %)
                                            (id-strings records (:primary-key entity-config :id))
                                            links-per-cell
                                            (get-in options [:overview/link-scopes %])))
     :facets  (when (and facet (not record?) (not (:overview/summary-only? options)))
                {facet (overview-part entity-name [:facet facet]
                                      #(facet-counts db-ctx entity-config qc options))})
     :summary (if record? [] (summary-values db-ctx entity-name entity-config qc options))}))

(defrecord AdminService [db-ctx schema-provider logger error-reporter config workflows writers]
  ports/IListOverview
  (list-overview [_ entity-name options records]
    (list-overview* db-ctx schema-provider entity-name options records))

  ports/ILinks
  (linked-records [_ entity-name rel-key ids {:keys [per-owner scopes]}]
    (let [rel (display/link-rel (ports/get-entity-config schema-provider entity-name) rel-key)]
      (when-not rel
        (throw (ex-info (str "No many-to-many " (name rel-key) " on " (name entity-name))
                        {:type :not-found :entity-name entity-name :relation rel-key})))
      (or (linked-records* db-ctx schema-provider rel ids per-owner scopes) {})))

  ports/IAdminService

  (list-entities [_ entity-name options]
    (persist-interceptors/execute-persistence-operation
     :admin-list-entities
     {:entity (name entity-name)
      :limit (:limit options)
      :offset (:offset options)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             ;; A sort the entity does not offer falls back to the default
             sort-field (let [f (:sort options)]
                          (when (and f (filters/queryable-field? entity-config :sortable f)) f))
             ;; The default direction goes with the default field only
             sort-dir (or (:sort-dir options)
                          (when-not sort-field (:default-sort-dir entity-config)))
             default-sort (:default-sort entity-config :id)

             ;; A file's content is not read for a list, its size only where it is a column
             {:keys [from-clause select-clause join-clause field-aliases] :as qc}
             (resolve-query-config entity-config (set (:list-fields entity-config)))

             ; Qualified for ORDER BY: in a join both tables may have the column
             col                    (column-of qc)
             resolved-sort-field    (when sort-field (col sort-field))
             resolved-default-sort  (col default-sort)

             ; Search, filters and soft delete, shared with the overview (ADR-040)
             where-clause (list-where (:adapter db-ctx) entity-config qc options)
             primary-key (:primary-key entity-config :id)
             ;; created-at or a name repeats; without the key as tie-breaker
             ;; a row can show on two pages, or on none
             ordering (cond-> (build-ordering resolved-sort-field sort-dir resolved-default-sort)
                        (and (not= (or sort-field default-sort) primary-key)
                             (contains? (:fields entity-config) primary-key))
                        (conj [(get field-aliases primary-key primary-key) :asc]))
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
             kebab-records (mapv #(typed-record entity-config %) records)

              ; Calculate pagination metadata
             page-size (:limit pagination)
             page-number (inc (quot (:offset pagination) page-size))
             total-pages (int (Math/ceil (/ total-count (double page-size))))]

         {:records kebab-records
          :total-count total-count
          :page-size page-size
          :page-number page-number
          :total-pages total-pages
          :sort (or sort-field default-sort)
          :sort-dir (or sort-dir :asc)}))
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
         (typed-record entity-config db-result)))
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
                        {:type :validation-error
                         :reason :cannot-create-split-table-entity
                         :entity-name entity-name
                         :split-config split-cfg
                         :create-redirect-url (:create-redirect-url entity-config)}))))
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          [data links]  (links-of data)]
      (assert-links! db-ctx schema-provider entity-config links)
      (persist-interceptors/execute-persistence-operation
       :admin-create-entity
       {:entity (name entity-name)}
       (fn [{:keys [_params]}]
         (db/with-transaction* db-ctx
           (fn [tx]
             (let [record (insert-record! tx entity-config data)]
               (sync-links! tx schema-provider entity-config
                            (get record (:primary-key entity-config :id)) links)
               record))))
       db-ctx)))

  (create-entity-with-children [_ entity-name data children]
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          [data links]  (links-of data)
          _             (assert-links! db-ctx schema-provider entity-config links)
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
               (sync-links! tx schema-provider entity-config (get parent (:primary-key entity-config :id)) links)
               {:record   parent
                :children (insert-children! tx by-entity
                                            (get parent (:primary-key entity-config :id))
                                            children)}))))
       db-ctx)))

  (update-entity [this entity-name id data]
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          [data links]  (links-of data)
          _             (assert-links! db-ctx schema-provider entity-config links)]
      (if-let [writer (entity-writer writers entity-config)]
        (written-update! this writer entity-config entity-name id data links)
        (persist-interceptors/execute-persistence-operation
         :admin-update-entity
         {:entity (name entity-name) :id id}
         (fn [{:keys [_params]}]
           (let [table-name (:table-name entity-config)
                 primary-key (:primary-key entity-config :id)

                  ; Remove only readonly and ID fields from update (not hide-fields!)
                  ; hide-fields are for display only, data can still be provided
                 sanitized-data (writable-data entity-config data)

                   ; Only set timestamp columns that exist in the table
                 now (Instant/now)
                 entity-fields (:fields entity-config)
                 prepared-data (cond-> sanitized-data
                                 (contains? entity-fields :updated-at) (assoc :updated-at now))

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
                             (split-update-data sanitized-data secondary-fields entity-fields now)
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
                                                       :where  [:= primary-key id-str]}))
                             (sync-links! tx schema-provider entity-config id-str links))))
                       ;; non-split path
                       (db/with-transaction* db-ctx
                         (fn [tx]
                           (when (seq db-data)
                             (db/execute-update! tx {:update table-name
                                                     :set    db-data
                                                     :where  [:= primary-key id-str]}))
                           (sync-links! tx schema-provider entity-config id-str links))))
                     (catch Exception e
                       (throw (refused-write entity-config e))))

                  ; Fetch the updated record using join-aware query
                 {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
                 id-field (get field-aliases :id primary-key)
                 select-query (cond-> {:select select-clause
                                       :from from-clause
                                       :where [:= id-field id-str]}
                                join-clause (assoc :join join-clause))
                 db-result (db/execute-one! db-ctx select-query)]

              ; Keys arrive kebab-case from the execution layer builder-fn
             (typed-record entity-config db-result)))
         db-ctx))))

  (update-entity-field [this entity-name id field value]
    ; Week 2: Single field update for inline editing
    ; More efficient than full entity update - only validates and updates one field
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])]

      ; Validate field exists and is not readonly
      (when-not field-config
        (throw (ex-info "Field does not exist"
                        {:type :validation-error
                         :field field
                         :entity entity-name})))

      (when (or (contains? (set (:readonly-fields entity-config)) field) (:readonly field-config))
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

      (if (entity-writer writers entity-config)
        (ports/update-entity this entity-name id {field value})
        (persist-interceptors/execute-persistence-operation
         :admin-update-entity-field
         {:entity (name entity-name) :id id :field (name field)}
         (fn [{:keys [_params]}]
           (let [table-name (:table-name entity-config)
                 primary-key (:primary-key entity-config :id)
                 ; Prepare single field update with updated-at timestamp
                 now (Instant/now)
               update-data {field value :updated-at now}

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
                     (throw (refused-write entity-config e))))

               ; Fetch updated record using join-aware query
               {:keys [from-clause select-clause join-clause field-aliases]} (resolve-query-config entity-config)
               id-field (get field-aliases :id primary-key)
               select-query (cond-> {:select select-clause
                                     :from from-clause
                                     :where [:= id-field id-str]}
                              join-clause (assoc :join join-clause))
               db-result (db/execute-one! db-ctx select-query)]

           ; Keys arrive kebab-case from the execution layer builder-fn
           (typed-record entity-config db-result)))
         db-ctx))))

  (delete-entity [this entity-name id]
    (pos? (roots-removed entity-name [id] (checked-delete! this entity-name [id] false))))

  (count-entities [_ entity-name filters]
    (persist-interceptors/execute-persistence-operation
     :admin-count-entities
     {:entity (name entity-name)}
     (fn [{:keys [_params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             {:keys [from-clause join-clause] :as qc} (resolve-query-config entity-config)
             ;; The list's rules: offered fields only, live rows only
             where-clause (list-where (:adapter db-ctx) entity-config qc {:filters filters})
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
       (let [{:keys [child-cfg select-clause qualify id-column query]} (related-query schema-provider parent-id relationship)]
         (mapv #(typed-record child-cfg %)
               (db/execute-query! db-ctx
                                  (cond-> (assoc query
                                                 :select   select-clause
                                                 ;; The child list's own order, so a limited
                                                 ;; panel is that list's first page. The id
                                                 ;; breaks ties, so a limit cuts the same rows
                                                 ;; on every read.
                                                 :order-by [[(qualify (:default-sort child-cfg :id)) :asc]
                                                            [id-column :asc]])
                                    (:limit relationship) (assoc :limit (:limit relationship)))))))
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
  (let [config  (ports/get-entity-config schema-provider entity-name)
        hidden  (:hide-fields config)
        ;; SQLite hands a UUID column back as a string; PostgreSQL as a UUID.
        ;; A subscriber gets one type for one entity whichever stored it (BOU-579).
        id      (if (and (string? id)
                         (= :uuid (get-in config [:fields (:primary-key config :id) :type])))
                  (or (parse-uuid id) id)
                  id)
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
  ports/IListOverview
  (list-overview [_ entity-name options records]
    (when (satisfies? ports/IListOverview inner)
      (ports/list-overview inner entity-name options records)))

  ports/ILinks
  (linked-records [_ entity-name rel-key ids opts]
    (if (satisfies? ports/ILinks inner)
      (ports/linked-records inner entity-name rel-key ids opts)
      {}))

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
      (when (map? record)
        (publish-lifecycle! publisher schema-provider :admin/entity-updated
                            entity-name id record prior))
      record))

  (update-entity-field [_ entity-name id field value]
    (let [prior  (ports/get-entity inner entity-name id)
          record (ports/update-entity-field inner entity-name id field value)]
      (when (map? record)
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
     writers: optional {keyword IEntityWriter}, named by an entity's :writer (BOU-665)

   Returns:
     AdminService instance implementing IAdminService"
  ([db-ctx schema-provider logger error-reporter config]
   (create-admin-service db-ctx schema-provider logger error-reporter config nil))
  ([db-ctx schema-provider logger error-reporter config event-publisher]
   (create-admin-service db-ctx schema-provider logger error-reporter config event-publisher nil))
  ([db-ctx schema-provider logger error-reporter config event-publisher workflows]
   (create-admin-service db-ctx schema-provider logger error-reporter config event-publisher workflows nil))
  ([db-ctx schema-provider logger error-reporter config event-publisher workflows writers]
   ;; Rows keyed as the schema repository keys columns: `x2y_id` is :x2y-id
   (let [db-ctx (assoc db-ctx :builder-fn db/as-kebab-maps)]
     (cond-> (->AdminService db-ctx schema-provider logger error-reporter config workflows writers)
       event-publisher (->PublishingAdminService db-ctx schema-provider event-publisher)))))
