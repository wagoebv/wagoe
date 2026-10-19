(ns wagoe.platform.database-after-commit-test
  "`after-commit!` on the engine WAGOE_TEST_DB names."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]))

(defn- with-table [f]
  (test-db/with-db "after_commit"
    (fn [ctx]
      (db/execute-ddl! ctx "CREATE TABLE ac (k VARCHAR(64) NOT NULL PRIMARY KEY)")
      (f ctx))))

(defn- committed-keys
  "The keys another connection sees."
  [ctx]
  (mapv :k (db/execute-query! ctx {:select [:k] :from [:ac] :order-by [:k]})))

(deftest ^:integration after-commit-runs-once-the-outermost-transaction-commits
  (with-table
    (fn [ctx]
      (let [seen (atom [])]
        (testing "a hook sees what the transaction wrote, committed"
          (db/with-transaction [tx ctx]
            (db/execute-update! tx {:insert-into :ac :values [{:k "a"}]})
            (db/with-transaction [inner tx]
              (db/after-commit! #(swap! seen conj [:inner (committed-keys ctx)]))
              (db/execute-update! inner {:insert-into :ac :values [{:k "b"}]}))
            (db/after-commit! #(swap! seen conj [:outer (committed-keys ctx)]))
            (is (empty? @seen) "not before commit, nor when the nested one ends"))
          (is (= [[:inner ["a" "b"]] [:outer ["a" "b"]]] @seen)))

        (testing "a rolled-back transaction runs none"
          (reset! seen [])
          (is (thrown? clojure.lang.ExceptionInfo
                       (db/with-transaction [_tx ctx]
                         (db/after-commit! #(swap! seen conj :ran))
                         (throw (ex-info "boom" {:type :internal-error})))))
          (is (empty? @seen)))

        (testing "a failing hook does not stop the next, nor fail the commit"
          (reset! seen [])
          (is (= :done (db/with-transaction [_tx ctx]
                         (db/after-commit! #(throw (ex-info "hook" {:type :internal-error})))
                         (db/after-commit! #(swap! seen conj :second))
                         :done)))
          (is (= [:second] @seen)))

        (testing "without a transaction, now"
          (reset! seen [])
          (db/after-commit! #(swap! seen conj :now))
          (is (= [:now] @seen)))))))
