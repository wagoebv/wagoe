(ns wagoe.devtools.core.project-repl
  "What a generated project's REPL helpers show: which modules are running, and
   the command palette that project actually has.

   Pure. The shell namespace of the same name reads the running system, asks
   Jetty for its port, and prints."
  (:require [clojure.string :as str]
            [wagoe.devtools.core.guidance :as guidance]))

;; =============================================================================
;; Which of these keys is a module?
;; =============================================================================

(def ^:private component-suffixes
  "Suffixes an Integrant key carries when it is a *part* of a module.

   Longest first: `user-db-schema` must lose `-db-schema`, not `-schema`, or
   the stem is `user-db`."
  ["-http-middleware" "-schema-provider" "-db-schema" "-repository" "-middleware"
   "-provider" "-emitter" "-service" "-context" "-handler" "-routes" "-server"
   "-schema" "-store"])

(def ^:private infra-stems
  "Stems that are plumbing every project has, not something the user added.

   `dashboard` and `dev-error-enricher` are devtools' own components. They only
   appear when devtools is on the classpath, which is why nothing listed them
   until this namespace started answering `(modules)` for a profile that runs
   it — and a developer who asks which modules are running did not add the tool
   they are asking through (BOU-399)."
  #{"settings" "postgresql" "sqlite" "mysql" "h2" "http" "router" "db"
    "api-versioning" "pagination" "logging" "metrics" "tracing" "email"
    "error-reporting" "i18n" "module"
    "dashboard" "dev-error-enricher"})

(def ^:private module-of
  "Components that belong to a module whose name they do not carry.

   The user module wires eight keys and only four are named `user-*`; without
   this a bare project reports auth, mfa, session and audit as four separate
   modules, which is four things the user never added."
  {"auth"       "user"
   "mfa"        "user"
   "session"    "user"
   "audit"      "user"
   "membership" "tenant"})

(defn- stem
  "The module name an Integrant key belongs to, or nil for plumbing.

   `:wagoe/tasks-repository` -> \"tasks\", `:wagoe/mfa-service` -> \"user\",
   `:wagoe.push/token-store` -> \"push\" (a module that namespaces its keys),
   `:wagoe/logging` -> nil."
  [k]
  (when (keyword? k)
    (let [ns- (namespace k)]
      (cond
        (nil? ns-) nil

        ;; :wagoe.push/… — the module is in the namespace.
        (str/starts-with? (str ns- ".") "wagoe..")
        nil

        (and (str/starts-with? ns- "wagoe.") (not= ns- "wagoe"))
        (let [m (subs ns- (count "wagoe."))]
          (when-not (contains? infra-stems m) m))

        (= ns- "wagoe")
        (let [n    (name k)
              base (or (some #(when (str/ends-with? n %)
                                (subs n 0 (- (count n) (count %))))
                             component-suffixes)
                       n)
              base (get module-of base base)]
          (when-not (contains? infra-stems base) base))))))

(defn module-names
  "Names of the application modules present in `system-or-config`, sorted.

   Takes the keys, not a blocklist of known plumbing: a generated project wires
   twenty Integrant keys and one of them is the module the user scaffolded. The
   first version of this listed all twenty minus a handful, so `(status)` on a
   fresh project reported twelve \"modules\", eleven of them the user module in
   pieces (BOU-319)."
  [system-or-config]
  (->> (keys system-or-config)
       (keep stem)
       distinct
       sort
       vec))

;; =============================================================================
;; The palette
;; =============================================================================

(def command-groups
  "What a generated project has.

   Deliberately not this monorepo's `guidance/command-groups`: that one lists
   (lint), (check-all), (scaffold!) and the ai/* helpers, none of which exist
   in a generated project's dev/user.clj. A palette that names commands the
   project does not have is the defect BOU-319 fixes, one layer down."
  {:system [{:name "(go)"          :desc "Start the system"}
            {:name "(reset)"       :desc "Reload code and restart"}
            {:name "(halt)"        :desc "Stop the system"}
            {:name "(status)"      :desc "Components, URL, active modules"}
            {:name "(modules)"     :desc "List active modules"}
            {:name "(system)"      :desc "The running system map"}
            {:name "(config)"      :desc "The running config"}
            {:name "(config :k)"   :desc "One section, secrets redacted"}
            {:name "(routes)"      :desc "Show all HTTP routes"}
            {:name "(routes :m)"   :desc "Filter routes by module"}]
   :debug  [{:name "(fix!)"        :desc "Apply the fix for the last error"}
            {:name "(commands)"    :desc "Show this list"}]
   :shell  [{:name "bb scaffold"   :desc "Generate a module"}
            {:name "bb migrate"    :desc "Run database migrations"}
            {:name "bb check"      :desc "FC/IS, deps, lint, doctor"}
            {:name "bb guide"      :desc "Contextual help"}]})

(defn commands-text
  "The command palette of a generated project, as a string."
  []
  (guidance/format-command-groups command-groups))

;; =============================================================================
;; The dashboard
;; =============================================================================

(defn status-text
  "The startup dashboard, or nil when nothing is running.

   The admin URL comes off the running system, not the config: `ig-config`
   never emits a `:wagoe/admin` key — the admin module arrives as
   `:wagoe/admin-service` and `:wagoe/admin-routes` — so reading a base path
   from the config yields nothing, and a guessed URL that 404s is worse than
   none. `:wagoe/admin-routes` carries the `:web-prefix` the router actually
   mounted, which is the question answered without guessing. Absent when the
   admin module is not running, and then the line is not printed (BOU-394)."
  [{:keys [system base-url]}]
  (when system
    (guidance/format-startup-dashboard
     {:components (count system)
      :errors     0
      :web-url    base-url
      :admin-url  (when-let [prefix (get-in system [:wagoe/admin-routes :web-prefix])]
                    (str base-url prefix))
      :nrepl-port 7888
      :modules    (module-names system)})))
