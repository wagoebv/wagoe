# wagoe/storage

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-storage.svg)](https://clojars.org/com.wagoe/wagoe-storage)

File storage abstraction with local filesystem and S3 backends, including upload validation and image processing.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-storage {:mvn/version "1.0.1"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-storage "1.0.1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Storage Protocol** | Abstract storage interface for multiple backends |
| **Local Adapter** | Filesystem-based storage for development |
| **S3 Adapter** | AWS S3 integration for production |
| **Upload Validation** | File type, size, and content validation |
| **Image Processing** | Basic resize and thumbnail generation |

## Requirements

- Clojure 1.12+
- wagoe/platform
- wagoe/core

## Quick Start

### 1. Configure

`:wagoe/storage` in `:active`, chosen by `:provider` (default `:local`). The
full set of keys is in [src/wagoe/storage/README.md](src/wagoe/storage/README.md#configuration).

```clojure
;; resources/conf/dev/config.edn
:wagoe/storage {:provider :local :root "uploads"}   ; :root or :base-path

;; resources/conf/prod/config.edn
:wagoe/storage {:provider :s3
                :bucket #env STORAGE_BUCKET
                :region #env AWS_REGION}
```

The component's value is `{:provider <kw> :storage <IFileStorage> :service
<IStorageService>}`. Take it into your own component with
`(ig/ref :wagoe/storage)` and call the ports on `:storage`.

### 2. Store, read, sign, delete

```clojure
(ns myapp.avatars
  (:require [wagoe.storage.core.validation :as validation]
            [wagoe.storage.ports :as storage]))

(defn save-avatar!
  "`store` is the :wagoe/storage component."
  [store user-id ^bytes jpeg]
  (let [file  {:bytes jpeg :content-type "image/jpeg"}
        meta  {:filename (str user-id ".jpg") :path "avatars"}
        check (validation/validate-file file meta {:max-size      (* 2 1024 1024)
                                                   :allowed-types ["image/jpeg" "image/png"]})]
    (if (:valid? check)
      (storage/store-file (:storage store) file meta)
      check)))
;; => {:key "avatars/42.jpg" :url ... :size 12345 :content-type "image/jpeg" :stored-at #inst ...}
;;    or {:valid? false :errors [{:code :file-too-large ...}]}

(defn avatar [store user-id]
  (storage/retrieve-file (:storage store) (str "avatars/" user-id ".jpg")))
;; => {:bytes ... :content-type "image/jpeg" :size 12345}, or nil

(defn forget-avatar! [store user-id]
  (let [fs  (:storage store)
        key (str "avatars/" user-id ".jpg")]
    (when (storage/file-exists? fs key)
      (storage/delete-file fs key))))

;; A link that expires in an hour; nil when the adapter cannot sign.
(defn avatar-link [store user-id]
  (storage/generate-signed-url (:storage store) (str "avatars/" user-id ".jpg") 3600))
```

### 3. At the REPL, without Integrant

```clojure
(require '[wagoe.storage.shell.adapters.local :as local]
         '[wagoe.storage.ports :as storage])

(def fs (local/create-local-storage {:base-path "/tmp/uploads"}))

(storage/store-file fs {:bytes (.getBytes "hi") :content-type "text/plain"}
                    {:filename "hello.txt"})
```

`wagoe.storage.shell.adapters.s3/create-s3-storage` and
`wagoe.storage.shell.adapters.gcs/create-gcs-storage` take the same keys as the
config entry.

### Image Processing

```clojure
(ns myapp.images
  (:require [wagoe.storage.shell.adapters.image-processor :as img]
            [wagoe.storage.ports :as storage]))

(def processor (img/create-image-processor {}))

(defn thumbnails [image-bytes]
  {:small  (storage/create-thumbnail processor image-bytes 100)
   :banner (storage/resize-image processor image-bytes {:width 1200})
   :info   (storage/get-image-info processor image-bytes)})
;; :info => {:width 1920 :height 1080 :format "jpg" :size 245678}
```

### HTTP routes

Upload, download, delete and signed-URL endpoints mount only with
`:expose-http? true`; see [src/wagoe/storage/README.md](src/wagoe/storage/README.md#http-routes).

## Module Structure

```
libs/storage/src/wagoe/storage/
├── core/validation.clj      # Upload validation, filename sanitising (pure)
├── ports.clj                # IFileStorage, IImageProcessor
├── schema.clj               # Malli schemas: file data, metadata, config
└── shell/
    ├── adapters/            # local, s3, gcs, image-processor
    ├── service.clj          # validate-then-store service
    ├── http_handlers.clj    # optional routes
    └── module_wiring.clj    # Integrant :wagoe/storage, :wagoe/storage-routes
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/platform` | 1.0.1 | Configuration, database |
| `aws-sdk/s3` | 2.39.5 | S3 client |
| `aws-sdk/s3-transfer-manager` | 2.39.5 | Efficient uploads |

## S3 Configuration

For S3 storage, configure AWS credentials via:

1. **Environment variables** (recommended for production):
   ```bash
   export AWS_ACCESS_KEY_ID=...
   export AWS_SECRET_ACCESS_KEY=...
   export AWS_REGION=us-east-1
   ```

2. **Configuration file** (development):
   ```clojure
   :wagoe/storage {:provider :s3
                   :bucket "my-bucket"
                   :region "us-east-1"
                   :access-key "AKIA..."
                   :secret-key "..."}
   ```

3. **IAM Role** (EC2, ECS, Lambda): leave out `:access-key` and `:secret-key`;
   the AWS default credentials chain is used.

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│            Your Application             │
└─────────────────┬───────────────────────┘
                  │ uses
                  ▼
┌─────────────────────────────────────────┐
│            wagoe/storage             │
│        (local, S3, validation)          │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│           wagoe/platform             │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/storage
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
