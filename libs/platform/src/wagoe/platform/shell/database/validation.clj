(ns wagoe.platform.shell.database.validation
  "Validation functions for database contexts and configurations.

   Context validation functions check data structure conformity including
   protocol satisfaction, which requires access to the DBAdapter protocol
   from the shell adapters layer."
  (:require [wagoe.platform.shell.adapters.database.protocols :as protocols]))

;; =============================================================================
;; Context Validation
;; =============================================================================

(defn db-context?
  "Check if value is a valid database context structure.

   Checks data structure shape and protocol satisfaction.

   Args:
     ctx: Value to check

   Returns:
     Boolean - true if ctx has correct structure"
  [ctx]
  (and (map? ctx)
       (:datasource ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-db-context
  "Validate database context structure.

   Returns context if valid, throws if invalid.

   Args:
     ctx: Database context to validate

   Returns:
     ctx if valid

   Throws:
     ExceptionInfo if ctx is invalid"
  [ctx]
  (if (db-context? ctx)
    ctx
    (throw (ex-info "Invalid database context"
                    {:type     :internal-error
                     :expected [:datasource :adapter]
                     :got-type (type ctx)
                     :got-keys (when (map? ctx) (keys ctx))}))))

(defn adapter-context?
  "Check if value has a valid adapter (subset of db-context).

   Checks adapter-specific structure.

   Args:
     ctx: Value to check

   Returns:
     Boolean - true if ctx has valid adapter"
  [ctx]
  (and (map? ctx)
       (:adapter ctx)
       (satisfies? protocols/DBAdapter (:adapter ctx))))

(defn validate-adapter-context
  "Validate that context has a valid adapter.

   Returns context if valid, throws if invalid.

   Args:
     ctx: Context to validate

   Returns:
     ctx if valid

   Throws:
     ExceptionInfo if adapter is invalid"
  [ctx]
  (if (adapter-context? ctx)
    ctx
    (throw (ex-info "Invalid adapter context"
                    {:type     :internal-error
                     :expected [:adapter]
                     :got-type (type ctx)
                     :got-keys (when (map? ctx) (keys ctx))}))))

;; =============================================================================
;; Configuration Validation
;; =============================================================================

(defn valid-config-structure?
  "Check if configuration has required structure.

   Args:
     config: Configuration map to check

   Returns:
     Boolean - true if config has :active and :inactive keys"
  [config]
  (and (map? config)
       (contains? config :active)
       (contains? config :inactive)))

(defn validate-sqlite-config
  "Validate SQLite adapter configuration.

   Args:
     config: SQLite configuration map

   Returns:
     nil if valid, error message string if invalid"
  [config]
  (when-not (:db config)
    "SQLite configuration missing :db key"))

(defn validate-postgresql-config
  "Validate PostgreSQL adapter configuration.

   Args:
     config: PostgreSQL configuration map

   Returns:
     nil if valid, error message string if invalid"
  [config]
  (let [required-keys [:host :port :dbname :user :password]]
    (when-let [missing (seq (remove config required-keys))]
      (str "PostgreSQL configuration missing keys: " missing))))

(defn validate-mysql-config
  "Validate MySQL adapter configuration.

   Args:
     config: MySQL configuration map

   Returns:
     nil if valid, error message string if invalid"
  [config]
  (let [required-keys [:host :port :dbname :user :password]]
    (when-let [missing (seq (remove config required-keys))]
      (str "MySQL configuration missing keys: " missing))))

(defn validate-h2-config
  "Validate H2 adapter configuration.

   Args:
     config: H2 configuration map

   Returns:
     nil if valid, error message string if invalid"
  [config]
  (when-not (or (:db config) (:memory config))
    "H2 configuration missing :db or :memory key"))

(defn validate-adapter-config
  "Validate adapter configuration based on adapter type.

   Dispatches to type-specific validators.

   Args:
     adapter-type: Keyword adapter type (:sqlite, :postgresql, etc.)
     config: Configuration map

   Returns:
     nil if valid, error message string if invalid"
  [adapter-type config]
  (case adapter-type
    :sqlite (validate-sqlite-config config)
    :postgresql (validate-postgresql-config config)
    :mysql (validate-mysql-config config)
    :h2 (validate-h2-config config)
    nil))  ; Unknown type, skip validation
