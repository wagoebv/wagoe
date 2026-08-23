(ns wagoe.admin.test.embedded-pg
  "Embedded PostgreSQL lifecycle for integration tests.

   Provides start/stop functions and a db-context factory compatible with
   wagoe.platform database execution layer. Uses io.zonky.test/embedded-postgres
   which downloads a real PostgreSQL binary — no Docker, no local PG install needed.

   Usage:
     (def pg (start!))                          ;; start embedded PG
     (def ctx (db-context pg))                  ;; get wagoe db-context
     ;; ... run tests against ctx ...
     (stop! pg)                                 ;; shut down

   Or use the `with-embedded-pg` fixture:
     (use-fixtures :once (with-embedded-pg (fn [ctx] (reset! my-ctx ctx))))"
  (:require [wagoe.platform.shell.adapters.database.factory :as db-factory])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]))

(defn start!
  "Start an embedded PostgreSQL instance. Returns the EmbeddedPostgres object."
  []
  (.start (EmbeddedPostgres/builder)))

(defn stop!
  "Stop an embedded PostgreSQL instance."
  [^EmbeddedPostgres pg]
  (when pg
    (.close pg)))

(defn pg-port
  "Get the port of a running embedded PostgreSQL instance."
  [^EmbeddedPostgres pg]
  (.getPort pg))

(defn db-context
  "Create a wagoe-compatible db-context from an embedded PG instance.

   Returns {:adapter adapter :datasource datasource} suitable for use with
   wagoe.platform.database functions."
  ([pg] (db-context pg {}))
  ([pg opts]
   (let [port (.getPort ^EmbeddedPostgres pg)
         config (merge {:adapter  :postgresql
                        :host     "localhost"
                        :port     port
                        :name     "postgres"
                        :username "postgres"
                        :password "postgres"
                        :pool     {:minimum-idle 1
                                   :maximum-pool-size 3}}
                       opts)]
     (db-factory/db-context config))))

(defn with-embedded-pg
  "Kaocha :once fixture that starts embedded PG, calls (init-fn db-ctx) to let
   the test namespace capture the context, runs all tests, then shuts down.

   Example:
     (use-fixtures :once
       (with-embedded-pg
         (fn [ctx]
           (alter-var-root #'*db-ctx* (constantly ctx)))))"
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
