(ns wagoe.storage.ports
  "Port definitions for file storage functionality.

  This namespace defines the contracts that storage adapters must implement.
  Follows the Functional Core / Imperative Shell pattern where ports define
  the boundaries between pure logic and side effects.")

;; ============================================================================
;; File Storage Port
;; ============================================================================

(defprotocol IFileStorage
  "Port for file storage operations.

  Implementations can store files locally, in S3, GCS, or other storage backends.
  All operations should be idempotent where possible."

  (store-file [this file-data metadata]
    "Store a file and return storage metadata.

    Parameters:
    - file-data: Map containing :bytes (byte array) and :content-type
    - metadata: Map with :filename, :path (optional), :visibility (public/private)

    Returns:
    Map with :key (storage identifier), :url (access URL), :size, :content-type")

  (retrieve-file [this file-key]
    "Retrieve a file by its storage key.

    Parameters:
    - file-key: Unique identifier for the stored file

    Returns:
    Map with :bytes, :content-type, :size, or nil if not found. The local
    adapter adds :version, which its signed URLs carry as `v` (BOU-991).")

  (delete-file [this file-key]
    "Delete a file from storage.

    Parameters:
    - file-key: Unique identifier for the stored file

    Returns:
    Boolean indicating success")

  (file-exists? [this file-key]
    "Check if a file exists in storage.

    Parameters:
    - file-key: Unique identifier for the stored file

    Returns:
    Boolean indicating existence. Throws when it cannot tell: a caller that
    deletes a record for a file that is gone must not read an outage as gone.")

  (generate-signed-url [this file-key expiration-seconds]
    "Generate a time-limited signed URL for secure file access.

    Parameters:
    - file-key: Unique identifier for the stored file
    - expiration-seconds: How long the URL should be valid

    Returns:
    String containing the signed URL, or nil if not supported"))

(defprotocol IStreamingFileStorage
  "Optional, beside `IFileStorage`, for an adapter that can store a file
  without holding all of it in memory."

  (store-stream [this file-data metadata]
    "Store what `(write! out)` writes to an OutputStream. `file-data` is
    {:write! fn :content-type}; `metadata` and the result are `store-file`'s.
    When `write!` throws, nothing is stored and its exception propagates."))

;; ============================================================================
;; Service Ports
;; ============================================================================

(defprotocol IStorageService
  "The storage service, `:service` of `:wagoe/storage`: validation, the upload
  index and the tenant directory around the adapter. The same methods as
  `wagoe.storage.shell.service/IStorageService`, here so another module needs
  no storage shell namespace (BOU-1077)."

  (upload-file [this file-data metadata options]
    "Upload `file-data`: {:bytes}, or {:file} (a File or path) or
    {:input-stream}, streamed so it is never all in memory; the service closes
    the stream. The filename's extension decides its type.
    `metadata` {:filename :path :visibility :owner {:user-id :tenant-id}},
    `options` {:max-size :allowed-types :allowed-extensions}. Returns
    `{:success true :data {:key :url :size :content-type}}`, or
    `{:success false :errors [...]}`.")

  (upload-image [this image-bytes metadata options]
    "Upload an image; `options` {:resize :create-thumbnail :thumbnail-size}.
    Returns {:original :thumbnail}.")

  (download-file [this file-key]
    "The file {:bytes :content-type :size}, or nil.")

  (remove-file [this file-key]
    "Remove the file and its upload record; true when it was removed.")

  (get-file-url [this file-key expiration-seconds]
    "A URL for the file: signed and expiring for a private one."))

(defprotocol ISignedFiles
  "Serving what `get-file-url` signed, from an app's own route."

  (signed-file [this file-key query-params]
    "The file a signed URL names, when its `query-params` (Ring's, string
    keys: \"expires\", \"v\", \"signature\") carry a valid, unexpired
    signature for the file as it is now; nil otherwise. Nil too on an adapter
    whose URLs its own store checks, such as S3."))

;; ============================================================================
;; Upload Index Port
;; ============================================================================

(defprotocol IUploadIndex
  "Who stored which file, and when. `IFileStorage` cannot list files, so data
  export and upload retention read this instead.

  An upload is {:key :user-id :tenant-id :size :content-type :stored-at};
  either owner may be nil."

  (record-upload! [this upload]
    "Record `upload`, replacing any record with the same key.")

  (remove-upload! [this file-key]
    "Forget the upload at `file-key`. Returns true when there was one.")

  (find-uploads [this owner opts]
    "Uploads of `owner`, {:user-id id} or {:tenant-id id}, in key order.
    `opts`: :limit, and :after-key to read the page after that key.")

  (uploads-stored-before [this cutoff limit]
    "At most `limit` uploads stored before `cutoff`, an Instant."))

(defprotocol IUploadPages
  "Beside `IUploadIndex`, which is stable: old uploads a page at a time, so
  upload retention reads past the ones an app keeps without reading them again
  (BOU-1081)."

  (uploads-stored-after [this cutoff after limit]
    "At most `limit` uploads stored before `cutoff`, ordered by stored-at and
    then key, after the upload `after`; from the oldest when `after` is nil."))

(defprotocol IUploadLookup
  "Who owns one file. Beside `IUploadIndex`, not in it, so an index written
  before it still compiles; the storage routes refuse to boot without it."

  (find-upload [this file-key]
    "The upload at `file-key`, or nil."))

;; ============================================================================
;; Export Job Store Port
;; ============================================================================

(defprotocol IExportJobStore
  "Data exports produced by a background job. A job is {:id :subject
  :requested-by :status :file-key :error :created-at :finished-at :expires-at},
  `:subject` {:type :user|:tenant :id}, `:status` :pending, :running, :done or
  :failed."

  (create-export-job! [this job]
    "Store `job`, a new :pending one, and return it; nil when its requester
    has a :pending or :running job for its subject already.")

  (save-export-job! [this job]
    "Store `job`, replacing the one with its id. Returns it.")

  (count-export-jobs-requested-since [this requested-by since]
    "How many jobs `requested-by` asked for since `since`, an Instant.")

  (find-export-job [this id]
    "The job with `id`, or nil.")

  (open-export-job [this subject requested-by]
    "`requested-by`'s :pending or :running job for `subject`, or nil.")

  (export-jobs-created-before [this cutoff open-before limit]
    "At most `limit` jobs created before `cutoff`, an Instant, oldest first.
    A :pending or :running job only when also created before `open-before`:
    a worker may still be building it.")

  (remove-export-job! [this id]
    "Forget the job with `id`."))

;; ============================================================================
;; Image Processing Port
;; ============================================================================

(defprotocol IImageProcessor
  "Port for image processing operations.

  Implementations can use different image processing libraries (ImageMagick,
  Java AWT, etc.) to manipulate images."

  (resize-image [this image-bytes dimensions]
    "Resize an image to specified dimensions.

    Parameters:
    - image-bytes: Byte array of the original image
    - dimensions: Map with :width and :height (either can be nil for proportional)

    Returns:
    Byte array of the resized image")

  (create-thumbnail [this image-bytes size]
    "Create a thumbnail from an image.

    Parameters:
    - image-bytes: Byte array of the original image
    - size: Integer for max dimension (will maintain aspect ratio)

    Returns:
    Byte array of the thumbnail")

  (get-image-info [this image-bytes]
    "Get metadata about an image.

    Parameters:
    - image-bytes: Byte array of the image

    Returns:
    Map with :width, :height, :format, :size")

  (is-image? [this bytes content-type]
    "Check if the provided bytes represent a valid image.

    Parameters:
    - bytes: Byte array to check
    - content-type: MIME type (optional hint)

    Returns:
    Boolean indicating if this is a valid image"))
