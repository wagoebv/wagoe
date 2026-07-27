(ns wagoe.core.validation.codes-test
  "Unit tests for wagoe.core.validation.codes namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [wagoe.core.validation.codes :as codes]))

(deftest ^:unit get-error-code-info-test
  (testing "known common error code"
    (let [info (codes/get-error-code-info :required)]
      (is (some? info))
      (is (= "Field is required" (:description info)))))

  (testing "known user error code"
    (let [info (codes/get-error-code-info :user.email/required)]
      (is (some? info))
      (is (= :schema (:category info)))
      (is (= :email (:field info)))))

  (testing "unknown error code returns nil"
    (is (nil? (codes/get-error-code-info :nonexistent/code)))))

(deftest ^:unit error-code-exists?-test
  (testing "existing codes"
    (is (true? (codes/error-code-exists? :required)))
    (is (true? (codes/error-code-exists? :user.email/required))))

  (testing "non-existing codes"
    (is (false? (codes/error-code-exists? :fake/code)))))

(deftest ^:unit get-error-codes-by-category-test
  (testing "schema category returns codes"
    (let [results (codes/get-error-codes-by-category :schema)]
      (is (vector? results))
      (is (pos? (count results)))
      (is (every? #(= :schema (:category (second %))) results))))

  (testing "business category returns codes"
    (let [results (codes/get-error-codes-by-category :business)]
      (is (vector? results))
      (is (pos? (count results)))
      (is (every? #(= :business (:category (second %))) results)))))

(deftest ^:unit get-error-codes-for-field-test
  (testing "email field returns related codes"
    (let [results (codes/get-error-codes-for-field :email)]
      (is (vector? results))
      (is (pos? (count results)))
      (is (every? #(= :email (:field (second %))) results))))

  (testing "non-existing field returns empty vector"
    (is (empty? (codes/get-error-codes-for-field :nonexistent-field)))))

(deftest ^:unit suggest-error-code-test
  (testing "suggests codes whose name contains the input"
    (let [suggestions (codes/suggest-error-code :required)]
      (is (vector? suggestions))
      (is (pos? (count suggestions)))
      (is (every? #(clojure.string/includes? (name %) "required") suggestions))))

  (testing "no suggestions for unrelated input"
    (let [suggestions (codes/suggest-error-code :zzz-completely-unknown)]
      (is (vector? suggestions))
      (is (empty? suggestions)))))

(deftest ^:unit error-code-catalog-test
  (testing "catalog merges all module codes"
    (is (map? codes/error-code-catalog))
    (is (contains? codes/error-code-catalog :required))
    (is (contains? codes/error-code-catalog :user.email/required))
    (is (contains? codes/error-code-catalog :billing.amount/required))
    (is (contains? codes/error-code-catalog :workflow.status/required))))
