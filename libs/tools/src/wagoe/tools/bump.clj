#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/bump.clj
;;
;; Rewrite the suite version everywhere `check:versions` looks — and nowhere
;; else.
;;
;; BOU-316. The release bump was a global `find | xargs sed` copied out of the
;; README, and three things were wrong with it at once:
;;
;;   1. The snippet set OLD and NEW to the *same string*. A copy-paste run
;;      rewrote nothing and reported success; the verification step was
;;      `grep -r "$OLD"`, which then found nothing and agreed. Two steps, both
;;      green, neither having done anything.
;;   2. `sed -i ''` is macOS-only. The documented command fails on Linux and on
;;      the CI image, so the procedure could not be automated as written.
;;   3. It replaced every occurrence of the version string in every .clj, .edn,
;;      .md and .adoc. Any string that happened to equal the current version —
;;      a third-party dependency pin, a test fixture, a log line in an example —
;;      was silently rewritten with it.
;;
;; `check:versions` already discovers the locations by pattern, and BOU-317 gave
;; it documentation too. So the mutation reads that same discovery rather than
;; keeping its own list of places to edit. A second list is precisely the drift
;; the gate exists to catch; it should not need one in order to be fixed.

(ns wagoe.tools.bump
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.check-versions :as check-versions]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(defn valid-version?
  "Whether `v` is a suite version this may write.

   Anchored, so `v1.0.0-beta-6` is refused rather than accepted with the tag
   prefix baked into 96 locations. That would be worse than failing: every
   location would agree, so `check:versions` would go green on it."
  [v]
  (boolean (and (string? v)
                (re-matches (re-pattern (str check-versions/version-pattern)) v))))

;; =============================================================================
;; Mutation
;; =============================================================================

(defn rewrite
  "`content` with each finding's excerpt reset to `new-version`.

   Pure, and scoped to the line and the matched text a finding names — never to
   the file. That is the whole difference from the sed it replaces: a
   `com.h2database/h2` pin that happens to sit at the same version as the suite
   keeps its own.

   A finding already at the target rewrites to itself, so re-running a bump is a
   no-op rather than a second edit."
  [content findings new-version]
  (let [lines   (vec (str/split-lines content))
        by-line (group-by :line findings)
        patched (reduce-kv
                 (fn [ls line-no fs*]
                   (if-let [line (get ls (dec line-no))]
                     (assoc ls (dec line-no)
                            (reduce (fn [l {:keys [excerpt version]}]
                                      (str/replace l excerpt
                                                   (str/replace excerpt version new-version)))
                                    line
                                    fs*))
                     ls))
                 lines
                 by-line)
        joined  (str/join "\n" patched)]
    ;; split-lines drops a trailing newline; putting it back keeps the diff to
    ;; the lines that changed instead of the whole file.
    (if (str/ends-with? content "\n") (str joined "\n") joined)))

;; =============================================================================
;; Planning
;; =============================================================================

(defn current-sources
  "Every location `check:versions` governs, source and documentation."
  []
  (concat (check-versions/version-sources) (check-versions/doc-sources)))

(defn plan
  "{path -> new-content} for a bump to `new-version`. Reads only; writes nothing."
  [new-version]
  (->> (current-sources)
       (group-by :file)
       (reduce (fn [acc [path findings]]
                 (let [f (fs/file root-dir path)]
                   (if-not (fs/exists? f)
                     acc
                     (assoc acc path (rewrite (slurp f) findings new-version)))))
               {})))

(defn changed-files
  "The paths in `plan` whose content differs from what is on disk."
  [plan]
  (->> plan
       (filter (fn [[path content]]
                 (not= content (slurp (fs/file root-dir path)))))
       (map key)
       sort))

;; =============================================================================
;; CLI
;; =============================================================================

(defn- usage []
  (println "Usage: bb bump <version> [--dry-run]")
  (println)
  (println "  Rewrites the suite version in every location bb check:versions")
  (println "  governs — source and documentation — and nowhere else, then")
  (println "  verifies the result against the version it just wrote.")
  (println)
  (println "  --dry-run   list the files that would change and write nothing"))

(defn -main [& args]
  (let [dry?        (some #{"--dry-run"} args)
        new-version (first (remove #(str/starts-with? % "--") args))]

    (when-not (valid-version? new-version)
      (binding [*out* *err*]
        (println (ansi/red (if new-version
                             (str "Not a suite version: " (pr-str new-version))
                             "No version given")))
        (println)
        (println "  Expected 1.0.0-beta-6, 1.0.1-alpha-43 or 2.0.0 — no leading \"v\",")
        (println "  and a numbered pre-release if there is one. A tag prefix written")
        (println "  into every location would leave them all agreeing, so the check")
        (println "  would pass on it.")
        (println))
      (usage)
      (System/exit 2))

    (let [sources (current-sources)
          current (:version (first sources))
          p       (plan new-version)
          changed (changed-files p)]

      (println (str "Bumping " (count sources) " location(s) across " (count p) " file(s)"))
      (println (str "  from " current " to " new-version))
      (println)

      (cond
        (empty? sources)
        (do (binding [*out* *err*]
              (println (ansi/red "Found no version locations at all — refusing to write.")))
            (System/exit 1))

        (empty? changed)
        (println (ansi/green (str "Nothing to do — every location already names "
                                  new-version ".")))

        dry?
        (do (println (str (count changed) " file(s) would change:"))
            (doseq [f changed] (println (str "  " f)))
            (println)
            (println "Nothing written (--dry-run)."))

        :else
        (do
          (doseq [[path content] p]
            (spit (fs/file root-dir path) content))
          (println (str (count changed) " file(s) changed:"))
          (doseq [f changed] (println (str "  " f)))
          (println)

          ;; The diff, so the change is reviewed rather than trusted. --stat
          ;; rather than the full patch: 96 one-word edits across 40 files is
          ;; not something anyone reads line by line, and the verification
          ;; below is what actually proves the result.
          (process/shell {:continue true} "git" "--no-pager" "diff" "--stat")
          (println)

          ;; Self-verify against the version just written, not against the
          ;; majority. Majority-wins misreports exactly here: a run that
          ;; rewrote fewer than half the locations would name the *bumped*
          ;; files as the offenders.
          (println "Verifying:")
          (let [all (current-sources)]
            (if-let [{:keys [offenders]} (check-versions/disagreements all new-version)]
              (do (binding [*out* *err*]
                    (println (ansi/red (str "  ✗ " (count offenders)
                                            " location(s) still do not name " new-version)))
                    (doseq [{:keys [file line version what]} offenders]
                      (println (str "      " file (when line (str ":" line))
                                    "  (" what ") names " version))))
                  (System/exit 1))
              (do (println (ansi/green (str "  ✓ all " (count all)
                                            " location(s) name " new-version)))
                  (println)
                  (println "Next: bb check --quick, then commit and tag.")))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
