(ns wagoe.product.shell.product-repository-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.product.shell.persistence :as persistence]
            [wagoe.product.ports :as ports]))

(deftest ^:integration create-product-test
  (testing "the repository implements its persistence port"
    (is (satisfies? ports/IProductRepository
                    (persistence/create-repository nil))))
  (testing "creating a product round-trips through the database"
    ;; Add a database context and assert on a real create here.
    ;; See the module README for wiring a test db-ctx.
    ))
