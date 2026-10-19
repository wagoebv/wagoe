(ns wagoe.storage.shell.adapters.gcs
  "Google Cloud Storage adapter.

  Implements IFileStorage for storing files in Google Cloud Storage (GCS).
  Suitable for production deployments on Google Cloud requiring scalable
  object storage."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.storage.core.validation :as validation]
            [wagoe.storage.shell.adapters.spool :as spool]
            [wagoe.observability.logging.ports :as logging]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.google.cloud.storage
            StorageOptions Storage BlobId BlobInfo Blob
            Storage$BlobTargetOption Storage$BlobWriteOption Storage$BlobGetOption
            Storage$SignUrlOption Blob$BlobSourceOption]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.nio.file Files Path]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; GCS Client Configuration
;; ============================================================================

(defn- create-storage
  "Create a configured GCS Storage client.

  When no :credentials-path is provided, falls back to Application Default
  Credentials (ADC)."
  ^Storage [{:keys [project-id credentials-path]}]
  (let [b (StorageOptions/newBuilder)]
    (when project-id
      (.setProjectId b project-id))
    (when credentials-path
      (.setCredentials b (GoogleCredentials/fromStream (io/input-stream credentials-path))))
    (.getService (.build b))))

;; ============================================================================
;; Storage Key Generation
;; ============================================================================

(defn- generate-gcs-key
  "Generate a GCS object key with optional prefix."
  [filename prefix]
  (let [unique-suffix (str (System/currentTimeMillis) "-" (subs (str (UUID/randomUUID)) 0 8))
        unique-filename (validation/generate-unique-filename* filename unique-suffix)]
    (if prefix
      (str (str/replace prefix #"/$" "") "/" unique-filename)
      unique-filename)))

;; ============================================================================
;; GCS Storage Adapter
;; ============================================================================

(defn- put-blob!
  "`(upload! storage blob-info)` a blob of `size` bytes under a new key;
   `store-file`'s result."
  [{:keys [bucket prefix public-read? storage logger]} upload! size content-type metadata]
  (try
    (let [{:keys [filename path visibility]} metadata

          ;; Determine object key
          object-path (or path prefix)
          object-key  (generate-gcs-key filename object-path)

          ;; Determine visibility
          is-public   (case visibility :public true :private false public-read?)

          ;; Build blob info
          blob-id     (BlobId/of bucket object-key)
          blob-info   (-> (BlobInfo/newBuilder blob-id)
                          (.setContentType content-type)
                          (.build))

          _ (upload! storage blob-info)

          ;; Generate URL
          url (when is-public
                (format "https://storage.googleapis.com/%s/%s" bucket object-key))]

      (when logger
        (logging/info logger "GCS file stored"
                      {:event ::gcs-file-stored
                       :bucket bucket
                       :key object-key
                       :size size
                       :content-type content-type
                       :public is-public}))

      {:key object-key
       :url url
       :size size
       :content-type content-type
       :stored-at (java.util.Date.)})

    (catch Exception e
      (when logger
        (logging/error logger "Failed to store file in GCS"
                       {:event ::gcs-store-failed
                        :bucket bucket
                        :filename (:filename metadata)
                        :error (.getMessage e)}))
      (throw (ex-info "Failed to store file in GCS"
                      {:type :storage-error
                       :bucket bucket
                       :filename (:filename metadata)
                       :error (.getMessage e)}
                      e)))))

(defrecord GCSFileStorage [bucket prefix public-read? storage logger]
  ports/IFileStorage

  (store-file [this {:keys [bytes content-type]} metadata]
    (put-blob! this
               (fn [^Storage storage ^BlobInfo blob-info]
                 (.create storage blob-info ^bytes bytes
                          ^"[Lcom.google.cloud.storage.Storage$BlobTargetOption;"
                          (into-array Storage$BlobTargetOption [])))
               (alength ^bytes bytes) content-type metadata))

  (retrieve-file [_ file-key]
    (try
      (let [^Storage storage storage
            blob-id (BlobId/of bucket file-key)
            ^Blob blob (.get storage blob-id
                             ^"[Lcom.google.cloud.storage.Storage$BlobGetOption;"
                             (into-array Storage$BlobGetOption []))]
        (if blob
          (let [content-bytes (.getContent blob (into-array Blob$BlobSourceOption []))
                content-type  (.getContentType blob)
                size          (alength ^bytes content-bytes)]

            (when logger
              (logging/debug logger "GCS file retrieved"
                             {:event ::gcs-file-retrieved
                              :bucket bucket
                              :key file-key
                              :size size}))

            {:bytes content-bytes
             :content-type content-type
             :size size})

          (do
            (when logger
              (logging/debug logger "GCS file not found"
                             {:event ::gcs-file-not-found
                              :bucket bucket
                              :key file-key}))
            nil)))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to retrieve file from GCS"
                         {:event ::gcs-retrieve-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  (delete-file [_ file-key]
    (try
      (let [^Storage storage storage
            blob-id (BlobId/of bucket file-key)
            deleted (.delete storage blob-id)]

        (when logger
          (logging/info logger "GCS file deleted"
                        {:event ::gcs-file-deleted
                         :bucket bucket
                         :key file-key
                         :deleted deleted}))

        (boolean deleted))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to delete file from GCS"
                         {:event ::gcs-delete-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (file-exists? [_ file-key]
    (try
      (let [^Storage storage storage
            blob-id (BlobId/of bucket file-key)
            ^Blob blob (.get storage blob-id
                             ^"[Lcom.google.cloud.storage.Storage$BlobGetOption;"
                             (into-array Storage$BlobGetOption []))]
        (boolean (and blob
                      (.exists blob (into-array Blob$BlobSourceOption [])))))

      ;; A missing blob is nil; an exception is a check that could not tell,
      ;; which read as false would let retention drop a record of a live file.
      (catch Exception e
        (when logger
          (logging/error logger "GCS file exists check failed"
                         {:event ::gcs-exists-check-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        (throw (ex-info "Could not check whether the file exists in GCS"
                        {:type :storage-error :bucket bucket :key file-key}
                        e)))))

  (generate-signed-url [_ file-key expiration-seconds]
    (try
      (let [^Storage storage storage
            blob-id   (BlobId/of bucket file-key)
            blob-info (.build (BlobInfo/newBuilder blob-id))
            ^java.net.URL url (.signUrl storage blob-info
                                        (long expiration-seconds)
                                        TimeUnit/SECONDS
                                        (into-array Storage$SignUrlOption
                                                    [(Storage$SignUrlOption/withV4Signature)]))
            url-str (.toString url)]

        (when logger
          (logging/debug logger "GCS signed URL generated"
                         {:event ::gcs-signed-url-generated
                          :bucket bucket
                          :key file-key
                          :expiration-seconds expiration-seconds}))

        url-str)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to generate GCS signed URL"
                         {:event ::gcs-signed-url-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  ports/IStreamingFileStorage

  (store-stream [this {:keys [write! content-type]} metadata]
    ;; Spooled to disk first: a failed writer must not leave a partial object.
    (spool/with-spooled write!
      (fn [^Path path]
        (put-blob! this
                   (fn [^Storage storage ^BlobInfo blob-info]
                     (.createFrom storage blob-info path (make-array Storage$BlobWriteOption 0)))
                   (Files/size path) content-type metadata)))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-gcs-storage
  "Create a Google Cloud Storage adapter.

  Required options:
  - :bucket - GCS bucket name
  - :project-id - Google Cloud project id

  Optional options:
  - :credentials-path - Path to a service-account JSON key file (if not using
    Application Default Credentials)
  - :prefix - Key prefix for all objects (default: nil)
  - :public-read? - Make all objects publicly readable (default: false)
  - :logger - Logger instance (optional)"
  [{:keys [bucket project-id prefix public-read? logger] :as config}]
  (when-not bucket
    (throw (ex-info "bucket is required for GCS storage"
                    {:type :configuration-error
                     :provided-config (dissoc config :credentials-path)})))

  (when-not project-id
    (throw (ex-info "project-id is required for GCS storage"
                    {:type :configuration-error
                     :provided-config (dissoc config :credentials-path)})))

  (let [storage (create-storage config)]

    (when logger
      (logging/info logger "GCS storage initialized"
                    {:event ::gcs-storage-initialized
                     :bucket bucket
                     :project-id project-id
                     :prefix prefix
                     :public-read public-read?}))

    (->GCSFileStorage bucket prefix public-read? storage logger)))

(defn close-gcs-storage
  "Close the GCS Storage client to release resources."
  [^GCSFileStorage storage-record]
  (try
    (when-let [storage (:storage storage-record)]
      (.close ^Storage storage))
    (when-let [logger (:logger storage-record)]
      (logging/info logger "GCS storage closed" {:event ::gcs-storage-closed}))
    (catch Exception e
      (when-let [logger (:logger storage-record)]
        (logging/error logger "Failed to close GCS storage"
                       {:event ::gcs-storage-close-failed
                        :error (.getMessage e)})))))
