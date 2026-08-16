#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_changelog.clj
;;
;; A branch that changes shipped source must say so in CHANGELOG.md.
;;
;; Thirty pull requests merged between 2026-08-05 and 2026-08-16 without one
;; entry between them — a new library, a new launch mode, a removed config key
;; and a change to the order jobs are dispatched in. Nothing checked, which is
;; the same reason the adapter suites drifted: the work was being done, and the
;; only thing missing was something that noticed it hadn't been.
;;
;; Deliberately narrow. Tests, docs, CI and dev tooling are not shipped source
;; and do not need an entry; only `src/` under the repo root and under a library
;; does. An entry is required to exist, not to be any good — that is a reviewer's
;; job, and a gate that tried would be one more thing to work around.

(ns wagoe.tools.check-changelog
  (:require [clojure.string :as str]
            [babashka.process :as process]
            [wagoe.tools.ansi :as ansi]))

(def changelog-path "CHANGELOG.md")

(def opt-out-marker
  "In any commit message on the branch, this waives the requirement.

   Spelled out rather than inferred so it shows up in `git log` and in review."
  "[no changelog]")

(defn shipped-source?
  "Whether `path` is source that ends up in a published artifact.

   `src/` at the repo root, or `src/` inside a library. Everything else —
   `test/`, `dev/`, `docs/`, `.github/`, `resources/`, build files — is either
   not shipped or not something a user of the framework can observe."
  [path]
  (boolean (or (re-matches #"src/.*\.clj[cs]?" path)
               (re-matches #"libs/[^/]+/src/.*\.clj[cs]?" path))))

(defn verdict
  "Whether `changed-files` needs a CHANGELOG entry it does not have.

   Returns nil when the branch is fine, or a map describing what is missing.
   Pure, so a test can prove the gate still fires."
  [changed-files opted-out?]
  (let [shipped (filter shipped-source? changed-files)]
    (when (and (seq shipped)
               (not opted-out?)
               (not (some #{changelog-path} changed-files)))
      {:files (sort shipped)})))

(defn- git
  [& args]
  (let [{:keys [exit out err]} (apply process/shell
                                      {:out :string :err :string :continue true}
                                      "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed (exit " exit
                           ") — this gate cannot report a verdict without it")
                      {:exit exit :err (str/trim (or err ""))})))
    out))

(defn- base-ref
  "What to compare against. `CHANGELOG_BASE` wins, so CI can name the PR base."
  []
  (or (System/getenv "CHANGELOG_BASE")
      (let [candidates ["origin/main" "main"]]
        (first (filter #(zero? (:exit (process/shell
                                       {:out :string :err :string :continue true}
                                       "git" "rev-parse" "--verify" "--quiet" %)))
                       candidates)))))

(defn- lines [s] (remove str/blank? (str/split-lines s)))

(defn changed-since
  "Every file this branch touches relative to `base`, committed or not."
  [base]
  (let [merge-base (str/trim (git "merge-base" base "HEAD"))]
    (distinct (concat (lines (git "diff" "--name-only" (str merge-base "..HEAD")))
                      (lines (git "diff" "--name-only" "HEAD"))
                      (lines (git "diff" "--name-only" "--cached"))))))

(defn opted-out?
  "Whether any commit on the branch waives the requirement."
  [base]
  (let [merge-base (str/trim (git "merge-base" base "HEAD"))]
    (str/includes? (git "log" "--format=%B" (str merge-base "..HEAD"))
                   opt-out-marker)))

(defn -main [& _args]
  (if-let [base (base-ref)]
    (let [changed (changed-since base)]
      (if-let [{:keys [files]} (verdict changed (opted-out? base))]
        (do
          (println (ansi/red (str "Shipped source changed with no " changelog-path " entry:")))
          (println)
          (doseq [f (take 10 files)] (println (str "  " f)))
          (when (> (count files) 10)
            (println (str "  … and " (- (count files) 10) " more")))
          (println)
          (println "Add an entry under [Unreleased] describing what a user of the")
          (println (str "framework will notice. If they will notice nothing, say so with "
                        opt-out-marker))
          (println "in a commit message on this branch.")
          (System/exit 1))
        (do
          (println (ansi/green (str changelog-path " is up to date with this branch.")))
          (System/exit 0))))
    (do
      ;; No base means no comparison, and a gate that passes because it could
      ;; not look is the failure BOU-250 is about.
      (println (ansi/red "Neither origin/main nor main exists — cannot tell what this branch changed."))
      (println "Set CHANGELOG_BASE to the ref to compare against.")
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
