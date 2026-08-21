(ns wagoe.storage.shell.adapters.s3
  "Amazon S3 storage adapter.

  Implements IFileStorage for storing files in AWS S3 or S3-compatible services.
  Suitable for production deployments requiring scalable object storage."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.storage.core.validation :as validation]
            [wagoe.observability.logging.ports :as logging]
            [clojure.string :as str])
(:import [software.amazon.awssdk.core.sync RequestBody]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.services.s3 S3Client S3Configuration]
           [software.amazon.awssdk.services.s3.model
            PutObjectRequest GetObjectRequest DeleteObjectRequest
            HeadObjectRequest NoSuchKeyException ObjectCannedACL]
           [software.amazon.awssdk.services.s3.presigner S3Presigner]
           [software.amazon.awssdk.services.s3.presigner.model
            GetObjectPresignRequest]
           [software.amazon.awssdk.auth.credentials
            AwsBasicCredentials StaticCredentialsProvider
           DefaultCredentialsProvider]
           [java.net URI]
           [java.time Duration]
           [java.util UUID]))

;; ============================================================================
;; S3 Client Configuration
;; ============================================================================

(defn- create-credentials-provider
  "Create AWS credentials provider from config."
  [{:keys [access-key secret-key]}]
  (if (and access-key secret-key)
    (StaticCredentialsProvider/create
     (AwsBasicCredentials/create access-key secret-key))
    (DefaultCredentialsProvider/create)))

(defn- create-s3-client
  "Create configured S3 client."
  [{:keys [region endpoint] :as config}]
  (let [builder (S3Client/builder)]
    (when region
      (.region builder (Region/of region)))
    (when endpoint
      ;; For S3-compatible services (MinIO, DigitalOcean Spaces, etc.)
      (.endpointOverride builder (URI/create endpoint))
      (.serviceConfiguration builder
                             (-> (S3Configuration/builder)
                                 (.pathStyleAccessEnabled true)
                                 .build)))
    (.credentialsProvider builder (create-credentials-provider config))
    (.build builder)))

(defn- create-s3-presigner
  "Create S3 presigner for generating signed URLs."
  [{:keys [region endpoint] :as config}]
  (let [builder (S3Presigner/builder)]
    (when region
      (.region builder (Region/of region)))
    (when endpoint
      (.endpointOverride builder (URI/create endpoint)))
    (.credentialsProvider builder (create-credentials-provider config))
    (.build builder)))

;; ============================================================================
;; Storage Key Generation
;; ============================================================================

(defn- generate-s3-key
  "Generate S3 object key with optional prefix."
  [filename prefix]
  (let [unique-suffix (str (System/currentTimeMillis) "-" (subs (str (UUID/randomUUID)) 0 8))
        unique-filename (validation/generate-unique-filename* filename unique-suffix)]
    (if prefix
      (str (str/replace prefix #"/$" "") "/" unique-filename)
      unique-filename)))

;; ============================================================================
;; S3 Storage Adapter
;; ============================================================================

(defrecord S3FileStorage [bucket prefix public-read? s3-client presigner logger]
  ports/IFileStorage

  (store-file [_ file-data metadata]
    (try
      (let [{:keys [bytes content-type]} file-data
            {:keys [filename path visibility]} metadata

            ;; Determine object key
            object-path (or path prefix)
            object-key (generate-s3-key filename object-path)

            ;; Determine ACL based on visibility
            is-public (or (= visibility :public) public-read?)
            acl (if is-public
                  ObjectCannedACL/PUBLIC_READ
                  ObjectCannedACL/PRIVATE)

            ;; Build put request
            put-request (-> (PutObjectRequest/builder)
                            (.bucket bucket)
                            (.key object-key)
                            (.contentType content-type)
                            (.contentLength (long (alength bytes)))
                            (.acl acl)
                            .build)

            ;; Upload to S3
            request-body (RequestBody/fromBytes bytes)
            _ (.putObject s3-client put-request request-body)

            ;; Generate URL
            url (if is-public
                  (format "https://%s.s3.amazonaws.com/%s" bucket object-key)
                  nil)]

        (when logger
          (logging/info logger "S3 file stored"
                        {:event ::s3-file-stored
                         :bucket bucket
                         :key object-key
                         :size (alength bytes)
                         :content-type content-type
                         :public is-public}))

        {:key object-key
         :url url
         :size (alength bytes)
         :content-type content-type
         :stored-at (java.util.Date.)})

      (catch Exception e
        (when logger
          (logging/error logger "Failed to store file in S3"
                         {:event ::s3-store-failed
                          :bucket bucket
                          :filename (:filename metadata)
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to store file in S3"
                        {:type :storage-error
                         :bucket bucket
                         :filename (:filename metadata)
                         :error (.getMessage e)}
                        e)))))

  (retrieve-file [_ file-key]
    (try
      (let [get-request (-> (GetObjectRequest/builder)
                            (.bucket bucket)
                            (.key file-key)
                            .build)
            response (.getObject s3-client get-request)
            bytes (.readAllBytes response)
            content-type (.contentType (.response response))
            size (alength bytes)]

        (when logger
          (logging/debug logger "S3 file retrieved"
                         {:event ::s3-file-retrieved
                          :bucket bucket
                          :key file-key
                          :size size}))

        {:bytes bytes
         :content-type content-type
         :size size})

      (catch NoSuchKeyException _
        (when logger
          (logging/debug logger "S3 file not found"
                         {:event ::s3-file-not-found
                          :bucket bucket
                          :key file-key}))
        nil)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to retrieve file from S3"
                         {:event ::s3-retrieve-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  (delete-file [_ file-key]
    (try
      (let [delete-request (-> (DeleteObjectRequest/builder)
                               (.bucket bucket)
                               (.key file-key)
                               .build)]
        (.deleteObject s3-client delete-request)

        (when logger
          (logging/info logger "S3 file deleted"
                        {:event ::s3-file-deleted
                         :bucket bucket
                         :key file-key}))

        true)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to delete file from S3"
                         {:event ::s3-delete-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (file-exists? [_ file-key]
    (try
      (let [head-request (-> (HeadObjectRequest/builder)
                             (.bucket bucket)
                             (.key file-key)
                             .build)]
        (.headObject s3-client head-request)
        true)

      (catch NoSuchKeyException _
        false)

      (catch Exception e
        (when logger
          (logging/error logger "S3 file exists check failed"
                         {:event ::s3-exists-check-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (generate-signed-url [_ file-key expiration-seconds]
    (try
      (let [get-request (-> (GetObjectRequest/builder)
                            (.bucket bucket)
                            (.key file-key)
                            .build)
            presign-request (-> (GetObjectPresignRequest/builder)
                                (.signatureDuration (Duration/ofSeconds expiration-seconds))
                                (.getObjectRequest get-request)
                                .build)
            presigned-request (.presignGetObject presigner presign-request)
            url (.toString (.url presigned-request))]

        (when logger
          (logging/debug logger "S3 signed URL generated"
                         {:event ::s3-signed-url-generated
                          :bucket bucket
                          :key file-key
                          :expiration-seconds expiration-seconds}))

        url)

      (catch Exception e
        (when logger
          (logging/error logger "Failed to generate S3 signed URL"
                         {:event ::s3-signed-url-failed
                          :bucket bucket
                          :key file-key
                          :error (.getMessage e)}))
        nil))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-s3-storage
  "Create an S3 storage adapter.

  Required options:
  - :bucket - S3 bucket name
  - :region - AWS region (e.g., \"us-east-1\")

  Optional options:
  - :access-key - AWS access key (if not using default credentials)
  - :secret-key - AWS secret key (if not using default credentials)
  - :endpoint - Custom endpoint for S3-compatible services
  - :prefix - Key prefix for all objects (default: nil)
  - :public-read? - Make all objects publicly readable (default: false)
  - :logger - Logger instance (optional)"
  [{:keys [bucket region prefix public-read? logger] :as config}]
  (when-not bucket
    (throw (ex-info "bucket is required for S3 storage"
                    {:type :configuration-error
                     :provided-config (dissoc config :secret-key)})))

  (when-not region
    (throw (ex-info "region is required for S3 storage"
                    {:type :configuration-error
                     :provided-config (dissoc config :secret-key)})))

  (let [s3-client (create-s3-client config)
        presigner (create-s3-presigner config)]

    (when logger
      (logging/info logger "S3 storage initialized"
                    {:event ::s3-storage-initialized
                     :bucket bucket
                     :region region
                     :prefix prefix
                     :public-read public-read?}))

    (->S3FileStorage bucket prefix public-read? s3-client presigner logger)))

(defn close-s3-storage
  "Close S3 client and presigner to release resources."
  [^S3FileStorage storage]
  (try
    (.close (:s3-client storage))
    (.close (:presigner storage))
    (when-let [logger (:logger storage)]
      (logging/info logger "S3 storage closed" {:event ::s3-storage-closed}))
    (catch Exception e
      (when-let [logger (:logger storage)]
        (logging/error logger "Failed to close S3 storage"
                       {:event ::s3-storage-close-failed
                        :error (.getMessage e)})))))
