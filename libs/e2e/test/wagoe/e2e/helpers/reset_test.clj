(ns wagoe.e2e.helpers.reset-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.e2e.helpers.reset :as reset]))

(deftest ^:unit base-url-defaults-to-localhost-3100
  (testing "default base URL matches the bb e2e server"
    (is (= "http://localhost:3100" (reset/default-base-url)))))

(deftest ^:unit parses-seed-response-shape
  (testing "parse-seed-response returns a SeedResult with :tenant :admin :user"
    (let [body   {:ok     true
                  :seeded {:tenant {:id "T-1" :slug "acme"}
                           :admin  {:id "A-1" :email "admin@acme.test"
                                    :password "Test-Pass-1234!"}
                           :user   {:id "U-1" :email "user@acme.test"
                                    :password "Test-Pass-1234!"}}}
          result (reset/parse-seed-response body)]
      (is (= "acme" (-> result :tenant :slug)))
      (is (= "admin@acme.test" (-> result :admin :email)))
      (is (= "user@acme.test" (-> result :user :email))))))
