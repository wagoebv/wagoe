(ns wagoe.storage.core.uploads
  "Pure halves of the upload index: the record a store makes, and what data
  export shows of it.")

(defn upload-record
  "The index record for `stored`, an `IFileStorage/store-file` result, stored
  by `owner` ({:user-id :tenant-id}, either may be nil) at `now`."
  [stored owner now]
  {:key          (:key stored)
   :user-id      (some-> (:user-id owner) str)
   :tenant-id    (some-> (:tenant-id owner) str)
   :size         (:size stored)
   :content-type (:content-type stored)
   :stored-at    now})

(defn subject-owner
  "The owner whose uploads belong to export `subject`, or nil for a type of
  subject that owns none."
  [{:keys [type id]}]
  (case type
    :user   {:user-id (str id)}
    :tenant {:tenant-id (str id)}
    nil))

(defn uploads-export
  "The storage section of a data export. In a zip export each file sits at
  `storage/<key>`."
  [uploads]
  {:uploads (mapv #(select-keys % [:key :size :content-type :stored-at]) uploads)})
