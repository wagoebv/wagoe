(ns wagoe.platform.database-current-transaction-test
  "`current-transaction` with transactions on two databases nested (BOU-971)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.common.core :as common]
            [wagoe.platform.shell.adapters.database.h2.core :as h2])
  (:import (java.io Closeable)
           (java.util UUID)))

(defn- h2-ctx []
  (let [adapter (h2/new-adapter)]
    {:adapter    adapter
     :datasource (common/create-connection-pool
                  adapter {:adapter           :h2
                           :database-path     (str "mem:current-tx-" (UUID/randomUUID))
                           :connection-params {:DB_CLOSE_DELAY "-1"}})}))

(defn- notes [ctx]
  (set (map :k (db/execute-query! ctx {:select [:k] :from [:notes]}))))

(defn- note! [ctx k]
  (db/execute-update! ctx {:insert-into :notes :values [{:k k}]}))

(deftest ^:integration current-transaction-answers-the-database-asked-about
  (let [first-db (h2-ctx)
        other    (h2-ctx)]
    (try
      (doseq [c [first-db other]] (db/execute-ddl! c "CREATE TABLE notes (k VARCHAR(64) PRIMARY KEY)"))
      (testing "outside a transaction, none"
        (is (nil? (db/current-transaction first-db)))
        (is (nil? (db/current-transaction))))
      (db/with-transaction [tx first-db]
        (db/with-transaction [inner other]
          (let [first-tx (db/current-transaction first-db)]
            (testing "each database's own, by context or datasource"
              (is (identical? (db/tx-connection tx) first-tx))
              (is (identical? first-tx (db/current-transaction (:datasource first-db))))
              (is (identical? (db/tx-connection inner) (db/current-transaction other))))
            (testing "a transaction context answers its own connection"
              (is (identical? first-tx (db/current-transaction tx))))
            (testing "the 0-arity still answers the innermost"
              (is (identical? (db/tx-connection inner) (db/current-transaction))))
            (testing "a savepoint on it rolls back the first database's write only"
              (note! other "other")
              (is (thrown-with-msg? Exception #"abort"
                                    (db/in-savepoint first-tx
                                                     #(do (note! first-db "rolled-back")
                                                          (throw (RuntimeException. "abort"))))))
              (note! first-db "kept")))))
      (is (= #{"kept"} (notes first-db)))
      (is (= #{"other"} (notes other)))
      (finally
        (doseq [c [first-db other]] (.close ^Closeable (:datasource c)))))))
