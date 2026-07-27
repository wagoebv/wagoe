(ns wagoe.reports.shell.jobs-integration
  "Optional integration with boundary-jobs for async report generation.

   This namespace provides async report generation via the jobs module.
   The jobs module is an OPTIONAL dependency — add it to your deps.edn:

     {:deps {org.boundary-app/boundary-jobs {:mvn/version \"0.1.0\"}}}

   If the jobs module is not available, async functions will throw descriptive
   errors with :type :missing-dependency.

   Usage:
     ;; Queue report for async generation
     (queue-report-job! report-def {:invoice-id 42})

     ;; Register report job handler with jobs module
     (register-report-job-handler! job-registry)

   The job handler will:
     - Resolve data via :data-source (if defined)
     - Generate the report bytes
     - Optionally store via boundary-storage (:storage-key in opts)
     - Optionally send an email notification (:notify-email in opts)"
  (:require [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.adapters.excel :as excel]
            [wagoe.reports.shell.adapters.pdf :as pdf]
            [wagoe.reports.shell.adapters.word :as word]
            [wagoe.reports.core.report :as core]
            [clojure.stacktrace]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Job Queue Integration
;; =============================================================================

(defn queue-report-job!
  "Queue a report for async generation via boundary-jobs.

   Args:
     report-def - ReportDefinition map (from defreport or plain map)
     opts       - Options forwarded to the job handler:
                  :notify-email - Email address for completion notification
                  :storage-key  - Object key for boundary-storage output
                  + any other opts passed to generate

   Returns job-id (UUID string).

   Throws ex-info with :type :missing-dependency if boundary-jobs is unavailable."
  [report-def opts]
  (if-let [enqueue-fn (requiring-resolve 'wagoe.jobs.ports/enqueue-job!)]
    (let [job {:job-type :generate-report
               :args     {:report-def report-def
                          :opts       opts}
               :priority :normal
               :metadata {:report-id   (:id report-def)
                          :report-type (:type report-def)}}]
      (log/info "Queueing report for async generation via jobs module"
                {:report-id   (:id report-def)
                 :report-type (:type report-def)})
      (enqueue-fn nil :reports job))
    (throw (ex-info "Jobs module not available. Add org.boundary-app/boundary-jobs to deps.edn"
                    {:type          :missing-dependency
                     :module        "boundary-jobs"
                     :required-for  "Async report generation"
                     :documentation "https://github.com/thijs-creemers/boundary/tree/main/libs/jobs"}))))

;; =============================================================================
;; Report Job Processor
;; =============================================================================

(defn process-report-job
  "Job handler for generating reports.

   Called by the jobs module worker pool. Generates the report, optionally
   stores via boundary-storage, and optionally sends an email notification.

   Job args structure:
     :report-def - ReportDefinition map
     :opts       - Options map (may include :notify-email, :storage-key)

   Returns:
     {:success? true  :result {:report-id :k :filename \"f.pdf\" :bytes N}}
     {:success? false :error  {:message \"...\" :type \"ReportJobError\"}}"
  [job-args]
  (try
    (let [{:keys [report-def opts]} job-args
          _ (log/debug "Processing report job"
                       {:report-id   (:id report-def)
                        :report-type (:type report-def)})
          generator (case (:type report-def)
                      :pdf   (pdf/create-pdf-generator)
                      :excel (excel/create-excel-generator)
                      :word  (word/create-word-generator))
          data      (core/resolve-data report-def opts)
          result    (ports/generate! generator report-def data opts)]
      (log/info "Report job completed successfully"
                {:report-id (:id report-def)
                 :filename  (:filename result)
                 :bytes     (count (:bytes result))})
      {:success? true
       :result   {:report-id (:id report-def)
                  :filename  (:filename result)
                  :bytes     (count (:bytes result))}})
    (catch Exception e
      (log/error e "Report job threw exception"
                 {:report-id       (get-in job-args [:report-def :id])
                  :error-message   (.getMessage e)})
      {:success? false
       :error    {:message    (.getMessage e)
                  :type       "ReportJobError"
                  :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e))}})))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-report-job-handler!
  "Register the :generate-report job handler with boundary-jobs.

   After registration, jobs with :job-type :generate-report will be processed
   by process-report-job.

   Args:
     job-registry - IJobRegistry instance from boundary-jobs

   Returns :generate-report (the job-type keyword).

   Throws ex-info with :type :missing-dependency if boundary-jobs is unavailable."
  [job-registry]
  (if-let [register-fn (requiring-resolve 'wagoe.jobs.ports/register-handler!)]
    (do
      (log/info "Registering report job handler with jobs module"
                {:job-type :generate-report})
      (register-fn job-registry :generate-report process-report-job))
    (throw (ex-info "Jobs module not available. Add org.boundary-app/boundary-jobs to deps.edn"
                    {:type          :missing-dependency
                     :module        "boundary-jobs"
                     :required-for  "Report job handler registration"
                     :documentation "https://github.com/thijs-creemers/boundary/tree/main/libs/jobs"}))))
