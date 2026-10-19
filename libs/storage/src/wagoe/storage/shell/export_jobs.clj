(ns wagoe.storage.shell.export-jobs
  "Data export as a background job, for a subject too large to zip during a
   request: the job writes the archive to file storage, and its requester
   downloads it for a day. Needs the jobs module.

   `ctx` here is {:store IExportJobStore :file-storage IFileStorage
   :provider kw :data-export IDataExport :job-queue IJobQueue :queue kw}."
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.storage.core.export-jobs :as core]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.data-export :as data-export])
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]))

(def job-type :storage/data-export)

(def ^:private signed-url-seconds
  "How long a signed download URL lives: long enough to follow the redirect."
  300)

(defn- enqueue!
  "Queue the job that produces `export-id`. Resolved, as reports does: the
   jobs module is optional to storage, and loaded whenever there is a queue."
  [job-queue queue export-id]
  (let [create-job (requiring-resolve 'wagoe.jobs.core.job/create-job)
        enqueue    (requiring-resolve 'wagoe.jobs.ports/enqueue-job!)]
    (enqueue job-queue queue (create-job {:job-type job-type :queue queue :args {:export-id export-id}}
                                         (random-uuid) (Instant/now)))))

(defn- open-job
  "The open job for `subject`, unless it is older than a download window: a
   worker that died mid-export leaves one running, and it must not block
   every later request."
  [store subject ^Instant now]
  (when-let [job (ports/open-export-job store subject)]
    (when (.isAfter ^Instant (:created-at job) (.minus now ^java.time.Duration core/download-window))
      job)))

(defn request-export!
  "Queue an export of `subject` for `requested-by`; the job already open for
   it, if there is one."
  [{:keys [store job-queue queue]} subject requested-by]
  (let [now (Instant/now)]
    (or (open-job store subject now)
        (let [job (ports/save-export-job! store (core/new-job (str (random-uuid)) subject requested-by now))]
          (try
            (enqueue! job-queue (or queue :default) (:id job))
            (catch Exception e
              ;; Left behind, it would be the open job every later request returns.
              (ports/remove-export-job! store (:id job))
              (throw e)))
          job))))

(defn- archive-bytes
  [data-export subject]
  (let [doc (privacy/export-subject data-export subject)
        out (ByteArrayOutputStream.)]
    (privacy/write-archive! data-export doc out)
    (.toByteArray out)))

(defn run-export-job
  "The handler of `job-type`. The archive is private and kept out of the
   upload index, so it is in no later export."
  [{:keys [store file-storage data-export]} {:keys [export-id]}]
  (let [job (ports/find-export-job store export-id)]
    (cond
      ;; Purged before it ran: nothing is waiting for it.
      (nil? job)
      {:success? true :result {:export-id export-id :skipped :gone}}

      ;; Delivered again after it finished: a second archive would replace the
      ;; first's key, and no retention run would find the first. A failed one
      ;; told its user to ask again; retried, it would flip back to running.
      (#{:done :failed} (:status job))
      {:success? true :result {:export-id export-id :skipped (:status job)}}

      :else
      (let [stored (volatile! nil)]
        (try
          (ports/save-export-job! store (assoc job :status :running))
          (vreset! stored (ports/store-file file-storage
                                            {:bytes (archive-bytes data-export (:subject job)) :content-type "application/zip"}
                                            {:filename (str export-id ".zip") :path "data-exports" :visibility :private}))
          (ports/save-export-job! store (core/finished job (:key @stored) (Instant/now)))
          {:success? true :result {:export-id export-id}}
          (catch Exception e
            (log/error e "Data export job failed" {:export-id export-id :subject (:subject job)})
            ;; An archive no job points at is personal data nothing deletes.
            (when-let [k (:key @stored)]
              (try (ports/delete-file file-storage k)
                   (catch Exception de (log/error de "Could not delete an unrecorded export archive" {:key k}))))
            (ports/save-export-job! store (core/failed job (ex-message e) (Instant/now)))
            {:success? false
             :error    {:type :data-export-failed :message (str "Data export " export-id " failed")}}))))))

(defn download-response
  "The archive of `job`: a redirect to a short-lived signed URL where the
   storage serves one itself, else the bytes."
  [{:keys [file-storage provider]} {:keys [id file-key]}]
  (let [headers {"Cache-Control" "no-store"}]
    (if-let [url (when (#{:s3 :gcs} provider)
                   (ports/generate-signed-url file-storage file-key signed-url-seconds))]
      {:status 302 :headers (assoc headers "Location" url) :body ""}
      (if-let [file (ports/retrieve-file file-storage file-key)]
        {:status  200
         :headers (assoc headers
                         "Content-Type"        "application/zip"
                         "Content-Disposition" (str "attachment; filename=\"data-export-" id ".zip\""))
         :body    (java.io.ByteArrayInputStream. ^bytes (:bytes file))}
        (throw (ex-info "The export archive is gone" {:type :not-found :export-id id}))))))

(defrecord ExportArchiveRetention [store file-storage]
  privacy/IRetentionPolicy
  (purge-before! [_ cutoff]
    (data-export/purge-in-pages! #(ports/export-jobs-created-before
                                   store cutoff (.minus (Instant/now) ^java.time.Duration core/download-window) %)
                                 :id
                                 (fn [{:keys [id file-key]}]
                                   (when file-key (data-export/delete-or-confirm-gone! file-storage file-key))
                                   (ports/remove-export-job! store id))
                                 "export archives")))
