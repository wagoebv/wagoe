(ns wagoe.admin.shell.schema-repository
  "Schema repository implementation for database metadata retrieval.

   This namespace provides the concrete implementation of ISchemaProvider,
   connecting to the database through existing adapter protocols to fetch
   table and column metadata.

   Responsibilities:
   - Fetch raw database metadata using adapter protocols
   - Coordinate with pure core functions for parsing and merging
   - Cache entity configurations for performance
   - Provide entity discovery based on configuration

   Caching: computed entity configurations are cached in an atom inside the
   long-lived SchemaRepository component (populated on first access). Table
   schemas only change at migration/deploy time, so the cache lives as long
   as the component. Use `reset-cache!` (or recreate the component via
   (ig-repl/reset)) to invalidate after a migration."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.schema :as admin-schema]
   [wagoe.admin.core.schema-introspection :as introspection]
   [wagoe.platform.ports.database :as db-protocols]
   [wagoe.core.utils.case-conversion :as case-conv]))

;; =============================================================================
;; Entity Config Computation (helper — must be defined before the record)
;; =============================================================================

(defn- compute-entity-config
  "Compute the complete entity configuration for `entity-name`.

   This is the uncached workhorse behind `get-entity-config`: it issues the
   database metadata queries and runs the full parse/merge pipeline. Called
   at most once per entity per SchemaRepository instance — results are cached
   in the repository's `config-cache` atom.

   Process:
   1. Fetch table metadata from database
   2. Parse metadata into auto-detected configuration
   3. Enrich fields with enum info from registered Malli schemas (if any)
   4. Get manual config overrides from admin config (if any)
   5. Merge auto-detected with manual overrides
   6. Inject effective UI config (admin global + entity override)
   7. Apply field ordering if :field-order is specified
   8. Return complete entity configuration"
  [repo entity-name]
  (let [{:keys [config malli-schemas]} repo
        ;; Use :table-name from manual config if provided, otherwise derive from entity-name
        effective-table-name (or (get-in config [:entities entity-name :table-name])
                                 entity-name)
        table-metadata (ports/fetch-table-metadata repo effective-table-name)
        auto-config (introspection/parse-table-metadata entity-name table-metadata)

        ;; When :split-table-update is configured, also introspect the secondary table
        ;; so that its fields appear in the entity config (editable in forms, etc.)
        split-cfg (get-in config [:entities entity-name :split-table-update])
        auto-config (if split-cfg
                      (let [secondary-table (:secondary-table split-cfg)
                            secondary-fields (:secondary-fields split-cfg)
                            secondary-meta (ports/fetch-table-metadata repo secondary-table)
                            secondary-parsed (introspection/parse-table-metadata entity-name secondary-meta)
                            ;; Only merge fields that are declared as secondary-fields
                            secondary-field-configs (select-keys (:fields secondary-parsed) secondary-fields)
                            ;; Determine which secondary fields are editable (not readonly)
                            readonly-set (set (:readonly-fields auto-config))
                            new-editable (vec (remove readonly-set (keys secondary-field-configs)))]
                        (-> auto-config
                            (update :fields merge secondary-field-configs)
                            (update :editable-fields into new-editable)
                            (update :detail-fields into new-editable)))
                      auto-config)

        ;; Enrich auto-detected fields with enum type/widget/options from Malli schema
        malli-schema (get malli-schemas entity-name)
        enum-overrides (introspection/extract-enum-fields-from-malli-schema malli-schema)
        enriched-auto-config (if (seq enum-overrides)
                               (update auto-config :fields
                                       (fn [fields]
                                         (reduce-kv
                                          (fn [acc field-name enum-cfg]
                                            (if (contains? acc field-name)
                                              (update acc field-name merge enum-cfg)
                                              acc))
                                          fields
                                          enum-overrides)))
                               auto-config)
        manual-config (get-in config [:entities entity-name])
        merged-config (introspection/build-entity-config enriched-auto-config manual-config)
        ;; Compute effective UI config: entity overrides admin global
        admin-ui (get config :ui {})
        entity-ui (get merged-config :ui {})
        ;; Deep merge: entity field-grouping overrides admin field-grouping
        effective-field-grouping (merge (get admin-ui :field-grouping {})
                                        (get entity-ui :field-grouping {}))
        effective-ui (-> (merge admin-ui entity-ui)
                         (assoc :field-grouping effective-field-grouping))
        config-with-ui (assoc merged-config :ui effective-ui)
        ;; Apply field ordering if specified
        ordered-config (introspection/apply-field-order-to-config config-with-ui)]
    ;; Add relationship detection (Week 1 stub)
    (introspection/detect-relationships ordered-config)))

;; =============================================================================
;; Schema Repository Implementation
;; =============================================================================

(defrecord SchemaRepository [db-ctx config malli-schemas config-cache]
  ports/ISchemaProvider

  (fetch-table-metadata [_ table-name]
    "Fetch raw table metadata from database using adapter protocol.

     Uses the existing db-protocols/get-table-info which works across
     all database adapters (PostgreSQL, SQLite, MySQL, H2).
     
     Converts kebab-case table names to snake_case for database lookup."
    (let [adapter (:adapter db-ctx)
          datasource (:datasource db-ctx)
          ;; Convert kebab-case to snake_case at database boundary
          table-name-str (if (keyword? table-name)
                           (name table-name)
                           table-name)
          table-name-normalized (case-conv/kebab-case->snake-case-string table-name-str)]
      (try
        (let [columns (db-protocols/get-table-info adapter datasource table-name-normalized)]
          (when (empty? columns)
            (throw (ex-info (str "Table not found: " table-name-normalized)
                            {:type :table-not-found
                             :table-name table-name})))
          columns)
        (catch Exception e
          (throw (ex-info (str "Failed to fetch table metadata: " (.getMessage e))
                          {:type :schema-fetch-error
                           :table-name table-name
                           :cause e}))))))

  (get-entity-config [this entity-name]
    "Get complete entity configuration by merging auto-detected with manual config.

     Cached: the configuration is computed once per entity (issuing database
     metadata queries) and stored in the component's config-cache atom.
     Subsequent calls are a map lookup. Under concurrent first access the
     computation may run more than once; last write wins, results are
     identical. See `reset-cache!` to invalidate (e.g. after a migration)."
    (or (get @config-cache entity-name)
        (let [entity-config (compute-entity-config this entity-name)]
          (swap! config-cache assoc entity-name entity-config)
          entity-config)))

  (list-available-entities [_]
    "List all entities available based on discovery configuration.

     Discovery modes:
     - :allowlist - Only return entities in :allowlist
     - :denylist - Return all tables except those in :denylist
     - :all - Return all accessible database tables (Week 2+)"
    (let [discovery-config (:entity-discovery config)
          mode (:mode discovery-config)]
      (case mode
        :allowlist
        (vec (:allowlist discovery-config []))

        :denylist
        ; Week 2+: Query all tables, filter by denylist
        (throw (ex-info "Denylist mode not yet implemented"
                        {:type :not-implemented
                         :mode :denylist
                         :available-modes [:allowlist]}))

        :all
        ; Week 2+: Query all tables from database
        (throw (ex-info "All mode not yet implemented"
                        {:type :not-implemented
                         :mode :all
                         :available-modes [:allowlist]}))

        ; Default fallback
        (throw (ex-info (str "Invalid entity discovery mode: " mode)
                        {:type :invalid-config
                         :mode mode
                         :valid-modes [:allowlist :denylist :all]})))))

  (get-entity-label [_ entity-name]
    "Get display label for entity.

     Tries manual config label first, falls back to humanized entity name."
    (or (get-in config [:entities entity-name :label])
        (introspection/humanize-entity-name entity-name)))

  (validate-entity-exists [this entity-name]
    "Check if entity is valid and accessible.

     Returns true if entity is in the list of available entities.
     The entity set is computed once and cached in the config-cache atom
     (under a namespaced key that cannot collide with entity names)."
    (let [available (or (get @config-cache ::available-entities)
                        (let [entity-set (set (ports/list-available-entities this))]
                          (swap! config-cache assoc ::available-entities entity-set)
                          entity-set))]
      (contains? available entity-name))))

;; =============================================================================
;; Factory Function
;; =============================================================================

(defn create-schema-repository
  "Create new SchemaRepository instance.

   Args:
     db-ctx: Database context map with :adapter and :datasource
     config: Admin configuration map with :entity-discovery and :entities
     malli-schemas: Optional map of entity-name → Malli schema.
                    When provided, enum fields are auto-detected from the schema
                    and rendered as select widgets without manual config.

   Returns:
     SchemaRepository instance implementing ISchemaProvider.
     Entity configurations are cached per instance (populated on first
     access); see `reset-cache!` to invalidate.

   Example:
     (create-schema-repository db-ctx
       {:entity-discovery {:mode :allowlist
                           :allowlist #{:users}}
        :entities {:users {:label \"System Users\"}}}
       {:users wagoe.user.schema/User})"
  ([db-ctx config]
   (create-schema-repository db-ctx config {}))
  ([db-ctx config malli-schemas]
   (->SchemaRepository db-ctx config (or malli-schemas {}) (atom {}))))

(defn reset-cache!
  "Clear the repository's cached entity configurations.

   Call after a schema migration (or from the REPL) to force fresh database
   metadata on the next access. Recreating the component — e.g. via
   (ig-repl/reset) — has the same effect."
  [schema-repository]
  (reset! (:config-cache schema-repository) {})
  nil)

;; =============================================================================
;; Helper Functions for Testing and Development
;; =============================================================================

(defn list-all-database-tables
  "Development helper: List all tables in the database.

   Useful for debugging and understanding what tables are available.

   Week 2+: This will be used for :all and :denylist discovery modes.

   Args:
     db-ctx: Database context map

   Returns:
     Vector of table names as keywords

    Note: Implementation depends on database type - will need to query
         information_schema or similar."
  [_db-ctx]
  ; Week 2+: Implement database-specific table listing
  ; PostgreSQL: SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
  ; SQLite: SELECT name FROM sqlite_master WHERE type='table'
  ; MySQL: SHOW TABLES
  (throw (ex-info "list-all-database-tables not yet implemented"
                  {:type :not-implemented})))

(defn validate-entity-config
  "Validate entity configuration against schema.

   Args:
     entity-config: Entity configuration map

   Returns:
     {:valid? true} or {:valid? false :errors ...}

   Example:
     (validate-entity-config {:label \"Users\" :table-name :users ...})"
  [entity-config]
  (admin-schema/validate-entity-config entity-config))

(defn get-entity-field-names
  "Get list of all field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of field name keywords

   Example:
     (get-entity-field-names provider :users)
     ;=> [:id :email :name :role :active :created-at ...]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (vec (keys (:fields entity-config)))))

(defn get-entity-primary-key
  "Get primary key field name for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Keyword primary key field name (defaults to :id)

   Example:
     (get-entity-primary-key provider :users) ;=> :id"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:primary-key entity-config :id)))

(defn get-searchable-fields
  "Get list of searchable field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of searchable field name keywords

   Example:
     (get-searchable-fields provider :users)
     ;=> [:email :name]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:search-fields entity-config [])))

(defn get-list-fields
  "Get list of fields to display in list view.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of field name keywords for list view

   Example:
     (get-list-fields provider :users)
     ;=> [:email :name :role :active :created-at]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:list-fields entity-config [])))

(defn get-editable-fields
  "Get list of editable field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of editable field name keywords

   Example:
     (get-editable-fields provider :users)
     ;=> [:name :email :role :active]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:editable-fields entity-config [])))

;; =============================================================================
;; Entity Configuration Summary (for debugging)
;; =============================================================================

(defn summarize-entity-config
  "Create human-readable summary of entity configuration.

   Useful for debugging, logging, and understanding what was auto-detected
   vs manually configured.

   Args:
     entity-config: Entity configuration map

   Returns:
     Summary map with key statistics

   Example:
     (summarize-entity-config entity-config)
     ;=> {:entity-name :users
     ;    :label \"Users\"
     ;    :total-fields 10
     ;    :visible-fields 8
     ;    :editable-fields 5
     ;    :searchable-fields 2
     ;    :readonly-fields #{:id :created-at :updated-at}
     ;    :hidden-fields #{:password-hash :deleted-at}}"
  [entity-config]
  {:entity-name (:table-name entity-config)
   :label (:label entity-config)
   :primary-key (:primary-key entity-config :id)
   :total-fields (count (:fields entity-config))
   :list-fields-count (count (:list-fields entity-config))
   :detail-fields-count (count (:detail-fields entity-config))
   :editable-fields-count (count (:editable-fields entity-config))
   :searchable-fields-count (count (:search-fields entity-config))
   :readonly-fields (:readonly-fields entity-config #{})
   :hidden-fields (:hide-fields entity-config #{})
   :soft-delete (:soft-delete entity-config false)
   :default-sort [(:default-sort entity-config) (:default-sort-dir entity-config)]})
