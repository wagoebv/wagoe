(ns wagoe.platform.schema
  "Malli schemas for platform-level concerns: pagination, versioning, API metadata.
   
   These schemas are used across all modules for consistent API responses."
  (:require
   [malli.core :as m]))

;; =============================================================================
;; Pagination Query Parameter Schemas
;; =============================================================================

(def PaginationParams
  "Schema for pagination query parameters.
   
   Used for validating incoming pagination requests.
   All parameters are optional with sensible defaults."
  [:map {:title "Pagination Parameters"}
   [:limit {:optional true} [:int {:min 1 :max 100}]]  ; Default: 20, Max: 100
   [:offset {:optional true} [:int {:min 0}]]          ; Default: 0
   [:cursor {:optional true} [:string {:min 1}]]       ; Base64-encoded cursor
   [:sort {:optional true} [:string {:min 1}]]         ; e.g., "name", "-created_at"
   [:type {:optional true} [:enum :offset :cursor]]])  ; Pagination type

(def OffsetPaginationParams
  "Schema for offset-based pagination parameters."
  [:map {:title "Offset Pagination Parameters"}
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0}]]
   [:sort {:optional true} [:string {:min 1}]]])

(def CursorPaginationParams
  "Schema for cursor-based pagination parameters."
  [:map {:title "Cursor Pagination Parameters"}
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:cursor {:optional true} [:string {:min 1}]]
   [:sort {:optional true} [:string {:min 1}]]])

(def SortParam
  "Schema for sort parameter.
   
   Format: 'field' for ascending, '-field' for descending.
   Examples: 'name', '-created_at', 'email'"
  [:string {:min 1 :max 100}])

;; =============================================================================
;; Pagination Response Metadata Schemas
;; =============================================================================

(def OffsetPaginationMeta
  "Schema for offset-based pagination metadata in API responses.
   
   Includes total count, offset, limit, and navigation flags."
  [:map {:title "Offset Pagination Metadata"}
   [:type [:enum "offset"]]           ; Pagination type identifier
   [:total [:int {:min 0}]]           ; Total number of items
   [:offset [:int {:min 0}]]          ; Current offset
   [:limit [:int {:min 1 :max 100}]]  ; Items per page
   [:has-next :boolean]               ; True if next page exists
   [:has-prev :boolean]               ; True if previous page exists
   [:total-pages [:int {:min 0}]]     ; Total number of pages
   [:current-page [:int {:min 1}]]    ; Current page number (1-indexed)
   [:next-offset {:optional true} [:maybe [:int {:min 0}]]]  ; Offset for next page
   [:prev-offset {:optional true} [:maybe [:int {:min 0}]]]]) ; Offset for previous page

(def CursorPaginationMeta
  "Schema for cursor-based pagination metadata in API responses.
   
   Uses opaque cursors instead of offsets for stable pagination."
  [:map {:title "Cursor Pagination Metadata"}
   [:type [:enum "cursor"]]           ; Pagination type identifier
   [:limit [:int {:min 1 :max 100}]]  ; Items per page
   [:has-next :boolean]               ; True if next page exists
   [:has-prev :boolean]               ; True if previous page exists
   [:next-cursor {:optional true} [:maybe :string]]  ; Cursor for next page
   [:prev-cursor {:optional true} [:maybe :string]]]) ; Cursor for previous page

(def PaginationMeta
  "Union schema for pagination metadata (either offset or cursor)."
  [:or {:title "Pagination Metadata"}
   OffsetPaginationMeta
   CursorPaginationMeta])

;; =============================================================================
;; Cursor Schemas
;; =============================================================================

(def CursorData
  "Schema for decoded cursor data (before Base64 encoding).
   
   Contains enough information to resume pagination from a specific point."
  [:map {:title "Cursor Data"}
   [:id :uuid]                               ; Unique identifier of the item
   [:sort-value :any]                        ; Value of the sort field (string, int, inst, etc.)
   [:sort-field [:string {:min 1}]]          ; Name of the field used for sorting
   [:sort-direction [:enum :asc :desc]]      ; Sort direction
   [:timestamp {:optional true} inst?]])     ; Cursor creation timestamp (for expiry)

(def EncodedCursor
  "Schema for Base64-encoded cursor string."
  [:string {:min 1 :max 500}])  ; Max 500 chars for encoded cursor

;; =============================================================================
;; API Versioning Schemas
;; =============================================================================

(def VersionString
  "Schema for API version string.
   
   Supports formats: v1, v1.2, v1.2.3, v0 (experimental)"
  [:re {:error/message "Invalid version format (expected v1, v1.2, or v1.2.3)"}
   #"^v\d+(\.\d+)?(\.\d+)?$"])

(def ParsedVersion
  "Schema for parsed version components.
   
   Result of parsing a version string into its major.minor.patch components."
  [:map {:title "Parsed Version"}
   [:major :int]                         ; Major version (v1 -> 1)
   [:minor {:optional true} [:maybe :int]]  ; Minor version (v1.2 -> 2)
   [:patch {:optional true} [:maybe :int]]]) ; Patch version (v1.2.3 -> 3)

(def VersionMetadata
  "Schema for version metadata in API responses.
   
   Provides version information and deprecation status to clients."
  [:map {:title "Version Metadata"}
   [:version :keyword]                       ; Current version (e.g., :v1)
   [:latest-stable :keyword]                 ; Latest stable version
   [:deprecated :boolean]                    ; True if current version is deprecated
   [:sunset-date {:optional true} [:maybe :string]]  ; ISO 8601 date (e.g., "2026-06-01")
   [:supported-versions [:set :keyword]]])   ; Set of all supported versions

;; =============================================================================
;; API Response Envelope Schemas
;; =============================================================================

(def ApiMetadata
  "Schema for API response metadata.
   
   Includes version info, timestamp, and request ID for observability."
  [:map {:title "API Metadata"}
   [:version :keyword]                       ; API version used
   [:timestamp inst?]                        ; Response timestamp
   [:request-id {:optional true} [:maybe :string]]  ; Correlation ID
   [:deprecated {:optional true} :boolean]   ; True if version is deprecated
   [:sunset-date {:optional true} [:maybe :string]]]) ; Deprecation sunset date

(def PaginatedResponse
  "Schema for paginated API responses.
   
   Standard envelope for all paginated endpoints across the application.
   
   Example:
   {:data [{:id ... :name ...} ...]
    :pagination {:type \"offset\" :total 100 :offset 0 :limit 20 ...}
    :meta {:version :v1 :timestamp #inst \"...\" ...}}"
  [:map {:title "Paginated Response"}
   [:data [:sequential :any]]                 ; Array of items (any type)
   [:pagination PaginationMeta]               ; Pagination metadata
   [:meta {:optional true} ApiMetadata]])     ; API metadata (optional)

(def ErrorResponse
  "Schema for API error responses.
   
   Consistent error format across all API versions."
  [:map {:title "Error Response"}
   [:error :string]                           ; Human-readable error message
   [:code {:optional true} [:maybe :keyword]] ; Machine-readable error code
   [:details {:optional true} [:maybe :map]]  ; Additional error details
   [:meta {:optional true} ApiMetadata]])     ; API metadata

;; =============================================================================
;; Link Header Schemas (RFC 5988)
;; =============================================================================

(def LinkRelation
  "Schema for RFC 5988 link relation types.
   
   Supported relations for pagination navigation."
  [:enum :first :last :prev :next :self])

(def LinkData
  "Schema for link data used to build RFC 5988 Link headers.
   
   Contains URL and relation type for pagination links."
  [:map {:title "Link Data"}
   [:url :string]                             ; Full URL with query params
   [:rel LinkRelation]])                      ; Relation type (first, last, prev, next, self)

(def LinkHeaderString
  "Schema for RFC 5988 Link header string.
   
   Format: '</api/v1/users?offset=20&limit=20>; rel=\"next\", ...'"
  [:string {:min 1}])

;; =============================================================================
;; Validation Schemas
;; =============================================================================

(def ValidationError
  "Schema for validation error details.
   
   Used when request parameters fail validation."
  [:map {:title "Validation Error"}
   [:field :keyword]                          ; Field that failed validation
   [:message :string]                         ; Human-readable error message
   [:value {:optional true} :any]             ; Invalid value provided
   [:constraint {:optional true} [:maybe :any]]]) ; Constraint that was violated

(def ValidationErrors
  "Schema for collection of validation errors."
  [:sequential ValidationError])

;; =============================================================================
;; Configuration Schemas
;; =============================================================================

(def PaginationConfig
  "Schema for pagination configuration.
   
   Used in config.edn to configure pagination behavior."
  [:map {:title "Pagination Configuration"}
   [:default-limit [:int {:min 1 :max 100}]]  ; Default items per page
   [:max-limit [:int {:min 1 :max 1000}]]     ; Maximum allowed items per page
   [:default-type [:enum :offset :cursor]]    ; Default pagination type
   [:cursor-ttl {:optional true} [:int {:min 0}]]  ; Cursor TTL in seconds
   [:enable-link-headers {:optional true} :boolean]]) ; Enable RFC 5988 headers

(def VersioningConfig
  "Schema for API versioning configuration.
   
   Used in config.edn to configure version behavior."
  [:map {:title "Versioning Configuration"}
   [:default-version :keyword]                ; Default version (e.g., :v1)
   [:latest-stable :keyword]                  ; Latest stable version
   [:deprecated-versions [:set :keyword]]     ; Set of deprecated versions
   [:sunset-dates [:map-of :keyword :string]] ; Map of version -> sunset date
   [:supported-versions [:set :keyword]]])    ; Set of all supported versions

;; =============================================================================
;; Helper Functions for Schema Validation
;; =============================================================================

(def ^:private pagination-params-validator (m/validator PaginationParams))
(def ^:private offset-pagination-params-validator (m/validator OffsetPaginationParams))
(def ^:private cursor-pagination-params-validator (m/validator CursorPaginationParams))
(def ^:private version-string-validator (m/validator VersionString))
(def ^:private paginated-response-validator (m/validator PaginatedResponse))
(def ^:private pagination-params-explainer (m/explainer PaginationParams))
(def ^:private paginated-response-explainer (m/explainer PaginatedResponse))

(defn valid-pagination-params?
  "Validate pagination parameters against schema.
   
   Args:
     params - Map of pagination parameters
     
   Returns:
     Boolean indicating validity"
  [params]
  (pagination-params-validator params))

(defn valid-offset-pagination-params?
  "Validate offset pagination parameters.
   
   Args:
     params - Map of offset pagination parameters
     
   Returns:
     Boolean indicating validity"
  [params]
  (offset-pagination-params-validator params))

(defn valid-cursor-pagination-params?
  "Validate cursor pagination parameters.
   
   Args:
     params - Map of cursor pagination parameters
     
   Returns:
     Boolean indicating validity"
  [params]
  (cursor-pagination-params-validator params))

(defn valid-version-string?
  "Validate version string format.
   
   Args:
     version-str - String version (e.g., \"v1\", \"v1.2\")
     
   Returns:
     Boolean indicating validity"
  [version-str]
  (version-string-validator version-str))

(defn valid-paginated-response?
  "Validate paginated response structure.
   
   Args:
     response - Paginated response map
     
   Returns:
     Boolean indicating validity"
  [response]
  (paginated-response-validator response))

(defn explain-pagination-params
  "Get detailed validation errors for pagination parameters.
   
   Args:
     params - Map of pagination parameters
     
   Returns:
     Malli explanation map or nil if valid"
  [params]
  (pagination-params-explainer params))

(defn explain-paginated-response
  "Get detailed validation errors for paginated response.
   
   Args:
     response - Paginated response map
     
   Returns:
     Malli explanation map or nil if valid"
  [response]
  (paginated-response-explainer response))

;; =============================================================================
;; Schema Coercion Transformers
;; =============================================================================

(comment
  "Example usage of schemas:
   
   ;; Validate incoming query parameters
   (valid-pagination-params? {:limit 20 :offset 40})
   ;;=> true
   
   (valid-pagination-params? {:limit 200})
   ;;=> false (exceeds max limit)
   
   ;; Explain validation errors
   (explain-pagination-params {:limit 200})
   ;;=> {:schema [...] :value {...} :errors [...]}
   
   ;; Validate response structure
   (valid-paginated-response?
     {:data [{:id #uuid \"...\" :name \"Alice\"}]
      :pagination {:type \"offset\"
                   :total 100
                   :offset 0
                   :limit 20
                   :has-next true
                   :has-prev false
                   :total-pages 5
                   :current-page 1}
      :meta {:version :v1
             :timestamp #inst \"2024-01-04T10:00:00Z\"}})
   ;;=> true
   
   ;; Validate version string
   (valid-version-string? \"v1\")
   ;;=> true
   
   (valid-version-string? \"v1.2.3\")
   ;;=> true
   
   (valid-version-string? \"1.0\")
   ;;=> false (missing 'v' prefix)")
