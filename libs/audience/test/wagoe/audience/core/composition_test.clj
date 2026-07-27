(ns wagoe.audience.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.audience.core.composition :as comp]))

(deftest ^:unit and-composition
  (testing "AND intersects user ID sets"
    (is (= #{2 3}
           (comp/compose-results
            {:and [{:user-ids #{1 2 3}} {:user-ids #{2 3 4}}]})))))

(deftest ^:unit or-composition
  (testing "OR unions user ID sets"
    (is (= #{1 2 3 4}
           (comp/compose-results
            {:or [{:user-ids #{1 2}} {:user-ids #{3 4}}]})))))

(deftest ^:unit not-composition
  (testing "NOT excludes user IDs from universe"
    (is (= #{1 4}
           (comp/compose-results
            {:and [{:user-ids #{1 2 3 4}}
                   {:not {:user-ids #{2 3}}}]})))))

(deftest ^:unit nested-composition
  (testing "nested AND/OR/NOT"
    (is (= #{3}
           (comp/compose-results
            {:and [{:or [{:user-ids #{1 2 3}} {:user-ids #{3 4 5}}]}
                   {:not {:user-ids #{1 2 4 5}}}]})))))

(deftest ^:unit resolve-refs
  (testing "segment refs resolved via lookup fn"
    (let [lookup (fn [id]
                   (case id
                     :seg-a {:user-ids #{1 2 3}}
                     :seg-b {:user-ids #{2 3 4}}
                     nil))]
      (is (= #{2 3}
             (comp/resolve-and-compose
              {:and [{:ref :seg-a} {:ref :seg-b}]}
              lookup))))))

(deftest ^:unit circular-ref-detection
  (testing "explain-composition flags a circular reference; evaluation still terminates"
    (let [lookup (fn [id]
                   (case id
                     :seg-a {:compose {:and [{:ref :seg-b}]}}
                     :seg-b {:compose {:and [{:ref :seg-a}]}}
                     nil))
          tree   {:and [{:ref :seg-a}]}]
      (is (= :circular-reference (get-in (comp/explain-composition tree lookup) [:error :type])))
      ;; core no longer throws — it fails safe to a set rather than looping
      (is (set? (comp/resolve-and-compose tree lookup))))))

(deftest ^:unit explain-composition-validation
  (let [lookup (fn [id] (case id
                          :seg-a {:user-ids #{1 2}}
                          :with-compose {:compose {:or [{:user-ids #{1}}]}}
                          :bad-seg {}
                          nil))]
    (testing "well-formed tree returns nil"
      (is (nil? (comp/explain-composition {:and [{:ref :seg-a} {:user-ids #{3}}]} lookup))))
    (testing "unknown operator"
      (is (= :composition-error
             (get-in (comp/explain-composition {:xor [{:user-ids #{1}}]} lookup) [:error :type]))))
    (testing "NOT directly inside OR"
      (is (= :composition-error
             (get-in (comp/explain-composition {:or [{:not {:user-ids #{1}}}]} lookup) [:error :type]))))
    (testing "unknown segment ref"
      (is (= :unknown-segment-ref
             (get-in (comp/explain-composition {:and [{:ref :missing}]} lookup) [:error :type]))))
    (testing "segment with neither :user-ids nor :compose"
      (is (= :invalid-segment
             (get-in (comp/explain-composition {:and [{:ref :bad-seg}]} lookup) [:error :type]))))))
