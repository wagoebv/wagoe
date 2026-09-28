(ns wagoe.platform.shell.database.reset-test
  "BOU-585: `bb db:reset` said all data would be lost and kept users and
   sessions. The first fix dropped every table in the schema, and with it
   whatever else shared the database. A reset drops what the application owns,
   and nothing when something else depends on it."
  (:require [wagoe.platform.shell.database.reset :as sut]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [support.reset-app :refer [tables with-app]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- h2 []
  (db-factory/db-context {:adapter :h2 :database-path (str "mem:reset_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")}))

(defn- sqlite []
  (db-factory/db-context {:adapter :sqlite
                          :database-path (str (io/file "target" (str "bou585-" (System/nanoTime) ".db")))}))

(defn- boot-ddl-tables
  "The tables the libraries' sources create at boot, found in the source:
   literal CREATE TABLE IF NOT EXISTS, the maps given to
   initialize-tables-from-schemas!, and the migration workflow runs at boot."
  []
  (let [libs (io/file "libs")]
    (set
     (for [lib   (.listFiles libs)
           :when (not (#{"platform" "scaffolder" "tools" "wagoe-cli" "wagoe-mcp"} (.getName lib)))
           f     (file-seq (io/file lib "src"))
           :when (str/ends-with? (.getName f) ".clj")
           :let  [src (slurp f)]
           table (concat
                  (map second (re-seq #"\"CREATE TABLE IF NOT EXISTS (\w+)" src))
                  (for [[_ m] (re-seq #"initialize-tables-from-schemas! ctx\s*\{([^}]*)\}" src)
                        [_ t] (re-seq #"\"(\w+)\"" m)]
                    t)
                  (for [[_ sql-file] (re-seq #"\(migration-resource \"([^\"]+)\"" src)
                        sql          (file-seq (io/file lib "resources"))
                        :when        (= sql-file (.getName sql))
                        [_ t]        (re-seq #"CREATE TABLE IF NOT EXISTS (\w+)" (slurp sql))]
                    t))]
       table))))

(deftest ^:unit every-boot-table-is-declared
  ;; A boot table missing from its library's wagoe/boot-tables/<lib>.edn
  ;; survives a reset, as users and sessions did (BOU-585).
  (let [found (boot-ddl-tables)]
    (is (< 10 (count found)) "the scan finds the boot DDL")
    (is (= found (set (sut/boot-tables))))))

(deftest ^:integration reset-drops-the-apps-tables
  (doseq [[label mk] [["H2" h2] ["SQLite" sqlite]]]
    (testing label
      (let [ctx (mk)]
        (try
          (with-app ctx "dev"
            (fn [run-reset]
              (db/execute-ddl! ctx "CREATE TABLE someone_elses (id INT)")
              (is (nil? (run-reset)))
              (is (= #{"app_table" "schema_migrations" "someone_elses"} (tables ctx))
                  "users and sessions go; a table nobody declared stays")))
          (finally (db-factory/close-db-context! ctx)))))))

(deftest ^:integration reset-runs-in-dev-test-and-acc-only
  (doseq [env ["dev" "test" "acc"]]
    (testing env
      (let [ctx (h2)]
        (try
          (with-app ctx env (fn [run-reset] (is (nil? (run-reset)))))
          (is (not (contains? (tables ctx) "auth_users")))
          (finally (db-factory/close-db-context! ctx))))))
  (doseq [env ["prod" "production" "staging" "local" "development" ""]]
    (testing (pr-str env)
      (let [ctx (h2)]
        (try
          (with-app ctx env
            (fn [run-reset]
              (is (= {:type :forbidden :env env} (run-reset)))
              (is (contains? (tables ctx) "auth_users") "nothing is dropped")
              (is (contains? (tables ctx) "app_table"))))
          (finally (db-factory/close-db-context! ctx))))))
  (testing "refused before the database is looked up"
    (with-redefs [db-config/detect-environment (constantly "prod")
                  migrations/rollback-config   (fn [] (throw (ex-info "connected" {})))]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (sut/plan {})))]
        (is (re-find #"bb migrate up" (ex-message e)))))))

(deftest ^:unit another-machine-needs-allow-remote
  (is (sut/loopback? nil))
  (is (sut/loopback? "localhost"))
  (is (sut/loopback? "127.0.0.1"))
  (is (sut/loopback? "::1"))
  (is (not (sut/loopback? "10.1.2.3")))
  (is (not (sut/loopback? "db.example.com"))))

