(ns wagoe.platform.shell.adapters.database.utils.schema
  "Schema-to-DDL generation utilities for database adapters.
   
   This namespace provides utilities for generating database DDL statements
   from Malli schema definitions. It handles:
   - Column type mapping based on database dialect
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
  (:require [wagoe.platform.shell.adapters.database.protocols :as protocols]
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

(defn- malli-type->column-type
  "Map Malli schema types to database column types based on dialect.

   Uses condp instead of case because inst? is a symbol (not a keyword)
   and case only supports compile-time constant dispatch.

   Args:
     ctx: Database context with adapter
     malli-type: Malli type (keyword like :uuid, :string or symbol like inst?)

   Returns:
     String column type definition"
  [ctx malli-type]
  (let [adapter (:adapter ctx)
        ;; PostgreSQL adapter returns nil for dialect (HoneySQL default),
        ;; normalize to :postgresql so case dispatch works correctly
        dialect (or (protocols/dialect adapter) :postgresql)]
    (condp = malli-type
      :uuid (case dialect
              (:sqlite :mysql) "CHAR(36)"
              (:postgresql :h2 :ansi) "UUID"
              "VARCHAR(36)")

      :string (case dialect
                :sqlite "TEXT"
                "VARCHAR(255)")

      :int (case dialect
             :sqlite "INTEGER"
             :mysql "INT"
             (:postgresql :h2 :ansi) "INTEGER"
             "INTEGER")

      :bigint (case dialect
                :sqlite "INTEGER"
                :mysql "BIGINT"
                (:postgresql :h2 :ansi) "BIGINT"
                "BIGINT")

      :boolean (case dialect
                 :sqlite "INTEGER"
                 :mysql "TINYINT(1)"
                 (:postgresql :h2 :ansi) "BOOLEAN"
                 "BOOLEAN")

      :text (case dialect
              :sqlite "TEXT"
              :mysql "LONGTEXT"
              :postgresql "TEXT"
              (:h2 :ansi) "CLOB"
              "TEXT")

      inst? (case dialect
              :sqlite "TEXT"
              :mysql "DATETIME"
              :postgresql "TIMESTAMPTZ"
              (:h2 :ansi) "TIMESTAMP WITH TIME ZONE"
              "TIMESTAMP")

      'inst? (case dialect
               :sqlite "TEXT"
               :mysql "DATETIME"
               :postgresql "TIMESTAMPTZ"
               (:h2 :ansi) "TIMESTAMP WITH TIME ZONE"
               "TIMESTAMP")

      :enum (case dialect
              :sqlite "TEXT"
              "VARCHAR(50)")

      :double (case dialect
                :sqlite "REAL"
                :mysql "DOUBLE"
                (:postgresql :h2 :ansi) "DOUBLE PRECISION"
                "DOUBLE PRECISION")

      :map (case dialect
             :sqlite "TEXT"
             :mysql "JSON"
             :postgresql "JSONB"
             (:h2 :ansi) "CLOB"
             "TEXT")

      :re (case dialect
            :sqlite "TEXT"
            "VARCHAR(255)")

      ;; Default fallback for unknown types
      (case dialect
        :sqlite "TEXT"
        :mysql "VARCHAR(255)"
        (:postgresql :h2 :ansi) "VARCHAR(255)"
        "TEXT"))))

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
     Map with :name, :type, :optional?, :schema or nil for non-field entries"
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
       :schema unwrapped-type})))

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
  [ctx {:keys [name type optional? properties]}]
  (let [column-name (col-name name) ; Convert kebab-case to snake_case
        column-type (or (:db-type properties)
                        (malli-type->column-type ctx type))
        not-null (if optional? "" " NOT NULL")
        primary-key (if (= name "id") " PRIMARY KEY" "")
        ; Handle boolean defaults for active fields
        boolean-default (if (and (= type :boolean) (= name "active") (not optional?))
                          (case (or (protocols/dialect (:adapter ctx)) :postgresql)
                            :sqlite " DEFAULT 1"
                            " DEFAULT true")
                          "")]
    (str column-name " " column-type not-null primary-key boolean-default)))

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

(defn- generate-table-constraints
  "Generate table-level constraints (unique, foreign keys) based on table name and fields.
   
   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps
     
   Returns:
     Vector of constraint definition strings"
  [table-name field-infos]
  (let [; Find foreign key fields (fields ending with -id but not id itself)
        _fk-fields (filter #(and (not= (:name %) "id")
                                 (str/ends-with? (:name %) "-id"))
                           field-infos)]
    (vec (concat
          ; Foreign key constraints - DISABLED FOR NOW
          ; (uncomment when tenant table is implemented)
          ; (map (fn [{:keys [name]}]
          ;        (let [ref-table (str/replace name #"-id$" "s") ; user-id -> users
          ;              constraint-name (str "fk_" table-name "_" (col-name name))]
          ;          (str "CONSTRAINT " constraint-name
          ;               " FOREIGN KEY (" (col-name name)
          ;               ") REFERENCES " ref-table "(id) ON DELETE CASCADE")))
          ;      fk-fields)
          []

          ; Table-specific unique constraints
          (case table-name
            "auth_users" ["CONSTRAINT uk_auth_users_email UNIQUE(email)"]
            "user_sessions" ["CONSTRAINT uk_user_sessions_token UNIQUE(session_token)"]
            [])))))

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
  "Generate CREATE INDEX statements based on table name and field analysis.
   
   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps
     
   Returns:
     Vector of DDL index statements"
  [table-name field-infos]
  (let [; Categorize fields for automatic index generation
        id-fields (filter #(str/ends-with? (:name %) "-id") field-infos)
        enum-fields (filter #(and (vector? (:schema %))
                                  (= :enum (first (:schema %)))) field-infos)
        timestamp-fields (filter #(str/ends-with? (:name %) "-at") field-infos)]

    (vec (concat
          ; Indexes on foreign key fields
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               id-fields)

          ; Indexes on enum fields (like role, active)
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               enum-fields)

          ; Indexes on timestamp fields
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               timestamp-fields)

          ; Table-specific compound indexes
          (case table-name
            "auth_users"
            ["CREATE INDEX IF NOT EXISTS idx_auth_users_email ON auth_users (email)"
             "CREATE INDEX IF NOT EXISTS idx_auth_users_active ON auth_users (active)"]

            "users"
            ["CREATE INDEX IF NOT EXISTS idx_users_role ON users (role)"]

            "user_sessions"
            ["CREATE INDEX IF NOT EXISTS idx_sessions_token ON user_sessions (session_token)"
             "CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON user_sessions (expires_at)"]

            [])))))

(defn generate-indexes-ddl
  "Generate CREATE INDEX statements from Malli schema analysis.
   
   Args:
     ctx: Database context
     table-name: String name of the table
     malli-schema: Malli schema definition
     
   Returns:
     Vector of DDL index statements"
  [_ctx table-name malli-schema]
  (let [fields (rest malli-schema)
        field-infos (->> fields
                         (map extract-field-info)
                         (filter some?))]
    (generate-table-indexes table-name field-infos)))

;; =============================================================================
;; Idempotent Enum-Constraint Repair
;; =============================================================================

(def ^:private constraint-repair-dialects
  "Dialects that need — and support — idempotent re-application of named enum
   CHECK constraints on re-init.

   Only :ansi (H2) qualifies: it is the sole dialect that corrupts CHECK
   constraint clauses when a fresh pool re-opens a persisted in-memory DB.
   PostgreSQL and MySQL keep their constraints intact across reconnects and
   treat a repeat CREATE TABLE IF NOT EXISTS as a no-op, so re-adding
   constraints on every boot would only churn table locks for no benefit;
   SQLite cannot ALTER add/drop CHECK constraints at all."
  #{:ansi})

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
  "Re-apply named enum CHECK constraints for `table-name` when the dialect
   supports it. Called only for tables that already existed before this init,
   making schema initialization idempotent and healing H2 reconnect corruption."
  [ctx table-name malli-schema]
  (let [dialect (or (protocols/dialect (:adapter ctx)) :postgresql)]
    (when (contains? constraint-repair-dialects dialect)
      (let [field-infos (->> (rest malli-schema)
                             (map extract-field-info)
                             (filter some?))]
        (doseq [ddl (enum-constraint-repair-ddls table-name field-infos)]
          (log/debug "Repairing enum constraint" {:table table-name :ddl ddl})
          (db-core/execute-ddl! ctx ddl))))))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

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
            {:dialect (protocols/dialect (:adapter ctx))
             :tables (keys schema-definitions)})

  (try
    ; Create tables. For tables that already exist, CREATE TABLE IF NOT EXISTS is
    ; a no-op, so re-apply named enum CHECK constraints to keep initialization
    ; idempotent (and to heal H2's in-memory reconnect corruption).
    (doseq [[table-name malli-schema] schema-definitions]
      (let [existed? (db-core/table-exists? ctx table-name)
            ddl (generate-table-ddl ctx table-name malli-schema)]
        (log/debug "Creating table" {:table table-name :ddl ddl :existed? existed?})
        (db-core/execute-ddl! ctx ddl)
        (when existed?
          (repair-enum-constraints! ctx table-name malli-schema))))

    ; Create indexes
    (doseq [[table-name malli-schema] schema-definitions]
      (let [index-ddls (generate-indexes-ddl ctx table-name malli-schema)]
        (doseq [index-ddl index-ddls]
          (log/debug "Creating index" {:table table-name :ddl index-ddl})
          (db-core/execute-ddl! ctx index-ddl))))

    (log/info "Database schema initialization completed successfully"
              {:dialect (protocols/dialect (:adapter ctx))
               :tables (keys schema-definitions)})

    (catch Exception e
      (log/error "Database schema initialization failed"
                 {:error (.getMessage e)
                  :dialect (protocols/dialect (:adapter ctx))})
      (throw e))))
