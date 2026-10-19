(ns wagoe.storage.shell.data-export
  "The storage module's contributions to data export and retention: a
   subject's uploads, listed and, in a zip export, included; and the upload
   retention policy."
  (:require [wagoe.platform.ports.privacy :as privacy]
            [wagoe.storage.core.uploads :as core]
            [wagoe.storage.ports :as ports]))

(def ^:private page-size 500)

(defn- all-uploads
  "Every upload of `owner`, a page at a time."
  [upload-index owner]
  (loop [after-key nil acc []]
    (let [page (ports/find-uploads upload-index owner {:limit page-size :after-key after-key})
          acc  (into acc page)]
      (if (< (count page) page-size)
        acc
        (recur (:key (peek page)) acc)))))

(defrecord UploadDataExporter [upload-index storage]
  privacy/IDataExporter
  (export-data [_ subject]
    (when-let [owner (core/subject-owner subject)]
      (core/uploads-export (all-uploads upload-index owner))))

  privacy/IFileExporter
  (export-files [_ _subject section]
    (for [{:keys [key]} (:uploads section)]
      {:path    key
       :content #(:bytes (ports/retrieve-file storage key))})))

(defn- delete-upload!
  "Delete the file at `key`, then its record. A file storage cannot delete
   keeps its record, so the next run tries again."
  [upload-index storage key]
  (when-not (or (ports/delete-file storage key)
                (not (ports/file-exists? storage key)))
    (throw (ex-info (str "Could not delete upload " key) {:type :internal-error :key key})))
  (ports/remove-upload! upload-index key))

(defrecord UploadRetention [upload-index storage]
  privacy/IRetentionPolicy
  (purge-before! [_ cutoff]
    (loop [n 0]
      (let [batch (ports/uploads-stored-before upload-index cutoff page-size)]
        (doseq [{:keys [key]} batch]
          (delete-upload! upload-index storage key))
        (if (< (count batch) page-size)
          (+ n (count batch))
          (recur (+ n (count batch))))))))
