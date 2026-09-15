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
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

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

;; =============================================================================
;; Rule 2 — a deprecation nobody announced
;; =============================================================================

(def ^:private name-chars "a-zA-Z0-9*+!_'?<>=/.-")

(def deprecated-var-re
  "`^:deprecated` on a def form, and the name it applies to.

   Metadata can sit either side of the name (`defn ^:deprecated f` and
   `defn f ^:deprecated`), and Clojure accepts both."
  (re-pattern (str "\\(def[a-z-]*\\s+(?:\\^:deprecated\\s+([" name-chars "]+)"
                   "|([" name-chars "]+)\\s+\\^:deprecated)")))

(def deprecated-method-re
  "`^:deprecated` on a protocol method — `(^:deprecated old [this])`.

   The policy covers \"the var, protocol or method\", and the def-form pattern
   sees only the metadata next to the outer `defprotocol` name, so a method
   deprecated inside a live protocol was invisible to this gate. Keyed on the
   argument vector, which is what distinguishes a method declaration from the
   `(defn ^:deprecated f` the pattern above already reads."
  (re-pattern (str "\\(\\^:deprecated\\s+([" name-chars "]+)\\s*\\[")))

(defn deprecated-vars
  "The names carrying `^:deprecated` in `content`, vars and protocol methods.

   Comments and string literals are blanked first. A regex over raw text reads
   prose as code: this namespace's own docstring shows `defn ^:deprecated f` as
   an example, and the gate duly demanded a changelog entry for `f`."
  [content]
  (let [code (parsing/strip-comments-and-strings content)]
    (distinct (concat (->> (re-seq deprecated-var-re code)
                           (keep (fn [[_ a b]] (or a b))))
                      (map second (re-seq deprecated-method-re code))))))

(def deprecated-section-re
  "The body of every `### Deprecated` section in a changelog.

   Scoped rather than searching the whole file, because a bare name matches
   anywhere — an older `### Fixed` entry, or ordinary prose. Not scoped to
   `[Unreleased]` alone, though: a var announced three releases ago and still
   carrying the metadata is announced, and requiring it again every release
   would make the gate demand a lie."
  #"(?ms)^###\s+Deprecated\s*$(.*?)(?=^#{2,3}\s|\z)")

(defn announced-in
  "True when `changelog` names `var-name` in a `### Deprecated` section.

   Whole-identifier, so `create` does not match `create-user`. `/` and `.` are
   not boundaries, so the qualified `…adapters.db/enqueue-in-tx!` the entry
   actually writes still counts."
  [changelog var-name]
  (let [boundary "[a-zA-Z0-9*+!_'?<>=-]"
        re       (re-pattern (str "(?<!" boundary ")"
                                  (java.util.regex.Pattern/quote var-name)
                                  "(?!" boundary ")"))]
    (boolean (some #(re-find re (second %))
                   (re-seq deprecated-section-re changelog)))))

(defn undocumented-deprecations
  "Deprecated vars in shipped source that `changelog` never announces.

   Stability policy makes a deprecation three things — metadata, a changelog
   entry, and a replacement that exists. Only the metadata was ever checked, so
   `enqueue-in-tx!` carried `^:deprecated` for months while `CHANGELOG.md` had
   never used the heading (BOU-433). Pure, so a test can prove it still fires."
  [files read-file changelog]
  (for [path  files
        :when (shipped-source? path)
        var-name (deprecated-vars (read-file path))
        :when (not (announced-in changelog var-name))]
    {:rule :undocumented-deprecation :path path :var var-name}))

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

(defn tracked-source
  "Every tracked file, for the whole-repo deprecation rule."
  []
  (lines (git "ls-files")))

(defn- report-deprecations
  "Prints the unannounced deprecations and returns whether there were any."
  []
  (let [changelog (slurp changelog-path)
        findings  (undocumented-deprecations (tracked-source) slurp changelog)]
    (when (seq findings)
      (println (ansi/red (str "Deprecated vars that " changelog-path " never names:")))
      (println)
      (doseq [{:keys [path var]} findings]
        (println (str "  " (ansi/bold path) " — " (ansi/red var))))
      (println)
      (println (str "A deprecation is metadata, a `### Deprecated` entry, and a replacement "
                    "that exists.")))
    (boolean (seq findings))))

(defn -main [& _args]
  (if-let [base (base-ref)]
    (let [changed     (changed-since base)
          missing     (verdict changed (opted-out? base))
          undocumented (report-deprecations)]
      (when missing
        (let [{:keys [files]} missing]
          (when undocumented (println))
          (println (ansi/red (str "Shipped source changed with no " changelog-path " entry:")))
          (println)
          (doseq [f (take 10 files)] (println (str "  " f)))
          (when (> (count files) 10)
            (println (str "  … and " (- (count files) 10) " more")))
          (println)
          (println "Add an entry under [Unreleased] describing what a user of the")
          (println (str "framework will notice. If they will notice nothing, say so with "
                        opt-out-marker))
          (println "in a commit message on this branch.")))
      (if (or missing undocumented)
        (System/exit 1)
        (do
          (println (ansi/green (str changelog-path " is up to date with this branch, "
                                    "and announces every deprecation.")))
          (System/exit 0))))
    (do
      ;; No base means no comparison, and a gate that passes because it could
      ;; not look is the failure BOU-250 is about.
      (println (ansi/red "Neither origin/main nor main exists — cannot tell what this branch changed."))
      (println "Set CHANGELOG_BASE to the ref to compare against.")
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
