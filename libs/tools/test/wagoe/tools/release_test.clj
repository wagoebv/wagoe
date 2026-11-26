(ns wagoe.tools.release-test
  "`bb release` tags only a commit CI passed on main (BOU-1029)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.changes :as changes]
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
  (testing "the release PR merged: wait and tag, though the changelog has its section"
    (is (empty? (release/preflight (merge fresh {:pr-state "MERGED" :in-changelog? true})))))
  (testing "main moved on while the release PR waits or after it merged: no git pull needed"
    (is (empty? (release/preflight (merge fresh {:pr-state "OPEN" :behind 3}))))
    (is (empty? (release/preflight (merge fresh {:pr-state "MERGED" :behind 3})))))
  (testing "a closed release PR starts over from an up-to-date main"
    (is (= 1 (count (release/preflight (merge fresh {:pr-state "CLOSED" :behind 1})))))))

(deftest ^:unit the-release-commit-reaches-main-through-a-pr
  ;; BOU-1120: main has no bypass, so bb release opens a PR and tags after it merged.
  (is (= :prepare (release/next-step nil)) "no release PR yet")
  (is (= :prepare (release/next-step "CLOSED")) "a PR closed unmerged is opened again")
  (is (= :merge (release/next-step "OPEN")) "an open PR is waited for")
  (is (= :tag (release/next-step "MERGED")) "a merged PR: tag its merge commit after green CI"))

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

(deftest ^:unit a-passed-merge-queue-run-counts-as-green
  ;; A queue push runs CI only on its last commit; a release merged mid-group has its queue run only.
  (let [queue (fn [c] {:event "merge_group" :status "completed" :conclusion c})]
    (is (= :green (release/verdict [(queue "success")])))
    (is (= :green (release/verdict [(queue "success") {:event "push" :status "in_progress"}])))
    (testing "a failed or cancelled queue run decides nothing"
      (is (= :pending (release/verdict [(queue "failure")])))
      (is (= :pending (release/verdict [(queue "cancelled")])))
      (is (= :green (release/verdict [(queue "failure") {:event "push" :status "completed" :conclusion "success"}]))))
    (is (= :green (release/verdict [(queue "success") {:event "push" :status "completed" :conclusion "failure"}]))
        "the push run skipped its tests and reused the queue run")
    (is (= :pending (release/verdict [{:event "merge_group" :status "in_progress"}])) "a queue run still running")))

(deftest ^:unit a-snapshot-is-never-released-by-tag
  ;; BOU-1057: a snapshot is published by dispatch, and tags are immutable.
  (is (= 1 (count (release/preflight (assoc fresh :version "1.3.0-SNAPSHOT"))))))

(def ^:private fragments
  [["changes/BOU-2.md" "### Added\n\n- B (BOU-2).\n\n### Fixed\n\n- C (BOU-2).\n\n## Upgrading\n\n### Do b (BOU-2)\n\nRun b.\n"]
   ["changes/BOU-3.md" "### Fixed\n\n- D (BOU-3).\n\n## Upgrading\n\n### 1. Do d (BOU-3)\n\nRun d.\n"]])

(deftest ^:unit fragments-join-their-sections-of-unreleased
  (is (= (str "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A (BOU-1).\n- C (BOU-2).\n- D (BOU-3).\n\n"
              "### Added\n\n- B (BOU-2).\n\n## [1.2.0] — x\n\n- Old.\n")
         (changes/with-fragments
           "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A (BOU-1).\n\n## [1.2.0] — x\n\n- Old.\n" fragments)))
  (testing "new sections go in Keep a Changelog order, Breaking first"
    (is (= "## [Unreleased]\n\n### Breaking\n\n- X.\n\n### Fixed\n\n- Y.\n\n"
           (changes/with-fragments "## [Unreleased]\n"
             [["changes/A.md" "### Fixed\n\n- Y.\n"] ["changes/B.md" "### Breaking\n\n- X.\n"]]))))
  (testing "no fragments leave the changelog as it is"
    (let [text (slurp "CHANGELOG.md")]
      (is (= text (changes/with-fragments text []))))))

(def ^:private upgrading
  "# Upgrading\n\nIntro.\n\n## 1.2.x → 1.3.0\n\n### 1. Old (BOU-1)\n\nDo it.\n\n## 1.1.x → 1.2.0\n\n### 1. Older\n")

(deftest ^:unit upgrade-notes-land-in-the-releases-section
  (is (= (str "# Upgrading\n\nIntro.\n\n## 1.2.x → 1.3.0\n\n### 1. Old (BOU-1)\n\nDo it.\n\n"
              "### 2. Do b (BOU-2)\n\nRun b.\n\n### 3. Do d (BOU-3)\n\nRun d.\n\n## 1.1.x → 1.2.0\n\n### 1. Older\n")
         (changes/with-upgrades upgrading "1.3.0-rc-1" fragments)))
  (testing "a release with no section yet gets one above the newest"
    (is (= (str "# Upgrading\n\nIntro.\n\n## 1.3.x → 1.4.0\n\n### 1. Do b (BOU-2)\n\nRun b.\n\n"
                "### 2. Do d (BOU-3)\n\nRun d.\n\n## 1.2.x → 1.3.0" (second (str/split upgrading #"## 1.2.x → 1.3.0")))
           (changes/with-upgrades upgrading "1.4.0" fragments))))
  (testing "a beta lands in its release's section"
    (is (= (changes/with-upgrades upgrading "1.3.0" fragments)
           (changes/with-upgrades upgrading "1.3.0-beta-2" fragments))))
  (testing "no notes leave it as it is"
    (is (= upgrading (changes/with-upgrades upgrading "1.4.0" (take 0 fragments))))))

(deftest ^:unit a-release-merges-the-fragments-and-deletes-them
  (let [root (str (fs/create-temp-dir))]
    (try
      (fs/create-dirs (fs/path root "changes"))
      (spit (str (fs/path root "CHANGELOG.md")) "# C\n\n## [Unreleased]\n\n## [1.2.0] — x\n")
      (spit (str (fs/path root "UPGRADING.md")) upgrading)
      (doseq [[path text] fragments] (spit (str (fs/path root path)) text))
      (changes/merge! root "1.3.0")
      (is (= "# C\n\n## [Unreleased]\n\n### Added\n\n- B (BOU-2).\n\n### Fixed\n\n- C (BOU-2).\n- D (BOU-3).\n\n## [1.2.0] — x\n"
             (slurp (str (fs/path root "CHANGELOG.md")))))
      (is (= (changes/with-upgrades upgrading "1.3.0" fragments) (slurp (str (fs/path root "UPGRADING.md")))))
      (is (empty? (changes/read-fragments root)))
      (testing "and refuses one it would misfile, touching nothing"
        (let [before (map #(slurp (str (fs/path root %))) ["CHANGELOG.md" "UPGRADING.md"])]
          (spit (str (fs/path root "changes/BOU-4.md")) "- loose\n")
          (is (= :release-refused (try (changes/merge! root "1.3.0")
                                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= before (map #(slurp (str (fs/path root %))) ["CHANGELOG.md" "UPGRADING.md"])))
          (is (= 1 (count (changes/read-fragments root))))))
      (finally (fs/delete-tree root)))))
