(ns wagoe.platform.shell.adapters.database.common.connection
  "Common connection pool management utilities."
  (:require [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [clojure.tools.logging :as log])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

;; =============================================================================
;; Connection Pool Management
;; =============================================================================

(defn create-connection-pool
  "Create HikariCP connection pool using database adapter configuration.

   Args:
     adapter: Database adapter implementing DBAdapter protocol
     db-config: Database configuration map

   Returns:
     HikariDataSource configured for the database type

   Example:
     (create-connection-pool sqlite-adapter {:database-path \"./app.db\"})"
  [adapter db-config]
  {:pre [(satisfies? protocols/DBAdapter adapter)
         (map? db-config)]}
  (protocols/validate-db-config db-config)

  (let [pool-config (or (:pool db-config) {})
        defaults (protocols/pool-defaults adapter)
        hikari-config (doto (HikariConfig.)
                        (.setDriverClassName (protocols/jdbc-driver adapter))
                        (.setJdbcUrl (protocols/jdbc-url adapter db-config))
                        (.setMinimumIdle (get pool-config :minimum-idle (:minimum-idle defaults 1)))
                        (.setMaximumPoolSize (get pool-config :maximum-pool-size (:maximum-pool-size defaults 10)))
                        (.setConnectionTimeout (get pool-config :connection-timeout-ms (:connection-timeout-ms defaults 30000)))
                        (.setIdleTimeout (get pool-config :idle-timeout-ms (:idle-timeout-ms defaults 600000)))
                        (.setMaxLifetime (get pool-config :max-lifetime-ms (:max-lifetime-ms defaults 1800000)))
                        (.setPoolName (str (or (when-let [d (protocols/dialect adapter)] (name d)) "default") "-Pool"))
                        (.setAutoCommit true))]

    (when-let [username (:username db-config)]
      (.setUsername hikari-config username))

    (when-let [password (:password db-config)]
      (.setPassword hikari-config password))

    (log/info "Creating database connection pool"
              {:adapter (protocols/dialect adapter)
               :pool-size (get pool-config :maximum-pool-size (:maximum-pool-size defaults 10))
               :pool-name (.getPoolName hikari-config)})

    (let [datasource (HikariDataSource. hikari-config)]
      (try
        ;; Initialize database-specific connection settings
        (protocols/init-connection! adapter datasource db-config)
        (log/info "Database connection pool created successfully"
                  {:adapter (protocols/dialect adapter)
                   :pool-name (.getPoolName hikari-config)})
        datasource
        (catch Exception e
          (log/error "Failed to initialize database connection, closing pool"
                     {:adapter (protocols/dialect adapter)
                      :error (.getMessage e)})
          (.close datasource)
          (throw (ex-info "Database initialization failed"
                          ;; :db/error is what the BND classifier reads for
                          ;; BND-303 ("Database Connection Failed"), which is
                          ;; exactly this case. A failed query or DDL is a
                          ;; different failure and carries :database-error, so
                          ;; the dashboard does not report every SQL error as a
                          ;; connection problem (BOU-323).
                          {:type :db/error
                           :adapter (protocols/dialect adapter)
                           :original-error (.getMessage e)}
                          e)))))))

(defn close-connection-pool!
  "Close HikariCP connection pool.

   Args:
     datasource: HikariDataSource to close

   Returns:
     nil"
  [datasource]
  (when (instance? HikariDataSource datasource)
    (let [pool-name (try (.getPoolName ^HikariDataSource datasource)
                         (catch Exception _ "unknown"))]
      (log/info "Closing database connection pool" {:pool-name pool-name})
      (.close ^HikariDataSource datasource)
      (log/info "Database connection pool closed" {:pool-name pool-name}))))
