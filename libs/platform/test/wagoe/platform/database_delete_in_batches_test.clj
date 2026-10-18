(ns wagoe.platform.database-delete-in-batches-test
  "Batched DELETE on the engine WAGOE_TEST_DB names."
  (:require [clojure.test :refer [deftest is]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]))

(deftest ^:integration delete-in-batches-takes-every-match-and-nothing-else
  (test-db/with-db "delete_batches"
    (fn [ctx]
      (db/execute-ddl! ctx "CREATE TABLE purge_me (id VARCHAR(36) PRIMARY KEY, old_row BOOLEAN NOT NULL)")
      (doseq [i (range 7)]
        (db/execute-update! ctx {:insert-into :purge_me
                                 :values [{:id (str (random-uuid)) :old_row (< i 5)}]}))
      (is (= 5 (db/delete-in-batches! ctx :purge_me [:= :old_row true] {:batch-size 2})))
      (is (= [false false] (mapv :old-row (db/execute-query! ctx {:select [:old_row] :from [:purge_me]}))))
      (is (zero? (db/delete-in-batches! ctx :purge_me [:= :old_row true] {:batch-size 2}))))))
