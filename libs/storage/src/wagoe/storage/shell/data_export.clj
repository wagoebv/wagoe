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
       ;; The adapters read any failure as nil; a file still there is not
       ;; gone, and must not be listed as missing.
       :content #(or (:bytes (ports/retrieve-file storage key))
                     (when (ports/file-exists? storage key)
                       (throw (ex-info (str "Could not read upload " key) {:type :internal-error :key key}))))})))

(defn- delete-upload!
  "Delete the file at `key`, then its record. A file storage cannot delete
   keeps its record, so the next run tries again."
  [upload-index storage key]
  (when-not (or (ports/delete-file storage key)
                (not (ports/file-exists? storage key)))
    (throw (ex-info (str "Could not delete upload " key) {:type :internal-error :key key})))
  (ports/remove-upload! upload-index key))

;; One file that will not delete must not hold back the rest: the oldest come
;; first, so it would fail every run before anything newer was reached.
(defrecord UploadRetention [upload-index storage]
  privacy/IRetentionPolicy
  (purge-before! [_ cutoff]
    (loop [n 0 failed #{}]
      (let [batch   (ports/uploads-stored-before upload-index cutoff page-size)
            stuck   (into #{} (keep (fn [{:keys [key]}]
                                      (try (delete-upload! upload-index storage key) nil
                                           (catch Exception _ key))))
                          (remove (comp failed :key) batch))
            failed  (into failed stuck)
            deleted (count (remove (comp failed :key) batch))
            n       (+ n deleted)]
        (if (and (= (count batch) page-size) (pos? deleted))
          (recur n failed)
          (if (seq failed)
            (throw (ex-info (str "Could not delete " (count failed) " uploads; " n " deleted")
                            {:type :internal-error :keys (vec (sort failed)) :deleted n}))
            n))))))
