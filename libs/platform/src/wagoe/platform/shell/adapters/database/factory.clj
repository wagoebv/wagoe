(ns wagoe.platform.shell.adapters.database.factory
  "Factory functions for database adapter creation and configuration.
   
   This namespace provides convenient factory functions for creating database
   adapters, data sources, and database contexts. It supports multiple database
   types (SQLite, PostgreSQL, MySQL, H2) through a unified configuration interface.
   
   Key Features:
   - Database adapter creation from configuration
   - Connection pool and datasource management
   - Database context creation with adapter + datasource
   - Resource management with automatic cleanup
   - Configuration validation and error handling
   
   Usage:
     (require '[wagoe.platform.shell.adapters.database.factory :as dbf])
     
     (def ctx (dbf/db-context {:adapter :sqlite :database-path \"./app.db\"}))
     (db/execute-query! ctx {:select [:*] :from [:users]})"
  (:require [wagoe.platform.shell.adapters.database.common.core :as core]
            [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Forward Declarations for Adapter Constructors
;; =============================================================================

;; These will be defined in the respective adapter namespaces
(declare new-sqlite-adapter)
(declare new-postgresql-adapter)
(declare new-mysql-adapter)
(declare new-h2-adapter)

;; =============================================================================
;; Dynamic Adapter Loading
;; =============================================================================

(defn- load-adapter-constructor
  "Dynamically load adapter constructor function.
   
   Args:
     adapter-type: Keyword adapter type (:sqlite, :postgresql, :mysql, :h2)
     
   Returns:
     Constructor function
     
   Throws:
     RuntimeException if adapter cannot be loaded"
  [adapter-type]
  (let [adapter-ns     (case adapter-type
                         :sqlite 'wagoe.platform.shell.adapters.database.sqlite.core
                         :postgresql 'wagoe.platform.shell.adapters.database.postgresql.core
                         :mysql 'wagoe.platform.shell.adapters.database.mysql.core
                         :h2 'wagoe.platform.shell.adapters.database.h2.core)
        constructor-fn (case adapter-type
                         :sqlite 'new-adapter
                         :postgresql 'new-adapter
                         :mysql 'new-adapter
                         :h2 'new-adapter)]

    (try
      (require adapter-ns)
      (let [ns-obj (find-ns adapter-ns)]
        (if ns-obj
          (ns-resolve ns-obj constructor-fn)
          (throw (RuntimeException. (str "Adapter namespace not found: " adapter-ns)))))
      (catch Exception e
        (throw (RuntimeException.
                (str "Failed to load database adapter: " adapter-type
                     ". Error: " (.getMessage e)) e))))))

;; =============================================================================
;; Adapter Factory Functions
;; =============================================================================

(defn create-adapter
  "Create database adapter from configuration.

       Args:
         db-config: Database configuration map with :adapter key

       Returns:
         Database adapter implementing DBAdapter protocol

       Example:
         (create-adapter {:adapter :sqlite :database-path \"./app.db\"})
         (create-adapter {:adapter :postgresql :host \"localhost\" :port 5432
                          :name \"mydb\" :username \"user\" :password \"pass\"})"
  [{:keys [adapter] :as db-config}]
  (protocols/validate-db-config db-config)

  (if-let [constructor-fn (load-adapter-constructor adapter)]
    (do
      (log/debug "Creating database adapter" {:adapter adapter})
      (constructor-fn))
    (throw (IllegalArgumentException.
            (str "Unsupported database adapter: " adapter
                 ". Supported adapters: :sqlite, :postgresql, :mysql, :h2")))))

(defn create-datasource
  "Create connection pool datasource using adapter and configuration.

       Args:
         db-config: Database configuration map

       Returns:
         HikariDataSource connection pool

       Example:
         (create-datasource {:adapter :sqlite :database-path \"./app.db\"})"
  [db-config]
  (let [adapter (create-adapter db-config)]
    (core/create-connection-pool adapter db-config)))

;; =============================================================================
;; Database Context Management
;; =============================================================================

(defn db-context
  "Create database context with adapter and datasource.

       A database context contains both the database adapter and the connection
       pool, providing everything needed for database operations through the core API.

       Args:
         db-config: Database configuration map

       Returns:
         Database context map {:adapter adapter :datasource datasource}

       Example:
         (def ctx (db-context {:adapter :sqlite :database-path \"./app.db\"}))
         (core/execute-query! ctx {:select [:*] :from [:users]})"
  [db-config]
  (protocols/validate-db-config db-config)
  (let [adapter    (create-adapter db-config)
        datasource (core/create-connection-pool adapter db-config)]
    (log/info "Created database context"
              {:adapter (protocols/dialect adapter)})
    {:adapter    adapter
     :datasource datasource}))

(defn close-db-context!
  "Close database context and its connection pool.

       Args:
         ctx: Database context

       Returns:
         nil"
  [ctx]
  (when (core/db-context? ctx)
    (log/info "Closing database context"
              {:adapter (protocols/dialect (:adapter ctx))})
    (core/close-connection-pool! (:datasource ctx))))

(defn with-db
  "Execute function with temporary database context.

       Creates a database context, executes the function with it, then
       automatically closes the connection pool. Useful for one-time operations
       or when you don't want to manage the lifecycle manually.

       Args:
         db-config: Database configuration map
         f: Function that takes database context and returns result

       Returns:
         Result of function execution

       Example:
         (with-db {:adapter :sqlite :database-path \"./temp.db\"}
           (fn [ctx]
             (core/execute-query! ctx {:select [:*] :from [:users]})))"
  [db-config f]
  (let [ctx (db-context db-config)]
    (try
      (f ctx)
      (finally
        (close-db-context! ctx)))))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn sqlite-config
  "Create SQLite database configuration.

       Args:
         database-path: String path to SQLite database file
         opts: Optional map with :pool settings

       Returns:
         Database configuration map

       Example:
         (sqlite-config \"./app.db\")
         (sqlite-config \"./app.db\" {:pool {:maximum-pool-size 3}})"
  ([database-path]
   (sqlite-config database-path {}))
  ([database-path opts]
   (merge {:adapter       :sqlite
           :database-path database-path}
          opts)))

(defn postgresql-config
  "Create PostgreSQL database configuration.

       Args:
         host: Database hostname
         port: Database port
         database: Database name
         username: Username
         password: Password
         opts: Optional map with :pool settings and other options

       Returns:
         Database configuration map

       Example:
         (postgresql-config \"localhost\" 5432 \"mydb\" \"user\" \"pass\")
         (postgresql-config \"localhost\" 5432 \"mydb\" \"user\" \"pass\"
                           {:pool {:maximum-pool-size 20}})"
  ([host port database username password]
   (postgresql-config host port database username password {}))
  ([host port database username password opts]
   (merge {:adapter  :postgresql
           :host     host
           :port     port
           :name     database
           :username username
           :password password}
          opts)))

(defn mysql-config
  "Create MySQL database configuration.

       Args:
         host: Database hostname
         port: Database port
         database: Database name
         username: Username
         password: Password
         opts: Optional map with :pool settings and other options

       Returns:
         Database configuration map

       Example:
         (mysql-config \"localhost\" 3306 \"mydb\" \"user\" \"pass\")
         (mysql-config \"localhost\" 3306 \"mydb\" \"user\" \"pass\"
                       {:pool {:maximum-pool-size 15}})"
  ([host port database username password]
   (mysql-config host port database username password {}))
  ([host port database username password opts]
   (merge {:adapter  :mysql
           :host     host
           :port     port
           :name     database
           :username username
           :password password}
          opts)))

(defn h2-config
  "Create H2 database configuration.

       Args:
         database-path: String path to H2 database (or :memory for in-memory)
         opts: Optional map with :pool settings and other options

       Returns:
         Database configuration map

       Example:
         (h2-config \"./data/app\")
         (h2-config :memory)
         (h2-config \"./data/app\" {:pool {:maximum-pool-size 5}})"
  ([database-path]
   (h2-config database-path {}))
  ([database-path opts]
   (let [path-str (if (= database-path :memory)
                    "mem:testdb"
                    (str database-path))]
     (merge {:adapter       :h2
             :database-path path-str}
            opts))))

;; =============================================================================
;; Environment-based Configuration
;; =============================================================================

(defn db-config-from-env
  "Create database configuration from environment variables.

       Expected environment variables:
       - DB_ADAPTER: sqlite, postgresql, mysql, or h2
       - DB_HOST: Database host (server DBs only)
       - DB_PORT: Database port (server DBs only)
       - DB_NAME: Database name (server DBs only)
       - DB_USERNAME: Username (server DBs only)
       - DB_PASSWORD: Password (server DBs only)
       - DB_PATH: Database file path (SQLite/H2 only)
       - DB_POOL_SIZE: Maximum pool size (optional, default varies by adapter)

       Returns:
         Database configuration map

       Throws:
         IllegalArgumentException if required environment variables are missing

       Example:
         ;; With environment: DB_ADAPTER=sqlite DB_PATH=./app.db
         (db-config-from-env) ;; => {:adapter :sqlite :database-path \"./app.db\"}"
  []
  (let [adapter-str (System/getenv "DB_ADAPTER")
        adapter     (when adapter-str (keyword adapter-str))]

    (when-not adapter
      (throw (IllegalArgumentException.
              "DB_ADAPTER environment variable is required. Valid values: sqlite, postgresql, mysql, h2")))

    (case adapter
      :sqlite
      (let [db-path (System/getenv "DB_PATH")]
        (when-not db-path
          (throw (IllegalArgumentException. "DB_PATH environment variable is required for SQLite")))
        (sqlite-config db-path
                       (when-let [pool-size (System/getenv "DB_POOL_SIZE")]
                         {:pool {:maximum-pool-size (Integer/parseInt pool-size)}})))

      :h2
      (let [db-path (or (System/getenv "DB_PATH") ":memory")]
        (h2-config db-path
                   (when-let [pool-size (System/getenv "DB_POOL_SIZE")]
                     {:pool {:maximum-pool-size (Integer/parseInt pool-size)}})))

      (:postgresql :mysql)
      (let [host     (System/getenv "DB_HOST")
            port-str (System/getenv "DB_PORT")
            database (System/getenv "DB_NAME")
            username (System/getenv "DB_USERNAME")
            password (System/getenv "DB_PASSWORD")]

        (when-not (and host port-str database username password)
          (throw (IllegalArgumentException.
                  (str "For " (name adapter) ", these environment variables are required: "
                       "DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD"))))

        (let [port (Integer/parseInt port-str)
              opts (when-let [pool-size (System/getenv "DB_POOL_SIZE")]
                     {:pool {:maximum-pool-size (Integer/parseInt pool-size)}})]
          (case adapter
            :postgresql (postgresql-config host port database username password opts)
            :mysql (mysql-config host port database username password opts))))

      (throw (IllegalArgumentException.
              (str "Unsupported adapter: " adapter
                   ". Supported: sqlite, postgresql, mysql, h2"))))))

;; =============================================================================
;; Validation and Testing Helpers
;; =============================================================================

(defn validate-connection
  "Test database connection and validate configuration.

       Args:
         db-config: Database configuration map

       Returns:
         Map with connection status and database info

       Example:
         (validate-connection {:adapter :sqlite :database-path \"./app.db\"})"
  [db-config]
  (try
    (with-db db-config
      (fn [ctx]
        (let [db-info (core/database-info ctx)]
          {:status          :success
           :adapter         (:adapter db-info)
           :connection-pool (:pool-info db-info)
           :message         "Database connection successful"})))
    (catch Exception e
      {:status  :error
       :error   (.getMessage e)
       :message "Database connection failed"})))

(defn list-supported-adapters
  "List all supported database adapters.

       Returns:
         Vector of supported adapter keywords"
  []
  [:sqlite :postgresql :mysql :h2])

;; =============================================================================
;; Resource Management
;; =============================================================================

(defn create-managed-contexts
  "Create multiple database contexts with centralized lifecycle management.

       Args:
         config-map: Map of context-name -> db-config

       Returns:
         Map of context-name -> database context, plus a :close-all! function

       Example:
         (def contexts (create-managed-contexts
                         {:main {:adapter :sqlite :database-path \"./main.db\"}
                          :cache {:adapter :h2 :database-path :memory}}))
         (core/execute-query! (:main contexts) {...})
         ((:close-all! contexts)) ; Close all contexts"
  [config-map]
  (let [contexts (into {}
                       (map (fn [[name config]]
                              [name (db-context config)]))
                       config-map)
        close-fn (fn []
                   (log/info "Closing all managed database contexts")
                   (doseq [[name ctx] contexts]
                     (log/debug "Closing context" {:name name})
                     (close-db-context! ctx)))]
    (assoc contexts :close-all! close-fn)))

;; =============================================================================
;; Development and Testing Utilities
;; =============================================================================

(defn in-memory-context
  "Create in-memory database context for testing.

       Uses H2 in-memory database for fast, isolated testing.

       Returns:
         Database context for in-memory H2 database"
  []
  (db-context (h2-config :memory)))