(ns wagoe.platform.shell.interfaces.http.middleware
  "HTTP middleware for web applications (imperative shell).
   
   This namespace provides reusable HTTP middleware that can be used across
   different modules and applications. It follows RFC standards and best
   practices for web API development.
   
   Middleware functions handle I/O boundaries: request/response interception,
   logging, and exception handling.

   Features:
   - Request correlation ID management
   - Tenant and user context extraction
   - Multi-tenant request processing with schema switching
   - Structured request/response logging with observability context
   - RFC 7807 Problem Details for error responses (via core)
   - Generic exception handling middleware
   - Error reporting breadcrumb integration
   
   Multi-Tenant Middleware:
   See wagoe.tenant.shell.tenant-middleware for:
   - Tenant resolution (subdomain, JWT, headers)
   - PostgreSQL schema switching per request
   - Tenant caching with configurable TTL
   - Combined middleware for simplified integration"
  (:require [wagoe.platform.core.http.problem-details :as problem]
            [wagoe.observability.errors.core :as error-reporting]
            [wagoe.observability.errors.ports :as error-reporting-ports]
            [wagoe.observability.logging.core :as logging]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

(defn- current-environment []
  (or (System/getProperty "environment") "test"))

(defn- current-timestamp []
  (java.time.Instant/now))

;; =============================================================================
;; Middleware - Correlation ID
;; =============================================================================

;; =============================================================================
;; Error Reporting Helpers
;; =============================================================================

(defn- add-http-breadcrumb
  "Add HTTP operation breadcrumb with enriched context.
   
   Args:
     context: Error reporting context (from request :observability-context)
     operation: String describing the HTTP operation
     status: :start, :success, or :error
     details: Map with operation details"
  [context operation status details]
  ;; Only attempt to add breadcrumb if context is an actual error reporter instance
  ;; In tests, context might just be a plain map, so we skip breadcrumb creation
  (when (and context (satisfies? error-reporting-ports/IErrorContext context))
    (error-reporting/add-breadcrumb
     context
     (str "HTTP " operation " " (case status
                                  :start "initiated"
                                  :success "completed"
                                  :error "failed"))
     "http" ; category
     (case status
       :start :info
       :success :info
       :error :error) ; level
     (merge {:operation operation
             :status (name status)
             :source "http-middleware"}
            details))))

(defn wrap-correlation-id
  "Middleware to add correlation ID to requests and responses.

       Adds a unique correlation ID to each request for tracing purposes.
       Uses X-Correlation-ID header if present, otherwise generates a new UUID.

       Args:
         handler: Ring handler function

       Returns:
         Ring middleware function"
  [handler]
  (fn [request]
    (let [correlation-id (or (get-in request [:headers "x-correlation-id"])
                             (get-in request [:headers "x-request-id"])
                             (str (UUID/randomUUID)))
          request-with-id (assoc request :correlation-id correlation-id)
          response (handler request-with-id)]
      (if response
        (assoc-in response [:headers "X-Correlation-ID"] correlation-id)
        response))))

;; =============================================================================
;; Middleware - Tenant and User Context
;; =============================================================================

(defn wrap-tenant-context
  "Middleware to extract and add tenant context to requests.

   Extracts tenant ID from various sources (header, JWT, query param) and
   adds it to the request context for downstream observability and business logic.

   Extraction priority:
   1. X-Tenant-Id header (explicit tenant override)
   2. :tenant-id from JWT claims (if JWT middleware ran first)
   3. tenant-id query parameter

   Args:
     handler: Ring handler function

   Returns:
     Ring middleware function"
  [handler]
  (fn [request]
    (let [tenant-id (or (get-in request [:headers "x-tenant-id"])
                        (get-in request [:identity :tenant-id]) ; from JWT
                        (get-in request [:query-params "tenant-id"]))
          request-with-tenant (if tenant-id
                                (assoc request :tenant-id tenant-id)
                                request)]
      (handler request-with-tenant))))

(defn wrap-user-context
  "Middleware to extract and add user context to requests.

   Extracts user ID from various sources (JWT claims, session) and
   adds it to the request context for downstream observability and audit logging.

   Extraction priority:
   1. :user-id from JWT claims (if JWT middleware ran first)
   2. :user-id from session (if session middleware ran first)
   3. X-User-Id header (for service-to-service calls)

   Args:
     handler: Ring handler function

   Returns:
     Ring middleware function"
  [handler]
  (fn [request]
    (let [user-id (or (get-in request [:identity :user-id]) ; from JWT
                      (get-in request [:session :user-id]) ; from session
                      (get-in request [:headers "x-user-id"])) ; explicit header
          request-with-user (if user-id
                              (assoc request :user-id user-id)
                              request)]
      (handler request-with-user))))

(defn wrap-observability-context
  "Middleware to create observability context from request data.

   Builds a structured context map that can be used by logging, metrics,
   and error reporting throughout the request lifecycle. This middleware
   should run after correlation-id, tenant-context, and user-context middleware.

   Args:
     handler: Ring handler function
     logger: ILogger instance (optional, for context enhancement)

   Returns:
     Ring middleware function"
  ([handler] (wrap-observability-context handler nil))
  ([handler _logger]
   (fn [request]
     (let [{:keys [correlation-id tenant-id user-id request-method uri]} request
           base-context (cond-> {}
                          correlation-id (logging/with-correlation-id correlation-id)
                          tenant-id (logging/with-tenant-id tenant-id)
                          user-id (logging/with-user-id user-id)
                          request-method (logging/with-tags {:http-method (name request-method)})
                          uri (logging/with-tags {:request-uri uri}))
           request-with-context (assoc request :observability-context base-context)]
       (handler request-with-context)))))

;; =============================================================================
;; Middleware - Request Logging
;; =============================================================================

(defn wrap-request-logging
  "Middleware for structured request/response logging with observability context.

   Logs HTTP requests and responses with timing information, correlation IDs,
   and enriched context for observability and debugging purposes. Also adds
   error reporting breadcrumbs for request lifecycle tracking.

   This middleware should run after wrap-observability-context to have access
   to the full observability context.

   Args:
     handler: Ring handler function
     logger: ILogger instance (optional, uses tools.logging if not provided)

   Returns:
     Ring middleware function"
  ([handler] (wrap-request-logging handler nil))
  ([handler logger]
   (fn [request]
     (let [start-time (System/currentTimeMillis)
           {:keys [request-method uri observability-context]} request
           context (or observability-context {})
           request-details {:method (name request-method)
                            :uri uri
                            :correlation-id (:correlation-id request)
                            :tenant-id (:tenant-id request)
                            :user-id (:user-id request)}]

       ;; Add breadcrumb for request start
       (add-http-breadcrumb context "request" :start request-details)

       ;; Log request start with enriched context
       (if logger
         (logging/log-request-metrics logger request-method uri nil nil context)
         (log/info "HTTP request started"
                   (merge {:method request-method
                           :uri uri
                           :event :http-request-start}
                          context)))

       (try
         (let [response (handler request)
               duration (- (System/currentTimeMillis) start-time)
               final-context (assoc context
                                    :duration-ms duration
                                    :status (:status response)
                                    :event :http-request-complete)
               success-details (merge request-details
                                      {:duration-ms duration
                                       :status (:status response)})]

           ;; Add breadcrumb for successful request completion
           (add-http-breadcrumb context "request" :success success-details)

           ;; Log request completion with metrics
           (if logger
             (logging/log-request-metrics logger request-method uri (:status response) duration context)
             (log/info "HTTP request completed" final-context))

           response)

         (catch Exception e
           (let [duration (- (System/currentTimeMillis) start-time)
                 error-details (merge request-details
                                      {:duration-ms duration
                                       :error (.getMessage e)})]
             ;; Add breadcrumb for request error (will be handled by exception middleware)
             (add-http-breadcrumb context "request" :error error-details)

             ;; Re-throw to let exception middleware handle
             (throw e))))))))

;; =============================================================================
;; Middleware - Exception Handling
;; =============================================================================

(defn wrap-exception-handling
  "Middleware to catch exceptions and return RFC 7807 problem details with context.
   
   SHELL FUNCTION: Performs side effects (exception catching, logging).
   Uses pure core functions for problem details transformation with enhanced context.

   Catches all exceptions and converts them to standardized RFC 7807 problem
   details responses with full error context for debugging. Logs exceptions for 
   debugging and monitoring, and reports them to the error reporting system.

   Args:
     handler: Ring handler function
     error-mappings: Optional custom error type mappings

   Returns:
     Ring middleware function"
  ([handler]
   (wrap-exception-handling handler {}))
  ([handler error-mappings]
   (fn [request]
     (try
       (handler request)
       (catch Exception ex
         (let [{:keys [observability-context correlation-id uri request-method
                       tenant-id user-id]} request
               context (or observability-context {})
               ;; Build enhanced error context using the new helper
               error-context (problem/request->context*
                              request
                              {:environment (current-environment)
                               :timestamp (current-timestamp)})
               ;; Add additional context for error reporting
               error-details {:method (if request-method (name request-method) "unknown")
                              :uri uri
                              :correlation-id correlation-id
                              :tenant-id tenant-id
                              :user-id user-id
                              :error (.getMessage ex)
                              :exception-type (.getSimpleName (class ex))}]

           ;; Add breadcrumb for exception
           (add-http-breadcrumb context "exception" :error error-details)

           ;; Report error to error reporting system with enhanced context
           (when (and context (satisfies? error-reporting-ports/IErrorReporter context))
             (error-reporting/report-enhanced-application-error
              context
              ex
              "HTTP request failed with exception"
              error-details
              error-context))

           ;; Side effect: error logging with enhanced context
           (log/error "Request failed with exception"
                      (merge {:uri uri
                              :method (name request-method)
                              :correlation-id correlation-id
                              :error (.getMessage ex)
                              :ex-data (ex-data ex)
                              :error-context error-context}
                             (when tenant-id {:tenant-id tenant-id})
                             (when user-id {:user-id user-id})))

           ;; Pure: transform exception to problem details using enhanced core function
           (problem/exception->problem-response
            ex
            correlation-id
            uri
            error-mappings
            error-context)))))))
