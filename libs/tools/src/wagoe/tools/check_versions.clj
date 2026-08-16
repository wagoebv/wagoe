#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_versions.clj
;;
;; Every place that names the library-suite version must name the same one.

(ns wagoe.tools.check-versions
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(def ^:private version-pattern
  "A suite version: 1.0.0-beta-5, 1.0.1-alpha-32, 2.0.0."
  #"\d+\.\d+\.\d+(?:-[a-z]+-\d+)?")

(defn version-sources
  "Every file that hard-codes the suite version, and the version it names.

   Discovered by pattern rather than listed: a hand-kept list is how this
   drifted in the first place — three repositories shipped a bb.edn pinning
   wagoe-tools at alpha-20 and alpha-32 while their deps.edn had moved on,
   because the bump routine covered deps.edn and nobody had written bb.edn
   down.

   Returns a seq of {:file str :version str :what str}."
  []
  (let [read* (fn [f] (try (slurp (fs/file f)) (catch Exception _ "")))
        rel   (fn [f] (str (fs/relativize root-dir (fs/absolutize f))))
        find1 (fn [content re]
                (when-let [m (re-find re content)]
                  (re-find version-pattern (if (vector? m) (first m) m))))]
    (concat
     ;; Each publishable library's build.clj.
     (for [f    (sort (map str (fs/glob root-dir "libs/*/build.clj")))
           :let [v (find1 (read* f) #"\(def version \"[^\"]+\"")]
           :when v]
       {:file (rel f) :version v :what "build.clj"})

     ;; The CLI's pin of the tools artifact it writes into generated projects.
     (for [f    ["libs/wagoe-cli/src/wagoe/cli/new.clj"]
           :let [c (read* (fs/file root-dir f))]
           [_ v] (re-seq (re-pattern (str "-version\\s+\"(" version-pattern ")\"")) c)]
       {:file f :version v :what "generated-project pin"})

     ;; The module catalogue shipped with the CLI. Every version-bearing key,
     ;; not just :catalogue-version: the file also carries :cli-version and a
     ;; per-module :version for each addable module, and `wagoe add` pins the
     ;; artifact from that per-module value. Reading one of 25 meant the gate
     ;; could pass while the CLI emitted a stale dependency — the exact failure
     ;; BOU-245 is about, in the file most likely to have it.
     (for [f     ["libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"]
           :let  [c (read* (fs/file root-dir f))]
           [_ k v] (re-seq (re-pattern (str "(:[a-z-]*version[a-z-]*)\\s+\"("
                                            version-pattern ")\"")) c)]
       {:file f :version v :what k})

     ;; Any com.wagoe/* Maven pin, in deps.edn or bb.edn, anywhere in the tree.
     ;; This is the shape the ticket is named for: bb.edn pins are separate
     ;; from deps.edn pins and were bumped separately, which is to say not
     ;; bumped.
     (for [f    (concat (map str (fs/glob root-dir "**/deps.edn"))
                        (map str (fs/glob root-dir "**/bb.edn")))
           :when (not (str/includes? f "/target/"))
           :let  [c (read* f)]
           [_ v] (re-seq (re-pattern (str "com\\.wagoe/[a-z0-9-]+\\s*\\{:mvn/version\\s+\"("
                                          version-pattern ")\"")) c)]
       {:file (rel f) :version v :what "com.wagoe pin"}))))

;; =============================================================================
;; Documentation — the half of the surface users actually read
;; =============================================================================
;;
;; BOU-317. The rules above cover 59 code locations and stopped there, so
;; roughly 40% of the version surface was ungated — and it was the half people
;; copy from. `installation.adoc` pinned `1.0.1-alpha-42` through 43 releases,
;; a line Maven sorts *newer* than every beta, and no run ever went red.
;;
;; The release procedure made that worse rather than better: the documented bump
;; ran `sed` over `.md`/`.adoc` as well as source, so documentation was in scope
;; for the mutation and out of scope for the verification.

(def doc-excluded-paths
  "Path prefixes whose versions are historical and must NOT be rewritten.

   Same principle as `check-doc-counts/excluded-paths`: a CHANGELOG entry naming
   alpha-32 was true at that release, and an ADR records a decision as of a
   date. Rewriting either would be a lie about the past.

   `stability.adoc` is here for a different reason, and it is the interesting
   one: the page exists to explain that `1.0.0-beta-1` sorts *older* than
   `1.0.1-alpha-42`. Those two strings are its subject. Bumping them would
   delete the explanation while leaving the prose that refers to it."
  ["CHANGELOG.md"
   "dev-docs/adr/"
   "dev-docs/roadmap.adoc"
   "dev-docs/presentations/"
   "dev-docs/reference/historical-docs-triage.adoc"
   "docs/superpowers/"
   "docs/modules/ROOT/pages/stability.adoc"])

(defn doc-in-scope?
  "True when `path` is live documentation this gate governs."
  [path]
  (and (or (str/ends-with? path ".md")
           (str/ends-with? path ".adoc"))
       (not (str/includes? path "/target/"))
       (not-any? #(str/starts-with? path %) doc-excluded-paths)))

(def ^:private coordinate-re
  (re-pattern (str "com\\.wagoe/[a-z0-9-]+\\s*\\{:mvn/version\\s+\""
                   version-pattern "\"")))

(def ^:private tag-pin-re
  (re-pattern (str "--tag\\s+v(" version-pattern ")\\b")))

(def ^:private prose-pin-re
  "A version presented as the release something arrived in.

   Deliberately narrow. Matching every version-shaped string in prose would fire
   on the sentences that *explain* version drift, which are the ones that must
   keep naming old releases. The novelty phrasings are the ones that rot:
   `libs/realtime/README.md` announced a feature as NEW in `1.0.1-alpha-26` for
   sixteen releases afterwards."
  (re-pattern (str "(?i)\\b(?:new|added|introduced|available)\\s+in\\s+v?("
                   version-pattern ")\\b")))

(def ^:private our-repo-re
  "This repository, in the forms documentation refers to it by."
  #"wagoebv/wagoe\b")

(defn- blocks
  "`text` split into blank-line-separated blocks, each with its starting line.

   `--tag` is scoped per block rather than per line because a shell command is
   written across several lines: `bbin install https://github.com/wagoebv/wagoe \\`
   and `  --tag v1.0.0-beta-5 \\` are one command and two lines. A line-scoped
   rule sees the tag with no repository beside it, and would then have to choose
   between missing our own recipe and rewriting `--tag v0.2.2` in the
   clojure-mcp-light install — someone else's tool, at the correct version."
  [text]
  (->> (str/split-lines text)
       (map-indexed vector)
       (partition-by (fn [[_ line]] (str/blank? line)))
       (remove (fn [group] (str/blank? (second (first group)))))))

(defn doc-version-findings
  "Every suite version `text` names, as {:file :line :version :what :excerpt}.

   Pure and public so the gate can be proven to fire without a repository to
   break — and so `bb bump` can rewrite exactly what this discovers rather than
   keeping a second list that drifts from it."
  [path text]
  (for [block (blocks text)
        :let  [ours? (some (fn [[_ l]] (re-find our-repo-re l)) block)]
        [idx line] block
        [what re] [["com.wagoe pin"        coordinate-re]
                   ["git tag pin"          (when ours? tag-pin-re)]
                   ["release-pinned prose" prose-pin-re]]
        :when re
        :let  [m (re-find re line)]
        :when m
        :let  [matched (if (vector? m) (first m) m)]]
    {:file    path
     :line    (inc idx)
     :version (re-find version-pattern matched)
     :what    what
     :excerpt (str/trim matched)}))

(defn tracked-docs
  "Tracked `.md`/`.adoc` files in scope.

   Throws when git fails. Returning [] on a bad exit would make this gate report
   clean because it could not look — the failure BOU-250 exists to stop."
  []
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "ls-files")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files failed (exit " exit ") — cannot determine "
                           "tracked files, so this gate cannot report a verdict")
                      {:exit exit :err (str/trim (or err ""))})))
    (->> (str/split-lines out)
         (remove str/blank?)
         (filter doc-in-scope?))))

(defn doc-sources
  "Every version named by live documentation, in `version-sources`' shape."
  []
  (mapcat (fn [path]
            (doc-version-findings path (try (slurp (fs/file root-dir path))
                                            (catch Exception _ ""))))
          (tracked-docs)))

;; =============================================================================
;; Verdict
;; =============================================================================

(defn disagreements
  "The sources that do not name `expected`, or the majority version without it.

   Pure, so the gate can be proven to fire without a repository to break.

   The majority rule holds when a bump touches most locations and misses a few —
   the shape this gate was built for. It inverts when a whole *category* is
   missed, which is exactly what documentation was: had the 30 doc locations
   stayed on alpha-42 while a handful of libs were bumped, the majority would
   have been the stale version and the correctly-bumped files would have been
   reported as the offenders. So documentation is checked against the version
   the code declares rather than allowed to vote on it.

   Returns {:consensus str :offenders seq}, or nil when everything agrees."
  ([sources] (disagreements sources nil))
  ([sources expected]
   (let [versions  (frequencies (map :version sources))
         consensus (or expected
                       (when (seq versions) (key (apply max-key val versions))))
         offenders (->> sources
                        (remove #(= consensus (:version %)))
                        (sort-by :file))]
     (when (seq offenders)
       {:consensus consensus :offenders offenders}))))

(defn check
  "Fail when the repository names more than one suite version."
  []
  (println "Verifying every hard-coded suite version agrees")
  (let [code (version-sources)
        docs (doc-sources)]
    (cond
      (empty? code)
      (do (binding [*out* *err*]
            (println "  ✗ found no version strings in source at all — the check is not looking at anything"))
          (System/exit 1))

      (empty? docs)
      (do (binding [*out* *err*]
            (println "  ✗ found no version strings in documentation — the docs half of this")
            (println "    gate is not looking at anything (BOU-317)"))
          (System/exit 1))

      :else
      ;; The source consensus first, then every location against it. Deciding
      ;; the version from source rather than from the whole population is what
      ;; keeps a wholly-stale documentation set from outvoting the code.
      (let [code-verdict (disagreements code)
            expected     (or (:consensus code-verdict) (:version (first code)))
            all          (concat code docs)]
        (if-let [{:keys [consensus offenders]} (disagreements all expected)]
          (do (binding [*out* *err*]
                (println (str "  ✗ " (count offenders) " location(s) disagree with the other "
                              (- (count all) (count offenders))))
                (doseq [{:keys [file line version what]} offenders]
                  (println (str "      " file (when line (str ":" line))
                                "  (" what ") names " version
                                ", the rest name " consensus))))
              (System/exit 1))
          (println (str "  ✓ " (count all) " location(s) all name " expected
                        "  (" (count code) " in source, " (count docs) " in docs)")))))))

(defn -main [& _] (check))
