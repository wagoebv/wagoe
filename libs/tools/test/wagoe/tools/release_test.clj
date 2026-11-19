(ns wagoe.tools.release-test
  "`bb release` tags only a commit CI passed on main (BOU-1029)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.release :as release]))

(deftest ^:unit the-changelog-releases-what-is-unreleased
  (let [text "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A thing (BOU-1).\n\n## [1.2.0] — 2026-11-13\n\n- Old.\n"]
    (is (= "# Changelog\n\n## [Unreleased]\n\n## [1.3.0] — 2026-11-20\n\n### Fixed\n\n- A thing (BOU-1).\n\n## [1.2.0] — 2026-11-13\n\n- Old.\n"
           (release/release-changelog text "1.3.0" "2026-11-20"))))
  (testing "nothing unreleased, or no section, is nothing to release"
    (is (nil? (release/release-changelog "## [Unreleased]\n\n## [1.2.0] — x\n" "1.3.0" "d")))
    (is (nil? (release/release-changelog "## [1.2.0] — x\n" "1.3.0" "d")))))

(deftest ^:unit a-release-starts-only-from-an-up-to-date-clean-main
  (let [ok {:version "1.3.0" :branch "main"}]
    (is (empty? (release/preflight ok)))
    (doseq [[facts why] [[{:version "v1.3.0"} "a tag prefix"]
                         [{:branch "feature"} "another branch"]
                         [{:dirty? true} "uncommitted changes"]
                         [{:behind? true} "behind origin/main"]
                         [{:ahead? true} "unpushed commits"]
                         [{:tag-exists? true} "an existing tag"]]]
      (is (= 1 (count (release/preflight (merge ok facts)))) why))))

(deftest ^:unit only-a-green-run-on-main-lets-the-tag-through
  (is (= :green (release/verdict ["completed:success"])))
  (is (= :green (release/verdict ["completed:cancelled" "completed:success"])))
  (is (= :red (release/verdict ["completed:failure"])))
  (testing "a running, absent or only superseded run is no verdict yet"
    (is (= :pending (release/verdict ["in_progress:none"])))
    (is (= :pending (release/verdict [])))
    (is (= :pending (release/verdict ["completed:cancelled"])))))
