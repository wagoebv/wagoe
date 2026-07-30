(ns wagoe.devtools.shell.repl-error-handler
  "REPL error handler — runs the error pipeline and stores the last exception.
   This is a shell namespace: it performs I/O (printing).
   Usage from user.clj:
     Wrap public REPL functions with try/catch that calls handle-repl-error!
     The zero-arity (fix!) reads from last-exception*."
  (:require [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.error-enricher :as enricher]
            [wagoe.devtools.core.error-formatter :as formatter]
            [wagoe.devtools.shell.dashboard.pages.errors :as dashboard-errors]))

(defonce last-exception* (atom nil))

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
         (let [enriched  (enricher/enrich classified)
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
