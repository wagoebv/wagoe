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

  (testing "case-insensitive — uppercase and lowercase produce same hash"
    (is (= (sut/query-hash {:city "AMSTERDAM"})
           (sut/query-hash {:city "amsterdam"}))))

  (testing "whitespace-insensitive — trimmed inputs produce same hash"
    (is (= (sut/query-hash {:city "Amsterdam"})
           (sut/query-hash {:city "  Amsterdam  "})))))
