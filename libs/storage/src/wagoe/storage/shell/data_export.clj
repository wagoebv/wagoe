(ns wagoe.storage.shell.data-export
  "The storage module's contributions to data export and retention: a
   subject's uploads, listed and, in a zip export, included; and the upload
   retention policy."
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.privacy :as privacy]
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

(def kept
  "What `purge-one!` returns for an item it leaves where it is."
  ::kept)

(defn- purge-batch
  "Purge each of `batch`: {:deleted n :kept [ids] :failed [ids] :cause e}, the
   first failure's exception as `:cause`. A lost database connection ends it at
   once (BOU-970), naming those that failed before, `failed-before` too."
  [batch id-of purge-one! noun done failed-before]
  (reduce (fn [{:keys [deleted] :as acc} item]
            (try (if (= kept (purge-one! item))
                   (update acc :kept conj (id-of item))
                   (update acc :deleted inc))
                 (catch Exception e
                   ;; Every item after it would wait out the timeout to fail the same way
                   (when (db/connection-lost? e)
                     (let [done (+ done deleted)]
                       (throw (ex-info (str "Lost the database connection; " done " " noun " deleted")
                                       {:type :internal-error :keys (vec (sort (concat failed-before (:failed acc))))
                                        :deleted done}
                                       e))))
                   (-> acc (update :failed conj (id-of item)) (update :cause #(or % e))))))
          {:deleted 0 :kept [] :failed [] :cause nil}
          batch))

(defn- refuse-failed! [noun n failed cause]
  (throw (ex-info (str "Could not delete " (count failed) " " noun "; " n " deleted")
                  {:type :internal-error :keys (vec (sort failed)) :deleted n}
                  cause)))

(defn purge-in-pages!
  "Purge what `(fetch-page limit)` returns, a page at a time, with
   `(purge-one! item)`, until a page is short. Returns the number purged;
   throws, after purging the rest, naming the `(id-of item)`s that failed.
   A lost database connection ends it at once (BOU-970).

   One item that will not purge must not hold back the rest: the oldest come
   first, so it would fail every run before anything newer was reached.
   `purge-one!` may return `kept` for one it leaves; a page it keeps entirely
   ends the run, so a store that pages by cursor uses `purge-after!`."
  [fetch-page id-of purge-one! noun]
  (loop [n 0 skip #{} failed #{} cause nil]
    (let [page  (fetch-page page-size)
          batch (remove (comp skip id-of) page)
          r     (purge-batch batch id-of purge-one! noun n failed)
          n     (+ n (:deleted r))
          failed (into failed (:failed r))
          cause  (or cause (:cause r))]
      (cond
        (and (= page-size (count page)) (pos? (:deleted r)))
        (recur n (into skip (concat (:kept r) (:failed r))) failed cause)
        (seq failed) (refuse-failed! noun n failed cause)
        :else        n))))

(defn purge-after!
  "`purge-in-pages!` over `(fetch-after after limit)`, which returns the items
   after `after`, nil for the first page: what it keeps or fails on is not read
   again. A full page with nothing deleted or kept, an outage say, ends it.
   Returns {:deleted n :kept n}."
  [fetch-after id-of purge-one! noun]
  (loop [after nil n 0 k 0 failed [] cause nil]
    (let [page   (fetch-after after page-size)
          r      (purge-batch page id-of purge-one! noun n failed)
          n      (+ n (:deleted r))
          k      (+ k (count (:kept r)))
          failed (into failed (:failed r))
          cause  (or cause (:cause r))]
      (cond
        (and (= page-size (count page)) (or (pos? (:deleted r)) (seq (:kept r))))
        (recur (last page) n k failed cause)
        (seq failed) (refuse-failed! noun n failed cause)
        :else        {:deleted n :kept k}))))

(defrecord UploadRetention [upload-index storage]
  privacy/IRetentionPolicy
  (purge-before! [this cutoff]
    ;; `:in-use?`, the app's (fn [upload]) on this record: a file it still
    ;; refers to is kept, however old (BOU-1081). One it cannot answer for
    ;; fails, so it is kept too and the run says so.
    (let [in-use? (:in-use? this)
          purge!  (fn [{:keys [key] :as upload}]
                    (if (and in-use? (in-use? upload))
                      kept
                      (do (delete-or-confirm-gone! storage key)
                          (ports/remove-upload! upload-index key))))]
      (if (satisfies? ports/IUploadPages upload-index)
        (let [{:keys [deleted kept]}
              (purge-after! #(ports/uploads-stored-after upload-index cutoff %1 %2) :key purge! "uploads")]
          (when (pos? kept)
            (log/info "Upload retention kept uploads the app still uses" {:kept kept :deleted deleted}))
          deleted)
        (do (when in-use?
              (log/warn "The upload index has no IUploadPages; 500 uploads in use before the cutoff end retention early"))
            (purge-in-pages! #(ports/uploads-stored-before upload-index cutoff %) :key purge! "uploads"))))))
