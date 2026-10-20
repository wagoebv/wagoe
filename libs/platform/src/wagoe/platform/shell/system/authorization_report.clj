(ns wagoe.platform.shell.system.authorization-report
  "`authz-report`: the route table as `:wagoe/authorization :enforce` would
  judge it, printed without serving. Builds `:wagoe/http-handler` and what it
  depends on, never `:wagoe/http-server`, so it binds no port; the database
  and the modules still start, as the routes need them."
  (:require [integrant.core :as ig]
            [reitit.core]
            [wagoe.platform.core.authorization :as core]))

(defn- failure-message [^Throwable e]
  (let [cause (last (take-while some? (iterate ex-cause e)))]
    (str "Could not build the routes: " (ex-message e)
         (when-not (identical? cause e) (str "\n  caused by: " (ex-message cause))))))

(defn report
  "The report text for `ig-config`, an application's Integrant config. The
   system is halted before it returns."
  [ig-config]
  (let [system (ig/init ig-config [:wagoe/http-handler])]
    (try
      (let [handler (:wagoe/http-handler system)]
        (core/format-route-report (:wagoe/authorization-mode (meta handler))
                                  (core/route-report (reitit.core/routes (:reitit/router (meta handler))))))
      (finally (ig/halt! system)))))

(defn print-report!
  "Print the report for `ig-config` and return an exit status: 0, or 1 when
   the system does not build."
  [ig-config]
  (try
    (println (report ig-config))
    0
    (catch Exception e
      (binding [*out* *err*] (println (failure-message e)))
      1)))
