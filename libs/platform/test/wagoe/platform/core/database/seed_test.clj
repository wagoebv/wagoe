(ns wagoe.platform.core.database.seed-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.core.database.seed :as seed]))

(deftest ^:unit validate-seed-accepts-well-formed-data
  (testing "a map of table -> uniform rows is valid"
    (let [data {:tasks [{:title "a" :done false}
                        {:title "b" :done true}]}]
      (is (= {:ok data} (seed/validate-seed data))))))

(deftest ^:unit validate-seed-rejects-bad-shapes
  (testing "non-map input"
    (is (= :validation-error (get-in (seed/validate-seed [1 2 3]) [:error :type]))))

  (testing "empty file — nothing to do, and silence would look like success"
    (is (= :validation-error (get-in (seed/validate-seed {}) [:error :type]))))

  (testing "rows must be sequential"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks {:title "a"}}) [:error :type]))))

  (testing "a table with no rows is a mistake, not a no-op"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks []}) [:error :type]))))

  (testing "every row must be a map"
    (is (= :validation-error
           (get-in (seed/validate-seed {:tasks [{:title "a"} "nope"]}) [:error :type]))))

  (testing "ragged rows are rejected — the missing key would insert NULL silently"
    (let [result (seed/validate-seed {:tasks [{:title "a" :done false}
                                              {:title "b"}]})]
      (is (= :validation-error (get-in result [:error :type])))
      (is (= :tasks (get-in result [:error :table]))))))

(deftest ^:unit row-and-table-names-convert-to-snake-case
  (testing "column keys"
    (is (= {:created_at "x" :user_id 1}
           (seed/row->columns {:created-at "x" :user-id 1}))))

  (testing "table names"
    (is (= "audit_logs" (seed/table->name :audit-logs)))
    (is (= "tasks" (seed/table->name :tasks)))))

(deftest ^:unit seed-plan-preserves-file-order
  (testing "parents can be listed before children, and that order is kept"
    (let [plan (seed/seed-plan (array-map :users [{:email "a@b.c"}]
                                          :tasks [{:title "t" :user-id 1}]))]
      (is (= ["users" "tasks"] (mapv :table plan)))
      (is (= [1 1] (mapv :count plan)))
      (is (= [{:email "a@b.c"}] (:rows (first plan))))
      (is (= [{:title "t" :user_id 1}] (:rows (second plan)))))))
