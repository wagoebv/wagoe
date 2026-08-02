(ns wagoe.tools.check-doc-counts-test
  "Unit tests for the documented-library-count gate.

   The cases are not invented. Every string asserted on below appeared verbatim
   in the repository while being wrong, and each one survived at least one
   correction pass that was searching for a different phrasing of the same
   claim (PR #351)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.check-doc-counts :as sut]))

;; =============================================================================
;; Scope
;; =============================================================================

(deftest ^:unit in-scope-test
  (testing "live documentation is scanned"
    (is (sut/in-scope? "README.md"))
    (is (sut/in-scope? "AGENTS.md"))
    (is (sut/in-scope? "docs/modules/ROOT/pages/stability.adoc"))
    (is (sut/in-scope? "libs/tools/AGENTS.md")))

  (testing "historical documents are not — their counts were true when written"
    (is (not (sut/in-scope? "CHANGELOG.md")))
    (is (not (sut/in-scope? "dev-docs/adr/ADR-001-library-split.adoc")))
    (is (not (sut/in-scope? "dev-docs/roadmap.adoc")))
    (is (not (sut/in-scope? "docs/superpowers/specs/2026-05-24-boundary-push-design.md"))))

  (testing "non-documentation and build output are not"
    (is (not (sut/in-scope? "libs/tools/src/wagoe/tools/deploy.clj")))
    (is (not (sut/in-scope? "libs/platform/target/classes/README.md")))))

;; =============================================================================
;; Rule 1 — counts
;; =============================================================================

(deftest ^:unit count-findings-detects-every-phrasing-that-drifted-test
  (testing "each real phrasing from the PR #351 sweep is caught"
    (doseq [line ["Wagoe is a monorepo of **23 independently publishable libraries**"
                  "Wagoe is a monorepo of 24 libraries publishable to Clojars"
                  "=== All 22 artifacts in one command"
                  "This deploys all 22 publishable artifacts in dependency"
                  "bb deploy --all              # Deploy all 24 libraries to Clojars"
                  "=== For developers: 24 composable libraries"
                  "| `wagoe.tools.deploy` | `bb deploy` (handles all 24 libs) |"
                  "| `wagoe.tools.deploy` | `bb deploy` | Publish the 22 Clojars artifacts |"
                  "bb deploy --all                  # Deploy all 21+ libraries to Clojars"
                  "| Clojars deployment for all 24 published artifacts"
                  "Total CI time is approximately 15–20 minutes (23 artifacts × 30s"]]
      (is (seq (sut/count-findings "doc.md" line 29))
          (str "not detected: " line)))))

(deftest ^:unit count-findings-accepts-the-right-number-test
  (testing "the same phrasings pass once corrected"
    (doseq [line ["Wagoe is a monorepo of **29 independently publishable libraries**"
                  "=== All 29 artifacts in one command"
                  "bb deploy --all              # Deploy all 29 libraries to Clojars"
                  "| `wagoe.tools.deploy` | `bb deploy` (handles all 29 libs) |"]]
      (is (empty? (sut/count-findings "doc.md" line 29))
          (str "false positive: " line)))))

(deftest ^:unit count-findings-reports-position-and-value-test
  (testing "a finding carries enough to fix it without re-grepping"
    (let [[f :as fs] (sut/count-findings "README.md" "intro\n\nall 22 artifacts here\n" 29)]
      (is (= 1 (count fs)))
      (is (= "README.md" (:path f)))
      (is (= 3 (:line f)))
      (is (= 22 (:found f)))
      (is (= :count (:rule f)))
      (is (str/includes? (:context f) "all 22 artifacts")))))

(deftest ^:unit count-findings-ignores-unrelated-numbers-test
  (testing "numbers not attached to a library noun are left alone"
    (doseq [line ["Total CI time is approximately 20–25 minutes"
                  "with a 30-second pause between artifacts to allow indexing"
                  "Requires Clojure 1.12 and Java 21"
                  "The pool defaults to 10 connections"]]
      (is (empty? (sut/count-findings "doc.md" line 29))
          (str "false positive: " line))))

  (testing "a distant number does not bind to a later noun"
    (is (empty? (sut/count-findings
                 "doc.md"
                 "12 of these were fixed in the first pass, which is why libraries drift"
                 29)))))

(deftest ^:unit count-findings-ignores-per-library-and-subset-phrasing-test
  ;; Both found by running the gate against the real tree on the first attempt.
  (testing "a number that applies to each library individually is not a count of them"
    (is (empty? (sut/count-findings
                 "doc.md" "# Quick check — expects HTTP 200 for each library" 29))))

  (testing "`N of M libraries` describes a subset, not the suite"
    (is (empty? (sut/count-findings "doc.md" "Partially delivered (6 of 9 libraries done)" 29))))

  (testing "but `of` alone is not the signal — `a monorepo of N libraries` is a total"
    ;; Excluding every `of N` was the first attempt, and it went blind to the
    ;; exact phrasing the README had been getting wrong. Only a digit before the
    ;; `of` marks a subset.
    (is (seq (sut/count-findings "doc.md" "Wagoe is a monorepo of 23 libraries" 29)))
    (is (seq (sut/count-findings "doc.md" "a suite of 24 libraries" 29))))

  (testing "but a scoped total is still a total, and still wrong"
    ;; `across all 22 libraries` reads as a claim about the whole suite. It was
    ;; tempting to drop anything with a preposition nearby; that would have made
    ;; the gate blind to a real stale count.
    (is (seq (sut/count-findings "doc.md" "3044 tests across all 22 libraries" 29))))

  (testing "adjectives between number and noun still count — the real phrasings survive"
    (doseq [line ["24 independent libs"
                  "13 onafhankelijk publiceerbare libraries"
                  "all 24 published artifacts"
                  "Publish the 22 Clojars artifacts"]]
      (is (seq (sut/count-findings "doc.md" line 29))
          (str "disqualifier too broad, lost: " line)))))

(deftest ^:unit count-findings-reads-spelled-out-counts-test
  ;; `docs/modules/ROOT/pages/roadmap.adoc` opens with "Twenty-nine libraries
  ;; are published on Clojars" — a live, public count the digit-only pattern
  ;; could not see. A gate that misses the sentence a document leads with is not
  ;; guarding that document.
  (testing "a word-form count that disagrees is caught"
    (doseq [line ["Twenty-nine libraries are published on Clojars under `com.wagoe`"
                  "Twenty-two libraries are published on Clojars"
                  "The suite is thirteen libraries today"
                  "All twenty-four artifacts ship together"
                  "Fifteen independently publishable libraries"]]
      (is (seq (sut/count-findings "doc.md" line 30))
          (str "not detected: " line))))

  (testing "a word-form count that agrees passes"
    (doseq [line ["Twenty-nine libraries are published on Clojars under `com.wagoe`"
                  "All twenty-nine artifacts ship together"]]
      (is (empty? (sut/count-findings "doc.md" line 29))
          (str "false positive: " line))))

  (testing "the subset rule applies whichever form the numbers take"
    (is (empty? (sut/count-findings "doc.md" "one of twenty-two libraries is stale" 29)))
    (is (empty? (sut/count-findings "doc.md" "6 of twenty-two libraries are stale" 29))))

  (testing "small spelled-out numbers are prose, not totals"
    ;; `docs/modules/architecture/pages/scaling.adoc:67` says "Two libraries
    ;; already ship both adapters" — true, and a subset. English spells out
    ;; small numbers in ordinary sentences, so a units-word before a library
    ;; noun is almost never the suite total; the digit form still catches any
    ;; count, including a single-digit one.
    (doseq [line ["Two libraries already ship both adapters"
                  "Three libraries depend on `platform`"
                  "This scaffolds four core libs"
                  "One library has no shell layer"]]
      (is (empty? (sut/count-findings "doc.md" line 29))
          (str "false positive on subset prose: " line)))))

(deftest ^:unit parse-count-reads-both-forms-test
  (testing "digits and English number words resolve to the same integer"
    (is (= 29 (sut/parse-count "29")))
    (is (= 29 (sut/parse-count "twenty-nine")))
    (is (= 29 (sut/parse-count "Twenty-Nine")))
    (is (= 29 (sut/parse-count "twenty nine")))
    (is (= 20 (sut/parse-count "twenty")))
    (is (= 13 (sut/parse-count "thirteen")))
    (is (nil? (sut/parse-count "several")))))

;; =============================================================================
;; Rule 2 — publishing claims
;; =============================================================================

(def ^:private published #{"tools" "wagoe-tools" "devtools" "wagoe-devtools"
                           "wagoe-cli" "cli" "core" "wagoe-core"})

(deftest ^:unit publishing-findings-detects-every-phrasing-that-drifted-test
  (testing "each real claim about wagoe-tools is caught"
    (doseq [line ["> **Note:** `libs/tools` is not published to Clojars."
                  "This library is **dev-only**: it is **not published to Clojars**."
                  "`wagoe-tools` itself is not published — it is a monorepo-internal tool."
                  "- `bb deploy --all` publishes every artifact listed in `all-libs`. `wagoe-tools` is excluded from this list."
                  "It is a dev-only dependency and is not included in `bb deploy --all`."]]
      (is (seq (sut/publishing-findings "libs/tools/AGENTS.md" line published))
          (str "not detected: " line)))))

(deftest ^:unit publishing-findings-detects-generic-claims-with-no-subject-test
  ;; The regression this gate exists to prevent, in the file it was found in.
  ;; docs/.../libraries/pages/index.adoc carried exactly this sentence above the
  ;; devtools/tools/cli/mcp table. It names no library and the page has no single
  ;; subject, so attribution by name or by subject both miss it.
  (testing "a plural claim about libraries is caught even with nothing named"
    (is (seq (sut/publishing-findings
              "docs/modules/libraries/pages/index.adoc"
              "These libraries are not published to Clojars — they are part of the monorepo."
              published))))

  (testing "the same claim in any index-like document is caught"
    (is (seq (sut/publishing-findings
              "README.md" "The dev libs are not published to Clojars." published))))

  (testing "a claim about something that is not a library is still left alone"
    (doseq [line ["Your application jar is not published to Clojars."
                  "The generated project is not published to Clojars."
                  "Build output is not published to Clojars."]]
      (is (empty? (sut/publishing-findings
                   "docs/modules/guides/pages/deployment.adoc" line published))
          (str "false positive: " line))))

  (testing "a true statement naming a genuinely unpublished library is left alone"
    ;; Found by running the generic rule against the tree: this line is correct,
    ;; and mentions `libs/` so the library-noun test alone flagged it.
    (is (empty? (sut/publishing-findings
                 "dev-docs/reference/publishing.adoc"
                 "`libs/e2e` is the only directory under `libs/` that is not published — it is an in-repo test harness."
                 published
                 #{"e2e"})))

    (testing "and the same sentence IS flagged once that library becomes published"
      ;; Guards the escape hatch: it must key off all-libs, not off the wording.
      (is (seq (sut/publishing-findings
                "dev-docs/reference/publishing.adoc"
                "`libs/e2e` is the only directory under `libs/` that is not published — it is an in-repo test harness."
                published
                #{}))))))

(deftest ^:unit names-lib-matches-words-not-substrings-test
  ;; Found by injecting the real regression: the finding said "calls ai
  ;; unpublished" because `ai` sits inside `available`.
  (testing "a library name inside another word is not a mention"
    (is (not (sut/names-lib? "they are available automatically" "ai")))
    (is (not (sut/names-lib? "the maintenance window" "ai")))
    (is (not (sut/names-lib? "admin-ui-style-tokens.css" "ui-style"))))

  (testing "a real mention still matches, in the punctuation docs actually use"
    (doseq [line ["the ai library" "`ai`" "libs/ai/" "(ai)" "ai."]]
      (is (sut/names-lib? line "ai") (str "missed mention in: " line))))

  (testing "the artifact form matches its own entry, not the bare directory name"
    ;; `wagoe-ai` is not a mention of `ai` — the hyphen binds them into one
    ;; token. Nothing is lost: both spellings are in published-libs, so the line
    ;; is still attributed, to the more specific of the two.
    (is (not (sut/names-lib? "com.wagoe/wagoe-ai" "ai")))
    (is (sut/names-lib? "com.wagoe/wagoe-ai" "wagoe-ai")))

  (testing "hyphenated names match as a unit"
    (is (sut/names-lib? "see `wagoe-cli` for details" "wagoe-cli"))
    (is (sut/names-lib? "the shared-ui primitives" "shared-ui"))))

(deftest ^:unit publishing-findings-attributes-to-the-right-library-test
  (testing "the generic claim reports libraries, not a word that contains one"
    (let [[f] (sut/publishing-findings
               "docs/modules/libraries/pages/index.adoc"
               "These libraries are not published to Clojars — they are part of the monorepo and available automatically."
               (conj published "ai"))]
      (is (some? f))
      (is (not= ["ai"] (:libs f))
          "`ai` appears only inside `available`; attributing to it misdirects the fix"))))

(deftest ^:unit unpublished-lib-dirs-is-derived-from-all-libs-test
  (testing "the unpublished set comes from disk, so a new library needs no edit here"
    (let [dirs (sut/unpublished-lib-dirs)]
      (is (contains? dirs "e2e")
          "libs/e2e is a test harness, not a published library")
      (is (not-any? (set dirs) ["core" "platform" "tools" "wagoe-cli"])
          "nothing in all-libs may appear as unpublished"))))

(deftest ^:unit publishing-findings-uses-the-documents-subject-test
  (testing "a claim with no library named is attributed to the file's subject"
    (is (seq (sut/publishing-findings
              "libs/tools/README.md"
              "NOTE: Not published to Clojars. Part of the monorepo."
              published))))

  (testing "the Antora library pages map to their artifact"
    (is (seq (sut/publishing-findings
              "docs/modules/libraries/pages/cli.adoc"
              "NOTE: Not published to Clojars. Installed via bbin."
              published)))))

(deftest ^:unit publishing-findings-allows-genuinely-unpublished-libraries-test
  (testing "libs/e2e really is not published, and must not be flagged"
    (is (empty? (sut/publishing-findings
                 "libs/e2e/README.md"
                 "Test code only — this library is **not published to Clojars**."
                 published))))

  (testing "a published library named in passing does not override the file's subject"
    ;; The real line from libs/e2e/README.md, which mentions `platform` while
    ;; being about e2e. Attribution by mention alone flagged it on the gate's
    ;; first run against the tree.
    (is (empty? (sut/publishing-findings
                 "libs/e2e/README.md"
                 (str "End-to-end browser + HTTP-API test suite for the Wagoe platform. "
                      "**Test code only** — this library ships no production namespaces "
                      "and is **not published to Clojars**.")
                 (conj published "platform")))))

  (testing "a claim about something outside all-libs is left alone"
    (is (empty? (sut/publishing-findings
                 "docs/modules/guides/pages/deployment.adoc"
                 "Your application jar is not published to Clojars."
                 published))))

  (testing "but an unpublished subject does not exempt the rest of the file"
    ;; The suppression above is about the file's *own* claim. Skipping the file
    ;; wholesale — the first implementation — meant `libs/e2e/README.md` could
    ;; say anything at all about any other library and never be read.
    (let [[f :as fs] (sut/publishing-findings
                      "libs/e2e/README.md"
                      "`wagoe-tools` is not published to Clojars."
                      published)]
      (is (= 1 (count fs)))
      (is (= ["wagoe-tools"] (:libs f))
          "the claim names a published library as its subject — attribute it there"))

    (is (seq (sut/publishing-findings
              "libs/e2e/README.md"
              "Unlike `core`, which is not published to Clojars, this suite runs in-repo."
              published)))))

(deftest ^:unit subject-lib-test
  (is (= "tools" (sut/subject-lib "libs/tools/AGENTS.md")))
  (is (= "wagoe-cli" (sut/subject-lib "libs/wagoe-cli/README.md")))
  (is (= "cli" (sut/subject-lib "docs/modules/libraries/pages/cli.adoc")))
  (is (nil? (sut/subject-lib "docs/modules/libraries/pages/index.adoc")))
  (is (nil? (sut/subject-lib "README.md"))))

;; =============================================================================
;; Allowlist
;; =============================================================================

(deftest ^:unit allowed-test
  (let [allow #{["docs/x.adoc" :any] ["docs/y.adoc" 12]}]
    (is (sut/allowed? allow {:path "docs/x.adoc" :line 99}))
    (is (sut/allowed? allow {:path "docs/y.adoc" :line 12}))
    (is (not (sut/allowed? allow {:path "docs/y.adoc" :line 13})))
    (is (not (sut/allowed? allow {:path "docs/z.adoc" :line 1})))))

(deftest ^:unit allowlist-requires-a-justification-test
  (testing "an entry without :why is rejected — an unexplained exemption is where a bug hides"
    (let [f (doto (java.io.File/createTempFile "allow" ".edn") .deleteOnExit)]
      (spit f (pr-str {:allow [{:path "docs/x.adoc"}]}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :why"
                            (sut/read-allowlist f)))))

  (testing "an entry with :why is honoured"
    (let [f (doto (java.io.File/createTempFile "allow" ".edn") .deleteOnExit)]
      (spit f (pr-str {:allow [{:path "docs/x.adoc" :why "quotes the old number on purpose"}]}))
      (is (= #{["docs/x.adoc" :any]} (sut/read-allowlist f))))))

;; =============================================================================
;; Scan
;; =============================================================================

(deftest ^:unit scan-applies-the-allowlist-test
  (let [files {"a.md" "all 22 libraries\n"
               "b.md" "all 23 libraries\n"}
        opts  {:expected 29 :published-libs published :read-file files}]
    (testing "both are reported without an allowlist"
      (is (= 2 (count (sut/scan (keys files) opts)))))

    (testing "an allowlisted file drops out"
      (is (= ["b.md"] (map :path (sut/scan (keys files)
                                           (assoc opts :allow #{["a.md" :any]}))))))))

(deftest ^:unit scan-is-sorted-for-stable-output-test
  (let [files {"z.md" "all 22 libraries\n" "a.md" "all 23 libraries\n"}]
    (is (= ["a.md" "z.md"]
           (map :path (sut/scan (keys files)
                                {:expected 29 :published-libs published :read-file files}))))))
