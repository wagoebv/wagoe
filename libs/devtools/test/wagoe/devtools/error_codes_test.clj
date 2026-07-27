(ns wagoe.devtools.error-codes-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.error-codes :as codes]))

(deftest ^:unit lookup-test
  (testing "finds known error codes"
    (let [result (codes/lookup "BND-101")]
      (is (some? result))
      (is (= "BND-101" (:code result)))
      (is (= :config (:category result)))
      (is (string? (:title result)))
      (is (string? (:description result)))))

  (testing "returns nil for unknown codes"
    (is (nil? (codes/lookup "BND-999")))))

(deftest ^:unit by-category-test
  (testing "returns all config errors"
    (let [results (codes/by-category :config)]
      (is (pos? (count results)))
      (is (every? #(= :config (:category %)) results))))

  (testing "returns all validation errors"
    (let [results (codes/by-category :validation)]
      (is (pos? (count results)))
      (is (every? #(= :validation (:category %)) results))))

  (testing "returns all tooling errors"
    (let [results (codes/by-category :tooling)]
      (is (pos? (count results)))
      (is (every? #(= :tooling (:category %)) results))))

  (testing "returns all MCP guardrail errors"
    (let [results (codes/by-category :mcp)]
      (is (pos? (count results)))
      (is (every? #(= :mcp (:category %)) results))
      (is (some #(= "BND-803" (:code %)) results))))

  (testing "returns empty for unknown category"
    (is (empty? (codes/by-category :unknown)))))

(deftest ^:unit all-codes-test
  (testing "returns all codes sorted"
    (let [results (codes/all-codes)]
      (is (pos? (count results)))
      (is (= (map :code results) (sort (map :code results)))))))

(deftest ^:unit every-code-has-required-fields
  (testing "all codes have required fields"
    (doseq [error (codes/all-codes)]
      (is (string? (:code error)) (str "Missing :code in " error))
      (is (keyword? (:category error)) (str "Missing :category in " (:code error)))
      (is (string? (:title error)) (str "Missing :title in " (:code error)))
      (is (string? (:description error)) (str "Missing :description in " (:code error)))
      (is (string? (:fix error)) (str "Missing :fix in " (:code error))))))
