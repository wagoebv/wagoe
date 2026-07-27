(ns wagoe.search.core.highlighting-test
  "Unit tests for search highlighting and snippet extraction.
   
   Tests all highlighting, snippet extraction, and helper functions.
   All functions are pure so tests need no mocks or fixtures."
  {:kaocha.testable/meta {:unit true :search true}}
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.search.core.highlighting :as hl]))

;; ============================================================================
;; Highlighting
;; ============================================================================

(deftest ^:unit highlight-matches-test
  (testing "highlights single term with default markup"
    (let [text "John Doe is a software engineer"
          result (hl/highlight-matches text ["John"])]
      (is (= "<mark>John</mark> Doe is a software engineer" result))))

  (testing "highlights multiple terms"
    (let [text "John Doe is a software engineer"
          result (hl/highlight-matches text ["John" "engineer"])]
      (is (= "<mark>John</mark> Doe is a software <mark>engineer</mark>" result))))

  (testing "highlights with custom markup function"
    (let [text "John Doe"
          result (hl/highlight-matches text ["John"] (fn [term] (str "[" term "]")))]
      (is (= "[John] Doe" result))))

  (testing "case-insensitive by default"
    (let [text "john doe"
          result (hl/highlight-matches text ["JOHN"])]
      (is (= "<mark>john</mark> doe" result))))

  (testing "respects case-sensitive option"
    (let [text "john doe"
          result (hl/highlight-matches text ["JOHN"]
                                       (fn [term] (str "<mark>" term "</mark>"))
                                       {:case-sensitive? true})]
      (is (= "john doe" result))))

  (testing "matches whole words by default"
    (let [text "engineering engineer"
          result (hl/highlight-matches text ["engineer"])]
      (is (= "engineering <mark>engineer</mark>" result))))

  (testing "matches partial words when whole-words? is false"
    (let [text "engineering engineer"
          result (hl/highlight-matches text ["engineer"]
                                       (fn [term] (str "<mark>" term "</mark>"))
                                       {:whole-words? false})]
      (is (= "<mark>engineer</mark>ing <mark>engineer</mark>" result))))

  (testing "handles empty text"
    (is (= "" (hl/highlight-matches "" ["term"]))))

  (testing "handles nil text"
    (is (nil? (hl/highlight-matches nil ["term"]))))

  (testing "handles empty search terms"
    (is (= "text" (hl/highlight-matches "text" []))))

  (testing "escapes regex special characters in terms"
    (let [text "Price is $100"
          result (hl/highlight-matches text ["$100"]
                                       (fn [term] (str "<mark>" term "</mark>"))
                                       {:whole-words? false})]
      (is (= "Price is <mark>$100</mark>" result)))))

(deftest ^:unit highlight-field-test
  (testing "highlights single field"
    (let [result {:name "John Doe"}
          highlighted (hl/highlight-field result :name ["John"])]
      (is (= "John Doe" (:name highlighted)))
      (is (= "<mark>John</mark> Doe" (get-in highlighted [:_highlights :name])))))

  (testing "highlights with custom markup"
    (let [result {:name "John Doe"}
          highlighted (hl/highlight-field result :name ["John"]
                                          (fn [term] (str "[" term "]")))]
      (is (= "[John] Doe" (get-in highlighted [:_highlights :name])))))

  (testing "handles missing field"
    (let [result {:name "John"}
          highlighted (hl/highlight-field result :bio ["software"])]
      (is (= result highlighted))))

  (testing "handles nil field value"
    (let [result {:name nil}
          highlighted (hl/highlight-field result :name ["John"])]
      (is (= result highlighted))))

  (testing "preserves original field value"
    (let [result {:name "John Doe"}
          highlighted (hl/highlight-field result :name ["John"])]
      (is (= "John Doe" (:name highlighted))))))

(deftest ^:unit highlight-multiple-fields-test
  (testing "highlights multiple fields"
    (let [result {:name "John Doe" :bio "Software engineer"}
          highlighted (hl/highlight-multiple-fields result [:name :bio]
                                                    ["John" "engineer"])]
      (is (= "<mark>John</mark> Doe" (get-in highlighted [:_highlights :name])))
      (is (= "Software <mark>engineer</mark>" (get-in highlighted [:_highlights :bio])))))

  (testing "handles some missing fields"
    (let [result {:name "John Doe"}
          highlighted (hl/highlight-multiple-fields result [:name :bio] ["John"])]
      (is (= "<mark>John</mark> Doe" (get-in highlighted [:_highlights :name])))
      (is (nil? (get-in highlighted [:_highlights :bio])))))

  (testing "handles empty field list"
    (let [result {:name "John"}
          highlighted (hl/highlight-multiple-fields result [] ["John"])]
      (is (= result highlighted))))

  (testing "uses custom highlight function"
    (let [result {:name "John Doe"}
          highlighted (hl/highlight-multiple-fields result [:name] ["John"]
                                                    (fn [term] (str "[" term "]")))]
      (is (= "[John] Doe" (get-in highlighted [:_highlights :name]))))))

;; ============================================================================
;; Snippet Extraction
;; ============================================================================

(deftest ^:unit find-first-match-position-test
  (testing "finds first match position"
    (let [text "Hello John Doe"
          result (hl/find-first-match-position text ["John" "Jane"])]
      (is (= 6 (:match-pos result)))
      (is (= "john" (:matched-term result)))))

  (testing "finds earliest match when multiple terms present"
    (let [text "Hello Jane and John"
          result (hl/find-first-match-position text ["John" "Jane"])]
      (is (= 6 (:match-pos result)))
      (is (= "jane" (:matched-term result)))))

  (testing "returns nil when no match"
    (is (nil? (hl/find-first-match-position "Hello World" ["John"]))))

  (testing "case-insensitive by default"
    (let [result (hl/find-first-match-position "hello john" ["JOHN"])]
      (is (= 6 (:match-pos result)))))

  (testing "respects case-sensitive option"
    (is (nil? (hl/find-first-match-position "hello john" ["JOHN"] true))))

  (testing "handles empty search terms"
    (is (nil? (hl/find-first-match-position "text" [])))))

(deftest ^:unit extract-snippet-test
  (testing "extracts snippet around match"
    (let [text "This is a long text about John Doe who works as a software engineer"
          result (hl/extract-snippet text ["John"] 30)]
      (is (str/includes? result "John"))
      (is (<= (count result) 36))  ; 30 + ellipsis + adjustment
      (is (or (str/starts-with? result "...")
              (str/starts-with? result "This")))))

  (testing "adds ellipsis when text truncated at start"
    (let [text "The beginning of this text is long before we get to the important John part"
          result (hl/extract-snippet text ["John"] 30)]
      (is (str/starts-with? result "..."))
      (is (str/includes? result "John"))))

  (testing "adds ellipsis when text truncated at end"
    (let [text "John is mentioned early and then there is a lot more text afterwards"
          result (hl/extract-snippet text ["John"] 30)]
      (is (str/includes? result "John"))
      (is (str/ends-with? result "..."))))

  (testing "no ellipsis when entire text fits"
    (let [text "Short text with John"
          result (hl/extract-snippet text ["John"] 100)]
      (is (= text result))))

  (testing "returns beginning when no match found"
    (let [text "This is some text without the search term"
          result (hl/extract-snippet text ["missing"] 20)]
      (is (= "This is some text wi..." result))))

  (testing "handles empty text"
    (is (= "" (hl/extract-snippet "" ["term"] 100))))

  (testing "handles nil text"
    (is (= "" (hl/extract-snippet nil ["term"] 100))))

  (testing "uses default max-length"
    (let [text (apply str (repeat 300 "a"))
          result (hl/extract-snippet text ["x"])]
      (is (<= (count result) 203))))  ; 200 + ellipsis

  (testing "respects context-chars parameter"
    (let [text "Beginning John End"
          result (hl/extract-snippet text ["John"] 20 5)]
      (is (str/includes? result "John"))
      (is (<= (count result) 23)))))  ; 20 + ellipsis

(deftest ^:unit extract-snippet-with-highlight-test
  (testing "extracts and highlights in one step"
    (let [text "This is a long text about John Doe who works as an engineer"
          result (hl/extract-snippet-with-highlight text ["John" "engineer"] 50)]
      (is (str/includes? result "<mark>John</mark>"))
      (is (<= (count result) 80))))  ; Snippet + markup + ellipsis

  (testing "uses custom highlight function"
    (let [text "This is text about John"
          result (hl/extract-snippet-with-highlight text ["John"] 30
                                                    (fn [term] (str "[" term "]")))]
      (is (str/includes? result "[John]"))))

  (testing "highlights all terms in snippet"
    (let [text "John and Jane work as engineers"
          result (hl/extract-snippet-with-highlight text ["John" "Jane" "engineers"] 50)]
      (is (str/includes? result "<mark>John</mark>"))
      (is (str/includes? result "<mark>Jane</mark>")))))
      ; Note: "engineers" may be truncated depending on snippet extraction)

(deftest ^:unit extract-multiple-snippets-test
  (testing "extracts multiple snippets for multiple matches"
    (let [text (str "John works in NYC. " (apply str (repeat 200 "x"))
                    " John lives in LA.")
          result (hl/extract-multiple-snippets text ["John"] 2 30)]
      (is (= 2 (count result)))
      (is (str/includes? (first result) "John"))
      (is (str/includes? (second result) "John"))))

  (testing "limits to max-snippets"
    (let [text "John here. John there. John everywhere."
          result (hl/extract-multiple-snippets text ["John"] 2 20)]
      (is (<= (count result) 2))))

  (testing "avoids overlapping snippets"
    (let [text "John and Jane are here"
          result (hl/extract-multiple-snippets text ["John" "Jane"] 3 30)]
      ;; Should combine into single snippet since matches are close
      (is (= 1 (count result)))))

  (testing "returns single snippet for short text"
    (let [text "Short text"
          result (hl/extract-multiple-snippets text ["text"] 3 50)]
      (is (= 1 (count result)))))

  (testing "handles empty text"
    (let [result (hl/extract-multiple-snippets "" ["term"] 2 50)]
      (is (= 1 (count result)))
      (is (= "" (first result)))))

  (testing "uses default parameters"
    (let [text (str "Match1 " (apply str (repeat 300 "x")) " Match2")
          result (hl/extract-multiple-snippets text ["Match1" "Match2"])]
      (is (<= (count result) 3)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(deftest ^:unit truncate-text-test
  (testing "truncates long text"
    (is (= "Hello..." (hl/truncate-text "Hello World" 8))))

  (testing "no truncation when text fits"
    (is (= "Hello" (hl/truncate-text "Hello" 10))))

  (testing "uses custom ellipsis"
    (is (= "Hello>>>" (hl/truncate-text "Hello World" 8 ">>>"))))

  (testing "handles nil text"
    (is (nil? (hl/truncate-text nil 10))))

  (testing "handles empty text"
    (is (= "" (hl/truncate-text "" 10))))

  (testing "accounts for ellipsis length"
    (let [result (hl/truncate-text "Hello World" 10)]
      (is (= 10 (count result))))))

(deftest ^:unit strip-html-test
  (testing "strips simple HTML tags"
    (is (= "Hello world" (hl/strip-html "<p>Hello world</p>"))))

  (testing "strips nested HTML tags"
    (is (= "Hello world" (hl/strip-html "<p>Hello <strong>world</strong></p>"))))

  (testing "collapses multiple spaces"
    (is (= "Hello world" (hl/strip-html "<p>Hello   <br/>   world</p>"))))

  (testing "trims whitespace"
    (is (= "Hello" (hl/strip-html "  <p>Hello</p>  "))))

  (testing "handles text without HTML"
    (is (= "Plain text" (hl/strip-html "Plain text"))))

  (testing "handles nil text"
    (is (nil? (hl/strip-html nil))))

  (testing "handles empty text"
    (is (= "" (hl/strip-html ""))))

  (testing "removes self-closing tags"
    (is (= "BeforeAfter" (hl/strip-html "Before<br/>After")))))

(deftest ^:unit count-matches-test
  (testing "counts single term matches"
    (let [result (hl/count-matches "John and Jane met John" ["John"])]
      (is (= 2 (get result "John")))))

  (testing "counts multiple terms"
    (let [result (hl/count-matches "John and Jane met John" ["John" "Jane"])]
      (is (= 2 (get result "John")))
      (is (= 1 (get result "Jane")))))

  (testing "case-insensitive by default"
    (let [result (hl/count-matches "john and JOHN" ["John"])]
      (is (= 2 (get result "John")))))

  (testing "respects case-sensitive option"
    (let [result (hl/count-matches "john and JOHN" ["john"] true)]
      (is (= 1 (get result "john")))))

  (testing "returns 0 for non-matching terms"
    (let [result (hl/count-matches "Hello World" ["John"])]
      (is (= 0 (get result "John")))))

  (testing "handles empty text"
    (let [result (hl/count-matches "" ["term"])]
      (is (= 0 (get result "term")))))

  (testing "handles overlapping matches"
    (let [result (hl/count-matches "aaa" ["aa"])]
      (is (= 1 (get result "aa"))))))  ; re-seq doesn't find overlapping matches

(deftest ^:unit calculate-match-density-test
  (testing "calculates density for single term"
    (let [density (hl/calculate-match-density "John Doe" ["John"])]
      (is (= 0.5 density))))  ; "John" is 4 chars out of 8 total

  (testing "calculates density for multiple terms"
    (let [density (hl/calculate-match-density "John and Jane" ["John" "Jane"])]
      (is (< 0.6 density))
      (is (> 0.7 density))))

  (testing "returns 0 for no matches"
    (is (= 0.0 (hl/calculate-match-density "Hello World" ["xyz"]))))

  (testing "returns 0 for empty text"
    (is (= 0.0 (hl/calculate-match-density "" ["term"]))))

  (testing "returns 0 for empty search terms"
    (is (= 0.0 (hl/calculate-match-density "text" []))))

  (testing "counts repeated matches"
    (let [density (hl/calculate-match-density "aa" ["a"])]
      (is (= 1.0 density))))  ; "a" appears twice, 2 chars out of 2 total

  (testing "density is between 0 and 1"
    (let [density (hl/calculate-match-density "John Doe is a software engineer"
                                              ["John" "software"])]
      (is (>= density 0.0))
      (is (<= density 1.0)))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:unit highlighting-pipeline-test
  (testing "complete highlighting and snippet workflow"
    (let [text "John Doe is a software engineer who works on distributed systems"
          search-terms ["John" "engineer" "systems"]

          ;; Extract snippet
          snippet (hl/extract-snippet text search-terms 40)

          ;; Highlight matches in snippet
          highlighted (hl/highlight-matches snippet search-terms)

          ;; Count matches
          match-count (hl/count-matches text search-terms)

          ;; Calculate density
          density (hl/calculate-match-density text search-terms)]

      ;; Verify snippet contains matches
      (is (str/includes? snippet "John"))

      ;; Verify highlighting worked
      (is (str/includes? highlighted "<mark>"))

      ;; Verify counts
      (is (= 1 (get match-count "John")))
      (is (= 1 (get match-count "engineer")))
      (is (= 1 (get match-count "systems")))

      ;; Verify density makes sense
      (is (> density 0.0))
      (is (< density 1.0))))

  (testing "highlight multiple fields with snippets"
    (let [result {:name "John Doe"
                  :bio "Software engineer with 10 years of experience"}
          search-terms ["John" "engineer"]

          ;; Highlight all fields
          highlighted (hl/highlight-multiple-fields result [:name :bio] search-terms)

          ;; Extract snippet from bio
          bio-snippet (hl/extract-snippet (:bio result) search-terms 30)
          highlighted-snippet (hl/highlight-matches bio-snippet search-terms)]

      ;; Verify field highlighting
      (is (= "<mark>John</mark> Doe" (get-in highlighted [:_highlights :name])))
      (is (str/includes? (get-in highlighted [:_highlights :bio]) "<mark>engineer</mark>"))

       ;; Verify snippet extraction and highlighting
      (is (str/includes? highlighted-snippet "<mark>engineer</mark>"))
      (is (<= (count bio-snippet) 36)))))  ; 30 + ellipsis + adjustment
