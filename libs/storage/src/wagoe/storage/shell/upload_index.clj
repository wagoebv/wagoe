(ns wagoe.storage.shell.upload-index
  "`IUploadIndex` on the application database, and the boot step that creates
   its table."
  (:require [clojure.tools.logging :as log]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.storage.ports :as ports])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(def migration-resource "wagoe/storage/migrations/20261021100001-storage-uploads.up.sql")

(defn create-tables!
  "Create the upload index table when absent, for installations that boot
   without running `migrate up`."
  [ctx]
  (db/execute-migration-ddl! ctx migration-resource)
  (log/info "Upload index ready"))

(defn- ->ts [^Instant i] (Timestamp/from i))

(defn- upload->row [u]
  {:file_key     (:key u)
   :user_id      (:user-id u)
   :tenant_id    (:tenant-id u)
   :size_bytes   (:size u)
   :content_type (:content-type u)
   :stored_at    (->ts (:stored-at u))})

(defn- row->upload [row]
  {:key          (:file-key row)
   :user-id      (:user-id row)
   :tenant-id    (:tenant-id row)
   :size         (:size-bytes row)
   :content-type (:content-type row)
   :stored-at    (tc/string->instant (:stored-at row))})

(defrecord JdbcUploadIndex [ctx]
  ports/IUploadIndex
  (record-upload! [_ upload]
    (let [row (upload->row upload)]
      ;; No portable upsert; a key is re-stored only when a caller names its path.
      (db/with-transaction [tx ctx]
        (when (zero? (db/execute-update! tx {:update :storage_uploads
                                             :set    (dissoc row :file_key)
                                             :where  [:= :file_key (:file_key row)]}))
          (db/execute-update! tx {:insert-into :storage_uploads :values [row]}))))
    upload)

  (remove-upload! [_ file-key]
    (pos? (db/execute-update! ctx {:delete-from :storage_uploads
                                   :where       [:= :file_key file-key]})))

  (find-uploads [_ {:keys [user-id tenant-id]} {:keys [limit after-key]}]
    (if-let [owner (cond user-id   [:= :user_id (str user-id)]
                         tenant-id [:= :tenant_id (str tenant-id)])]
      (mapv row->upload
            (db/execute-query! ctx {:select   [:*]
                                    :from     [:storage_uploads]
                                    :where    (cond-> [:and owner]
                                                after-key (conj [:> :file_key after-key]))
                                    :order-by [[:file_key :asc]]
                                    :limit    (or limit 500)}))
      []))

  (uploads-stored-before [_ cutoff limit]
    (mapv row->upload
          (db/execute-query! ctx {:select   [:*]
                                  :from     [:storage_uploads]
                                  :where    [:< :stored_at (->ts cutoff)]
                                  :order-by [[:stored_at :asc] [:file_key :asc]]
                                  :limit    limit}))))

(defn create-upload-index
  "The upload index on db-context `ctx`."
  [ctx]
  (->JdbcUploadIndex ctx))
