(ns wagoe.platform.shell.system.config
  "Assemble an Integrant system from a loaded configuration map.

   This is the half of `wagoe.system-config` that is the same in every
   application: the components every Wagoe app has (database, HTTP, router,
   observability, i18n), the framework modules its config switches on, and the
   modules `bb scaffold generate` produced. What is left for the application is
   what only it knows — see the generated `config.clj`.

   Before BOU-326 this lived in the generated file, 404 lines of it, and a
   second copy lived in this repository's own `src/wagoe/system_config.clj`.
   Neither copy wired `:wagoe/workflow-db-schema`, so `wagoe add workflow`
   produced a module whose tables were never created.

   Usage:
     (system-config (config/load-config) {:base-ns \"shop\"})"
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.modules :as modules]
            [wagoe.platform.shell.privacy :as privacy]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(def ^:private core-keys
  "`:active` keys `core-components` reads directly.

   Discovery treats any other `:wagoe/*` key carrying `:enabled?` as a
   scaffolded module, so a key wired here and absent from this set would be
   wired twice."
  #{:wagoe/settings :wagoe/http :wagoe/router :wagoe/logging :wagoe/metrics
    :wagoe/tracing :wagoe/error-reporting
    :wagoe/sqlite :wagoe/h2 :wagoe/postgresql :wagoe/mysql})

(defn- core-components
  "The components every Wagoe application has, whatever it enables.

   `http-extras` carries what the modules contributed to `:wagoe/http-handler`
   by name — tenant's `:tenant-service`, user's `:user-service`. Routes are
   not among them: they arrive as `module-routes`, one collection, so platform
   holds no list of which modules may serve HTTP (BOU-330), and neither is
   middleware: each module's `:extra-middleware` arrives in `module-middleware`."
  [config http-extras module-routes module-middleware]
  (let [active   (:active config)
        ;; The port strategy decides by profile: prod binds exactly (BOU-566).
        http-cfg (cond-> (config/http-config config)
                   (:wagoe/profile config) (assoc :profile (:wagoe/profile config)))]
    {:wagoe/settings        (:wagoe/settings active)
     :wagoe/db-context      (config/db-spec config)
     :wagoe/logging         (config/logging-config config)
     :wagoe/metrics         (config/metrics-config config)
     :wagoe/tracing         (config/tracing-config config)
     :wagoe/error-reporting (config/error-reporting-config config)
     :wagoe/router          (get active :wagoe/router {:coercion :malli :middleware []})

     :wagoe/http-handler
     (merge {:config          config
             ;; Routes of the scaffolded modules. The handler cannot name a
             ;; generated module, so they reach it as a collection; without
             ;; this a scaffolded module initialises and serves nothing.
             :module-routes   module-routes
             ;; Each module's `:extra-middleware`, in the same order.
             :module-middleware module-middleware
             ;; Checks every route's :permission against the registry.
             :permission-registry (ig/ref :wagoe/permission-registry)
             :router          (ig/ref :wagoe/router)
             :logger          (ig/ref :wagoe/logging)
             :metrics-emitter (ig/ref :wagoe/metrics)
             :tracer          (ig/ref :wagoe/tracing)
             :error-reporter  (ig/ref :wagoe/error-reporting)
             :db-context      (ig/ref :wagoe/db-context)
             :i18n-middleware (ig/ref :wagoe/i18n-http-middleware)
             ;; Installs devtools' request-capture middleware, which is the only
             ;; thing that writes the log the dashboard's Request Inspector
             ;; reads. The handler has always taken this parameter; nothing ever
             ;; supplied it, so the page could not capture in any application
             ;; (BOU-506). Dev only — it holds request/response pairs in memory,
             ;; and the wiring skips it when devtools is off the classpath.
             :request-capture? (= :dev (:wagoe/profile config))}
            http-extras)

     :wagoe/http-server (merge http-cfg
                               {:handler (ig/ref :wagoe/http-handler)
                                :config  http-cfg})}))

(defn- warn-code-enabled-migrations!
  "Neither `migrate up` nor a boot migration sees `:extra-modules`, which live
   in code, so a module enabled only there gets no tables. Say so."
  [active extra-modules]
  (let [code-only (set/difference (modules/enabled-libraries active extra-modules)
                                  (modules/enabled-libraries active))]
    (doseq [lib (sort (filter migrations/ships-migrations? code-only))]
      (log/warn (str "The " lib " module is enabled in code, not in :active, so its migrations"
                     " do not run. Add its key to :active in config.edn.")))))

(defn- with-contributed
  "`ig-config` with what modules contribute under `part` handed to `target` as
   `:contributed`. Throws when `target` is off: nothing would use it."
  [ig-config target part contributed]
  (if (contains? ig-config target)
    (assoc-in ig-config [target :contributed] contributed)
    (throw (ex-info (str "Modules contribute " (vec (keys contributed)) " under " part
                         ", but " target ", which uses them, is not enabled.")
                    {:type :configuration-error :keys (vec (keys contributed))}))))

(defn- with-retention
  "Retention, when `:wagoe/privacy :retention` names a policy: the policies
   modules contributed, a job handler and its recurring schedule. Off
   otherwise; and refused without the jobs module, which would run it.

   Policies are checked here, against every enabled module, rather than at
   init: a service launch drops the modules it does not run, and their
   policies with them."
  [ig-config privacy-settings contributed]
  (let [configured (:retention privacy-settings)
        ;; A misspelled :retention would otherwise switch retention off, silently.
        unknown    (seq (remove #{:retention :retention-schedule} (keys privacy-settings)))]
    (cond
      unknown
      (throw (ex-info (str ":wagoe/privacy does not know " (vec unknown) "; it takes :retention and :retention-schedule.")
                      {:type :configuration-error :keys (vec unknown)}))

      (and (some? configured) (not (map? configured)))
      (throw (ex-info ":wagoe/privacy :retention takes {policy-id {:retain \"P2Y\"}}."
                      {:type :configuration-error :retention configured}))

      (empty? configured)
      ig-config

      (not (contains? ig-config :wagoe/job-registry))
      (throw (ex-info ":wagoe/privacy :retention runs as a recurring job, but :wagoe/jobs is not enabled."
                      {:type :configuration-error :policies (vec (keys configured))}))

      :else
      (do
        (privacy/retention-plan! configured contributed)
        (-> ig-config
            (assoc :wagoe/retention {:policies configured :contributed contributed})
            (update-in [:wagoe/job-registry :handler-maps] (fnil conj []) (ig/ref :wagoe/retention))
            ;; A schedule set under :wagoe/jobs :recurring wins.
            (update-in [:wagoe/job-scheduler :recurring privacy/retention-job-type]
                       #(or % (merge privacy/default-retention-schedule (:retention-schedule privacy-settings)))))))))

(defn system-config
  "The Integrant configuration for `config`.

   Options:
     :extra-modules  modules the application enables in code rather than in
                     config. Their migrations do not run; prefer a key in
                     :active.
     :base-ns        namespace prefix for scaffolded modules, default \"wagoe\"
                     — what `bb scaffold generate` uses unless told otherwise.

   Merge your own components over the result; nothing here is privileged."
  ([config] (system-config config {}))
  ([config {:keys [extra-modules base-ns] :or {extra-modules #{} base-ns "wagoe"}}]
   (let [active     (:active config)
         known      (into core-keys (keys modules/framework-modules))
         ;; Before anything is built: a key that reaches no component is a
         ;; feature the config asks for and does not get, and it used to say
         ;; nothing at all (BOU-477).
         _          (modules/assert-every-key-claimed! active known)
         {:keys [components http auth search data-export retention authorization middleware routes job-handlers]}
         (modules/framework-module-config
          active
          {:config            config
           :validation-config (get-in active [:wagoe/settings :user-validation] {})
           :extra-modules     (into modules/always-on-modules extra-modules)}
          modules/require-wiring!
          requiring-resolve)

         ;; Built before the refs, so they can be filtered against what exists:
         ;; a discovered module with a graph of its own need not have routes.
         {discovered          :components
          discovered-http     :http
          discovered-auth     :auth
          discovered-search   :search
          discovered-export   :data-export
          discovered-retain   :retention
          discovered-authz    :authorization
          discovered-mw       :middleware
          discovered-routes   :routes
          discovered-handlers :job-handlers}
         (modules/discover-module-config
          active known base-ns modules/require-wiring! requiring-resolve)
         auth   (modules/merge-auth auth :discovered discovered-auth)
         search (modules/merge-search search :discovered discovered-search)
         data-export (modules/merge-data-export data-export :discovered discovered-export)
         retention   (modules/merge-retention retention :discovered discovered-retain)]
     (warn-code-enabled-migrations! active extra-modules)
     ;; Said once, so nobody looks for it on :9999 in prod (BOU-588).
     (when-let [profile (:wagoe/profile config)]
       (when (not= :dev (keyword profile))
         (log/info (str "Dev dashboard is off: it runs under the dev profile only, and this is "
                        (name profile) "."))))
     ;; Framework modules first, then the scaffolded ones — the order routes are
     ;; concatenated in and mounted, and middleware is applied in.
     (cond-> (merge (core-components config
                                     (modules/merge-http http :discovered discovered-http)
                                     ;; distinct: a module may name its
                                     ;; `-routes` ref and match the convention
                                     (into [] (distinct)
                                           (concat routes discovered-routes
                                                   (modules/discovered-route-refs active known discovered)))
                                     (into middleware discovered-mw))
                    {:wagoe/data-export {:contributed data-export}
                     :wagoe/permission-registry {:declarations (merge authorization discovered-authz)}}
                    components
                    discovered)
       ;; Handlers reach the registry only when the jobs module is on. Assoc'ing
       ;; them unconditionally would leave refs to handler components in a
       ;; config that has no registry, and a module that contributes handlers
       ;; without jobs enabled would fail the boot on a dangling ref.
       (contains? components :wagoe/job-registry)
       (assoc-in [:wagoe/job-registry :handler-maps] (into (vec job-handlers) discovered-handlers))

       ;; The modules :active switches on: what a boot migration applies, the
       ;; set `migrate up` reads (BOU-579), and, carried on the context, what
       ;; a tenant schema migrates.
       :always
       (assoc-in [:wagoe/db-context :enabled-libraries]
                 (modules/enabled-libraries active))

       (seq auth)
       (with-contributed :wagoe/auth-hooks :auth auth)

       (seq search)
       (with-contributed :wagoe/search :search search)

       :always
       (with-retention (:wagoe/privacy active) retention)))))
