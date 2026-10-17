(ns wagoe.reports.ports
  "Protocol definitions (interfaces) for the reports module.

   Applications call ReportServiceProtocol. Adapters (PDF, Excel, Word, CSV)
   implement ReportGeneratorProtocol.")

;; =============================================================================
;; Report Generator Protocol
;; =============================================================================

(defprotocol ReportGeneratorProtocol
  "Abstraction over PDF and Excel generation backends."

  (generate! [this report-def data opts]
    "Generate report bytes synchronously.

     Args:
       this       - Generator instance (OpenHtmlToPdfGenerator or DocjureExcelGenerator)
       report-def - ReportDefinition map (satisfies schema/ReportDefinition)
       data       - Seq of records (nil if :data-source not used)
       opts       - Arbitrary options map passed through from the caller

     Returns ReportOutput map:
       {:bytes    byte-array
        :type     :pdf | :excel
        :filename \"report.pdf\"}")

  (supported-type? [this report-type]
    "Returns true if this generator handles the given report type.

     Args:
       this        - Generator instance
       report-type - :pdf or :excel"))

;; =============================================================================
;; Report Service Protocol
;; =============================================================================

(defprotocol ReportServiceProtocol
  "What an application calls. The component behind `(ig/ref :wagoe/reports)`
   implements it, so app code needs only this namespace."

  (generate [this report opts]
    "Generate a report now. `report` is a ReportDefinition map or the id of a
     registered one. `opts` reaches :data-source and the adapter.

     Returns ReportOutput: {:bytes byte-array :type kw :filename \"...\"}")

  (generate-async [this report opts]
    "Queue a registered report on wagoe-jobs. Only the id travels: a worker
     finds the definition in its own registry, so register it on every node
     that runs workers.

     Extra opts:
       :storage-key  - storage path to save the output under (needs wagoe-storage)
       :notify-email - address to mail the link, or the file, to (needs wagoe-email)

     Returns the job id. Throws :missing-dependency when a module the request
     needs is not enabled.")

  (register-report! [this report-def]
    "Register a ReportDefinition by its :id, as `defreport` does.")

  (list-reports [this]
    "Ids of every registered report."))
