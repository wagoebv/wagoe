(ns wagoe.product.core.product-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.product.core.product :as core])
  (:import [java.time Instant]
           [java.util UUID]))

(deftest ^:unit prepare-new-product-test
  (testing "prepares product for creation"
    (let [data {:name "Test"}
          product-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
          current-time (Instant/parse "2026-01-01T00:00:00Z")
          result (core/prepare-new-product data product-id current-time)]
      (is (= product-id (:id result)))
      (is (= current-time (:created-at result)))
      (is (= current-time (:updated-at result))))))
