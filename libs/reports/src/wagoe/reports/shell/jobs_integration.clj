(ns wagoe.reports.shell.jobs-integration
  "Async report generation on wagoe-jobs, with optional storage and email.

   jobs, storage and email are optional companions, so their ports are resolved
   at call time rather than required: reports works without them, and a request
   that needs one that is not enabled fails with :missing-dependency."
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

(defn- port-fn
  "A port function of an optional companion library, or a :missing-dependency."
  [sym module needed-for]
  (or (try (requiring-resolve sym)
           (catch java.io.FileNotFoundException _ nil))
      (throw (missing-dependency module needed-for))))

;; =============================================================================
;; Enqueue
;; =============================================================================

(defn- job
  "A job map with the fields `wagoe.jobs.core.job/create-job` sets. Built here
   so reports reaches only the jobs port, not its core."
  [queue-name args metadata]
  (let [now (java.time.Instant/now)]
    {:id          (random-uuid)
     :job-type    job-type
     :queue       queue-name
     :args        args
     :status      :pending
     :priority    :normal
     :retry-count 0
     :max-retries 3
     :created-at  now
     :updated-at  now
     :metadata    metadata}))

(defn queue-report-job!
  "Enqueue `report-def` on the service's job queue. Checks up front that every
   module the opts need is wired, so a missing one fails the request rather
   than the job."
  [{:keys [job-queue queue-name file-storage email-sender]} report-def opts]
  (let [id (:id report-def)]
    (when-not job-queue
      (throw (missing-dependency "jobs" "Async report generation")))
    (when (and (:storage-key opts) (not file-storage))
      (throw (missing-dependency "storage" ":storage-key")))
    (when (and (:notify-email opts) (not email-sender))
      (throw (missing-dependency "email" ":notify-email")))
    (when-not (registry/get-report id)
      (throw (ex-info (str "Report " id " is not registered. generate-async sends only the id;"
                           " register the definition with register-report! or defreport.")
                      {:type :validation-error :report-id id})))
    (let [enqueue! (port-fn 'wagoe.jobs.ports/enqueue-job! "jobs" "Async report generation")
          j        (job queue-name {:report-id id :opts opts}
                        {:report-id id :report-type (:type report-def)})]
      (log/info "Queueing report" {:report-id id :queue queue-name :job-id (:id j)})
      (enqueue! job-queue queue-name j)
      (:id j))))

;; =============================================================================
;; Job handler
;; =============================================================================

(defn- store!
  [file-storage storage-key {:keys [bytes type filename]}]
  (let [store-file (port-fn 'wagoe.storage.ports/store-file "storage" ":storage-key")]
    (store-file file-storage
                {:bytes bytes :content-type (content-types type)}
                {:filename filename :path storage-key})))

(defn- notify!
  "Mail the link when the output was stored somewhere reachable, else the file."
  [email-sender from to report-id {:keys [bytes type filename]} stored]
  (let [send! (port-fn 'wagoe.email.ports/send-email! "email" ":notify-email")
        url   (:url stored)
        sent  (send! email-sender
                     (cond-> {:id         (random-uuid)
                              :to         [to]
                              :from       from
                              :subject    (str "Report ready: " filename)
                              :body       (if url
                                            (str "Your report " (name report-id) " is ready:\n\n" url "\n")
                                            (str "Your report " (name report-id) " is attached.\n"))
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
  (let [report-def (or (registry/get-report report-id)
                       (throw (ex-info (str "Report " report-id " is not registered on this node")
                                       {:type :not-found :report-id report-id})))
        {:keys [storage-key notify-email]} opts
        output     (ports/generate service report-def opts)
        stored     (when storage-key
                     (when-not file-storage
                       (throw (missing-dependency "storage" ":storage-key")))
                     (store! file-storage storage-key output))]
    (when notify-email
      (when-not email-sender
        (throw (missing-dependency "email" ":notify-email")))
      (notify! email-sender from notify-email report-id output stored))
    (log/info "Report job completed" {:report-id report-id :stored (:key stored)})
    {:success? true
     :result   (cond-> {:report-id report-id
                        :filename  (:filename output)
                        :bytes     (count (:bytes output))}
                 stored       (assoc :storage-key (:key stored) :url (:url stored))
                 notify-email (assoc :notified notify-email))}))
