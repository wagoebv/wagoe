(ns wagoe.tools.check-changelog-test
  "Unit tests for the changelog gate.

   The file lists in these cases are real: they are the shapes of the thirty
   pull requests that merged between 2026-08-05 and 2026-08-16 without one
   CHANGELOG entry between them."
  (:require [clojure.test :refer [deftest is testing]]
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
