(ns wagoe.tools.check-changelog-test
  "Unit tests for the changelog gate.

   The file lists in these cases are real: they are the shapes of the thirty
   pull requests that merged between 2026-08-05 and 2026-08-16 without one
   CHANGELOG entry between them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-changelog :as sut]))

;; =============================================================================
;; What counts as shipped source
;; =============================================================================

(deftest ^:unit shipped-source-test
  (testing "source that ends up in a published artifact"
    (is (sut/shipped-source? "src/wagoe/main.clj"))
    (is (sut/shipped-source? "libs/cache/src/wagoe/cache/shell/adapters/redis.clj"))
    (is (sut/shipped-source? "libs/tools/src/wagoe/tools/check_changelog.clj"))
    (is (sut/shipped-source? "libs/i18n/src/wagoe/i18n/core.cljc")))

  (testing "and what a user of the framework cannot observe"
    (is (not (sut/shipped-source? "libs/cache/test/wagoe/cache/adapter_surface_test.clj")))
    (is (not (sut/shipped-source? "test/support/handler_test_helpers.clj")))
    (is (not (sut/shipped-source? "dev/wagoe/test/reporter.clj")))
    (is (not (sut/shipped-source? "docs/modules/architecture/pages/scaling.adoc")))
    (is (not (sut/shipped-source? ".github/workflows/ci.yml")))
    (is (not (sut/shipped-source? "libs/cache/AGENTS.md")))
    (is (not (sut/shipped-source? "libs/cache/resources/wagoe/cache/x.edn")))
    (is (not (sut/shipped-source? "bb.edn")))
    (is (not (sut/shipped-source? "CHANGELOG.md"))))

  (testing "a path that merely contains src/ is not src/"
    (is (not (sut/shipped-source? "docs/src/example.clj")))
    (is (not (sut/shipped-source? "libs/cache/test/src/helper.clj")))))

;; =============================================================================
;; The verdict
;; =============================================================================

(deftest ^:unit a-source-change-without-an-entry-is-caught
  ;; PR #395 changed the order jobs are dispatched in and touched no changelog.
  (let [changed ["libs/jobs/src/wagoe/jobs/shell/adapters/in_memory.clj"
                 "libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"
                 "libs/jobs/test/wagoe/jobs/adapter_surface_test.clj"
                 "libs/jobs/AGENTS.md"
                 ".github/workflows/ci.yml"]]
    (is (= {:files ["libs/jobs/src/wagoe/jobs/shell/adapters/in_memory.clj"
                    "libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"]}
           (sut/verdict changed false))
        "a behaviour change to two adapters went unreported")))

(deftest ^:unit an-entry-satisfies-it
  (is (nil? (sut/verdict ["libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"
                          "CHANGELOG.md"]
                         false))))

(deftest ^:unit a-branch-that-ships-nothing-needs-no-entry
  (testing "docs only"
    ;; PR #393 rewrote two architecture pages and changed no behaviour.
    (is (nil? (sut/verdict ["docs/modules/architecture/pages/scaling.adoc"
                            "AGENTS.md"]
                           false))))

  (testing "tests only"
    (is (nil? (sut/verdict ["libs/cache/test/wagoe/cache/adapter_surface_test.clj"]
                           false))))

  (testing "CI only"
    (is (nil? (sut/verdict [".github/workflows/ci.yml"] false))))

  (testing "nothing at all"
    (is (nil? (sut/verdict [] false)))))

(deftest ^:unit the-opt-out-waives-it
  ;; For a source change a user will not notice — a rename, a comment, a
  ;; refactor with no behavioural edge. Explicit, and visible in git log.
  (is (nil? (sut/verdict ["libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"] true))
      "the marker did not waive the requirement"))

;; =============================================================================
;; Rule 3 — the stability page counts the breaks the changelog lists (BOU-579)
;; =============================================================================

(def ^:private changelog
  "## [Unreleased]\n\n### Breaking\n\n- **A** (X-1). Do a.\n  More.\n- **B** (X-2). Do b.\n\n### Fixed\n\n- **C** (X-3).\n\n## [1.0.0-rc-3]\n\n### Breaking\n\n- **Old** (X-0).\n")

(defn- stability [word items additions total]
  (str "| Current version\n| `1.0.0-rc-3`\n\n"
       "* *One has, in `1.0.0-rc-2`:* a thing.\n"
       "* *One more, in `1.0.0-rc-3`:* another.\n"
       "* *" word " in `1.0.0-rc-4`:* each is under `### Breaking`.\n"
       (apply str (map #(str "** " % "\n") items))
       "+\n" additions " additions to the three frozen at `rc-1`, so the list is " total ".\n"
       "* Breaking changes that already shipped are in `CHANGELOG.md`.\n"))

(deftest ^:unit the-stability-page-agrees-with-the-changelog
  (is (empty? (sut/stability-findings changelog (stability "Two" ["a" "b"] "Four" "seven"))))
  (testing "with the totals wrapped across lines, as prose is"
    (is (empty? (sut/stability-findings
                 changelog
                 (str/replace (stability "Two" ["a" "b"] "Four" "seven")
                              "frozen at `rc-1`" "frozen at\n  `rc-1`"))))))

(deftest ^:unit a-break-the-stability-page-does-not-count-is-caught
  (testing "the rc count"
    (is (seq (sut/stability-findings changelog (stability "One" ["a" "b"] "Three" "six")))))
  (testing "the listed items"
    (is (seq (sut/stability-findings changelog (stability "Two" ["a"] "Four" "seven")))))
  (testing "the additions"
    (is (seq (sut/stability-findings changelog (stability "Two" ["a" "b"] "Three" "six")))))
  (testing "the total"
    (is (seq (sut/stability-findings changelog (stability "Two" ["a" "b"] "Four" "eight")))))
  (testing "no paragraph at all for breaks that are unreleased"
    (is (seq (sut/stability-findings changelog "| Current version\n| `1.0.0-rc-3`\n")))))

(def ^:private released-changelog
  (str "## [Unreleased]\n\n### Fixed\n\n- **D** (X-4).\n\n"
       "## [1.0.0] — 2026-09-28\n\n### Breaking\n\n- **A** (X-1). Do a.\n- **B** (X-2). Do b.\n\n"
       "## [1.0.0-rc-3]\n\n### Breaking\n\n- **Old** (X-0).\n"))

(defn- released-stability [word items additions total]
  (str "| Current version\n| `1.0.0`\n\n"
       "* *One has, in `1.0.0-rc-2`:* a thing.\n"
       "* *One more, in `1.0.0-rc-3`:* another.\n"
       "* *" word " in `1.0.0`:* each is under `### Breaking`.\n"
       (apply str (map #(str "** " % "\n") items))
       "+\n" additions " additions to the three frozen at `rc-1`, so the list is " total ".\n"))

(deftest ^:unit at-1-0-0-the-page-counts-what-1-0-0-shipped
  (is (empty? (sut/stability-findings released-changelog
                                      (released-stability "Two" ["a" "b"] "Four" "seven"))))
  (testing "the 1.0.0 count"
    (is (seq (sut/stability-findings released-changelog
                                     (released-stability "One" ["a" "b"] "Three" "six")))))
  (testing "the listed items"
    (is (seq (sut/stability-findings released-changelog
                                     (released-stability "Two" ["a"] "Four" "seven"))))))

(deftest ^:unit after-1-0-0-an-unreleased-break-needs-a-major-version
  (is (seq (sut/stability-findings
            (str/replace released-changelog "### Fixed\n\n- **D** (X-4)."
                         "### Breaking\n\n- **E** (X-5). Do e.")
            (released-stability "Two" ["a" "b"] "Four" "seven")))))

(deftest ^:unit a-patch-release-is-held-to-the-1-0-0-rules
  ;; 1.0.1 failed this gate with "no current version": the pattern knew only
  ;; 1.0.0 and its candidates.
  (let [page (str/replace (released-stability "Two" ["a" "b"] "Four" "seven")
                          "| `1.0.0`\n" "| `1.0.1`\n")]
    (is (empty? (sut/stability-findings released-changelog page)))
    (testing "and still refuses an unreleased break"
      (is (seq (sut/stability-findings
                (str/replace released-changelog "### Fixed\n\n- **D** (X-4)."
                             "### Breaking\n\n- **E** (X-5). Do e.")
                page))))))

(deftest ^:unit the-shipped-stability-page-agrees-with-the-changelog
  (is (empty? (sut/stability-findings (slurp "CHANGELOG.md")
                                      (slurp "docs/modules/ROOT/pages/stability.adoc")))))
