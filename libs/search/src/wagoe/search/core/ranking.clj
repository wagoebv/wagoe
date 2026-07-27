;; ^:boundary/allow-throw / ^:boundary/allow-mutable-state — the throw is a
;; total-function guard on an unparseable timestamp; the volatile! usages are
;; local accumulators inside otherwise-pure ranking fns (no shared/process
;; state). Both are intentional and local, not FC/IS violations to migrate.
(ns ^:boundary/allow-throw ^:boundary/allow-mutable-state wagoe.search.core.ranking
  "Pure ranking and scoring functions for search results.
   
   This namespace provides functions for calculating relevance scores,
   applying boosts, and normalizing results. All functions are pure.
   
   Architecture: Functional Core (Pure)"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Field Weighting
;; ============================================================================

(defn calculate-field-weight
  "Calculate weight for field based on configuration.
   
   PostgreSQL text search weights:
   - 'A: 1.0 (highest weight - titles, names)
   - 'B: 0.4 (medium-high - emails, summaries)
   - 'C: 0.2 (medium - descriptions, content)
   - 'D: 0.1 (lowest - metadata, tags)
   
   Args:
     field - Field keyword (:name, :email, etc.)
     config - Configuration map with :weights
     
   Returns:
     Float weight (0.1-1.0)
     
   Example:
     (calculate-field-weight :name {:weights {:name 'A}})
     ;=> 1.0
     
     (calculate-field-weight :bio {:weights {:bio 'C}})
     ;=> 0.2
     
   Pure: true"
  [field config]
  (let [weight-symbol (get-in config [:weights field] 'D)]
    (case weight-symbol
      A 1.0
      B 0.4
      C 0.2
      D 0.1
      0.1)))  ; Default to lowest weight

(defn normalize-field-weights
  "Normalize field weights to sum to 1.0.
   
   Useful when combining scores from multiple fields.
   
   Args:
     weights - Map of field->weight
     
   Returns:
     Map of field->normalized-weight
     
   Example:
     (normalize-field-weights {:name 1.0 :email 0.4 :bio 0.2})
     ;=> {:name 0.625 :email 0.25 :bio 0.125}
     
   Pure: true"
  [weights]
  (let [total (reduce + (vals weights))]
    (if (zero? total)
      weights
      (into {} (map (fn [[field weight]]
                      [field (/ weight total)])
                    weights)))))

;; ============================================================================
;; Recency Boost
;; ============================================================================

(defn- parse-timestamp
  "Parse various timestamp formats to java.time.Instant.
   
   Handles:
   - java.time.Instant (returns as-is)
   - java.sql.Timestamp (converts to Instant)
   - ISO-8601 string (2024-01-01T12:00:00Z)
   - PostgreSQL timestamp string (2024-01-01 12:00:00.123456)
   
   Args:
     ts - Timestamp in various formats
     
   Returns:
     java.time.Instant
     
   Pure: true"
  [ts]
  (cond
    (instance? java.time.Instant ts)
    ts

    (instance? java.sql.Timestamp ts)
    (.toInstant ^java.sql.Timestamp ts)

    (string? ts)
    (try
      ;; Try ISO-8601 format first
      (java.time.Instant/parse ts)
      (catch java.time.format.DateTimeParseException _
        ;; Try PostgreSQL format: "2024-01-01 12:00:00.123456"
        ;; Convert space to T and add Z
        (let [normalized (-> ts
                             (str/replace #" " "T")
                             (str "Z"))]
          (java.time.Instant/parse normalized))))

    :else
    (throw (ex-info "Unsupported timestamp format"
                    {:type :timestamp-parse-error
                     :value ts
                     :class (class ts)}))))

(defn calculate-document-age-days
  "Calculate document age in days.
   
   Args:
     created-at - java.time.Instant, java.sql.Timestamp, or string timestamp
     current-time - java.time.Instant
   
   Returns:
     Age in days (integer)
     
   Example:
     (calculate-document-age-days #inst \"2024-01-01\" #inst \"2024-01-08\")
     ;=> 7
     
   Pure: true"
  ([created-at current-time]
   (let [created-instant (parse-timestamp created-at)
         current-instant (parse-timestamp current-time)
         duration (java.time.Duration/between created-instant current-instant)]
     (.toDays duration))))

(defn apply-recency-boost
  "Apply exponential decay boost for recent documents.
   
   Boosts recent documents' scores to prefer fresh content.
   Uses exponential decay: score * (1 + e^(-decay * age))
   
   Args:
     base-score - Original relevance score (0-1)
     document-age-days - Age of document in days
     decay-factor - Decay rate (default: 0.1)
                    Higher = faster decay (prefer very recent docs)
                    Lower = slower decay (prefer recent but not too aggressive)
     
   Returns:
     Boosted score
     
   Example:
     ;; Recent document (1 day old) gets significant boost
     (apply-recency-boost 0.5 1 0.1)
     ;=> ~0.95
     
     ;; Older document (30 days) gets small boost
     (apply-recency-boost 0.5 30 0.1)
     ;=> ~0.52
     
     ;; Very old document (100 days) gets no boost
     (apply-recency-boost 0.5 100 0.1)
     ;=> ~0.50
     
   Pure: true"
  ([base-score document-age-days]
   (apply-recency-boost base-score document-age-days 0.1))
  ([base-score document-age-days decay-factor]
   (let [time-decay (Math/exp (* (- decay-factor) document-age-days))
         boost-factor (+ 1.0 time-decay)]
     (* base-score boost-factor))))

(defn apply-linear-recency-boost
  "Apply linear decay boost for recent documents.
   
   Simpler than exponential decay. Boost decreases linearly with age.
   
   Args:
     base-score - Original relevance score
     document-age-days - Age in days
     max-boost - Maximum boost for brand new documents (default: 2.0 = 2x)
     decay-days - Days until boost reaches 0 (default: 30)
     
   Returns:
     Boosted score
     
   Example:
     ;; Brand new document gets 2x boost
     (apply-linear-recency-boost 0.5 0 2.0 30)
     ;=> 1.0
     
     ;; 15 day old document gets 1.5x boost
     (apply-linear-recency-boost 0.5 15 2.0 30)
     ;=> 0.75
     
     ;; 30+ day old document gets no boost
     (apply-linear-recency-boost 0.5 35 2.0 30)
     ;=> 0.5
     
   Pure: true"
  ([base-score document-age-days]
   (apply-linear-recency-boost base-score document-age-days 2.0 30))
  ([base-score document-age-days max-boost decay-days]
   (let [boost-factor (if (>= document-age-days decay-days)
                        1.0
                        (+ 1.0 (* (- max-boost 1.0)
                                  (- 1.0 (/ document-age-days decay-days)))))]
     (* base-score boost-factor))))

;; ============================================================================
;; Score Normalization
;; ============================================================================

(defn normalize-scores
  "Normalize scores to 0-1 range using min-max normalization.
   
   Args:
     results - List of result maps with :score
     
   Returns:
     Results with added :normalized-score field
     
   Example:
     (normalize-scores [{:id 1 :score 0.8}
                        {:id 2 :score 0.5}
                        {:id 3 :score 0.3}])
     ;=> [{:id 1 :score 0.8 :normalized-score 1.0}
     ;    {:id 2 :score 0.5 :normalized-score 0.4}
     ;    {:id 3 :score 0.3 :normalized-score 0.0}]
     
   Pure: true"
  [results]
  (if (empty? results)
    results
    (let [scores (map :score results)
          max-score (apply max scores)
          min-score (apply min scores)
          range (- max-score min-score)]
      (if (zero? range)
        ;; All scores are equal - set normalized score to 1.0
        (map #(assoc % :normalized-score 1.0) results)
        ;; Normalize to 0-1 range
        (map #(assoc % :normalized-score
                     (/ (- (:score %) min-score) range))
             results)))))

(defn normalize-scores-zscore
  "Normalize scores using z-score standardization.
   
   Z-score normalization: (score - mean) / stddev
   Results in mean=0, stddev=1 distribution.
   
   Args:
     results - List of result maps with :score
     
   Returns:
     Results with added :z-score field
     
   Example:
     (normalize-scores-zscore [{:id 1 :score 0.8}
                               {:id 2 :score 0.5}
                               {:id 3 :score 0.3}])
     
   Pure: true"
  [results]
  (if (< (count results) 2)
    results
    (let [scores (map :score results)
          n (count scores)
          mean (/ (reduce + scores) n)
          variance (/ (reduce + (map #(Math/pow (- % mean) 2) scores)) n)
          stddev (Math/sqrt variance)]
      (if (zero? stddev)
        (map #(assoc % :z-score 0.0) results)
        (map #(assoc % :z-score
                     (/ (- (:score %) mean) stddev))
             results)))))

;; ============================================================================
;; Score Combination
;; ============================================================================

(defn combine-scores
  "Combine multiple scores with weights.
   
   Useful for combining text relevance, recency, popularity, etc.
   
   Args:
     scores - Map of score-name->value
              {:relevance 0.8 :recency 0.6 :popularity 0.9}
     weights - Map of score-name->weight
               {:relevance 0.6 :recency 0.2 :popularity 0.2}
     
   Returns:
     Combined score (weighted average)
     
   Example:
     (combine-scores {:relevance 0.8 :recency 0.5}
                     {:relevance 0.7 :recency 0.3})
     ;=> 0.71
     
   Pure: true"
  [scores weights]
  (let [weighted-scores (for [[name score] scores]
                          (* score (get weights name 1.0)))
        total-weight (reduce + (vals weights))]
    (if (zero? total-weight)
      0.0
      (/ (reduce + weighted-scores) total-weight))))

(defn multiply-scores
  "Multiply scores together (useful for boolean scoring).
   
   Args:
     scores - Collection of scores
     
   Returns:
     Product of all scores
     
   Example:
     (multiply-scores [0.8 0.9 0.7])
     ;=> 0.504
     
   Pure: true"
  [scores]
  (reduce * 1.0 scores))

;; ============================================================================
;; Ranking Functions
;; ============================================================================

(defn rank-results
  "Rank results by score (highest first).
   
   Args:
     results - List of result maps with :score
     
   Returns:
     Results sorted by score descending (as vector)
     
   Example:
     (rank-results [{:id 1 :score 0.5}
                    {:id 2 :score 0.9}
                    {:id 3 :score 0.7}])
     ;=> [{:id 2 :score 0.9}
     ;    {:id 3 :score 0.7}
     ;    {:id 1 :score 0.5}]
     
   Pure: true"
  [results]
  (vec (sort-by :score #(compare %2 %1) results)))

(defn rank-by-field
  "Rank results by field value.
   
   Args:
     results - List of result maps
     field - Field to sort by
     direction - :asc or :desc
     
   Returns:
     Sorted results
     
   Example:
     (rank-by-field [{:name \"Zoe\"} {:name \"Alice\"}] :name :asc)
     ;=> [{:name \"Alice\"} {:name \"Zoe\"}]
     
   Pure: true"
  [results field direction]
  (let [comparator (if (= direction :desc)
                     #(compare %2 %1)
                     compare)]
    (sort-by field comparator results)))

(defn add-rank-position
  "Add rank position (1, 2, 3...) to results.
   
   Args:
     results - Sorted list of results
     
   Returns:
     Results with :rank field added (as vector)
     
   Example:
     (add-rank-position [{:score 0.9} {:score 0.7}])
     ;=> [{:score 0.9 :rank 1} {:score 0.7 :rank 2}]
     
   Pure: true"
  [results]
  (vec (map-indexed (fn [idx result]
                      (assoc result :rank (inc idx)))
                    results)))

;; ============================================================================
;; Scoring Metrics
;; ============================================================================

(defn calculate-average-score
  "Calculate average score across results.
   
   Args:
     results - List of result maps with :score
     
   Returns:
     Average score (float)
     
   Example:
     (calculate-average-score [{:score 0.8} {:score 0.6} {:score 0.4}])
     ;=> 0.6
     
   Pure: true"
  [results]
  (if (empty? results)
    0.0
    (/ (reduce + (map :score results))
       (count results))))

(defn calculate-median-score
  "Calculate median score across results.
   
   Args:
     results - List of result maps with :score
     
   Returns:
     Median score (float)
     
   Example:
     (calculate-median-score [{:score 0.8} {:score 0.6} {:score 0.4}])
     ;=> 0.6
     
   Pure: true"
  [results]
  (if (empty? results)
    0.0
    (let [sorted-scores (sort (map :score results))
          n (count sorted-scores)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted-scores mid)
        (/ (+ (nth sorted-scores mid)
              (nth sorted-scores (dec mid)))
           2.0)))))

;; ============================================================================
;; Diversity & De-duplication
;; ============================================================================

(defn deduplicate-by-field
  "Remove duplicate results based on field value.
   
   Keeps first occurrence of each unique value.
   
   Args:
     results - List of result maps
     field - Field to deduplicate by
     
   Returns:
     Deduplicated results
     
   Example:
     (deduplicate-by-field [{:id 1 :name \"John\"}
                            {:id 2 :name \"Jane\"}
                            {:id 3 :name \"John\"}]
                           :name)
     ;=> [{:id 1 :name \"John\"} {:id 2 :name \"Jane\"}]
     
   Pure: true"
  [results field]
  (let [seen (volatile! #{})]
    (filter (fn [result]
              (let [value (get result field)]
                (if (contains? @seen value)
                  false
                  (do (vswap! seen conj value)
                      true))))
            results)))

(defn diversify-results
  "Diversify results by field (reduce over-representation).
   
   Ensures results are diverse by limiting how many results
   can have the same field value.
   
   Args:
     results - Sorted list of results
     field - Field to diversify by (e.g., :category, :author)
     max-per-value - Maximum results per unique value
     
   Returns:
     Diversified results
     
   Example:
     (diversify-results [{:category \"tech\" :score 0.9}
                         {:category \"tech\" :score 0.8}
                         {:category \"tech\" :score 0.7}
                         {:category \"sports\" :score 0.6}]
                        :category
                        2)
     ;=> First 2 tech results + sports result
     
   Pure: true"
  [results field max-per-value]
  (let [counts (volatile! {})]
    (filter (fn [result]
              (let [value (get result field)
                    current-count (get @counts value 0)]
                (if (< current-count max-per-value)
                  (do (vswap! counts update value (fnil inc 0))
                      true)
                  false)))
            results)))
