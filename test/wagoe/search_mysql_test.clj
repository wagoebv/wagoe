(ns wagoe.search-mysql-test
  "Search on MySQL: its migrations apply, and the LIKE fallback MySQL takes
   answers, though its score column used to be aliased `rank`, which MySQL 8
   reserves (BOU-651). Requires MySQL: `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence])
  (:import [java.time Instant]))

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(def ^:private mysql-password
  (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe"))

(defn- server []
  (jdbc/get-datasource {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
                        :user "root" :password mysql-password}))

(deftest ^:integration search-runs-on-mysql
  (let [dbname (str "search_" (System/nanoTime))]
    (jdbc/execute! (server) [(str "CREATE DATABASE " dbname)])
    (let [ctx (factory/db-context
               (factory/mysql-config "127.0.0.1" mysql-port dbname "root" mysql-password))]
      (try
        (migratus/migrate (migrations/migratus-config (:datasource ctx) ["wagoe/search/migrations/"]))
        (let [store (persistence/create-search-store (:datasource ctx) (:adapter ctx))
              id    (random-uuid)]
          (ports/upsert-document! store {:id (str "products:" id) :index-id :products
                                         :entity-type :product :entity-id id
                                         :weight-a "Blue widget" :content-all "blue widget"
                                         :updated-at (Instant/now)})
          (testing "a search finds it, with its score"
            (let [[hit :as hits] (ports/search-documents store :products :product "widget" {})]
              (is (= 1 (count hits)))
              (is (= id (:entity-id hit)))
              (is (number? (:rank hit)))))
          (testing "and so do suggestions and counts"
            (is (= 1 (count (ports/suggest-documents store :products :product "wid" {}))))
            (is (= 1 (ports/count-documents store :products)))))
        (finally
          (factory/close-db-context! ctx)
          (jdbc/execute! (server) [(str "DROP DATABASE " dbname)]))))))
