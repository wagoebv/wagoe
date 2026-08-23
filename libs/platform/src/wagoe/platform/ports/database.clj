(ns wagoe.platform.ports.database
  "The database port: what a database adapter must provide.

   Lived at `wagoe.platform.ports.database` until BOU-303,
   which is where a port cannot live — every module's persistence layer had to
   reach into platform's shell to name it, and `.wagoe/check-ports.edn` carried
   the 20 sites that did as its largest burn-down entry.

   Unlike the router abstraction ADR-037 removed, this seam is real: four
   adapters implement it (postgresql, mysql, sqlite, h2), and the differences
   between them — dialect, boolean representation, JDBC URL shape — are exactly
   what the methods below name.

   For executing queries rather than describing a database, see
   `wagoe.platform.database`.

   Protocol defining the common interface for database adapters.
   
   This protocol abstracts database-specific behavior while allowing 
   common database operations to be implemented in a shared core namespace.
   Each database type (SQLite, PostgreSQL, MySQL, H2) implements this 
   protocol to provide database-specific functionality.
   
   Design Philosophy:
   - Keep protocol narrow - prefer common behavior in core
   - Only include methods that differ between databases
   - Database-agnostic operations belong in core namespace
   - Type conversions use shared utilities where possible"
  (:require [malli.core :as m]))

;; =============================================================================
;; Core Database Adapter Protocol
;; =============================================================================

(defprotocol DBAdapter
  "Protocol defining database-specific behavior for multi-database support.
   
   Each database adapter (SQLite, PostgreSQL, MySQL, H2) implements this
   protocol to provide database-specific functionality while common operations
   are handled by the shared database core namespace."

  (dialect [this]
    "Return the HoneySQL dialect keyword for this database.
     
     Returns:
       Keyword - :sqlite, :postgresql, :mysql, :h2, or nil (for PostgreSQL default)
       
     Note:
       PostgreSQL adapter returns nil to use HoneySQL's default dialect
       
     Example:
       (dialect sqlite-adapter) ;; => :sqlite
       (dialect postgres-adapter) ;; => nil")

  (jdbc-driver [this]
    "Return the JDBC driver class string for this database.
     
     Returns:
       String - fully qualified JDBC driver class name
       
     Example:
       (jdbc-driver sqlite-adapter) ;; => \"org.sqlite.JDBC\"")

  (jdbc-url [this db-config]
    "Generate JDBC URL string from database configuration.
     
     Args:
       db-config: Database configuration map
       
     Returns:
       String - JDBC URL formatted for this database type
       
     Example:
       (jdbc-url postgres-adapter {:host \"localhost\" :port 5432 :name \"mydb\"})
       ;; => \"jdbc:postgresql://localhost:5432/mydb\"")

  (pool-defaults [this]
    "Return default connection pool settings optimized for this database.
     
     Returns:
       Map - HikariCP pool configuration defaults
       
     Example:
       (pool-defaults sqlite-adapter)
       ;; => {:minimum-idle 1 :maximum-pool-size 5 :connection-timeout-ms 30000}")

  (init-connection! [this datasource db-config]
    "Initialize database connection with database-specific settings.
     
     Performs one-time connection initialization such as:
     - SQLite: Apply PRAGMA settings
     - PostgreSQL: Set application_name, timezone
     - MySQL: Set sql_mode, timezone
     - H2: Set MODE, timezone
     
     Args:
       datasource: Database connection pool or connection
       db-config: Database configuration map
       
     Returns:
       nil - side effects only
       
     Example:
       (init-connection! sqlite-adapter datasource config)")

  (build-where [this filters]
    "Build database-specific WHERE clause conditions from filter map.
     
     Handles database-specific differences such as:
     - PostgreSQL: Use ILIKE for case-insensitive string matching
     - Others: Use LIKE for string matching
     - Boolean handling varies by database
     
     Args:
       filters: Map of field -> value filters
       
     Returns:
       HoneySQL WHERE clause fragment or nil
       
     Example:
       (build-where postgres-adapter {:name \"john\" :active true})
       ;; => [:and [:ilike :name \"%john%\"] [:= :active true]]")

  (boolean->db [this boolean-value]
    "Convert boolean value to database-specific representation.
     
     Args:
       boolean-value: Boolean value (true/false/nil)
       
     Returns:
       Database-specific boolean representation
       - SQLite/MySQL: 1/0
       - PostgreSQL/H2: true/false
       
     Example:
       (boolean->db sqlite-adapter true) ;; => 1
       (boolean->db postgres-adapter true) ;; => true")

  (db->boolean [this db-value]
    "Convert database boolean representation to Clojure boolean.
     
     Args:
       db-value: Database boolean value
       
     Returns:
       Boolean - true/false
       
     Example:
       (db->boolean sqlite-adapter 1) ;; => true
       (db->boolean postgres-adapter true) ;; => true")

  (table-exists? [this datasource table-name]
    "Check if a table exists in the database.
     
     Uses database-specific table introspection:
     - SQLite: Query sqlite_master
     - PostgreSQL: Query information_schema.tables
     - MySQL: Query information_schema.tables
     - H2: Query INFORMATION_SCHEMA.TABLES
     
     Args:
       datasource: Database connection pool or connection
       table-name: String or keyword table name
       
     Returns:
       Boolean - true if table exists
       
     Example:
       (table-exists? sqlite-adapter ds :users) ;; => true")

  (get-table-info [this datasource table-name]
    "Get column information for a table.
     
     Uses database-specific column introspection to return standardized
     column information including name, type, constraints, and primary key status.
     
     Args:
       datasource: Database connection pool or connection
       table-name: String or keyword table name
       
     Returns:
       Vector of column info maps with keys:
       - :name - column name string
       - :type - database type string
       - :not-null - boolean
       - :default - default value or nil
       - :primary-key - boolean
       
     Example:
       (get-table-info sqlite-adapter ds :users)
       ;; => [{:name \"id\" :type \"TEXT\" :not-null true :primary-key true} ...]"))

;; =============================================================================
;; Configuration Schemas (Malli)
;; =============================================================================

(def PoolConfig
  "Schema for connection pool configuration."
  [:map {:closed true}
   [:minimum-idle {:optional true} pos-int?]
   [:maximum-pool-size {:optional true} pos-int?]
   [:connection-timeout-ms {:optional true} pos-int?]
   [:idle-timeout-ms {:optional true} pos-int?]
   [:max-lifetime-ms {:optional true} pos-int?]])

(def EmbeddedDBConfig
  "Schema for embedded database configuration (SQLite, H2 file/memory)."
  [:map {:title "Embedded Database Configuration"}
   [:adapter [:enum :sqlite :h2]]
   [:database-path string?]
   [:pool {:optional true} PoolConfig]])

(def ServerDBConfig
  "Schema for server-based database configuration (PostgreSQL, MySQL, H2 server)."
  [:map {:title "Server Database Configuration"}
   [:adapter [:enum :postgresql :mysql :h2]]
   [:host string?]
   [:port pos-int?]
   [:name string?]  ; Database name is required for server databases
   [:username {:optional true} string?]
   [:password {:optional true} string?]
   [:connection-params {:optional true} map?]  ; For database-specific connection parameters
   [:pool {:optional true} PoolConfig]])

(def DBConfig
  "Schema for database configuration - supports embedded and server-based databases.

   Embedded databases (SQLite, H2 file/memory):
   - Require :adapter and :database-path
   - database-path can be file path or 'mem:name' for H2

   Server databases (PostgreSQL, MySQL, H2 server):
   - Require :adapter, :host, :port, and :name
   - Optional :username, :password, :connection-params"
  [:or
   {:error/message "Database configuration must be either embedded (SQLite/H2) or server-based (PostgreSQL/MySQL/H2)"}
   EmbeddedDBConfig
   ServerDBConfig])

(def ^:private db-config-validator (m/validator DBConfig))
(def ^:private db-config-explainer (m/explainer DBConfig))

(defn validate-db-config
  "Validate database configuration against Malli schema.

   Args:
     db-config: Database configuration map

   Returns:
     db-config if valid

   Throws:
     ExceptionInfo with detailed validation errors if configuration is invalid

   Example:
     (validate-db-config {:adapter :sqlite :database-path \"db.sqlite\"})
     (validate-db-config {:adapter :postgresql :host \"localhost\" :port 5432 :name \"mydb\"})"
  [db-config]
  (if (db-config-validator db-config)
    db-config
    (throw (ex-info "Invalid database configuration"
                    {:type :configuration-error
                     :config db-config
                     :errors (db-config-explainer db-config)}))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn valid-db-config?
  "Check if database configuration is valid without throwing exceptions.

   Args:
     db-config: Database configuration map

   Returns:
     Boolean - true if configuration is valid

   Example:
     (valid-db-config? {:adapter :sqlite :database-path \"db.sqlite\"}) ;; => true
     (valid-db-config? {:adapter :postgresql :host \"localhost\" :port 5432 :name \"mydb\"}) ;; => true"
  [db-config]
  (db-config-validator db-config))

(defn explain-db-config
  "Get detailed validation errors for invalid database configuration.

   Args:
     db-config: Database configuration map

   Returns:
     Malli explanation map or nil if valid

   Example:
     (explain-db-config {:adapter :invalid})"
  [db-config]
  (when-not (db-config-validator db-config)
    (db-config-explainer db-config)))

(defn embedded-db-config?
  "Check if configuration is for an embedded database (SQLite or H2 file/memory).

   Args:
     db-config: Database configuration map

   Returns:
     Boolean - true if embedded database configuration

   Example:
     (embedded-db-config? {:adapter :sqlite :database-path \"./app.db\"}) ;; => true
     (embedded-db-config? {:adapter :h2 :database-path \"mem:testdb\"}) ;; => true"
  [db-config]
  (and (contains? #{:sqlite :h2} (:adapter db-config))
       (contains? db-config :database-path)))

(defn server-db-config?
  "Check if configuration is for server-based database (PostgreSQL, MySQL, H2 server).

   Args:
     db-config: Database configuration map

   Returns:
     Boolean - true if server-based database configuration

   Example:
     (server-db-config? {:adapter :postgresql :host \"localhost\" :port 5432 :name \"mydb\"}) ;; => true"
  [db-config]
  (and (contains? #{:postgresql :mysql :h2} (:adapter db-config))
       (contains? db-config :host)
       (contains? db-config :port)
       (contains? db-config :name)))

(defn get-adapter-type
  "Get the adapter type from database configuration.

   Args:
     db-config: Database configuration map

   Returns:
     Keyword - :sqlite, :h2, :postgresql, or :mysql

   Example:
     (get-adapter-type {:adapter :sqlite :database-path \"./app.db\"}) ;; => :sqlite"
  [db-config]
  (:adapter db-config))

(defn requires-credentials?
  "Check if the database configuration requires authentication credentials.

   Args:
     db-config: Database configuration map

   Returns:
     Boolean - true if database typically requires username/password

   Example:
     (requires-credentials? {:adapter :postgresql :host \"localhost\"}) ;; => true
     (requires-credentials? {:adapter :sqlite :database-path \"./app.db\"}) ;; => false"
  [db-config]
  (server-db-config? db-config))
