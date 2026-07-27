(ns wagoe.geo.core.math-test
  "Unit tests for Haversine distance and bearing calculations — pure functions, no I/O."
  (:require [wagoe.geo.core.math :as sut]
            [clojure.test :refer [deftest is testing]]))

;; Well-known reference points
(def amsterdam {:lat 52.3676 :lng 4.9041})
(def rotterdam {:lat 51.9225 :lng 4.4792})
(def london    {:lat 51.5074 :lng -0.1278})
(def new-york  {:lat 40.7128 :lng -74.0060})

;; =============================================================================
;; haversine-distance
;; =============================================================================

(deftest ^:unit haversine-distance-test
  (testing "same point has zero distance"
    (is (< (sut/haversine-distance amsterdam amsterdam) 0.001)))

  (testing "Amsterdam → Rotterdam is approximately 57 km"
    (let [d (sut/haversine-distance amsterdam rotterdam)]
      (is (> d 55.0))
      (is (< d 60.0))))

  (testing "Amsterdam → London is approximately 357 km"
    (let [d (sut/haversine-distance amsterdam london)]
      (is (> d 350.0))
      (is (< d 365.0))))

  (testing "distance is symmetric"
    (let [d1 (sut/haversine-distance amsterdam london)
          d2 (sut/haversine-distance london amsterdam)]
      (is (< (Math/abs (- d1 d2)) 0.001))))

  (testing "transatlantic distance Amsterdam → New York is approximately 5865 km"
    (let [d (sut/haversine-distance amsterdam new-york)]
      (is (> d 5800.0))
      (is (< d 5950.0)))))

;; =============================================================================
;; bearing
;; =============================================================================

(deftest ^:unit bearing-test
  (testing "bearing is in range 0–360"
    (let [b (sut/bearing amsterdam london)]
      (is (>= b 0.0))
      (is (< b 360.0))))

  (testing "bearing Amsterdam → London is roughly westward (270° ± 30°)"
    ;; Amsterdam is at 52.4°N 4.9°E, London at 51.5°N -0.1°E — broadly west
    (let [b (sut/bearing amsterdam london)]
      (is (> b 240.0))
      (is (< b 310.0))))

  (testing "due north bearing is 0 or 360"
    (let [south {:lat 0.0 :lng 0.0}
          north {:lat 10.0 :lng 0.0}
          b     (sut/bearing south north)]
      ;; bearing due north ≈ 0 (or very close)
      (is (or (< b 1.0) (> b 359.0)))))

  (testing "due east bearing is approximately 90"
    (let [west {:lat 0.0 :lng 0.0}
          east {:lat 0.0 :lng 10.0}
          b    (sut/bearing west east)]
      (is (> b 89.0))
      (is (< b 91.0))))

  (testing "due south bearing is approximately 180"
    (let [north {:lat 10.0 :lng 0.0}
          south {:lat 0.0  :lng 0.0}
          b     (sut/bearing north south)]
      (is (> b 179.0))
      (is (< b 181.0)))))
