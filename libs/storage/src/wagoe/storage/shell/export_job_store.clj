(ns wagoe.storage.shell.export-job-store
  "`IExportJobStore` on the application database."
  (:require [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.storage.ports :as ports])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(def migration-resource "wagoe/storage/migrations/20261021100007-storage-export-jobs.up.sql")

(defn- ->ts [^Instant i] (some-> i Timestamp/from))

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
   :expires_at   (->ts (:expires-at job))})

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
  (save-export-job! [_ job]
    (let [row (job->row job)]
      (db/with-transaction [tx ctx]
        (when (zero? (db/execute-update! tx {:update :storage_export_jobs
                                             :set    (dissoc row :id)
                                             :where  [:= :id (:id row)]}))
          (db/execute-update! tx {:insert-into :storage_export_jobs :values [row]}))))
    job)

  (find-export-job [_ id]
    (row->job (db/execute-one! ctx {:select [:*] :from [:storage_export_jobs] :where [:= :id (str id)]})))

  (open-export-job [_ {:keys [type id]}]
    (row->job (db/execute-one! ctx {:select   [:*]
                                    :from     [:storage_export_jobs]
                                    :where    [:and
                                               [:= :subject_type (name type)]
                                               [:= :subject_id (str id)]
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

  (remove-export-job! [_ id]
    (db/execute-update! ctx {:delete-from :storage_export_jobs :where [:= :id (str id)]})))

(defn create-tables!
  "Create the export job table when absent, for installations that boot
   without running `migrate up`."
  [ctx]
  (db/execute-migration-ddl! ctx migration-resource))

(defn create-export-job-store
  [ctx]
  (->JdbcExportJobStore ctx))
