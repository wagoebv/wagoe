(ns wagoe.reports.shell.adapters.csv
  "CSV adapter: the first :table section of a report as UTF-8 RFC 4180 text.

   `:bom? true` in opts prefixes a UTF-8 byte order mark, without which Excel
   misreads non-ASCII characters."
  (:require [wagoe.reports.core.csv :as csv]
            [wagoe.reports.core.report :as report]
            [wagoe.reports.ports :as ports])
  (:import [java.nio.charset StandardCharsets]))

(defrecord CsvGenerator []
  ports/ReportGeneratorProtocol

  (generate! [_ report-def data opts]
    ;; Without columns every row is empty: a file of blank lines, not an error.
    (when-not (seq (report/first-table-columns (:sections report-def)))
      (throw (ex-info "A :csv report needs a :table section with :columns"
                      {:type :validation-error :report-id (:id report-def)})))
    (let [ctx  {:zone-id (or (:zone-id opts) (java.time.ZoneId/systemDefault))}
          text (csv/->csv (:sections report-def) data ctx)]
      {:bytes    (.getBytes ^String (cond->> text (:bom? opts) (str "\uFEFF"))
                            StandardCharsets/UTF_8)
       :type     :csv
       :filename (or (:filename report-def) (str (name (:id report-def)) ".csv"))}))

  (supported-type? [_ t] (= t :csv)))

(defn create-csv-generator
  "Create a CSV generator."
  []
  (->CsvGenerator))
