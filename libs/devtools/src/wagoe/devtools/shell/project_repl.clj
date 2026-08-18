(ns wagoe.devtools.shell.project-repl
  "The REPL helpers a project created by `wagoe new` gets.

   `bb quickstart` has always closed with \"run (status), run (commands)\", and
   until BOU-319 neither existed outside this monorepo: the generated
   dev/user.clj was thirteen lines of go/reset/halt, so the first instruction a
   new user follows answered `Unable to resolve symbol: status`.

   The implementations live here rather than in the template because a template
   is not compiled by anything until someone generates a project and boots it.
   dev/user.clj is a thin delegation layer over these, and these are tested.

   Shell: reads the running system, asks Jetty for its port, prints. The
   formatting and the module detection are in the core namespace of the same
   name."
  (:require [integrant.repl.state :as state]
            [wagoe.devtools.core.auto-fix :as auto-fix]
            [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.introspection :as introspection]
            [wagoe.devtools.core.project-repl :as core]
            [wagoe.devtools.shell.auto-fix :as auto-fix-shell]
            [wagoe.devtools.shell.repl :as repl]
            [wagoe.devtools.shell.repl-error-handler :as repl-errors]))

;; =============================================================================
;; What is running
;; =============================================================================

(defn base-url
  "The URL you can open, from the running server rather than the config.

   Two things the config cannot tell you. The port: auto-find moves the app off
   an occupied 3000, and printing the configured port then sends the user to a
   dead address. The host: `0.0.0.0` is what the server binds, not something a
   browser can open. And `ig-config` folds the `:wagoe/http` block into
   `:wagoe/http-server`, so the key the first version of this read did not
   exist in the Integrant config at all."
  [system config]
  (let [server (get system :wagoe/http-server)
        port   (or (try
                     (some-> server (.getConnectors) first (.getLocalPort))
                     (catch Throwable _ nil))
                   (get-in config [:wagoe/http-server :port])
                   3000)
        host   (or (get-in config [:wagoe/http-server :host]) "localhost")]
    (str "http://" (if (contains? #{"0.0.0.0" "::"} host) "localhost" host) ":" port)))

(defn status
  "Print system health: components, URL, active modules."
  []
  (if-let [report (core/status-text {:system   state/system
                                     :base-url (base-url state/system state/config)})]
    (println report)
    (println "System not running. Start it with (go)")))

(defn modules
  "Active application modules, as a vector of names."
  []
  (core/module-names state/system))

(defn config
  "The running config. With a section keyword, print that section as a tree
   with secrets redacted."
  ([] state/config)
  ([section] (println (introspection/format-config-tree state/config section))))

(defn routes
  "Print the HTTP routes of the running system, optionally filtered by module
   keyword or path string."
  ([] (routes nil))
  ([filter-key]
   (if-let [handler (get state/system :wagoe/http-handler)]
     (let [all (repl/extract-routes-from-handler handler)]
       (println (introspection/format-route-table
                 (if filter-key
                   (introspection/filter-routes all filter-key)
                   all))))
     (println "System not running. Start it with (go)"))))

;; =============================================================================
;; Errors
;; =============================================================================

(defn handle-error!
  "Run the error pipeline on `e` and remember it for (fix!).

   dev/user.clj calls this from the catch of (go) and (reset): without it
   nothing ever populates the last-exception the zero-arity (fix!) reads, and
   (fix!) can only ever answer \"no recent error\"."
  [e]
  (repl-errors/handle-repl-error! e {:guidance-level :full}))

(defn fix!
  "Apply the suggested fix for the last error, if there is one.

   (fix!)     — the last error the REPL saw
   (fix! ex)  — a specific exception"
  ([] (fix! @repl-errors/last-exception*))
  ([exception]
   (if (nil? exception)
     (println "No recent error. Trigger one first, then call (fix!)")
     (if-let [fix-desc (auto-fix/match-fix (classifier/classify exception))]
       (auto-fix-shell/execute-fix! fix-desc
                                    {:guidance-level :full
                                     :confirm-fn     #(do (print (str % " [y/N] "))
                                                          (flush)
                                                          (= "y" (read-line)))})
       (println "No auto-fix available for this error.")))))

(defn commands
  "Print the commands this project has."
  []
  (println (core/commands-text)))
