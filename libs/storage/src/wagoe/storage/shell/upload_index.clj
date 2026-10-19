(ns wagoe.storage.shell.upload-index
  "`IUploadIndex` on the application database, and the boot step that creates
   its table."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as db-ports]
            [wagoe.storage.ports :as ports])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(def migration-resource "wagoe/storage/migrations/20261021100001-storage-uploads.up.sql")

(defn- key-collation
  "The collation of `storage_uploads.file_key` on MySQL."
  [ctx]
  (:key-collation
   (db/execute-one! ctx ["SELECT collation_name AS key_collation FROM information_schema.columns
                          WHERE table_schema = DATABASE() AND table_name = 'storage_uploads'
                            AND column_name = 'file_key'"])))

(defn ensure-case-sensitive-keys!
  "On MySQL, give `file_key` a binary collation. Its default is
   case-insensitive, which merges `A.png` and `a.png` into one record. The
   other engines compare keys case-sensitively already."
  [ctx]
  (when (and (= :mysql (db-ports/engine (:adapter ctx)))
             (not (some-> (key-collation ctx) (str/ends-with? "_bin"))))
    (db/execute-ddl! ctx "ALTER TABLE storage_uploads MODIFY file_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL")))

(defn create-tables!
  "Create the upload index table when absent, for installations that boot
   without running `migrate up`."
  [ctx]
  (db/execute-migration-ddl! ctx migration-resource)
  (ensure-case-sensitive-keys! ctx)
  (log/info "Upload index ready"))

(defn up
  "Migratus entry point of the binary-collation migration."
  [config]
  (ensure-case-sensitive-keys! (db/context-of (db/migration-connectable config))))

(defn down
  "Nothing: a case-insensitive key is the bug this fixed."
  [_config])

(defn- ->ts [^Instant i] (Timestamp/from i))

(defn- unique-conflict? [e]
  (= {:type :conflict :constraint :unique} (select-keys (ex-data e) [:type :constraint])))

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
    (let [row    (upload->row upload)
          write! #(db/with-transaction [tx ctx]
                    (when (zero? (db/execute-update! tx {:update :storage_uploads
                                                         :set    (dissoc row :file_key)
                                                         :where  [:= :file_key (:file_key row)]}))
                      (db/execute-update! tx {:insert-into :storage_uploads :values [row]})))]
      ;; No portable upsert; a key is re-stored only when a caller names its
      ;; path. Two uploads to one path race to insert, and the loser updates
      ;; instead: failing, it would delete the file both wrote to.
      (try (write!)
           (catch clojure.lang.ExceptionInfo e
             (if (unique-conflict? e) (write!) (throw e)))))
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
