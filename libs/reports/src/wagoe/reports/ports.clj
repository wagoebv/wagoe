(ns wagoe.reports.ports
  "Protocol definitions (interfaces) for the reports module.

   All adapters (PDF, Excel) implement ReportGeneratorProtocol.
   This keeps the core layer decoupled from any specific rendering backend.")

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
