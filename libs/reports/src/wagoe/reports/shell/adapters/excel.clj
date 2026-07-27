(ns wagoe.reports.shell.adapters.excel
  "Excel generation adapter using docjure (Apache POI wrapper).

   Builds one worksheet per entry in :sheets, or derives a single sheet
   from :sections when :sheets is not provided.

   Usage:
     (def xl-gen (create-excel-generator))
     (generate! xl-gen my-report-def data {})
     ;; => {:bytes #bytes[...] :type :excel :filename \"my-report.xlsx\"}"
  (:require [wagoe.reports.core.report :as core]
            [wagoe.reports.ports :as ports]
            [clojure.tools.logging :as log]
            [dk.ative.docjure.spreadsheet :as ss])
  (:import [java.io ByteArrayOutputStream]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- first-table-columns
  "Extract :columns from the first :table section, or nil."
  [sections]
  (:columns (first (filter #(= :table (:type %)) sections))))

(defn- sheet-rows
  "Build header + data rows for one sheet spec."
  [{:keys [columns data formatting-context]}]
  (let [header-row (mapv :label columns)
        data-rows  (mapv (fn [record] (core/map-columns* columns record formatting-context)) data)]
    (into [header-row] data-rows)))

(defn- add-sheet-data!
  "Add a new sheet with header + data rows to wb. Returns wb."
  [wb {:keys [name] :as sheet-spec}]
  (ss/add-sheet! wb name)
  (ss/add-rows! (ss/select-sheet name wb) (sheet-rows sheet-spec))
  wb)

;; =============================================================================
;; Docjure Excel generator record
;; =============================================================================

(defrecord DocjureExcelGenerator [])

(extend-protocol ports/ReportGeneratorProtocol
  DocjureExcelGenerator

  (generate! [_this report-def data opts]
    (log/debug "Generating Excel report" {:id (:id report-def)})
    (let [formatting-context {:zone-id (or (:zone-id opts) (java.time.ZoneId/systemDefault))}
          sheet-title (or (:title report-def) (name (:id report-def)))
          sheets      (or (:sheets report-def)
                          [{:name    sheet-title
                            :columns (first-table-columns (:sections report-def))
                            :data    data
                            :formatting-context formatting-context}])
          ;; Create workbook from the first sheet, then add the rest
          normalized-sheets (map #(assoc % :formatting-context formatting-context) sheets)
          first-sheet (first normalized-sheets)
          rest-sheets (rest normalized-sheets)
          wb          (reduce add-sheet-data!
                              (ss/create-workbook (:name first-sheet)
                                                  (sheet-rows first-sheet))
                              rest-sheets)
          baos        (ByteArrayOutputStream.)]
      (ss/save-workbook-into-stream! baos wb)
      (let [result {:bytes    (.toByteArray baos)
                    :type     :excel
                    :filename (or (:filename report-def)
                                  (str (name (:id report-def)) ".xlsx"))}]
        (log/debug "Excel report generated"
                   {:id       (:id report-def)
                    :filename (:filename result)
                    :bytes    (count (:bytes result))})
        result)))

  (supported-type? [_ t] (= t :excel)))

(defn create-excel-generator
  "Create a new docjure-backed Excel generator."
  []
  (->DocjureExcelGenerator))
