(ns wagoe.storage.shell.http-handlers
  "HTTP handlers for file upload and download operations.

  Provides Ring-compatible handlers for file operations."
  (:require [wagoe.storage.shell.service :as service]
            [wagoe.platform.core.http.problem-details :as problem-details]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream]))

;; ============================================================================
;; Parsing Helpers
;; ============================================================================

(defn- parse-int-safe
  "Parse string to int, returning nil on invalid input."
  [^String s]
  (try (Integer/parseInt s) (catch NumberFormatException _ nil)))

;; ============================================================================
;; Multipart File Helpers
;; ============================================================================

(defn- multipart-file->bytes
  "Convert a Ring multipart file to byte array."
  [multipart-file]
  (cond
    ;; File is a java.io.File
    (instance? java.io.File (:tempfile multipart-file))
    (with-open [input (io/input-stream (:tempfile multipart-file))
                output (ByteArrayOutputStream.)]
      (.transferTo input output)
      (.toByteArray output))

    ;; File is already a byte array
    (bytes? (:bytes multipart-file))
    (:bytes multipart-file)

    ;; File has content that can be read
    (:content multipart-file)
    (let [content (:content multipart-file)]
      (if (bytes? content)
        content
        (with-open [input (io/input-stream content)
                    output (ByteArrayOutputStream.)]
          (.transferTo input output)
          (.toByteArray output))))

    :else
    (throw (ex-info "Unable to extract bytes from multipart file"
                    {:type :validation-error
                     :multipart-file (dissoc multipart-file :tempfile :content)}))))

(defn- extract-file-data
  "Extract file data from Ring multipart request."
  [multipart-file]
  (let [bytes (multipart-file->bytes multipart-file)
        content-type (or (:content-type multipart-file) "application/octet-stream")
        filename (:filename multipart-file)]
    {:bytes bytes
     :content-type content-type
     :size (alength bytes)
     :filename filename}))

;; ============================================================================
;; HTTP Handlers
;; ============================================================================

(defn upload-file-handler
  "Handler for file upload endpoint.

  Expects multipart/form-data with:
  - file: The file to upload (required)
  - path: Storage path (optional)
  - visibility: 'public' or 'private' (optional, default: private)

  Query parameters:
  - max-size: Maximum file size in bytes
  - allowed-types: Comma-separated list of allowed MIME types
  - allowed-extensions: Comma-separated list of allowed extensions"
  [storage-service]
  (fn [{:keys [multipart-params query-params]}]
    (let [file (get multipart-params "file")
          path (get multipart-params "path")
          visibility-str (get multipart-params "visibility")
          visibility (when visibility-str
                       (keyword visibility-str))

          ;; Extract validation options from query params
          max-size (when-let [ms (get query-params "max-size")]
                     (parse-int-safe ms))
          allowed-types (when-let [types (get query-params "allowed-types")]
                          (str/split types #","))
          allowed-extensions (when-let [exts (get query-params "allowed-extensions")]
                               (str/split exts #","))]

      (if-not file
        (problem-details/bad-request
         "Missing required field: file"
         {:missing-field "file"})

        (try
          (let [file-data (extract-file-data file)
                metadata {:filename (:filename file-data)
                          :path path
                          :visibility visibility}
                options {:max-size max-size
                         :allowed-types allowed-types
                         :allowed-extensions allowed-extensions}

                result (service/upload-file storage-service file-data metadata options)]

            (if (:success result)
              {:status 201
               :headers {"Content-Type" "application/json"}
               :body (:data result)}

              (problem-details/bad-request
               "File validation failed"
               {:errors (:errors result)})))

          (catch Exception e
            (problem-details/internal-server-error
             "Failed to upload file"
             {:error (.getMessage e)})))))))

(defn upload-image-handler
  "Handler for image upload endpoint with processing options.

  Expects multipart/form-data with:
  - file: The image file to upload (required)
  - path: Storage path (optional)
  - visibility: 'public' or 'private' (optional)
  - create-thumbnail: 'true' to create thumbnail (optional)
  - thumbnail-size: Thumbnail max dimension in pixels (optional, default: 200)"
  [storage-service]
  (fn [{:keys [multipart-params]}]
    (let [file (get multipart-params "file")
          path (get multipart-params "path")
          visibility-str (get multipart-params "visibility")
          visibility (when visibility-str (keyword visibility-str))
          create-thumbnail (= "true" (get multipart-params "create-thumbnail"))
          thumbnail-size (when-let [size (get multipart-params "thumbnail-size")]
                           (parse-int-safe size))]

      (if-not file
        (problem-details/bad-request
         "Missing required field: file"
         {:missing-field "file"})

        (try
          (let [file-data (extract-file-data file)
                metadata {:filename (:filename file-data)
                          :path path
                          :visibility visibility}
                options {:create-thumbnail create-thumbnail
                         :thumbnail-size thumbnail-size}

                result (service/upload-image storage-service
                                             (:bytes file-data)
                                             metadata
                                             options)]

            (if (:success result)
              {:status 201
               :headers {"Content-Type" "application/json"}
               :body (dissoc result :success)}

              (problem-details/bad-request
               "Image upload failed"
               {:errors (:errors result)})))

          (catch Exception e
            (problem-details/internal-server-error
             "Failed to upload image"
             {:error (.getMessage e)})))))))

(defn download-file-handler
  "Handler for file download endpoint.

  Path parameter:
  - file-key: Storage key of the file"
  [storage-service]
  (fn [{:keys [path-params]}]
    (let [file-key (get path-params :file-key)]

      (if-not file-key
        (problem-details/bad-request
         "Missing required parameter: file-key"
         {:missing-parameter "file-key"})

        (if-let [file-data (service/download-file storage-service file-key)]
          {:status 200
           :headers {"Content-Type" (:content-type file-data)
                     "Content-Length" (str (:size file-data))
                     "Content-Disposition" (str "attachment; filename=\"" file-key "\"")}
           :body (io/input-stream (:bytes file-data))}

          (problem-details/not-found
           "File not found"
           {:file-key file-key}))))))

(defn delete-file-handler
  "Handler for file deletion endpoint.

  Path parameter:
  - file-key: Storage key of the file"
  [storage-service]
  (fn [{:keys [path-params]}]
    (let [file-key (get path-params :file-key)]

      (if-not file-key
        (problem-details/bad-request
         "Missing required parameter: file-key"
         {:missing-parameter "file-key"})

        (if (service/remove-file storage-service file-key)
          {:status 204
           :body nil}

          (problem-details/not-found
           "File not found or deletion failed"
           {:file-key file-key}))))))

(defn get-file-url-handler
  "Handler for getting a file URL (direct or signed).

  Path parameter:
  - file-key: Storage key of the file

  Query parameter:
  - expiration: Expiration time in seconds (optional, default: 3600)"
  [storage-service]
  (fn [{:keys [path-params query-params]}]
    (let [file-key (get path-params :file-key)
          expiration (if-let [exp (get query-params "expiration")]
                       (parse-int-safe exp)
                       3600)]

      (if-not file-key
        (problem-details/bad-request
         "Missing required parameter: file-key"
         {:missing-parameter "file-key"})

        (if-let [url (service/get-file-url storage-service file-key expiration)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body {:url url
                  :expiration-seconds expiration}}

          (problem-details/not-found
           "File not found or URL generation failed"
           {:file-key file-key}))))))

;; ============================================================================
;; Route Definitions
;; ============================================================================

(def ^:private file-key-swagger
  {:parameters [{:name "file-key" :in "path" :required true :type "string"
                 :description "Storage key of the file"}]})

(defn storage-routes
  "Reitit route data for the storage endpoints, as this module's `:api`.

  Versioning prepends `/api/v1`, so paths here carry no `/api` prefix.

  Parameters:
  - storage-service: Instance of IStorageService
  - options: Map with optional :base-path (default: \"/storage\")"
  ([storage-service] (storage-routes storage-service {}))
  ([storage-service {:keys [base-path] :or {base-path "/storage"}}]
   [[(str base-path "/upload")
     {:post {:handler     (upload-file-handler storage-service)
             :summary     "Upload a file"
             :description "Upload a file with validation (max-size, allowed-types, allowed-extensions)."}}]

    [(str base-path "/upload/image")
     {:post {:handler     (upload-image-handler storage-service)
             :summary     "Upload an image with optional processing"
             :description "Upload an image and optionally create a thumbnail."}}]

    [(str base-path "/download/:file-key")
     {:get {:handler (download-file-handler storage-service)
            :summary "Download a file"
            :swagger file-key-swagger}}]

    [(str base-path "/delete/:file-key")
     {:delete {:handler (delete-file-handler storage-service)
               :summary "Delete a file"
               :swagger file-key-swagger}}]

    [(str base-path "/url/:file-key")
     {:get {:handler (get-file-url-handler storage-service)
            :summary "Get a direct or signed URL for a file"
            :swagger {:parameters
                      [{:name "file-key" :in "path" :required true :type "string"
                        :description "Storage key of the file"}
                       {:name "expiration" :in "query" :required false :type "integer"
                        :description "Signed-URL lifetime in seconds (default 3600)"}]}}}]]))
