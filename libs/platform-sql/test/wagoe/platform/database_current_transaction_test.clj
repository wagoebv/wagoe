(ns wagoe.platform.database-current-transaction-test
  "`current-transaction` and `open-transaction?` with transactions on two
   databases nested (BOU-971, BOU-972)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory])
  (:import (java.util UUID)))

(defn- h2-ctx []
  (factory/db-context (factory/h2-config (str "mem:current-tx-" (UUID/randomUUID) ";DB_CLOSE_DELAY=-1"))))

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
        (run! factory/close-db-context! [first-db other])))))

(deftest ^:integration open-transaction-knows-each-nested-transaction
  (let [first-db (h2-ctx)
        other    (h2-ctx)]
    (try
      (db/with-transaction [tx first-db]
        (db/with-transaction [inner other]
          (testing "the outer database's, and the inner's"
            (is (db/open-transaction? (db/tx-connection tx)))
            (is (db/open-transaction? (db/tx-connection inner))))
          (testing "a connection this thread opened no transaction on"
            (with-open [^java.sql.Connection own (.getConnection ^javax.sql.DataSource (:datasource first-db))]
              (.setAutoCommit own false)
              (is (not (db/open-transaction? own)))))
          (is (not (db/open-transaction? nil)))))
      (testing "once committed, no longer"
        (let [conn (db/with-transaction [tx first-db] (db/tx-connection tx))]
          (is (not (db/open-transaction? conn)))))
      (finally
        (run! factory/close-db-context! [first-db other])))))
