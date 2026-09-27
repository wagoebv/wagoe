(ns wagoe.tools.check-tests-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [wagoe.tools.check-tests :as ct]))

(defn- scan-meta [src]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "check-tests-meta-" (System/currentTimeMillis) "-" (hash src) ".clj"))]
    (try
      (spit f src)
      (#'ct/scan-file-meta f)
      (finally (.delete f)))))

(deftest ^:unit flags-metadata-after-deftest-name
  (testing "metadata on the line after the test name is flagged"
    (let [ms (scan-meta "(ns x)\n(deftest my-test\n  ^:unit\n  (is true))\n")]
      (is (= 1 (count ms)))
      (is (= "my-test" (:name (first ms))))))
  (testing "metadata after the name on the same line is also flagged"
    (is (= 1 (count (scan-meta "(ns x)\n(deftest my-test ^:integration (is true))\n"))))))

(deftest ^:unit ignores-correctly-placed-metadata
  (testing "metadata before the name is correct — not flagged"
    (is (empty? (scan-meta "(ns x)\n(deftest ^:unit my-test\n  (is true))\n"))))
  (testing "stacked metadata before the name is correct"
    (is (empty? (scan-meta "(ns x)\n(deftest ^:unit ^:security my-test\n  (is true))\n"))))
  (testing "deftest with no metadata is not flagged"
    (is (empty? (scan-meta "(ns x)\n(deftest my-test\n  (is true))\n")))))

(deftest ^:unit ignores-commented-and-string-occurrences
  (testing "a commented-out misplaced deftest is ignored (stripped source)"
    (is (empty? (scan-meta "(ns x)\n;; (deftest old-test\n;;   ^:unit\n;;   (is true))\n"))))
  (testing "^: inside a string is not mistaken for metadata"
    (is (empty? (scan-meta "(ns x)\n(deftest my-test\n  (is (= \"^:unit\" (str x))))\n")))))

;; ---------------------------------------------------------------------------
;; Pyramid tag gate (BOU-166)
;; ---------------------------------------------------------------------------

(defn- scan-tags [src]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "check-tags-" (System/currentTimeMillis) "-" (hash src) ".clj"))]
    (try (spit f src) (#'ct/scan-file-tags f)
         (finally (.delete f)))))

(deftest ^:unit flags-deftest-without-pyramid-tag
  (testing "a deftest with no pyramid tag is flagged"
    (let [vs (scan-tags "(ns x)\n(deftest foo (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 0 (:count (first vs))))
      (is (= "foo" (:name (first vs)))))))

(deftest ^:unit accepts-exactly-one-pyramid-tag
  (testing "each pyramid tag alone passes"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit a (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:integration b (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:contract c (is true))\n"))))
  (testing "a cross-cutting tag alongside one pyramid tag is fine, any order"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit ^:security a (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(deftest ^:security ^:unit a (is true))\n")))))

(deftest ^:unit flags-multiple-pyramid-tags
  (testing "two pyramid tags on one deftest is flagged"
    (let [vs (scan-tags "(ns x)\n(deftest ^:unit ^:integration a (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 2 (:count (first vs)))))))

(deftest ^:unit tag-scan-ignores-comments-and-strings
  (testing "a commented-out or string-embedded deftest is not scanned"
    (is (empty? (scan-tags "(ns x)\n;; (deftest foo (is true))\n(deftest ^:unit real (is true))\n")))
    (is (empty? (scan-tags "(ns x)\n(def s \"(deftest foo ...)\")\n(deftest ^:unit real (is true))\n")))))

(deftest ^:unit tag-scan-handles-map-form-metadata
  (testing "a deftest with a keyword tag + a trailing kaocha map is validated (1 pyramid), not skipped"
    (is (empty? (scan-tags "(ns x)\n(deftest ^:unit ^{:kaocha.testable/meta {:unit true}} a (is true))\n"))))
  (testing "a deftest with ONLY map-form meta counts zero keyword pyramid tags -> flagged (use ^:keyword)"
    (let [vs (scan-tags "(ns x)\n(deftest ^{:kaocha.testable/meta {:unit true}} a (is true))\n")]
      (is (= 1 (count vs)))
      (is (= 0 (:count (first vs)))))))

;; =============================================================================
;; Form-level analysis (BOU-365) — the ticket's four planted placeholders
;; =============================================================================

(def ^:private planted
  (str "(deftest ^:unit does-nothing\n"
       "  (let [x (+ 1 1)] x))\n"
       "(deftest ^:unit tautology\n"
       "  (is (= 1 1)))\n"
       "(deftest ^:unit are-tautology\n"
       "  (are [x] (= x x) 1 2 3))\n"
       "(deftest ^:unit split\n"
       "  (is\n   true))\n"))

(deftest ^:unit all-four-planted-placeholders-are-reported
  ;; The seven regexes found one of these four. Form-level analysis finds the
  ;; other three: a deftest with no assertion at all, and identical-token
  ;; tautologies in `is` and `are`.
  (let [lines (set (map :line (ct/scan-content-structural "planted.clj" planted)))]
    (is (contains? lines 1) "no-assertion deftest")
    (is (contains? lines 4) "(is (= 1 1)) tautology — reported at the assertion")
    (is (contains? lines 6) "are-tautology — reported at the assertion")
    (is (contains? lines 8) "(is ... true) split placeholder — the match starts at (is")))

(deftest ^:unit assertion-helpers-are-recognised-by-name
  (testing "a deftest asserting through a named helper is not a placeholder"
    (is (empty? (ct/scan-content-structural
                 "snap.clj"
                 "(deftest snap (snapshot-io/check-snapshot! :k (f)))"))))
  (testing "the helper list is what makes that pass — an unknown helper fails"
    (is (seq (ct/scan-content-structural
              "snap.clj"
              "(deftest snap (snapshot-io/verify-somehow! :k (f)))")))))

(deftest ^:unit the-placeholder-escape-hatch-is-explicit-metadata
  (is (empty? (ct/scan-content-structural
               "stub.clj"
               "(deftest ^:wagoe/allow-placeholder stub (todo))"))))

(deftest ^:unit an-escaped-quote-inside-a-string-does-not-blank-the-assertion
  ;; The old three-pass stripper ate the \" inside the string as a character
  ;; literal, broke the quote pairing, and blanked real code after it — on
  ;; prometheus_test.clj that swallowed an (is ...) whole and made a real test
  ;; look like a placeholder (BOU-365).
  (is (empty? (ct/scan-content-structural
               "esc.clj"
               "(deftest esc\n  (is (str/includes? t \"path=\\\"a\\\\b\\\"\")))"))))

;; =============================================================================
;; Review findings on the first cut (BOU-365)
;; =============================================================================

(deftest ^:unit string-argument-placeholders-still-match-after-stripping
  ;; The lexer rewrite briefly kept string delimiters, which silently turned
  ;; off the whitespace-argument patterns for (is (some? "x")) and
  ;; (is (string? "x")).
  (is (= 1 (count (ct/scan-content-structural "s.clj" "(deftest x (is (some? \"lit\")))"))))
  (is (= 1 (count (ct/scan-content-structural "s.clj" "(deftest x (is (string? \"lit\")))")))))

(deftest ^:unit discarded-and-commented-drafts-are-not-placeholders
  (is (empty? (ct/scan-content-structural
               "d.clj" "(comment\n  (deftest draft (todo)))")))
  (is (empty? (ct/scan-content-structural
               "d.clj" "#_(deftest draft (todo))")))
  (testing "a live deftest next to a discarded one is still checked"
    (is (= 1 (count (ct/scan-content-structural
                     "d.clj" "#_(deftest draft (todo))\n(deftest live (setup))"))))))

(deftest ^:unit an-alias-qualified-deftest-is-still-a-deftest
  (is (= 1 (count (ct/scan-content-structural
                   "q.clj"
                   "(ns q (:require [clojure.test :as t]))\n(t/deftest empty-one (setup))"))))
  (testing "an alias that is not clojure.test defines nothing"
    (is (empty? (ct/scan-content-structural
                 "q.clj"
                 "(ns q (:require [other.lib :as t]))\n(t/deftest empty-one (setup))")))))

(deftest ^:unit quoted-equality-data-is-not-a-tautology
  (is (empty? (ct/scan-content-structural
               "g.clj" "(deftest gen (is (= '(= x x) actual)))")))
  (is (empty? (ct/scan-content-structural
               "g.clj" "(deftest gen (is (= (quote (= x x)) actual)))")))
  (testing "an executed identical-token equality is still flagged"
    (is (= 1 (count (ct/scan-content-structural
                     "g.clj" "(deftest t (is (= x x)))"))))))

(deftest ^:unit the-marker-exempts-only-from-metadata-position
  (is (seq (ct/scan-content-structural
            "m.clj" "(deftest sneaky (log :wagoe/allow-placeholder))"))
      "the keyword as body data is not an exemption")
  (is (empty? (ct/scan-content-structural
               "m.clj" "(deftest ^:wagoe/allow-placeholder stub (todo))")))
  (is (empty? (ct/scan-content-structural
               "m.clj" "(deftest ^{:wagoe/allow-placeholder true} stub (todo))"))))

;; =============================================================================
;; Review round 3 (BOU-365)
;; =============================================================================

(deftest ^:unit the-escape-hatch-covers-shape-findings-too
  ;; (deftest ^:wagoe/allow-placeholder stub (is true)) must be exempt from
  ;; every detector, and its neighbours must not be.
  (let [src "(deftest ^:wagoe/allow-placeholder stub (is true))\n(deftest live (is true))"
        exempt (ct/exempted-extents src)]
    (is (= 1 (count exempt)))
    (is (= 1 (:row (first exempt))))
    (testing "the live placeholder on the next line is still caught"
      (is (some #(= 2 (:line %)) (ct/scan-content-structural "x.clj" src))))))

(deftest ^:unit a-discarded-assertion-does-not-count-as-one
  (is (= 1 (count (ct/scan-content-structural
                   "x.clj" "(deftest x #_(is (= 1 2)) (setup))")))
      "the only assertion is discarded — the test runs nothing")
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest x #_(is (= 1 2)) (is (pos? (f))))"))
      "a live assertion next to a discarded one still counts"))

(deftest ^:unit a-draft-inside-a-discarded-container-is-not-scanned
  (is (empty? (ct/scan-content-structural
               "x.clj" "#_(do (deftest draft (setup)))"))))

(deftest ^:unit deeply-nested-quoted-equality-is-not-a-tautology
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest gen (is (= '(foo (= x x)) actual)))"))))

(deftest ^:unit metadata-parsing-is-structural
  (testing "a marker after a nested metadata map is seen"
    (is (empty? (ct/scan-content-structural
                 "x.clj"
                 "(deftest ^{:kaocha.testable/meta {:unit true}} ^:unit ^:wagoe/allow-placeholder stub (todo))"))))
  (testing "a misspelled marker is not an exemption"
    (is (seq (ct/scan-content-structural
              "x.clj" "(deftest ^:wagoe/allow-placeholder-typo stub (todo))")))))

;; =============================================================================
;; Review round 4 (BOU-365) — the reader decides, not a lexer
;; =============================================================================

(deftest ^:unit a-discarded-string-does-not-swallow-the-next-assertion
  ;; #_"note" before a real assertion: the lexical scan blanked the string
  ;; first, so #_ attached to the (is …) and a failing test looked vacuous.
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest x #_\"note\" (is (= 1 2)))"))))

(deftest ^:unit reader-prefixed-discards-are-fully-consumed
  (testing "#_#(…), #_#{…}, #_^:m (…) — the whole form is discarded"
    (is (= 1 (count (ct/scan-content-structural
                     "x.clj" "(deftest x #_#(is (= a b)) (setup))"))))
    (is (= 1 (count (ct/scan-content-structural
                     "x.clj" "(deftest x #_^:m (is (= a b)) (setup))"))))))

(deftest ^:unit whitespace-between-quote-and-form-is-still-quoted
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest gen (is (= ' (foo (= x x)) actual)))"))))

(deftest ^:unit the-marker-must-be-a-top-level-metadata-key
  (testing "the keyword in a value position is not an exemption"
    (is (seq (ct/scan-content-structural
              "x.clj" "(deftest ^{:other :wagoe/allow-placeholder} stub (todo))"))))
  (testing "a suffixed spelling is not an exemption"
    (is (seq (ct/scan-content-structural
              "x.clj" "(deftest ^:wagoe/allow-placeholder.foo stub (todo))")))))

(deftest ^:unit a-meaningful-nested-equality-is-not-a-tautology
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest nan (is (false? (= ##NaN ##NaN))))"))
      "only the asserted expression counts, and NaN is not equal to itself")
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest r (is (= (rand) (rand))))"))
      "equal collection forms are not equal values"))

(deftest ^:unit exemption-is-extent-scoped-and-findings-deduplicate
  ;; Through the private scan-file seam: exempt stub and live placeholder on
  ;; ONE line — the live one must survive the filter; and one (is (= true
  ;; true)) must yield one row, not a regex row plus a tautology row.
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory
                       "wagoe-ct" (make-array java.nio.file.attribute.FileAttribute 0)))
        f    (io/file dir "one_line_test.clj")
        scan #(do (spit f %) (#'ct/scan-file f))]
    (try
      (testing "same-line exempt + live: live still caught"
        (is (= 1 (count (scan "(deftest ^:wagoe/allow-placeholder stub (is true)) (deftest live (is true))")))))
      (testing "one assertion, one row"
        (is (= 1 (count (scan "(deftest t (is (= true true)))")))))
      (finally (doseq [x (reverse (file-seq dir))] (.delete x))))))

;; =============================================================================
;; Review round 5 (BOU-365)
;; =============================================================================

(deftest ^:unit the-marker-exempts-only-on-the-test-name
  ;; Metadata on an early body symbol used to exempt the whole test.
  (is (empty? (ct/exempted-extents
               "(deftest ^:unit live (setup) ^:wagoe/allow-placeholder todo (is true))"))
      "a marked symbol in the body grants no exemption — the (is true) stays a regex finding")
  (is (seq (ct/scan-content-structural
            "x.clj" "(deftest live (setup) ^:wagoe/allow-placeholder todo)"))
      "and a no-assertion test cannot exempt itself from the body either")
  (is (empty? (ct/scan-content-structural
               "x.clj" "(deftest ^:unit ^:wagoe/allow-placeholder stub (todo))"))
      "on the name, alongside other metadata, it still exempts"))

(deftest ^:unit reader-features-follow-the-test-surface
  ;; libs/tools tests execute under bb (features #{:clj :bb}); everything
  ;; else on the JVM (#{:clj}). A branch no runtime of the surface selects
  ;; is dead code, not a finding.
  (testing "on the bb surface the :bb arm is live"
    (is (= 1 (count (ct/scan-content-structural
                     "libs/tools/test/x.clj"
                     "#?(:bb (deftest x (setup)) :clj (def x 1))")))))
  (testing "on a JVM surface the same file has no live deftest"
    (is (empty? (ct/scan-content-structural
                 "libs/core/test/x.clj"
                 "#?(:bb (deftest x (setup)) :clj (def x 1))"))))
  (testing "a dead :default arm under the bb surface is not a finding"
    (is (empty? (ct/scan-content-structural
                 "libs/tools/test/x.clj"
                 "#?(:clj #?(:bb (deftest real (is (pos? (f)))) :default (deftest x (is true))))")))))

;; =============================================================================
;; Review round 6 (BOU-365)
;; =============================================================================

(deftest ^:unit babashka-enables-both-features
  ;; bb's feature set is #{:clj :bb}: a nested #?(:clj #?(:bb …)) reaches the
  ;; inner branch on the bb surface.
  (is (= 1 (count (ct/scan-content-structural
                   "libs/tools/test/x.clj"
                   "#?(:clj #?(:bb (deftest x (setup)) :default nil))")))))

(deftest ^:unit a-conditional-marker-follows-the-arm-the-surface-reads
  ;; Each surface reads exactly one arm of the conditional; the marker
  ;; exempts only where the arm carrying it is the one selected.
  (testing "bb surface reads the unmarked :bb arm — the placeholder is live"
    (is (= 1 (count (ct/scan-content-structural
                     "libs/tools/test/b.clj"
                     "(deftest #?(:bb stub :clj ^:wagoe/allow-placeholder stub) (is true))")))))
  (testing "JVM surface reads the marked :clj arm — exempt"
    (is (empty? (ct/scan-content-structural
                 "libs/core/test/b.clj"
                 "(deftest #?(:bb stub :clj ^:wagoe/allow-placeholder stub) (is true))")))))

;; =============================================================================
;; Review round 7 (BOU-365)
;; =============================================================================

(deftest ^:unit the-shape-regexes-honour-unevaluated-regions-too
  ;; The structural scan skipped #_/quote/comment; the regex scan still flagged
  ;; placeholders inside them — one gate, two philosophies.
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory
                       "wagoe-ct7" (make-array java.nio.file.attribute.FileAttribute 0)))
        f    (io/file dir "uneval_test.clj")
        scan #(do (spit f %) (#'ct/scan-file f))]
    (try
      (testing "discarded, comment-wrapped and quoted (is true) never run"
        (is (empty? (scan "(deftest t #_(is true) (is (pos? (f))))")))
        (is (empty? (scan "(comment (deftest c (is true)))\n(deftest live (is (pos? (f))))")))
        (is (empty? (scan "(deftest q (is (= x '(is true))))"))))
      (testing "a live (is true) is still a finding"
        (is (= 1 (count (scan "(deftest t (is true))")))))
      (finally (doseq [x (reverse (file-seq dir))] (.delete x))))))

;; =============================================================================
;; Review round 8 (BOU-365) — one scanner, the reader
;; =============================================================================

(deftest ^:unit a-conditional-wrapped-placeholder-is-still-caught
  ;; Edamame reports a conditional-selected form at the #? wrapper's position,
  ;; which broke the old textual-liveness bridge. Judging the parsed form
  ;; directly makes position irrelevant.
  (is (= 1 (count (ct/scan-content-structural
                   "x.clj" "(deftest x #?(:clj (is true) :bb (is (pos? (f)))))")))))

(deftest ^:unit an-alias-qualified-assertion-is-judged-like-any-other
  ;; The old regexes matched bare `(is` only, so `(t/is true)` passed by
  ;; accident of aliasing — four real sentinels in snapshot_io.clj were
  ;; invisible until the reader-based scan looked.
  (let [hits (ct/scan-content-structural
              "x.clj"
              "(ns x (:require [clojure.test :as t]))\n(deftest x (t/is true))")]
    (is (= 1 (count hits)))
    (is (str/includes? (:content (first hits)) "placeholder")
        "found as a placeholder assertion, not as a no-assertion deftest"))
  (testing "an is from a different namespace is an ordinary call"
    (is (empty? (ct/scan-content-structural
                 "x.clj"
                 "(ns x (:require [my.predicates :as pred] [clojure.test :refer [deftest is]]))\n(deftest x (pred/is true) (is (pos? (f))))")))))

(deftest ^:unit the-hatch-also-works-one-assertion-wide
  (let [with-ns #(str "(ns x (:require [clojure.test :as t]))\n" %)]
    (is (empty? (ct/scan-content-structural
                 "x.clj"
                 (with-ns "(defn helper [] ^:wagoe/allow-placeholder (t/is true \"sentinel\"))")))
        "a marked reporting sentinel in a helper is deliberate")
    (is (= 1 (count (ct/scan-content-structural
                     "x.clj" (with-ns "(defn helper [] (t/is true \"sentinel\"))"))))
        "an unmarked one is a finding — the marker is what exempts")))

;; =============================================================================
;; Review round 10 (BOU-365) — and the full decision-surface sweep
;; =============================================================================

(deftest ^:unit the-gate-decision-surface
  ;; Table-driven: every resolution/classification rule with its negative,
  ;; so the next edge case has a row to join instead of a review round.
  (doseq [[note path src expected]
          [;; -- operator resolution --------------------------------------
           ["bare is"                       "x.clj" "(deftest t (is true))" 1]
           ["renamed is counts as is"       "x.clj" "(ns x (:require [clojure.test :refer [deftest is] :rename {is check}]))\n(deftest t (check true))" 1]
           ["renamed is: real assertion passes" "x.clj" "(ns x (:require [clojure.test :refer [deftest is] :rename {is check}]))\n(deftest t (check (pos? (f))))" 0]
           ["renamed deftest is a deftest"  "x.clj" "(ns x (:require [clojure.test :refer [deftest] :rename {deftest dt}]))\n(dt t (setup))" 1]
           ["fully qualified clojure.test/is" "x.clj" "(deftest t (clojure.test/is true))" 1]
           ;; -- shape operators must be core ------------------------------
           ["domain/some? is not core some?" "x.clj" "(ns x (:require [my.domain :as domain] [clojure.test :refer [deftest is]]))\n(deftest t (is (domain/some? \"v\")))" 0]
           ["clojure.core/some? still is"    "x.clj" "(deftest t (is (clojure.core/some? \"v\")))" 1]
           ["domain/= is not core ="         "x.clj" "(ns x (:require [my.domain :as domain] [clojure.test :refer [deftest is]]))\n(deftest t (is (domain/= x x)))" 0]
           ;; -- are rows decide the tautology ------------------------------
           ["are with literal rows"          "x.clj" "(deftest t (are [x] (= x x) 1 2 3))" 1]
           ["are with a call row can differ" "x.clj" "(deftest t (are [x] (= x x) (next-value)))" 0]
           ["are with a call inside a row"   "x.clj" "(deftest t (are [x] (= x x) [1 (f)]))" 0]
           ;; -- round 11 ---------------------------------------------------
           ["windows path still selects the bb surface"
            "libs\\tools\\test\\x.clj" "#?(:bb (deftest x (setup)) :clj (def x 1))" 1]
           ["foo/comment is an ordinary call whose arguments run"
            "x.clj" "(ns x (:require [my.lib :as foo] [clojure.test :refer [deftest is]]))\n(deftest t (foo/comment (is true)) (is (pos? (f))))" 1]
           ["bare comment still prunes"
            "x.clj" "(deftest t (comment (is true)) (is (pos? (f))))" 0]
           ["refer-clojure :exclude makes bare = a local fn"
            "x.clj" "(ns x (:refer-clojure :exclude [=]) (:require [clojure.test :refer [deftest is]]))\n(deftest t (is (= x x)))" 0]]]
    (is (= expected (count (ct/scan-content-structural path src)))
        (str note ": " (pr-str src)))))

(deftest ^:unit the-exemption-stops-at-its-closing-paren
  ;; Edamame's :end-col is exclusive; with <= the assertion starting exactly
  ;; there was swallowed by the exemption.
  (let [hits (ct/scan-content-structural
              "x.clj"
              "(deftest ^:wagoe/allow-placeholder stub (todo))(is true)")]
    (is (= 1 (count hits)) "the adjacent (is true) is a neighbour, not exempt content")))

(deftest ^:unit a-testing-block-without-an-assertion-is-a-placeholder
  ;; The scaffolder generated one: a `testing` holding only a comment, next to
  ;; one that asserted, so the deftest passed the whole-test rule (BOU-562).
  (testing "an empty testing block is reported at the block"
    (is (= [3] (map :line (ct/scan-content-structural
                           "x.clj"
                           "(deftest ^:unit t\n  (testing \"a\" (is (pos? (f))))\n  (testing \"b\"\n    ;; todo\n    ))")))))
  (testing "a testing block that only sets up is reported"
    (is (= 1 (count (ct/scan-content-structural
                     "x.clj" "(deftest t (is (pos? (f))) (testing \"b\" (setup!)))")))))
  (testing "a testing block that asserts, directly or nested, is not"
    (is (empty? (ct/scan-content-structural
                 "x.clj" "(deftest t (testing \"a\" (let [x (f)] (testing \"b\" (is (pos? x))))))"))))
  (testing "a testing block asserting through a helper defined in the file is not"
    (is (empty? (ct/scan-content-structural
                 "x.clj" "(defn- check [x] (is (pos? x)))\n(deftest t (is (pos? (g))) (testing \"a\" (check (f))))"))))
  (testing "a testing block asserting through a let-bound helper is not"
    (is (empty? (ct/scan-content-structural
                 "x.clj" "(deftest t (let [check (fn [x] (is (pos? x)))] (testing \"a\" (check (f)))))"))))
  (testing "a testing block asserting through a named helper is not"
    (is (empty? (ct/scan-content-structural
                 "x.clj" "(deftest t (testing \"a\" (snapshot-io/check-snapshot! :k (f))))")))))
