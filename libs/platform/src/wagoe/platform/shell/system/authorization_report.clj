(ns wagoe.platform.shell.system.authorization-report
  "`authz-report`: the route table as `:wagoe/authorization :enforce` would
  judge it, printed without serving. Builds `:wagoe/http-handler` and what it
  depends on, never `:wagoe/http-server`, so it binds no port; the database
  and the modules still start, as the routes need them."
  (:require [integrant.core :as ig]
            [reitit.core]
            [wagoe.platform.core.authorization :as core]))

(defn report
  "The report text for `ig-config`, an application's Integrant config. The
   system is halted before it returns; a system that does not build throws,
   as it would on a normal start."
  [ig-config]
  (let [system (ig/init ig-config [:wagoe/http-handler])]
    (try
      (let [handler (:wagoe/http-handler system)]
        (core/format-route-report (:wagoe/authorization-mode (meta handler))
                                  (core/route-report (reitit.core/routes (:reitit/router (meta handler))))))
      (finally (ig/halt! system)))))
