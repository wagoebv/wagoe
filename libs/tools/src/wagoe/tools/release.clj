(ns wagoe.tools.release
  "`bb release <version>`: gate 2 of trunk-based development (BOU-1029). A
   release tag lands only on a commit CI passed on main.

   From a clean, up-to-date main it moves `[Unreleased]` to the version, bumps,
   and pushes `Release <version>`. It then tags the tip of main once CI passed
   there and the tip still holds that version. Once the release commit is on
   main, running it again only waits and tags, so it survives red CI fixed on
   main and runs cancelled by newer pushes."
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
  "Reasons not to start a release, from facts about the checkout. `released?`:
   the release commit is on origin/main already, so only waiting and tagging
   remain; `pending-commit?`: it is the one local commit not yet pushed."
  [{:keys [version branch dirty? behind ahead released? pending-commit? tagged? in-changelog?]}]
  (cond-> []
    (not (bump/valid-version? version)) (conj (str "not a suite version: " (pr-str version) " (no leading v)"))
    (check-versions/snapshot? version)  (conj (str version " is not tagged: bb bump it on main and publish with "
                                                   "gh workflow run publish.yml --ref main"))
    (not= "main" branch)                (conj (str "on " branch ", not main"))
    dirty?                              (conj "the working tree has changes; commit or stash them")
    (pos? behind)                       (conj "behind origin/main; git pull --rebase first")
    (and (pos? ahead) (not pending-commit?))
    (conj "ahead of origin/main with commits that are not the release; push them first")
    tagged?                             (conj (str version " is already tagged on origin"))
    (and in-changelog? (not released?) (not pending-commit?))
    (conj (str "CHANGELOG.md already has a " version " section"))))

(defn verdict
  "What CI on main says about a commit, from its workflow runs: :green, :red,
   :superseded when every run was cancelled by a newer push, or :pending."
  [runs]
  (let [done      (keep #(when (= "completed" (:status %)) (:conclusion %)) runs)
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
  (:workflow_runs
   (json/parse-string
    (out "gh" "api" "--method" "GET" "repos/{owner}/{repo}/actions/workflows/ci.yml/runs"
         "-f" (str "head_sha=" sha) "-f" "event=push" "-f" "branch=main")
    true)))

(defn- fail! [& lines]
  (throw (ex-info (str/join "\n" lines) {:type :release-refused})))

(def ^:private wait-minutes 45)

(defn- wait-for-green [sha]
  (println (str "Waiting for CI on main to pass on " sha " (up to " wait-minutes " min)..."))
  (let [deadline (+ (System/currentTimeMillis) (* wait-minutes 60 1000))
        again    "Fix main (revert first), git pull, and run bb release again: it tags the tip of main."]
    (loop []
      (case (verdict (ci-runs sha))
        :green      (println (ansi/green "CI passed."))
        :red        (fail! (str "CI failed on " sha "; no tag.") again)
        :superseded (fail! (str "A newer push cancelled CI on " sha "; no tag.")
                           "git pull, and run bb release again once CI ran on the tip of main.")
        :pending    (if (> (System/currentTimeMillis) deadline)
                      (fail! (str "CI reached no verdict on " sha " within " wait-minutes " min; no tag.")
                             "Run bb release again once it has.")
                      (do (Thread/sleep 30000) (recur)))))))

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
      (process/shell "git" "commit" "--quiet" "-am" (str "Release " version)
                     "-m" "Review-skipped: version bump and changelog move by bb release")
      (catch Exception e
        (fail! (str "release aborted before its commit: " (ex-message e))
               "Undo the bump with: git restore .")))))

(defn- release! [version]
  ;; --force: CI moves the `green` tag, and a stale local one refused the fetch.
  (process/shell "git" "fetch" "--quiet" "--force" "--tags" "origin" "main")
  (let [subject  (str "Release " version)
        [ahead behind] (map parse-long (str/split (out "git" "rev-list" "--left-right" "--count" "HEAD...origin/main") #"\s+"))
        released? (seq (out "git" "log" "origin/main" "--format=%H" "-1" "--fixed-strings" (str "--grep=" subject)))
        pending?  (and (= 1 ahead) (zero? behind) (= subject (out "git" "log" "-1" "--format=%s")))
        problems  (preflight {:version         version
                              :branch          (out "git" "rev-parse" "--abbrev-ref" "HEAD")
                              :dirty?          (seq (out "git" "status" "--porcelain"))
                              :behind          behind
                              :ahead           ahead
                              :released?       released?
                              :pending-commit? pending?
                              :tagged?         (seq (out "git" "ls-remote" "--tags" "origin" (str "refs/tags/" version)))
                              :in-changelog?   (some? (release-check/changelog-section (slurp "CHANGELOG.md") version))})]
    (when (seq problems)
      (apply fail! "refusing to release:" (map #(str "  - " %) problems)))
    (when-not (or released? pending?)
      (prepare! version))
    (when-not released?
      ;; CI on main tests this commit fully before it is tagged; the pre-push
      ;; run would test every library a bump touches, for 25 minutes.
      (process/shell "git" "push" "--no-verify" "origin" "HEAD:main"))
    ;; The tip of main, with the version still in place: a green run there
    ;; means check:versions passed on the code the tag names.
    (when-not (= version (:version (first (bump/current-sources))))
      (fail! (str "the tip of main no longer holds version " version "; no tag.")))
    (let [sha (out "git" "rev-parse" "HEAD")]
      (wait-for-green sha)
      (when-not (ok? "git" "rev-parse" "-q" "--verify" (str "refs/tags/" version))
        (process/shell "git" "tag" "-a" version "-m" subject sha))
      (when-not (= sha (out "git" "rev-parse" (str version "^{commit}")))
        (fail! (str "a local tag " version " points elsewhere; delete it with git tag -d " version)))
      (process/shell "git" "push" "origin" (str "refs/tags/" version))
      (println (ansi/green (str "Tagged " version " on " sha "; publish.yml takes it from here.")))
      (let [[major minor] (map parse-long (str/split version #"\."))]
        (when-not (str/includes? version "-")
          (println (str "Next: bb bump " major "." (inc minor) ".0-SNAPSHOT on main.")))))))

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
