#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_versions.clj
;;
;; Every place that names the library-suite version must name the same one.

(ns wagoe.tools.check-versions
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [edamame.core :as e]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(def version-pattern
  "A suite version: 1.0.0-beta-5, 1.0.1-alpha-32, 2.0.0.

   Public because `bb bump` validates its argument against it. What counts as a
   suite version has to be one definition: a bump that accepts a shape the check
   does not recognise writes 96 locations the gate then cannot read."
  #"\d+\.\d+\.\d+(?:-[a-z]+-\d+)?")

(defn prerelease-of-a-patch?
  "Whether `v` is a pre-release cut from a patch version — `1.0.1-alpha-1`.

   Maven orders `1.0.1-alpha-1` *above* `1.0.0`, so such a version outranks the
   release it precedes for anything resolving \"newest\". That is not
   hypothetical: the `1.0.1-alpha-N` line outranked every `1.0.0-beta-N` and
   users had to pin exact versions (BOU-176). Pre-releases belong to the next
   minor — `1.1.0-alpha-1` — and patch versions are only ever final (BOU-435)."
  [v]
  (boolean
   (when-let [[_ patch] (re-matches #"\d+\.\d+\.(\d+)-[a-z]+-\d+" (str v))]
     (pos? (parse-long patch)))))

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

(def banner-text-re
  "A string that announces a Wagoe tool's own version.

   `wagoe` must precede `version`, which keeps someone else's out:
   `\"text/plain; version=0.0.4\"` is Prometheus' content type and
   `\"version:1.0.0\"` a Datadog tag example, both in this tree."
  (re-pattern (str "(?i)\\bwagoe\\b.*?\\bversion\\b\\s+v?(" version-pattern ")")))

(def ^:private print-ops
  "Operators whose arguments reach a user's terminal.

   `print-str` and `println-str` are deliberately absent: they return a string
   and write nothing, so a formatter built from one prints no banner and is not
   this gate's business. They were here on the strength of their names.

   Nothing is lost by leaving them out — `printed-strings` collects literals at
   any depth, so `(println (print-str \"…\"))` is still one print. What remains
   uncovered is a banner built here and printed from somewhere else entirely,
   which is the same gap as a `def`'d string and covered from the other side by
   `wagoe.cli.main-test`."
  #{"println" "print" "printf" "pr" "prn" "pprint"})

(defn- unevaluated-head?
  "Whether `head` is the `quote` or `comment` operator — forms neither runs.

   Same judgement as `check-tests/unevaluated-head?`. A qualified
   `(foo/comment …)` is an ordinary call whose arguments do run."
  [head]
  (and (symbol? head)
       (contains? #{"quote" "comment"} (name head))
       (contains? #{nil "clojure.core"} (namespace head))))

(defn- printed-strings
  "Every string literal `form` can hand to a print operator, with the call's row.

   Nested at any depth rather than direct arguments only, so
   `(println (str \"wagoe CLI version \" v))` is read as the one print it is."
  [form]
  (let [out (volatile! [])]
    (letfn [(collect [row x]
              (cond
                (string? x)             (vswap! out conj [row x])
                (and (coll? x)
                     (not (unevaluated-head? (when (seq? x) (first x)))))
                (run! #(collect row %) (seq x))))
            (walk [x]
              (when (coll? x)
                (let [head (when (seq? x) (first x))]
                  (cond
                    (unevaluated-head? head)
                    nil

                    (and (symbol? head) (contains? print-ops (name head)))
                    (run! #(collect (or (:row (meta x)) 0) %) (rest x))

                    :else (run! walk (seq x))))))]
      (walk form))
    @out))

(defn banner-findings
  "Every version banner `text` prints, as {:line :excerpt :version}.

   `wagoe version` answered `1.0.0-beta-5` four releases after beta-5, from a
   string literal in `wagoe/cli/main.clj`. Every other rule here reads a
   coordinate, a `(def … -version)`, a tag or a documented claim; a banner is
   none of those, so the one number a user asks the tool for directly was the
   only number nothing checked.

   Read by the reader, not scanned. Two rounds of review found the same defect in
   a textual scan — a comment, then a docstring quoting an example `println` —
   and this rule is the one verdict here that fails on *presence*, with no
   version to disagree with and no bump to correct it. Every false positive is
   therefore a hard CI failure over a sentence. The same argument moved
   `check-tests` off a lexer in BOU-365: only the reader knows what executes.
   `#_` never arrives, a `comment` body is not a call, quoted code is data, and a
   docstring is the string it is rather than the code it quotes.

   Unparseable source throws rather than returning nothing — a file this cannot
   read is one it cannot clear.

   Pure and public so the rule can be proven to fire without a file to break —
   the repository is meant to carry none of these (see `hardcoded-banners`)."
  [text]
  (let [forms (try
                ;; :features because libs/tools and scripts/ run under Babashka,
                ;; whose reader enables both. A banner behind a reader
                ;; conditional is not a shape anyone writes; taking the :clj
                ;; branch is enough to read the file.
                (e/parse-string-all text {:all true
                                          :features  #{:clj :bb}
                                          :read-cond :allow
                                          :readers   (fn [_tag] identity)
                                          :auto-resolve (fn [_] 'this.ns)})
                (catch Exception e
                  (throw (ex-info (str "could not read source, so it cannot be "
                                       "cleared of hardcoded version banners")
                                  {:cause (ex-message e)} e))))]
    (for [form            forms
          [row literal]   (printed-strings form)
          :let            [m (re-find banner-text-re literal)]
          :when           m]
      {:line row :excerpt (first m) :version (second m)})))

(defn- source-files
  "Every Clojure source file a version could be hardcoded in."
  []
  (sort (mapcat #(map str (fs/glob root-dir %))
                ["libs/*/src/**/*.clj" "src/**/*.clj" "scripts/**/*.clj"])))

(defn hardcoded-banners
  "Source files that print a suite version from a literal instead of reading one.

   A separate verdict from the agreement check, because agreement is the wrong
   question here: the CLI ships `modules-catalogue.edn`, which already carries
   `:cli-version` and is already gated, so a banner has a single source to read
   and does not need a second copy kept in step with it. Reported rather than
   bumped — bumping it would keep the duplicate alive at the right number, which
   is how it survived four releases at the wrong one."
  []
  (into []
        (mapcat (fn [f]
                  (let [path (str (fs/relativize root-dir (fs/absolutize f)))]
                    (try
                      (map #(assoc % :file path)
                           (banner-findings (slurp (fs/file f))))
                      (catch Exception e
                        (throw (ex-info (str path ": " (ex-message e))
                                        {:file path} e)))))))
        (source-files)))

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

(def doc-exempt-rules
  "Path prefix -> the rules that must not run on it, or `:all` for the file.

   Same principle as `check-doc-counts/excluded-paths`: a CHANGELOG entry naming
   alpha-32 was true at that release, and an ADR records a decision as of a
   date. Rewriting either would be a lie about the past, so they are exempt
   from everything.

   `stability.adoc` is the interesting one, and the reason this is a map rather
   than the list it used to be (BOU-413). The page exists to explain that
   `1.0.0-beta-1` sorts *older* than `1.0.1-alpha-42`; those strings are its
   subject, and bumping them would delete the explanation. It also says
   `1.0.0` several times meaning the future stable release — which
   `version-pattern` matches, so a naive un-exclusion would rewrite those too.

   But it opens with a `| Current version` cell, and excluding the whole file
   left that ungated: it read `1.0.0-beta-6` in the `1.0.0-beta-7` release, and
   `1.0.0-beta-5` in beta-6, lagging exactly one release every release on the
   page a visitor opens to find out what the current version is (BOU-413).

   A file-level exemption was protecting particular *lines*. So the exemption is
   per-rule: this page stays exempt from the three rules that would damage it,
   and is subject to the current-version rules, which are the only ones that can
   read the cell. A historical claim and a current-version claim are opposites —
   nothing that must not be rewritten can be phrased as \"this is the version
   right now\"."
  {"CHANGELOG.md"                                   :all
   "dev-docs/adr/"                                  :all
   "dev-docs/roadmap.adoc"                          :all
   "dev-docs/presentations/"                        :all
   "dev-docs/reference/historical-docs-triage.adoc" :all
   "docs/superpowers/"                              :all
   "docs/modules/ROOT/pages/stability.adoc"         #{"com.wagoe pin"
                                                      "git tag pin"
                                                      "release-pinned prose"}})

(defn exempt-rules
  "The rules exempt on `path`: `:all`, or a set of rule names (possibly empty).

   Prefixes are unioned rather than first-match, so adding a broad exemption
   cannot silently narrow a specific one already in the map."
  [path]
  (let [matched (keep (fn [[prefix rules]]
                        (when (str/starts-with? path prefix) rules))
                      doc-exempt-rules)]
    (if (some #{:all} matched)
      :all
      (reduce into #{} matched))))

(defn doc-in-scope?
  "True when `path` is documentation this gate reads at all.

   In scope no longer means every rule applies — see `doc-exempt-rules`. A file
   exempt from some rules is still read, and `doc-version-findings` decides
   which rules run on it."
  [path]
  (and (or (str/ends-with? path ".md")
           (str/ends-with? path ".adoc"))
       (not (str/includes? path "/target/"))
       (not= :all (exempt-rules path))))

(def ^:private coordinate-re
  (re-pattern (str "com\\.wagoe/[a-z0-9-]+\\s*\\{:mvn/version\\s+\""
                   version-pattern "\"")))

(def ^:private lein-coordinate-re
  "The Leiningen vector spelling of a coordinate: `[com.wagoe/wagoe-core \"…\"]`.

   The same pin as `coordinate-re`, written the way ten library READMEs open —
   three lines into the page, under \"Installation\". Nine of them sat on
   `1.0.0-beta-5` for four releases while the gate reported every location in
   agreement, because it read the tools.deps map form only.

   The group prefix is optional because the READMEs write it both ways."
  (re-pattern (str "\\[(?:com\\.)?wagoe/[a-z0-9-]+\\s+\"" version-pattern "\"\\]")))

(def ^:private dep-table-row-re
  "A coordinate split across two cells of a dependency table.

   Five READMEs close with one — `| `wagoe/observability` | 1.0.0-beta-5 |
   Logging, metrics |`. Still a pin, and still where a reader gets the version
   from, but neither coordinate rule can see it: the artifact and the version are
   separated by a cell boundary rather than by `{:mvn/version`.

   The artifact cell is what makes the next cell a version of ours, so it is part
   of the match — a bare `| 1.3.1048 |` in the same table belongs to next.jdbc."
  (re-pattern (str "\\|\\s*[`']?(?:com\\.)?wagoe/[a-z0-9-]+[`']?\\s*\\|\\s*[`']?v?("
                   version-pattern ")")))

(def ^:private version-header-re
  "A `**Version:**` header, which states the version as of right now.

   `libs/tools/AGENTS.md` opens with one, `libs/external/README.md` and
   `libs/realtime/README.md` too, and all three read `1.0.0-beta-5` four
   releases on. Same defect as the `| Current version` cell (BOU-413) in a
   different notation: a header asserting the version cannot be true of an old
   release, and none of the four rules read it.

   The bold marker is required. `Version: 1.2.3` in running text is as likely to
   be a third party's, and `bb bump` rewrites what this reports. Both spellings
   are in use — the colon inside the marker and outside it."
  (re-pattern (str "(?i)\\*\\*version:?\\*\\*:?\\s*[`']?v?(" version-pattern ")")))

(def ^:private tag-pin-re
  "A git tag of this repository, pinned in an install command.

   The `v` is optional because our tags do not carry one: releases are tagged
   `1.0.0-beta-7`, and `publish.yml` reads the version straight off the ref with
   `version=\"${GITHUB_REF#refs/tags/}\"` before checking it against every
   `build.clj`. A `v`-prefixed tag would fail that guard.

   Requiring it here is why `cli.adoc` documented `--tag v1.0.0-beta-7` through
   every beta (BOU-412). The rule matched only the prefixed spelling, `bb bump`
   rewrites what the rule matches, so each release bumped the number and
   carefully preserved a prefix that resolves no ref. The gate was not blind to
   the line — it was verifying the broken form.

   Optional rather than forbidden for two reasons. The `v1.0.1-alpha-*` line and
   `v1.0.0-alpha` really are prefixed, so a document naming an old tag is still
   naming a real one. And a bare tag has to match, or correcting the `.adoc`
   would drop the line out of the gate's sight — trading a wrong version for an
   unwatched one, which is the worse of the two.

   This does not widen what third-party tags match: `--tag v0.2.2` matched
   before, the `v` being the literal and `0.2.2` the version. `tag-ownership` is
   what keeps someone else's tag out, and that is unchanged."
  (re-pattern (str "--tag\\s+v?(" version-pattern ")\\b")))

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

(def ^:private current-version-label-re
  "An AsciiDoc table cell whose text labels the next cell as the current version."
  #"(?i)^\s*\|\s*current\s+version\s*$")

(def ^:private current-version-cell-re
  "The value cell beneath a `| Current version` label.

   `current-version-re` cannot read this, and that is the whole of BOU-413: it
   wants the label and the version in one sentence, and an AsciiDoc table row is
   two lines —

       | Current version
       | `1.0.0-beta-7`

   so the claim on `stability.adoc` matched nothing even once the page was in
   scope. The label is what makes the version a claim about the present, exactly
   as `wagoe is at` does in prose; it just sits on the line above.

   Anchored to the cell rather than the bare version so `bb bump`, which
   rewrites the excerpt this produces, edits the cell and not some other
   version-shaped string that happens to share the line."
  (re-pattern (str "\\|\\s*[`']?v?(" version-pattern ")[`']?")))

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

(defn- current-version-cells
  "For each line of `block`, whether the line above it is a `| Current version` label.

   The same shape as `tag-ownership` and for the same reason: the thing that
   makes a version a claim is not on the version's own line."
  [block]
  (->> block
       (map (fn [[_ line]] (boolean (re-matches current-version-label-re line))))
       (cons false)
       butlast))

(defn doc-version-findings
  "Every suite version `text` names, as {:file :line :version :what :excerpt}.

   Every match on a line, not the first: two coordinates on one line is ordinary
   Clojure formatting, and reading one of them would leave the other ungated —
   and, because `bb bump` rewrites what this discovers, stale after a bump that
   then verified clean.

   Rules exempt on `path` are skipped rather than filtered afterwards, so an
   exempt rule cannot contribute a finding that `bb bump` would then rewrite —
   see `doc-exempt-rules`.

   Pure and public so the gate can be proven to fire without a repository to
   break."
  [path text]
  (let [exempt  (exempt-rules path)
        exempt? (if (= :all exempt) (constantly true) exempt)]
    (for [block (blocks text)
          [[idx line] owner cell?] (map vector
                                        block
                                        (tag-ownership block)
                                        (current-version-cells block))
          [what re] [["com.wagoe pin"         coordinate-re]
                     ["com.wagoe pin"         lein-coordinate-re]
                     ["com.wagoe pin"         dep-table-row-re]
                     ["git tag pin"           (when (= our-repo owner) tag-pin-re)]
                     ["release-pinned prose"  prose-pin-re]
                     ["current-version claim" current-version-re]
                     ["current-version claim" version-header-re]
                     ["current-version claim" (when cell? current-version-cell-re)]]
          :when (and re (not (exempt? what)))
          m     (re-seq re line)
          :let  [matched (if (vector? m) (first m) m)
                 v       (re-find version-pattern matched)]
          :when v]
      {:file    path
       :line    (inc idx)
       :version v
       :what    what
       :excerpt (str/trim matched)})))

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
  (let [code    (version-sources)
        docs    (doc-sources)
        banners (hardcoded-banners)]
    (cond
      (seq banners)
      (do (binding [*out* *err*]
            (println (str "  ✗ " (count banners)
                          " source file(s) print a suite version from a literal"))
            (doseq [{:keys [file line version]} banners]
              (println (str "      " file ":" line "  prints " version)))
            (println "    Read it from a gated single source instead —")
            (println "    modules-catalogue.edn carries :cli-version."))
          (System/exit 1))

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
