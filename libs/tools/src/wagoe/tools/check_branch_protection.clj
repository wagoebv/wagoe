#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_branch_protection.clj
;;
;; Verifies that the one status check branch protection requires — the summary
;; job — actually depends on every job that can fail.
;;
;; BOU-277: branch protection required 13 contexts — lint, test-core,
;; test-observability … — that no job has ever reported. They are the *job
;; keys* from ci.yml, and GitHub reports a check under the job's `name:`. Every
;; job here has had one since Phase 0, so those contexts never matched anything.
;;
;; The failure mode is why this needs a gate: a required check that never
;; reports is indistinguishable from one that always passes. `enforce_admins`
;; was off, so the owner's override absorbed 13 permanently-pending checks on
;; every merge. Nothing failed. Nothing said anything.
;;
;; The first version of this checker read branch protection over the API and
;; reconciled all 14 contexts. That needed an admin-scoped token stored in the
;; repository — a standing credential — and 12 of those 14 contexts were
;; redundant anyway, because the summary job already `needs:` them.
;;
;; So the fix is structural rather than supervisory: require one context, and
;; check here that it covers everything. Adding a library changes ci.yml and
;; nothing else. No API, no token, and it runs on every pull request rather than
;; nightly.

(ns wagoe.tools.check-branch-protection
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [wagoe.tools.ansi :as ansi]))

(def summary-job-name
  "The single context branch protection requires.

   Asserted against ci.yml rather than assumed: renaming the summary job
   without changing branch protection recreates the original defect exactly —
   a required context nothing reports."
  "All Tests Passed")

(def ^:private summary-job-key :test-summary)

(defn ci-workflow
  "Parsed ci.yml. Throws when it cannot be found.

   Deliberately not nil-tolerant: a lockstep check that quietly passes when it
   cannot read the file it compares against is the same stopped-checking
   failure it exists to prevent (BOU-250)."
  []
  (let [cwd        (System/getProperty "user.dir")
        candidates [(io/file cwd ".github" "workflows" "ci.yml")
                    (io/file cwd ".." ".." ".github" "workflows" "ci.yml")]]
    (if-let [f (some (fn [^java.io.File f] (when (.exists f) f)) candidates)]
      (yaml/parse-string (slurp f))
      (throw (ex-info "ci.yml not found — cannot verify the summary's coverage"
                      {:cwd cwd :tried (mapv str candidates)})))))

(defn job-context
  "The status-check context GitHub reports for a job.

   `name:` when set, the job key otherwise. Reading the key and assuming it is
   the context is exactly how branch protection came to require jobs that do
   not exist."
  [job-key job]
  (or (:name job) (name job-key)))

(defn- needs-of
  "A job's dependencies as a set of job-key strings.

   `needs:` is a list in most jobs and a bare string in a few — YAML allows
   both, and treating the string as a sequence yields its characters."
  [job]
  (let [n (:needs job)]
    (cond (nil? n)    #{}
          (string? n) #{n}
          :else       (set (map name n)))))

(defn- disabled?
  "Whether a job can never run, so nothing it does can be required.

   `if: false` is how e2e is parked: it needs a live server on :3100, which CI
   does not start, and it reports as skipped. Requiring a job that never runs
   would recreate the original defect — a context that can never be satisfied."
  [job]
  (false? (:if job)))

(defn summary-covers
  "{:summary-name s :missing [job-keys] :disabled [job-keys]} for `workflow`.

   Coverage is transitive. A job the summary reaches through another job is
   guarded: `warm-deps` is not in the summary's `needs:`, but `lint` needs it,
   so a warm-deps failure fails lint and therefore the summary. A flat check
   reported it as unguarded, which would have pushed a redundant entry into the
   `needs:` list to satisfy the wrong model.

   Public so a test can drive it; `-main` exits the process."
  [workflow]
  (let [jobs    (:jobs workflow)
        summary (get jobs summary-job-key)
        graph   (into {} (for [[k v] jobs] [(name k) (needs-of v)]))
        reached (loop [seen #{} frontier (needs-of summary)]
                  (if (empty? frontier)
                    seen
                    (let [nxt (remove seen frontier)]
                      (recur (into seen nxt)
                             (mapcat #(get graph % #{}) nxt)))))
        parked  (set (for [[k v] jobs :when (disabled? v)] (name k)))
        all     (set (keys graph))]
    {:summary-name (when summary (job-context summary-job-key summary))
     :disabled     (sort parked)
     :missing      (sort (set/difference all reached parked #{(name summary-job-key)}))}))

;; =============================================================================
;; Triggers — coverage is worthless if the workflow never starts
;; =============================================================================
;;
;; BOU-315: everything above verifies that the required context covers every job
;; that can fail. None of it means anything on a run that does not happen.
;; ci.yml triggered on `push:` alone, so a fork PR started zero jobs — and a
;; required context reported by a job that never ran sits *pending*, which is
;; the same indistinguishable-from-passing shape BOU-277 was about.

(defn workflow-triggers
  "The parsed `on:` block, as a map keyed by event.

   YAML 1.1 reads the bare word `on` as the boolean true, so SnakeYAML gives
   this key back as `true` rather than `:on`; a quoted `\"on\":` stays a string.
   Reading only `:on` would find nothing and conclude the workflow has no
   triggers — firing this gate on a correct file."
  [workflow]
  (or (get workflow :on)
      (get workflow true)
      (get workflow "on")
      {}))

(defn trigger-findings
  "Reasons `workflow` would not run the full pipeline on every pull request.

   Empty means every PR — fork or not — starts the pipeline exactly once."
  [workflow]
  (let [triggers (workflow-triggers workflow)
        push     (find triggers :push)]
    (cond-> []
      (empty? triggers)
      (conj (str "no `on:` block at all — nothing triggers this workflow. "
                 "(If the file parses, this is a reader bug, not a config one.)"))

      (and (seq triggers) (not-any? #(contains? triggers %) [:pull_request :pull-request]))
      (conj (str "no `pull_request:` trigger — a pull request from a fork runs "
                 "zero jobs, so the required context stays pending rather than "
                 "failing, and nothing blocks the merge."))

      (and push (not (seq (:branches (val push)))))
      (conj (str "`push:` is unscoped, so it fires on every branch. Alongside "
                 "`pull_request:` that runs the whole pipeline twice for a "
                 "same-repo PR, under two concurrency groups that cannot cancel "
                 "each other. Scope it to `branches: [main]`.")))))

(defn -main [& _args]
  (let [wf (ci-workflow)
        {:keys [missing summary-name disabled]} (summary-covers wf)
        trigger-problems (trigger-findings wf)]

    (when (seq trigger-problems)
      (println (ansi/red "ci.yml does not run on every pull request:"))
      (println)
      (doseq [p trigger-problems] (println (str "  " (ansi/red "✗") " " p)))
      (println)
      (println "Job coverage below is only as good as the runs that start.")
      (System/exit 1))

    (when-not (= summary-job-name summary-name)
      (println (ansi/red "The summary job is not named") (str "\"" summary-job-name "\""))
      (println)
      (println (str "  ci.yml calls it " (pr-str summary-name)
                    ", and branch protection requires \"" summary-job-name "\"."))
      (println "  A required context nothing reports can never be satisfied — and,")
      (println "  while something absorbs it, can never fail either.")
      (System/exit 1))

    (if (seq missing)
      (do
        (println (ansi/red "Jobs that cannot block a merge:"))
        (println)
        (doseq [m missing]
          (println (str "  " (ansi/red m) " — not in " summary-job-name "'s needs")))
        (println)
        (println (str "Branch protection requires exactly one context, \"" summary-job-name
                      "\"."))
        (println "A job outside its `needs:` can fail while that context still goes")
        (println "green, so nothing stops the merge.")
        (println)
        (println (str (count missing) " unguarded job(s)."))
        (System/exit 1))
      (do
        (when (seq disabled)
          (println (ansi/yellow "Jobs parked with `if: false`, so not required:"))
          (doseq [d disabled] (println (str "  " d)))
          (println))
        (println (ansi/green "Branch protection check passed.")
                 (str "\"" summary-job-name "\" transitively covers every job in "
                      "ci.yml except " (count disabled) " parked."))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
