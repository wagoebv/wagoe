(ns wagoe.reports.shell.service
  "Public convenience API for report generation.

   Provides `generate` (sync) and `generate-async` (queued via boundary-jobs).
   These functions live in the shell layer — not core — because they
   instantiate adapter records and call :data-source, both of which are
   side effects (FC/IS: Core → Shell is forbidden).

   Typical HTTP handler usage:

     (require '[wagoe.reports.shell.service :as reports])

     (defn export-handler [request]
       (let [output (reports/generate invoice-report {:invoice-id 42})]
         {:status  200
          :headers {\"Content-Type\"        \"application/pdf\"
                    \"Content-Disposition\" (str \"attachment; filename=\\\"\"
                                               (:filename output) \"\\\"\")}
          :body    (java.io.ByteArrayInputStream. (:bytes output))}))"
  (:require [wagoe.reports.core.report :as core]
            [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.adapters.excel :as excel]
            [wagoe.reports.shell.adapters.pdf :as pdf]
            [wagoe.reports.shell.adapters.word :as word]
            [wagoe.reports.shell.jobs-integration :as jobs-integration]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Synchronous generation
;; =============================================================================

(defn generate
  "Generate a report synchronously.

   Selects the appropriate backend based on (:type report-def):
     :pdf   → OpenHTMLtoPDF adapter
     :excel → docjure adapter
     :word  → Apache POI XWPF adapter

   Calls :data-source with opts if present; passes nil otherwise.

   Args:
     report-def - ReportDefinition map (from defreport or plain map)
     opts       - Arbitrary options forwarded to :data-source and the adapter
                  Recognised keys:
                    :css  - Additional CSS string appended to default.css (PDF only)

   Returns ReportOutput map: {:bytes byte-array :type :pdf|:excel|:word :filename \"...\"}

   Throws on schema validation failure or adapter error."
  [report-def opts]
  (log/info "Generating report" {:id (:id report-def) :type (:type report-def)})
  (let [generator (case (:type report-def)
                    :pdf   (pdf/create-pdf-generator)
                    :excel (excel/create-excel-generator)
                    :word  (word/create-word-generator)
                    (throw (ex-info "Unsupported report type"
                                    {:type          (:type report-def)
                                     :supported     #{:pdf :excel :word}
                                     :report-id     (:id report-def)})))
        data      (core/resolve-data report-def opts)]
    (ports/generate! generator report-def data opts)))

;; =============================================================================
;; Asynchronous generation (requires boundary-jobs)
;; =============================================================================

(defn generate-async
  "Queue report generation as a background job (requires boundary-jobs).

   The job will call generate and optionally notify via email when done.

   Args:
     report-def   - ReportDefinition map
     opts         - Same options as generate, plus:
                    :notify-email - Address to email the download link when ready
                    :storage-key  - Object storage key to save the output bytes

   Returns job-id (UUID string).

   Throws ex-info with :type :missing-dependency if boundary-jobs is not available."
  [report-def opts]
  (log/info "Queueing async report generation" {:id (:id report-def)})
  (jobs-integration/queue-report-job! report-def opts))
