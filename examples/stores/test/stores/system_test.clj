(ns stores.system-test
  "The whole shop app, booted once per store. The product API answers the same
   on both, and on :memory nothing reaches the products table."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [shop.product.shell.memory]
            [shop.product.shell.persistence]
            [stores.system-config :as stores]
            [wagoe.platform.database :as db]
            [wagoe.test :as t])
  (:import [shop.product.shell.memory MemoryProductRepository]
           [shop.product.shell.persistence DatabaseProductRepository]))

(defn- body [response]
  (json/parse-string (:body response) true))

(deftest ^:integration the-product-api-runs-on-either-store
  (doseq [[store repository-type rows] [[:sql DatabaseProductRepository 1]
                                        [:memory MemoryProductRepository 0]]]
    (testing (name store)
      (t/with-test-system [sys {:ig-config stores/ig-config
                                :config    #(assoc-in % [:active :wagoe/product :store] store)}]
        (is (instance? repository-type
                       (:repository (:wagoe/product-service sys))))
        (let [c       (doto (t/client sys) (t/sign-in-as! {:roles [:product/editor]}))
              created (t/request c :post "/api/v1/products" {:json {:name "Tee" :sku "T-1" :price 9.99}})
              id      (:id (body created))]
          (is (= 201 (:status created)))
          (is (= "T-1" (:sku (body (t/request c :get (str "/api/v1/products/" id))))))
          (is (= ["T-1"] (map :sku (body (t/request c :get "/api/v1/products")))))
          (testing "the products table holds the row only when SQL is the store"
            (is (= rows (:n (db/execute-one! (:wagoe/db-context sys)
                                             {:select [[:%count.* :n]] :from [:products]}))))))))))
