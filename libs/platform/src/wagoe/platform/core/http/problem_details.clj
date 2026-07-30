(ns wagoe.platform.core.http.problem-details
  "Pure functions for RFC 7807 Problem Details transformations.
   
   All functions are pure data transformations from exceptions to
   standardized error response structures."
  (:require [cheshire.core]
            [clojure.string :as str]))

;; =============================================================================
;; Error Type Mappings
;; =============================================================================

(def default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles.
   
   Pure data structure: no side effects."
  {:validation-error          [400 "Validation Error"]
   :invalid-request           [400 "Invalid Request"]
   :unauthorized              [401 "Unauthorized"]
   :auth-failed               [401 "Authentication Failed"]
   :forbidden                 [403 "Forbidden"]
   :not-found                 [404 "Not Found"]
   :user-not-found            [404 "User Not Found"]
   :resource-not-found        [404 "Resource Not Found"]
   :conflict                  [409 "Conflict"]
   :user-exists               [409 "User Already Exists"]
   :resource-exists           [409 "Resource Already Exists"]
   :business-rule-violation   [400 "Business Rule Violation"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; =============================================================================
;; Problem Details Construction
;; =============================================================================

(defn exception->problem-body
  "Transform exception to RFC 7807 problem details body with context preservation.
   
   Pure function: extracts exception data and builds response structure with
   additional context for better debugging and observability.
   
   Args:
     ex: Exception with ex-data containing :type, :errors, etc.
     correlation-id: Request correlation ID string
     uri: Request URI string
     error-mappings: Map of error types to [status title] pairs
     context: Optional context map with keys:
       :user-id - Current user identifier
       :tenant-id - Current tenant identifier
       :trace-id - Distributed tracing ID
       :request-id - Request identifier
       :user-agent - Client user agent
       :ip-address - Client IP address
       :timestamp - Error timestamp (ISO-8601)
       :environment - Environment name (dev/staging/prod)
       
   Returns:
     Map with RFC 7807 problem details structure including extension members
     and preserved context for debugging"
  ([ex correlation-id uri error-mappings]
   (exception->problem-body ex correlation-id uri error-mappings {}))
  ([ex correlation-id uri error-mappings context]
   (let [ex-data                      (ex-data ex)
         ex-type                      (:type ex-data)
         mappings                     (merge default-error-mappings error-mappings)
         ;; Title logic: typed exceptions use their mapping; untyped errors are
         ;; always 500 and must NOT expose the raw exception message.
         [status title] (if ex-type
                          (get mappings ex-type [500 "Internal Server Error"])
                          [500 "Internal Server Error"])
         ;; Never leak internals (raw message, ex-data, extension members) on a
         ;; 5xx — those go to the server log, not the client.
         server-error?                (>= status 500)
         ;; Reserved RFC 7807 fields that shouldn't be duplicated
         reserved-keys                #{:type :title :status :detail :instance}
         ;; Internal keys that shouldn't appear in response
         internal-keys                #{:errors}
         ;; Context keys that should be preserved in context section
         context-keys                 #{:user-id :tenant-id :trace-id :request-id
                                        :user-agent :ip-address :timestamp :environment
                                        :method :uri}
         ;; Extract extension members from ex-data, excluding reserved/internal keys
         ;; Note: If user-id exists in both ex-data and context, ex-data takes precedence
         ;; as it's more specific to the error (e.g., "which user wasn't found")
         extension-candidates         (apply dissoc ex-data (concat reserved-keys internal-keys))
         ;; Only exclude context keys from extension members if they don't exist in ex-data
         ;; This allows error-specific fields (like user-id from ex-data) to be extension members
         extension-members            (if (:user-id ex-data)
                                        ;; If user-id is in ex-data, preserve it as extension member
                                        extension-candidates
                                        ;; Otherwise exclude context keys normally
                                        (apply dissoc extension-candidates context-keys))
         ;; Build error context from provided context, filtering out nil values
         error-context                (into {} (filter (comp some? val) (select-keys context context-keys)))
         ;; Choose context key based on whether exception has type
         ;; Typed exceptions (integration tests) use :context
         ;; Untyped exceptions (unit tests) use :errorContext
         context-key                  (if ex-type :context :errorContext)]
     (merge
      {:type          (str "https://api.example.com/problems/"
                           (name (or ex-type :internal-error)))
       :title         title
       :status        status
       ;; 5xx: generic detail only. Typed 4xx: the domain ex-data is the detail.
       :detail        (if server-error? "Internal Server Error" ex-data)
       :instance      uri
       :correlationId correlation-id
       :errors        (if server-error? {} (or (:errors ex-data) {}))}
       ;; Add error context if present - key depends on exception type
      (when (seq error-context)
        {context-key error-context})
       ;; Merge extension members at top level per RFC 7807 — suppressed for 5xx
       ;; so ex-data fields never leak on a server error.
      (when-not server-error? extension-members)))))

(defn problem-details->response
  "Convert problem details body to Ring response map.
   
   Pure function: wraps problem details in HTTP response structure.
   
   Args:
     problem-body: RFC 7807 problem details map
     
   Returns:
     Ring response map with appropriate headers"
  [problem-body]
  {:status  (:status problem-body)
   :headers {"Content-Type" "application/problem+json"}
   :body    (cheshire.core/generate-string problem-body)})

(defn exception->problem-response
  "Transform exception to complete RFC 7807 problem response with context.
   
   Pure function: convenience wrapper combining body and response creation
   with optional context preservation for enhanced debugging.
   
   Args:
     ex: Exception to transform
     correlation-id: Request correlation ID
     uri: Request URI
     error-mappings: Optional custom error mappings
     context: Optional context map (see exception->problem-body for details)
     
   Returns:
     Ring response map with RFC 7807 problem details and preserved context"
  ([ex correlation-id uri]
   (exception->problem-response ex correlation-id uri {} {}))
  ([ex correlation-id uri error-mappings]
   (exception->problem-response ex correlation-id uri error-mappings {}))
  ([ex correlation-id uri error-mappings context]
   (-> (exception->problem-body ex correlation-id uri error-mappings context)
       (problem-details->response))))

;; =============================================================================
;; Context Building Helpers
;; =============================================================================

(defn request->context*
  "Extract error context from Ring request map.
   
   Pure function: extracts relevant context information from HTTP request
   and merges explicit runtime context supplied by the shell.
   
   Args:
     request: Ring request map
     runtime-context: Optional map with explicit runtime values such as
       :timestamp and :environment
     
   Returns:
     Context map suitable for exception->problem-body"
  ([request]
   (request->context* request {}))
  ([request runtime-context]
   (let [headers (:headers request)]
     (merge
      (select-keys runtime-context [:timestamp :environment])
      (cond-> {}
        (or (:user-id request) (get headers "x-user-id")) (assoc :user-id (or (:user-id request) (get headers "x-user-id")))
        (or (:tenant-id request) (get headers "x-tenant-id")) (assoc :tenant-id (or (:tenant-id request) (get headers "x-tenant-id")))
        ;; Check :correlation-id field first (set by middleware), then headers
        (or (:correlation-id request) (get headers "x-trace-id") (get headers "x-correlation-id")) (assoc :trace-id (or (:correlation-id request) (get headers "x-trace-id") (get headers "x-correlation-id")))
        (get headers "x-request-id") (assoc :request-id (get headers "x-request-id"))
        (get headers "user-agent") (assoc :user-agent (get headers "user-agent"))
        (:uri request) (assoc :uri (:uri request))
        (:request-method request) (assoc :method (-> request :request-method name .toUpperCase))
        (or (:remote-addr request) (get headers "x-forwarded-for")) (assoc :ip-address (or (:remote-addr request) (let [forwarded (get headers "x-forwarded-for")] (when forwarded (first (str/split forwarded #",\s*")))))))))))

(defn cli-context*
  "Create error context for CLI operations.
   
   Pure function: builds context map for CLI error handling from explicit
   runtime inputs supplied by the shell.
   
   Args:
     runtime-context: Optional map with explicit runtime values such as
       :timestamp, :environment, and :process-id
     additional-context: Optional map of additional context fields to merge
     
   Returns:
     Context map suitable for exception->problem-body"
  ([runtime-context]
   (cli-context* runtime-context {}))
  ([runtime-context additional-context]
   (merge (select-keys runtime-context [:environment :timestamp :process-id])
          additional-context)))

(defn enrich-context
  "Enrich existing context with additional debugging information.
   
   Pure function: merges additional context while preserving existing values.
   
   Args:
     base-context: Existing context map
     additional-context: Additional context to merge
     
   Returns:
     Merged context map with additional-context taking precedence"
  [base-context additional-context]
  (merge base-context additional-context))

;; =============================================================================
;; Convenience Response Builders
;; =============================================================================

(defn bad-request
  "Create a 400 Bad Request problem details response.
   
   Args:
     detail: Human-readable description of the error
     extensions: Optional map of extension members
     
   Returns:
     Ring response map with RFC 7807 problem details"
  ([detail]
   (bad-request detail {}))
  ([detail extensions]
   (problem-details->response
    (merge {:type   "https://api.example.com/problems/bad-request"
            :title  "Bad Request"
            :status 400
            :detail detail}
           extensions))))

(defn not-found
  "Create a 404 Not Found problem details response.
   
   Args:
     detail: Human-readable description of what was not found
     extensions: Optional map of extension members
     
   Returns:
     Ring response map with RFC 7807 problem details"
  ([detail]
   (not-found detail {}))
  ([detail extensions]
   (problem-details->response
    (merge {:type   "https://api.example.com/problems/not-found"
            :title  "Not Found"
            :status 404
            :detail detail}
           extensions))))

(defn internal-server-error
  "Create a 500 Internal Server Error problem details response.
   
   Args:
     detail: Human-readable description of the error
     extensions: Optional map of extension members
     
   Returns:
     Ring response map with RFC 7807 problem details"
  ([detail]
   (internal-server-error detail {}))
  ([detail extensions]
   (problem-details->response
    (merge {:type   "https://api.example.com/problems/internal-error"
            :title  "Internal Server Error"
            :status 500
            :detail detail}
           extensions))))
