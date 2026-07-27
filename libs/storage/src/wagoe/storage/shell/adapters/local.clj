(ns wagoe.storage.shell.adapters.local
  "Local filesystem storage adapter.

  Implements IFileStorage for storing files on the local filesystem.
  Suitable for development and single-server deployments."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.storage.core.validation :as validation]
            [wagoe.observability.logging.ports :as logging]
            [clojure.string :as str])
  (:import [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util UUID]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- bytes->hex
  "Convert byte array to hex string."
  [bytes]
  (apply str (map #(format "%02x" %) bytes)))

;; ============================================================================
;; Signed-URL (HMAC-SHA256) helpers
;; ============================================================================

(defn- hmac-sha256-hex
  "HMAC-SHA256 of `message` keyed by `secret`, hex-encoded."
  [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
    (bytes->hex (.doFinal mac (.getBytes message StandardCharsets/UTF_8)))))

(defn- constant-time=?
  "Constant-time string comparison (timing-attack safe)."
  [^String a ^String b]
  (and a b
       (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                              (.getBytes b StandardCharsets/UTF_8))))

(defn- now-epoch-seconds ^long []
  (quot (System/currentTimeMillis) 1000))

(defn verify-signed-url
  "Verify a signed local-storage URL. Given the configured `signing-secret`, the
   `file-key`, and the URL's query params (`:expires` epoch-seconds, `:signature`
   hex), return true iff the signature matches and the URL has not expired.

   The serving route is responsible for calling this before streaming a private
   file — the local adapter cannot enforce it at the filesystem layer."
  [signing-secret file-key {:keys [expires signature]}]
  (boolean
   (when (and signing-secret file-key expires signature)
     (let [exp (if (string? expires) (parse-long expires) expires)]
       (and exp
            (>= (long exp) (now-epoch-seconds))
            (constant-time=? signature
                             (hmac-sha256-hex signing-secret (str file-key ":" exp))))))))

(defn- compute-sha256
  "Compute SHA-256 hash of bytes."
  [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (bytes->hex (.digest digest))))

(defn- ensure-directory-exists
  "Create directory if it doesn't exist."
  [^Path path]
  (when-not (Files/exists path (make-array java.nio.file.LinkOption 0))
    (Files/createDirectories path (make-array FileAttribute 0))))

(defn- path-join
  "Join path segments safely."
  [& segments]
  (.toString (Paths/get (first segments) (into-array String (rest segments)))))

(defn- sanitize-path
  "Sanitize a path segment to prevent directory traversal."
  [segment]
  (when segment
    (-> segment
        (str/replace #"\.\." "")
        (str/replace #"[/\\]" ""))))

;; ============================================================================
;; Storage Key Generation
;; ============================================================================

(defn- generate-storage-key
  "Generate a unique storage key based on content hash, timestamp, and random component."
  [bytes filename]
  (let [hash (compute-sha256 bytes)
        ext (validation/get-file-extension filename)
        timestamp (System/currentTimeMillis)
        ;; Use first 2 chars of hash for directory sharding to avoid too many
        ;; files in a single directory while keeping lookups simple.
        shard (subs hash 0 2)
        ;; Use a UUID to provide strong uniqueness guarantees even under
        ;; high concurrency with identical content and filenames.
        random-id (str (UUID/randomUUID))
        key-name (str timestamp "-" random-id "-" (subs hash 0 16))]
    (if ext
      (path-join shard (str key-name "." ext))
      (path-join shard key-name))))

;; ============================================================================
;; Local Storage Adapter
;; ============================================================================

(defrecord LocalFileStorage [base-path url-base signing-secret logger]
  ports/IFileStorage

  (store-file [_ file-data metadata]
    (try
      (let [{:keys [bytes content-type]} file-data
            {:keys [filename path]} metadata

            ;; Generate storage key
            storage-key (if path
                          (path-join (sanitize-path path)
                                     (validation/sanitize-filename filename))
                          (generate-storage-key bytes filename))

            ;; Full filesystem path
            full-path (path-join base-path storage-key)
            file-path (Paths/get full-path (into-array String []))

            ;; Ensure parent directory exists
            _ (ensure-directory-exists (.getParent file-path))

            ;; Write file
            _ (Files/write file-path bytes (make-array java.nio.file.OpenOption 0))

            ;; Generate URL if url-base is configured
            url (when url-base
                  (str url-base "/" (str/replace storage-key "\\" "/")))]

        (when logger
          (logging/info logger "File stored"
                        {:event ::file-stored
                         :key storage-key
                         :size (alength bytes)
                         :content-type content-type}))

        {:key storage-key
         :url url
         :size (alength bytes)
         :content-type content-type
         :stored-at (java.util.Date.)})

      (catch Exception e
        (when logger
          (logging/error logger "Failed to store file"
                         {:event ::store-file-failed
                          :filename (:filename metadata)
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to store file"
                        {:filename (:filename metadata)
                         :error (.getMessage e)}
                        e)))))

  (retrieve-file [_ file-key]
    (try
      (let [full-path (path-join base-path file-key)
            file-path (Paths/get full-path (into-array String []))]

        (when (Files/exists file-path (make-array java.nio.file.LinkOption 0))
          (let [bytes (Files/readAllBytes file-path)
                size (alength bytes)
                ;; Try to determine content type from extension
                _ext (validation/get-file-extension file-key)
                content-type (or (validation/mime-type-from-extension file-key)
                                 "application/octet-stream")]

            (when logger
              (logging/debug logger "File retrieved"
                             {:event ::file-retrieved
                              :key file-key
                              :size size}))

            {:bytes bytes
             :content-type content-type
             :size size})))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to retrieve file"
                         {:event ::retrieve-file-failed
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  (delete-file [_ file-key]
    (try
      (let [full-path (path-join base-path file-key)
            file-path (Paths/get full-path (into-array String []))]

        (if (Files/exists file-path (make-array java.nio.file.LinkOption 0))
          (do
            (Files/delete file-path)

            (when logger
              (logging/info logger "File deleted"
                            {:event ::file-deleted
                             :key file-key}))

            true)
          false))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to delete file"
                         {:event ::delete-file-failed
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (file-exists? [_ file-key]
    (try
      (let [full-path (path-join base-path file-key)
            file-path (Paths/get full-path (into-array String []))]
        (Files/exists file-path (make-array java.nio.file.LinkOption 0)))

      (catch Exception e
        (when logger
          (logging/error logger "File exists check failed"
                         {:event ::file-exists-check-failed
                          :key file-key
                          :error (.getMessage e)}))
        false)))

  (generate-signed-url [_ file-key expiration-seconds]
    ;; With a signing-secret we issue a genuinely signed URL (HMAC-SHA256 over
    ;; "<key>:<expires>", with an expiry the serving route enforces via
    ;; `verify-signed-url`). Without a secret we fall back to the plain public URL.
    (when url-base
      (let [base (str url-base "/" (str/replace file-key "\\" "/"))]
        (if signing-secret
          (let [expires (+ (now-epoch-seconds) (long (or expiration-seconds 3600)))
                sig     (hmac-sha256-hex signing-secret (str file-key ":" expires))]
            (str base "?expires=" expires "&signature=" sig))
          base)))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-local-storage
  "Create a local filesystem storage adapter.

  Options:
  - :base-path - Root directory for file storage (required)
  - :url-base - Base URL for accessing files (optional)
  - :signing-secret - HMAC key enabling signed, expiring URLs (optional)
  - :create-directories? - Create base directory if missing (default: true)
  - :logger - Logger instance (optional)"
  [{:keys [base-path url-base signing-secret create-directories? logger]
    :or {create-directories? true}}]
  (when-not base-path
    (throw (ex-info "base-path is required for local storage"
                    {:provided-config {:base-path base-path}})))

  ;; Create base directory if needed
  (when create-directories?
    (let [path (Paths/get base-path (into-array String []))]
      (ensure-directory-exists path)))

  (when logger
    (logging/info logger "Local storage initialized"
                  {:event ::local-storage-initialized
                   :base-path base-path
                   :url-base url-base}))

  (->LocalFileStorage base-path url-base signing-secret logger))
