(ns wagoe.platform.shell.adapters.database.utils.schema
  "Schema-to-DDL generation utilities for database adapters.
   
   This namespace provides utilities for generating database DDL statements
   from Malli schema definitions. It handles:
   - Column types, from the adapter's `column-type`
   - Table creation with constraints
   - Index generation from schema analysis
   - Cross-database compatibility
   
   Key Features:
   - Uses Malli schemas as canonical source of truth
   - Database-specific column type mapping
   - Foreign key and unique constraint generation
   - Automatic index creation based on field patterns
   
   Usage:
     (require '[wagoe.platform.shell.adapters.database.utils.schema :as schema])

     (schema/generate-table-ddl ctx \"users\" some-malli-schema)
     (schema/initialize-tables-from-schemas! ctx schema-map)"
  (:require [wagoe.core.utils.text :as text]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.common.core :as db-core]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Column Name Conversion
;; =============================================================================

(defn- col-name
  "Convert kebab-case field name to snake_case column name."
  [s]
  (str/replace s "-" "_"))

;; =============================================================================
;; Column Type Mapping
;; =============================================================================

(def ^:private malli->logical
  "The `protocols/logical-column-types` entry for a Malli type. inst? comes as
   the fn or, quoted, the symbol. A collection is stored serialised, which a
   255-character string does not hold (BOU-715)."
  {:uuid :uuid :string :string :re :string :int :int :bigint :bigint
   :double :double :boolean :boolean :text :text :enum :enum
   :map :json
   :map-of :text :vector :text :sequential :text :set :text :tuple :text :any :text
   inst? :instant 'inst? :instant})

;; =============================================================================
;; Malli Schema Field Processing
;; =============================================================================

(defn- extract-field-info
  "Extract field information from Malli schema field definition.

   Handles [:maybe X] unwrapping so that [:maybe inst?] resolves to inst?
   and [:maybe [:enum ...]] resolves to :enum with the enum values preserved.

   Args:
     field-def: Malli field definition - [field-name schema] or [field-name properties schema]

   Returns:
     Map with :name, :type, :optional?, :schema, :max (a `{:max n}` on the
     type) or nil for non-field entries"
  [field-def]
  (when (and (vector? field-def)
             (>= (count field-def) 2)
             (keyword? (first field-def)))
    (let [field-name (name (first field-def))
          ;; Handle both [name type] and [name properties type] formats
          has-properties? (and (= 3 (count field-def)) (map? (second field-def)))
          field-type (if has-properties?
                       (nth field-def 2)
                       (second field-def))
          properties (if has-properties? (second field-def) {})
          ;; Unwrap [:maybe X] → X (also implies nullable)
          maybe? (and (vector? field-type) (= :maybe (first field-type)))
          unwrapped-type (if maybe? (second field-type) field-type)
          ;; Extract type from vector schemas like [:enum :admin :user]
          actual-type (if (vector? unwrapped-type) (first unwrapped-type) unwrapped-type)
          optional? (or (boolean (:optional properties)) maybe?)]
      {:name field-name
       :type actual-type
       :optional? optional?
       :properties properties
       :schema unwrapped-type
       :max (when (and (vector? unwrapped-type) (map? (second unwrapped-type)))
              (:max (second unwrapped-type)))})))

;; =============================================================================
;; DDL Generation
;; =============================================================================

(defn- enum-field-values
  "Return the enum value strings for a field whose schema is [:enum ...], else nil.

   Handles both [:enum :a :b] and [:enum {props} :a :b] forms and coerces
   keyword/string/number values to their SQL literal string form."
  [{:keys [schema]}]
  (when (and (vector? schema) (= :enum (first schema)))
    (let [schema-rest (rest schema)
          ;; Skip a leading properties map: [:enum {...} :val1 :val2]
          raw-values (if (and (seq schema-rest) (map? (first schema-rest)))
                       (rest schema-rest)
                       schema-rest)]
      (->> raw-values
           (filter #(or (keyword? %) (string? %) (number? %)))
           (map (fn [v]
                  (cond
                    (keyword? v) (clojure.core/name v) ; :admin -> "admin"
                    (string? v) v ; already a string
                    :else (str v)))) ; fallback to str
           (into [])))))

(defn- enum-check-clause
  "SQL CHECK clause body for an enum column, e.g. \"role IN ('admin', 'user')\"."
  [column-name values]
  (str column-name " IN (" (str/join ", " (map #(str "'" % "'") values)) ")"))

(defn- enum-constraint-name
  "Deterministic, stable name for an enum column's CHECK constraint.

   Named (rather than inline/anonymous) so it can be dropped and re-added
   idempotently — see enum-constraint-repair-ddls."
  [table-name column-name]
  (str "chk_" table-name "_" column-name))

(def ^:private longest-varchar
  "The longest `[:string {:max n}]` kept as a VARCHAR. Longer is TEXT: MySQL
   counts every VARCHAR towards a 65,535-byte row, four bytes a character."
  1024)

(defn- logical-type
  "A field's `logical-column-types` entry: its :column-type, else its Malli
   type's, a string as long as a `{:max n}` above the default."
  [{:keys [type properties max]}]
  (let [max-length (when (= :string type) max)]
    (cond
      (:column-type properties) (:column-type properties)
      ;; Only ever longer than the default: a shorter column would refuse
      ;; what a looser validator elsewhere lets through.
      (not (and (pos-int? max-length) (> max-length protocols/default-string-length)))
      (malli->logical type :string)
      (<= max-length longest-varchar) [:string max-length]
      :else :text)))

(defn- column-type
  "The SQL type of a field: its :db-type, or the adapter's for its
   `logical-type`, so the builder and `column-type` cannot disagree (BOU-610)."
  [ctx {:keys [properties] :as field-info}]
  (or (:db-type properties)
      (protocols/column-type (:adapter ctx) (logical-type field-info))))

(defn- column-default
  "The DEFAULT clause of a field: only a required `active` boolean has one."
  [ctx {:keys [name type optional?]}]
  (if (and (= type :boolean) (= name "active") (not optional?))
    (str " DEFAULT " (protocols/boolean->db (:adapter ctx) true))
    ""))

(defn- generate-column-definition
  "Generate SQL column definition from field info and context.

   Enum CHECK constraints are emitted separately as *named table-level*
   constraints (see generate-table-ddl / enum-constraint-repair-ddls), not
   inline here, so schema initialization can repair them idempotently.

   Args:
     ctx: Database context
     field-info: Field info map from extract-field-info

   Returns:
     String column definition"
  [ctx {:keys [name optional?] :as field-info}]
  (let [column-name (col-name name) ; Convert kebab-case to snake_case
        not-null (if optional? "" " NOT NULL")
        primary-key (if (= name "id") " PRIMARY KEY" "")]
    (str column-name " " (column-type ctx field-info) not-null primary-key
         (column-default ctx field-info))))

(defn- generate-enum-constraints
  "Generate named table-level CHECK constraint definitions for enum fields.

   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps

   Returns:
     Vector of \"CONSTRAINT <name> CHECK(<col> IN (...))\" definition strings"
  [table-name field-infos]
  (->> field-infos
       (keep (fn [{:keys [name] :as field-info}]
               (when-let [values (enum-field-values field-info)]
                 (let [column-name (col-name name)]
                   (str "CONSTRAINT " (enum-constraint-name table-name column-name)
                        " CHECK(" (enum-check-clause column-name values) ")")))))
       (into [])))

(def ^:private table-unique-keys
  "Table-level unique keys a schema does not express, as [name [column ...]]."
  {"auth_users"    [["uk_auth_users_email" ["email"]]]
   "user_sessions" [["uk_user_sessions_token" ["session_token"]]]})

(defn- foreign-key-constraints
  "A FOREIGN KEY to `<table>(id)` for each field whose properties say
   `:references <table>`, with `:on-delete :cascade` (or `:set-null`) when
   given. Only a table being created gets one: an existing table is not
   altered."
  [table-name field-infos]
  (for [{:keys [name properties]} field-infos
        :let [{:keys [references on-delete]} properties]
        :when references]
    (str "CONSTRAINT fk_" table-name "_" (col-name name)
         " FOREIGN KEY (" (col-name name) ") REFERENCES " (clojure.core/name references) "(id)"
         (when on-delete
           (str " ON DELETE " (text/upper-case (str/replace (clojure.core/name on-delete) "-" " ")))))))

(defn- generate-table-constraints
  "Generate table-level constraints (unique, foreign keys) based on table name and fields.
   
   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps
     
   Returns:
     Vector of constraint definition strings"
  [table-name field-infos]
  (vec (concat
        (foreign-key-constraints table-name field-infos)

        ; Table-specific unique constraints
        (for [[constraint-name columns] (table-unique-keys table-name)]
          (str "CONSTRAINT " constraint-name " UNIQUE(" (str/join ", " columns) ")")))))

(defn generate-table-ddl
  "Generate CREATE TABLE DDL from Malli schema definition.
   
   Args:
     ctx: Database context
     table-name: String name for the table
     malli-schema: Malli schema definition ([:map ...] form)
     
   Returns:
     String DDL statement
     
   Example:
     (generate-table-ddl ctx \"users\" User-schema)"
  [ctx table-name malli-schema]
  (let [; Extract fields from Malli schema (skip :map and properties)
        fields (rest malli-schema)
        field-infos (->> fields
                         (map extract-field-info)
                         (filter some?))

        ; Generate column definitions
        column-defs (map #(generate-column-definition ctx %) field-infos)

        ; Generate constraints (named enum CHECK constraints + table-specific unique/FK)
        enum-constraints (generate-enum-constraints table-name field-infos)
        constraints (generate-table-constraints table-name field-infos)

        ; Combine all definitions
        all-definitions (concat column-defs enum-constraints constraints)
        definitions-str (str/join ",\n  " all-definitions)]

    (str "CREATE TABLE IF NOT EXISTS " table-name " (\n  "
         definitions-str
         "\n);")))

(defn- generate-table-indexes
  "The indexes a table gets from its field analysis, as [name table columns].

   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps

   Returns:
     Vector of [index-name table-name [column ...]]"
  [table-name field-infos]
  (let [; Categorize fields for automatic index generation
        id-fields (filter #(str/ends-with? (:name %) "-id") field-infos)
        enum-fields (filter #(and (vector? (:schema %))
                                  (= :enum (first (:schema %)))) field-infos)
        timestamp-fields (filter #(str/ends-with? (:name %) "-at") field-infos)
        single (fn [{:keys [name]}]
                 [(str "idx_" table-name "_" (col-name name)) table-name [(col-name name)]])]
    (vec (concat
          ; Foreign key, enum and timestamp fields
          (map single (concat id-fields enum-fields timestamp-fields))

          ; Table-specific indexes
          (case table-name
            "auth_users"    [["idx_auth_users_email" "auth_users" ["email"]]
                             ["idx_auth_users_active" "auth_users" ["active"]]]
            "users"         [["idx_users_role" "users" ["role"]]]
            "user_sessions" [["idx_sessions_token" "user_sessions" ["session_token"]]
                             ["idx_sessions_expires_at" "user_sessions" ["expires_at"]]]
            [])))))

(defn generate-indexes
  "The indexes for `malli-schema`'s table, as [index-name table [column ...]]."
  [table-name malli-schema]
  (generate-table-indexes table-name (keep extract-field-info (rest malli-schema))))

;; =============================================================================
;; Idempotent Enum-Constraint Repair
;; =============================================================================

(def ^:private constraint-repair-engines
  "Engines that need — and support — idempotent re-application of named enum
   CHECK constraints on re-init.

   Only H2 qualifies: it is the sole engine that corrupts CHECK
   constraint clauses when a fresh pool re-opens a persisted in-memory DB.
   PostgreSQL and MySQL keep their constraints intact across reconnects and
   treat a repeat CREATE TABLE IF NOT EXISTS as a no-op, so re-adding
   constraints on every boot would only churn table locks for no benefit;
   SQLite cannot ALTER add/drop CHECK constraints at all."
  #{:h2})

(defn- enum-constraint-repair-ddls
  "DROP-then-ADD DDL pairs that idempotently (re)establish named enum CHECK
   constraints on an already-existing table.

   This repairs a corruption H2 exhibits for in-memory `DB_CLOSE_DELAY=-1`
   databases: when a fresh connection pool re-opens a persisted in-memory DB,
   CHECK constraint clauses are reloaded empty and reject even valid values
   (`Check constraint invalid: \"chk_...: \"`). Re-running CREATE TABLE
   IF NOT EXISTS is a no-op and does not repair them; dropping and re-adding
   the *named* constraint does.

   Note: only heals constraints created by this (named) code path. A DB created
   by an older version that emitted inline anonymous CHECKs (CONSTRAINT_<n>)
   would keep those — a non-issue in practice since the only affected dialect,
   H2, is used solely for ephemeral in-memory test DBs that are always recreated
   by the current code.

   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps

   Returns:
     Vector of DDL statement strings (drop, add, drop, add, ...)"
  [table-name field-infos]
  (->> field-infos
       (mapcat (fn [{:keys [name] :as field-info}]
                 (when-let [values (enum-field-values field-info)]
                   (let [column-name (col-name name)
                         constraint-name (enum-constraint-name table-name column-name)]
                     [(str "ALTER TABLE " table-name
                           " DROP CONSTRAINT IF EXISTS " constraint-name)
                      (str "ALTER TABLE " table-name
                           " ADD CONSTRAINT " constraint-name
                           " CHECK(" (enum-check-clause column-name values) ")")]))))
       (into [])))

(defn- repair-enum-constraints!
  "Re-apply named enum CHECK constraints for `table-name` when the engine
   needs it. Called only for tables that already existed before this init,
   making schema initialization idempotent and healing H2 reconnect corruption."
  [ctx table-name malli-schema]
  (when (contains? constraint-repair-engines (protocols/engine (:adapter ctx)))
    (let [field-infos (->> (rest malli-schema)
                           (map extract-field-info)
                           (filter some?))]
      (doseq [ddl (enum-constraint-repair-ddls table-name field-infos)]
        (log/debug "Repairing enum constraint" {:table table-name :ddl ddl})
        (db-core/execute-ddl! ctx ddl)))))

;; =============================================================================
;; Missing Columns
;; =============================================================================

(defn- add-missing-columns!
  "Add the fields of `malli-schema` an existing table lacks (BOU-622), nullable:
   a NOT NULL column cannot join a table with rows, so a migration backfills it."
  [ctx table-name malli-schema columns]
  (let [present (set (map :name columns))]
    (when-let [missing (and (seq present)
                            (seq (remove #(present (text/lower-case (col-name (:name %))))
                                         (keep extract-field-info (rest malli-schema)))))]
      (doseq [{:keys [name optional?]} missing :when (not optional?)]
        (log/warn "Added a required field as nullable: backfill it and add NOT NULL in a migration"
                  {:table table-name :column (col-name name)}))
      (db-core/add-columns-if-not-exist! ctx table-name
                                         (mapv (fn [f] [(col-name (:name f))
                                                        (str (column-type ctx f) (column-default ctx f))])
                                               missing)
                                         present)
      ;; The constraints CREATE TABLE gives them, so an upgraded database
      ;; enforces what a fresh one does (BOU-632).
      (let [added (set (map #(col-name (:name %)) missing))]
        (doseq [{:keys [name] :as f} missing
                :let [values (enum-field-values f)]
                :when values]
          (if (= :sqlite (protocols/engine (:adapter ctx)))
            (log/info "SQLite cannot add a CHECK to an existing table; the enum is not enforced"
                      {:table table-name :column (col-name name)})
            (db-core/add-check-if-not-exists! ctx table-name
                                              (enum-constraint-name table-name (col-name name))
                                              (enum-check-clause (col-name name) values))))
        (doseq [[constraint-name columns] (table-unique-keys table-name)
                :when (some added columns)]
          (db-core/create-index-if-not-exists! ctx constraint-name table-name columns {:unique? true}))))))

;; =============================================================================
;; Short Columns
;; =============================================================================

(def ^:private text-length
  "How long a :text field's column need hold before it counts as wide enough:
   MySQL's TEXT. Longer TEXT types and unbounded VARCHARs are left alone."
  65535)

(defn- wanted-length
  "How long a field's column should hold, or nil when it is no string column
   the builder widens: a JSON or :db-type one is left to a migration."
  [{:keys [properties] :as field-info}]
  (when-not (:db-type properties)
    (let [logical (logical-type field-info)]
      (cond (= :text logical)   text-length
            (= :string logical) protocols/default-string-length
            (vector? logical)   (second logical)))))

(defn- widen-short-columns!
  "Widen the VARCHAR columns of an existing table shorter than its schema now
   asks, so a database created before a field grew holds what a fresh one does
   (BOU-715). Only ever wider: rows are kept. A column the database will not
   alter, say one a view reads, is logged and left, so boot goes on."
  [ctx table-name malli-schema columns]
  (let [present (into {} (map (juxt :name identity)) columns)
        mysql?  (= :mysql (protocols/engine (:adapter ctx)))]
    (doseq [f (keep extract-field-info (rest malli-schema))
            :let [column (col-name (:name f))
                  {:keys [max-length not-null default]} (present (text/lower-case column))
                  wanted (wanted-length f)]
            :when (and wanted max-length (< max-length wanted))]
      (if (and mysql? (some? default))
        (log/warn "Not widening a column with a default: MySQL's MODIFY would drop it; widen it in a migration"
                  {:table table-name :column column :max-length max-length :wanted wanted})
        (try
          (db-core/widen-column! ctx table-name column (column-type ctx f) not-null)
          (catch Exception e
            (log/error e "Could not widen a column; widen it in a migration"
                       {:table table-name :column column :max-length max-length :wanted wanted})))))))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

(defn- referenced-tables
  "The tables `malli-schema`'s fields name under `:references`."
  [malli-schema]
  (into #{} (keep #(some-> (get-in % [:properties :references]) name))
        (keep extract-field-info (rest malli-schema))))

(defn- creation-order
  "`schema-definitions` as [table schema] pairs, each after the tables in the
   map it references, so its foreign keys have something to point at."
  [schema-definitions]
  (loop [pending (vec schema-definitions) done [] created #{}]
    (if (empty? pending)
      done
      (let [ready? (fn [[t s]] (every? #(or (created %) (= % t) (not (contains? schema-definitions %)))
                                       (referenced-tables s)))
            [ready waiting] ((juxt filter remove) ready? pending)]
        (if (empty? ready)
          (into done waiting)
          (recur (vec waiting) (into done ready) (into created (map first ready))))))))

(defn initialize-tables-from-schemas!
  "Initialize database tables and indexes from a map of Malli schema definitions.
   
   Args:
     ctx: Database context
     schema-definitions: Map of table-name -> malli-schema
     
   Returns:
     nil
     
   Throws:
     Exception if schema initialization fails
     
   Example:
     (initialize-tables-from-schemas! ctx 
       {\"users\" User-schema
        \"user_sessions\" UserSession-schema})"
  [ctx schema-definitions]
  (log/info "Initializing database schema from Malli definitions"
            {:engine (protocols/engine (:adapter ctx))
             :tables (keys schema-definitions)})

  (try
    ; Create tables. For tables that already exist, CREATE TABLE IF NOT EXISTS is
    ; a no-op, so add the fields the schema gained since, widen those it
    ; lengthened, and re-apply named enum
    ; CHECK constraints to keep initialization idempotent (and to heal H2's
    ; in-memory reconnect corruption).
    (doseq [[table-name malli-schema] (creation-order schema-definitions)]
      (let [existed? (db-core/table-exists? ctx table-name)
            ddl (generate-table-ddl ctx table-name malli-schema)]
        (log/debug "Creating table" {:table table-name :ddl ddl :existed? existed?})
        (db-core/execute-ddl! ctx ddl)
        (when existed?
          (let [columns (db-core/get-table-info ctx table-name)]
            (add-missing-columns! ctx table-name malli-schema columns)
            (widen-short-columns! ctx table-name malli-schema columns))
          (repair-enum-constraints! ctx table-name malli-schema))))

    ; Create indexes
    (doseq [[table-name malli-schema] schema-definitions]
      (doseq [[index table columns] (generate-indexes table-name malli-schema)]
        (log/debug "Creating index" {:table table :index index})
        (db-core/create-index-if-not-exists! ctx index table columns)))

    (log/info "Database schema initialization completed successfully"
              {:engine (protocols/engine (:adapter ctx))
               :tables (keys schema-definitions)})

    (catch Exception e
      (log/error "Database schema initialization failed"
                 {:error (.getMessage e)
                  :engine (protocols/engine (:adapter ctx))})
      (throw e))))
