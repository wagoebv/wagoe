(ns wagoe.reports.shell.service
  "The report service behind `:wagoe/reports`.

   Applications take `(ig/ref :wagoe/reports)` and call it through
   `wagoe.reports.ports/ReportServiceProtocol`, so they require no shell
   namespace of this module."
  (:require [wagoe.reports.core.report :as core]
            [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.adapters.csv :as csv]
            [wagoe.reports.shell.adapters.excel :as excel]
            [wagoe.reports.shell.adapters.pdf :as pdf]
            [wagoe.reports.shell.adapters.word :as word]
            [wagoe.reports.shell.jobs-integration :as jobs-integration]
            [wagoe.reports.shell.registry :as registry]
            [clojure.tools.logging :as log]))

(def ^:private generators
  {:pdf   pdf/create-pdf-generator
   :excel excel/create-excel-generator
   :word  word/create-word-generator
   :csv   csv/create-csv-generator})

;; =============================================================================
;; Synchronous generation
;; =============================================================================

(defn generate
  "Generate `report-def` synchronously with the adapter for its :type.

   Calls :data-source with opts when present. Recognised opts:
     :css      - CSS appended to default.css (PDF only)
     :bom?     - prefix a UTF-8 byte order mark (CSV only)
     :zone-id  - java.time.ZoneId for :date columns

   Returns ReportOutput: {:bytes byte-array :type kw :filename \"...\"}"
  [report-def opts]
  (log/info "Generating report" {:id (:id report-def) :type (:type report-def)})
  (let [create (or (generators (:type report-def))
                   (throw (ex-info "Unsupported report type"
                                   {:type        :validation-error
                                    :report-type (:type report-def)
                                    :supported   (set (keys generators))
                                    :report-id   (:id report-def)})))]
    (ports/generate! (create) report-def (core/resolve-data report-def opts) opts)))

;; =============================================================================
;; Service component
;; =============================================================================

(defn- resolve-report
  "A definition map as given, or the registered one for an id."
  [report]
  (if (keyword? report)
    (or (registry/get-report report)
        (throw (ex-info (str "Report " report " is not registered")
                        {:type :not-found :report-id report})))
    report))

(defrecord ReportService [job-queue queue-name file-storage email-sender from]
  ports/ReportServiceProtocol

  (generate [_ report opts]
    (generate (resolve-report report) opts))

  (generate-async [this report opts]
    (jobs-integration/queue-report-job! this (resolve-report report) opts))

  (register-report! [_ report-def]
    (registry/register-report! report-def))

  (list-reports [_]
    (registry/list-reports)))

(defn create-report-service
  "A ReportService. Every collaborator is optional: without `:job-queue`
   generate-async refuses, without `:file-storage` it refuses :storage-key,
   without `:email-sender` :notify-email."
  [{:keys [job-queue queue-name file-storage email-sender from]}]
  (->ReportService job-queue (or queue-name :default) file-storage email-sender
                   (or from "no-reply@localhost")))
