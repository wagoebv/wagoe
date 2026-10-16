(ns support.mysql
  "A throwaway MySQL database for a test, on the server `bb test:services up`
   starts. WAGOE_TEST_MYSQL_PORT and WAGOE_TEST_MYSQL_PASSWORD move it."
  (:require [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

(def port (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(def password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe"))

(defn server
  "A datasource on the server itself, for CREATE and DROP DATABASE."
  []
  (jdbc/get-datasource {:dbtype "mysql" :host "127.0.0.1" :port port
                        :user "root" :password password}))

(defn with-database
  "Call `f` with a db-context on a fresh database named after `prefix`, and
   drop the database afterwards."
  [prefix f]
  (let [dbname (str prefix "_" (System/nanoTime))]
    (jdbc/execute! (server) [(str "CREATE DATABASE " dbname)])
    (let [ctx (factory/db-context (factory/mysql-config "127.0.0.1" port dbname "root" password))]
      (try (f ctx)
           (finally
             (factory/close-db-context! ctx)
             (jdbc/execute! (server) [(str "DROP DATABASE " dbname)]))))))
