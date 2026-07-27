(ns wagoe.platform.shell.utils.error-handling
  "Error handling utilities for enhanced context preservation and structured error responses.
   
   This namespace provides middleware and utilities for capturing rich error context
   across HTTP and CLI interfaces, enabling better debugging and observability."
(:require [wagoe.platform.core.http.problem-details :as problem]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

(defn- current-environment []
  (or (System/getProperty "environment") "test"))

(defn- current-cli-environment []
  (or (System/getProperty "environment") "development"))

(defn- current-timestamp []
  (java.time.Instant/now))

(defn- current-process-id []
  (str (.pid (java.lang.ProcessHandle/current))))

;; =============================================================================
;; HTTP Error Context Middleware
;; =============================================================================

(defn wrap-error-context
  "Middleware to enhance request with error context information.
   
   This middleware runs early in the stack to capture and preserve context
   that will be useful for error handling and debugging. It enriches the
   request with standardized error context information.
   
   Context captured:
   - Request ID (generated if not present)
   - Trace ID (from headers)
   - User Agent
   - IP Address
   - Environment
   - Timestamp
   
   Args:
     handler: Ring handler function
     
   Returns:
     Ring middleware function that adds :error-context to request"
  [handler]
  (fn [request]
    (let [headers (:headers request)
          ;; Generate request ID if not present
          request-id (or (get headers "x-request-id")
                         (str (UUID/randomUUID)))
          ;; Build comprehensive error context
          error-context (merge
                         (problem/request->context*
                          request
                          {:environment (current-environment)
                           :timestamp (current-timestamp)})
                         {:request-id request-id
                          :timestamp (current-timestamp)})
          ;; Add error context to request for downstream middleware
          enhanced-request (assoc request :error-context error-context)]
      ;; Add request ID header for tracing
      (-> (handler enhanced-request)
          (assoc-in [:headers "X-Request-ID"] request-id)))))

(defn wrap-enhanced-exception-handling
  "Enhanced exception handling middleware with comprehensive context capture.
   
   This is an alternative to the basic exception handling middleware that
   provides more comprehensive error context and can be used when maximum
   debugging information is needed.
   
   Features:
   - Uses pre-captured error context from wrap-error-context
   - Comprehensive error logging with full context
   - Enhanced Problem Details responses
   - Request/response correlation tracking
   
   Args:
     handler: Ring handler function
     error-mappings: Optional custom error type mappings
     
   Returns:
     Ring middleware function"
  ([handler]
   (wrap-enhanced-exception-handling handler {}))
  ([handler error-mappings]
   (fn [request]
     (try
       (handler request)
       (catch Exception ex
         (let [headers (:headers request)
               ;; Extract correlation-id from headers
               correlation-id (or (:correlation-id request)
                                  (get headers "x-correlation-id"))
               uri (:uri request)
               request-method (:request-method request)
               error-context (:error-context request)
               ;; Use pre-captured error context or build it
               context (or error-context
                           (problem/request->context*
                            request
                            {:environment (current-environment)
                             :timestamp (current-timestamp)}))
               ;; Enrich with exception-specific context
               enriched-context (problem/enrich-context
                                 context
                                 {:exception-type (.getSimpleName (class ex))
                                  :exception-message (.getMessage ex)
                                  :request-method (name (or request-method :unknown))
                                  :request-uri uri})]

           ;; Enhanced error logging with full context
           (log/error "HTTP request failed with enhanced context"
                      {:correlation-id correlation-id
                       :uri uri
                       :method (name (or request-method :unknown))
                       :exception {:type (.getSimpleName (class ex))
                                   :message (.getMessage ex)
                                   :data (ex-data ex)}
                       :context enriched-context})

           ;; Return enhanced Problem Details response with correlation-id in headers
           (let [problem-response (problem/exception->problem-response
                                   ex
                                   correlation-id
                                   uri
                                   error-mappings
                                   enriched-context)]
             ;; Add correlation-id to response headers if present
             (if correlation-id
               (assoc-in problem-response [:headers "X-Correlation-ID"] correlation-id)
               problem-response))))))))

;; =============================================================================
;; CLI Error Context Utilities
;; =============================================================================

(defn with-cli-error-context
  "Execute a CLI operation with enhanced error context.
   
   Wraps CLI operations to capture and preserve error context for better
   debugging and error reporting. This is the CLI equivalent of HTTP
   error context middleware.
   
   Args:
     context: CLI context map with keys:
       :command - CLI command being executed
       :user-id - Current user identifier
       :additional context as needed
     operation-fn: Function to execute
       
   Returns:
     Result of operation-fn, or throws exception with enhanced context"
  [context operation-fn]
  (try
    (operation-fn)
    (catch Exception ex
      (let [;; Preserve original exception data
            original-data (ex-data ex)
            ;; Check if this exception already has CLI context (nested case)
            existing-cli-context (:cli-context original-data)
            ;; Build CLI error context
            base-cli-context (problem/cli-context*
                              {:environment (current-cli-environment)
                               :timestamp (current-timestamp)
                               :process-id (current-process-id)}
                              {:user-id (:user-id context)
                               :command (:command context)})
            ;; Enrich with provided context
            enriched-context (problem/enrich-context base-cli-context context)
            ;; For nested contexts, preserve inner context (it takes precedence)
            final-cli-context (if existing-cli-context
                                existing-cli-context
                                enriched-context)
            ;; Create enhanced exception data structure
            enhanced-data {:original-data (if existing-cli-context
                                            (:original-data original-data)
                                            original-data)
                           :cli-context final-cli-context}
            ;; Create enhanced exception preserving the original cause chain
            enhanced-ex (ex-info (.getMessage ex)
                                 enhanced-data
                                 (.getCause ex))]
        ;; Enhanced CLI error logging
        (log/error "CLI operation failed with enhanced context"
                   {:command (:command context)
                    :user-id (:user-id context)
                    :exception {:type (.getSimpleName (class ex))
                                :message (.getMessage ex)
                                :data (if existing-cli-context
                                        (:original-data original-data)
                                        original-data)}
                    :context final-cli-context})

        ;; Re-throw with enhanced context
        (throw enhanced-ex)))))

(defn format-cli-error
  "Format an exception for CLI display with context information.
   
   Extracts error context from exception and formats it for CLI output,
   providing both user-friendly error messages and debug information.
   
   Args:
     ex: Exception with optional error context in ex-data
     :include-context (boolean): Whether to show full context (default false)
     
   Returns:
     Formatted error string for CLI display"
  [ex & {:keys [include-context] :or {include-context false}}]
  (let [ex-data (ex-data ex)
        cli-context (:cli-context ex-data)
        original-data (:original-data ex-data)
        base-message (.getMessage ex)]
    (if include-context
      ;; Full context formatting - include CLI context and all data
      (let [context-lines [(str "Operation: " (:operation cli-context))
                           (str "User ID: " (:user-id cli-context))
                           (str "Timestamp: " (:timestamp cli-context))]
            ;; Include additional context fields beyond standard ones  
            additional-context (dissoc cli-context :operation :user-id :timestamp :environment :process-id)
            additional-lines (when (seq additional-context)
                               (map (fn [[k v]] (str (name k) ": " v)) additional-context))
            all-context-lines (concat context-lines additional-lines)
            ;; Display exception data (either original-data or filtered ex-data)
            display-data (or original-data
                             (dissoc ex-data :cli-context :original-data))
            data-text (when (seq display-data)
                        (str "Exception Data:\n"
(with-out-str (pprint/pprint display-data))))]
        (format "%s\n\nContext:\n%s\n%s"
                base-message
                (str/join "\n" all-context-lines)
                (or data-text "")))
      ;; Basic formatting - include original exception data but no CLI context
      (let [display-data (or original-data
                             (dissoc ex-data :cli-context :original-data))]
        (if (seq display-data)
          (format "%s\nData: %s"
                  base-message
                  (with-out-str (pprint/pprint display-data)))
          base-message)))))
