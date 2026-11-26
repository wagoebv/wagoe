(ns wagoe.tools.release-test
  "`bb release` tags only a commit CI passed on main (BOU-1029)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.release :as release]))

(deftest ^:unit the-changelog-releases-what-is-unreleased
  (let [text "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A thing (BOU-1).\n\n## [1.2.0] — 2026-11-13\n\n- Old.\n"]
    (is (= "# Changelog\n\n## [Unreleased]\n\n## [1.3.0] — 2026-11-20\n\n### Fixed\n\n- A thing (BOU-1).\n\n## [1.2.0] — 2026-11-13\n\n- Old.\n"
           (release/release-changelog text "1.3.0" "2026-11-20"))))
  (testing "nothing unreleased, or no section, is nothing to release"
    (is (nil? (release/release-changelog "# C\n\n## [Unreleased]\n\n## [1.2.0] — x\n" "1.3.0" "d")))
    (is (nil? (release/release-changelog "# C\n\n## [1.2.0] — x\n" "1.3.0" "d")))))

(deftest ^:unit an-unreleased-deprecation-names-the-release
  (is (= "[{:ticket \"X-1\" :since  \"1.3.0\"} {:ticket \"X-0\" :since \"1.2.0\"}]"
         (release/release-upgrades "[{:ticket \"X-1\" :since  \"Unreleased\"} {:ticket \"X-0\" :since \"1.2.0\"}]"
                                   "1.3.0"))))

(def ^:private fresh {:version "1.3.0" :branch "main" :behind 0 :ahead 0})

(deftest ^:unit a-release-starts-only-from-an-up-to-date-clean-main
  (is (empty? (release/preflight fresh)))
  (doseq [[facts why] [[{:version "v1.3.0"} "a tag prefix"]
                       [{:branch "feature"} "another branch"]
                       [{:dirty? true} "uncommitted changes"]
                       [{:behind 1} "behind origin/main"]
                       [{:ahead 1} "an unpushed commit"]
                       [{:tagged? true} "a tag already on origin"]
                       [{:in-changelog? true} "a version section already in the changelog"]]]
    (is (= 1 (count (release/preflight (merge fresh facts)))) why)))

(deftest ^:unit a-started-release-resumes
  (testing "the release commit on main: wait and tag, though the changelog has its section"
    (is (empty? (release/preflight (merge fresh {:released? true :in-changelog? true}))))))

(deftest ^:unit the-release-commit-reaches-main-through-a-pr
  ;; BOU-1120: main has no bypass, so bb release opens a PR and tags after it merged.
  (is (= :prepare (release/next-step {})) "no release PR yet")
  (is (= :prepare (release/next-step {:pr-state "CLOSED"})) "a PR closed unmerged is opened again")
  (is (= :merge (release/next-step {:pr-state "OPEN"})) "an open PR is waited for")
  (is (= :merge (release/next-step {:pr-state "MERGED"})) "merged after the fetch: pull it in, then tag")
  (is (= :tag (release/next-step {:released? true})) "the release commit on main: tag after green CI"))

(defn- runs [& pairs] (map (fn [[s c]] {:status s :conclusion c}) (partition 2 pairs)))

(deftest ^:unit only-a-green-run-on-main-lets-the-tag-through
  (is (= :green (release/verdict (runs "completed" "success"))))
  (is (= :green (release/verdict (runs "completed" "cancelled" "completed" "success"))))
  (is (= :red (release/verdict (runs "completed" "failure"))))
  (testing "a run cancelled by a newer push says so at once"
    (is (= :superseded (release/verdict (runs "completed" "cancelled")))))
  (testing "a running or absent run is no verdict yet"
    (is (= :pending (release/verdict (runs "in_progress" nil))))
    (is (= :pending (release/verdict (runs "completed" "cancelled" "queued" nil))))
    (is (= :pending (release/verdict [])))))

(deftest ^:unit a-snapshot-is-never-released-by-tag
  ;; BOU-1057: a snapshot is published by dispatch, and tags are immutable.
  (is (= 1 (count (release/preflight (assoc fresh :version "1.3.0-SNAPSHOT"))))))
