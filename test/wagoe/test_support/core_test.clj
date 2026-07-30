(ns wagoe.test-support.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.test-support.core :as tsc]))

(deftest ^:unit baseline-seed-spec-test
  (testing "returns tenant + admin + regular user"
    (let [spec (tsc/baseline-seed-spec)]
      (is (= "acme" (-> spec :tenant :slug)))
      (is (= "Acme Test" (-> spec :tenant :name)))
      (is (= "admin@acme.test" (-> spec :admin :email)))
      (is (= :admin (-> spec :admin :role)))
      (is (= "user@acme.test" (-> spec :user :email)))
      (is (= :user (-> spec :user :role)))
      (is (every? #(>= (count (:password %)) 12)
                  [(:admin spec) (:user spec)])))))

(deftest ^:unit empty-seed-spec-test
  (testing "empty seed has no entities"
    (is (= {} (tsc/empty-seed-spec)))))
