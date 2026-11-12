(ns wagoe.tools.eval-report
  "`bb eval:report`: `bb eval:scaffold` results summarised per provider, the
   regression rule, and the stability-page line (BOU-909)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.eval-scaffold :as eval-scaffold]))

(def stability-nights
  "Results a provider needs before the stability line names it."
  30)

(defn read-results
  "Every result EDN under `dir`."
  [dir]
  (->> (fs/glob dir "**.edn")
       (map #(edn/read-string (slurp (str %))))
       (filter :provider)))

(defn- ran? [r] (= :ran (:status r)))

(defn- nights
  "`provider`'s nights that ran, oldest first."
  [results provider]
  (->> results (filter #(and (ran? %) (= provider (:provider %)))) (sort-by :date)))

(defn- days-before [date days]
  (str (.minusDays (java.time.LocalDate/parse date) days)))

(defn- within [results today days]
  (let [from (days-before today days)]
    (filter #(pos? (compare (:date %) from)) results)))

(defn- count-of
  "Median nightly mergeable count, rounded down: a claim a reader can hold us to."
  [ns]
  (some-> (eval-scaffold/median (map (comp :mergeable :summary) ns)) long))

(defn provider-summary [ns]
  (let [{:keys [provider model label summary]} (last ns)]
    {:provider     provider
     :model        model
     :label        label
     :nights       (count ns)
     :mergeable    (count-of ns)
     :of           (:of summary)
     :median-edits (eval-scaffold/median (keep (comp :median-edits :summary) ns))}))

(defn summaries
  "One summary per provider over the `days` before `today`."
  [results today days]
  (let [recent (within results today days)]
    (->> (distinct (map :provider recent))
         sort
         (keep #(let [ns (nights recent %)] (when (seq ns) (provider-summary ns)))))))

(defn summary-line [{:keys [label model nights mergeable of median-edits]}]
  (str label "/" model ": " mergeable " of " of " prompts mergeable without edits, median edits "
       (if median-edits (str median-edits) "n/a") " (" nights " nights)"))

(defn regression
  "The latest night when its mergeable count is more than one below the
   average of `provider`'s nights in the 7 days before it, else nil."
  [results provider]
  (let [ns (nights results provider)]
    (when-let [latest (last ns)]
      (let [from  (days-before (:date latest) 7)
            prior (filter #(not (neg? (compare (:date %) from))) (butlast ns))]
        (when (seq prior)
          (let [avg (/ (reduce + (map (comp :mergeable :summary) prior)) (double (count prior)))
                now (:mergeable (:summary latest))]
            (when (> (- avg now) 1)
              {:latest latest :average avg :mergeable now :nights (count prior)})))))))

(defn issue-body
  "Markdown for the regression issue: the numbers, then each failing prompt."
  [{:keys [latest average mergeable nights]}]
  (str "`bb scaffold ai` on " (:label latest) "/" (:model latest) " fell to " mergeable " of "
       (:of (:summary latest)) " prompts mergeable without edits on " (:date latest)
       ", against a " (format "%.1f" average) " average over the " nights " nights before it."
       " Commit " (:git-sha latest) ".\n"
       (str/join
        (for [{:keys [id prompt failure]} (:prompts latest)
              :when failure]
          (str "\n### " (name id) "\n\n" prompt "\n\n"
               "Failed: " (pr-str (dissoc failure :log :repair-diff)) "\n"
               (when-let [log (:log failure)] (str "\n```\n" log "\n```\n"))
               (when-let [d (not-empty (:repair-diff failure))] (str "\nRepair diff:\n\n```diff\n" d "\n```\n")))))))

(defn stability-line
  "The stability page's sentence, or nil until a provider has
   `stability-nights` nights. Providers short of that are left out."
  [results]
  (let [parts (for [p (sort (distinct (map :provider (filter ran? results))))
                    :let [ns (take-last stability-nights (nights results p))]
                    :when (>= (count ns) stability-nights)
                    :let [{:keys [label model mergeable of]} (provider-summary ns)]]
                [(str mergeable "/" of) (str "(" label "/" model ")")])]
    (when (seq parts)
      (let [[[n1 p1] & more] parts]
        (str "AI scaffolding, last " stability-nights " nights: " n1
             " prompts mergeable without edits " p1
             (str/join (for [[n p] more] (str ", " n " " p)))
             ".")))))

(def ^:private usage
  "Usage: bb eval:report <dir> [--days N] [--regression PROVIDER] [--stability-line]")

(defn -main
  "Exit 2 with the issue body on stdout when --regression finds one."
  [& args]
  (let [[dir & flags] args
        {:strs [--days --regression --stability-line]}
        (loop [m {} [f & more] flags]
          (cond (nil? f)                   m
                (= f "--stability-line")   (recur (assoc m f true) more)
                (#{"--days" "--regression"} f) (recur (assoc m f (first more)) (rest more))
                :else (do (binding [*out* *err*] (println usage)) (System/exit 1))))
        _       (when-not (and dir (fs/directory? dir)
                               (or (nil? --days) (some-> --days parse-long pos?)))
                  (binding [*out* *err*] (println usage)) (System/exit 1))
        results (read-results dir)]
    (cond
      --stability-line
      (some-> (stability-line results) println)

      --regression
      (if-let [r (regression results --regression)]
        (do (println (issue-body r)) (System/exit 2))
        (println (str "No regression for " --regression ".")))

      :else
      (let [days (parse-long (or --days "30"))
            today (str (java.time.LocalDate/now java.time.ZoneOffset/UTC))
            ss    (summaries results today days)]
        (println (str "Last " days " days:"))
        (if (seq ss)
          (doseq [s ss] (println (str "  " (summary-line s))))
          (println "  no results"))))))
