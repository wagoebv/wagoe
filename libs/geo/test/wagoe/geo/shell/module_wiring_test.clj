(ns wagoe.geo.shell.module-wiring-test
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.module-wiring]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(defn- h2-with-cache-table []
  (let [ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname "geo_wiring_test" :DB_CLOSE_DELAY "-1"})]
    (jdbc/execute! ds ["DROP TABLE IF EXISTS geo_cache"])
    (jdbc/execute! ds ["CREATE TABLE geo_cache (
      address_hash VARCHAR(255) PRIMARY KEY, lat NUMERIC(10, 7) NOT NULL, lng NUMERIC(10, 7) NOT NULL,
      formatted_address TEXT, street TEXT, house_number TEXT, house_number_addition TEXT,
      postcode TEXT, city TEXT, country TEXT, provider TEXT NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"])
    ds))

(defn- round-trips? [cache]
  (ports/cache-store! cache "h1" {:lat 52.37 :lng 4.9 :provider :pdok})
  (= 52.37 (:lat (ports/cache-lookup cache "h1"))))

(deftest ^:integration db-cache-config-test
  (testing ":db-ctx takes the :wagoe/db-context map and caches in its datasource"
    (let [svc (ig/init-key :wagoe/geo-service
                           {:provider :pdok :db-ctx {:datasource (h2-with-cache-table)}})]
      (is (round-trips? (:cache svc)))))

  (testing ":db still takes a bare datasource"
    (let [svc (ig/init-key :wagoe/geo-service {:provider :pdok :db (h2-with-cache-table)})]
      (is (round-trips? (:cache svc)))))

  (testing "a :db-ctx without a datasource fails at boot, not on the first lookup"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :datasource"
                          (ig/init-key :wagoe/geo-service {:provider :pdok :db-ctx {}}))))

  (testing "no database, no cache"
    (is (nil? (:cache (ig/init-key :wagoe/geo-service {:provider :pdok}))))))
