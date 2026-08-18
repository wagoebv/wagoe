(ns wagoe.devtools.shell.http-error-middleware
  "Dev-mode HTTP error enrichment middleware.

   NOT the supported path, and nothing in the framework wires it. The
   application injects `:wagoe/dev-error-enricher` (see module-wiring) and the
   platform interceptor calls it; that route is gated on the environment and
   returns a code, a category and a fix. This one attaches `:dev-info`
   containing the *formatted* block — stacktrace included — to ex-data, so
   whatever includes ex-data in a response would put a stacktrace in it.
   Kept for an application that wired it by hand before BOU-321.

   Positioned INSIDE the exception handler: catches exceptions, runs the error
   pipeline, re-throws with :dev-info attached."
  (:require [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.error-enricher :as enricher]
            [wagoe.devtools.core.error-formatter :as formatter]
            [wagoe.devtools.shell.dashboard.pages.errors :as dashboard-errors]))

(defn- build-dev-info
  "Build the :dev-info map from an enriched error."
  [{:keys [code category fix] :as enriched}]
  {:formatted      (if code
                     (formatter/format-enriched-error enriched)
                     (formatter/format-unclassified-error (:exception enriched)))
   :code           code
   :category       category
   :fix-available? (boolean fix)
   :fix-label      (:label fix)})

(defn wrap-dev-error-enrichment
  "Ring middleware that enriches exceptions with dev-info in dev mode.
   Catches exceptions, runs the error pipeline, attaches result as
   :dev-info in the exception's ex-data, and re-throws.
   The outer error handling middleware can then include :dev-info
   in its Problem Details response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (let [classified (classifier/classify ex)
              enriched   (enricher/enrich classified)
              dev-info   (build-dev-info enriched)
              original-data (or (ex-data ex) {})
              enhanced-data (assoc original-data :dev-info dev-info)]
          ;; Record in dashboard error log
          (dashboard-errors/record-error!
           (merge enriched
                  {:timestamp-ms (System/currentTimeMillis)
                   :message      (.getMessage ex)
                   :source       :http}))
          ;; Pass original exception as cause to preserve full stack trace
          (throw (ex-info (.getMessage ex)
                          enhanced-data
                          ex)))))))
