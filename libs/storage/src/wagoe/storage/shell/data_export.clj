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

(defn delete-or-confirm-gone!
  "Delete the file at `key`, or throw when it is still there: the caller
   keeps its record, so the next run tries again."
  [storage key]
  (when-not (or (ports/delete-file storage key)
                (not (ports/file-exists? storage key)))
    (throw (ex-info (str "Could not delete " key) {:type :internal-error :key key}))))

(defn purge-in-pages!
  "Purge what `(fetch-page limit)` returns, a page at a time, with
   `(purge-one! item)`, until a page is short. Returns the number purged;
   throws, after purging the rest, naming the `(id-of item)`s that failed.

   One item that will not purge must not hold back the rest: the oldest come
   first, so it would fail every run before anything newer was reached."
  [fetch-page id-of purge-one! noun]
  (loop [n 0 failed #{}]
    (let [page    (fetch-page page-size)
          batch   (remove (comp failed id-of) page)
          stuck   (into #{} (keep #(try (purge-one! %) nil (catch Exception _ (id-of %)))) batch)
          deleted (- (count batch) (count stuck))
          n       (+ n deleted)
          failed  (into failed stuck)]
      (cond
        (and (= page-size (count page)) (pos? deleted)) (recur n failed)
        (seq failed) (throw (ex-info (str "Could not delete " (count failed) " " noun "; " n " deleted")
                                     {:type :internal-error :keys (vec (sort failed)) :deleted n}))
        :else        n))))

(defrecord UploadRetention [upload-index storage]
  privacy/IRetentionPolicy
  (purge-before! [_ cutoff]
    (purge-in-pages! #(ports/uploads-stored-before upload-index cutoff %)
                     :key
                     (fn [{:keys [key]}]
                       (delete-or-confirm-gone! storage key)
                       (ports/remove-upload! upload-index key))
                     "uploads")))
