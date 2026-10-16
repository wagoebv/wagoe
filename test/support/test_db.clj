(ns support.test-db
  "A fresh database for a test, on the engine WAGOE_TEST_DB names: h2 (the
   default), pg (embedded) or mysql (`bb test:services up`). One suite run
   against each engine is how a library proves it supports it (BOU-652)."
  (:require [support.mysql :as mysql]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

(def engine
  (keyword (or (System/getenv "WAGOE_TEST_DB") "h2")))

(defn with-db
  "Call `f` with a db-context on a fresh database named after `prefix`, and
   discard the database afterwards."
  [prefix f]
  (case engine
    :h2    (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:" prefix "_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             (try (f ctx) (finally (factory/close-db-context! ctx))))
    ;; Resolved here: embedded PostgreSQL is on the classpath only with the
    ;; :test/pg alias.
    :pg    (let [start!     (requiring-resolve 'support.embedded-pg/start!)
                 stop!      (requiring-resolve 'support.embedded-pg/stop!)
                 db-context (requiring-resolve 'support.embedded-pg/db-context)
                 pg         (start!)]
             (try (let [ctx (db-context pg)]
                    (try (f ctx) (finally (factory/close-db-context! ctx))))
                  (finally (stop! pg))))
    :mysql (mysql/with-database prefix f)
    (throw (ex-info (str "WAGOE_TEST_DB must be h2, pg or mysql, not " (name engine))
                    {:type :validation-error :engine engine}))))
