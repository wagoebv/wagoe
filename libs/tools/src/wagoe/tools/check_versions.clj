#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_versions.clj
;;
;; Every place that names the library-suite version must name the same one.

(ns wagoe.tools.check-versions
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(def version-pattern
  "A suite version: 1.0.0-beta-5, 1.0.1-alpha-32, 2.0.0.

   Public because `bb bump` validates its argument against it. What counts as a
   suite version has to be one definition: a bump that accepts a shape the check
   does not recognise writes 96 locations the gate then cannot read."
  #"\d+\.\d+\.\d+(?:-[a-z]+-\d+)?")

(defn matches-in
  "Every match of `re` in `content`, as {:line :excerpt :version :groups}.

   The version is read out of the matched text with `version-pattern` rather
   than from a capture group, so the four rules below can each have whatever
   group layout their own shape needs. `:groups` keeps the raw match for the one
   rule that needs a group for something else — the catalogue, whose key names
   the finding.

   Line-scoped on purpose: a whole-file `re-seq` cannot say *where*, and a
   rewrite that cannot say where has to fall back to replacing every occurrence
   of the version string — which is the blind-sed failure BOU-316 is about."
  [content re]
  (for [[idx line] (map-indexed vector (str/split-lines content))
        m          (re-seq re line)
        :let       [matched (if (vector? m) (first m) m)
                    v       (re-find version-pattern matched)]
        :when      v]
    {:line (inc idx) :excerpt matched :version v
     :groups (if (vector? m) m [m])}))

(defn publishable-libs
  "The library names `libs/*/build.clj` says are published.

   Read off the filesystem rather than listed, for the reason every other rule
   here is: a hand-kept list is the thing that drifts. Used to tell a suite
   version pin from a third-party one — see `injected-pin-name?`."
  []
  (->> (fs/glob root-dir "libs/*/build.clj")
       (map #(str (fs/file-name (fs/parent %))))
       set))

(defn injected-pin-name?
  "True when a `(def <name>-version \"…\")` names a Wagoe artifact.

   `libs/tools` shells other Wagoe CLIs into generated projects with `-Sdeps`,
   pinning them from a bare def: `ai-version` in `wagoe.tools.ai` and
   `scaffolder-version` in `wagoe.tools.scaffold`. Both shipped `1.0.0-beta-5`
   in the `1.0.0-beta-6` release, so a beta-6 project ran a beta-5 scaffolder
   and a beta-5 AI CLI — `bb scaffold ai` died on `Unknown subcommand:
   scaffold-parse`, a subcommand beta-6 has and beta-5 does not.

   The gate reported 101 locations in agreement while those two disagreed,
   because nothing read them. The comment above `ai-version` asked a human to
   \"update with the other release pins\", which is the arrangement this gate
   exists to replace.

   Matching every `-version` def instead would sweep in third-party pins —
   `tools-cli-version \"1.4.256\"` sits four lines above `ai-version` — and
   `bb bump` rewrites what this discovers, so a false positive here does not
   merely over-report, it breaks the tools.cli pin. The library set decides:
   `ai` and `scaffolder` are directories under `libs/`, `tools-cli` is not.
   Tried with and without the `wagoe-` prefix because both spellings are in
   use — `wagoe-tools-version` names `libs/tools`, `wagoe-mcp-version` names
   `libs/wagoe-mcp`."
  [name libs]
  (boolean (some libs [name
                       (str/replace name #"^wagoe-" "")
                       (str "wagoe-" name)])))

(defn version-sources
  "Every file that hard-codes the suite version, and the version it names.

   Discovered by pattern rather than listed: a hand-kept list is how this
   drifted in the first place — three repositories shipped a bb.edn pinning
   wagoe-tools at alpha-20 and alpha-32 while their deps.edn had moved on,
   because the bump routine covered deps.edn and nobody had written bb.edn
   down.

   Returns a seq of {:file str :line int :version str :what str :excerpt str}.

   `:line` and `:excerpt` are what let `bb bump` (BOU-316) rewrite exactly what
   this discovers, instead of keeping a second list of locations — which is the
   drift this gate exists to catch, so it should not need one to be fixed."
  []
  (let [read* (fn [f] (try (slurp (fs/file f)) (catch Exception _ "")))
        rel   (fn [f] (str (fs/relativize root-dir (fs/absolutize f))))]
    (concat
     ;; Each publishable library's build.clj.
     (for [f (sort (map str (fs/glob root-dir "libs/*/build.clj")))
           m (matches-in (read* f) #"\(def version \"[^\"]+\"")]
       (assoc m :file (rel f) :what "build.clj"))

     ;; The CLI's pin of the tools artifact it writes into generated projects.
     (for [f ["libs/wagoe-cli/src/wagoe/cli/new.clj"]
           m (matches-in (read* (fs/file root-dir f))
                         (re-pattern (str "-version\\s+\"" version-pattern "\"")))]
       (assoc m :file f :what "generated-project pin"))

     ;; The module catalogue shipped with the CLI. Every version-bearing key,
     ;; not just :catalogue-version: the file also carries :cli-version and a
     ;; per-module :version for each addable module, and `wagoe add` pins the
     ;; artifact from that per-module value. Reading one of 25 meant the gate
     ;; could pass while the CLI emitted a stale dependency — the exact failure
     ;; BOU-245 is about, in the file most likely to have it.
     (for [f ["libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn"]
           m (matches-in (read* (fs/file root-dir f))
                         (re-pattern (str "(:[a-z-]*version[a-z-]*)\\s+\""
                                          version-pattern "\"")))]
       (assoc m :file f :what (second (:groups m))))

     ;; Artifacts one library pins for another and injects with -Sdeps. Not in
     ;; any deps.edn, so no rule above can see them: the version is a bare def
     ;; interpolated into a coordinate string at call time.
     (let [libs (publishable-libs)]
       (for [f     (sort (map str (fs/glob root-dir "libs/*/src/**/*.clj")))
             m     (matches-in (read* f)
                               (re-pattern (str "\\(def\\s+(?:\\^:private\\s+)?"
                                                "([a-z][a-z0-9-]*)-version\\s+\""
                                                version-pattern "\"")))
             :when (injected-pin-name? (second (:groups m)) libs)]
         (assoc m :file (rel f) :what "injected pin")))

     ;; Any com.wagoe/* Maven pin, in deps.edn or bb.edn, anywhere in the tree.
     ;; This is the shape the ticket is named for: bb.edn pins are separate
     ;; from deps.edn pins and were bumped separately, which is to say not
     ;; bumped.
     (for [f     (concat (map str (fs/glob root-dir "**/deps.edn"))
                         (map str (fs/glob root-dir "**/bb.edn")))
           :when (not (str/includes? f "/target/"))
           m     (matches-in (read* f)
                             (re-pattern (str "com\\.wagoe/[a-z0-9-]+\\s*\\{:mvn/version\\s+\""
                                              version-pattern "\"")))]
       (assoc m :file (rel f) :what "com.wagoe pin")))))

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

(def ^:private current-version-re
  "A version presented as the one Wagoe is on right now.

   The novelty phrasings above rot silently; these rot loudly, on the pages a
   visitor reads first. `docs/modules/ROOT/pages/roadmap.adoc` opened with
   \"Wagoe is at `1.0.0-beta-5`\" on the day `1.0.0-beta-6` shipped: in scope for
   this gate, matched by none of its three rules, because a current-version claim
   is neither a coordinate, a tag pin, nor a novelty marker.

   Narrow for the same reason `prose-pin-re` is. \"is at\" and \"current/latest
   version|release is\" are assertions about the present and cannot be true of an
   old release; the sentences that explain version drift say \"the 1.0.1-alpha
   line is discontinued\", which this does not match."
  (re-pattern (str "(?i)\\b(?:wagoe\\s+is\\s+at|(?:current|latest)\\s+"
                   "(?:version|release)\\s+is)\\s+[`']?v?("
                   version-pattern ")\\b")))

(def ^:private github-repo-re
  "The owner/name of whatever repository a line points at, if any."
  #"github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?(?:[/\s\\]|$)")

(def ^:private our-repo "wagoebv/wagoe")

(defn- blocks
  "`text` split into blank-line-separated blocks, each line paired with its index."
  [text]
  (->> (str/split-lines text)
       (map-indexed vector)
       (partition-by (fn [[_ line]] (str/blank? line)))
       (remove (fn [group] (str/blank? (second (first group)))))))

(defn- tag-ownership
  "For each line of `block`, the repository a `--tag` on it would belong to.

   A `--tag` cannot be read on its own line, because a shell command is written
   across several: `bbin install https://github.com/wagoebv/wagoe \\` and
   `  --tag v1.0.0-beta-5 \\` are one command and two lines.

   An earlier version therefore asked whether the *block* mentioned this
   repository anywhere — which is wrong in the other direction. AGENTS.md
   already writes two `bbin install` lines with no blank line between them; put
   a wagoe install in such a block and every `--tag` in it becomes ours,
   including `--tag v0.2.2` for clojure-mcp-light. The gate would go red on a
   correct file, and `bb bump` — which rewrites what this discovers — would
   rewrite someone else's tool to our version and break the documented command.

   So a tag belongs to the nearest install URL at or above it. Nothing above it
   means it belongs to nobody."
  [block]
  (->> block
       (reductions (fn [owner [_ line]]
                     (or (second (re-find github-repo-re line)) owner))
                   nil)
       rest))

(defn doc-version-findings
  "Every suite version `text` names, as {:file :line :version :what :excerpt}.

   Every match on a line, not the first: two coordinates on one line is ordinary
   Clojure formatting, and reading one of them would leave the other ungated —
   and, because `bb bump` rewrites what this discovers, stale after a bump that
   then verified clean.

   Pure and public so the gate can be proven to fire without a repository to
   break."
  [path text]
  (for [block (blocks text)
        [[idx line] owner] (map vector block (tag-ownership block))
        [what re] [["com.wagoe pin"        coordinate-re]
                   ["git tag pin"          (when (= our-repo owner) tag-pin-re)]
                   ["release-pinned prose" prose-pin-re]
                   ["current-version claim" current-version-re]]
        :when re
        m     (re-seq re line)
        :let  [matched (if (vector? m) (first m) m)
               v       (re-find version-pattern matched)]
        :when v]
    {:file    path
     :line    (inc idx)
     :version v
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
