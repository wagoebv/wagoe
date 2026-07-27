(ns wagoe.platform.core.database.query-test
  "Unit tests for wagoe.platform.core.database.query namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.database.query :as query]))

(deftest ^:unit adapter-dialect->honey-dialect-test
  (testing "sqlite maps to nil (ANSI)"
    (is (nil? (query/adapter-dialect->honey-dialect :sqlite))))

  (testing "h2 maps to :ansi"
    (is (= :ansi (query/adapter-dialect->honey-dialect :h2))))

  (testing "postgresql passes through"
    (is (= :postgresql (query/adapter-dialect->honey-dialect :postgresql))))

  (testing "mysql passes through"
    (is (= :mysql (query/adapter-dialect->honey-dialect :mysql)))))

(deftest ^:unit build-pagination-test
  (testing "uses defaults when no options"
    (let [result (query/build-pagination {})]
      (is (= query/default-pagination-limit (:limit result)))
      (is (= 0 (:offset result)))))

  (testing "respects provided values"
    (is (= {:limit 50 :offset 100}
           (query/build-pagination {:limit 50 :offset 100}))))

  (testing "clamps limit to max"
    (is (= query/max-pagination-limit
           (:limit (query/build-pagination {:limit 9999})))))

  (testing "clamps limit to min"
    (is (= query/min-pagination-limit
           (:limit (query/build-pagination {:limit 0})))))

  (testing "clamps negative offset to 0"
    (is (= 0 (:offset (query/build-pagination {:offset -5}))))))

(deftest ^:unit build-ordering-test
  (testing "uses default field when not specified"
    (is (= [[:id :asc]]
           (query/build-ordering {} :id))))

  (testing "uses provided sort-by and direction"
    (is (= [[:created-at :desc]]
           (query/build-ordering {:sort-by :created-at :sort-direction :desc} :id))))

  (testing "falls back to :asc for invalid direction"
    (is (= [[:name :asc]]
           (query/build-ordering {:sort-by :name :sort-direction :invalid} :id)))))

(deftest ^:unit build-where-filters-test
  (testing "returns nil for empty filters"
    (is (nil? (query/build-where-filters {})))
    (is (nil? (query/build-where-filters nil))))

  (testing "single filter produces simple condition"
    (is (= [:= :name "John"]
           (query/build-where-filters {:name "John"}))))

  (testing "multiple filters produce :and condition"
    (let [result (query/build-where-filters {:name "John" :active true})]
      (is (= :and (first result)))
      (is (= 3 (count result)))))

  (testing "nil values produce :is nil condition"
    (is (= [:is :deleted_at nil]
           (query/build-where-filters {:deleted-at nil}))))

  (testing "sequential values produce :in condition"
    (is (= [:in :role ["admin" "user"]]
           (query/build-where-filters {:role [:admin :user]}))))

  (testing "converts kebab-case keys to snake_case"
    (let [result (query/build-where-filters {:tenant-id "t-1"})]
      (is (= [:= :tenant_id "t-1"] result))))

  (testing "converts UUID values to strings"
    (let [uuid (java.util.UUID/randomUUID)
          result (query/build-where-filters {:id uuid})]
      (is (= [:= :id (str uuid)] result)))))

(deftest ^:unit format-sql-test
  (testing "formats simple select with sqlite dialect"
    (let [[sql] (query/format-sql :sqlite {:select [:*] :from [:users]})]
      (is (string? sql))
      (is (re-find #"(?i)SELECT" sql))
      (is (re-find #"users" sql))))

  (testing "formats with h2 dialect"
    (let [[sql] (query/format-sql :h2 {:select [:*] :from [:users]})]
      (is (string? sql))
      (is (re-find #"(?i)SELECT" sql))))

  (testing "converts kebab-case to snake_case"
    (let [[sql] (query/format-sql :sqlite {:select [:*] :from [:user-profiles]})]
      (is (re-find #"user_profiles" sql)))))
