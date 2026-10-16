(ns wagoe.reports.core.csv
  "RFC 4180 CSV text from a report's first :table section, the one the Excel
   adapter reads."
  (:require [clojure.string :as str]
            [wagoe.reports.core.report :as report]))

(def ^:private formula-starts
  "Leading characters a spreadsheet reads as a formula (OWASP CSV injection)."
  #{\= \+ \- \@ \tab \return})

(defn neutralise-formula
  "Prefix `s` with a single quote when a spreadsheet would evaluate it."
  [s]
  (if (and (seq s) (formula-starts (first s)))
    (str "'" s)
    s))

(defn quote-field
  "Quote `s` per RFC 4180 when it holds a comma, quote, CR or LF."
  [s]
  (if (re-find #"[\",\r\n]" s)
    (str \" (str/replace s "\"" "\"\"") \")
    s))

(defn- cell
  "A formatted cell. Numbers skip the formula guard: `-5` is data, and a quote
   in front of it would turn it into text."
  [record {:keys [key format]} formatting-context]
  (let [raw (get record key)
        s   (str (report/format-cell* raw format formatting-context))]
    (if (number? raw) s (neutralise-formula s))))

(defn csv-rows
  "Header plus one row per record, as vectors of unquoted strings."
  [columns data formatting-context]
  (into [(mapv (comp neutralise-formula str :label) columns)]
        (map (fn [record] (mapv #(cell record % formatting-context) columns)))
        data))

(defn ->csv
  "CSV text for the first :table section of `sections`, CRLF line endings."
  [sections data formatting-context]
  (let [columns (:columns (first (filter #(= :table (:type %)) sections)))]
    (->> (csv-rows columns data formatting-context)
         (map #(str (str/join "," (map quote-field %)) "\r\n"))
         (apply str))))
