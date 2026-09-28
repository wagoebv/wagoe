(ns support.reset-app
  "An application database for `db:reset` tests: one migration and the user
   module's boot tables, with rows (BOU-585)."
  (:require [wagoe.platform.shell.database.reset :as reset]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.database :as db]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [migratus.core :as migratus]))

(defn migration-dir!
  "A directory holding one migration that creates `table`."
  [root id table]
  (let [dir (doto (io/file root table) .mkdirs)]
    (spit (io/file dir (str id "-" table ".up.sql")) (str "CREATE TABLE " table " (id INT)"))
    (spit (io/file dir (str id "-" table ".down.sql")) (str "DROP TABLE " table))
    ;; Relative: migratus refuses an absolute migration directory.
    (str (.getPath dir) "/")))

(defn tables [ctx]
  (set (map str/lower-case (db/list-tables ctx))))

(defn with-app
  "An app on `ctx`: one migration applied and the user module's boot tables,
   with rows. Calls (f run-reset) in profile `env`, where run-reset returns nil
   or the refusal's ex-data."
  [ctx env f]
  (let [root (io/file "target" (str "bou585-" (System/nanoTime)))]
    (try
      (let [app (migration-dir! root 20260101000000 "app_table")]
        (migratus/migrate (migrations/migratus-config (:datasource ctx) [app]))
        ;; What initialize-user-schema! makes at boot, outside any migration.
        (db/execute-ddl! ctx "CREATE TABLE auth_users (id INT PRIMARY KEY)")
        (db/execute-ddl! ctx (str "CREATE TABLE user_sessions (id INT, user_id INT"
                                  " REFERENCES auth_users (id))"))
        (db/execute-ddl! ctx "INSERT INTO auth_users (id) VALUES (1)")
        (db/execute-ddl! ctx "INSERT INTO user_sessions (id, user_id) VALUES (1, 1)")
        (with-redefs [migrations/shadowed-migration-dirs (fn ([] nil) ([_ _] nil))
                      migrations/manifest-urls           (fn [] [])
                      migrations/discover-migration-dirs (fn [] [app])
                      db-config/detect-environment       (constantly env)
                      db-config/get-active-db-config     (fn [] {:datasource (:datasource ctx)})
                      db-config/load-config              (fn [_] {:active {}})]
          (f (fn [& [opts]]
               (try (reset/reset-database! (or opts {})) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete ^java.io.File file))))))
