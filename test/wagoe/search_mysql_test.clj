(ns wagoe.search-mysql-test
  "Search answers on MySQL, whose LIKE fallback aliased its score `rank`, a
   reserved word (BOU-651). Needs `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [support.mysql :as mysql]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence])
  (:import [java.time Instant]))

(deftest ^:integration search-runs-on-mysql
  (mysql/with-database "search"
    (fn [ctx]
      (is (nil? (migratus/migrate (migrations/migratus-config (:datasource ctx) ["wagoe/search/migrations/"]))))
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
          (is (= 1 (ports/count-documents store :products))))))))

(deftest ^:integration a-hand-built-table-with-filters-still-migrates
  ;; Before search shipped its migrations the docs had people create the
  ;; table, filters included; ADD COLUMN filters then failed (BOU-651).
  (mysql/with-database "search_hand"
    (fn [{:keys [datasource]}]
      (jdbc/execute! datasource [(str "CREATE TABLE search_documents (id VARCHAR(255) PRIMARY KEY,"
                                      " index_id VARCHAR(255), entity_type VARCHAR(255),"
                                      " entity_id VARCHAR(255), content_all TEXT, metadata TEXT,"
                                      " updated_at VARCHAR(64), filters TEXT,"
                                      " UNIQUE (index_id, entity_id))")])
      (is (nil? (migratus/migrate (migrations/migratus-config datasource ["wagoe/search/migrations/"])))))))
