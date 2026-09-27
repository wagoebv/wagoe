(ns shop.product.shell.product-repository-test
  (:require [clojure.test :refer [deftest testing is]]
            [shop.product.shell.persistence :as persistence]
            [shop.product.ports :as ports]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- with-database
  "Call `f` with a context on a fresh in-memory H2 database, migrated."
  [f]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:" (UUID/randomUUID))
                                    :pool {:minimum-idle 1 :maximum-pool-size 2}})]
    (try
      (migrations/migrate-datasource! (:datasource ctx))
      (f ctx)
      (finally (db-factory/close-db-context! ctx)))))

(deftest ^:integration create-product-test
  (testing "the repository implements its persistence port"
    (is (satisfies? ports/IProductRepository
                    (persistence/create-repository nil))))
  (testing "a product round-trips through the database"
    (with-database
      (fn [ctx]
        (let [repo    (persistence/create-repository ctx)
              fields  {:name "Test"
                       :sku "Test"
                       :price 9.99M}
              created (ports/create repo (assoc fields :id (UUID/randomUUID) :created-at (Instant/now)))]
          (is (= fields (select-keys created (keys fields))))
          (is (= created (ports/find-by-id repo (:id created)))))))))
