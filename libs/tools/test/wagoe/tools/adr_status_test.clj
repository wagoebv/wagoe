(ns wagoe.tools.adr-status-test
  "Every ADR says what it is, and says it in one place.

   Thirteen ADRs had no status at all and sixteen sat on `Proposed` while the
   thing they decided was shipped and in daily use — devtools, the dashboard,
   the MCP server, audience segmentation. A status that does not track reality
   is worse than none: a reader deciding whether to build on a decision reads
   `Proposed` and hesitates over code that has been there for months (BOU-329).

   Three places have to agree, and each drifted from the others: the `:status:`
   attribute, the `== Status` prose (or the older `*Status:*` line), and the
   index in README.adoc."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private known-statuses
  "What README.adoc tells a reader the vocabulary is."
  #{"Accepted" "Proposed" "Deprecated" "Superseded"})

(defn- adr-dir []
  (or (first (filter fs/exists? ["dev-docs/adr" "../../dev-docs/adr"]))
      (throw (ex-info "dev-docs/adr not found — cannot check the ADRs" {}))))

(defn- adrs
  "{\"001\" {:file .. :text .. :status ..}} for every ADR, index excluded."
  []
  (into (sorted-map)
        (for [f    (fs/glob (adr-dir) "ADR-*.adoc")
              :let [text (slurp (fs/file f))
                    num  (second (re-find #"ADR-(\d+)" (str (fs/file-name f))))]]
          [num {:file   (str (fs/file-name f))
                :text   text
                :status (second (re-find #"(?m)^:status:\s*(\S+)" text))}])))

(deftest ^:unit every-adr-declares-a-status
  (let [all (adrs)]
    (testing "the ADRs were found — otherwise this passes by checking nothing"
      (is (<= 30 (count all)) (str "only found " (count all) " ADRs")))

    (testing "each has a machine-readable :status:"
      (let [missing (for [[_ {:keys [file status]}] all :when (nil? status)] file)]
        (is (empty? missing)
            (str "no `:status:` attribute — a reader cannot tell whether to build "
                 "on these: " (pr-str (vec missing))))))

    (testing "and it is one of the four README.adoc documents"
      (doseq [[_ {:keys [file status]}] all
              :when status]
        (is (contains? known-statuses status)
            (str file " has :status: " status
                 ", which README.adoc does not list as a status"))))))

(deftest ^:unit the-status-attribute-and-the-prose-agree
  ;; The attribute is what tooling reads and the prose is what a person reads.
  ;; When they disagree, whichever one is wrong is the one being trusted.
  (doseq [[_ {:keys [file text status]}] (adrs)
          :when status
          :let  [prose (or (second (re-find #"(?ms)^== Status\s*\n+(.+?)$" text))
                           (second (re-find #"(?m)^\*Status:\*\s*(.+?)\s*\+?$" text)))]]
    (testing file
      (is (some? prose)
          (str file " has a :status: attribute and no Status section — the "
               "attribute is invisible to a reader of the rendered page"))
      (when prose
        (is (str/starts-with? (str/replace prose #"[*_]" "") status)
            (str file ": attribute says " status ", the page says "
                 (pr-str (subs prose 0 (min 60 (count prose))))))))))

(deftest ^:unit the-index-agrees-with-the-adrs
  ;; README.adoc repeats every status in a heading, so it is a second copy that
  ;; can rot. Fourteen entries said Proposed over ADRs whose code had shipped.
  (let [all   (adrs)
        index (slurp (fs/file (adr-dir) "README.adoc"))
        listed (into {} (for [[_ num status] (re-seq #"(?m)^==== ADR-(\d+):.*?\[(\w+)\]\s*$" index)]
                          [num status]))]

    (testing "the index was parsed — otherwise this is vacuous"
      (is (<= 25 (count listed)) (str "read " (count listed) " entries from README.adoc")))

    (testing "every listed status matches the ADR it names"
      (doseq [[num status] listed
              :let [actual (get-in all [num :status])]
              :when actual]
        (is (= actual status)
            (str "README.adoc lists ADR-" num " as " status
                 " and the file says " actual))))))
