#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_branch_protection.clj
;;
;; Reconciles main's required status checks against the jobs CI actually emits.
;;
;; BOU-277: branch protection required 13 contexts — lint, test-core,
;; test-observability … — that no job has ever reported. They are the *job
;; keys* from ci.yml, and GitHub reports a check under the job's `name:` when
;; one is set. Every job here has had one since Phase 0, so those contexts
;; never matched anything.
;;
;; The failure mode is why this needs a gate rather than a one-time fix: a
;; required check that never reports is indistinguishable from one that always
;; passes. `enforce_admins` was off, so the owner's override absorbed 13
;; permanently-pending checks on every merge, and every PR that has landed on
;; main did so by bypassing them. Nothing failed. Nothing said anything.

(ns wagoe.tools.check-branch-protection
  (:require [babashka.process :refer [shell]]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]))

(def ^:private repo "wagoebv/wagoe")
(def ^:private branch "main")

;; ---------------------------------------------------------------------------
;; Which workflows can produce a check on a pull request
;; ---------------------------------------------------------------------------

(defn pr-visible-workflow?
  "Whether `workflow` (parsed YAML) emits checks on a pull request.

   Only these can be required: a context that never appears on a PR can never
   be satisfied, which is the defect this gate exists to catch — reintroduced
   by requiring something from a nightly workflow.

   `on: push` with no filter, or any `pull_request`, qualifies. These do not:

   - a push filtered to tags — publish.yml runs on release tags
   - a push filtered to named branches — branch-protection.yml runs on main
     only, so a PR branch never triggers it. That filter is what keeps its
     admin-scoped token away from branch-controlled code, and treating it as
     PR-visible would have made this gate demand a context that PRs never see.
   - `schedule` and `workflow_dispatch` — brand-canary.yml and
     first-run-matrix.yml are nightly

   That last point settles what looked like the hard case here. The matrix
   workflow generates job names per axis (`Smoke — ${{ matrix.image }}`), which
   no static parse can expand; it does not matter, because those jobs cannot be
   required in the first place."
  [workflow]
  ;; YAML 1.1 parses a bare `on:` key as boolean true, so the trigger block is
  ;; under `true`, not `:on`.
  (let [on (or (:on workflow) (get workflow true))]
    (boolean
     (or (contains? on :pull_request)
         ;; `contains?`, not `when-let`: `push:` with no filter parses to nil,
         ;; and `when-let` on nil short-circuits — which read as "no push
         ;; trigger" and made every workflow invisible, so the gate found
         ;; nothing to compare against and reported CI as emitting no contexts
         ;; at all.
         (and (contains? on :push)
              (let [push (:push on)]
                (or (nil? push)
                    (and (map? push)
                         (not (:tags push))
                         ;; A named-branch filter cannot match a PR branch.
                         ;; `branches: ['**']` and the like would, but nothing
                         ;; here uses one; a wildcard is treated as visible.
                         (let [bs (:branches push)]
                           (or (nil? bs)
                               (some #(str/includes? % "*") bs)))))))))))

;; ---------------------------------------------------------------------------
;; Contexts a workflow emits
;; ---------------------------------------------------------------------------

(defn job-context
  "The status-check context GitHub reports for a job.

   `name:` when set, the job key otherwise. Reading the key and assuming it is
   the context is exactly how the required set came to name jobs that do not
   exist.

   Verified against a real PR: ci.yml declares 51 jobs and GitHub reported 51
   checks, with no difference in either direction."
  [job-key job]
  (or (:name job) (name job-key)))

(defn- dynamic-context?
  "Whether a context contains an unexpanded expression, e.g. a matrix axis.

   `Smoke — ${{ matrix.image }}` becomes one check per image at run time, and
   the literal string is never reported. Such a job cannot usefully be
   required, so it is excluded from the emitted set rather than compared as-is."
  [context]
  (str/includes? context "${{"))

(defn workflow-contexts
  "Contexts a parsed workflow emits on a pull request, as a set."
  [workflow]
  (if-not (pr-visible-workflow? workflow)
    #{}
    (->> (:jobs workflow)
         (map (fn [[k j]] (job-context k j)))
         (remove dynamic-context?)
         set)))

(defn workflow-files
  "Every workflow file under .github/workflows."
  []
  (let [dir (io/file ".github" "workflows")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(and (.isFile %)
                         (or (str/ends-with? (.getName %) ".yml")
                             (str/ends-with? (.getName %) ".yaml"))))
           (sort-by #(.getName %))))))

(defn emitted-contexts
  "Contexts every PR-visible workflow emits: {context #{workflow-file}}.

   Public so a test can drive it; `-main` exits the process."
  ([] (emitted-contexts (workflow-files)))
  ([files]
   (reduce (fn [acc f]
             (let [wf (yaml/parse-string (slurp f))]
               (reduce #(update %1 %2 (fnil conj #{}) (.getName f))
                       acc
                       (workflow-contexts wf))))
           {}
           files)))

;; ---------------------------------------------------------------------------
;; What branch protection requires
;; ---------------------------------------------------------------------------

(defn required-contexts
  "Required status checks on `main`, or `:unavailable` when they cannot be read.

   Reading branch protection needs an admin-scoped token. Returning
   `:unavailable` rather than an empty set is deliberate: empty would mean
   \"nothing is required\", and this gate would then report every CI job as
   unprotected — a wall of noise pointing at the wrong thing, from a checker
   that simply could not see."
  []
  (let [{:keys [exit out]} (shell {:out :string :err :string :continue true}
                                  "gh" "api"
                                  (str "repos/" repo "/branches/" branch "/protection")
                                  ;; `[]` streams one context per line. Without
                                  ;; it the whole JSON array arrives on one
                                  ;; line and split-lines yields a single
                                  ;; "context" that is the array literal.
                                  "--jq" ".required_status_checks.contexts[]")]
    (if (zero? exit)
      (set (remove str/blank? (str/split-lines out)))
      :unavailable)))

;; ---------------------------------------------------------------------------
;; Reconciliation
;; ---------------------------------------------------------------------------

(defn reconcile
  "Compare required contexts against emitted ones.

   :phantom  required, but no PR-visible job emits it — the BOU-277 defect.
             A gate that can never be satisfied, or never fail.
   :unguarded  a test job that is emitted but not required, so its failure
             cannot block a merge. Reported, not failed: which jobs to require
             is a policy choice, and `All Tests Passed` covers the test jobs
             through `needs:`."
  [required emitted]
  {:phantom   (sort (set/difference required (set (keys emitted))))
   :unguarded (sort (remove #(contains? required %)
                            (filter #(str/starts-with? % "Test wagoe/")
                                    (keys emitted))))})

(defn -main [& _args]
  (let [emitted  (emitted-contexts)
        required (required-contexts)]
    (when (= :unavailable required)
      (println (ansi/yellow "Cannot read branch protection for") (str repo "#" branch))
      (println "  `gh` needs a token with admin scope. Skipping — a checker that")
      (println "  cannot see its input must say so rather than draw a conclusion.")
      (System/exit 0))

    (let [{:keys [phantom unguarded]} (reconcile required emitted)]
      (when (seq unguarded)
        (println (ansi/yellow "Test jobs that cannot block a merge:"))
        (doseq [c unguarded] (println (str "  " c)))
        (println (str "  " (count unguarded) " not in the required set."
                      " `All Tests Passed` covers them via `needs:` — this is a"
                      " note, not a failure."))
        (println))

      (if (seq phantom)
        (do
          (println (ansi/red "Required status checks that no job emits:"))
          (println)
          (doseq [c phantom]
            (println (str "  " (ansi/red c) " — required, never reported")))
          (println)
          (println "These can never be satisfied, so every PR is blocked; and while")
          (println "something absorbs them (an admin override, say) they can never")
          (println "fail either. GitHub reports a check under the job's `name:`, not")
          (println "its key in the YAML.")
          (println)
          (println "Contexts CI does emit:")
          (doseq [c (sort (keys emitted))] (println (str "  " c)))
          (println)
          (println (str (count phantom) " phantom context(s)."))
          (System/exit 1))
        (do
          (println (ansi/green "Branch protection check passed.")
                   (str (count required) " required context(s), all emitted by CI."))
          (System/exit 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
