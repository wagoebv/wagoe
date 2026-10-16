(ns wagoe.reports.shell.module-wiring
  "Integrant wiring for the reports module.

   `:wagoe/reports` is a ReportServiceProtocol. Take `(ig/ref :wagoe/reports)`
   and call `wagoe.reports.ports/generate` on it.

     :wagoe/reports
     {:enabled? true
      :from     \"reports@example.com\"   ; sender of :notify-email mail
      :queue    :default}                ; queue generate-async enqueues on

   The job queue, storage and email refs are added only for the modules that
   are enabled, so none of them is required."
  (:require [integrant.core :as ig]
            [wagoe.reports.shell.jobs-integration :as jobs-integration]
            [wagoe.reports.shell.service :as service]))

(defmethod ig/init-key :wagoe/reports
  [_ {:keys [job-queue storage email queue from]}]
  (service/create-report-service {:job-queue    job-queue
                                  :queue-name   queue
                                  ;; :wagoe/storage is {:storage <IFileStorage> …}. Its
                                  ;; IStorageService is not on storage's port.
                                  :file-storage (:storage storage)
                                  :email-sender email
                                  :from         from}))

(defmethod ig/halt-key! :wagoe/reports [_ _service] nil)

(defmethod ig/init-key :wagoe.reports/job-handlers
  [_ {:keys [service]}]
  {jobs-integration/job-type (partial jobs-integration/process-report-job service)})

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`."
  [settings {:keys [enabled]}]
  (let [on? #(contains? enabled %)]
    (cond-> {:components
             {:wagoe/reports (cond-> (or settings {})
                               (on? :wagoe/jobs)    (assoc :job-queue (ig/ref :wagoe/job-queue))
                               (on? :wagoe/storage) (assoc :storage (ig/ref :wagoe/storage))
                               (on? :wagoe/email)   (assoc :email (ig/ref :wagoe/email)))}}
      (on? :wagoe/jobs)
      (-> (assoc-in [:components :wagoe.reports/job-handlers] {:service (ig/ref :wagoe/reports)})
          (assoc :job-handlers [(ig/ref :wagoe.reports/job-handlers)])))))
