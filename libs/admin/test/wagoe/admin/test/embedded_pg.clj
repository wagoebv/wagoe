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
  (:require [clojure.string :as str]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

;; Embedded Postgres is not in :test but in :test/pg (plus :test/pg-mac on
;; Apple Silicon), BOU-260. Loaded by name rather than imported, so a run
;; without those aliases says which command to use instead of failing to
;; compile with a bare ClassNotFoundException.

(def ^:private class-name "io.zonky.test.db.postgres.embedded.EmbeddedPostgres")

(defn- mac? []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac"))

(defn- command-hint []
  (str "Run with the embedded Postgres aliases: clojure -M:test:test/pg"
       (when (mac?) ":test/pg-mac")
       " <suite>"))

(defn- embedded-postgres-class
  ^Class []
  (try
    (Class/forName class-name)
    (catch ClassNotFoundException e
      (throw (ex-info (str "Embedded PostgreSQL is not on the classpath. " (command-hint))
                      {:missing class-name} e)))))

(defn start!
  "Start an embedded PostgreSQL instance. Returns the EmbeddedPostgres object."
  []
  (let [builder (clojure.lang.Reflector/invokeStaticMethod
                 (embedded-postgres-class) "builder" (object-array 0))]
    (try
      (clojure.lang.Reflector/invokeInstanceMethod builder "start" (object-array 0))
      (catch Exception e
        ;; The library is there but no binary for this platform (on a Mac:
        ;; :test/pg without :test/pg-mac). Anything else is not ours to explain.
        (if (re-find #"(?i)binar" (str (ex-message e)))
          (throw (ex-info (str "No embedded PostgreSQL binary for "
                               (System/getProperty "os.name") "/" (System/getProperty "os.arch")
                               ". " (command-hint))
                          {:os (System/getProperty "os.name") :arch (System/getProperty "os.arch")}
                          e))
          (throw e))))))

(defn stop!
  "Stop an embedded PostgreSQL instance."
  [^java.io.Closeable pg]
  (when pg
    (.close pg)))

(defn pg-port
  "Get the port of a running embedded PostgreSQL instance."
  [pg]
  (clojure.lang.Reflector/invokeInstanceMethod pg "getPort" (object-array 0)))

(defn db-context
  "Create a wagoe-compatible db-context from an embedded PG instance.

   Returns {:adapter adapter :datasource datasource} suitable for use with
   wagoe.platform.database functions."
  ([pg] (db-context pg {}))
  ([pg opts]
   (let [port (pg-port pg)
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
