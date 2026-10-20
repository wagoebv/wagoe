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

        (testing "a savepoint rolled back drops the hooks it added"
          (reset! seen [])
          (db/with-transaction [tx ctx]
            (db/after-commit! #(swap! seen conj :kept))
            (is (thrown? clojure.lang.ExceptionInfo
                         (db/in-savepoint (db/current-transaction)
                                          #(do (db/after-commit! (fn [] (swap! seen conj :dropped)))
                                               (throw (ex-info "boom" {:type :internal-error}))))))
            (db/execute-update! tx {:insert-into :ac :values [{:k "c"}]}))
          (is (= [:kept] @seen)))

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

(deftest ^:integration a-transaction-remembers-an-answer-until-it-ends
  (with-table
    (fn [ctx]
      (let [calls (atom 0)
            ask   #(db/remembered-in-transaction ::k (fn [] (swap! calls inc)))]
        (db/with-transaction [tx ctx]
          (is (= 1 (ask)))
          (db/with-transaction [_inner tx]
            (is (= 1 (ask)) "a nested transaction shares it")))
        (db/with-transaction [_tx ctx]
          (is (= 2 (ask)) "the next transaction asks again"))
        (is (= 3 (ask)) "and outside one, every time")
        (testing "a falsey answer is asked again"
          (let [n (atom 0)]
            (db/with-transaction [_tx ctx]
              (dotimes [_ 2] (db/remembered-in-transaction ::no #(do (swap! n inc) nil))))
            (is (= 2 @n))))))))

(deftest ^:integration a-nested-transaction-rolls-back-with-the-outer
  (with-table
    (fn [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (db/with-transaction [tx ctx]
                     (db/with-transaction [inner tx]
                       (db/execute-update! inner {:insert-into :ac :values [{:k "nested"}]}))
                     (is (= ["nested"] (committed-keys tx)) "written, in the outer transaction")
                     (throw (ex-info "boom" {:type :internal-error})))))
      (is (= [] (committed-keys ctx)) "the nested write did not commit early"))))
