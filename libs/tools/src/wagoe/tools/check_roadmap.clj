#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_roadmap.clj
;;
;; Keeps the published roadmap from underselling what already shipped.
;;
;; Three roadmap sources disagreed at once (BOU-434): `scaling.adoc` marked the
;; remote-port adapter, service launch mode, the event bus and the removal of
;; every module dependency cycle as shipped, while `roadmap.adoc` still listed
;; them under "After 1.0.0" and called the cycles "tolerated by an allowlist" —
;; and `dev-docs/roadmap.adoc` contradicted both from April.
;;
;; The ticket proposed grepping for the BOU ids the two pages share. That gate
;; would have been removed by the very next ticket, which takes ticket ids out of
;; the rendered docs (BOU-437), so it reads what survives: the ✅ entries in
;; scaling.adoc are discovered, not listed here, so shipping the next thing puts
;; it under this gate without anyone remembering to.

(ns wagoe.tools.check-roadmap
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]))

(def roadmap-path
  "The one maintained roadmap. Every other roadmap file must point here."
  "docs/modules/ROOT/pages/roadmap.adoc")

(def shipped-source
  "Where shipped-status is decided. Its ✅ entries are read as the truth a
   roadmap page may not contradict, because they are written next to the code
   they describe and reviewed with it."
  "docs/modules/architecture/pages/scaling.adoc")

(def shipped-heading-re
  "The roadmap's own shipped section. Text under it may name shipped work;
   everything else is read as a statement about the future."
  #"(?m)^==+\s+Shipped in 1\.0")

(def shipped-entry-re
  "`✅ *Title* — …` in scaling.adoc, in either of its two lists.

   Bounded to one line. Letting it span them made a ✅ at the end of a table cell
   pair up with an unclosed `*` three lines later, and the whole paragraph
   between them became a `title`."
  #"✅[^*\n]*\*([^*\n]+)\*")

;; =============================================================================
;; Rule 1 — nothing shipped may be listed as future work
;; =============================================================================

(def stopwords
  #{"the" "and" "or" "of" "for" "to" "in" "on" "an" "a" "its" "with" "as" "by"
    "at" "that" "this" "into" "over" "per" "via" "not" "but" "all" "one"})

(defn significant-words
  "The words of `s` that carry its meaning, lowercased.

   Hyphens split, so `remote-port` is two words and matches prose that writes it
   either way. Short words and connectives go, because they are what a rewrite
   changes while the claim stays the same."
  [s]
  (->> (str/split (str/lower-case s) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove stopwords)
       (filter #(>= (count %) 3))))

(defn singular
  "`topologies` → `topology`, `adapters` → `adapter`. Enough of a stemmer for
   two documentation pages; a prefix rule alone reads `topology` and
   `topologies` as different words, because they diverge before the suffix."
  [w]
  (cond
    (str/ends-with? w "ies")              (str (subs w 0 (- (count w) 3)) "y")
    (and (str/ends-with? w "es")
         (> (count w) 4))                 (subs w 0 (- (count w) 2))
    (and (str/ends-with? w "s")
         (not (str/ends-with? w "ss"))
         (> (count w) 3))                 (subs w 0 (dec (count w)))
    :else                                 w))

(defn word-match?
  "True when two words are the same word in a different form.

   Prose reworded between two pages inflects: `deploy`/`deployment`,
   `topology`/`topologies`, `port`/`ports`. Singularising and then comparing by
   prefix covers those. The length ratio is what stops it from also covering
   `port`/`portable` — a word twice as long as its prefix is a different word,
   not an inflection of one."
  [a b]
  (let [[short long] (sort-by count [(singular a) (singular b)])]
    (or (= short long)
        (and (>= (count short) 4)
             (str/starts-with? long short)
             (< (count long) (* 2 (count short)))))))

(defn line-claims?
  "True when `line` says the same thing as `title`.

   Every significant word of the title must be present. A partial overlap is how
   a gate like this starts reporting any line about adapters."
  [title line]
  (let [line-words (significant-words line)]
    (every? (fn [w] (some #(word-match? w %) line-words))
            (significant-words title))))

(defn future-section
  "`roadmap` with its shipped section removed, as `[line-number line]` pairs.

   The shipped section is where naming shipped work is the point. Splitting on
   the heading rather than filtering per line keeps a multi-line entry whole."
  [roadmap]
  (let [lines (map-indexed (fn [i l] [(inc i) l]) (str/split-lines roadmap))
        start (some (fn [[n l]] (when (re-find shipped-heading-re l) n)) lines)
        end   (when start
                (some (fn [[n l]] (when (and (> n start) (re-find #"(?m)^==\s" l)) n))
                      lines))]
    (if start
      (remove (fn [[n _]] (and (>= n start) (or (nil? end) (< n end)))) lines)
      lines)))

(defn shipped-titles
  "The titles scaling.adoc marks ✅, deduplicated.

   The two lists spell some entries differently (`Generic remote-port adapter`
   and `Generic remote-port adapter + RPC envelope`); both are kept, since either
   phrasing appearing as future work is the same mistake."
  [scaling]
  (distinct (map second (re-seq shipped-entry-re scaling))))

(defn stale-findings
  "Lines of `roadmap` that plan something `scaling` says is shipped."
  [roadmap scaling]
  (for [title (shipped-titles scaling)
        [n line] (future-section roadmap)
        :when (and (seq (str/trim line)) (line-claims? title line))]
    {:rule :stale :line n :title title :context (str/trim line)}))

;; =============================================================================
;; Rule 2 — one roadmap, and the rest are redirects
;; =============================================================================

(def redirect-max-lines
  "A superseded roadmap may keep a pointer, not a second opinion. Twenty lines
   is a header and a paragraph; the April file that started this was 164."
  20)

(defn roadmap-files
  "Tracked files whose name says they are a roadmap.

   Discovered, so a new one cannot be added quietly — which is how the repo came
   to have two in the first place."
  [tracked]
  (filter #(re-find #"(?i)roadmap\.(adoc|md)$" %) tracked))

(defn duplicate-findings
  "Roadmap files other than the canonical one that are more than a redirect."
  [files read-file]
  (for [path files
        :when (not= path roadmap-path)
        :let [text  (read-file path)
              lines (count (str/split-lines text))]
        :when (or (> lines redirect-max-lines)
                  (not (str/includes? text roadmap-path)))]
    {:rule :duplicate :path path :lines lines
     :points-here? (str/includes? text roadmap-path)}))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn tracked-files
  "Every tracked file. Throws when git fails — a gate that cannot look must not
   report clean."
  []
  (let [{:keys [exit out err]} (process/shell {:out :string :err :string :continue true}
                                              "git" "ls-files")]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-files failed (exit " exit ") — cannot determine "
                           "tracked files, so this gate cannot report a verdict")
                      {:exit exit :err (str/trim (or err ""))})))
    (remove str/blank? (str/split-lines out))))

(defn findings
  [{:keys [read-file] :or {read-file slurp}}]
  (let [tracked (tracked-files)]
    (concat (map #(assoc % :path roadmap-path)
                 (stale-findings (read-file roadmap-path) (read-file shipped-source)))
            (duplicate-findings (roadmap-files tracked) read-file))))

(defn- report-stale [{:keys [path line title context]}]
  (println (str "  " (ansi/bold path) ":" line))
  (println (str "    plans " (ansi/red title)
                ", which " shipped-source " marks ✅ shipped"))
  (println (str "    " (ansi/dim context))))

(defn- report-duplicate [{:keys [path lines points-here?]}]
  (println (str "  " (ansi/bold path)))
  (println (str "    " (ansi/red (str lines " lines"))
                (if points-here?
                  " — a redirect, but long enough to hold its own opinion"
                  (str " — and no pointer to " roadmap-path)))))

(defn -main [& _args]
  (let [fs (findings {})
        {stale :stale dupes :duplicate} (group-by :rule fs)]
    (if (seq fs)
      (do
        (when (seq stale)
          (println (ansi/red "The roadmap plans work that is already shipped:"))
          (println)
          (doseq [f stale] (report-stale f))
          (println))
        (when (seq dupes)
          (println (ansi/red "More than one roadmap:"))
          (println)
          (doseq [f dupes] (report-duplicate f))
          (println))
        (println (str (count fs) " finding(s). " shipped-source
                      " is the source of truth for what shipped."))
        (System/exit 1))
      (do
        (println (ansi/green (str "One roadmap, and it agrees with " shipped-source ".")))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
