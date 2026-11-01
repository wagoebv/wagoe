(ns wagoe.platform.shell.adapters.database.mysql-config-test
  "MySQL driver settings survive both config conversions (BOU-647)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.config :as config]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.mysql.connection :as mysql]))

(def ^:private settings
  {:host "db" :port 3306 :dbname "app" :user "u" :password "p"
   :connection-params {:useSSL "false" :allowPublicKeyRetrieval "true"}
   :sql-mode "ANSI_QUOTES"})

(deftest ^:unit driver-settings-reach-the-connection
  (doseq [[path spec] [["boot (db-spec)"      (config/db-spec {:active {:wagoe/mysql settings}})]
                       ["migrate (db-config)" (db-config/config->db-config :wagoe/mysql settings)]]]
    (testing path
      (let [url (mysql/build-jdbc-url spec)]
        (is (str/includes? url "allowPublicKeyRetrieval=true"))
        (is (str/includes? url "useSSL=false") "a param overrides the default"))
      (is (some #{["SET SESSION sql_mode = ?" "ANSI_QUOTES"]}
                (mysql/session-statements spec))))))

(deftest ^:unit a-nil-sql-mode-turns-strict-mode-off
  (let [spec (config/db-spec {:active {:wagoe/mysql (assoc settings :sql-mode nil)}})]
    (is (not-any? #(str/includes? (first %) "sql_mode") (mysql/session-statements spec)))))
