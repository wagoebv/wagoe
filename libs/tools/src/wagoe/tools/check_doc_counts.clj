#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_doc_counts.clj
;;
;; Locks two documented facts to the code that decides them:
;;
;;   1. how many libraries Wagoe publishes, and
;;   2. which of them are published at all.
;;
;; Both are stated in prose across README.md, AGENTS.md, the Antora site and
;; dev-docs, and both drifted badly. A single review pass over PR #351 found the
;; count written as 21, 22, 23, 24 and 25 in different files while the real
;; number was 29, and found `libs/tools` described as "not published to Clojars"
;; in six places while `com.wagoe/wagoe-tools` was serving POMs from Clojars.
;;
;; Neither is the kind of drift a human sweep catches reliably. It took three
;; passes, because each pass grepped for the wording of the last one — "N
;; libraries" misses "all 22 artifacts", and "not published to Clojars" misses
;; "a monorepo-internal tool" and "excluded from this list". A regex that a
;; person tunes by hand converges slowly; a gate that reads the answer out of
;; `wagoe.tools.deploy/all-libs` does not have to converge at all.
;;
;; This is the same shape as the `all-checks` <-> ci.yml lockstep test added in
;; PR #327: two hand-maintained copies of one fact, so make the code the source
;; of truth and fail when prose disagrees.
;;
;; Historical documents are out of scope by design — a CHANGELOG entry saying
;; "all 25 libraries bumped" was true at that release, and rewriting it would be
;; a lie about the past. See `excluded-paths`.
;;
;; Escape hatch: `.wagoe/check-doc-counts.edn`, which requires a justification
;; per entry (BOU-250 — an allowlist without a stated reason is where bugs hide).

(ns wagoe.tools.check-doc-counts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [babashka.process :as process]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.deploy :as deploy]))

;; =============================================================================
;; Scope
;; =============================================================================

(def excluded-paths
  "Path prefixes whose counts are historical and must NOT be rewritten.

   CHANGELOG entries and ADRs record what was true when written. dev-docs/
   roadmap.adoc is explicitly marked stale and superseded by the published
   roadmap. docs/superpowers/ holds dated design records. dev-docs/presentations/
   holds delivered talks — `fc-is-hexagonal-nl.adoc` carries `:revdate:
   2026-03-07` and said 13 libraries, which was true when it was given; a talk
   is not edited after the fact."
  ["CHANGELOG.md"
   "dev-docs/adr/"
   "dev-docs/roadmap.adoc"
   "dev-docs/presentations/"
   "dev-docs/reference/historical-docs-triage.adoc"
   "docs/superpowers/"])

(defn in-scope?
  "True when `path` is a live documentation file this gate governs."
  [path]
  (and (or (str/ends-with? path ".md")
           (str/ends-with? path ".adoc"))
       (not (str/includes? path "/target/"))
       (not-any? #(str/starts-with? path %) excluded-paths)))

;; =============================================================================
;; Rule 1 — documented counts must equal (count all-libs)
;; =============================================================================

(def number-words
  "English number words this gate reads as a possible library total.

   Deliberately starts at ten. Prose spells out small numbers as a matter of
   style, and at that magnitude the number is almost always a subset rather than
   the suite: `docs/modules/architecture/pages/scaling.adoc` says \"Two
   libraries already ship both adapters\", which is true and must stay. Nothing
   is lost by the omission — the digit form still catches any count, including a
   single-digit one, should the suite ever shrink that far."
  (let [tens  {"ten" 10 "eleven" 11 "twelve" 12 "thirteen" 13 "fourteen" 14
               "fifteen" 15 "sixteen" 16 "seventeen" 17 "eighteen" 18
               "nineteen" 19}
        units {"one" 1 "two" 2 "three" 3 "four" 4 "five" 5
               "six" 6 "seven" 7 "eight" 8 "nine" 9}
        round {"twenty" 20 "thirty" 30 "forty" 40 "fifty" 50 "sixty" 60
               "seventy" 70 "eighty" 80 "ninety" 90}]
    (merge tens round
           (into {} (for [[t tv] round [u uv] units
                          sep    ["-" " "]]
                      [(str t sep u) (+ tv uv)])))))

(def ^:private number-word-alternation
  ;; Longest first, so `twenty-nine` never matches as bare `twenty`.
  (->> (keys number-words)
       (sort-by (comp - count))
       (map #(java.util.regex.Pattern/quote %))
       (str/join "|")))

(def count-pattern
  "A number followed by up to three words and then a library/artifact noun.

   Deliberately matches the *shape* rather than any particular phrasing. Every
   miss in the PR #351 sweep was a paraphrase the previous regex had not
   anticipated: `23 independently publishable libraries`, `All 22 artifacts`,
   `Deploy all 24 libraries`, `24 composable libraries`, `handles all 29 libs`,
   `Publish the 22 Clojars artifacts`, `all 21+ libraries`. The three-word
   window covers each without reaching into an unrelated clause.

   The number may be spelled out. `docs/modules/ROOT/pages/roadmap.adoc` opens
   with `Twenty-nine libraries are published on Clojars` — a public count that a
   digit-only pattern leaves outside the gate entirely, which is how a document
   ends up guarded everywhere except the sentence it leads with. See
   `number-words` for why the vocabulary starts at ten.

   The leading lookbehind drops `6 of 9 libraries`, where the second number is a
   subset. It requires a *digit* before the `of`, because `of` alone is not the
   signal: `a monorepo of 23 libraries` is a total, and an earlier version of
   this pattern that excluded every `of N` went blind to exactly the phrasing
   the README had been getting wrong. The lookbehind lists the unit words too,
   so `one of twenty-two libraries` is read as a subset for the same reason `6
   of 9` is — it is the same rule, not a second one.

   `across all 22 libraries` still matches on purpose — that is a total too."
  (re-pattern (str "(?i)(?<!(?:\\d|one|two|three|four|five|six|seven|eight|nine)\\sof\\s)"
                   "\\b(\\d{1,3}|" number-word-alternation ")"
                   "\\+?\\s+((?:[a-z][a-z-]*\\s+){0,3}?)"
                   "(librar(?:y|ies)|artifacts?|libs)\\b")))

(defn parse-count
  "`\"29\"` and `\"Twenty-nine\"` both as 29; nil when neither."
  [s]
  (or (parse-long s)
      (get number-words (str/lower-case s))))

(def gap-disqualifiers
  "Words that, between the number and the noun, mean the number is not a count
   of libraries.

   Without this the pattern reads `expects HTTP 200 for each library` as a claim
   that there are 200 libraries. A preposition turns the phrase from `N
   <adjectives> libraries` into something about each library individually, or
   about a subset — `6 of 9 libraries done`. Adjectives never do that, which is
   why the real phrasings (`independently publishable`, `composable`,
   `published`, `Clojars`, `onafhankelijk publiceerbare`) all survive."
  #{"for" "of" "in" "per" "to" "on" "from" "with" "at" "by" "across"})

(defn- counts-libraries?
  "False when the words between number and noun disqualify the match."
  [gap]
  (->> (str/split (str/lower-case (str/trim gap)) #"\s+")
       (remove str/blank?)
       (not-any? gap-disqualifiers)))

(defn count-findings
  "Documented counts in `text` that disagree with `expected`.

   Pure, and public so a test can prove the gate still detects what it claims.
   Testing `-main` is not an option — it exits the process."
  [path text expected]
  (->> (str/split-lines text)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (for [[matched found-str gap noun] (re-seq count-pattern line)
                       :let [found (parse-count found-str)]
                       :when (and found
                                  (not= found expected)
                                  (counts-libraries? gap))]
                   {:rule    :count
                    :path    path
                    :line    (inc idx)
                    :found   found
                    :noun    noun
                    :excerpt (str/trim matched)
                    :context (str/trim line)})))))

;; =============================================================================
;; Rule 2 — no document may call a published library unpublished
;; =============================================================================

(def unpublished-patterns
  "Phrasings that assert a library is not on Clojars.

   Each of these appeared verbatim in the repo while the library in question was
   published. Matching one phrasing only is what let the claim survive three
   correction passes."
  [#"(?i)not\s+published\s+to\s+clojars"
   #"(?i)is\s+not\s+published"
   #"(?i)itself\s+is\s+not\s+published"
   #"(?i)monorepo-internal\s+tool"
   #"(?i)excluded\s+from\s+(?:this\s+list|all-libs)"
   #"(?i)not\s+included\s+in\s+`?bb\s+deploy\s+--all`?"])

(defn subject-lib
  "The library a documentation file is *about*, or nil.

   `libs/<lib>/…` is the direct case. The Antora library pages are named after
   the artifact minus its `wagoe-` prefix, so `cli.adoc` documents `wagoe-cli`;
   both spellings are returned for the caller to test against `all-libs`."
  [path]
  (or (second (re-find #"^libs/([^/]+)/" path))
      (when-let [page (second (re-find #"^docs/modules/libraries/pages/([^/]+)\.adoc$" path))]
        (when-not (= page "index") page))))

(defn published?
  "True when `lib-ish` names something in `published-libs`, allowing for the
   Antora pages that drop the `wagoe-` prefix from the directory name."
  [published-libs lib-ish]
  (boolean (and lib-ish
                (or (contains? published-libs lib-ish)
                    (contains? published-libs (str "wagoe-" lib-ish))))))

(defn names-lib?
  "True when `line` mentions `lib` as a word, not as a substring.

   Plain `includes?` attributes \"These libraries are not published ... and
   available automatically\" to the `ai` library, because `ai` is inside
   `available`. A finding that names the wrong library sends whoever fixes it to
   the wrong file. Hyphens count as word characters here so `ui-style` does not
   match inside `admin-ui-style-tokens`."
  [line lib]
  (boolean (re-find (re-pattern (str "(?i)(?<![a-z0-9-])" (java.util.regex.Pattern/quote lib)
                                     "(?![a-z0-9-])"))
                    line)))

(def library-noun-pattern
  "Marks a claim as being *about libraries*, rather than about something else
   that is legitimately not on Clojars.

   This is what makes a generic claim actionable. `These libraries are not
   published to Clojars` says which kind of thing it means without naming one;
   `Your application jar is not published to Clojars` is true and must stay."
  #"(?i)\b(?:librar(?:y|ies)|libs?|artifacts?|modules?)\b")

(defn publishing-findings
  "Lines in `text` claiming something unpublished when it is published.

   A document that is *about* one library decides the question for that file:
   `libs/e2e/README.md` saying \"not published to Clojars\" is correct, and must
   stay correct even though the sentence happens to contain the word `platform`.
   Attribution by mention alone got that wrong — the subject outranks anything
   named in passing.

   Where a file has no single subject — root `README.md`, the library index —
   two things make a claim actionable. Naming a published library is the obvious
   one; that caught \"`libs/tools` is not published to Clojars\" in the root
   AGENTS.md.

   The other is saying *libraries* without naming any. `docs/.../libraries/
   pages/index.adoc` carried \"These libraries are not published to Clojars\"
   above the devtools/tools/cli/mcp table — no name on the line, no subject for
   the page, and four published libraries mislabelled by one sentence. Requiring
   a name would let that exact regression back in, so the discriminator is what
   the sentence says is unpublished, not whether it spells out which.

   `unpublished-libs` names the directories under `libs/` that genuinely are not
   published — `e2e`. A line that mentions one is describing it, and is left
   alone however it is phrased: `libs/e2e is the only directory under libs/ that
   is not published` is both true and, without this, a generic-claim match."
  ([path text published-libs]
   (publishing-findings path text published-libs #{}))
  ([path text published-libs unpublished-libs]
   (let [subject (subject-lib path)]
     (if (and subject (not (published? published-libs subject)))
      ;; The file documents something genuinely unpublished. Its claim is true.
       []
       (->> (str/split-lines text)
            (map-indexed vector)
            (keep (fn [[idx line]]
                    (when (some #(re-find % line) unpublished-patterns)
                      (let [named       (->> published-libs
                                             (filter #(names-lib? line %))
                                             sort
                                             seq)
                            describes-  (some #(names-lib? line %) unpublished-libs)
                            generic     (and (not describes-)
                                             (re-find library-noun-pattern line))]
                        (when (and (not describes-)
                                   (or named subject generic))
                          {:rule    :publishing
                           :path    path
                           :line    (inc idx)
                           :libs    (cond subject [subject]
                                          named   named
                                          :else   ["libraries (unnamed)"])
                           :context (str/trim line)}))))))))))

;; =============================================================================
;; Allowlist
;; =============================================================================

(def allowlist-path ".wagoe/check-doc-counts.edn")

(defn read-allowlist
  "Allowlist entries as `{[path line-or-:any] justification}`.

   Every entry must carry a non-blank `:why`. An allowlist entry without a
   stated reason is indistinguishable from an unfixed bug (BOU-250), so one is
   rejected loudly rather than honoured."
  ([] (read-allowlist (io/file allowlist-path)))
  ([^java.io.File f]
   (when (.exists f)
     (let [entries (:allow (edn/read-string (slurp f)))]
       (doseq [{:keys [path why]} entries]
         (when (str/blank? why)
           (throw (ex-info (str "Allowlist entry for " path " has no :why. "
                                "Every exemption must say why it is legitimate.")
                           {:path path}))))
       (set (map (juxt :path #(get % :line :any)) entries))))))

(defn allowed?
  [allow {:keys [path line]}]
  (boolean (or (contains? allow [path :any])
               (contains? allow [path line]))))

;; =============================================================================
;; Scanning
;; =============================================================================

(defn tracked-docs
  "Tracked .md/.adoc files in scope.

   Throws when git fails. Returning [] on a bad exit would make this gate report
   clean because it could not look — the exact failure BOU-250 exists to stop."
  []
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "ls-files")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files failed (exit " exit ") — cannot determine "
                           "tracked files, so this gate cannot report a verdict")
                      {:exit exit :err (str/trim (or err ""))})))
    (->> (str/split-lines out)
         (remove str/blank?)
         (filter in-scope?))))

(defn scan
  "All findings across `paths`, allowlist applied.

   `read-file` is injected so tests can scan in-memory content."
  [paths {:keys [expected published-libs unpublished-libs allow read-file]
          :or   {allow #{} unpublished-libs #{} read-file slurp}}]
  (->> paths
       (mapcat (fn [path]
                 (let [text (read-file path)]
                   (concat (count-findings path text expected)
                           (publishing-findings path text published-libs unpublished-libs)))))
       (remove #(allowed? allow %))
       (sort-by (juxt :path :line))))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn- report-count [{:keys [path line found noun excerpt]} expected]
  (println (str "  " (ansi/bold path) ":" line))
  (println (str "    says " (ansi/red (str found " " noun))
                " — expected " (ansi/green (str expected))))
  (println (str "    " (ansi/dim excerpt))))

(defn- report-publishing [{:keys [path line libs context]}]
  (println (str "  " (ansi/bold path) ":" line))
  (println (str "    calls " (ansi/red (str/join ", " libs))
                " unpublished, but it is in deploy/all-libs"))
  (println (str "    " (ansi/dim context))))

(defn unpublished-lib-dirs
  "Directories under `libs/` that are not in `all-libs`.

   Derived rather than listed, so a library added to the monorepo but not yet to
   `all-libs` is treated as unpublished automatically — and so this gate cannot
   drift the way the prose it checks did."
  []
  (let [libs-dir (io/file "libs")]
    (if (.isDirectory libs-dir)
      (let [dirs (->> (.listFiles libs-dir)
                      (filter #(.isDirectory ^java.io.File %))
                      (map #(.getName ^java.io.File %))
                      set)]
        (clojure.set/difference dirs (set deploy/all-libs)))
      #{})))

(defn scan-opts
  "The options `-main` scans with, derived from `all-libs`.

   Public so the gate-firing test scans exactly what CI scans. Having the test
   build its own copy of this map is the same two-copies-of-one-fact problem
   this gate exists to catch — the first version did, and went green while the
   real run was failing on an option the test did not pass."
  []
  (let [published (set (map deploy/artifact-name deploy/all-libs))]
    {:expected         (count deploy/all-libs)
     :published-libs   (into published (set deploy/all-libs))
     :unpublished-libs (unpublished-lib-dirs)
     :allow            (or (read-allowlist) #{})}))

(defn -main [& _args]
  (let [{:keys [expected]} (scan-opts)
        findings  (scan (tracked-docs) (scan-opts))
        {counts :count pubs :publishing} (group-by :rule findings)]
    (if (seq findings)
      (do
        (when (seq counts)
          (println (ansi/red (str "Documented library counts disagree with "
                                  "wagoe.tools.deploy/all-libs (" expected "):")))
          (println)
          (doseq [f counts] (report-count f expected))
          (println))
        (when (seq pubs)
          (println (ansi/red "Documents calling a published library unpublished:"))
          (println)
          (doseq [f pubs] (report-publishing f))
          (println))
        (println (str (count findings) " finding(s). all-libs is the source of "
                      "truth — update the prose, not the code."))
        (println (ansi/dim (str "Historical files are already out of scope; for a "
                                "genuine exception add an entry with a :why to "
                                allowlist-path ".")))
        (System/exit 1))
      (do
        (println (ansi/green (str "Documented library counts and publishing claims "
                                  "match all-libs (" expected ").")))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
