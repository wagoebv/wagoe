(ns wagoe.geo.shell.module-wiring-test
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.module-wiring]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.geo.test-db :as test-db]))

(defn- round-trips? [cache]
  (ports/cache-store! cache "h1" {:lat 52.37 :lng 4.9 :provider :pdok})
  (= 52.37 (:lat (ports/cache-lookup cache "h1"))))

(deftest ^:integration db-cache-config-test
  (testing ":db-ctx takes the :wagoe/db-context map and caches in its datasource"
    (let [ds  (test-db/fresh-cache-db! "geo_wiring_test")
          svc (ig/init-key :wagoe/geo-service {:provider :pdok :db-ctx {:datasource ds}})]
      (try
        (is (round-trips? (:cache svc)))
        (finally (test-db/drop-cache-table! ds)))))

  (testing "a :db-ctx without a datasource fails at boot, not on the first lookup"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :datasource"
                          (ig/init-key :wagoe/geo-service {:provider :pdok :db-ctx {}}))))

  (testing "the deprecated :db still caches, given a datasource or a db-context"
    (let [ds (test-db/fresh-cache-db! "geo_wiring_test")]
      (try
        (doseq [db [ds {:datasource ds}]]
          (is (round-trips? (:cache (ig/init-key :wagoe/geo-service {:provider :pdok :db db})))))
        (finally (test-db/drop-cache-table! ds)))))

  (testing "no database, no cache"
    (is (nil? (:cache (ig/init-key :wagoe/geo-service {:provider :pdok}))))))
