(ns wagoe.tools.release
  "`bb release <version>`: gate 2 of trunk-based development (BOU-1029). A
   release tag lands only on a commit CI passed on main.

   On main, clean and up to date it bumps the version, turns `[Unreleased]`
   into the version and pushes `Release <version>`. Then it waits for CI on
   main to pass on that commit and pushes the tag, which publish.yml picks up.
   Run it again after a failure: it resumes at the wait."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.bump :as bump]))

(defn release-changelog
  "CHANGELOG text with `[Unreleased]` renamed to `version`, dated `date`, and a
   new empty `[Unreleased]` above it. nil when there is nothing unreleased."
  [text version date]
  (let [heading "## [Unreleased]"
        start   (str/index-of text heading)
        body    (when start
                  (let [after (+ start (count heading))
                        end   (or (str/index-of text "\n## [" after) (count text))]
                    (str/trim (subs text after end))))]
    (when (seq body)
      (str (subs text 0 start) heading "\n\n## [" version "] — " date
           (subs text (+ start (count heading)))))))

(defn preflight
  "Reasons not to start a release, from facts about the checkout."
  [{:keys [version branch dirty? behind? ahead? tag-exists?]}]
  (cond-> []
    (not (bump/valid-version? version)) (conj (str "not a suite version: " (pr-str version) " (no leading v)"))
    (not= "main" branch)                (conj (str "on " branch ", not main"))
    dirty?                              (conj "the working tree has changes; commit or stash them")
    behind?                             (conj "behind origin/main; git pull --rebase first")
    ahead?                              (conj "ahead of origin/main with commits that are not the release; push them first")
    tag-exists?                         (conj (str version " is already tagged"))))

(defn verdict
  "What CI on main says about a commit, from its runs' `status:conclusion`
   lines: :green, :red, or :pending. A cancelled run was superseded and says
   nothing."
  [statuses]
  (let [done (remove #(str/ends-with? % ":cancelled") (filter #(str/starts-with? % "completed:") statuses))]
    (cond (some #{"completed:success"} done) :green
          (seq done)                         :red
          :else                              :pending)))

(defn- sh
  "Run a command, stream its output, throw on failure."
  [& args]
  (apply process/shell args))

(defn- out [& args]
  (str/trim (:out (apply process/shell {:out :string} args))))

(defn- quiet-ok? [& args]
  (zero? (:exit (apply process/shell {:out :string :err :string :continue true} args))))

(defn- ci-statuses [sha]
  (let [runs (json/parse-string
              (out "gh" "api" "--method" "GET" "repos/{owner}/{repo}/actions/workflows/ci.yml/runs"
                   "-f" (str "head_sha=" sha) "-f" "event=push" "-f" "branch=main")
              true)]
    (map #(str (:status %) ":" (or (:conclusion %) "none")) (:workflow_runs runs))))

(defn- fail! [& lines]
  (binding [*out* *err*]
    (println (ansi/red (str "bb release: " (first lines))))
    (run! println (rest lines)))
  (System/exit 1))

(def ^:private wait-minutes 45)

(defn- wait-for-green [sha]
  (println (str "Waiting for CI on main to pass on " sha " (up to " wait-minutes " min)..."))
  (let [deadline (+ (System/currentTimeMillis) (* wait-minutes 60 1000))]
    (loop []
      (case (verdict (ci-statuses sha))
        :green   (println (ansi/green "CI passed."))
        :red     (fail! (str "CI failed on " sha "; no tag.")
                        "Fix main (revert first), then release from the new commit.")
        :pending (if (> (System/currentTimeMillis) deadline)
                   (fail! (str "CI reached no verdict on " sha " within " wait-minutes " min; no tag.")
                          "If a newer push cancelled it, re-run CI on that commit, then bb release again.")
                   (do (Thread/sleep 30000) (recur)))))))

(defn -main [& [version]]
  (sh "git" "fetch" "--quiet" "--tags" "origin" "main")
  (let [subject  (out "git" "log" "-1" "--format=%s")
        resuming (= subject (str "Release " version))
        counts   (out "git" "rev-list" "--left-right" "--count" "HEAD...origin/main")
        [ahead behind] (map parse-long (str/split counts #"\s+"))
        problems (preflight {:version     version
                             :branch      (out "git" "rev-parse" "--abbrev-ref" "HEAD")
                             :dirty?      (seq (out "git" "status" "--porcelain"))
                             :behind?     (pos? behind)
                             ;; Resuming: the release commit is pushed already.
                             :ahead?      (and (pos? ahead) (not resuming))
                             :tag-exists? (quiet-ok? "git" "rev-parse" "-q" "--verify" (str "refs/tags/" version))})]
    (when (seq problems)
      (apply fail! "refusing to release:" (map #(str "  - " %) problems)))
    (when-not resuming
      (sh "bb" "bump" version)
      (let [text (or (release-changelog (slurp "CHANGELOG.md") version (str (java.time.LocalDate/now)))
                     (fail! "CHANGELOG.md has nothing under [Unreleased] to release."))]
        (spit "CHANGELOG.md" text))
      (sh "bb" "check:versions")
      (sh "git" "commit" "--quiet" "-am" (str "Release " version))
      ;; CI on main tests this commit fully before it is tagged; the pre-push
      ;; run would test every library a bump touches, for 25 minutes.
      (sh "git" "push" "--no-verify" "origin" "HEAD:main"))
    (when (and resuming (pos? ahead))
      (sh "git" "push" "--no-verify" "origin" "HEAD:main"))
    (let [sha (out "git" "rev-parse" "HEAD")]
      (wait-for-green sha)
      (sh "git" "tag" "-a" version "-m" (str "Release " version) sha)
      (sh "git" "push" "origin" (str "refs/tags/" version))
      (println (ansi/green (str "Tagged " version " on " sha "; publish.yml takes it from here."))))))
