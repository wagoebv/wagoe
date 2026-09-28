(ns wagoe.platform.shell.database.cli-migrations
  "CLI commands for database migration management.

   Usage:
     clojure -M -m wagoe.platform.shell.database.cli-migrations [command] [options]

   Commands:
     migrate         - Run all pending migrations
     rollback        - Roll back the last migration
     status          - Show migration status
     create <name>   - Create a new migration file
     reset           - Drop what the app owns and migrate (dev, test, acc only)
     init            - Initialize migration system"
  (:require [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.database.reset :as reset]
            [clojure.tools.cli :as cli]
            [clojure.string :as str])
  (:gen-class))

;; =============================================================================
;; CLI Specification
;; =============================================================================

(def cli-options
  "CLI options specification for migration commands."
  [["-h" "--help" "Show help"]
   ["-v" "--verbose" "Verbose output"]
   [nil "--allow-remote" "reset: allow a database on another machine"]])

;; =============================================================================
;; Command Implementations
;; =============================================================================

(defn cmd-migrate
  "Runs all pending migrations."
  [opts]
  (try
    (println "\n🔄 Running database migrations...")
    (migrations/migrate)
    (println "✅ Migrations completed successfully\n")
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Migration failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-rollback
  "Rolls back the last migration."
  [opts]
  (try
    (println "\n🔙 Rolling back last migration...")
    (migrations/rollback)
    (println "✅ Rollback completed successfully\n")
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Rollback failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-status
  "Shows migration status."
  [opts]
  (try
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Failed to get status:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-create
  "Creates a new migration file."
  [migration-name opts]
  (if (str/blank? migration-name)
    (do
      (println "❌ Error: Migration name required")
      (println "\nUsage: clojure -M -m wagoe.platform.shell.database.cli-migrations create <name>")
      (println "\nExample: clojure -M -m wagoe.platform.shell.database.cli-migrations create add-email-verification")
      1)
    (try
      (println (format "\n📝 Creating migration: %s..." migration-name))
      (let [result (migrations/create-migration migration-name)]
        (println "✅" (:message result))
        (println (format "\nMigration files created in: %s" (:directory result)))
        (println "\nNext steps:")
        ;; The same directory as the line above, not a hardcoded "migrations/".
        ;; In a resources-backed layout those two lines disagreed, and this one
        ;; sent the user to a directory the files were not in (BOU-274).
        (println (format "1. Edit the generated SQL files in %s" (:directory result)))
        (println "2. Run: clojure -M -m wagoe.platform.shell.database.cli-migrations migrate")
        0)
      (catch Exception e
        (println "❌ Migration creation failed:" (.getMessage e))
        (when (:verbose opts)
          (.printStackTrace e))
        1))))

(defn tty?
  "Whether a person can answer the prompt. A console is not enough: newer
   JDKs return one with stdin redirected. isTerminal is asked reflectively
   because the baseline JDK lacks it."
  []
  (if-let [c (System/console)]
    (try (boolean (clojure.lang.Reflector/invokeInstanceMethod c "isTerminal" (object-array 0)))
         (catch Exception _ true))
    false))

(defn- print-plan [{:keys [env host database schema tables tenant-schemas]}]
  (println "\n⚠️  bb db:reset drops everything below, then reapplies the migrations.")
  (println (str "   Profile:  " env))
  (println (str "   Host:     " host))
  (println (str "   Database: " database (when schema (str "   Schema: " schema))))
  (println "   Every migration, rolled back through its down migration")
  (println (str "   Tables:   " (if (seq tables) (str/join ", " tables) "none")))
  (println (str "   Tenant schemas: " (if (seq tenant-schemas) (str/join ", " tenant-schemas) "none"))))

(defn cmd-reset
  "Drops what the application owns and migrates again (BOU-585).

   Refuses outside dev, test and acc, on another machine without
   --allow-remote, and when something it does not own depends on what it
   would drop. The profile is resolved by `detect-environment`, the function
   the connection uses, so `clojure -M:migrate reset` is guarded as well as
   `bb db:reset` (BOU-258). Then it shows what it will drop and asks for the
   database's name, on a terminal only: piping `yes` answers nothing."
  [opts]
  (try
    (let [plan (reset/plan {:allow-remote? (:allow-remote opts)})]
      (print-plan plan)
      (if-not (tty?)
        (do (println "\n❌ No terminal to confirm on. Nothing was dropped.")
            1)
        (do
          (print (str "\nType the database name (" (:database plan) ") to continue: "))
          (flush)
          (if (= (:database plan) (some-> (read-line) str/trim))
            (do (println "\n🔄 Resetting database...")
                (reset/execute! plan)
                (println "✅ Database reset completed\n")
                (migrations/print-status)
                0)
            ;; Non-zero: a cancelled destructive operation is not success for
            ;; whoever shelled out to us (BOU-500).
            (do (println "\n❌ Reset cancelled")
                1)))))
    (catch clojure.lang.ExceptionInfo e
      (if (#{:forbidden :conflict :reset-failed} (:type (ex-data e)))
        (do (println (str "\n❌ " (ex-message e) "\n"))
            1)
        (do (println "❌ Reset failed:" (or (:error (ex-data e)) (ex-message e)))
            (when (:verbose opts) (.printStackTrace e))
            1)))
    (catch Exception e
      (println "❌ Reset failed:" (.getMessage e))
      (when (:verbose opts) (.printStackTrace e))
      1)))

(defn cmd-init
  "Initializes the migration system."
  [opts]
  (try
    (println "\n🔧 Initializing migration system...")
    (migrations/init)
    (println "✅ Migration system initialized successfully")
    (println "\nNext steps:")
    (println "1. Run 'status' to see migration state")
    (println "2. Run 'migrate' to apply pending migrations")
    0
    (catch Exception e
      (println "❌ Initialization failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

;; =============================================================================
;; Help and Usage
;; =============================================================================

(defn print-help
  "Prints CLI help message."
  []
  (println "\nWagoe Database Migration CLI")
  (println "================================\n")
  (println "Usage:")
  (println "  clojure -M -m wagoe.platform.shell.database.cli-migrations [command] [options]\n")
  (println "Commands:")
  (println "  migrate              Run all pending migrations")
  (println "  rollback             Roll back the last migration")
  (println "  status               Show current migration status")
  (println "  create <name>        Create a new migration file")
  (println "  init                 Initialize migration system (first time setup)")
  (println "  reset [--allow-remote]  Drop what the app owns and migrate; dev, test and acc only [DESTRUCTIVE]\n")
  (println "Options:")
  (println "  -h, --help           Show this help message")
  (println "  -v, --verbose        Verbose output\n")
  (println "Examples:")
  (println "  # Check migration status")
  (println "  clojure -M -m wagoe.platform.shell.database.cli-migrations status\n")
  (println "  # Run pending migrations")
  (println "  clojure -M -m wagoe.platform.shell.database.cli-migrations migrate\n")
  (println "  # Create a new migration")
  (println "  clojure -M -m wagoe.platform.shell.database.cli-migrations create add-user-email-verification\n")
  (println "  # Roll back last migration")
  (println "  clojure -M -m wagoe.platform.shell.database.cli-migrations rollback\n"))

(defn exit!
  "Wrapper around System/exit to keep CLI dispatch testable."
  [status]
  (System/exit status))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main CLI entry point for migration commands."
  [& args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)
        command-args (rest arguments)]

    (cond
      ;; Show help
      (:help options)
      (do
        (print-help)
        (exit! 0))

      ;; No command provided
      (nil? command)
      (do
        (println "❌ Error: No command specified\n")
        (print-help)
        (exit! 1))

      ;; Parse errors
      errors
      (do
        (println "❌ Errors:")
        (doseq [error errors]
          (println "  " error))
        (println)
        (print-help)
        (exit! 1))

      ;; Execute command
      :else
      (let [status (case command
                     "migrate"  (cmd-migrate options)
                     "up"       (cmd-migrate options)   ; common alias
                     "rollback" (cmd-rollback options)
                     "down"     (cmd-rollback options)  ; common alias
                     "status"   (cmd-status options)
                     "create"   (cmd-create (first command-args) options)
                     ;; After the command, which :in-order leaves unparsed.
                     "reset"    (cmd-reset (cond-> options
                                             (some #{"--allow-remote"} command-args)
                                             (assoc :allow-remote true)))
                     "init"     (cmd-init options)

                     ;; Unknown command
                     (do
                       (println (format "❌ Unknown command: %s\n" command))
                       (print-help)
                       1))]
        (exit! status)))))
