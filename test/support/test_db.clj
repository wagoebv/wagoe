(ns support.test-db
  "A fresh database for a test, on the engine WAGOE_TEST_DB names: h2 (the
   default), pg (embedded) or mysql (`bb test:services up`). One suite run
   against each engine is how a library proves it supports it (BOU-652)."
  (:require [next.jdbc :as jdbc]
            [support.mysql :as mysql]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

(def engine
  (keyword (or (System/getenv "WAGOE_TEST_DB") "h2")))

(defonce ^:private pg
  ;; One embedded PostgreSQL per JVM: starting one per test took seconds each.
  ;; Resolved here: it is on the classpath only with the :test/pg alias.
  (delay (let [instance ((requiring-resolve 'support.embedded-pg/start!))]
           (.addShutdownHook (Runtime/getRuntime)
                             (Thread. #((requiring-resolve 'support.embedded-pg/stop!) instance)))
           instance)))

(defn- with-pg-database [prefix f]
  (let [instance @pg
        server   ((requiring-resolve 'support.embedded-pg/datasource) instance)
        dbname   (str prefix "_" (System/nanoTime))]
    (jdbc/execute! server [(str "CREATE DATABASE " dbname)])
    (try (let [ctx ((requiring-resolve 'support.embedded-pg/db-context) instance {:name dbname})]
           (try (f ctx) (finally (factory/close-db-context! ctx))))
         (finally (jdbc/execute! server [(str "DROP DATABASE " dbname)])))))

(defn with-db
  "Call `f` with a db-context on a fresh database named after `prefix`, and
   discard the database afterwards."
  [prefix f]
  (case engine
    :h2    (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:" prefix "_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             ;; DB_CLOSE_DELAY=-1 keeps the database past the pool; empty it.
             (try (f ctx)
                  (finally
                    (jdbc/execute! (:datasource ctx) ["DROP ALL OBJECTS"])
                    (factory/close-db-context! ctx))))
    :pg    (with-pg-database prefix f)
    :mysql (mysql/with-database prefix f)
    (throw (ex-info (str "WAGOE_TEST_DB must be h2, pg or mysql, not " (name engine))
                    {:type :validation-error :engine engine}))))
