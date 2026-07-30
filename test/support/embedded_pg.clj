(ns support.embedded-pg
  "Shared embedded-PostgreSQL lifecycle for integration tests.

   Uses io.zonky.test/embedded-postgres, which starts a real PostgreSQL process
   from a bundled binary — no Docker, no local PG install, no CI service
   container. Because the DB always starts, integration tests that use this
   helper ALWAYS run (and fail loud if PG cannot start) instead of silently
   skipping when an external PG is absent.

   Usage:
     (use-fixtures :once (with-embedded-pg #(reset! *ctx* %)))
   or manage the lifecycle directly with start!/db-context/stop!."
  (:require [wagoe.platform.shell.adapters.database.factory :as db-factory])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]))

(defn start!
  "Start an embedded PostgreSQL instance. Returns the EmbeddedPostgres object."
  ^EmbeddedPostgres []
  (.start (EmbeddedPostgres/builder)))

(defn stop!
  "Stop an embedded PostgreSQL instance."
  [^EmbeddedPostgres pg]
  (when pg (.close pg)))

(defn port
  "Port of a running embedded PostgreSQL instance."
  [^EmbeddedPostgres pg]
  (.getPort pg))

(defn datasource
  "Raw javax.sql.DataSource for the default `postgres` database."
  [^EmbeddedPostgres pg]
  (.getPostgresDatabase pg))

(defn db-config
  "Database config map (as produced by db-config/get-active-db-config) for the
   embedded instance — :host/:port/:name/:username/:password. Suitable for the
   tenant migration runner and other code that builds its own connections."
  [^EmbeddedPostgres pg]
  {:adapter  :postgresql
   :host     "localhost"
   :port     (.getPort pg)
   :name     "postgres"
   :username "postgres"
   :password "postgres"})

(defn db-context
  "Wagoe db-context ({:adapter :datasource ...}) backed by the embedded PG."
  ([pg] (db-context pg {}))
  ([^EmbeddedPostgres pg opts]
   (db-factory/db-context (merge (db-config pg)
                                 {:pool {:minimum-idle 1 :maximum-pool-size 3}}
                                 opts))))

(defn with-embedded-pg
  "Kaocha :once fixture: start embedded PG, call (init-fn db-ctx) so the test ns
   can capture the context, run tests, then tear down. Fails loud if PG cannot
   start — integration coverage is never silently skipped."
  [init-fn]
  (fn [f]
    (let [pg  (start!)
          ctx (db-context pg)]
      (try
        (init-fn ctx)
        (f)
        (finally
          (db-factory/close-db-context! ctx)
          (stop! pg))))))
