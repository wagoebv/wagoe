(ns wagoe.tools.report
  "One shape for a diagnostic result, and the two ways to render it.

   `bb doctor`, `bb doctor:env` and `bb guide next` each built the same map —
   `{:id :level :msg :fix}` — and each carried its own byte-identical copy of the
   formatter. This is that copy, once.

   The second renderer is why this exists rather than being a tidy-up: `wagoe
   doctor` runs all three and has to end with a single next action. Picking that
   action out of formatted terminal text means parsing ANSI colour and column
   padding, so `--edn` prints the results as data instead (BOU-324)."
  (:require [clojure.string :as str]
            [wagoe.tools.ansi :refer [bold green yellow red dim]]))

(def ^:private levels
  "Worst first. The order `wagoe doctor` sorts by when it picks what to say."
  [:error :warn :pass])

(defn tally
  "{:passed n :warnings n :errors n} for a seq of results."
  [results]
  {:passed   (count (filter #(= :pass (:level %)) results))
   :warnings (count (filter #(= :warn (:level %)) results))
   :errors   (count (filter #(= :error (:level %)) results))})

(defn format-result
  "One result as a terminal line, with its fix indented underneath."
  [{:keys [id level msg fix]}]
  (let [icon   (case level
                 :pass  (green "✓")
                 :warn  (yellow "⚠")
                 :error (red "✗"))
        id-str (format "%-20s" (if id (name id) ""))]
    (str "  " icon " " id-str " " msg
         (when fix
           (str "\n" (dim (str "                       Fix: " fix)))))))

(defn print-results
  "Print `results` under `title`. Returns the tally."
  [title results]
  (println)
  (println (bold title))
  (println)
  (doseq [r results]
    (println (format-result r)))
  (let [{:keys [passed warnings errors] :as summary} (tally results)]
    (println)
    (println (str "Summary: " (green (str passed " passed"))
                  ", " (yellow (str warnings " warning" (when (not= warnings 1) "s")))
                  ", " (red (str errors " error" (when (not= errors 1) "s")))))
    summary))

(defn print-edn
  "Print `results` as a data map, for a caller that has to reason about them.

   Nothing else may go to stdout in this mode — the reader is `read-string`, not
   a person. A check that prints progress as it runs has to be silent here."
  [section results]
  (prn {:section section
        :summary (tally results)
        :results (vec (sort-by #(.indexOf ^java.util.List levels (:level %)) results))}))

(defn edn-requested?
  "Whether `args` asks for machine-readable output."
  [args]
  (boolean (some #{"--edn"} args)))

(defn worst
  "The result a reader should act on first, or nil if everything passed.

   Errors before warnings, and within a level the first one reported — checks
   are ordered from the most fundamental outwards, so the first error is the one
   the others are most likely downstream of."
  [results]
  (or (first (filter #(= :error (:level %)) results))
      (first (filter #(= :warn (:level %)) results))))

(defn next-action
  "One line telling the reader what to do about `result`, or nil.

   The `:fix` is often several lines of code to paste. This is the one-line
   version: what is wrong and where to read the rest."
  [result]
  (when result
    (let [head (first (str/split-lines (or (:fix result) (:msg result))))]
      (str (:msg result)
           (when (:fix result) (str "\n    " head))))))
