(ns wagoe.storage.core.validation
  "Pure functional file validation logic.

  This namespace contains no side effects - only pure functions for validating
  files based on size, type, extension, etc."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def default-max-file-size
  "Default maximum file size in bytes (10 MB)."
  (* 10 1024 1024))

(def common-mime-types
  "Common MIME type mappings for file extensions."
  {"jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "png"  "image/png"
   "gif"  "image/gif"
   "webp" "image/webp"
   "svg"  "image/svg+xml"
   "pdf"  "application/pdf"
   "txt"  "text/plain"
   "csv"  "text/csv"
   "json" "application/json"
   "xml"  "application/xml"
   "zip"  "application/zip"
   "doc"  "application/msword"
   "docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   "xls"  "application/vnd.ms-excel"
   "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})

(def image-mime-types
  "Set of image MIME types."
  #{"image/jpeg"
    "image/png"
    "image/gif"
    "image/webp"
    "image/svg+xml"
    "image/bmp"
    "image/tiff"})

;; ============================================================================
;; Validation Result Helpers
;; ============================================================================

(defn validation-success
  "Create a successful validation result."
  ([]
   {:valid? true})
  ([data]
   {:valid? true
    :data   data}))

(defn validation-failure
  "Create a failed validation result with error details."
  ([error-message]
   {:valid? false
    :errors [{:message error-message}]})
  ([error-code error-message]
   {:valid? false
    :errors [{:code    error-code
              :message error-message}]})
  ([error-code error-message details]
   {:valid? false
    :errors [{:code    error-code
              :message error-message
              :details details}]}))

;; ============================================================================
;; File Extension Helpers
;; ============================================================================

(defn get-file-extension
  "Extract file extension from filename.

  Returns lowercase extension without the dot, or nil if no extension."
  [filename]
  (when filename
    (when-let [dot-index (str/last-index-of filename ".")]
      (-> filename
          (subs (inc dot-index))
          str/lower-case))))

(defn mime-type-from-extension
  "Get MIME type from file extension."
  [filename]
  (when-let [ext (get-file-extension filename)]
    (get common-mime-types ext)))

(defn extension-matches-mime-type?
  "Check if file extension matches the declared MIME type."
  [filename content-type]
  (when-let [expected-type (mime-type-from-extension filename)]
    (= expected-type content-type)))

;; ============================================================================
;; Size Validation
;; ============================================================================

(defn validate-file-size
  "Validate file size against maximum allowed size.

  Returns validation result map."
  [file-size max-size]
  (let [limit (or max-size default-max-file-size)]
    (if (<= file-size limit)
      (validation-success)
      (validation-failure
       :file-too-large
       (format "File size %d bytes exceeds maximum allowed size of %d bytes"
               file-size limit)
       {:file-size file-size
        :max-size  limit}))))

;; ============================================================================
;; Type Validation
;; ============================================================================

(defn validate-content-type
  "Validate content type against allowed types.

  Returns validation result map."
  [content-type allowed-types]
  (if (or (nil? allowed-types)
          (empty? allowed-types)
          (contains? (set allowed-types) content-type))
    (validation-success)
    (validation-failure
     :invalid-content-type
     (format "Content type '%s' is not allowed" content-type)
     {:content-type   content-type
      :allowed-types  allowed-types})))

(defn validate-extension
  "Validate file extension against allowed extensions.

  Returns validation result map."
  [filename allowed-extensions]
  (let [ext (get-file-extension filename)]
    (if (or (nil? allowed-extensions)
            (empty? allowed-extensions)
            (and ext (contains? (set allowed-extensions) ext)))
      (validation-success)
      (validation-failure
       :invalid-extension
       (format "File extension '%s' is not allowed" (or ext "none"))
       {:extension          ext
        :allowed-extensions allowed-extensions}))))

;; ============================================================================
;; Image Validation
;; ============================================================================

(defn is-image-mime-type?
  "Check if content type is an image MIME type."
  [content-type]
  (contains? image-mime-types content-type))

(defn validate-image-type
  "Validate that the file is an image based on MIME type.

  Returns validation result map."
  [content-type]
  (if (is-image-mime-type? content-type)
    (validation-success)
    (validation-failure
     :not-an-image
     (format "Content type '%s' is not a valid image type" content-type)
     {:content-type content-type})))

;; ============================================================================
;; Composite Validation
;; ============================================================================

(defn validate-file
  "Perform comprehensive file validation.

  Parameters:
  - file-data: Map with :bytes, :content-type, :size
  - file-metadata: Map with :filename
  - options: Map with :max-size, :allowed-types, :allowed-extensions

  Returns validation result map with all errors if any."
  [{:keys [bytes content-type size] :as _file-data}
   {:keys [filename] :as _file-metadata}
   {:keys [max-size allowed-types allowed-extensions] :as _options}]
  (let [file-size (or size (alength bytes))
        validations [(validate-file-size file-size max-size)
                     (validate-content-type content-type allowed-types)
                     (validate-extension filename allowed-extensions)]
        errors      (mapcat :errors (filter (comp not :valid?) validations))]
    (if (empty? errors)
      (validation-success {:filename     filename
                           :content-type content-type
                           :size         file-size})
      {:valid? false
       :errors errors})))

(defn validate-image-file
  "Validate a file as an image with additional image-specific checks.

  Returns validation result map."
  [file-data file-metadata options]
  (let [base-validation (validate-file file-data file-metadata options)
        image-validation (validate-image-type (:content-type file-data))]
    (if (:valid? base-validation)
      image-validation
      (if (:valid? image-validation)
        base-validation
        {:valid? false
         :errors (concat (:errors base-validation)
                         (:errors image-validation))}))))

;; ============================================================================
;; Sanitization
;; ============================================================================

(defn sanitize-filename
  "Sanitize filename to remove potentially dangerous characters.

  - Removes path separators
  - Removes special characters
  - Limits length
  - Preserves extension"
  [filename]
  (when filename
    (let [max-length 255
          ;; Remove path separators and dangerous characters
          safe-name (-> filename
                        (str/replace #"\.\." "")       ; Remove ".."
                        (str/replace #"[/\\]" "")      ; Remove path separators
                        (str/replace #"[^\w.]" "")     ; Keep only word chars and dots
                        (str/trim))
          ;; Limit length while preserving extension
          ext (get-file-extension safe-name)
          base (if ext
                 (subs safe-name 0 (- (count safe-name) (count ext) 1))
                 safe-name)
          max-base-length (if ext
                            (- max-length (count ext) 1)
                            max-length)
          truncated-base (if (> (count base) max-base-length)
                           (subs base 0 max-base-length)
                           base)]
      (if ext
        (str truncated-base "." ext)
        truncated-base))))

(defn generate-unique-filename*
  "Generate a unique filename using explicit shell-supplied components.

  Preserves the original extension."
  [original-filename unique-suffix]
  (let [ext (get-file-extension original-filename)
        base unique-suffix]
    (if ext
      (str base "." ext)
      base)))
