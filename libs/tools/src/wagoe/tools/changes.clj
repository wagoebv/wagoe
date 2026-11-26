(ns wagoe.tools.changes
  "Changelog fragments (BOU-1118): each change adds `changes/<TICKET>.md` with
   `### Added`-style sections and an optional `## Upgrading` part, so parallel
   pull requests do not conflict in CHANGELOG.md. `bb release` merges them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private order ["Breaking" "Security" "Added" "Changed" "Deprecated" "Removed" "Fixed"])

(def headings (set order))

(defn fragment? [path] (boolean (re-matches #"changes/[^/]+\.md" path)))

(defn read-fragments
  "The fragments under `root` as `[path text]`, sorted by path."
  ([] (read-fragments "."))
  ([root]
   (for [f (sort (map str (fs/glob (fs/path root "changes") "*.md")))]
     [f (slurp f)])))

(defn- split-fragment
  "`[entries upgrading]` of a fragment, both trimmed."
  [text]
  (let [[entries upgrading] (str/split text #"(?m)^## Upgrading\s*$" 2)]
    [(str/trim entries) (str/trim (str upgrading))]))

(defn- sections
  "`[preamble sections]` of `text`, each section `[heading body]`."
  [text]
  ;; The leading newline keeps the preamble: Java's split drops an empty first match.
  (let [[preamble & parts] (str/split (str "\n" text) #"(?m)^(?=### )")]
    [(str/trim preamble)
     (for [p parts
           :let [[head body] (str/split p #"\n" 2)]]
       [(str/trim (subs head 4)) (str/trim (str body))])]))

(defn- unfenced
  "`text` without its fenced code blocks, whose `#` lines are no headings."
  [text]
  (str/replace text #"(?ms)^```.*?^```" ""))

(defn problems
  "What in `fragments` a release would drop or misfile, as strings."
  [fragments]
  (for [[path text] fragments
        :let [[entries notes] (map unfenced (split-fragment text))
              [preamble secs] (sections entries)
              [loose steps]   (sections notes)]
        p (concat (when (some #(re-find #"(?m)^#{2,3} " %) (re-seq #"(?ms)^```.*?^```" text))
                    ["a ## or ### line in fenced code, where the merge would split"])
                  (when (seq preamble) ["text before the first ### heading"])
                  (when (empty? secs) ["no ### section, so nothing for CHANGELOG.md"])
                  (for [[h b] secs :when (empty? b)] (str "### " h " has no entry"))
                  (when (re-find #"(?m)^#{1,2} " (str entries "\n" notes))
                    ["a # or ## heading other than ## Upgrading"])
                  (for [[h] secs :when (not (headings h))]
                    (str "### " h " is not one of " (str/join ", " order)))
                  (when (seq loose) ["## Upgrading text before its first ### step"])
                  (for [[h] steps :when (headings h)]
                    (str "### " h " under ## Upgrading, where it would become an upgrade step")))]
    (str path ": " p)))

(defn with-fragments
  "`changelog` with each fragment's entries appended to the same `### ` section
   of `[Unreleased]`; sections it lacks follow its own, Breaking first."
  [changelog fragments]
  (if (empty? fragments)
    changelog
    (let [[_ head body tail] (or (re-find #"(?s)^(.*?## \[Unreleased\][^\n]*\n)(.*?)(\n## \[.*|\z)" changelog)
                                 (throw (ex-info "CHANGELOG.md has no ## [Unreleased] heading" {})))
          [preamble secs] (sections body)
          all    (concat secs (mapcat #(second (sections (first (split-fragment (second %))))) fragments))
          merged (for [h (distinct (concat (map first secs)
                                           (sort-by #(.indexOf ^java.util.List order %) (map first all))))]
                   (str "### " h "\n\n" (str/join "\n" (for [[h' b] all :when (= h h')] b))))]
      (str head "\n"
           (str/join (for [part (cons preamble merged) :when (seq part)] (str part "\n\n")))
           (str/triml tail)))))

(defn changelog
  "CHANGELOG.md as it reads with the fragments merged in."
  []
  (with-fragments (slurp "CHANGELOG.md") (read-fragments)))

(defn- upgrade-heading
  "The UPGRADING.md heading for `base`, e.g. `## 1.3.x → 1.4.0`."
  [base]
  (let [[mj mi p] (map parse-long (str/split base #"\."))]
    (str "## " (cond (pos? p)  (str mj "." mi "." (dec p))
                     (pos? mi) (str mj "." (dec mi) ".x")
                     :else     (str (dec mj) ".x"))
         " → " base)))

(defn with-upgrades
  "`upgrading` with the fragments' `## Upgrading` parts appended to the section
   for `version`, added above the newest section if missing. Their `### `
   steps are numbered on from that section's."
  [upgrading version fragments]
  (let [notes (keep #(not-empty (second (split-fragment (second %)))) fragments)
        base  (str/replace version #"-.*$" "")
        found (re-find (re-pattern (str "(?s)^(.*?)(\n## [^\n]*→ " (java.util.regex.Pattern/quote base)
                                        "[ \\t]*\n.*?)(\n## .*|\\z)"))
                       upgrading)
        [before section after] (cond
                                 (empty? notes) nil
                                 found          (rest found)
                                 :else          (let [i (or (str/index-of upgrading "\n## ")
                                                            (throw (ex-info "UPGRADING.md has no ## section" {})))]
                                                  [(subs upgrading 0 i) (str "\n" (upgrade-heading base) "\n")
                                                   (subs upgrading i)]))
        k     (atom (count (re-seq #"(?m)^### " (str section))))]
    (if (empty? notes)
      upgrading
      (str before (str/trimr section) "\n\n"
           (str/replace (str/join "\n\n" notes) #"(?m)^### (?:\d+\.\s+)?"
                        (fn [_] (str "### " (swap! k inc) ". ")))
           "\n" after))))

(defn merge!
  "Merges the fragments under `root` into its CHANGELOG.md `[Unreleased]` and
   UPGRADING.md for `version`, then deletes them. Refuses fragments it would
   misfile."
  [root version]
  (when-let [fragments (seq (read-fragments root))]
    (when-let [found (seq (problems fragments))]
      (throw (ex-info (str/join "\n" found) {:type :release-refused})))
    (let [changelog (str (fs/path root "CHANGELOG.md"))
          upgrading (str (fs/path root "UPGRADING.md"))
          merged    (with-fragments (slurp changelog) fragments)
          upgraded  (with-upgrades (slurp upgrading) version fragments)]
      (spit changelog merged)
      (spit upgrading upgraded)
      (run! (comp fs/delete first) fragments))))
