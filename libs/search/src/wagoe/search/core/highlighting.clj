(ns wagoe.search.core.highlighting
  "Pure functions for search result highlighting and snippet extraction.
   
   This namespace provides functions for highlighting matching terms
   in search results and extracting relevant snippets.
   All functions are pure (no side effects).
   
   Architecture: Functional Core (Pure)"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Highlighting
;; ============================================================================

(defn highlight-matches
  "Highlight matching terms in text.
   
   Wraps matching terms with highlight function (default: <mark> tags).
   Case-insensitive matching by default.
   
   Args:
     text - Original text to highlight
     search-terms - List of terms to highlight
     highlight-fn - Function to wrap matches (optional)
                    Default: (fn [term] (str \"<mark>\" term \"</mark>\"))
     options - Map of options:
               :case-sensitive? - Match case exactly (default: false)
               :whole-words? - Only match whole words (default: true)
     
   Returns:
     Text with highlighted terms
     
   Example:
     (highlight-matches \"John Doe is a software engineer\"
                        [\"John\" \"engineer\"])
     ;=> \"<mark>John</mark> Doe is a software <mark>engineer</mark>\"
     
     (highlight-matches \"john doe\" [\"john\"]
                        (fn [term] (str \"[\" term \"]\")))
     ;=> \"[john] doe\"
     
   Pure: true"
  ([text search-terms]
   (highlight-matches text search-terms
                      (fn [term] (str "<mark>" term "</mark>"))
                      {}))
  ([text search-terms highlight-fn]
   (highlight-matches text search-terms highlight-fn {}))
  ([text search-terms highlight-fn options]
   (if (or (str/blank? text) (empty? search-terms))
     text
     (let [case-sensitive? (get options :case-sensitive? false)
           whole-words? (get options :whole-words? true)
           escaped-terms (map #(java.util.regex.Pattern/quote %) search-terms)
           word-boundary (if whole-words? "\\b" "")
           pattern-str (str word-boundary
                            "(" (str/join "|" escaped-terms) ")"
                            word-boundary)
           flags (if case-sensitive?
                   0
                   java.util.regex.Pattern/CASE_INSENSITIVE)
           pattern (java.util.regex.Pattern/compile pattern-str flags)]
       (str/replace text pattern #(highlight-fn (first %)))))))

(defn highlight-field
  "Highlight matches in a specific field of a result.
   
   Args:
     result - Result map
     field - Field to highlight
     search-terms - Terms to highlight
     highlight-fn - Highlight function (optional)
     
   Returns:
     Result map with highlighted field in :_highlights
     
   Example:
     (highlight-field {:name \"John Doe\"} :name [\"John\"])
     ;=> {:name \"John Doe\"
     ;    :_highlights {:name \"<mark>John</mark> Doe\"}}
     
   Pure: true"
  ([result field search-terms]
   (highlight-field result field search-terms
                    (fn [term] (str "<mark>" term "</mark>"))))
  ([result field search-terms highlight-fn]
   (let [text (get result field)
         highlighted (when text
                       (highlight-matches text search-terms highlight-fn))]
     (if highlighted
       (assoc-in result [:_highlights field] highlighted)
       result))))

(defn highlight-multiple-fields
  "Highlight matches in multiple fields.
   
   Args:
     result - Result map
     fields - List of fields to highlight
     search-terms - Terms to highlight
     highlight-fn - Highlight function (optional)
     
   Returns:
     Result map with highlighted fields in :_highlights
     
   Example:
     (highlight-multiple-fields {:name \"John Doe\" :bio \"Software engineer\"}
                               [:name :bio]
                               [\"John\" \"engineer\"])
     ;=> {:name \"John Doe\"
     ;    :bio \"Software engineer\"
     ;    :_highlights {:name \"<mark>John</mark> Doe\"
     ;                  :bio \"Software <mark>engineer</mark>\"}}
     
   Pure: true"
  ([result fields search-terms]
   (highlight-multiple-fields result fields search-terms
                              (fn [term] (str "<mark>" term "</mark>"))))
  ([result fields search-terms highlight-fn]
   (reduce (fn [r field]
             (highlight-field r field search-terms highlight-fn))
           result
           fields)))

;; ============================================================================
;; Snippet Extraction
;; ============================================================================

(defn find-first-match-position
  "Find position of first search term match in text.
   
   Args:
     text - Text to search
     search-terms - Terms to find
     case-sensitive? - Match case exactly (default: false)
     
   Returns:
     {:match-pos int :matched-term string} or nil if no match
     
   Example:
     (find-first-match-position \"Hello John Doe\" [\"John\" \"Jane\"])
     ;=> {:match-pos 6 :matched-term \"John\"}
     
   Pure: true"
  ([text search-terms]
   (find-first-match-position text search-terms false))
  ([text search-terms case-sensitive?]
   (let [text-to-search (if case-sensitive? text (str/lower-case text))
         terms-to-search (if case-sensitive?
                           search-terms
                           (map str/lower-case search-terms))]
     (first
      (sort-by :match-pos
               (keep (fn [term]
                       (let [pos (.indexOf text-to-search term)]
                         (when (>= pos 0)
                           {:match-pos pos :matched-term term})))
                     terms-to-search))))))

(defn extract-snippet
  "Extract relevant snippet from text around search terms.
   
   Extracts text centered around the first matching search term.
   Adds ellipsis (...) when text is truncated.
   
   Args:
     text - Full text
     search-terms - Terms to find
     max-length - Maximum snippet length (default: 200)
     context-chars - Characters of context around match (default: max-length/2)
     
   Returns:
     Snippet string with context around first match
     
   Example:
     (extract-snippet \"This is a long text about John Doe who works...\"
                      [\"John\"]
                      50)
     ;=> \"...a long text about John Doe who works...\"
     
   Pure: true"
  ([text search-terms]
   (extract-snippet text search-terms 200))
  ([text search-terms max-length]
   (extract-snippet text search-terms max-length (quot max-length 2)))
  ([text search-terms max-length context-chars]
   (if (or (str/blank? text) (empty? search-terms))
     (if text
       (subs text 0 (min max-length (count text)))
       "")
     (let [match-info (find-first-match-position text search-terms)]
       (if match-info
         (let [match-pos (:match-pos match-info)
               match-term (:matched-term match-info)
               match-end (+ match-pos (count match-term))

               ;; Calculate snippet bounds
               start (max 0 (- match-pos context-chars))
               end (min (count text) (+ match-end context-chars))

               ;; Adjust for max-length
               snippet-length (- end start)
               excess (- snippet-length max-length)
               adjusted-start (if (> excess 0)
                                (+ start (quot excess 2))
                                start)
               adjusted-end (if (> excess 0)
                              (- end (quot excess 2))
                              end)

                ;; Extract snippet
               snippet (subs text adjusted-start adjusted-end)

                ;; Add ellipsis
               prefix (when (> adjusted-start 0) "...")
               suffix (when (< adjusted-end (count text)) "...")]
           (str prefix snippet suffix))
          ;; No match found - return beginning of text
         (let [snippet (subs text 0 (min max-length (count text)))
               suffix (when (< max-length (count text)) "...")]
           (str snippet suffix)))))))

(defn extract-snippet-with-highlight
  "Extract snippet and highlight matches in one step.
   
   Args:
     text - Full text
     search-terms - Terms to find and highlight
     max-length - Maximum snippet length
     highlight-fn - Highlight function (optional)
     
   Returns:
     Highlighted snippet
     
   Example:
     (extract-snippet-with-highlight
       \"This is a long text about John Doe who works as an engineer\"
       [\"John\" \"engineer\"]
       50)
     ;=> \"...long text about <mark>John</mark> Doe who works as an <mark>engineer</mark>\"
     
   Pure: true"
  ([text search-terms max-length]
   (extract-snippet-with-highlight text search-terms max-length
                                   (fn [term] (str "<mark>" term "</mark>"))))
  ([text search-terms max-length highlight-fn]
   (let [snippet (extract-snippet text search-terms max-length)]
     (highlight-matches snippet search-terms highlight-fn))))

;; ============================================================================
;; Multi-Snippet Extraction
;; ============================================================================

(defn extract-multiple-snippets
  "Extract multiple snippets from text (one per unique match).
   
   Useful when search terms appear in different parts of a long document.
   
   Args:
     text - Full text
     search-terms - Terms to find
     max-snippets - Maximum number of snippets (default: 3)
     snippet-length - Length per snippet (default: 150)
     
   Returns:
     Vector of snippets
     
   Example:
     (extract-multiple-snippets
       \"John works in NY...lots of text...John lives in LA\"
       [\"John\"]
       2
       30)
     ;=> [\"John works in NY...\" \"...John lives in LA\"]
     
   Pure: true"
  ([text search-terms]
   (extract-multiple-snippets text search-terms 3 150))
  ([text search-terms max-snippets snippet-length]
   (if (or (str/blank? text) (empty? search-terms))
     [(subs text 0 (min snippet-length (count text)))]
     (let [;; Find all match positions
           text-lower (str/lower-case text)
           terms-lower (map str/lower-case search-terms)
           all-positions (mapcat (fn [term]
                                   (loop [pos 0 positions []]
                                     (let [match-pos (.indexOf text-lower term pos)]
                                       (if (>= match-pos 0)
                                         (recur (+ match-pos (count term))
                                                (conj positions match-pos))
                                         positions))))
                                 terms-lower)
           ;; Sort and deduplicate positions
           sorted-positions (sort (distinct all-positions))
           ;; Group positions that would result in overlapping snippets
           context-chars (quot snippet-length 2)
           grouped-positions (reduce (fn [groups pos]
                                       (if (empty? groups)
                                         [[pos]]
                                         (let [last-group (last groups)
                                               last-pos (last last-group)]
                                           (if (< (- pos last-pos) snippet-length)
                                            ;; Overlapping - add to last group
                                             (conj (vec (butlast groups))
                                                   (conj last-group pos))
                                            ;; Not overlapping - new group
                                             (conj groups [pos])))))
                                     []
                                     sorted-positions)
           ;; Take first position from each group, up to max-snippets
           snippet-positions (take max-snippets (map first grouped-positions))]
       (mapv (fn [pos]
               (let [start (max 0 (- pos context-chars))
                     end (min (count text) (+ pos context-chars))
                     snippet (subs text start end)
                     prefix (when (> start 0) "...")
                     suffix (when (< end (count text)) "...")]
                 (str prefix snippet suffix)))
             snippet-positions)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn truncate-text
  "Truncate text to maximum length with ellipsis.
   
   Args:
     text - Text to truncate
     max-length - Maximum length
     ellipsis - Ellipsis string (default: \"...\")
     
   Returns:
     Truncated text
     
   Example:
     (truncate-text \"This is a long text\" 10)
     ;=> \"This is...\"
     
   Pure: true"
  ([text max-length]
   (truncate-text text max-length "..."))
  ([text max-length ellipsis]
   (if (or (nil? text) (<= (count text) max-length))
     text
     (str (subs text 0 (- max-length (count ellipsis))) ellipsis))))

(defn strip-html
  "Strip HTML tags from text.
   
   Useful for cleaning text before highlighting or snippet extraction.
   
   Args:
     text - Text with HTML tags
     
   Returns:
     Text without HTML tags
     
   Example:
     (strip-html \"<p>Hello <strong>world</strong></p>\")
     ;=> \"Hello world\"
     
   Pure: true"
  [text]
  (when text
    (-> text
        (str/replace #"<[^>]+>" "")
        (str/replace #"\s+" " ")
        str/trim)))

(defn count-matches
  "Count how many times search terms appear in text.
   
   Args:
     text - Text to search
     search-terms - Terms to count
     case-sensitive? - Match case exactly (default: false)
     
   Returns:
     Map of term->count
     
   Example:
     (count-matches \"John and Jane met John\" [\"John\" \"Jane\"])
     ;=> {\"John\" 2 \"Jane\" 1}
     
   Pure: true"
  ([text search-terms]
   (count-matches text search-terms false))
  ([text search-terms case-sensitive?]
   (let [text-to-search (if case-sensitive? text (str/lower-case text))]
     (into {}
           (map (fn [term]
                  (let [term-to-search (if case-sensitive?
                                         term
                                         (str/lower-case term))
                        matches (re-seq (re-pattern (java.util.regex.Pattern/quote term-to-search))
                                        text-to-search)]
                    [term (count matches)]))
                search-terms)))))

(defn calculate-match-density
  "Calculate density of search term matches in text.
   
   Density = (total characters in matches) / (total text length)
   
   Args:
     text - Text to analyze
     search-terms - Terms to find
     
   Returns:
     Float between 0-1 (higher = more dense with matches)
     
   Example:
     (calculate-match-density \"John Doe\" [\"John\"])
     ;=> 0.5  ; \"John\" is 4 chars out of 8 total
     
   Pure: true"
  [text search-terms]
  (if (or (str/blank? text) (empty? search-terms))
    0.0
    (let [text-length (count text)
          match-counts (count-matches text search-terms)
          total-match-chars (reduce-kv (fn [sum term match-count]
                                         (+ sum (* match-count (count term))))
                                       0
                                       match-counts)]
      (/ (double total-match-chars) text-length))))
