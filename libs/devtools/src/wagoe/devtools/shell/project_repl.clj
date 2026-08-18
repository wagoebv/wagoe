(ns wagoe.devtools.shell.project-repl
  "The REPL helpers a project created by `wagoe new` gets.

   `bb quickstart` has always closed with \"run (status), run (commands)\", and
   until BOU-319 neither existed outside this monorepo: the generated
   dev/user.clj was thirteen lines of go/reset/halt, so the first instruction a
   new user follows answered `Unable to resolve symbol: status`.

   The implementations live here rather than in the template because a template
   is not compiled by anything until someone generates a project and boots it.
   dev/user.clj is now a thin delegation layer over these, and these are
   tested.

   Shell, not core: every function here reads the running system or prints."
  (:require [integrant.repl.state :as state]
            [wagoe.devtools.core.auto-fix :as auto-fix]
            [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.guidance :as guidance]
            [wagoe.devtools.core.introspection :as introspection]
            [wagoe.devtools.shell.auto-fix :as auto-fix-shell]
            [wagoe.devtools.shell.repl :as repl]
            [wagoe.devtools.shell.repl-error-handler :as repl-errors]))

;; =============================================================================
;; What is running
;; =============================================================================

(def ^:private infra-keys
  "Integrant keys that are infrastructure rather than application modules."
  #{"settings" "postgresql" "sqlite" "mysql" "h2" "http" "router"
    "api-versioning" "pagination" "logging" "metrics" "tracing"
    "error-reporting" "http-server" "http-handler" "db-context"
    "i18n" "i18n-http-middleware" "email"})

(defn module-names
  "Names of the application modules in `system`, sorted."
  [system]
  (->> (keys system)
       (filter #(and (keyword? %)
                     (= "wagoe" (namespace %))
                     (not (contains? infra-keys (name %)))))
       (map name)
       sort
       vec))

(defn- http-port
  "The port the server is actually listening on, not the one that was asked
   for. They differ whenever auto-find moved the app off an occupied 3000, and
   printing the configured port then sends the user to a dead URL."
  [system config]
  (or (try
        (some-> (get system :wagoe/http-server)
                (.getConnectors)
                first
                (.getLocalPort))
        (catch Exception _ nil))
      (get-in config [:wagoe/http :port])
      3000))

(defn status-report
  "The startup dashboard for `system`, or nil when nothing is running."
  [system config]
  (when system
    (let [host      (or (get-in config [:wagoe/http :host]) "localhost")
          base-url  (str "http://" (if (= host "0.0.0.0") "localhost" host)
                         ":" (http-port system config))
          admin     (get config :wagoe/admin)]
      (guidance/format-startup-dashboard
       {:components (count system)
        :errors     0
        :web-url    base-url
        :admin-url  (when admin (str base-url (or (:base-path admin) "/admin")))
        :nrepl-port 7888
        :modules    (module-names system)}))))

(defn status
  "Print system health: components, URLs, active modules."
  []
  (if-let [report (status-report state/system state/config)]
    (println report)
    (println "System not running. Start it with (go)")))

(defn modules
  "Active application modules, as a vector of names."
  []
  (module-names state/system))

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

;; =============================================================================
;; The palette
;; =============================================================================

(def command-groups
  "What a generated project actually has.

   Deliberately not this monorepo's `guidance/command-groups`: that one lists
   (lint), (check-all), (scaffold!) and the ai/* helpers, none of which exist
   in a generated project's dev/user.clj. A palette that names commands the
   project does not have is the defect BOU-319 fixes, one layer down."
  {:system [{:name "(go)"        :desc "Start the system"}
            {:name "(reset)"     :desc "Reload code and restart"}
            {:name "(halt)"      :desc "Stop the system"}
            {:name "(status)"    :desc "Components, URLs, active modules"}
            {:name "(modules)"   :desc "List active modules"}
            {:name "(config)"    :desc "The running config"}
            {:name "(routes)"    :desc "Show all HTTP routes"}
            {:name "(routes :m)" :desc "Filter routes by module"}]
   :debug  [{:name "(fix!)"      :desc "Apply the fix for the last error"}
            {:name "(commands)"  :desc "Show this list"}]
   :shell  [{:name "bb scaffold" :desc "Generate a module"}
            {:name "bb migrate"  :desc "Run database migrations"}
            {:name "bb check"    :desc "FC/IS, deps, lint, doctor"}
            {:name "bb guide"    :desc "Contextual help"}]})

(defn commands
  "Print the commands this project has."
  []
  (println (guidance/format-command-groups command-groups)))
