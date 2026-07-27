(ns wagoe.search.core.ranking-test
  "Unit tests for search ranking functions.
   
   Tests all scoring, normalization, and ranking functions.
   All functions are pure so tests need no mocks or fixtures."
  {:kaocha.testable/meta {:unit true :search true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.search.core.ranking :as ranking]))

;; ============================================================================
;; Field Weighting
;; ============================================================================

(deftest ^:unit calculate-field-weight-test
  (testing "calculates weight for A-level field"
    (is (= 1.0 (ranking/calculate-field-weight :name {:weights {:name 'A}}))))

  (testing "calculates weight for B-level field"
    (is (= 0.4 (ranking/calculate-field-weight :email {:weights {:email 'B}}))))

  (testing "calculates weight for C-level field"
    (is (= 0.2 (ranking/calculate-field-weight :bio {:weights {:bio 'C}}))))

  (testing "calculates weight for D-level field"
    (is (= 0.1 (ranking/calculate-field-weight :tags {:weights {:tags 'D}}))))

  (testing "defaults to D-level for unconfigured field"
    (is (= 0.1 (ranking/calculate-field-weight :unknown {}))))

  (testing "handles missing weights config"
    (is (= 0.1 (ranking/calculate-field-weight :name nil)))))

(deftest ^:unit normalize-field-weights-test
  (testing "normalizes weights to sum to 1.0"
    (let [weights {:name 1.0 :email 0.4 :bio 0.2}
          normalized (ranking/normalize-field-weights weights)
          sum (reduce + (vals normalized))]
      (is (< (Math/abs (- sum 1.0)) 0.001))))

  (testing "calculates correct proportions"
    (let [weights {:name 1.0 :email 0.4 :bio 0.2}
          normalized (ranking/normalize-field-weights weights)]
      (is (< (Math/abs (- 0.625 (:name normalized))) 0.0001))
      (is (< (Math/abs (- 0.25 (:email normalized))) 0.0001))
      (is (< (Math/abs (- 0.125 (:bio normalized))) 0.0001))))

  (testing "handles equal weights"
    (let [weights {:a 1.0 :b 1.0 :c 1.0}
          normalized (ranking/normalize-field-weights weights)]
      (is (< (Math/abs (- 0.333 (:a normalized))) 0.01))
      (is (< (Math/abs (- 0.333 (:b normalized))) 0.01))
      (is (< (Math/abs (- 0.333 (:c normalized))) 0.01))))

  (testing "handles zero total weight"
    (let [weights {:a 0.0 :b 0.0}
          normalized (ranking/normalize-field-weights weights)]
      (is (= weights normalized))))

  (testing "handles empty weights"
    (is (= {} (ranking/normalize-field-weights {})))))

;; ============================================================================
;; Recency Boost
;; ============================================================================

(deftest ^:unit calculate-document-age-days-test
  (testing "calculates age in days"
    (let [created (.toInstant #inst "2024-01-01T00:00:00Z")
          current (.toInstant #inst "2024-01-08T00:00:00Z")
          age (ranking/calculate-document-age-days created current)]
      (is (= 7 age))))

  (testing "handles same day"
    (let [created (.toInstant #inst "2024-01-01T12:00:00Z")
          current (.toInstant #inst "2024-01-01T18:00:00Z")
          age (ranking/calculate-document-age-days created current)]
      (is (= 0 age))))

  (testing "handles multiple months"
    (let [created (.toInstant #inst "2024-01-01T00:00:00Z")
          current (.toInstant #inst "2024-03-01T00:00:00Z")
          age (ranking/calculate-document-age-days created current)]
      (is (= 60 age))))

  (testing "handles string timestamps"
    (let [age (ranking/calculate-document-age-days
               "2024-01-01T00:00:00Z"
               "2024-01-08T00:00:00Z")]
      (is (= 7 age)))))

(deftest ^:unit apply-recency-boost-test
  (testing "boosts recent documents significantly"
    (let [boosted (ranking/apply-recency-boost 0.5 1 0.1)]
      (is (> boosted 0.5))
      (is (< boosted 1.0))))

  (testing "applies small boost to older documents"
    (let [boosted (ranking/apply-recency-boost 0.5 30 0.1)]
      (is (> boosted 0.5))
      (is (< boosted 0.55))))

  (testing "applies minimal boost to very old documents"
    (let [boosted (ranking/apply-recency-boost 0.5 100 0.1)]
      (is (> boosted 0.5))
      (is (< boosted 0.51))))

  (testing "uses default decay factor"
    (let [boosted (ranking/apply-recency-boost 0.5 10)]
      (is (> boosted 0.5))))

  (testing "higher decay factor means faster decay"
    (let [slow-decay (ranking/apply-recency-boost 0.5 10 0.05)
          fast-decay (ranking/apply-recency-boost 0.5 10 0.2)]
      (is (> slow-decay fast-decay)))))

(deftest ^:unit apply-linear-recency-boost-test
  (testing "brand new document gets maximum boost"
    (let [boosted (ranking/apply-linear-recency-boost 0.5 0 2.0 30)]
      (is (= 1.0 boosted))))

  (testing "mid-age document gets partial boost"
    (let [boosted (ranking/apply-linear-recency-boost 0.5 15 2.0 30)]
      (is (= 0.75 boosted))))

  (testing "old document gets no boost"
    (let [boosted (ranking/apply-linear-recency-boost 0.5 35 2.0 30)]
      (is (= 0.5 boosted))))

  (testing "uses default parameters"
    (let [boosted (ranking/apply-linear-recency-boost 0.5 0)]
      (is (= 1.0 boosted))))

  (testing "linear decay is proportional"
    (let [boost-0 (ranking/apply-linear-recency-boost 0.5 0 2.0 30)
          boost-10 (ranking/apply-linear-recency-boost 0.5 10 2.0 30)
          boost-20 (ranking/apply-linear-recency-boost 0.5 20 2.0 30)
          boost-30 (ranking/apply-linear-recency-boost 0.5 30 2.0 30)]
      (is (> boost-0 boost-10))
      (is (> boost-10 boost-20))
      (is (> boost-20 boost-30))
      (is (= boost-30 0.5)))))

;; ============================================================================
;; Score Normalization
;; ============================================================================

(deftest ^:unit normalize-scores-test
  (testing "normalizes scores to 0-1 range"
    (let [results [{:id 1 :score 0.8}
                   {:id 2 :score 0.5}
                   {:id 3 :score 0.3}]
          normalized (ranking/normalize-scores results)]
      (is (= 1.0 (:normalized-score (first normalized))))
      (is (= 0.0 (:normalized-score (last normalized))))))

  (testing "handles equal scores"
    (let [results [{:id 1 :score 0.5}
                   {:id 2 :score 0.5}
                   {:id 3 :score 0.5}]
          normalized (ranking/normalize-scores results)]
      (is (every? #(= 1.0 (:normalized-score %)) normalized))))

  (testing "handles empty results"
    (is (= [] (ranking/normalize-scores []))))

  (testing "handles single result"
    (let [results [{:id 1 :score 0.5}]
          normalized (ranking/normalize-scores results)]
      (is (= 1.0 (:normalized-score (first normalized))))))

  (testing "preserves original score"
    (let [results [{:id 1 :score 0.8}]
          normalized (ranking/normalize-scores results)]
      (is (= 0.8 (:score (first normalized)))))))

(deftest ^:unit normalize-scores-zscore-test
  (testing "calculates z-scores"
    (let [results [{:id 1 :score 0.8}
                   {:id 2 :score 0.5}
                   {:id 3 :score 0.3}]
          normalized (ranking/normalize-scores-zscore results)
          z-scores (map :z-score normalized)]
      ;; Z-scores should sum to approximately 0
      (is (< (Math/abs (reduce + z-scores)) 0.001))))

  (testing "handles equal scores"
    (let [results [{:id 1 :score 0.5}
                   {:id 2 :score 0.5}]
          normalized (ranking/normalize-scores-zscore results)]
      (is (every? #(= 0.0 (:z-score %)) normalized))))

  (testing "handles single result"
    (let [results [{:id 1 :score 0.5}]
          normalized (ranking/normalize-scores-zscore results)]
      (is (= results normalized))))

  (testing "preserves original score"
    (let [results [{:id 1 :score 0.8}
                   {:id 2 :score 0.5}]
          normalized (ranking/normalize-scores-zscore results)]
      (is (= 0.8 (:score (first normalized)))))))

;; ============================================================================
;; Score Combination
;; ============================================================================

(deftest ^:unit combine-scores-test
  (testing "combines scores with equal weights"
    (let [scores {:relevance 0.8 :recency 0.6}
          weights {:relevance 0.5 :recency 0.5}
          combined (ranking/combine-scores scores weights)]
      (is (= 0.7 combined))))

  (testing "combines scores with different weights"
    (let [scores {:relevance 0.8 :recency 0.5}
          weights {:relevance 0.7 :recency 0.3}
          combined (ranking/combine-scores scores weights)]
      (is (< (Math/abs (- combined 0.71)) 0.01))))

  (testing "handles missing score in weights"
    (let [scores {:relevance 0.8 :recency 0.6}
          weights {:relevance 0.7}
          combined (ranking/combine-scores scores weights)]
      (is (some? combined))))

  (testing "handles zero total weight"
    (let [scores {:relevance 0.8}
          weights {:relevance 0.0}
          combined (ranking/combine-scores scores weights)]
      (is (= 0.0 combined))))

  (testing "handles empty scores"
    (let [combined (ranking/combine-scores {} {})]
      (is (= 0.0 combined)))))

(deftest ^:unit multiply-scores-test
  (testing "multiplies multiple scores"
    (is (= 0.504 (ranking/multiply-scores [0.8 0.9 0.7]))))

  (testing "handles single score"
    (is (= 0.8 (ranking/multiply-scores [0.8]))))

  (testing "handles empty scores"
    (is (= 1.0 (ranking/multiply-scores []))))

  (testing "zero score results in zero"
    (is (= 0.0 (ranking/multiply-scores [0.8 0.0 0.9])))))

;; ============================================================================
;; Ranking Functions
;; ============================================================================

(deftest ^:unit rank-results-test
  (testing "ranks by score descending"
    (let [results [{:id 1 :score 0.5}
                   {:id 2 :score 0.9}
                   {:id 3 :score 0.7}]
          ranked (ranking/rank-results results)]
      (is (= 2 (:id (first ranked))))
      (is (= 3 (:id (second ranked))))
      (is (= 1 (:id (last ranked))))))

  (testing "handles equal scores"
    (let [results [{:id 1 :score 0.5}
                   {:id 2 :score 0.5}]
          ranked (ranking/rank-results results)]
      (is (= 2 (count ranked)))))

  (testing "handles empty results"
    (is (= [] (ranking/rank-results []))))

  (testing "handles single result"
    (let [results [{:id 1 :score 0.5}]
          ranked (ranking/rank-results results)]
      (is (= results ranked)))))

(deftest ^:unit rank-by-field-test
  (testing "ranks by field ascending"
    (let [results [{:name "Zoe"} {:name "Alice"} {:name "Bob"}]
          ranked (ranking/rank-by-field results :name :asc)]
      (is (= "Alice" (:name (first ranked))))
      (is (= "Zoe" (:name (last ranked))))))

  (testing "ranks by field descending"
    (let [results [{:name "Zoe"} {:name "Alice"} {:name "Bob"}]
          ranked (ranking/rank-by-field results :name :desc)]
      (is (= "Zoe" (:name (first ranked))))
      (is (= "Alice" (:name (last ranked))))))

  (testing "ranks by numeric field"
    (let [results [{:score 10} {:score 5} {:score 20}]
          ranked (ranking/rank-by-field results :score :asc)]
      (is (= 5 (:score (first ranked))))
      (is (= 20 (:score (last ranked))))))

  (testing "handles empty results"
    (is (= [] (ranking/rank-by-field [] :name :asc)))))

(deftest ^:unit add-rank-position-test
  (testing "adds rank positions starting from 1"
    (let [results [{:score 0.9} {:score 0.7} {:score 0.5}]
          ranked (ranking/add-rank-position results)]
      (is (= 1 (:rank (first ranked))))
      (is (= 2 (:rank (second ranked))))
      (is (= 3 (:rank (last ranked))))))

  (testing "handles empty results"
    (is (= [] (ranking/add-rank-position []))))

  (testing "handles single result"
    (let [results [{:score 0.9}]
          ranked (ranking/add-rank-position results)]
      (is (= 1 (:rank (first ranked))))))

  (testing "preserves original data"
    (let [results [{:id 1 :score 0.9} {:id 2 :score 0.7}]
          ranked (ranking/add-rank-position results)]
      (is (= 1 (:id (first ranked))))
      (is (= 0.9 (:score (first ranked)))))))

;; ============================================================================
;; Scoring Metrics
;; ============================================================================

(deftest ^:unit calculate-average-score-test
  (testing "calculates average of multiple scores"
    (let [results [{:score 0.8} {:score 0.6} {:score 0.4}]
          avg (ranking/calculate-average-score results)]
      (is (= 0.6 avg))))

  (testing "handles single score"
    (let [results [{:score 0.8}]
          avg (ranking/calculate-average-score results)]
      (is (= 0.8 avg))))

  (testing "handles empty results"
    (is (= 0.0 (ranking/calculate-average-score []))))

  (testing "handles decimal precision"
    (let [results [{:score 0.7} {:score 0.5}]
          avg (ranking/calculate-average-score results)]
      (is (= 0.6 avg)))))

(deftest ^:unit calculate-median-score-test
  (testing "calculates median for odd count"
    (let [results [{:score 0.8} {:score 0.6} {:score 0.4}]
          median (ranking/calculate-median-score results)]
      (is (= 0.6 median))))

  (testing "calculates median for even count"
    (let [results [{:score 0.8} {:score 0.6} {:score 0.4} {:score 0.2}]
          median (ranking/calculate-median-score results)]
      (is (= 0.5 median))))

  (testing "handles single score"
    (let [results [{:score 0.8}]
          median (ranking/calculate-median-score results)]
      (is (= 0.8 median))))

  (testing "handles empty results"
    (is (= 0.0 (ranking/calculate-median-score []))))

  (testing "handles unsorted scores"
    (let [results [{:score 0.3} {:score 0.9} {:score 0.5}]
          median (ranking/calculate-median-score results)]
      (is (= 0.5 median)))))

;; ============================================================================
;; Diversity & De-duplication
;; ============================================================================

(deftest ^:unit deduplicate-by-field-test
  (testing "removes duplicates keeping first occurrence"
    (let [results [{:id 1 :name "John"}
                   {:id 2 :name "Jane"}
                   {:id 3 :name "John"}]
          deduped (ranking/deduplicate-by-field results :name)]
      (is (= 2 (count deduped)))
      (is (= 1 (:id (first deduped))))
      (is (= 2 (:id (second deduped))))))

  (testing "handles no duplicates"
    (let [results [{:id 1 :name "John"}
                   {:id 2 :name "Jane"}]
          deduped (ranking/deduplicate-by-field results :name)]
      (is (= 2 (count deduped)))))

  (testing "handles all duplicates"
    (let [results [{:id 1 :name "John"}
                   {:id 2 :name "John"}
                   {:id 3 :name "John"}]
          deduped (ranking/deduplicate-by-field results :name)]
      (is (= 1 (count deduped)))
      (is (= 1 (:id (first deduped))))))

  (testing "handles empty results"
    (is (= [] (ranking/deduplicate-by-field [] :name))))

  (testing "handles nil field values"
    (let [results [{:id 1 :name nil}
                   {:id 2 :name nil}
                   {:id 3 :name "John"}]
          deduped (ranking/deduplicate-by-field results :name)]
      (is (= 2 (count deduped))))))

(deftest ^:unit diversify-results-test
  (testing "limits results per category"
    (let [results [{:id 1 :category "tech" :score 0.9}
                   {:id 2 :category "tech" :score 0.8}
                   {:id 3 :category "tech" :score 0.7}
                   {:id 4 :category "sports" :score 0.6}]
          diversified (ranking/diversify-results results :category 2)]
      (is (= 3 (count diversified)))
      (is (= 1 (:id (nth diversified 0))))
      (is (= 2 (:id (nth diversified 1))))
      (is (= 4 (:id (nth diversified 2))))))

  (testing "handles max-per-value of 1"
    (let [results [{:id 1 :category "tech"}
                   {:id 2 :category "tech"}
                   {:id 3 :category "sports"}]
          diversified (ranking/diversify-results results :category 1)]
      (is (= 2 (count diversified)))
      (is (= #{"tech" "sports"} (set (map :category diversified))))))

  (testing "handles no diversity needed"
    (let [results [{:id 1 :category "tech"}
                   {:id 2 :category "sports"}]
          diversified (ranking/diversify-results results :category 1)]
      (is (= 2 (count diversified)))))

  (testing "handles empty results"
    (is (= [] (ranking/diversify-results [] :category 2))))

  (testing "handles high max-per-value"
    (let [results [{:id 1 :category "tech"}
                   {:id 2 :category "tech"}]
          diversified (ranking/diversify-results results :category 10)]
      (is (= 2 (count diversified))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:unit ranking-pipeline-test
  (testing "complete ranking pipeline"
    (let [raw-results [{:id 1 :name "John Doe" :score 0.5 :created-at (.toInstant #inst "2024-01-01")}
                       {:id 2 :name "Jane Smith" :score 0.8 :created-at (.toInstant #inst "2024-01-10")}
                       {:id 3 :name "Bob Johnson" :score 0.6 :created-at (.toInstant #inst "2024-01-05")}]
          current-time (.toInstant #inst "2024-01-15")
          ;; Apply recency boost
          boosted (map (fn [result]
                         (let [age (ranking/calculate-document-age-days
                                    (:created-at result) current-time)
                               boosted-score (ranking/apply-recency-boost
                                              (:score result) age 0.1)]
                           (assoc result :score boosted-score)))
                       raw-results)
          ;; Rank by score
          ranked (ranking/rank-results boosted)
          ;; Add rank positions
          with-ranks (ranking/add-rank-position ranked)
          ;; Normalize scores
          normalized (ranking/normalize-scores with-ranks)]

      ;; Verify pipeline worked
      (is (= 3 (count normalized)))
      (is (every? :rank normalized))
      (is (every? :normalized-score normalized))
      ;; Most recent document should rank higher
      (is (= 2 (:id (first normalized))))))

  (testing "score combination with multiple factors"
    (let [result {:relevance-score 0.8 :popularity-score 0.6 :recency-score 0.9}
          weights {:relevance-score 0.5 :popularity-score 0.3 :recency-score 0.2}
          combined (ranking/combine-scores
                    (select-keys result [:relevance-score :popularity-score :recency-score])
                    weights)]
      (is (< 0.7 combined))
      (is (> 0.8 combined)))))
