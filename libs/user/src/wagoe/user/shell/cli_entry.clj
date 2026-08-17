(ns wagoe.user.shell.cli-entry
  "User module CLI entrypoint wrapper.

  Encapsulates user-specific CLI startup so that the top-level CLI can
  remain as module-agnostic as possible and delegate into this module.

  Configuration comes from wagoe.config, a declared dependency. It used to be
  resolved at runtime, which meant this CLI worked in the monorepo and asked
  generated projects for a `db-spec` their config.clj never defined (BOU-306)."
  (:require [wagoe.config :as config]
            [wagoe.user.shell.cli :as user-cli]
            [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.shell.auth :as user-auth]
            [wagoe.user.shell.mfa :as user-mfa]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.observability.logging.shell.adapters.no-op :as no-op-logging]
            [wagoe.observability.metrics.shell.adapters.no-op :as no-op-metrics]
            [wagoe.observability.errors.shell.adapters.no-op :as no-op-error-reporting]
            [clojure.tools.logging :as log]))

(def ^:dynamic *exit!*
  "Terminates the process with `code`. Indirection so tests can observe the exit
   code -main asks for instead of killing the test JVM."
  (fn [code] (System/exit code)))

(defn run-cli!
  "Run the user module CLI for the given command-line arguments.

  Returns an integer exit status. Does not call System/exit."
  [args]
  (let [exit-status (atom 1)]
    (try
      (log/info "Starting Wagoe User CLI" {:args args})

      (let [cfg                (config/load-config)
            ;; Derive database configuration for the active adapter
            db-conf            (config/db-spec cfg)
            db-ctx             (db-factory/db-context db-conf)]

        (try
          ;; Initialize database schema
          (user-persistence/initialize-user-schema! db-ctx)

          ;; Create repositories
          (let [pagination-cfg (get-in cfg [:active :wagoe/pagination] {:default-limit 20})
                user-repo (user-persistence/create-user-repository db-ctx)
                session-repo (user-persistence/create-session-repository db-ctx)
                audit-repo (user-persistence/create-audit-repository db-ctx pagination-cfg)

                ;; Create no-op observability services for CLI
                ;; (available for future use if needed)
                _logger (no-op-logging/create-no-op-logger nil)
                _metrics (no-op-metrics/create-metrics-emitter nil)
                _error-reporter (no-op-error-reporting/create-error-reporter nil)

                ;; Validation and auth configuration
                validation-cfg (config/user-validation-config cfg)
                auth-cfg {} ; no special auth config for CLI yet

                ;; Create MFA service (required by auth service)
                mfa-svc (user-mfa/create-mfa-service user-repo {})

                ;; Create auth service with MFA support
                auth-svc (user-auth/create-authentication-service
                          user-repo session-repo mfa-svc auth-cfg)

                ;; Create user service with full dependencies
                user-svc (user-service/create-user-service
                          user-repo session-repo audit-repo validation-cfg auth-svc)

                ;; Dispatch CLI commands and capture exit status
                status (user-cli/run-cli! user-svc args)]

            ;; Ensure we always store an integer exit status
            (reset! exit-status (if (integer? status) status 1)))

          (finally
            ;; Always close database connections
            (when-let [datasource (:datasource db-ctx)]
              (try
                (.close ^java.lang.AutoCloseable datasource)
                (catch Exception e
                  (log/warn "Failed to close database connection" {:error (.getMessage e)})))))))

      (catch Exception e
        (log/error "User CLI execution failed" {:error (.getMessage e)})
        (binding [*out* *err*]
          (println "Fatal error:" (.getMessage e)))
        (reset! exit-status 1)))
    @exit-status))

(defn -main
  "CLI main entry point for the user module. Exits with the returned status.

   Invoked via `-m` (the :user-cli alias), NOT via `-e` with
   *command-line-args*. With -e, clojure.main treats the first non-option
   argument as a script path and binds *command-line-args* to what follows, so
   the verb — `create` in `bb create-admin` — was silently dropped and the CLI
   rejected the next argument as an unknown global option (BOU-266). -m passes
   arguments to -main intact."
  [& args]
  (*exit!* (run-cli! (vec args))))
