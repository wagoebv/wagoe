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
