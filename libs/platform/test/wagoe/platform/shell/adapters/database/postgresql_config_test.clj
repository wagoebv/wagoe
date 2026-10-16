(ns wagoe.platform.shell.adapters.database.postgresql-config-test
  "PostgreSQL session settings survive the config conversion and reach every
   pooled connection (BOU-658)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.config :as config]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

(deftest ^:unit boot-and-migrate-convert-every-adapter-alike
  (doseq [[k settings] {:wagoe/sqlite     {:db "app.db"}
                        :wagoe/h2         {:memory true :pool {:maximum-pool-size 2}}
                        :wagoe/postgresql {:host "db" :port 5432 :dbname "app" :user "u" :password "p"
                                           :application-name "billing" :statement-timeout-ms 5000}
                        :wagoe/mysql      {:host "db" :port 3306 :dbname "app" :user "u" :password "p"}}]
    (testing (name k)
      (is (= (config/db-spec {:active {k settings}})
             (db-config/config->db-config k settings))))))

(deftest ^:integration settings-reach-a-pooled-connection
  (let [pg  (epg/start!)
        cfg (db-config/config->db-config
             :wagoe/postgresql
             {:host "localhost" :port (epg/port pg) :dbname "postgres"
              :user "postgres" :password "postgres"
              :application-name "bou-658" :statement-timeout-ms 4321
              :pool {:minimum-idle 1 :maximum-pool-size 2}})
        ctx (factory/db-context cfg)]
    (try
      (with-open [conn (jdbc/get-connection (:datasource ctx))]
        (is (= "bou-658" (:application_name (jdbc/execute-one! conn ["SHOW application_name"]))))
        (is (= "4321ms" (:statement_timeout (jdbc/execute-one! conn ["SHOW statement_timeout"])))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
