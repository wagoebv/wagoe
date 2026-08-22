(ns wagoe.product.shell.service-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.product.shell.service :as service]
            [wagoe.product.ports :as ports]))

(deftest ^:unit create-product-test
  (testing "creates product via service"
    (let [mock-repo (reify ports/IProductRepository
                      (create [_ entity] entity)
                      (find-by-id [_ _id] nil)
                      (find-all [_ _opts] [])
                      (update-entity [_ entity] entity)
                      (delete [_ _id] nil))
          svc (service/create-service mock-repo)
          result (ports/create-product svc {:name "Test"})]
      (is (some? result)))))
