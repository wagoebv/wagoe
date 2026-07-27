(ns wagoe.platform.shell.interfaces.cli.middleware
  "CLI middleware for command execution with enhanced error context (imperative shell).
   
   This namespace provides reusable CLI middleware that can be used across
   different modules and applications for consistent observability and
   error handling in command-line interfaces.
   
   Middleware functions handle CLI boundaries: command execution interception,
   context generation, logging, and exception handling with enhanced context
   preservation for debugging.

   Features:
   - Command correlation ID generation
   - Tenant and user context extraction from config/environment
   - Structured command execution logging with observability context
   - Command timing and error reporting with enhanced context
   - Error reporting breadcrumb integration"
  (:require [wagoe.observability.logging.core :as logging]
            [wagoe.platform.shell.utils.error-handling :as error-handling]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

;; =============================================================================
;; CLI Context Management
;; =============================================================================

;; =============================================================================
;; Error Reporting Helpers
;; =============================================================================

;; Removed add-cli-breadcrumb - breadcrumb functionality should be handled
;; by the error reporting services, not the CLI middleware layer

(defn generate-correlation-id
  "Generates a new correlation ID for CLI command execution.
   
   Returns:
     UUID string for request correlation"
  []
  (str (UUID/randomUUID)))

(defn extract-tenant-context
  "Extracts tenant context from CLI options, config, or environment.
   
   Extraction priority:
   1. --tenant-id CLI option (explicit override)
   2. BOUNDARY_TENANT_ID environment variable
   3. :tenant-id from config map (if provided)
   
   Args:
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     Tenant ID string or nil"
  [cli-opts config]
  (or (:tenant-id cli-opts)
      (System/getenv "BOUNDARY_TENANT_ID")
      (:tenant-id config)))

(defn extract-user-context
  "Extracts user context from CLI options, config, or environment.
   
   For CLI commands, user context typically comes from:
   1. --user-id CLI option (for admin operations)
   2. BOUNDARY_USER_ID environment variable
   3. Current authenticated user from config
   
   Args:
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     User ID string or nil"
  [cli-opts config]
  (or (:user-id cli-opts)
      (System/getenv "BOUNDARY_USER_ID")
      (get-in config [:auth :user-id])))

(defn build-cli-context
  "Builds observability context for CLI command execution.
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     Observability context map"
  [command subcommand cli-opts config]
  (let [correlation-id (generate-correlation-id)
        tenant-id (extract-tenant-context cli-opts config)
        user-id (extract-user-context cli-opts config)]
    (cond-> {:correlation-id correlation-id
             :command command
             :event :cli-command}
      subcommand (assoc :subcommand subcommand)
      tenant-id (logging/with-tenant-id tenant-id)
      user-id (logging/with-user-id user-id))))

;; =============================================================================
;; CLI Middleware Functions
;; =============================================================================

(defn with-cli-context
  "Higher-order function that wraps CLI command execution with observability context.
   
   This middleware:
   1. Generates correlation ID for command execution
   2. Extracts tenant/user context from CLI args and environment
   3. Builds observability context for logging and metrics
   4. Makes context available to the wrapped function
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     config: Application config map (optional)
     f: Function to execute with context (receives: service, opts, context)
   
   Returns:
     Function that takes (service, cli-opts) and executes with context"
  ([command f] (with-cli-context command nil nil f))
  ([command subcommand f] (with-cli-context command subcommand nil f))
  ([command subcommand config f]
   (fn [service cli-opts]
     (let [context (build-cli-context command subcommand cli-opts config)]
       (f service cli-opts context)))))

(defn with-cli-logging
  "Higher-order function that wraps CLI command execution with structured logging.
   
   Logs command start/completion with timing and context information, plus
   error reporting breadcrumbs for command lifecycle tracking.
   Should be used together with with-cli-context.
   
   Args:
     logger: ILogger instance (optional, uses tools.logging if not provided)
     f: Function to execute with logging (receives same args as passed)
   
   Returns:
     Function that logs around execution"
  ([f] (with-cli-logging nil f))
  ([logger f]
   (fn [service cli-opts context]
     (let [start-time (System/currentTimeMillis)
           {:keys [command subcommand correlation-id tenant-id user-id]} context
           command-name (str command (when subcommand (str " " subcommand)))
           command-details {:command command
                            :subcommand subcommand
                            :correlation-id correlation-id
                            :tenant-id tenant-id
                            :user-id user-id}]

;; Note: breadcrumb tracking removed - should be handled by error reporting services

       ;; Log command start
       (if logger
         (logging/log-function-entry logger command-name cli-opts context)
         (log/info "CLI command started"
                   (merge {:command command
                           :subcommand subcommand
                           :event :cli-command-start}
                          context)))

        (try
          (let [result (f service cli-opts context)
                duration (- (System/currentTimeMillis) start-time)
                final-context (assoc context
                                     :duration-ms duration
                                     :outcome :success
                                     :event :cli-command-complete)
                _success-details (merge command-details
                                        {:duration-ms duration
                                         :outcome "success"})]

;; Note: breadcrumb tracking removed - should be handled by error reporting services

           ;; Log successful completion
           (if logger
             (logging/log-function-exit logger command-name result final-context)
             (log/info "CLI command completed successfully" final-context))

           result)

          (catch Exception ex
            (let [duration (- (System/currentTimeMillis) start-time)
                  error-context (assoc context
                                       :duration-ms duration
                                       :outcome :error
                                       :error (.getMessage ex)
                                       :event :cli-command-error)
                  _error-details (merge command-details
                                        {:duration-ms duration
                                         :outcome "error"
                                         :error (.getMessage ex)
                                         :exception-type (.getSimpleName (class ex))})]

;; Note: breadcrumb tracking removed - should be handled by error reporting services

             ;; Log error
             (if logger
               (logging/log-exception logger :error "CLI command failed" ex error-context)
               (log/error "CLI command failed" error-context))

             ;; Re-throw to maintain CLI error handling behavior
             (throw ex))))))))

(defn with-cli-error-reporting
  "Execute CLI command with enhanced error reporting.
   
   Captures unexpected exceptions and reports them to the error reporting system
   with comprehensive error context while preserving CLI exit code behavior. 
   Uses enhanced error context utilities for better debugging information.
   
   Args:
     context: CLI context map containing user, operation, and additional context
     operation-fn: Function to execute with error reporting (takes context as arg)
   
   Returns:
     Result of operation-fn, or throws enhanced exception with CLI context"
  [context operation-fn]
  (let [{:keys [operation user-id]} context
        ;; Build CLI error context for enhanced reporting using error-handling utilities
        cli-error-context (merge {:command operation
                                  :user-id user-id}
                                 context)]
    ;; Use enhanced CLI error context wrapper from error-handling utils
    ;; This handles the exception enhancement and CLI context preservation
    (error-handling/with-cli-error-context
      cli-error-context
      #(operation-fn context))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn wrap-cli-command
  "Convenience function that applies all CLI middleware to a command function.
   
   Creates a higher-order function that applies logging, context generation, 
   and error reporting middleware when called.
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     config: Application config map (optional)
     logger: ILogger instance (optional)
     f: Command function to wrap (will receive [service cli-opts context])
   
   Returns:
     Function that takes [service cli-opts] and executes with full middleware stack"
  ([command f]
   (wrap-cli-command command nil nil nil f))
  ([command subcommand f]
   (wrap-cli-command command subcommand nil nil f))
  ([command subcommand config logger f]
   (fn [service cli-opts]
     (let [context (build-cli-context command subcommand cli-opts config)]
       (with-cli-error-reporting
         context
         (fn [ctx]
           (let [logged-fn (with-cli-logging logger f)]
             (logged-fn service cli-opts ctx))))))))