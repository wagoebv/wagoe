(ns wagoe.tools.release
  "`bb release <version>` (BOU-1029). A release tag lands only on a commit CI
   passed on main.

   From a clean, up-to-date main it moves `[Unreleased]` to the version, bumps,
   and opens `Release <version>` as a PR from `release/<version>` with
   auto-merge (BOU-1120). Once that merged, it tags the PR's merge commit when
   CI on main passed there and it holds the version, so the tag holds nothing
   the changelog does not list. Run again, it waits for the open PR or only
   waits and tags."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.bump :as bump]
            [wagoe.tools.check-versions :as check-versions]
            [wagoe.tools.release-check :as release-check]))

(defn release-changelog
  "CHANGELOG text with `[Unreleased]` renamed to `version`, dated `date`, and a
   new empty `[Unreleased]` above it. nil when there is nothing unreleased."
  [text version date]
  (when (seq (some-> (release-check/changelog-section text "Unreleased") str/trim))
    (str/replace-first text "## [Unreleased]" (str "## [Unreleased]\n\n## [" version "] — " date))))

(def ^:private upgrades-path "libs/wagoe-cli/resources/wagoe/cli/upgrades.edn")

(defn release-upgrades
  "upgrades.edn text with each `:since \"Unreleased\"` naming `version`: the
   deprecation's `### Deprecated` entry moves to that heading with the rest.
   1.3.0's CI failed on the one left behind."
  [text version]
  (str/replace text #"(:since\s+)\"Unreleased\"" (str "$1\"" version "\"")))

(defn preflight
  "Reasons not to start a release, from facts about the checkout. `pr-state`:
   GitHub's state of the newest PR from `release/<version>`; once one is open
   or merged, only waiting and tagging remain."
  [{:keys [version branch dirty? behind ahead pr-state tagged? in-changelog?]}]
  (cond-> []
    (not (bump/valid-version? version)) (conj (str "not a suite version: " (pr-str version) " (no leading v)"))
    (check-versions/snapshot? version)  (conj (str version " is not tagged: bb bump it on main and publish with "
                                                   "gh workflow run publish.yml --ref main"))
    (not= "main" branch)                (conj (str "on " branch ", not main"))
    dirty?                              (conj "the working tree has changes; commit or stash them")
    (and (pos? behind) (not (#{"OPEN" "MERGED"} pr-state)))
    (conj "behind origin/main; git pull --rebase first")
    (pos? ahead)                        (conj "ahead of origin/main; open a PR for those commits first")
    tagged?                             (conj (str version " is already tagged on origin"))
    (and in-changelog? (not= "MERGED" pr-state))
    (conj (str "CHANGELOG.md already has a " version " section"))))

(defn next-step
  "What a run does before it tags, from `pr-state` as in `preflight`: :merge
   waits for an open release PR, :tag a merged one, :prepare opens one."
  [pr-state]
  (case pr-state
    "MERGED" :tag
    "OPEN"   :merge
    :prepare))

(defn verdict
  "What CI on main says about a commit, from its workflow runs: :green, :red,
   :superseded when every run was cancelled by a newer push, or :pending. A
   merge-queue run counts only when it passed: a queue push runs CI only on its
   last commit, and ci.yml trusts that run as fully tested."
  [runs]
  (let [runs      (remove #(and (= "merge_group" (:event %)) (not= "success" (:conclusion %))) runs)
        done      (keep #(when (= "completed" (:status %)) (:conclusion %)) runs)
        running?  (some #(not= "completed" (:status %)) runs)
        decided   (remove #{"cancelled"} done)]
    (cond (some #{"success"} decided) :green
          (seq decided)               :red
          (and (seq done) (not running?)) :superseded
          :else                       :pending)))

(defn- out [& args]
  (str/trim (:out (apply process/shell {:out :string} args))))

(defn- ok? [& args]
  (zero? (:exit (apply process/shell {:out :string :err :string :continue true} args))))

(defn- ci-runs [sha]
  (mapcat #(:workflow_runs
            (json/parse-string
             (apply out "gh" "api" "--method" "GET" "repos/{owner}/{repo}/actions/workflows/ci.yml/runs"
                    "-f" (str "head_sha=" sha) %)
             true))
          [["-f" "event=push" "-f" "branch=main"] ["-f" "event=merge_group"]]))

(defn- fail! [& lines]
  (throw (ex-info (str/join "\n" lines) {:type :release-refused})))

(def ^:private wait-minutes 45)

(defn- poll
  "Calls `step` every 30 s until it returns a truthy value; past `wait-minutes`,
   fails with `timeout-lines`."
  [step & timeout-lines]
  (let [deadline (+ (System/currentTimeMillis) (* wait-minutes 60 1000))]
    (loop []
      (or (step)
          (if (> (System/currentTimeMillis) deadline)
            (apply fail! timeout-lines)
            (do (Thread/sleep 30000) (recur)))))))

(defn- wait-for-green [sha]
  (println (str "Waiting for CI on main to pass on " sha " (up to " wait-minutes " min)..."))
  (let [again "Rerun that ci.yml run (gh run rerun <id>), then bb release again."]
    (poll #(case (verdict (ci-runs sha))
             :green      (do (println (ansi/green "CI passed.")) :green)
             :red        (fail! (str "CI failed on " sha "; no tag.") again)
             :superseded (fail! (str "A newer push cancelled CI on " sha "; no tag.") again)
             :pending    nil)
          (str "CI reached no verdict on " sha " within " wait-minutes " min; no tag.")
          "Run bb release again once it has.")))

(defn- prepare! [version]
  (let [text (or (release-changelog (slurp "CHANGELOG.md") version (str (java.time.LocalDate/now)))
                 (fail! "CHANGELOG.md has nothing under [Unreleased] to release."))]
    (try
      (spit "CHANGELOG.md" text)
      (spit upgrades-path (release-upgrades (slurp upgrades-path) version))
      (process/shell "bb" "bump" version)
      (process/shell "bb" "check:versions")
      (process/shell "bb" "check:changelog")
      (process/shell "bb" "release:check" version)
      (process/shell "git" "commit" "--quiet" "-am" (str "Release " version))
      (catch Exception e
        (fail! (str "release aborted before its commit: " (ex-message e))
               "Undo the bump with: git restore .")))))

(defn- pr-state [branch]
  (not-empty (out "gh" "pr" "list" "--head" branch "--state" "all" "--limit" "1"
                  "--json" "state" "--jq" ".[0].state // empty")))

(defn- merge-commit [branch]
  (or (not-empty (out "gh" "pr" "list" "--head" branch "--state" "merged" "--limit" "1"
                      "--json" "mergeCommit" "--jq" ".[0].mergeCommit.oid // empty"))
      (fail! (str "found no merged release PR from " branch "; no tag."))))

(defn- version-at
  "The suite version a commit names, read from git, not the working tree."
  [sha]
  (:version (first (check-versions/matches-in (out "git" "show" (str sha ":libs/core/build.clj"))
                                              #"\(def version \"[^\"]+\""))))

(defn- open-pr! [version branch]
  (process/shell "git" "switch" "--quiet" "-C" branch)
  (try (prepare! version)
       (catch clojure.lang.ExceptionInfo e
         (fail! (ex-message e) "Then go back with: git switch main")))
  (try
    ;; No open PR uses this branch, so a leftover from an aborted run may go.
    (process/shell "git" "push" "--quiet" "--force" "-u" "origin" branch)
    (process/shell "gh" "pr" "create" "--base" "main" "--head" branch "--title" (str "Release " version)
                   "--body" (str "`bb release " version "`: the changelog move and version bump."))
    (finally (process/shell "git" "switch" "--quiet" "main")))
  (println "It touches code, so a core member adds the `approved` label; bb release never does."))

(defn- wait-for-merge
  "Turns on auto-merge and waits for the release PR to merge."
  [branch]
  ;; Again on a rerun: an earlier run may have stopped before it.
  (when (= "OPEN" (pr-state branch))
    (process/shell "gh" "pr" "merge" branch "--auto" "--merge"))
  (println (str "Waiting for the release PR from " branch " to merge (up to " wait-minutes " min)..."))
  (poll #(case (pr-state branch)
           "MERGED" (do (println (ansi/green "The release PR merged.")) :merged)
           "CLOSED" (fail! (str "the release PR from " branch " closed without merging; no tag.")
                           "Reopen it, or run bb release again to open a new one.")
           nil)
        "The release PR has not merged yet; no tag."
        "It merges by itself once it has the `approved` label and green CI; run bb release again then."
        "If it failed in the merge queue or conflicts: close it, git pull, and run bb release again."))

(defn- release! [version]
  ;; --force: CI moves the `green` tag, and a stale local one refused the fetch.
  (process/shell "git" "fetch" "--quiet" "--force" "--tags" "origin" "main")
  (let [subject  (str "Release " version)
        branch   (str "release/" version)
        [ahead behind] (map parse-long (str/split (out "git" "rev-list" "--left-right" "--count" "HEAD...origin/main") #"\s+"))
        state    (pr-state branch)
        problems  (preflight {:version       version
                              :branch        (out "git" "rev-parse" "--abbrev-ref" "HEAD")
                              :dirty?        (seq (out "git" "status" "--porcelain"))
                              :behind        behind
                              :ahead         ahead
                              :pr-state      state
                              :tagged?       (seq (out "git" "ls-remote" "--tags" "origin" (str "refs/tags/" version)))
                              :in-changelog? (some? (release-check/changelog-section (slurp "CHANGELOG.md") version))})]
    (when (seq problems)
      (apply fail! "refusing to release:" (map #(str "  - " %) problems)))
    ;; main has no bypass: the release commit reaches it through a PR (ADR-045).
    (case (next-step state)
      :prepare (do (open-pr! version branch) (wait-for-merge branch))
      :merge   (wait-for-merge branch)
      :tag     nil)
    ;; The release PR's own merge commit: later merges are not in its changelog.
    ;; A green run there means check:versions passed on the code the tag names.
    (let [sha (merge-commit branch)]
      (process/shell "git" "fetch" "--quiet" "origin" "main")
      (when-not (= version (version-at sha))
        (fail! (str "the merge commit " sha " does not hold version " version "; no tag.")))
      (wait-for-green sha)
      (when-not (ok? "git" "rev-parse" "-q" "--verify" (str "refs/tags/" version))
        (process/shell "git" "tag" "-a" version "-m" subject sha))
      (when-not (= sha (out "git" "rev-parse" (str version "^{commit}")))
        (fail! (str "a local tag " version " points elsewhere; delete it with git tag -d " version)))
      (process/shell "git" "push" "origin" (str "refs/tags/" version))
      (println (ansi/green (str "Tagged " version " on " sha "; publish.yml takes it from here.")))
      (let [[major minor] (map parse-long (str/split version #"\."))]
        (when-not (str/includes? version "-")
          (println (str "Next: bb bump " major "." (inc minor) ".0-SNAPSHOT in a PR.")))))))

(defn -main [& [version]]
  (try
    (release! version)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        ;; A failed command throws with no message; name the command instead.
        (println (ansi/red (str "bb release: " (or (not-empty (ex-message e))
                                                   (some->> (:cmd (ex-data e)) (str/join " ") (str "failed: "))
                                                   "failed")))))
      (System/exit 1))))
