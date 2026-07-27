(ns wagoe.platform.core.pagination.pagination-test
  "Unit tests for pagination core functions.
   
   Tests are pure - no I/O, no mocks, just function verification."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.core.pagination.pagination :as pagination]))

;; =============================================================================
;; Offset Pagination Tests
;; =============================================================================

(deftest ^:unit calculate-offset-pagination-test
  (testing "Basic pagination calculation"
    (let [result (pagination/calculate-offset-pagination 100 0 20)]
      (is (= "offset" (:type result)))
      (is (= 100 (:total result)))
      (is (= 0 (:offset result)))
      (is (= 20 (:limit result)))
      (is (= true (:has-next? result)))
      (is (= false (:has-prev? result)))
      (is (= 1 (:page result)))
      (is (= 5 (:pages result)))))

  (testing "First page"
    (let [result (pagination/calculate-offset-pagination 100 0 20)]
      (is (= 1 (:page result)))
      (is (= false (:has-prev? result)))
      (is (= true (:has-next? result)))))

  (testing "Middle page"
    (let [result (pagination/calculate-offset-pagination 100 40 20)]
      (is (= 3 (:page result)))
      (is (= true (:has-prev? result)))
      (is (= true (:has-next? result)))))

  (testing "Last page"
    (let [result (pagination/calculate-offset-pagination 100 80 20)]
      (is (= 5 (:page result)))
      (is (= true (:has-prev? result)))
      (is (= false (:has-next? result)))))

  (testing "Empty collection"
    (let [result (pagination/calculate-offset-pagination 0 0 20)]
      (is (= 0 (:total result)))
      (is (= 0 (:pages result)))
      (is (= false (:has-next? result)))
      (is (= false (:has-prev? result)))))

  (testing "Single item"
    (let [result (pagination/calculate-offset-pagination 1 0 20)]
      (is (= 1 (:pages result)))
      (is (= false (:has-next? result)))))

  (testing "Exactly one page"
    (let [result (pagination/calculate-offset-pagination 20 0 20)]
      (is (= 1 (:pages result)))
      (is (= false (:has-next? result)))))

  (testing "Partial last page"
    (let [result (pagination/calculate-offset-pagination 95 80 20)]
      (is (= 5 (:page result)))
      (is (= 5 (:pages result)))
      (is (= false (:has-next? result)))))

  (testing "Small limit"
    (let [result (pagination/calculate-offset-pagination 100 0 5)]
      (is (= 20 (:pages result)))
      (is (= true (:has-next? result)))))

  (testing "Large limit"
    (let [result (pagination/calculate-offset-pagination 100 0 1000)]
      (is (= 1 (:pages result)))
      (is (= false (:has-next? result))))))

(deftest ^:unit calculate-next-offset-test
  (testing "Next offset available"
    (is (= 20 (pagination/calculate-next-offset 0 20 100)))
    (is (= 40 (pagination/calculate-next-offset 20 20 100))))

  (testing "Next offset at end"
    (is (nil? (pagination/calculate-next-offset 80 20 100))))

  (testing "Next offset beyond end"
    (is (nil? (pagination/calculate-next-offset 90 20 100))))

  (testing "Edge cases"
    (is (= 1 (pagination/calculate-next-offset 0 1 100)))
    (is (nil? (pagination/calculate-next-offset 0 20 10)))))

(deftest ^:unit calculate-prev-offset-test
  (testing "Previous offset available"
    (is (= 0 (pagination/calculate-prev-offset 20 20)))
    (is (= 20 (pagination/calculate-prev-offset 40 20))))

  (testing "Previous offset at beginning"
    (is (nil? (pagination/calculate-prev-offset 0 20))))

  (testing "Previous offset less than limit"
    (is (= 0 (pagination/calculate-prev-offset 10 20))))

  (testing "Edge cases"
    (is (= 0 (pagination/calculate-prev-offset 1 1)))
    (is (= 0 (pagination/calculate-prev-offset 5 20)))))

;; =============================================================================
;; Cursor Pagination Tests
;; =============================================================================

(deftest ^:unit calculate-cursor-pagination-test
  (testing "With next cursor"
    (let [result (pagination/calculate-cursor-pagination [] 20 "next-cursor" nil)]
      (is (= "cursor" (:type result)))
      (is (= 20 (:limit result)))
      (is (= "next-cursor" (:next-cursor result)))
      (is (nil? (:prev-cursor result)))
      (is (= true (:has-next? result)))
      (is (= false (:has-prev? result)))))

  (testing "With previous cursor"
    (let [result (pagination/calculate-cursor-pagination [] 20 nil "prev-cursor")]
      (is (nil? (:next-cursor result)))
      (is (= "prev-cursor" (:prev-cursor result)))
      (is (= false (:has-next? result)))
      (is (= true (:has-prev? result)))))

  (testing "With both cursors"
    (let [result (pagination/calculate-cursor-pagination [] 20 "next" "prev")]
      (is (= true (:has-next? result)))
      (is (= true (:has-prev? result)))))

  (testing "Without cursors"
    (let [result (pagination/calculate-cursor-pagination [] 20 nil nil)]
      (is (= false (:has-next? result)))
      (is (= false (:has-prev? result))))))

(deftest ^:unit extract-cursor-value-test
  (testing "Extract ID and sort value"
    (let [item {:id 123 :created-at "2024-01-01" :name "Test"}
          result (pagination/extract-cursor-value item :created-at)]
      (is (= 123 (:id result)))
      (is (= "2024-01-01" (:sort-value result)))))

  (testing "Extract with different sort key"
    (let [item {:id 456 :name "Test" :score 99}
          result (pagination/extract-cursor-value item :score)]
      (is (= 456 (:id result)))
      (is (= 99 (:sort-value result)))))

  (testing "Missing sort key"
    (let [item {:id 789}
          result (pagination/extract-cursor-value item :created-at)]
      (is (= 789 (:id result)))
      (is (nil? (:sort-value result))))))

;; =============================================================================
;; Parameter Validation Tests
;; =============================================================================

(deftest ^:unit validate-pagination-params-test
  (testing "Valid parameters with defaults"
    (let [result (pagination/validate-pagination-params
                  {}
                  {:default-limit 20 :max-limit 100})]
      (is (= true (:valid? result)))
      (is (empty? (:errors result)))
      (is (= 20 (get-in result [:params :limit])))
      (is (= 0 (get-in result [:params :offset])))))

  (testing "Valid parameters with custom values"
    (let [result (pagination/validate-pagination-params
                  {:limit 50 :offset 100}
                  {:default-limit 20 :max-limit 100})]
      (is (= true (:valid? result)))
      (is (= 50 (get-in result [:params :limit])))
      (is (= 100 (get-in result [:params :offset])))))

  (testing "Invalid limit - too large"
    (let [result (pagination/validate-pagination-params
                  {:limit 200}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :limit))))

  (testing "Invalid limit - negative"
    (let [result (pagination/validate-pagination-params
                  {:limit -5}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :limit))))

  (testing "Invalid limit - zero"
    (let [result (pagination/validate-pagination-params
                  {:limit 0}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :limit))))

  (testing "Invalid offset - negative"
    (let [result (pagination/validate-pagination-params
                  {:offset -10}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :offset))))

  (testing "Invalid - cursor with offset"
    (let [result (pagination/validate-pagination-params
                  {:cursor "abc123" :offset 20}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :cursor))))

  (testing "Valid - cursor with zero offset"
    (let [result (pagination/validate-pagination-params
                  {:cursor "abc123" :offset 0}
                  {:max-limit 100})]
      (is (= true (:valid? result)))))

  (testing "Invalid limit - not an integer"
    (let [result (pagination/validate-pagination-params
                  {:limit "not-a-number"}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :limit))))

  (testing "Invalid offset - not an integer"
    (let [result (pagination/validate-pagination-params
                  {:offset "not-a-number"}
                  {:max-limit 100})]
      (is (= false (:valid? result)))
      (is (contains? (:errors result) :offset)))))

;; =============================================================================
;; Parameter Parsing Tests
;; =============================================================================

(deftest ^:unit parse-limit-test
  (testing "Parse integer"
    (is (= 50 (pagination/parse-limit 50 20))))

  (testing "Parse string"
    (is (= 50 (pagination/parse-limit "50" 20))))

  (testing "Parse nil - use default"
    (is (= 20 (pagination/parse-limit nil 20))))

  (testing "Parse invalid string - use default"
    (is (= 20 (pagination/parse-limit "not-a-number" 20))))

  (testing "Parse other type - use default"
    (is (= 20 (pagination/parse-limit {:not :valid} 20)))))

(deftest ^:unit parse-offset-test
  (testing "Parse integer"
    (is (= 100 (pagination/parse-offset 100))))

  (testing "Parse string"
    (is (= 100 (pagination/parse-offset "100"))))

  (testing "Parse nil - use 0"
    (is (= 0 (pagination/parse-offset nil))))

  (testing "Parse invalid string - use 0"
    (is (= 0 (pagination/parse-offset "not-a-number"))))

  (testing "Parse other type - use 0"
    (is (= 0 (pagination/parse-offset {:not :valid})))))

;; =============================================================================
;; Sort Parameter Tests
;; =============================================================================

(deftest ^:unit parse-sort-test
  (testing "Parse ascending sort"
    (let [result (pagination/parse-sort "created_at")]
      (is (= :created-at (:field result)))
      (is (= :asc (:direction result)))))

  (testing "Parse descending sort"
    (let [result (pagination/parse-sort "-created_at")]
      (is (= :created-at (:field result)))
      (is (= :desc (:direction result)))))

  (testing "Parse nil - use default"
    (let [result (pagination/parse-sort nil)]
      (is (= :created-at (:field result)))
      (is (= :desc (:direction result)))))

  (testing "Parse different field"
    (let [result (pagination/parse-sort "name")]
      (is (= :name (:field result)))
      (is (= :asc (:direction result)))))

  (testing "Parse with underscores"
    (let [result (pagination/parse-sort "updated_at")]
      (is (= :updated-at (:field result)))))

  (testing "Parse descending with underscores"
    (let [result (pagination/parse-sort "-updated_at")]
      (is (= :updated-at (:field result)))
      (is (= :desc (:direction result))))))

(deftest ^:unit validate-sort-field-test
  (testing "Valid sort field"
    (let [result (pagination/validate-sort-field :created-at #{:created-at :name :email})]
      (is (= true (:valid? result)))
      (is (nil? (:error result)))))

  (testing "Invalid sort field"
    (let [result (pagination/validate-sort-field :invalid #{:created-at :name :email})]
      (is (= false (:valid? result)))
      (is (string? (:error result)))
      (is (re-find #"Invalid sort field" (:error result)))))

  (testing "Empty allowed fields"
    (let [result (pagination/validate-sort-field :any #{})]
      (is (= false (:valid? result))))))

;; =============================================================================
;; Response Construction Tests
;; =============================================================================

(deftest ^:unit create-paginated-response-test
  (testing "Create paginated response with meta"
    (let [items [{:id 1} {:id 2}]
          pagination-meta {:type "offset" :total 10 :offset 0 :limit 2}
          meta {:version :v1}
          result (pagination/create-paginated-response items pagination-meta meta)]
      (is (= items (:data result)))
      (is (= pagination-meta (:pagination result)))
      (is (map? (:meta result)))
      (is (= :v1 (get-in result [:meta :version])))))

  (testing "Empty items"
    (let [result (pagination/create-paginated-response [] {:type "offset"} {})]
      (is (empty? (:data result)))
      (is (map? (:pagination result)))))

  (testing "Without meta"
    (let [result (pagination/create-paginated-response [] {:type "offset"} nil)]
      (is (empty? (:data result)))
      (is (nil? (:meta result)))))

  (testing "Custom meta"
    (let [result (pagination/create-paginated-response
                  []
                  {:type "offset"}
                  {:custom "value"})]
      (is (= "value" (get-in result [:meta :custom]))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Very large numbers"
    (let [result (pagination/calculate-offset-pagination 1000000 0 100)]
      (is (= 10000 (:pages result)))
      (is (= true (:has-next? result)))))

  (testing "Limit equals total"
    (let [result (pagination/calculate-offset-pagination 50 0 50)]
      (is (= 1 (:pages result)))
      (is (= false (:has-next? result)))))

  (testing "Offset beyond total"
    (let [result (pagination/calculate-offset-pagination 50 100 20)]
      (is (= false (:has-next? result)))))

  (testing "Minimum values"
    (let [result (pagination/calculate-offset-pagination 1 0 1)]
      (is (= 1 (:pages result)))
      (is (= 1 (:page result)))))

  (testing "Parse with whitespace"
    (is (= 50 (pagination/parse-limit "  50  " 20)))
    (is (= 0 (pagination/parse-offset "  not-a-number  ")))))
