(ns shop.main
  "Foreground entry point for shop.

   Development is REPL-driven — `bb repl` then `(go)` from dev/user.clj.
   This namespace is everything after that: a process that starts the system,
   blocks, and shuts down cleanly on a signal. It is what `clojure -M:run`, the
   uberjar and the Dockerfile all call, and what a container or systemd unit
   needs, since none of them can drive a REPL."
  (:require [shop.system-config :as config]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:gen-class))

(defn failure-lines
  "What a failed start prints: each cause's message and :type, outermost
   first. Integrant's own message only names the key it was building, and the
   cause is what says why. Where the config was expected, only when that is
   what is missing."
  [e]
  (let [chain (take-while some? (iterate ex-cause e))
        line  (fn [x] (str (ex-message x)
                           (when-let [t (:type (ex-data x))] (str " (:type " t ")"))))]
    (concat [(str "Failed to start shop: " (line e))]
            (map #(str "  caused by: " (line %)) (rest chain))
            (when-let [path (some #(when (contains? (ex-data %) :available-profiles)
                                     (:path (ex-data %)))
                                  chain)]
              [(str "  expected resources/" path)]))))

(defn -main
  [& _args]
  (try
    (let [system (ig/init (config/ig-config (config/load-config)))]
      (log/info "shop started — press Ctrl+C to stop")
      ;; Halt on SIGTERM/SIGINT so a container stop closes the database pool and
      ;; drains the HTTP server rather than having them killed mid-request.
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (log/info "Shutdown signal received, stopping…")
                  (try
                    (ig/halt! system)
                    (log/info "Stopped gracefully")
                    (catch Exception e
                      (log/error e "Error during shutdown"))))))
      ;; Block until signalled. The system runs on its own threads.
      @(promise))
    (catch Exception e
      ;; Message first, stack trace only at debug. A startup failure is usually
      ;; a configuration mistake — a missing conf/<env>/config.edn or an unset
      ;; JWT_SECRET — and a 40-line trace buries the one line that says which.
      (doseq [line (failure-lines e)]
        (log/error line))
      ;; Gated on an env var rather than the log level: a startup failure often
      ;; means the config — including the logging config — never loaded, so the
      ;; root logger is at its DEBUG default and `log/debug` would print the
      ;; trace anyway, defeating the point.
      (when (System/getenv "WAGOE_DEBUG")
        (log/error e "Startup failure detail"))
      (log/error "  Set WAGOE_DEBUG=1 for the full stack trace.")
      ;; Exit non-zero so a supervisor or `docker run` reports the failure
      ;; instead of a process that vanished silently.
      (System/exit 1))))
