# wagoe/storage

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-storage.svg)](https://clojars.org/com.wagoe/wagoe-storage)

File storage abstraction with local filesystem and S3 backends, including upload validation and image processing.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-storage {:mvn/version "1.0.0-beta-5"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-storage "1.0.0-beta-5"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Storage Protocol** | Abstract storage interface for multiple backends |
| **Local Adapter** | Filesystem-based storage for development |
| **S3 Adapter** | AWS S3 integration for production |
| **Upload Validation** | File type, size, and content validation |
| **Image Processing** | Basic resize and thumbnail generation |
| **Path Generation** | Configurable path strategies (date-based, UUID, etc.) |

## Requirements

- Clojure 1.12+
- wagoe/platform
- wagoe/core

## Quick Start

### Local Storage

```clojure
(ns myapp.storage
  (:require [wagoe.storage.shell.adapters.local :as local]))

;; Create local storage adapter
(def store (local/create-adapter
  {:base-path "/var/uploads"
   :url-prefix "/uploads"}))

;; Store file
(storage/put store "avatars/user-123.jpg" file-bytes)
;; => {:path "avatars/user-123.jpg" :size 12345 :url "/uploads/avatars/user-123.jpg"}

;; Get file
(storage/get store "avatars/user-123.jpg")
;; => #<byte[] ...>

;; Delete file
(storage/delete store "avatars/user-123.jpg")

;; Check existence
(storage/exists? store "avatars/user-123.jpg")
;; => true
```

### S3 Storage

```clojure
(ns myapp.storage
  (:require [wagoe.storage.shell.adapters.s3 :as s3]))

;; Create S3 storage adapter
(def store (s3/create-adapter
  {:bucket "my-app-uploads"
   :region "us-east-1"
   :access-key #env AWS_ACCESS_KEY
   :secret-key #env AWS_SECRET_KEY
   :url-prefix "https://my-app-uploads.s3.amazonaws.com"}))

;; Same API as local storage
(storage/put store "avatars/user-123.jpg" file-bytes
  {:content-type "image/jpeg"
   :acl :public-read})
```

### Configuration

```clojure
;; config.edn
{:wagoe/storage
 #profile
 {:development
  {:adapter :local
   :base-path "uploads"
   :url-prefix "/uploads"}
  
  :production
  {:adapter :s3
   :bucket #env STORAGE_BUCKET
   :region #env AWS_REGION
   :url-prefix #env STORAGE_URL_PREFIX}}}
```

### Upload Validation

```clojure
(ns myapp.uploads
  (:require [wagoe.storage.core.validation :as v]))

;; Define allowed uploads
(def image-rules
  {:allowed-types #{"image/jpeg" "image/png" "image/gif" "image/webp"}
   :max-size (* 5 1024 1024)  ; 5 MB
   :min-size 100})            ; 100 bytes

;; Validate upload
(v/validate-upload file-data image-rules)
;; => {:valid? true} or {:valid? false :error "File too large"}

;; Validate with detailed errors
(v/validate-upload! file-data image-rules)
;; Throws ex-info on validation failure
```

### Path Generation

```clojure
(ns myapp.paths
  (:require [myapp.storage.path :as path]))

;; Date-based paths (good for many files)
(path/generate :date-based {:prefix "uploads" :extension "jpg"})
;; => "uploads/2024/01/15/a1b2c3d4.jpg"

;; UUID-based paths
(path/generate :uuid {:prefix "avatars" :extension "jpg"})
;; => "avatars/550e8400-e29b-41d4-a716-446655440000.jpg"

;; Custom path
(path/generate :custom {:template "users/{user-id}/avatar.{ext}"
                        :user-id "123"
                        :ext "jpg"})
;; => "users/123/avatar.jpg"
```

### Image Processing

```clojure
(ns myapp.images
  (:require [wagoe.storage.ports :as storage-ports]))

;; Resize image (via configured image processor)
(storage-ports/resize-image image-processor file-bytes {:width 200 :height 200})

;; Generate thumbnail
(storage-ports/create-thumbnail image-processor file-bytes 100)

;; Get image metadata
(storage-ports/get-image-info image-processor file-bytes)
;; => {:width 1920 :height 1080}
```

### HTTP Upload Handler

```clojure
(ns myapp.handlers
  (:require [wagoe.storage.ports :as storage-ports]
            [wagoe.storage.core.validation :as v]))

(defn upload-avatar [request]
  (let [file (get-in request [:multipart-params "file"])
        user-id (get-in request [:session :user-id])]
    
    ;; Validate
    (v/validate-upload! file {:allowed-types #{"image/jpeg" "image/png"}
                              :max-size (* 2 1024 1024)})
    
    ;; Store
    (let [path (str "avatars/" user-id ".jpg")
          result (storage-ports/put storage path (:tempfile file))]
      {:status 200
       :body {:url (:url result)}})))
```

## Module Structure

```
libs/storage/src/wagoe/storage/
├── core/
│   ├── validation.clj       # Upload validation (pure)
│   ├── path.clj             # Path generation (pure)
│   └── image.clj            # Image utilities (pure)
├── ports.clj                # Storage protocol
└── shell/
    ├── adapters/
    │   ├── local.clj        # Local filesystem adapter
    │   └── s3.clj           # AWS S3 adapter
    └── module-wiring.clj    # Integrant config
```

## Storage Protocol

```clojure
(defprotocol StorageAdapter
  (put [this path data] [this path data opts]
    "Store data at path, returns {:path :size :url}")
  
  (get [this path]
    "Retrieve data from path, returns bytes or nil")
  
  (delete [this path]
    "Delete data at path, returns true/false")
  
  (exists? [this path]
    "Check if path exists, returns true/false")
  
  (list-files [this prefix]
    "List files with prefix, returns seq of paths")
  
  (url [this path]
    "Get public URL for path"))
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/platform` | 1.0.0-beta-5 | Configuration, database |
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
   {:wagoe/storage
    {:adapter :s3
     :bucket "my-bucket"
     :region "us-east-1"
     :access-key "AKIA..."
     :secret-key "..."}}
   ```

3. **IAM Role** (EC2, ECS, Lambda):
   ```clojure
   {:wagoe/storage
    {:adapter :s3
     :bucket "my-bucket"
     :region "us-east-1"
     :use-instance-credentials? true}}
   ```

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
