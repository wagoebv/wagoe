(ns wagoe.storage.shell.service
  "Storage service orchestrating file operations.

  This service coordinates file validation, storage, and image processing.
  Follows the Functional Core / Imperative Shell pattern where this service
  represents the imperative shell that coordinates pure validation logic
  with side-effectful storage operations."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.core.uploads :as uploads]
            [wagoe.storage.core.validation :as validation]
            [wagoe.observability.logging.ports :as logging]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; ============================================================================
;; Service Protocol
;; ============================================================================

(defprotocol IStorageService
  "Service for managing file storage operations."

  (upload-file [_ file-data metadata options]
    "Upload a file with validation.

    Parameters:
    - file-data: Map with :bytes and :content-type
    - metadata: Map with :filename and optional :path, :visibility, and
      :owner {:user-id :tenant-id}, which the upload index records
    - options: Map with optional :max-size, :allowed-types, :allowed-extensions

    Returns:
    Storage result map or validation errors")

  (upload-image [_ image-bytes metadata options]
    "Upload an image with optional processing.

    Parameters:
    - image-bytes: Byte array of the image
    - metadata: Map with :filename and optional :path, :visibility
    - options: Map with optional :resize, :create-thumbnail, :thumbnail-size

    Returns:
    Map with :original (storage result) and optional :thumbnail")

  (download-file [_ file-key]
    "Download a file by its storage key.

    Returns:
    File data map or nil if not found")

  (remove-file [_ file-key]
    "Remove a file from storage.

    Returns:
    Boolean indicating success")

  (get-file-url [_ file-key expiration-seconds]
    "Get a URL for accessing a file.

    For public files, returns direct URL.
    For private files, returns signed URL with expiration.

    Returns:
    URL string or nil"))

;; ============================================================================
;; Service Implementation
;; ============================================================================

(defn- index-upload!
  "Record `stored` in the upload index. A file the index does not know is
   invisible to data export and retention, so when recording fails the file
   is deleted again and the upload fails."
  [storage upload-index logger stored owner]
  (when upload-index
    (try
      (ports/record-upload! upload-index (uploads/upload-record stored owner (Instant/now)))
      (catch Exception e
        (try (ports/delete-file storage (:key stored))
             (catch Exception de
               (when logger
                 (logging/warn logger "Could not delete a file the upload index did not record"
                               {:event ::unindexed-file-kept :key (:key stored) :error (.getMessage de)}))))
        (throw e)))))

(def ^:private tenants-dir
  "Where the local adapter keeps tenants' named paths; no caller may name it."
  ".tenants")

(defn- local? [storage]
  (instance? wagoe.storage.shell.adapters.local.LocalFileStorage storage))

(defn- reserved-path?
  "Whether `path`, normalized, names the tenants' directory (BOU-991)."
  [storage path]
  (and (local? storage) (= tenants-dir (first (some-> path (str/split #"/"))))))

(defn- in-tenant-dir
  "`metadata` with a tenant's named path below `.tenants/<tenant-id>/`, under
   the local adapter: two tenants' `invoices/a.pdf` are two files, and nobody
   learns or occupies another tenant's names (BOU-991). S3 and GCS add a
   random suffix."
  [storage {:keys [owner path] :as metadata}]
  (cond-> metadata
    (and path (:tenant-id owner) (local? storage))
    (assoc :path (str tenants-dir "/" (:tenant-id owner) "/" path))))

(defn- refused-key?
  "Whether `metadata` names a key that holds a file `may-replace?` says no to (BOU-950).
   Only the local adapter writes a named key as is; S3 and GCS add a random suffix."
  [storage metadata {:keys [may-replace?]}]
  (when-let [k (and may-replace?
                    (instance? wagoe.storage.shell.adapters.local.LocalFileStorage storage)
                    (local/named-storage-key metadata))]
    ;; ponytail: checked before the write, not with it; two uploads racing for one path can both pass.
    (and (ports/file-exists? storage k) (not (may-replace? k)))))

(defrecord StorageService [storage image-processor logger upload-index]
  IStorageService

  (upload-file [_ file-data metadata options]
    (when logger
      (logging/info logger "Upload file started"
                    {:event ::upload-file-started
                     :filename (:filename metadata)
                     :content-type (:content-type file-data)}))

    (try
      ;; Sanitize filename
      (let [sanitized-metadata (->> (-> metadata
                                        (update :filename validation/sanitize-filename)
                                        (update :path validation/normalize-path))
                                    (in-tenant-dir storage))

            ;; Validate file
            validation-result (validation/validate-file file-data sanitized-metadata options)]

        (cond
          (not (:valid? validation-result))
          (do
            (when logger
              (logging/warn logger "Upload file validation failed"
                            {:event ::upload-file-validation-failed
                             :filename (:filename metadata)
                             :errors (:errors validation-result)}))
            {:success false
             :errors (:errors validation-result)})

          (reserved-path? storage (validation/normalize-path (:path metadata)))
          {:success false
           :errors [{:code :invalid-path :message "That path is reserved"}]}

          (refused-key? storage sanitized-metadata options)
          {:success false
           :errors [{:code :forbidden :message "That path holds a file you may not replace"}]}

          :else
          (let [result (ports/store-file storage file-data (dissoc sanitized-metadata :owner))]
            (index-upload! storage upload-index logger result (:owner metadata))
            (when logger
              (logging/info logger "Upload file completed"
                            {:event ::upload-file-completed
                             :key (:key result)
                             :size (:size result)}))
            {:success true
             :data result})))

      (catch Exception e
        (when logger
          (logging/error logger "Upload file error"
                         {:event ::upload-file-error
                          :filename (:filename metadata)
                          :error (.getMessage e)}))
        {:success false
         :errors [{:code :storage-error
                   :message "Storage operation failed"}]})))

  (upload-image [this image-bytes metadata options]
    (when logger
      (logging/info logger "Upload image started"
                    {:event ::upload-image-started
                     :filename (:filename metadata)
                     :options options}))

    (try
      (let [{:keys [create-thumbnail thumbnail-size]} options
            thumbnail-size (or thumbnail-size 200)

            ;; Preserve the real content type — detect from the filename extension
            ;; rather than assuming JPEG (a PNG/WebP/GIF upload must keep its type).
            original-content-type (or (validation/mime-type-from-extension (:filename metadata))
                                      "image/jpeg")

            ;; Upload original image
            original-data {:bytes image-bytes
                           :content-type original-content-type}

            original-result (upload-file this original-data metadata
                                         (assoc options :allowed-types (vec validation/image-mime-types)))

            result (if (:success original-result)
                     {:success true
                      :original (:data original-result)}
                     original-result)]

        ;; Create and upload thumbnail if requested
        (if (and (:success result) create-thumbnail image-processor)
          (try
            (let [thumb-bytes (ports/create-thumbnail image-processor
                                                      image-bytes
                                                      thumbnail-size)
                  thumb-filename (str "thumb-" (:filename metadata))
                  thumb-metadata (assoc metadata :filename thumb-filename)
                  thumb-data {:bytes thumb-bytes
                              :content-type "image/jpeg"}
                  thumb-result (upload-file this thumb-data thumb-metadata (select-keys options [:may-replace?]))]

              (if (:success thumb-result)
                (do
                  (when logger
                    (logging/info logger "Thumbnail uploaded"
                                  {:event ::thumbnail-uploaded
                                   :original-key (:key (:original result))
                                   :thumbnail-key (:key (:data thumb-result))}))
                  (assoc result :thumbnail (:data thumb-result)))
                (do
                  (when logger
                    (logging/warn logger "Thumbnail upload failed"
                                  {:event ::thumbnail-upload-failed
                                   :errors (:errors thumb-result)}))
                  result)))

            (catch Exception e
              (when logger
                (logging/error logger "Thumbnail creation failed"
                               {:event ::thumbnail-creation-failed
                                :error (.getMessage e)}))
              result))

          result))

      (catch Exception e
        (when logger
          (logging/error logger "Upload image error"
                         {:event ::upload-image-error
                          :filename (:filename metadata)
                          :error (.getMessage e)}))
        {:success false
         :errors [{:code :storage-error
                   :message "Storage operation failed"}]})))

  (download-file [_ file-key]
    (when logger
      (logging/debug logger "Download file requested"
                     {:event ::download-file-requested
                      :key file-key}))

    (try
      (let [file-data (ports/retrieve-file storage file-key)]
        (when logger
          (if file-data
            (logging/debug logger "Download file completed"
                           {:event ::download-file-completed
                            :key file-key
                            :size (:size file-data)})
            (logging/debug logger "Download file not found"
                           {:event ::download-file-not-found
                            :key file-key})))
        file-data)

      (catch Exception e
        (when logger
          (logging/error logger "Download file error"
                         {:event ::download-file-error
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  (remove-file [_ file-key]
    (when logger
      (logging/info logger "Remove file requested"
                    {:event ::remove-file-requested
                     :key file-key}))

    (try
      (let [success (ports/delete-file storage file-key)]
        ;; A file already gone deletes as false; its record goes all the same.
        (when (and upload-index (or success (not (ports/file-exists? storage file-key))))
          (ports/remove-upload! upload-index file-key))
        (when logger
          (logging/info logger (if success
                                 "Remove file completed"
                                 "Remove file failed")
                        {:event (if success
                                  ::remove-file-completed
                                  ::remove-file-failed)
                         :key file-key}))
        success)

      (catch Exception e
        (when logger
          (logging/error logger "Remove file error"
                         {:event ::remove-file-error
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (get-file-url [_ file-key expiration-seconds]
    (try
      (let [url (ports/generate-signed-url storage file-key (or expiration-seconds 3600))]
        (when logger
          (logging/debug logger "File URL generated"
                         {:event ::file-url-generated
                          :key file-key
                          :expiration-seconds expiration-seconds
                          :url-type (if (and url (re-find #"X-Amz-|X-Goog-|signature=" url))
                                      :signed
                                      :public)}))
        url)

      (catch Exception e
        (when logger
          (logging/error logger "File URL generation error"
                         {:event ::file-url-generation-error
                          :key file-key
                          :error (.getMessage e)}))
        nil))))

(defn file-at?
  "Whether the storage backend holds a file at `file-key`."
  [service file-key]
  (ports/file-exists? (:storage service) file-key))

(defn upload-at
  "The upload index's record of `file-key`, or nil when there is no index or no record."
  [service file-key]
  (some-> (:upload-index service) (ports/find-upload file-key)))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-storage-service
  "Create a storage service.

  Parameters:
  - storage: Implementation of IFileStorage
  - image-processor: Implementation of IImageProcessor (optional)
  - logger: Logger instance (optional)
  - upload-index: Implementation of IUploadIndex (optional); records every upload"
  [{:keys [storage image-processor logger upload-index]}]
  (when-not storage
    (throw (ex-info "storage adapter is required"
                    {:type :configuration-error
                     :provided {:storage storage}})))

  (when logger
    (logging/info logger "Storage service created"
                  {:event ::storage-service-created
                   :has-image-processor (boolean image-processor)}))

  (->StorageService storage image-processor logger upload-index))
