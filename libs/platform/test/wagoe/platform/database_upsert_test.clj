(ns wagoe.platform.database-upsert-test
  "`upsert!` and `ensure-binary-collation!` on the engine WAGOE_TEST_DB names."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db])
  (:import [java.sql SQLException]))

(defn- with-table [f]
  (test-db/with-db "upsert"
    (fn [ctx]
      (db/execute-ddl! ctx "CREATE TABLE kv (k VARCHAR(64) NOT NULL PRIMARY KEY, v VARCHAR(64), born VARCHAR(64))")
      (f ctx))))

(defn- rows [ctx]
  (db/execute-query! ctx {:select [:k :v :born] :from [:kv] :order-by [:k]}))

(def ^:private deadlock
  (ex-info "Database update failed" {:type :database-error}
           (SQLException. "Deadlock found when trying to get lock" "40001" 1213)))

(deftest ^:integration upsert-inserts-then-updates
  (with-table
    (fn [ctx]
      (is (= :inserted (db/upsert! ctx :kv {:k "a" :v "1" :born "first"} [:k])))
      (is (= :updated (db/upsert! ctx :kv {:k "a" :v "2" :born "second"} [:k] {:update-columns [:v]})))
      (is (= [{:k "a" :v "2" :born "first"}] (rows ctx)) "only :update-columns change"))))

(deftest ^:integration a-writer-that-loses-the-race-to-insert-updates
  (with-table
    (fn [ctx]
      (let [update! db/execute-update!
            raced?  (atom false)]
        (with-redefs [db/execute-update! (fn [c q]
                                           (when (and (:insert-into q) (compare-and-set! raced? false true))
                                             (update! c {:insert-into :kv :values [{:k "a" :v "rival"}]}))
                                           (update! c q))]
          (is (= :updated (db/upsert! ctx :kv {:k "a" :v "mine"} [:k]))))
        (is (= ["mine"] (map :v (rows ctx))))))))

(deftest ^:integration a-deadlock-is-retried-outside-a-transaction-only
  (with-table
    (fn [ctx]
      (let [update! db/execute-update!
            fails   (atom 2)]
        (with-redefs [db/execute-update! (fn [c q]
                                           (when (and (:insert-into q) (pos? (swap! fails dec)))
                                             (throw deadlock))
                                           (update! c q))]
          (is (= :inserted (db/upsert! ctx :kv {:k "a" :v "1"} [:k])))
          (testing "in a transaction the engine rolled all of it back, so it is the caller's to retry"
            (reset! fails 2)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database update failed"
                                  (db/with-transaction [tx ctx]
                                    (db/upsert! tx :kv {:k "b" :v "1"} [:k]))))))
        (is (= ["a"] (map :k (rows ctx))))))))

(deftest ^:integration a-binary-collation-keeps-keys-differing-in-case-apart
  (with-table
    (fn [ctx]
      (db/ensure-binary-collation! ctx :kv :k "VARCHAR(64)" true)
      (db/ensure-binary-collation! ctx :kv :k "VARCHAR(64)" true)          ; idempotent
      (db/upsert! ctx :kv {:k "A" :v "upper"} [:k])
      (db/upsert! ctx :kv {:k "a" :v "lower"} [:k])
      (is (= #{"upper" "lower"} (set (map :v (rows ctx))))))))
