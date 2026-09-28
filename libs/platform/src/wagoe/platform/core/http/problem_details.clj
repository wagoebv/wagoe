(ns wagoe.platform.core.http.problem-details
  "Exceptions to error responses, and the `:type` -> status table.

   The name is historical: these were RFC 7807 bodies until BOU-586 made every
   JSON error the one shape of `wagoe.platform.core.http.errors`."
  (:require [cheshire.core]
            [clojure.string :as str]
            [wagoe.platform.core.http.errors :as errors]))

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
;; Error Body Construction
;; =============================================================================

(defn exception-status
  "The HTTP status and title for `ex`: its `:type` looked up in the default
   mappings merged with `error-mappings`. Untyped or unmapped is a 500."
  [ex error-mappings]
  (let [ex-type (:type (ex-data ex))]
    (if ex-type
      (get (merge default-error-mappings error-mappings) ex-type [500 "Internal Server Error"])
      [500 "Internal Server Error"])))

(defn exception->problem-body
  "The error body for `ex`, in the framework's one shape (BOU-586):

     {:error {:type \"not-found\" :message \"...\" :details {...}
              :correlation-id \"...\"}}

   A typed 4xx carries its message — ex-data `:message`, else the exception's,
   else the mapping's title — and the rest of its ex-data as `:details`. A 5xx
   carries none of that: raw messages and ex-data go to the server log, never
   to the client. `uri` and `context` are accepted for the callers that pass
   them and are not sent: request context is for the log."
  ([ex correlation-id uri error-mappings]
   (exception->problem-body ex correlation-id uri error-mappings {}))
  ([ex correlation-id _uri error-mappings _context]
   (let [data           (ex-data ex)
         [status title] (exception-status ex error-mappings)]
     (if (>= status 500)
       (errors/body :internal-error "Internal Server Error"
                    {:correlation-id correlation-id})
       (errors/body (:type data)
                    (or (:message data) (ex-message ex) title)
                    {:details        (dissoc data :type :message)
                     :correlation-id correlation-id})))))

(defn problem-details->response
  "Ring response for an error body. The body is encoded here, because some
   callers answer outside the content negotiation."
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (cheshire.core/generate-string body)})

(defn exception->problem-response
  "Ring response for `ex`: its status, and the body of
   `exception->problem-body`."
  ([ex correlation-id uri]
   (exception->problem-response ex correlation-id uri {} {}))
  ([ex correlation-id uri error-mappings]
   (exception->problem-response ex correlation-id uri error-mappings {}))
  ([ex correlation-id uri error-mappings context]
   (problem-details->response
    (first (exception-status ex error-mappings))
    (exception->problem-body ex correlation-id uri error-mappings context))))

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

(defn- builder [status type]
  (fn
    ([message] (problem-details->response status (errors/body type message)))
    ([message details]
     (problem-details->response status (errors/body type message {:details details})))))

(def bad-request
  "400 with `message` and optional `details`."
  (builder 400 :bad-request))

(def forbidden
  "403 with `message` and optional `details`."
  (builder 403 :forbidden))

(def not-found
  "404 with `message` and optional `details`."
  (builder 404 :not-found))

(def internal-server-error
  "500 with `message` and optional `details`."
  (builder 500 :internal-error))
