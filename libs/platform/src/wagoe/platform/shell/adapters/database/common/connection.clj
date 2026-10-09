(ns wagoe.platform.shell.adapters.database.common.connection
  "Common connection pool management utilities."
  (:require [wagoe.platform.ports.database :as protocols]
            [clojure.tools.logging :as log])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [com.zaxxer.hikari.util DriverDataSource]
           [java.sql Connection]
           [java.util Properties]
           [javax.sql DataSource]))

;; =============================================================================
;; Connection Pool Management
;; =============================================================================

(defn- initialising-data-source
  "Hikari's own driver data source, with `init!` run on each connection it
   opens. Session settings and SQLite PRAGMAs are per connection; run once on
   the pool they reached one connection and the rest ran without them (BOU-606)."
  [adapter db-config init!]
  (let [ds    (DriverDataSource. (protocols/jdbc-url adapter db-config) (protocols/jdbc-driver adapter)
                                 (Properties.) (:username db-config) (:password db-config))
        ready (fn [^Connection c]
                (try (init! c) c
                     (catch Throwable t (.close c) (throw t))))]
    (reify DataSource
      (getConnection [_] (ready (.getConnection ds)))
      (getConnection [_ user password] (ready (.getConnection ds user password)))
      (getLogWriter [_] (.getLogWriter ds))
      (setLogWriter [_ w] (.setLogWriter ds w))
      (setLoginTimeout [_ seconds] (.setLoginTimeout ds seconds))
      (getLoginTimeout [_] (.getLoginTimeout ds))
      (getParentLogger [_] (.getParentLogger ds))
      (unwrap [_ iface] (.unwrap ds iface))
      (isWrapperFor [_ iface] (.isWrapperFor ds iface)))))

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
                        (.setDataSource (initialising-data-source
                                         adapter db-config
                                         #(protocols/init-connection! adapter % db-config)))
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
        ;; Every connection is initialised as it opens; this proves one can be.
        (with-open [_ (.getConnection datasource)])
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
                          ;; :db/error is what the WGE classifier reads for
                          ;; WGE-303 ("Database Connection Failed"), which is
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
