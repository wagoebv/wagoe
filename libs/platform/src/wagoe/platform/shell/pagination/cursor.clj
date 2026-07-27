(ns wagoe.platform.shell.pagination.cursor
  "Shell layer cursor encoding/decoding for cursor-based pagination.
   
   SIDE EFFECTS:
   - JSON encoding/decoding
   - Base64 encoding/decoding
   - Exception throwing on invalid cursors
   
   Cursors are opaque tokens that encode:
   - Item ID (for stable ordering)
   - Sort field value (for comparison)
   - Sort direction (asc/desc)
   - Optional timestamp (for expiry)
   
   Format: Base64(JSON({:id ... :sort-value ... :sort-field ... :sort-direction ...}))"
  (:require
   [cheshire.core :as json]
   [clojure.tools.logging :as log])
  (:import
   [java.time Instant]
   [java.util UUID]))

;; =============================================================================
;; Cursor Encoding (Data -> Base64 String)
;; =============================================================================

(defn- prepare-cursor-data
  "Prepare cursor data for JSON encoding.
   
   Converts UUID and Instant to string representations.
   
   Args:
     cursor-data - Map with :id, :sort-value, :sort-field, :sort-direction
     
   Returns:
     Map with string-serializable values
     
   Side Effects:
     None (pure transformation)"
  [cursor-data]
  (-> cursor-data
      (update :id str)  ; UUID -> string
      (update :sort-value (fn [v]
                            (cond
                              (instance? Instant v) (.toString v)
                              (instance? UUID v) (str v)
                              :else v)))
      (update :sort-direction name)  ; :asc -> "asc"
      (cond-> (:timestamp cursor-data)
        (update :timestamp #(.toString ^Instant %)))))

(defn- data->json
  "Convert cursor data to JSON string.
   
   Args:
     cursor-data - Map to encode
     
   Returns:
     JSON string
     
   Side Effects:
     JSON encoding"
  [cursor-data]
  (json/generate-string cursor-data))

(defn- json->base64
  "Encode JSON string to Base64.
   
   Args:
     json-str - JSON string
     
   Returns:
     Base64-encoded string
     
   Side Effects:
     Base64 encoding"
  [json-str]
  (.encodeToString
   (java.util.Base64/getEncoder)
   (.getBytes json-str "UTF-8")))

(defn encode-cursor
  "Encode cursor data to Base64 string.
   
   Takes structured cursor data and returns an opaque Base64-encoded token
   suitable for pagination.
   
   Args:
     cursor-data - Map with keys:
       :id - UUID of the item
       :sort-value - Value of the sort field (any type)
       :sort-field - Name of field used for sorting
       :sort-direction - :asc or :desc
       :timestamp - (optional) Instant when cursor was created
       
   Returns:
     Base64-encoded cursor string (opaque token)
     
   Side Effects:
     - JSON encoding
     - Base64 encoding
     - Logging
     
   Example:
     (encode-cursor
       {:id #uuid \"123e4567-e89b-12d3-a456-426614174000\"
        :sort-value #inst \"2024-01-04T10:00:00Z\"
        :sort-field \"created_at\"
        :sort-direction :desc
        :timestamp #inst \"2024-01-04T12:00:00Z\"})
     ;;=> \"eyJpZCI6IjEyM2U0NTY3LWU4OWItMTJkMy1hNDU2LTQyNjYxNDE3NDAwMCI...\"
     
   Throws:
     ex-info if encoding fails"
  [cursor-data]
  (try
    (log/debug "Encoding cursor" {:cursor-data cursor-data})
    (let [prepared (prepare-cursor-data cursor-data)
          json-str (data->json prepared)
          encoded (json->base64 json-str)]
      (log/debug "Cursor encoded successfully"
                 {:cursor-length (count encoded)})
      encoded)
    (catch Exception e
      (log/error "Failed to encode cursor"
                 {:cursor-data cursor-data
                  :error (.getMessage e)})
      (throw (ex-info "Cursor encoding failed"
                      {:type :cursor-encoding-error
                       :cursor-data cursor-data}
                      e)))))

;; =============================================================================
;; Cursor Decoding (Base64 String -> Data)
;; =============================================================================

(defn- base64->json
  "Decode Base64 string to JSON.
   
   Args:
     encoded-str - Base64-encoded string
     
   Returns:
     JSON string
     
   Side Effects:
     Base64 decoding
     
   Throws:
     ex-info if decoding fails"
  [encoded-str]
  (try
    (String.
     (.decode (java.util.Base64/getDecoder) encoded-str)
     "UTF-8")
    (catch Exception e
      (throw (ex-info "Invalid Base64 cursor"
                      {:type :invalid-cursor
                       :reason :invalid-base64}
                      e)))))

(defn- json->data
  "Parse JSON string to map.
   
   Args:
     json-str - JSON string
     
   Returns:
     Map of cursor data
     
   Side Effects:
     JSON parsing
     
   Throws:
     ex-info if parsing fails"
  [json-str]
  (try
    (json/parse-string json-str keyword)
    (catch Exception e
      (throw (ex-info "Invalid JSON in cursor"
                      {:type :invalid-cursor
                       :reason :invalid-json}
                      e)))))

(defn- parse-cursor-data
  "Parse cursor data from decoded JSON.
   
   Converts string representations back to proper types (UUID, Instant).
   
   Args:
     data - Map from JSON parsing
     
   Returns:
     Map with proper types
     
   Side Effects:
     None (pure transformation)
     
   Throws:
     ex-info if data is invalid"
  [data]
  (try
    (let [{:keys [id sort-value sort-field sort-direction timestamp]} data]
      ;; Validate required fields
      (when-not (and (contains? data :id)
                     (contains? data :sort-value)
                     (contains? data :sort-field)
                     (contains? data :sort-direction))
        (throw (ex-info "Missing required cursor fields"
                        {:type :invalid-cursor
                         :reason :missing-fields
                         :data data})))

      ;; Parse types
      (cond-> {:id (UUID/fromString id)
               :sort-field sort-field
               :sort-direction (keyword sort-direction)}

        ;; Parse sort-value based on type
        (string? sort-value)
        (assoc :sort-value
               ;; Try to parse as Instant if it looks like ISO 8601
               (if (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" sort-value)
                 (try
                   (Instant/parse sort-value)
                   (catch Exception _ sort-value))  ; If parsing fails, keep as string
                 sort-value))

        (not (string? sort-value))
        (assoc :sort-value sort-value)

        ;; Parse timestamp if present
        timestamp
        (assoc :timestamp (Instant/parse timestamp))))
    (catch Exception e
      (if (= :invalid-cursor (:type (ex-data e)))
        (throw e)  ; Re-throw our own exceptions
        (throw (ex-info "Failed to parse cursor data"
                        {:type :invalid-cursor
                         :reason :parse-error
                         :data data}
                        e))))))

(defn decode-cursor
  "Decode Base64 cursor string to cursor data.
   
   Takes an opaque cursor token and returns structured data for pagination.
   
   Args:
     cursor-str - Base64-encoded cursor string
     
   Returns:
     Map with keys:
       :id - UUID of the item
       :sort-value - Value of the sort field
       :sort-field - Name of field used for sorting
       :sort-direction - :asc or :desc
       :timestamp - (optional) Instant when cursor was created
       
   Side Effects:
     - Base64 decoding
     - JSON parsing
     - Logging
     
   Example:
     (decode-cursor \"eyJpZCI6IjEyM2U0NTY3LWU4OWItMTJkMy1hNDU2LTQyNjYxNDE3NDAwMCI...\")
     ;;=> {:id #uuid \"123e4567-e89b-12d3-a456-426614174000\"
     ;;    :sort-value #inst \"2024-01-04T10:00:00Z\"
     ;;    :sort-field \"created_at\"
     ;;    :sort-direction :desc
     ;;    :timestamp #inst \"2024-01-04T12:00:00Z\"}
     
   Throws:
     ex-info with :type :invalid-cursor if:
     - Cursor is not valid Base64
     - Cursor does not contain valid JSON
     - Cursor is missing required fields
     - Cursor contains invalid data types"
  [cursor-str]
  (try
    (log/debug "Decoding cursor" {:cursor-length (count cursor-str)})
    (let [json-str (base64->json cursor-str)
          data (json->data json-str)
          parsed (parse-cursor-data data)]
      (log/debug "Cursor decoded successfully" {:cursor-data parsed})
      parsed)
    (catch Exception e
      (log/warn "Failed to decode cursor"
                {:cursor cursor-str
                 :error-type (:type (ex-data e))
                 :error (.getMessage e)})
      (if (= :invalid-cursor (:type (ex-data e)))
        (throw e)  ; Re-throw our own exceptions
        (throw (ex-info "Cursor decoding failed"
                        {:type :invalid-cursor
                         :reason :unknown}
                        e))))))

;; =============================================================================
;; Cursor Validation
;; =============================================================================

(defn valid-cursor?
  "Check if cursor string is valid without throwing.
   
   Attempts to decode the cursor and returns true if successful.
   
   Args:
     cursor-str - Base64-encoded cursor string
     
   Returns:
     Boolean - true if cursor is valid, false otherwise
     
   Side Effects:
     - Cursor decoding (if successful)
     - Logging
     
   Example:
     (valid-cursor? \"eyJpZCI6IjEyM2U0NTY3...\")
     ;;=> true
     
     (valid-cursor? \"invalid-cursor\")
     ;;=> false"
  [cursor-str]
  (try
    (decode-cursor cursor-str)
    true
    (catch Exception _
      false)))

(defn cursor-expired?
  "Check if cursor has expired based on TTL.
   
   Args:
     cursor-data - Decoded cursor data with :timestamp
     ttl-seconds - Time-to-live in seconds
     
   Returns:
     Boolean - true if cursor is expired, false otherwise
     
   Side Effects:
     - Current time retrieval
     
   Example:
     (cursor-expired?
       {:id #uuid \"...\" :timestamp #inst \"2024-01-04T10:00:00Z\" ...}
       3600)  ; 1 hour TTL
     ;;=> false (if current time is within 1 hour of timestamp)"
  [cursor-data ttl-seconds]
  (if-let [timestamp (:timestamp cursor-data)]
    (let [now (Instant/now)
          expiry (.plusSeconds timestamp ttl-seconds)]
      (not (.isBefore now expiry)))  ; Expired if now >= expiry
    false))  ; No timestamp = never expires

;; =============================================================================
;; Cursor Generation Helpers
;; =============================================================================

(defn create-cursor
  "Create cursor data from an item.
   
   Helper function to extract cursor data from a paginated item.
   
   Args:
     item - Map containing the item data
     sort-field - Keyword of field used for sorting
     sort-direction - :asc or :desc
     include-timestamp? - (optional) Include cursor creation timestamp (default: false)
     
   Returns:
     Cursor data map ready for encoding
     
   Side Effects:
     - Current time retrieval (if include-timestamp? is true)
     
   Example:
     (create-cursor
       {:id #uuid \"123...\" :created-at #inst \"2024-01-04\" :name \"Alice\"}
       :created-at
       :desc
       true)
     ;;=> {:id #uuid \"123...\"
     ;;    :sort-value #inst \"2024-01-04\"
     ;;    :sort-field \"created-at\"
     ;;    :sort-direction :desc
     ;;    :timestamp #inst \"2024-01-04T12:00:00Z\"}"
  ([item sort-field sort-direction]
   (create-cursor item sort-field sort-direction false))
  ([item sort-field sort-direction include-timestamp?]
   (let [base-cursor {:id (:id item)
                      :sort-value (get item sort-field)
                      :sort-field (name sort-field)
                      :sort-direction sort-direction}]
     (if include-timestamp?
       (assoc base-cursor :timestamp (Instant/now))
       base-cursor))))

(comment
  "Example usage:
   
   ;; Create and encode a cursor
   (def cursor-data
     {:id #uuid \"123e4567-e89b-12d3-a456-426614174000\"
      :sort-value #inst \"2024-01-04T10:00:00Z\"
      :sort-field \"created_at\"
      :sort-direction :desc
      :timestamp #inst \"2024-01-04T12:00:00Z\"})
   
   (def encoded (encode-cursor cursor-data))
   ;;=> \"eyJpZCI6IjEyM2U0NTY3LWU4OWItMTJkMy1hNDU2LTQyNjYxNDE3NDAwMCI...\"
   
   ;; Decode cursor
   (def decoded (decode-cursor encoded))
   ;;=> {:id #uuid \"123e4567-e89b-12d3-a456-426614174000\" ...}
   
   ;; Validate cursor
   (valid-cursor? encoded)
   ;;=> true
   
   (valid-cursor? \"invalid\")
   ;;=> false
   
   ;; Check expiry
   (cursor-expired? decoded 3600)
   ;;=> false (if within 1 hour)
   
   ;; Create cursor from item
   (def item {:id #uuid \"123...\" :created-at #inst \"2024-01-04\" :name \"Alice\"})
   (def cursor (create-cursor item :created-at :desc true))
   (encode-cursor cursor)
   ;;=> \"eyJ...\"")
