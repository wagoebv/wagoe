(ns wagoe.tools.i18n-test
  "Tests for the i18n scan gate.

   The gate shipped as a required CI job with a filter that no line holding a
   string literal could satisfy — `(not (str/includes? line \"\\\"\"))` — so it
   reported OK on every commit since it was added. These tests exist so a
   scanner that cannot fail cannot pass."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.i18n :as i18n]
            [wagoe.tools.parsing :as parsing]))

;; =============================================================================
;; The regression that motivated all of this
;; =============================================================================

(deftest ^:unit scan-reports-an-obvious-violation
  ;; If this ever passes vacuously again, every other test here is decoration.
  (testing "a capitalised literal in Hiccup is reported"
    (let [src "(ns demo.core.ui)\n\n(defn page []\n  [:div \"Please Sign In\"])\n"
          hits (i18n/scan-violations src)]
      (is (= 1 (count hits)))
      (is (= {:line 4 :text "Please Sign In"} (first hits)))))

  (testing "the line number points at the literal, not the form"
    (let [src "(ns demo.core.ui)\n\n(defn page\n  []\n  [:div\n   [:span \"Welcome Back\"]])\n"]
      (is (= [6] (map :line (i18n/scan-violations src)))))))

;; =============================================================================
;; What must not be reported
;; =============================================================================

(deftest ^:unit scan-ignores-what-is-not-prose
  (testing "a function docstring is not a violation"
    (is (empty? (i18n/scan-violations
                 "(defn page\n  \"Render the user page.\"\n  []\n  [:div])"))))

  (testing "a docstring before the argument vector is not a violation"
    (is (empty? (i18n/scan-violations
                 "(defn page \"Render the user page.\" [] [:div])"))))

  (testing "a multi-line docstring is not a violation on any of its lines"
    ;; A per-line scanner sees the continuation lines as bare strings.
    (is (empty? (i18n/scan-violations
                 "(defn page\n  \"First line of prose.\n   Second line of prose.\"\n  []\n  [:div])"))))

  (testing "a namespace docstring is not a violation"
    (is (empty? (i18n/scan-violations
                 "(ns demo.core.ui\n  \"User interface components.\"\n  (:require [x]))"))))

  (testing "a regex literal is not prose"
    (is (empty? (i18n/scan-violations
                 "(defn m [ua] (re-find #\"Mobile Or Tablet\" ua))"))))

  (testing "a date format pattern is not prose"
    (is (empty? (i18n/scan-violations
                 "(defn f [] (java.time.format.DateTimeFormatter/ofPattern \"MMM d, yyyy\"))"))))

  (testing "a comment is not code"
    (is (empty? (i18n/scan-violations
                 "(defn page []\n  ;; TODO Externalise This Later\n  [:div])"))))

  (testing "an interpolation argument is data, not a violation"
    (is (empty? (i18n/scan-violations
                 "(defn page []\n  [:div [:t :user/hello {:name \"Alice Smith\"}]])"))))

  (testing "a parameter nested inside another marker is still data"
    (is (empty? (i18n/scan-violations
                 "(defn page [] [:t :a {:b [:t :c {:d \"Deep Value Here\"}]}])"))))

  (testing "lowercase fragments are out of scope, deliberately"
    ;; `(str \" hour\" \" ago\")` is unexternalised too. Matching lowercase
    ;; turns every option name and map key into a finding, so the heuristic
    ;; stops at capitalised prose and the skill says so.
    (is (empty? (i18n/scan-violations "(defn f [n] (str n \" hours ago\"))"))))

  (testing "short and non-alphabetic strings are not prose"
    (is (empty? (i18n/scan-violations "(defn page [] [:div {:class \"Foo\"} \"-\"])")))))

(deftest ^:unit scan-checks-each-literal-not-the-line
  ;; A line-level "[:t " test suppressed every other literal sharing the line
  ;; with a marker. Five real violations were hidden by it in the same files
  ;; this gate had just been used to clean — all of the shape below.
  (testing "a sibling literal beside a marker is reported"
    (is (= [{:line 1 :text "Updating Now"}]
           (i18n/scan-violations
            "(ui/submit-button [:t :user/button-update] {:loading-text \"Updating Now\"})"))))

  (testing "a literal after a closed marker on the same line is reported"
    (is (= [{:line 1 :text "Trailing Text Here"}]
           (i18n/scan-violations "(defn f [] [:div [:t :user/a] \"Trailing Text Here\"])"))))

  (testing "a marker earlier in the form does not shield a later literal"
    (is (= [{:line 4 :text "Another Literal"}]
           (i18n/scan-violations
            "(defn f []\n  [:div\n   [:t :user/a]\n   [:span \"Another Literal\"]])"))))

  (testing "a keyword merely starting with :t is not a marker"
    ;; `[:title "Users"]` must not read as `[:t ...]`.
    (is (= [{:line 1 :text "Users Page"}]
           (i18n/scan-violations "(defn f [] [:title \"Users Page\"])")))))

;; =============================================================================
;; A def is a value, not a docstring
;; =============================================================================

(deftest ^:unit scan-does-not-mistake-a-def-value-for-a-docstring
  ;; `(def x "doc" v)` is legal, so treating everything after `(def name` as a
  ;; docstring would hide `(def title "Users Page")` — the exact shape the
  ;; scan exists to find.
  (testing "a string bound by def is reported"
    (is (= [{:line 1 :text "Users Page"}]
           (i18n/scan-violations "(def title \"Users Page\")")))))

;; =============================================================================
;; The extractor the scan is built on
;; =============================================================================

(deftest ^:unit string-literals-carry-position-and-context
  (let [src  "(ns d.core.ui\n  \"Doc.\")\n\n(defn f [x]\n  (re-find #\"Abc\" x)\n  [:div \"Hello There\"])\n"
        lits (parsing/string-literals src)]

    (testing "every literal is found, in order"
      (is (= ["Doc." "Abc" "Hello There"] (map :text lits))))

    (testing "line numbers survive multi-line forms"
      (is (= [2 5 6] (map :line lits))))

    (testing "a regex literal is marked as one"
      (is (= [false true false] (map :regex? lits))))

    (testing "docstring position is derived from the enclosing form"
      (is (= [true false false] (map parsing/docstring? lits)))))

  (testing "a character literal quote does not open a string"
    ;; `\\\"` is one token; reading it as a quote shifts every literal after it.
    (is (= ["ok"] (map :text (parsing/string-literals "(def c \\\") (def s \"ok\")")))))

  (testing "an escaped quote does not close a string"
    (is (= ["a\\\"b"] (map :text (parsing/string-literals "(def s \"a\\\"b\")")))))

  (testing "a quote inside a comment is not a string"
    (is (empty? (parsing/string-literals ";; a \" here\n(def x 1)")))))

(deftest ^:unit unclosed-forms-reports-the-enclosing-context
  (testing "open forms come back outermost first"
    (is (= ["[:div [:t :k {:a " "[:t :k {:a " "{:a "]
           (parsing/unclosed-forms "[:div [:t :k {:a "))))

  (testing "a closed form is not reported"
    ;; Only `[:div` is still open; each entry runs to the end of the input, so
    ;; the closed marker appears inside it but not as an entry of its own.
    (is (= ["[:div [:t :k] "] (parsing/unclosed-forms "[:div [:t :k] "))))

  (testing "nothing open reports nothing"
    (is (empty? (parsing/unclosed-forms "(def x 1)"))))

  (testing "a stray closer does not throw"
    ;; preceding-code is a prefix of a file, so it is normally unbalanced in
    ;; one direction only — but a caller should not have to guarantee that.
    (is (empty? (parsing/unclosed-forms ")))")))))
