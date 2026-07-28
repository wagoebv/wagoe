(ns wagoe.main
  "Main entry point for Wagoe application uberjar.
   
   Provides unified entry point that can run the application in different modes:
   - server: Start HTTP server (default)
   - worker: Start a background worker (no HTTP listener)
   - cli: Run CLI commands

   Usage:
     java -jar boundary-standalone.jar              # Start HTTP server
     java -jar boundary-standalone.jar server       # Start HTTP server explicitly
     java -jar boundary-standalone.jar worker       # Start a background worker
     java -jar boundary-standalone.jar cli [args]   # Run CLI commands"
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.system.wiring] ; Required for Integrant init functions
            ;; Load feature modules' Integrant init/halt methods at the app layer
            ;; so platform does not depend on the feature libs (BOU-171 / BOU-192).
            [wagoe.user.shell.module-wiring]
            [wagoe.admin.shell.module-wiring]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.search.shell.module-wiring]
            [wagoe.tenant.shell.module-wiring]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:gen-class))

(defn- print-usage
  "Print usage information."
  []
  (println "Wagoe Framework")
  (println)
  (println "Usage:")
  (println "  java -jar wagoe.jar [mode] [options]")
  (println)
  (println "Modes:")
  (println "  server  - Start HTTP server (default)")
  (println "  worker  - Start a background worker (no HTTP listener)")
  (println "  cli     - Run CLI commands")
  (println "  help    - Show this help message")
  (println)
  (println "Environment Variables:")
  (println "  HTTP_PORT           - HTTP server port (default: 3000)")
  (println "  HTTP_HOST           - HTTP server host (default: 0.0.0.0)")
  (println "  WAG_ENV             - Environment profile (dev, prod, test, acc)")
  (println)
  (println "Examples:")
  (println "  java -jar wagoe.jar")
  (println "  java -jar wagoe.jar server")
  (println "  WAG_ENV=prod java -jar wagoe.jar server")
  (println "  java -jar wagoe.jar cli user list"))

(def http-surface-keys
  "Integrant keys that make up the HTTP-serving surface. A worker node omits
   them so it binds no port and runs only background components."
  [:wagoe/http-server :wagoe/http-handler :wagoe/dashboard])

(defn worker-ig-config
  "The Integrant config for a worker node: the full system minus the HTTP
   surface (no Jetty listener, no route tree). Background components — jobs,
   scheduled tasks, realtime — still start. This is the counterpart to `server`
   that makes the web/worker split in scaling.adoc achievable."
  [ig-config]
  (apply dissoc ig-config http-surface-keys))

(defn- boot-and-block!
  "Init `ig-config`, install a shutdown hook that halts it gracefully, and block
   until the JVM is signalled to stop."
  [what ig-config]
  (let [system (ig/init ig-config)]
    (log/info (str "Wagoe " what " started successfully"))
    (log/info "Press Ctrl+C to stop")
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (log/info "Shutdown signal received, stopping...")
                (try
                  (ig/halt! system)
                  (log/info "Stopped gracefully")
                  (catch Exception e
                    (log/error e "Error during shutdown"))))))
    ;; Block forever (until Ctrl+C / SIGTERM)
    @(promise)))

(defn- start-server!
  "Start the full system including the HTTP server and block."
  []
  (log/info "Starting Wagoe HTTP server")
  (try
    (boot-and-block! "HTTP server" (config/ig-config (config/load-config)))
    (catch Exception e
      (log/error e "Failed to start server")
      (System/exit 1))))

(defn- start-worker!
  "Start the system without the HTTP surface (background worker) and block."
  []
  (log/info "Starting Wagoe worker (no HTTP listener)")
  (try
    (boot-and-block! "worker" (worker-ig-config (config/ig-config (config/load-config))))
    (catch Exception e
      (log/error e "Failed to start worker")
      (System/exit 1))))

(defn- run-cli!
  "Run CLI command and exit with status code."
  [args]
  (log/info "Running Wagoe CLI" {:args args})
  (try
    ;; Load CLI namespace dynamically to avoid loading HTTP dependencies
    (require 'wagoe.cli)
    (let [cli-main (resolve 'wagoe.cli/-main)]
      (apply cli-main args))
    (catch Exception e
      (log/error e "CLI command failed")
      (System/exit 1))))

(defn -main
  "Main entry point for uberjar.
   
   Parses command-line arguments to determine mode:
   - server: Start HTTP server (default if no mode specified)
   - cli: Run CLI commands
   - help: Show usage information"
  [& args]
  (let [mode (first args)
        remaining-args (rest args)]
    (case (str/lower-case (or mode "server"))
      "server"
      (start-server!)

      "worker"
      (start-worker!)

      "cli"
      (run-cli! remaining-args)

      ("help" "-h" "--help")
      (do
        (print-usage)
        (System/exit 0))

      ;; Default: treat as server mode
      (if (nil? mode)
        (start-server!)
        (do
          (println (str "Unknown mode: " mode))
          (println)
          (print-usage)
          (System/exit 1))))))
