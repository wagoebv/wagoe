(ns wagoe.geo.shell.cache-test
  "Contract tests for DbGeoCache against H2 in-memory database."
  (:require [wagoe.geo.shell.cache :as sut]
            [wagoe.geo.ports :as ports]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.geo.test-db :as test-db]
            [next.jdbc.sql :as sql])
  (:import [java.sql Timestamp]
           [java.time Instant]))

;; =============================================================================
;; H2 test DB setup
;; =============================================================================

(def ^:dynamic *ds* nil)
(def ^:dynamic *cache* nil)

(defn with-test-db [f]
  (let [ds (test-db/fresh-cache-db! "geo_test")]
    (binding [*ds*    ds
              *cache* (sut/create-db-geo-cache ds 3600)]
      (f))
    (test-db/drop-cache-table! ds)))

(use-fixtures :each with-test-db)

;; =============================================================================
;; Helper data
;; =============================================================================

(def test-hash "abc123def456abc123def456abc123def456abc123def456abc123def456abc1")

(def test-result
  {:lat               52.3676
   :lng               4.9041
   :formatted-address "Damrak 1, Amsterdam, Netherlands"
   :postcode          "1012 JS"
   :city              "Amsterdam"
   :country           "Netherlands"
   :provider          :openstreetmap
   :cached?           false})

;; =============================================================================
;; cache-lookup — miss
;; =============================================================================

(deftest ^:contract cache-lookup-miss-test
  (testing "lookup on empty cache returns nil"
    (is (nil? (ports/cache-lookup *cache* test-hash)))))

;; =============================================================================
;; cache-store! + cache-lookup — hit
;; =============================================================================

(deftest ^:contract cache-store-and-lookup-test
  (testing "stored result is retrievable"
    (ports/cache-store! *cache* test-hash test-result)
    (let [hit (ports/cache-lookup *cache* test-hash)]
      (is (some? hit))
      (is (= 52.3676 (:lat hit)))
      (is (= 4.9041 (:lng hit)))
      (is (= "Amsterdam" (:city hit)))
      (is (= :openstreetmap (:provider hit)))
      (is (true? (:cached? hit)))))

  (testing "street and house number come back"
    (ports/cache-store! *cache* "pdok-hash"
                        (assoc test-result :street "Dam" :house-number "1"
                               :house-number-addition "A-2" :provider :pdok))
    (is (= {:street "Dam" :house-number "1" :house-number-addition "A-2" :provider :pdok}
           (select-keys (ports/cache-lookup *cache* "pdok-hash")
                        [:street :house-number :house-number-addition :provider]))))

  (testing "a result without them has no such keys"
    (is (not-any? (ports/cache-lookup *cache* test-hash)
                  [:street :house-number :house-number-addition])))

  (testing "different hash returns nil"
    (is (nil? (ports/cache-lookup *cache* "different-hash")))))

;; =============================================================================
;; TTL expiry
;; =============================================================================

(deftest ^:contract cache-ttl-expiry-test
  (testing "expired entries are not returned"
    ;; Insert with a past created_at timestamp (2 hours ago)
    (let [past (Timestamp/from (Instant/ofEpochSecond
                                (- (.getEpochSecond (Instant/now)) 7200)))]
      (sql/insert! *ds* :geo_cache
                   {:address_hash      "expired-hash"
                    :lat               52.0
                    :lng               4.0
                    :formatted_address "Old Address"
                    :postcode          nil
                    :city              nil
                    :country           nil
                    :provider          "openstreetmap"
                    :created_at        past}))
    ;; 1-second TTL cache — the entry is 7200s old
    (let [short-cache (sut/create-db-geo-cache *ds* 1)]
      (is (nil? (ports/cache-lookup short-cache "expired-hash"))))))

;; =============================================================================
;; Duplicate key — idempotent store
;; =============================================================================

(deftest ^:contract cache-store-idempotent-test
  (testing "storing the same hash twice does not throw"
    (ports/cache-store! *cache* test-hash test-result)
    ;; Second store should be silently ignored (duplicate key → non-fatal)
    (is (nil? (ports/cache-store! *cache* test-hash test-result)))))
