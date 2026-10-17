(ns wagoe.reports.shell.jobs-integration
  "Async report generation on wagoe-jobs, with optional storage and email.

   jobs, storage and email are optional companions, so their functions are
   resolved at call time rather than required: reports works without them, and
   a request that needs one that is not enabled fails with :missing-dependency."
  (:require [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.registry :as registry]
            [clojure.tools.logging :as log]))

(def job-type
  "The job type `generate-async` enqueues and `process-report-job` handles."
  :reports/generate)

(def ^:private content-types
  {:pdf   "application/pdf"
   :excel "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
   :word  "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   :csv   "text/csv; charset=UTF-8"})

(defn- missing-dependency [module needed-for]
  (ex-info (str needed-for " needs " module ", which is not enabled. Add :wagoe/"
                module " to :active, or drop the option.")
           {:type :missing-dependency :module (str "wagoe-" module) :required-for needed-for}))

(defn- check-companions!
  "Throw when an option asks for a module the service was not given. Run at
   enqueue and again in the worker, which may be configured differently."
  [{:keys [file-storage email-sender]} {:keys [storage-key notify-email]}]
  (when (and storage-key (not file-storage))
    (throw (missing-dependency "storage" ":storage-key")))
  (when (and notify-email (not email-sender))
    (throw (missing-dependency "email" ":notify-email"))))

;; =============================================================================
;; Enqueue
;; =============================================================================

(defn queue-report-job!
  "Enqueue registered report `report-id` on the service's job queue, failing
   the request rather than the job when a module the opts need is missing."
  [{:keys [job-queue queue-name] :as service} report-id opts]
  (when-not job-queue
    (throw (missing-dependency "jobs" "Async report generation")))
  (check-companions! service opts)
  (let [report-def (registry/registered-report report-id)
        ;; A non-nil queue means wagoe-jobs is loaded. Its core builds the job
        ;; map: push hand-built one and the DB adapter threw (BOU-418).
        create-job (requiring-resolve 'wagoe.jobs.core.job/create-job)
        enqueue!   (requiring-resolve 'wagoe.jobs.ports/enqueue-job!)
        job        (create-job {:job-type job-type
                                :queue    queue-name
                                :args     {:report-id report-id :opts opts}
                                :metadata {:report-id report-id :report-type (:type report-def)}}
                               (random-uuid)
                               (java.time.Instant/now))]
    (log/info "Queueing report" {:report-id report-id :queue queue-name :job-id (:id job)})
    (enqueue! job-queue queue-name job)
    (:id job)))

;; =============================================================================
;; Job handler
;; =============================================================================

(defn- store!
  [file-storage storage-key {:keys [bytes type filename]}]
  ((requiring-resolve 'wagoe.storage.ports/store-file)
   file-storage
   {:bytes bytes :content-type (content-types type)}
   {:filename filename :path storage-key}))

(defn- notify!
  "Mail the link when the output was stored somewhere reachable, else the file."
  [email-sender from to report-id {:keys [bytes type filename]} stored]
  (let [send! (requiring-resolve 'wagoe.email.ports/send-email!)
        url   (:url stored)
        sent  (send! email-sender
                     (cond-> {:id         (random-uuid)
                              :to         [to]
                              :from       from
                              :subject    (str "Report ready: " filename)
                              :body       (str "Your report " (name report-id)
                                               (if url (str " is ready:\n\n" url "\n") " is attached.\n"))
                              :created-at (java.util.Date.)}
                       (not url) (assoc :attachments [{:filename     filename
                                                       :content-type (content-types type)
                                                       :content      bytes}])))]
    (when-not (:success? sent)
      (throw (ex-info (str "Report " report-id " was generated but the notification failed")
                      {:type :internal-error :report-id report-id :error (:error sent)})))))

(defn process-report-job
  "Handle a :reports/generate job: generate, then store and mail as asked.

   `service` is the ReportServiceProtocol that queued it, carrying
   `:file-storage`, `:email-sender` and `:from` when configured. Throws on
   failure; the worker records it and retries."
  [{:keys [file-storage email-sender from] :as service} {:keys [report-id opts]}]
  (check-companions! service opts)
  (let [{:keys [storage-key notify-email]} opts
        output (ports/generate service (registry/registered-report report-id) opts)
        stored (when storage-key (store! file-storage storage-key output))]
    (when notify-email
      (notify! email-sender from notify-email report-id output stored))
    (log/info "Report job completed" {:report-id report-id :stored (:key stored)})
    {:success? true
     :result   (cond-> {:report-id report-id
                        :filename  (:filename output)
                        :bytes     (count (:bytes output))}
                 stored       (assoc :storage-key (:key stored) :url (:url stored))
                 notify-email (assoc :notified notify-email))}))
