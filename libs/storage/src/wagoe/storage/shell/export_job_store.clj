(ns wagoe.storage.shell.export-job-store
  "`IExportJobStore` on the application database."
  (:require [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.storage.ports :as ports])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.sql Timestamp)
           (java.time Instant)))

(def migration-resource "wagoe/storage/migrations/20261021100007-storage-export-jobs.up.sql")

(defn add-open-subject!
  "`open_subject`, naming the subject and requester of a :pending or :running
   job and null otherwise. Unique, so two requests racing to open a job for
   one subject cannot both succeed. Binary on MySQL, so ids differing in case do not
   collide. With the requester index the hourly limit counts by."
  [ctx]
  (db/add-column-if-not-exists! ctx :storage_export_jobs :open_subject "VARCHAR(272)")
  (db/ensure-binary-collation! ctx :storage_export_jobs :open_subject "VARCHAR(272)" false)
  (db/create-index-if-not-exists! ctx "uq_storage_export_jobs_open_subject" :storage_export_jobs [:open_subject]
                                  {:unique? true})
  (db/create-index-if-not-exists! ctx "idx_storage_export_jobs_requested_by" :storage_export_jobs
                                  [:requested_by :created_at]))

(defn up
  "Migratus entry point of the open-subject migration."
  [config]
  (add-open-subject! (db/context-of (db/migration-connectable config))))

(defn down
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    (db/drop-index-if-exists! ctx "idx_storage_export_jobs_requested_by" :storage_export_jobs)
    (db/drop-index-if-exists! ctx "uq_storage_export_jobs_open_subject" :storage_export_jobs)
    (db/drop-column-if-exists! ctx :storage_export_jobs :open_subject)))

(defn- ->ts [^Instant i] (some-> i Timestamp/from))

(defn- open-subject
  "Subject and requester, hashed to fit the column: one open job per pair, so
   an admin's export of a user and the user's own do not wait on each other."
  [{:keys [status subject requested-by]}]
  (when (#{:pending :running} status)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (str (name (:type subject)) ":" (:id subject) "\n" requested-by)
                                     StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" %) digest)))))

(defn- job->row [job]
  {:id           (str (:id job))
   :subject_type (name (get-in job [:subject :type]))
   :subject_id   (str (get-in job [:subject :id]))
   :requested_by (:requested-by job)
   :status       (name (:status job))
   :file_key     (:file-key job)
   :error        (some-> (:error job) (subs 0 (min 1024 (count (:error job)))))
   :created_at   (->ts (:created-at job))
   :finished_at  (->ts (:finished-at job))
   :expires_at   (->ts (:expires-at job))
   :open_subject (open-subject job)})

(defn- row->job [row]
  (when row
    {:id           (:id row)
     :subject      {:type (keyword (:subject-type row)) :id (:subject-id row)}
     :requested-by (:requested-by row)
     :status       (keyword (:status row))
     :file-key     (:file-key row)
     :error        (:error row)
     :created-at   (tc/string->instant (:created-at row))
     :finished-at  (tc/string->instant (:finished-at row))
     :expires-at   (tc/string->instant (:expires-at row))}))

(defrecord JdbcExportJobStore [ctx]
  ports/IExportJobStore
  (create-export-job! [_ job]
    ;; A savepoint: in a tenant request's transaction a refused insert would
    ;; abort it on PostgreSQL, and the caller reads the winner's job next.
    (try
      (db/in-savepoint (db/current-transaction)
                       #(db/execute-update! ctx {:insert-into :storage_export_jobs :values [(job->row job)]}))
      job
      (catch clojure.lang.ExceptionInfo e
        (when-not (db/unique-conflict? e) (throw e)))))

  (save-export-job! [_ job]
    (db/upsert! ctx :storage_export_jobs (job->row job) [:id])
    job)

  (find-export-job [_ id]
    (row->job (db/execute-one! ctx {:select [:*] :from [:storage_export_jobs] :where [:= :id (str id)]})))

  (open-export-job [_ {:keys [type id]} requested-by]
    (row->job (db/execute-one! ctx {:select   [:*]
                                    :from     [:storage_export_jobs]
                                    :where    [:and
                                               [:= :subject_type (name type)]
                                               [:= :subject_id (str id)]
                                               [:= :requested_by (str requested-by)]
                                               [:in :status ["pending" "running"]]]
                                    :order-by [[:created_at :desc]]
                                    :limit    1})))

  (export-jobs-created-before [_ cutoff open-before limit]
    (mapv row->job
          (db/execute-query! ctx {:select   [:*]
                                  :from     [:storage_export_jobs]
                                  :where    [:and
                                             [:< :created_at (->ts cutoff)]
                                             [:or
                                              [:not [:in :status ["pending" "running"]]]
                                              [:< :created_at (->ts open-before)]]]
                                  :order-by [[:created_at :asc] [:id :asc]]
                                  :limit    limit})))

  (count-export-jobs-requested-since [_ requested-by since]
    (:n (db/execute-one! ctx {:select [[:%count.* :n]]
                              :from   [:storage_export_jobs]
                              :where  [:and [:= :requested_by (str requested-by)] [:>= :created_at (->ts since)]]})))

  (remove-export-job! [_ id]
    (db/execute-update! ctx {:delete-from :storage_export_jobs :where [:= :id (str id)]})))

(defn create-tables!
  "Create the export job table when absent, for installations that boot
   without running `migrate up`."
  [ctx]
  (db/execute-migration-ddl! ctx migration-resource)
  (add-open-subject! ctx))

(defn create-export-job-store
  [ctx]
  (->JdbcExportJobStore ctx))
