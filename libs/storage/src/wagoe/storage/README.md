# Boundary Storage Module

File upload and storage functionality for the Boundary framework. Supports local filesystem and cloud storage (S3, S3-compatible services) with image processing capabilities.

## Features

- **Multiple Storage Adapters**
  - Local filesystem storage
  - AWS S3 storage
  - S3-compatible services (MinIO, DigitalOcean Spaces, etc.)

- **File Validation**
  - Size limits
  - Content type validation
  - File extension validation
  - Filename sanitization

- **Image Processing**
  - Image resizing
  - Thumbnail generation
  - Image metadata extraction
  - Format detection

- **Security**
  - Signed URLs for temporary access
  - Public/private file visibility
  - Path traversal prevention
  - Filename sanitization

- **HTTP API**
  - RESTful endpoints for file operations
  - Multipart file upload
  - File download with proper headers
  - URL generation

## Quick Start

### 1. Local Filesystem Storage

```clojure
(require '[boundary.storage.shell.adapters.local :as local]
         '[boundary.storage.shell.service :as service]
         '[boundary.storage.shell.http-handlers :as handlers])

;; Create local storage adapter
(def storage
  (local/create-local-storage
    {:base-path "/var/app/uploads"
     :url-base "https://myapp.com/files"
     :create-directories? true}))

;; Create storage service
(def storage-service
  (service/create-storage-service
    {:storage storage}))

;; Upload a file
(def result
  (service/upload-file storage-service
    {:bytes file-bytes
     :content-type "image/jpeg"}
    {:filename "photo.jpg"
     :visibility :public}
    {:max-size (* 10 1024 1024)  ; 10 MB
     :allowed-types ["image/jpeg" "image/png"]}))

;; Check result
(when (:success result)
  (println "File uploaded:" (:data result)))
```

### 2. S3 Storage

```clojure
(require '[boundary.storage.shell.adapters.s3 :as s3])

;; Create S3 storage adapter
(def storage
  (s3/create-s3-storage
    {:bucket "my-app-files"
     :region "us-east-1"
     :access-key (System/getenv "AWS_ACCESS_KEY")
     :secret-key (System/getenv "AWS_SECRET_KEY")
     :prefix "uploads/"
     :public-read? false}))

;; Create service with image processing
(def image-processor
  (img/create-image-processor {}))

(def storage-service
  (service/create-storage-service
    {:storage storage
     :image-processor image-processor}))

;; Upload image with thumbnail
(def result
  (service/upload-image storage-service
    image-bytes
    {:filename "profile.jpg"}
    {:create-thumbnail true
     :thumbnail-size 200}))

;; Get signed URL (expires in 1 hour)
(def signed-url
  (service/get-file-url storage-service
    (:key (:original result))
    3600))
```

### 3. HTTP API Integration

```clojure
(require '[boundary.storage.shell.http-handlers :as handlers])

;; Add storage routes to your Reitit router
(def app-routes
  (concat
    existing-routes
    (handlers/storage-routes storage-service
      {:base-path "/api/v1/storage"})))
```

## Configuration

### Local Storage Configuration

```clojure
{:adapter :local
 :local {:base-path "/var/app/uploads"
         :url-base "https://myapp.com/files"
         :create-directories? true}
 :default-visibility :private
 :max-file-size (* 10 1024 1024)}  ; 10 MB
```

### S3 Storage Configuration

```clojure
{:adapter :s3
 :s3 {:bucket "my-bucket"
      :region "us-east-1"
      :access-key "AWS_ACCESS_KEY"
      :secret-key "AWS_SECRET_KEY"
      :prefix "uploads/"
      :public-read? false}
 :default-visibility :private
 :max-file-size (* 50 1024 1024)}  ; 50 MB
```

### S3-Compatible Services (MinIO, DigitalOcean Spaces)

```clojure
{:adapter :s3
 :s3 {:bucket "my-bucket"
      :region "us-east-1"
      :endpoint "https://nyc3.digitaloceanspaces.com"  ; Custom endpoint
      :access-key "DO_SPACES_KEY"
      :secret-key "DO_SPACES_SECRET"
      :public-read? true}}
```

## API Endpoints

When using `storage-routes`, the following endpoints are available:

### Upload File

```
POST /api/v1/storage/upload
Content-Type: multipart/form-data

Parameters:
- file: File to upload (required)
- path: Storage path (optional)
- visibility: "public" or "private" (optional)

Query Parameters:
- max-size: Maximum file size in bytes
- allowed-types: Comma-separated MIME types
- allowed-extensions: Comma-separated extensions

Response: 201 Created
{
  "key": "2a/1234567890-abcdef123456.jpg",
  "url": "https://myapp.com/files/2a/1234567890-abcdef123456.jpg",
  "size": 123456,
  "content-type": "image/jpeg",
  "stored-at": "2026-01-05T12:00:00Z"
}
```

### Upload Image

```
POST /api/v1/storage/upload/image
Content-Type: multipart/form-data

Parameters:
- file: Image file to upload (required)
- path: Storage path (optional)
- visibility: "public" or "private" (optional)
- create-thumbnail: "true" to create thumbnail (optional)
- thumbnail-size: Thumbnail max dimension in pixels (optional, default: 200)

Response: 201 Created
{
  "original": {
    "key": "2a/1234567890-abcdef123456.jpg",
    "url": "https://...",
    "size": 123456,
    "content-type": "image/jpeg"
  },
  "thumbnail": {
    "key": "2a/1234567890-thumb-abcdef.jpg",
    "url": "https://...",
    "size": 12345,
    "content-type": "image/jpeg"
  }
}
```

### Download File

```
GET /api/v1/storage/download/:file-key

Response: 200 OK
Content-Type: <file-content-type>
Content-Disposition: attachment; filename="..."

<file bytes>
```

### Delete File

```
DELETE /api/v1/storage/delete/:file-key

Response: 204 No Content
```

### Get File URL

```
GET /api/v1/storage/url/:file-key?expiration=3600

Response: 200 OK
{
  "url": "https://...",
  "expiration-seconds": 3600
}
```

## Validation

### File Size Validation

```clojure
(service/upload-file storage-service
  file-data
  metadata
  {:max-size (* 5 1024 1024)})  ; 5 MB limit
```

### Content Type Validation

```clojure
(service/upload-file storage-service
  file-data
  metadata
  {:allowed-types ["image/jpeg" "image/png" "image/gif"]})
```

### File Extension Validation

```clojure
(service/upload-file storage-service
  file-data
  metadata
  {:allowed-extensions ["jpg" "jpeg" "png" "gif"]})
```

### Combined Validation

```clojure
(service/upload-file storage-service
  file-data
  metadata
  {:max-size (* 10 1024 1024)
   :allowed-types ["image/jpeg" "image/png"]
   :allowed-extensions ["jpg" "jpeg" "png"]})
```

## Image Processing

### Resize Image

```clojure
(require '[boundary.storage.shell.adapters.image-processor :as img]
         '[boundary.storage.ports :as ports])

(def processor (img/create-image-processor {}))

;; Resize to specific dimensions
(def resized-bytes
  (ports/resize-image processor
    original-bytes
    {:width 800 :height 600}))

;; Resize with proportional height
(def resized-bytes
  (ports/resize-image processor
    original-bytes
    {:width 800}))
```

### Create Thumbnail

```clojure
(def thumbnail-bytes
  (ports/create-thumbnail processor
    original-bytes
    200))  ; Max dimension 200px
```

### Get Image Info

```clojure
(def info
  (ports/get-image-info processor original-bytes))

;; {:width 1920
;;  :height 1080
;;  :format "jpg"
;;  :size 245678}
```

## Security Best Practices

### 1. Use Environment Variables for Credentials

```clojure
{:s3 {:bucket "my-bucket"
      :region "us-east-1"
      :access-key (System/getenv "AWS_ACCESS_KEY")
      :secret-key (System/getenv "AWS_SECRET_KEY")}}
```

### 2. Validate File Types

Always validate file types to prevent malicious uploads:

```clojure
{:allowed-types ["image/jpeg" "image/png" "application/pdf"]
 :allowed-extensions ["jpg" "jpeg" "png" "pdf"]}
```

### 3. Set Size Limits

Prevent large file uploads that could exhaust resources:

```clojure
{:max-size (* 10 1024 1024)}  ; 10 MB
```

### 4. Use Private Visibility by Default

Only make files public when necessary:

```clojure
{:visibility :private}  ; Use signed URLs for access
```

### 5. Implement Rate Limiting

Add rate limiting to upload endpoints to prevent abuse (use Boundary's rate limiting middleware).

## Testing

Run the test suite:

```bash
clojure -M:test -m kaocha.runner --focus boundary.storage
```

Run specific test namespaces:

```bash
clojure -M:test -m kaocha.runner --focus boundary.storage.core.validation-test
clojure -M:test -m kaocha.runner --focus boundary.storage.shell.adapters.local-test
clojure -M:test -m kaocha.runner --focus boundary.storage.shell.service-test
```

## Architecture

The storage module follows Boundary's Functional Core / Imperative Shell pattern:

### Functional Core (Pure Functions)
- `boundary.storage.core.validation` - File validation logic
- `boundary.storage.schema` - Malli schemas

### Imperative Shell (Side Effects)
- `boundary.storage.shell.adapters.local` - Local filesystem operations
- `boundary.storage.shell.adapters.s3` - S3 API calls
- `boundary.storage.shell.adapters.image-processor` - Image manipulation
- `boundary.storage.shell.service` - Service orchestration
- `boundary.storage.shell.http-handlers` - HTTP request/response handling

### Ports (Interfaces)
- `boundary.storage.ports/IFileStorage` - Storage operations contract
- `boundary.storage.ports/IImageProcessor` - Image processing contract

## Extending

### Create Custom Storage Adapter

Implement the `IFileStorage` protocol:

```clojure
(ns my-app.storage.custom
  (:require [boundary.storage.ports :as ports]))

(defrecord CustomStorage [config]
  ports/IFileStorage

  (store-file [this file-data metadata]
    ;; Implementation
    )

  (retrieve-file [this file-key]
    ;; Implementation
    )

  (delete-file [this file-key]
    ;; Implementation
    )

  (file-exists? [this file-key]
    ;; Implementation
    )

  (generate-signed-url [this file-key expiration-seconds]
    ;; Implementation
    ))

(defn create-custom-storage [config]
  (->CustomStorage config))
```

## Troubleshooting

### Large File Uploads Failing

Increase Ring's multipart configuration:

```clojure
{:ring {:multipart {:max-file-size (* 100 1024 1024)}}}  ; 100 MB
```

### S3 Access Denied

Ensure IAM permissions include:
- `s3:PutObject`
- `s3:GetObject`
- `s3:DeleteObject`
- `s3:ListBucket`

### Image Processing OutOfMemoryError

Increase JVM heap size:

```bash
clojure -J-Xmx2g -M:dev  # 2 GB heap
```

## License

Same as Boundary Framework
