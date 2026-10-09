(ns wagoe.library-migrations-mysql-test
  "Every migration a library ships, up, down and up again, on MySQL. Every
   generated project runs them, and geo and workflow refused MySQL (BOU-544).

   Needs a server: `bb test:services up` starts one. Without it this fails
   rather than passing as if MySQL had been checked."
  (:require [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.database.migrations :as migrations]))

(defn- mysql-spec [dbname]
  {:dbtype "mysql" :host "127.0.0.1"
   :port (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306"))
   :dbname dbname :user "root"
   :password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")})

(defn- mysql-up? []
  (try (with-open [c (jdbc/get-connection (jdbc/get-datasource (mysql-spec "")))]
         (some? (.getMetaData c)))
       (catch Exception _ false)))

(defn- applied [ds]
  (mapv :id (jdbc/execute! ds ["SELECT id FROM schema_migrations ORDER BY id"]
                           {:builder-fn rs/as-unqualified-lower-maps})))

(deftest ^:integration library-migrations-run-on-mysql
  (is (mysql-up?) "MySQL is not reachable; start it with `bb test:services up`")
  (when (mysql-up?)
    (let [server (jdbc/get-datasource (mysql-spec ""))
          db     (str "libmig_" (System/nanoTime))
          dirs   (vec (remove #{migrations/project-migration-dir} (migrations/discover-migration-dirs)))]
      (jdbc/execute! server [(str "CREATE DATABASE " db)])
      (try
        (let [ds     (jdbc/get-datasource (mysql-spec db))
              config (migrations/migratus-config ds dirs)]
          (is (seq dirs))
          (testing "up"
            (is (nil? (migratus/migrate config)))
            (is (seq (applied ds)))
            (is (empty? (migratus/pending-list config)) "a library migration did not apply"))
          (testing "down"
            (migratus/rollback-until-just-after config 0)
            (is (empty? (applied ds))))
          (testing "up again"
            (is (nil? (migratus/migrate config)))
            (is (empty? (migratus/pending-list config)))))
        (finally
          (jdbc/execute! server [(str "DROP DATABASE " db)]))))))
