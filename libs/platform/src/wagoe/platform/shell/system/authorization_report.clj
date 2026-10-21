(ns wagoe.platform.shell.system.authorization-report
  "`authz-report`: the route table as `:wagoe/authorization :enforce` would
  judge it, printed without serving. Builds `:wagoe/http-handler` and what it
  depends on, never `:wagoe/http-server`, so it binds no port; the database
  and the modules still start, as the routes need them, and so do the
  migrations a config runs at start."
  (:require [integrant.core :as ig]
            [reitit.core]
            [wagoe.platform.core.authorization :as core]))

(defn handler-routes
  "`handler`'s endpoints as `core/route-report` gives them, and the mode it
   was built under: {:mode :report}."
  [handler]
  {:mode   (:wagoe/authorization-mode (meta handler))
   :report (core/route-report (reitit.core/routes (:reitit/router (meta handler))))})

(defn report
  "The report text for `ig-config`, an application's Integrant config. The
   system is halted before it returns; a system that does not build throws,
   as it would on a normal start."
  [ig-config]
  (let [system (try (ig/init ig-config [:wagoe/http-handler])
                    (catch clojure.lang.ExceptionInfo e
                      ;; What did build, the database pool among it, is halted too.
                      (some-> (:system (ex-data e)) ig/halt!)
                      (throw e)))]
    (try
      (let [handler (or (:wagoe/http-handler system)
                        (throw (ex-info "This config has no :wagoe/http-handler, so no routes to report."
                                        {:type :configuration-error})))
            {:keys [mode report]} (handler-routes handler)]
        (core/format-route-report mode report))
      (finally (ig/halt! system)))))
