(ns wagoe.main
  "Main entry point for Wagoe application uberjar.
   
   Provides unified entry point that can run the application in different modes:
   - server: Start HTTP server (default)
   - worker: Start a background worker (no HTTP listener)
   - service: Start only the named modules, as an independently deployable
     service (BOU-91)
   - cli: Run CLI commands

   Usage:
     java -jar wagoe-standalone.jar                   # Start HTTP server
     java -jar wagoe-standalone.jar server            # Start HTTP server explicitly
     java -jar wagoe-standalone.jar worker            # Start a background worker
     java -jar wagoe-standalone.jar service payments  # Start one module as a service
     java -jar wagoe-standalone.jar cli [args]        # Run CLI commands"
  (:require [wagoe.config :as config]
            [wagoe.system-config :as sys-config]
            [wagoe.platform.shell.system.wiring :as wiring] ; Integrant init functions, and start!/stop!
            ;; Load feature modules' Integrant init/halt methods at the app layer
            ;; so platform does not depend on the feature libs (BOU-171 / BOU-192).
            [wagoe.user.shell.module-wiring]
            [wagoe.admin.shell.module-wiring]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.search.shell.module-wiring]
            [wagoe.tenant.shell.module-wiring]
            [wagoe.platform.core.system-selection :as selection]
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
  (println "  service - Start only the named modules (e.g. service payments)")
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
  (println "  java -jar wagoe.jar service payments")
  (println "  java -jar wagoe.jar service user tenant")
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
  ;; Through `wiring/start!` rather than `ig/init` directly, so the system is
  ;; recorded where introspection can find it. The dev dashboard read the
  ;; running system from `integrant.repl.state/system`, which only a REPL
  ;; `(go)` fills — so on every ordinary server start it fell back to the three
  ;; components it could reach through its own Integrant refs and reported "3
  ;; components · all healthy" for a system of forty-three (BOU-400).
  (wiring/start! ig-config)
  (log/info (str "Wagoe " what " started successfully"))
  (log/info "Press Ctrl+C to stop")
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. (fn []
              (log/info "Shutdown signal received, stopping...")
              (try
                ;; `wiring/stop!` rather than `ig/halt!` on a captured system:
                ;; it halts the same one and clears the record, so nothing
                ;; later reads a system that has been torn down.
                (wiring/stop!)
                (log/info "Stopped gracefully")
                (catch Exception e
                  (log/error e "Error during shutdown"))))))
  ;; Block forever (until Ctrl+C / SIGTERM)
  @(promise))

(defn root-cause
  "The innermost cause of `e`."
  [^Throwable e]
  (if-let [c (.getCause e)] (recur c) e))

(defn startup-failure-summary
  "What to log when the system fails to build: the component and the reason.

   Not the exception object. Integrant's ex-data carries `:value` — the config
   map for the failing key — and logging the exception prints it. For
   `:wagoe/db-context` that map is the database configuration, so a failed
   production boot wrote POSTGRES_PASSWORD to stdout, twice, where container
   logs ship it to whatever aggregates them. Measured against the prod profile
   with a distinctive password.

   A boot fails on the ordinary days too — the database not up yet during a
   rollout, a rotated credential, a wrong host — so this is not a rare path.

   Returns a message string."
  [^Throwable e]
  (let [key'  (:key (ex-data e))
        cause (root-cause e)]
    (str "Failed to start"
         (when key' (str " — " key' " could not be built"))
         ": " (.getMessage cause)
         " [" (.getName (class cause)) "]")))

(defn- start-server!
  "Start the full system including the HTTP server and block."
  []
  (log/info "Starting Wagoe HTTP server")
  (try
    (boot-and-block! "HTTP server" (sys-config/ig-config (config/load-config)))
    (catch Exception e
      (log/error (startup-failure-summary e))
      (System/exit 1))))

(defn service-ig-config
  "The Integrant config for a process running only `service-names`.

   Two steps that are easy to conflate. `selection/service-config` decides
   which components run; `rpc-entry` adds the listener that lets the rest of
   the deployment call them. A service without the second boots happily and is
   unreachable, which is the state BOU-90 left payments in.

   Returns `[ig-config summary]`, or throws `:configuration-error` with a
   message meant for an operator reading a container log."
  [config service-names]
  (let [catalogue (sys-config/service-catalogue config)
        full      (sys-config/ig-config config)]
    (when-let [problem (selection/catalogue-problem catalogue)]
      (throw (ex-info problem {:type :configuration-error})))
    (when-let [problem (selection/selection-problem catalogue full service-names)]
      (throw (ex-info problem {:type :configuration-error})))
    (let [selected (selection/service-config full catalogue service-names)
          rpc-cfg  (sys-config/rpc-config config)
          offered  (->> (sort service-names)
                        (keep (fn [service-name]
                                (when-let [{:keys [protocol component]}
                                           (get-in catalogue [service-name :rpc])]
                                  (when (contains? selected component)
                                    [service-name protocol component]))))
                        vec)]
      ;; One listener serves one protocol. Picking the first of several and
      ;; carrying on would leave the others reachable by nobody, with a healthy
      ;; process and nothing in the log to say so — the failure this whole
      ;; ticket exists to make visible.
      (when (and rpc-cfg (< 1 (count offered)))
        (throw (ex-info
                (str "These services each offer a protocol over RPC, and one "
                     "process serves one: "
                     (str/join ", " (map (comp name first) offered))
                     ". Run them as separate services, or drop :wagoe/rpc to "
                     "run them together with none of them reachable.")
                {:type :configuration-error})))
      (let [[_ protocol component] (first offered)
            rpc (when (and rpc-cfg protocol)
                  {:wagoe/rpc-server
                   (merge (select-keys rpc-cfg [:port :host :service-key :auth])
                          {:protocol       protocol
                           :implementation (ig/ref component)})})]
        [(merge selected rpc)
         (assoc (selection/summary full selected service-names)
                :rpc (boolean rpc))]))))

(defn- start-service!
  "Boot only the named services and block."
  [service-names]
  (log/info "Starting Wagoe service" {:services (vec service-names)})
  (try
    (let [config (config/load-config)
          [ig-config summary] (service-ig-config config (set (map keyword service-names)))]
      ;; Logged before the boot, not after: if a component fails, this is what
      ;; says which subset was being started, and an operator otherwise has to
      ;; infer it from the failure.
      (log/info "Service composition" summary)
      (when-not (:rpc summary)
        (log/info (str "No RPC endpoint for this service — nothing else in the "
                       "deployment can call it. Set :wagoe/rpc in config to expose one.")))
      (boot-and-block! (str "service " (str/join "," (sort service-names))) ig-config))
    (catch Exception e
      (log/error (startup-failure-summary e))
      (System/exit 1))))

(defn- start-worker!
  "Start the system without the HTTP surface (background worker) and block."
  []
  (log/info "Starting Wagoe worker (no HTTP listener)")
  (try
    (boot-and-block! "worker" (worker-ig-config (sys-config/ig-config (config/load-config))))
    (catch Exception e
      (log/error (startup-failure-summary e))
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

      "service"
      (if (seq remaining-args)
        (start-service! remaining-args)
        (do (println "service mode needs at least one module name, e.g. `service user`")
            (println)
            (print-usage)
            (System/exit 2)))

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
