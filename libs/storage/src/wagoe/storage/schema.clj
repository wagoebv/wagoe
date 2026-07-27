(ns wagoe.storage.schema
  "Malli schemas for file storage validation."
  (:require [malli.core :as m]))

;; ============================================================================
;; File Storage Schemas
;; ============================================================================

(def FileData
  "Schema for file data to be stored."
  [:map
   [:bytes bytes?]
   [:content-type {:optional false} string?]
   [:size {:optional true} pos-int?]])

(def FileMetadata
  "Schema for file metadata."
  [:map
   [:filename string?]
   [:path {:optional true} string?]
   [:visibility {:optional true} [:enum :public :private]]])

(def StorageResult
  "Schema for storage operation result."
  [:map
   [:key string?]
   [:url {:optional true} [:maybe string?]]
   [:size pos-int?]
   [:content-type string?]
   [:stored-at inst?]])

(def FileValidationOptions
  "Schema for file validation options."
  [:map
   [:max-size {:optional true} pos-int?]
   [:allowed-types {:optional true} [:vector string?]]
   [:allowed-extensions {:optional true} [:vector string?]]
   [:virus-scan {:optional true} boolean?]])

;; ============================================================================
;; Image Processing Schemas
;; ============================================================================

(def ImageDimensions
  "Schema for image dimensions."
  [:map
   [:width {:optional true} [:maybe pos-int?]]
   [:height {:optional true} [:maybe pos-int?]]])

(def ImageInfo
  "Schema for image metadata."
  [:map
   [:width pos-int?]
   [:height pos-int?]
   [:format string?]
   [:size pos-int?]])

;; ============================================================================
;; Configuration Schemas
;; ============================================================================

(def LocalStorageConfig
  "Schema for local filesystem storage configuration."
  [:map
   [:base-path string?]
   [:url-base {:optional true} string?]
   [:signing-secret {:optional true} string?]
   [:create-directories? {:optional true} boolean?]])

(def S3StorageConfig
  "Schema for S3 storage configuration."
  [:map
   [:bucket string?]
   [:region string?]
   [:access-key {:optional true} string?]
   [:secret-key {:optional true} string?]
   [:endpoint {:optional true} string?]  ; For S3-compatible services
   [:public-read? {:optional true} boolean?]])

(def GCSStorageConfig
  "Schema for Google Cloud Storage configuration."
  [:map
   [:bucket string?]
   [:project-id string?]
   [:credentials-path {:optional true} string?]
   [:public-read? {:optional true} boolean?]])

(def StorageConfig
  "Schema for storage configuration."
  [:map
   [:adapter [:enum :local :s3 :gcs]]
   [:local {:optional true} LocalStorageConfig]
   [:s3 {:optional true} S3StorageConfig]
   [:gcs {:optional true} GCSStorageConfig]
   [:default-visibility {:optional true} [:enum :public :private]]
   [:max-file-size {:optional true} pos-int?]])

;; ============================================================================
;; Validation Functions
;; ============================================================================

(def ^:private file-data-validator (m/validator FileData))
(def ^:private file-data-explainer (m/explainer FileData))
(def ^:private file-metadata-validator (m/validator FileMetadata))
(def ^:private storage-config-validator (m/validator StorageConfig))
(def ^:private storage-config-explainer (m/explainer StorageConfig))
(def ^:private image-dimensions-validator (m/validator ImageDimensions))

(defn valid-file-data?
  "Check if file data conforms to FileData schema."
  [data]
  (file-data-validator data))

(defn valid-file-metadata?
  "Check if metadata conforms to FileMetadata schema."
  [metadata]
  (file-metadata-validator metadata))

(defn valid-storage-config?
  "Check if config conforms to StorageConfig schema."
  [config]
  (storage-config-validator config))

(defn valid-image-dimensions?
  "Check if dimensions conform to ImageDimensions schema."
  [dimensions]
  (image-dimensions-validator dimensions))

(defn explain-file-data
  "Explain validation errors for file data."
  [data]
  (file-data-explainer data))

(defn explain-storage-config
  "Explain validation errors for storage config."
  [config]
  (storage-config-explainer config))
