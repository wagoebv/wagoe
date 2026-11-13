(ns wagoe.storage.shell.adapters.local
  "Local filesystem storage adapter.

  Implements IFileStorage for storing files on the local filesystem.
  Suitable for development and single-server deployments."
  (:require [wagoe.storage.ports :as ports]
            [wagoe.storage.core.validation :as validation]
            [wagoe.storage.shell.adapters.spool :as spool]
            [wagoe.observability.logging.ports :as logging]
            [clojure.string :as str])
  (:import [java.nio.file CopyOption Files Path Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]
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

(defn canonical-key
  "A storage key in the one form signatures are computed over: forward
   slashes.

   `path-join` yields the platform separator, so on Windows a key is stored
   and signed as `2a\\photo.jpg` while the URL — and therefore the key the
   router hands back — carries `2a/photo.jpg`. Signing one form and verifying
   the other made every freshly issued signed URL 403 on that platform
   (BOU-421)."
  [file-key]
  (some-> file-key str (str/replace "\\" "/")))

(def max-signed-seconds
  "The longest a signed URL lives: 7 days, as S3 and GCS allow (BOU-991)."
  (* 7 24 3600))

(def ^:private clock-skew-seconds
  "How far another node's clock may run ahead when it signed at the cap."
  60)

(defn- signed-message
  "What a signature covers: the key, the expiry, and the file's version, so a
   URL dies with the file it was issued for (BOU-991)."
  [file-key expires version]
  (cond-> (str (canonical-key file-key) ":" expires)
    version (str ":" version)))

(defn- sign-url
  "`base-url` with the `expires`/`v`/`signature` query a signed local URL
   carries; `version` is its content's, see `content-version`. One place, so the URL
   a stored file reports and the URL the route accepts cannot disagree."
  [signing-secret file-key base-url expiration-seconds version]
  (let [expires (+ (now-epoch-seconds) (min (long (or expiration-seconds 3600)) max-signed-seconds))
        version (or version 0)
        sig     (hmac-sha256-hex signing-secret (signed-message file-key expires version))]
    (str base-url "?expires=" expires "&v=" version "&signature=" sig)))

(defn verify-signed-url
  "Verify a signed local-storage URL. Given the configured `signing-secret`, the
   `file-key`, and the URL's query params (`:expires` epoch-seconds, `:version`
   its `v`, `:signature` hex), return true iff the signature matches and the
   URL has not expired nor names an expiry past `max-signed-seconds`.

   A valid signature names a version; the serving route must also check it is
   the file's, `retrieve-file`'s `:version`, or a replaced file is served
   (BOU-991). The local adapter cannot enforce either at the filesystem layer.

   Total on any input a query string can produce: Ring gives a repeated
   parameter as a vector, and a vector reached `(long …)` as a
   ClassCastException — a 500 where the answer is simply \"not a valid
   signature\" (BOU-421). Anything that is not a single scalar is
   invalid, which is also the fail-closed reading of an ambiguous request."
  [signing-secret file-key {:keys [expires signature version]}]
  (boolean
   (when (and signing-secret file-key (string? signature) (or (nil? version) (string? version)))
     (let [exp (cond (integer? expires) expires
                     (string? expires)  (parse-long expires))
           now (now-epoch-seconds)]
       (and exp
            (>= (long exp) now)
            ;; A URL issued before the cap may name any expiry (BOU-991)
            (<= (long exp) (+ now max-signed-seconds clock-skew-seconds))
            (constant-time=? signature
                             (hmac-sha256-hex signing-secret (signed-message file-key exp version))))))))

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

(defn- resolve-within-root
  "The absolute path `file-key` names under `base-path`, or nil when it escapes.

   Containment, not sanitising: `..` segments, absolute keys and symlinked
   directories all resolve away before the comparison, so the check holds for
   forms a string filter never anticipates. The mounted HTTP routes hand this
   a caller-supplied key — `../../deps.edn` read a file outside the root
   before the check existed (BOU-421)."
  [base-path file-key]
  ;; A blank key is not a key: it resolves to the root itself, and
  ;; `delete-file ""` then removed the storage root — the catch-all route
  ;; matches `DELETE …/delete/` with an empty key (BOU-421). Refused
  ;; here rather than in the handler, so every caller of the port is covered.
  (when (and base-path (not (str/blank? (str file-key))))
    (let [empty-opts (make-array java.nio.file.LinkOption 0)
          real       (fn [^java.nio.file.Path p]
                       (try (.toRealPath p empty-opts) (catch Exception _ nil)))
          root       (.normalize (.toAbsolutePath (Paths/get base-path (into-array String []))))
          root-real  (or (real root) root)
          target     (.normalize (.resolve root (Paths/get (str file-key) (into-array String []))))
          ;; Normalizing collapses `..`, but it does not resolve symlinks — a
          ;; link inside the root pointing out of it passed the check and its
          ;; target was read (BOU-421). toRealPath resolves them, and
          ;; demands existence, so it is applied to the deepest ancestor that
          ;; does exist: enough to catch a linked directory on the way down,
          ;; while a key that is simply absent still answers "not here".
          ;;
          ;; NOFOLLOW on the probe, because a *dangling* link is the case where
          ;; following would lie: `Files/exists` reports false for it, the walk
          ;; fell through to its in-root parent, and `Files/write` then followed
          ;; the link and created the file outside the root (BOU-421 review).
          ;; Seen as existing, it reaches toRealPath, which cannot resolve it —
          ;; no anchor, no write. A link whose target does exist inside the root
          ;; still resolves and is still allowed.
          no-follow  (into-array java.nio.file.LinkOption
                                 [java.nio.file.LinkOption/NOFOLLOW_LINKS])
          existing   (loop [p target]
                       (cond (nil? p)                      nil
                             (Files/exists p no-follow)    p
                             :else                         (recur (.getParent p))))
          anchor     (some-> existing real)]
      (when (and anchor
                 (.startsWith anchor root-real)
                 ;; Strictly inside: the root is not a file this port
                 ;; addresses. "", ".", "./" and "a/.." all resolve to it, and
                 ;; `delete-file` then removed an empty storage root
                 ;; (BOU-421).
                 (not (.equals target root))
                 (not (.equals (.normalize target) (.normalize root))))
        (.toString target)))))

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

(defn- streamed-storage-key
  "`generate-storage-key` for a file not yet written: sharded by a random id
   instead of its hash."
  [filename]
  (let [id  (str (UUID/randomUUID))
        ext (validation/get-file-extension filename)]
    (path-join (subs id 0 2) (cond-> (str (System/currentTimeMillis) "-" id) ext (str "." ext)))))

(defn named-storage-key
  "The key a caller names with `:path`, or nil."
  [{:keys [filename path]}]
  (when path
    (path-join (or (validation/normalize-path path) "")
               (validation/sanitize-filename filename))))

(defn- target-path
  "Where `storage-key` is written, with its directory created. Through the
   same containment check the read paths use: an upload naming a symlinked
   directory inside the root created or truncated files outside it (BOU-421)."
  ^Path [base-path storage-key]
  (let [full-path (or (resolve-within-root base-path storage-key)
                      (throw (ex-info "Storage key resolves outside the storage root"
                                      {:type :validation-error
                                       :key  storage-key})))
        file-path (Paths/get full-path (into-array String []))]
    (ensure-directory-exists (.getParent file-path))
    file-path))

(defn- content-version
  "A signed URL's version of `bytes`: their hash, so a URL dies with the
   content it was issued for, whatever the clock or filesystem (BOU-991)."
  [bytes]
  (subs (compute-sha256 bytes) 0 16))

(defn- file-version
  "`content-version` of the file `file-key` names, or nil when there is none."
  ;; ponytail: reads the whole file per signed URL; keep a stored hash if large files make that slow.
  [base-path file-key]
  (let [path (some-> (resolve-within-root base-path file-key) (Paths/get (into-array String [])))]
    (when (and path (Files/exists path (make-array java.nio.file.LinkOption 0)))
      (content-version (Files/readAllBytes path)))))

(defn- spool-dir
  "Where a streamed file is written before it is moved to its key: inside the
   root, so the move stays on one filesystem and is atomic, and in one place,
   so the boot can clear what a crash left."
  ^Path [base-path]
  (Paths/get base-path (into-array String [".spool"])))

(defn- file-url
  "Signed when a secret is configured: the download route rejects an
   unsigned URL, so reporting one would hand the caller a link that 403s
   (BOU-421)."
  [url-base signing-secret storage-key version]
  (when url-base
    (let [base (str url-base "/" (canonical-key storage-key))]
      (if signing-secret
        (sign-url signing-secret storage-key base nil version)
        base))))

(defn- stored
  "Log a stored file and return `store-file`'s result for it."
  [logger url-base signing-secret storage-key size content-type version]
  (when logger
    (logging/info logger "File stored"
                  {:event ::file-stored :key storage-key :size size :content-type content-type}))
  {:key          storage-key
   :url          (file-url url-base signing-secret storage-key version)
   :size         size
   :content-type content-type
   :stored-at    (java.util.Date.)})

;; ============================================================================
;; Local Storage Adapter
;; ============================================================================

(defrecord LocalFileStorage [base-path url-base signing-secret logger]
  ports/IFileStorage

  (store-file [_ file-data metadata]
    (try
      (let [{:keys [bytes content-type]} file-data
            storage-key (or (named-storage-key metadata)
                            (generate-storage-key bytes (:filename metadata)))
            file-path   (target-path base-path storage-key)]
        (Files/write file-path ^bytes bytes (make-array java.nio.file.OpenOption 0))
        (stored logger url-base signing-secret storage-key (alength ^bytes bytes) content-type (content-version bytes)))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to store file"
                         {:event ::store-file-failed
                          :filename (:filename metadata)
                          :error (.getMessage e)}))
        (throw (ex-info "Failed to store file"
                        {:type :storage-error
                         :filename (:filename metadata)
                         :error (.getMessage e)}
                        e)))))

  (retrieve-file [_ file-key]
    (try
      (let [full-path (resolve-within-root base-path file-key)
            file-path (some-> full-path (Paths/get (into-array String [])))]

        (when (and file-path
                   (Files/exists file-path (make-array java.nio.file.LinkOption 0)))
          (let [bytes (Files/readAllBytes file-path)
                size (alength bytes)
                content-type (or (validation/mime-type-from-extension file-key)
                                 "application/octet-stream")]

            (when logger
              (logging/debug logger "File retrieved"
                             {:event ::file-retrieved
                              :key file-key
                              :size size}))

            {:bytes bytes
             :content-type content-type
             :size size
             ;; What a signed URL for it carries as `v` (BOU-991)
             :version (content-version bytes)})))

      (catch Exception e
        (when logger
          (logging/error logger "Failed to retrieve file"
                         {:event ::retrieve-file-failed
                          :key file-key
                          :error (.getMessage e)}))
        nil)))

  (delete-file [_ file-key]
    (try
      (let [full-path (resolve-within-root base-path file-key)
            file-path (some-> full-path (Paths/get (into-array String [])))]

        (if (and file-path
                 (Files/exists file-path (make-array java.nio.file.LinkOption 0)))
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
      (let [full-path (resolve-within-root base-path file-key)
            file-path (some-> full-path (Paths/get (into-array String [])))]
        (boolean
         (and file-path
              (Files/exists file-path (make-array java.nio.file.LinkOption 0)))))

      (catch Exception e
        (when logger
          (logging/error logger "File exists check failed"
                         {:event ::file-exists-check-failed
                          :key file-key
                          :error (.getMessage e)}))
        (throw (ex-info "Could not check whether the file exists"
                        {:type :storage-error :key file-key}
                        e)))))

  (generate-signed-url [_ file-key expiration-seconds]
    ;; With a signing-secret we issue a genuinely signed URL (HMAC-SHA256 over
    ;; "<key>:<expires>:<version>", which the serving route enforces via
    ;; `verify-signed-url`). Without a secret we fall back to the plain public URL.
    (when url-base
      (let [base (str url-base "/" (canonical-key file-key))]
        (if signing-secret
          (sign-url signing-secret file-key base expiration-seconds (file-version base-path file-key))
          base))))

  ports/IStreamingFileStorage

  (store-stream [_ {:keys [write! content-type]} metadata]
    ;; Written under the root and moved into place, so a reader never sees
    ;; half a file and a failed writer leaves none.
    (let [storage-key (or (named-storage-key metadata) (streamed-storage-key (:filename metadata)))
          file-path   (target-path base-path storage-key)]
      (spool/with-spooled write! (doto (spool-dir base-path) ensure-directory-exists)
        #(Files/move ^Path % file-path (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                                               StandardCopyOption/ATOMIC_MOVE])))
      (stored logger url-base signing-secret storage-key (Files/size file-path) content-type
              (file-version base-path storage-key)))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-local-storage
  "Create a local filesystem storage adapter.

  Options:
  - :base-path - Root directory for file storage (required)
  - :url-base - Public base URL files are reachable at (optional). A URL is
    emitted as `<url-base>/<key>`, so with :signing-secret set this must be
    the public URL of the mounted download route — that route is the only
    thing that verifies the signature (BOU-421). Pointing it at a static
    server or CDN instead means the signature is never checked.
  - :signing-secret - HMAC key enabling signed, expiring URLs (optional)
  - :create-directories? - Create base directory if missing (default: true)
  - :logger - Logger instance (optional)"
  [{:keys [base-path url-base signing-secret create-directories? logger]
    :or {create-directories? true}}]
  (when-not base-path
    (throw (ex-info "base-path is required for local storage"
                    {:type :configuration-error
                     :provided-config {:base-path base-path}})))

  ;; Create base directory if needed
  (when create-directories?
    (let [path (Paths/get base-path (into-array String []))]
      (ensure-directory-exists path)))
  (spool/remove-abandoned! (spool-dir base-path) (Duration/ofHours 1))

  (when logger
    (logging/info logger "Local storage initialized"
                  {:event ::local-storage-initialized
                   :base-path base-path
                   :url-base url-base}))

  ;; A blank secret is not a secret: it is truthy, so it selected the signing
  ;; branch and then SecretKeySpec threw "Empty key" — after the bytes were
  ;; already on disk, leaving the file orphaned (BOU-421). Absent and
  ;; blank mean the same thing here, and mean it before anything is written.
  (->LocalFileStorage base-path url-base (not-empty (some-> signing-secret str/trim)) logger))
