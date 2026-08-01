# Storage Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

File storage abstraction with pluggable backends. Ships local filesystem, AWS
S3 / S3-compatible, and Google Cloud Storage adapters behind a single
`IFileStorage` port, plus pure file validation, a Java-AWT image processor
(resize + thumbnails, no native deps), signed-URL generation, a
`:wagoe/storage` Integrant key, and Ring upload/download handlers.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.storage.ports` | Protocols: `IFileStorage`, `IImageProcessor` |
| `wagoe.storage.core.validation` | Pure: size/type/extension validation, filename sanitization, MIME lookup |
| `wagoe.storage.schema` | Malli schemas: `FileData`, `FileMetadata`, `StorageResult`, `ImageInfo`, `*StorageConfig` |
| `wagoe.storage.shell.service` | `IStorageService` protocol + `StorageService` record — validation + storage + optional image processing |
| `wagoe.storage.shell.adapters.local` | `LocalFileStorage` — filesystem, content-addressed keys with directory sharding |
| `wagoe.storage.shell.adapters.s3` | `S3FileStorage` — AWS S3 / S3-compatible (MinIO, DO Spaces) via AWS SDK v2 |
| `wagoe.storage.shell.adapters.gcs` | `GCSFileStorage` — Google Cloud Storage via `google-cloud-storage`; V4 signed URLs |
| `wagoe.storage.shell.adapters.image-processor` | `JavaImageProcessor` — Java AWT / `javax.imageio` |
| `wagoe.storage.shell.module-wiring` | `:wagoe/storage` + `:wagoe/storage-routes` Integrant keys |
| `wagoe.storage.shell.http-handlers` | Ring handlers + `storage-routes` (normalized `:api` map format) |

## Ports

### `IFileStorage` — the storage seam (both adapters implement it)

```clojure
(store-file          [this file-data metadata])       ; => {:key :url :size :content-type :stored-at}
(retrieve-file       [this file-key])                 ; => {:bytes :content-type :size} or nil
(delete-file         [this file-key])                 ; => boolean
(file-exists?        [this file-key])                 ; => boolean
(generate-signed-url [this file-key expiration-seconds]) ; => url string or nil
```

- `file-data` is `{:bytes <byte-array> :content-type <string>}`.
- `metadata` is `{:filename <string> :path <optional> :visibility :public|:private}`.
- Local `generate-signed-url` issues a real HMAC-SHA256 signed URL
  (`?expires=<epoch>&signature=<hex>`) when `:signing-secret` is configured;
  the serving route enforces it via `local/verify-signed-url`. Without a secret
  it falls back to the plain public URL (or `nil` if `:url-base` is unset).

### `IImageProcessor` — optional image ops

```clojure
(resize-image     [this image-bytes dimensions])  ; dimensions {:width w :height h}, either nil => proportional
(create-thumbnail [this image-bytes size])        ; size is an INTEGER (max dimension), NOT a map
(get-image-info   [this image-bytes])             ; => {:width :height :format :size}
(is-image?        [this bytes content-type])      ; => boolean
```

## Adapters & config selection

### Local (`create-local-storage`)

```clojure
(require '[wagoe.storage.shell.adapters.local :as local])

(def storage
  (local/create-local-storage
    {:base-path "uploads"          ; required — root dir
     :url-base  "https://cdn.example.com/files" ; optional — enables :url + signed URLs
     :signing-secret "hmac-key"    ; optional — enables real signed, expiring URLs
     :create-directories? true     ; default true
     :logger    logger}))          ; optional
```

- Auto-generated key (no `:path`): `{shard}/{timestamp}-{uuid}-{hash16}.{ext}`
  where `shard` = first 2 hex chars of the content SHA-256 (directory sharding
  to avoid huge dirs). With an explicit `:path`, the key is
  `{sanitized-path}/{sanitized-filename}`.

### S3 (`create-s3-storage` / `close-s3-storage`)

```clojure
(require '[wagoe.storage.shell.adapters.s3 :as s3])

(def storage
  (s3/create-s3-storage
    {:bucket      "my-bucket"      ; required
     :region      "us-east-1"      ; required
     :access-key  "..."            ; optional — falls back to DefaultCredentialsProvider
     :secret-key  "..."            ; optional
     :endpoint    "http://localhost:9000" ; optional — MinIO/Spaces; enables path-style access
     :prefix      "app/"           ; optional — key prefix
     :public-read? false           ; optional — PUBLIC_READ vs PRIVATE ACL
     :logger      logger}))

(s3/close-s3-storage storage)      ; releases S3Client + S3Presigner — call on shutdown
```

- S3 key: `{prefix}/{timestamp}-{uuid8}.{ext}`.
- Signed URLs use `S3Presigner` (`X-Amz-*` query params); public objects get a
  direct `https://{bucket}.s3.amazonaws.com/{key}` URL.

### GCS (`create-gcs-storage` / `close-gcs-storage`)

```clojure
(require '[wagoe.storage.shell.adapters.gcs :as gcs])

(def storage
  (gcs/create-gcs-storage
    {:bucket           "my-bucket"   ; required
     :project-id       "my-project"  ; required
     :credentials-path "sa.json"     ; optional — else Application Default Credentials
     :prefix           "app/"        ; optional — key prefix
     :public-read?     false         ; optional — controls public-URL emission
     :logger           logger}))

(gcs/close-gcs-storage storage)      ; releases the GCS client — call on shutdown
```

- GCS key: `{prefix}/{timestamp}-{uuid8}.{ext}`.
- `generate-signed-url` uses GCS V4 signing (needs service-account credentials);
  returns `nil` + logs on failure.

## Wiring — `:wagoe/storage` Integrant key

`wagoe.storage.shell.module-wiring` ships `defmethod ig/init-key
:wagoe/storage`, dispatching on `:provider` (`:local` / `:s3` / `:gcs`). It
builds the adapter + a default image processor and returns
`{:provider <kw> :storage <IFileStorage> :service <IStorageService>}`; the
halt-key closes the S3/GCS client. `:wagoe/storage-routes` turns the service
into normalized `:api` routes. The config matches the `wagoe new` catalogue
(`:local` accepts `:root` as an alias for `:base-path`):

```clojure
:wagoe/storage {:provider :local :root "uploads"}
;; or :s3 / :gcs — see the adapter configs above
:wagoe/storage-routes {:storage (ig/ref :wagoe/storage)}
```

To wire the factories by hand instead (no Integrant), use
`create-storage-service`:

```clojure
(require '[wagoe.storage.shell.service :as service])
(require '[wagoe.storage.shell.adapters.image-processor :as img])

(def svc
  (service/create-storage-service
    {:storage         storage                 ; required — any IFileStorage
     :image-processor (img/create-image-processor {:logger logger}) ; optional
     :logger          logger}))               ; optional
```

`create-storage-service` throws if `:storage` is missing. The service is the
imperative shell: it sanitizes filenames, runs pure `validate-file`, then
delegates to the adapter, wrapping results as `{:success bool ...}`.

## Usage — service layer (`IStorageService`)

```clojure
(require '[wagoe.storage.shell.service :as service])

;; Upload with validation options
(service/upload-file svc
  {:bytes (.getBytes "hi") :content-type "text/plain"}
  {:filename "note.txt"}
  {:max-size 5242880 :allowed-types ["text/plain"] :allowed-extensions ["txt"]})
;=> {:success true :data {:key "..." :url "..." :size 2 :content-type "text/plain" :stored-at #inst"..."}}
;=> {:success false :errors [{:code :file-too-large :message "..." :details {...}}]}

;; Upload an image, optionally producing a thumbnail
(service/upload-image svc image-bytes
  {:filename "pic.jpg"}
  {:create-thumbnail true :thumbnail-size 200})
;=> {:success true :original {...} :thumbnail {...}}

(service/download-file svc file-key)   ; => {:bytes :content-type :size} or nil
(service/remove-file   svc file-key)   ; => boolean
(service/get-file-url  svc file-key 3600) ; signed (S3 private) or public URL
```

To bypass the service, call the `wagoe.storage.ports` fns directly on an
adapter (`store-file`, `retrieve-file`, `file-exists?`, `delete-file`,
`generate-signed-url`) — but you then lose validation and filename sanitization.

## Validation (pure — `wagoe.storage.core.validation`)

```clojure
(require '[wagoe.storage.core.validation :as v])

(v/validate-file file-data metadata {:max-size 5242880 :allowed-types ["image/jpeg"]})
;=> {:valid? true :data {...}} | {:valid? false :errors [{:code ... :message ...}]}

(v/sanitize-filename "../../etc/passwd")  ; => "etcpasswd" (strips .., separators, non-word chars)
```

- Never throws — returns result maps. Error codes: `:file-too-large`,
  `:invalid-content-type`, `:invalid-extension`, `:not-an-image`.
- `default-max-file-size` = 10 MB. `image-mime-types` / `common-mime-types` back
  the type checks and extension→MIME lookup.

## HTTP endpoints (`storage-routes`)

`(http-handlers/storage-routes svc {:base-path "/storage"})` returns the
framework's **normalized module `:api` map format** — a vector of
`{:path … :methods {…}}` maps. Paths carry NO `/api` prefix (versioning adds
`/api/v1`). Mount via the module route mechanism (`:wagoe/storage-routes`):

| Method | Path | Handler |
|--------|------|---------|
| POST | `/upload` | multipart `file` + `path`/`visibility`; query `max-size`, `allowed-types`, `allowed-extensions` |
| POST | `/upload/image` | multipart `file` + `create-thumbnail`, `thumbnail-size` |
| GET | `/download/:file-key` | streams bytes with `Content-Disposition: attachment` |
| DELETE | `/delete/:file-key` | 204 on success, 404 otherwise |
| GET | `/url/:file-key` | query `expiration` (default 3600) → JSON `{:url :expiration-seconds}` |

Errors are emitted as RFC-7807 problem details via
`wagoe.platform.core.http.problem-details`.

## Gotchas

1. **`create-thumbnail` takes an integer**, not a map — `size` is the max
   dimension (aspect ratio preserved). Passing a map will break.
2. **Validation never throws.** The service also catches adapter exceptions and
   returns `{:success false :errors [{:code :storage-error ...}]}`.
3. **Image processor is optional.** `upload-image` only builds a thumbnail
   `(when (and create-thumbnail image-processor) ...)`; a failed thumbnail does
   not fail the original upload.
4. **`upload-image` detects the original content-type** from the filename
   extension (`mime-type-from-extension`), falling back to `image/jpeg` for
   unknown extensions — a PNG/WebP/GIF keeps its real type.
5. **Cloud resources must be released** — call `close-s3-storage` /
   `close-gcs-storage` on shutdown (the `:wagoe/storage` halt-key does this).
6. **Local signed URLs** are real HMAC-SHA256 signatures **only with a
   `:signing-secret`**; the serving route must call `local/verify-signed-url`
   to enforce expiry (the filesystem adapter can't). Without a secret the URL is
   the plain public one.
7. **GCS public-read?** controls public-URL *emission*, not object ACLs — make
   the bucket/object publicly accessible via GCS itself for those URLs to work.

## Testing

```bash
clojure -M:test :storage
```

Adapter tests are tagged `^:integration`. The local-adapter suite writes to the
`target/test-storage` temp directory with cleanup fixtures. The image-processor
suite exercises real `javax.imageio` encode/decode.

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
