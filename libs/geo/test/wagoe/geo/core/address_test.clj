(ns wagoe.geo.core.address-test
  "Unit tests for address query normalisation and hashing — pure functions, no I/O."
  (:require [wagoe.geo.core.address :as sut]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; normalize-query
;; =============================================================================

(deftest ^:unit normalize-query-test
  (testing "full query is joined in deterministic order"
    (is (= "damrak 1, 1012 lj, amsterdam, netherlands"
           (sut/normalize-query {:address  "Damrak 1"
                                 :postcode "1012 LJ"
                                 :city     "Amsterdam"
                                 :country  "Netherlands"}))))

  (testing "missing fields are omitted"
    (is (= "1012 lj, amsterdam, netherlands"
           (sut/normalize-query {:postcode "1012 LJ"
                                 :city     "Amsterdam"}))))

  (testing "country defaults to Netherlands"
    (is (= "amsterdam, netherlands"
           (sut/normalize-query {:city "Amsterdam"}))))

  (testing "whitespace is trimmed"
    (is (= "amsterdam, netherlands"
           (sut/normalize-query {:city "  Amsterdam  "}))))

  (testing "result is lowercase"
    (is (= "damrak, netherlands"
           (sut/normalize-query {:address "DAMRAK"}))))

  (testing "the house number follows the postcode, tagged"
    (is (= "1012 js, nr 1 a, netherlands"
           (sut/normalize-query {:postcode "1012 JS" :house-number 1 :house-number-addition " A "}))))

  (testing "an addition without a house number is left out"
    (is (= "1012 js, netherlands"
           (sut/normalize-query {:postcode "1012 JS" :house-number-addition "A"}))))

  (testing "explicit country overrides default"
    (is (= "london, united kingdom"
           (sut/normalize-query {:city "London" :country "United Kingdom"})))))

;; =============================================================================
;; query-hash
;; =============================================================================

(deftest ^:unit query-hash-test
  (testing "returns a 64-character hex string"
    (let [h (sut/query-hash {:city "Amsterdam"})]
      (is (string? h))
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h))))

  (testing "same query produces same hash"
    (is (= (sut/query-hash {:city "Amsterdam"})
           (sut/query-hash {:city "Amsterdam"}))))

  (testing "different queries produce different hashes"
    (is (not= (sut/query-hash {:city "Amsterdam"})
              (sut/query-hash {:city "Rotterdam"}))))

  (testing "the house number and its addition are part of the hash"
    (let [hashes (map sut/query-hash [{:postcode "1012 JS"}
                                      {:postcode "1012 JS" :house-number 1}
                                      {:postcode "1012 JS" :house-number 2}
                                      {:postcode "1012 JS" :house-number 1 :house-number-addition "A"}
                                      {:address "1" :postcode "1012 JS"}])]
      (is (apply distinct? hashes)))
    (is (= (sut/query-hash {:postcode "1012 JS" :house-number 1})
           (sut/query-hash {:postcode "1012 JS" :house-number "1"}))))

  (testing "a query without a house number keeps the hash it always had"
    (is (= "c525d198893f19f71736b6799a9bc41cf969ea1c08e26c63e9b570acb28394f9"
           (sut/query-hash {:postcode "1012 JS" :city "Amsterdam"}))))

  (testing "case-insensitive — uppercase and lowercase produce same hash"
    (is (= (sut/query-hash {:city "AMSTERDAM"})
           (sut/query-hash {:city "amsterdam"}))))

  (testing "whitespace-insensitive — trimmed inputs produce same hash"
    (is (= (sut/query-hash {:city "Amsterdam"})
           (sut/query-hash {:city "  Amsterdam  "})))))

(deftest ^:unit query-hash-per-provider-restriction-test
  (let [q {:postcode "1012JS" :house-number 1}]
    (is (= (sut/query-hash q) (sut/query-hash q nil)))
    (is (not= (sut/query-hash q) (sut/query-hash q #{:pdok})))
    (is (= (sut/query-hash q [:pdok :google]) (sut/query-hash q #{:google :pdok})))))
