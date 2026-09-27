(ns wagoe.devtools.shell.repl-error-handler
  "REPL error handler — runs the error pipeline and stores the last exception.
   This is a shell namespace: it performs I/O (printing).
   Usage from user.clj:
     Wrap public REPL functions with try/catch that calls handle-repl-error!
     The zero-arity (fix!) reads from last-exception*."
  (:require [integrant.repl.state :as state]
            [wagoe.platform.system :as platform-system]
            [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.error-enricher :as enricher]
            [wagoe.devtools.core.error-formatter :as formatter]
            [wagoe.devtools.shell.dashboard.pages.errors :as dashboard-errors]))

(defonce last-exception* (atom nil))

(defn dashboard-port
  "The port the running dev dashboard serves on, or nil when none runs.

   Read from both places a system lives: a REPL `(go)` fills
   integrant.repl.state, a start through `wagoe.main` fills
   `platform-system/running` (BOU-508). Not the config: Jetty may have moved
   off a busy port, and a stopped dashboard has no page to link to."
  []
  (get-in (or state/system
              (try (platform-system/running) (catch Exception _ nil)))
          [:wagoe/dashboard :port]))

(defn handle-repl-error!
  "Run the full error pipeline on an exception and print the result.
   Stores the exception in last-exception* for (fix!) to access.
   Pipeline: classify → enrich → format → print
   Falls back to standard output + AI hint for unclassified errors.

   opts (optional):
     :guidance-level — controls fix-hint visibility in output"
  ([^Throwable exception]
   (handle-repl-error! exception {}))
  ([^Throwable exception {:keys [guidance-level] :or {guidance-level :full}}]
   (when exception
     (reset! last-exception* exception)
     (let [classified (classifier/classify exception)]
       (if (:code classified)
         (let [enriched  (enricher/enrich classified {:dashboard-port (dashboard-port)})
               formatted (formatter/format-enriched-error enriched {:guidance-level guidance-level})]
           (dashboard-errors/record-error!
            {:code         (:code enriched)
             :message      (or (:message enriched) (.getMessage exception))
             :category     (:category enriched)
             :timestamp-ms (System/currentTimeMillis)})
           (println formatted))
         (do
           (dashboard-errors/record-error!
            {:code         "BND-000"
             :message      (.getMessage exception)
             :category     :unclassified
             :timestamp-ms (System/currentTimeMillis)})
           (println (formatter/format-unclassified-error exception))))))))
