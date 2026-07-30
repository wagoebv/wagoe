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
    Map with :bytes, :content-type, :size, or nil if not found")

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
    Boolean indicating existence")

  (generate-signed-url [this file-key expiration-seconds]
    "Generate a time-limited signed URL for secure file access.

    Parameters:
    - file-key: Unique identifier for the stored file
    - expiration-seconds: How long the URL should be valid

    Returns:
    String containing the signed URL, or nil if not supported"))

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
