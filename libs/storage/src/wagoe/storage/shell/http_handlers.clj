(ns wagoe.storage.shell.http-handlers
  "HTTP handlers for file upload and download operations.

  Provides Ring-compatible handlers for file operations."
  (:require [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.core.uploads :as uploads]
            [wagoe.core.http.access :as access]
            [wagoe.core.http.errors :as errors]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream]))

;; Map bodies the router encodes, so storage needs no JSON library and no
;; platform problem-details (BOU-930). No Content-Type on any of them:
;; muuntaja encodes a map body only when none is set (ZZP-120).
(defn- error-response [status type message details]
  (errors/response status type message {:details details}))

(defn- bad-request
  ([message] (bad-request message nil))
  ([message details] (error-response 400 :validation-error message details)))

(defn- forbidden [message] (error-response 403 :forbidden message nil))

(defn- not-found [message details] (error-response 404 :not-found message details))

;; ============================================================================
;; Parsing Helpers
;; ============================================================================

(defn- repeated-params
  "Names in `m` whose value is not a single scalar.

   Ring gives a repeated query or form parameter as a vector, and every
   consumer here — `parse-int-safe`, `keyword`, `str/split` — throws on one,
   turning `?expiration=1&expiration=2` into a 500. A repeated parameter is
   also genuinely ambiguous: silently taking one of the two would weaken an
   upload constraint the caller asked for. So it is refused, by name
   (BOU-421)."
  [m ks]
  (seq (for [k ks
             :let [v (get m k)]
             :when (and (some? v) (not (string? v)))]
         k)))

(defn- multipart-file?
  "Whether `v` is one uploaded file part rather than something else Ring can
   put under the same name: a vector when the field repeats, a string when it
   arrives as an ordinary text part. Both were truthy, so the missing-field
   guard passed them through and extraction threw a 500 (BOU-421)."
  [v]
  (and (map? v)
       (or (instance? java.io.File (:tempfile v))
           (bytes? (:bytes v))
           (string? (:content v)))))

(defn- repeated-params-response
  [names]
  (bad-request
   (str "Repeated parameter(s): " (str/join ", " names))
   {:repeated-parameters (vec names)}))

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

(defn- request-owner
  "Who an upload belongs to, for the upload index: the signed-in user, and the
   request's tenant when the caller is in it (BOU-982)."
  [request]
  {:user-id   (get-in request [:user :id])
   :tenant-id (when (access/in-tenant-scope? request) (get-in request [:tenant :id]))})

(defn- reachable?
  "`uploads/may-access?` for the request's caller (BOU-945); an admin needs no lookup."
  [storage-service request file-key]
  (let [caller (assoc (request-owner request) :admin? (access/platform-admin? (:user request)))]
    (or (:admin? caller)
        (uploads/may-access? (service/upload-at storage-service file-key) caller))))

(defn- upload-refused
  "A 403 when the service refused to replace another caller's file (BOU-950)."
  [result]
  (when (some #(= :forbidden (:code %)) (:errors result))
    (forbidden "That path holds a file you may not replace")))

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
  (fn [{:keys [multipart-params query-params] :as request}]
    (if-let [bad (or (repeated-params multipart-params ["path" "visibility"])
                     (repeated-params query-params
                                      ["max-size" "allowed-types" "allowed-extensions"]))]
      (repeated-params-response bad)
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

        (if-not (multipart-file? file)
          (bad-request
           (if file
             "Field 'file' must be an uploaded file"
             "Missing required field: file")
           {:missing-field "file"})

          (let [file-data (extract-file-data file)
                metadata {:filename (:filename file-data)
                          :path path
                          :visibility visibility
                          :owner (request-owner request)}
                options {:max-size max-size
                         :allowed-types allowed-types
                         :allowed-extensions allowed-extensions
                         :may-replace? #(reachable? storage-service request %)}

                result (service/upload-file storage-service file-data metadata options)]

            (cond
              (:success result)       {:status 201 :body (:data result)}
              (upload-refused result) (upload-refused result)
              :else                   (bad-request "File validation failed" {:errors (:errors result)}))))))))

(defn upload-image-handler
  "Handler for image upload endpoint with processing options.

  Expects multipart/form-data with:
  - file: The image file to upload (required)
  - path: Storage path (optional)
  - visibility: 'public' or 'private' (optional)
  - create-thumbnail: 'true' to create thumbnail (optional)
  - thumbnail-size: Thumbnail max dimension in pixels (optional, default: 200)"
  [storage-service]
  (fn [{:keys [multipart-params] :as request}]
    (if-let [bad (repeated-params multipart-params
                                  ["path" "visibility" "create-thumbnail" "thumbnail-size"])]
      (repeated-params-response bad)
      (let [file (get multipart-params "file")
            path (get multipart-params "path")
            visibility-str (get multipart-params "visibility")
            visibility (when visibility-str (keyword visibility-str))
            create-thumbnail (= "true" (get multipart-params "create-thumbnail"))
            thumbnail-size (when-let [size (get multipart-params "thumbnail-size")]
                             (parse-int-safe size))]

        (if-not (multipart-file? file)
          (bad-request
           (if file
             "Field 'file' must be an uploaded file"
             "Missing required field: file")
           {:missing-field "file"})

          (let [file-data (extract-file-data file)
                metadata {:filename (:filename file-data)
                          :path path
                          :visibility visibility
                          :owner (request-owner request)}
                options {:create-thumbnail create-thumbnail
                         :thumbnail-size thumbnail-size
                         :may-replace? #(reachable? storage-service request %)}

                result (service/upload-image storage-service
                                             (:bytes file-data)
                                             metadata
                                             options)]

            (cond
              (:success result)       {:status 201 :body (dissoc result :success)}
              (upload-refused result) (upload-refused result)
              :else                   (bad-request "Image upload failed" {:errors (:errors result)}))))))))

(defn download-file-handler
  "Handler for file download endpoint.

  Path parameter:
  - file-key: Storage key of the file"
  ([storage-service] (download-file-handler storage-service nil))
  ([storage-service signing-secret]
   (fn [{:keys [path-params query-params] :as request}]
     (let [file-key (let [k (get path-params :file-key)] (when-not (str/blank? k) k))]

       (cond
         (not file-key)
         (bad-request
          "Missing required parameter: file-key"
          {:missing-parameter "file-key"})

        ;; A configured signing secret means the URLs this module hands out are
        ;; signed and expiring — so the route that serves them must say no to
        ;; an unsigned or stale request, or the signature is decoration
        ;; (libs/storage/AGENTS.md; BOU-421).
         (and signing-secret
              (not (local/verify-signed-url
                    signing-secret file-key
                    {:expires   (get query-params "expires")
                     :signature (get query-params "signature")})))
         (forbidden "Missing, invalid or expired signature")
         (and (not signing-secret) (not (reachable? storage-service request file-key)))
         (not-found "File not found" {:file-key file-key})

         :else

         (if-let [file-data (service/download-file storage-service file-key)]
           {:status 200
            :headers {"Content-Type" (:content-type file-data)
                      "Content-Length" (str (:size file-data))
                      "Content-Disposition" (str "attachment; filename=\"" file-key "\"")}
            :body (io/input-stream (:bytes file-data))}

           (not-found
            "File not found"
            {:file-key file-key})))))))

(defn delete-file-handler
  "Handler for file deletion endpoint.

  Path parameter:
  - file-key: Storage key of the file"
  [storage-service]
  (fn [{:keys [path-params] :as request}]
    (let [file-key (let [k (get path-params :file-key)] (when-not (str/blank? k) k))]

      (if-not file-key
        (bad-request
         "Missing required parameter: file-key"
         {:missing-parameter "file-key"})

        (if (and (reachable? storage-service request file-key)
                 (service/remove-file storage-service file-key))
          {:status 204
           :body nil}

          (not-found
           "File not found or deletion failed"
           {:file-key file-key}))))))

(defn get-file-url-handler
  "Handler for getting a file URL (direct or signed).

  Path parameter:
  - file-key: Storage key of the file

  Query parameter:
  - expiration: Expiration time in seconds (optional, default: 3600)"
  [storage-service]
  (fn [{:keys [path-params query-params] :as request}]
    (if-let [bad (repeated-params query-params ["expiration"])]
      (repeated-params-response bad)
      (let [file-key (let [k (get path-params :file-key)] (when-not (str/blank? k) k))
            expiration (if-let [exp (get query-params "expiration")]
                         (parse-int-safe exp)
                         3600)]

        (if-not file-key
          (bad-request
           "Missing required parameter: file-key"
           {:missing-parameter "file-key"})

          (if-let [url (and (reachable? storage-service request file-key)
                            (service/get-file-url storage-service file-key expiration))]
            {:status 200
             :body {:url url
                    :expiration-seconds expiration}}

            (not-found
             "File not found or URL generation failed"
             {:file-key file-key})))))))

;; ============================================================================
;; Route Definitions
;; ============================================================================

(def ^:private file-key-openapi
  {:parameters [{:name "file-key" :in "path" :required true :schema {:type "string"}
                 :description "Storage key of the file"}]})

(defn storage-routes
  "Reitit route data for the storage endpoints, as this module's `:api`.

  Versioning prepends `/api/v1`, so paths here carry no `/api` prefix.

  Parameters:
  - storage-service: Instance of IStorageService
  - options: Map with optional :base-path (default: \"/storage\")"
  ([storage-service] (storage-routes storage-service {}))
  ([storage-service {:keys [base-path signing-secret]}]
   (let [base-path (or base-path "/storage")]
   ;; `{*file-key}` catches the whole key, slashes included: the local adapter
   ;; sharded every key it hands back (`2a/photo.jpg`), so a single-segment
   ;; `:file-key` could never match the key it had just returned (BOU-421).
     [[(str base-path "/upload")
       {:post {:handler     (upload-file-handler storage-service)
               :summary     "Upload a file"
               :description "Upload a file with validation (max-size, allowed-types, allowed-extensions)."}}]

      [(str base-path "/upload/image")
       {:post {:handler     (upload-image-handler storage-service)
               :summary     "Upload an image with optional processing"
               :description "Upload an image and optionally create a thumbnail."}}]

      [(str base-path "/download/{*file-key}")
     ;; With a signing secret the signature is the credential, so a signed link
     ;; works without a session. Without one this stays behind login (BOU-568).
       {:get (cond-> {:handler (download-file-handler storage-service signing-secret)
                      :summary "Download a file"
                      :openapi file-key-openapi}
               signing-secret (assoc :public true))}]

      [(str base-path "/delete/{*file-key}")
       {:delete {:handler (delete-file-handler storage-service)
                 :summary "Delete a file"
                 :openapi file-key-openapi}}]

      [(str base-path "/url/{*file-key}")
       {:get {:handler (get-file-url-handler storage-service)
              :summary "Get a direct or signed URL for a file"
              :openapi {:parameters
                        [{:name "file-key" :in "path" :required true :schema {:type "string"}
                          :description "Storage key of the file"}
                         {:name "expiration" :in "query" :required false :schema {:type "integer"}
                          :description "Signed-URL lifetime in seconds (default 3600)"}]}}}]])))
