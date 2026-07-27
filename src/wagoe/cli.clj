(ns wagoe.cli
  "Main CLI entry point for Boundary framework.
   
   Provides command-line interface for enabled modules.
   Currently user and scaffolder modules are wired, with selection driven by config."
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.modules :as modules]
            [wagoe.user.shell.cli-entry :as user-cli-entry]
            [wagoe.scaffolder.shell.cli-entry :as scaffolder-cli-entry]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  "CLI main entry point.
   
   Delegates to the appropriate module CLI implementation(s) based on
   configuration and exits with the returned status code."
  [& args]
  (log/info "Starting Boundary CLI" {:args args})
  (let [cfg (config/load-config)
        enabled (modules/enabled-modules cfg)
        ;; Map of module keyword to its CLI runner function
        module->runner {:user user-cli-entry/run-cli!
                        :scaffolder scaffolder-cli-entry/run-scaffolder-cli!}
        status (modules/dispatch-cli enabled module->runner args)]
    (System/exit status)))
