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
     (system-config (config/load-config) {:extra-modules #{:wagoe/user}})"
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.modules :as modules]
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
   — tenant's `:extra-middleware`, admin's `:admin-routes` ref — because the
   handler takes them as named keys rather than discovering them."
  [config http-extras module-routes]
  (let [active   (:active config)
        http-cfg (config/http-config config)]
    {:wagoe/settings        (:wagoe/settings active)
     :wagoe/db-context      (config/db-spec config)
     :wagoe/logging         (config/logging-config config)
     :wagoe/metrics         (config/metrics-config config)
     :wagoe/tracing         (config/tracing-config config)
     :wagoe/error-reporting (config/error-reporting-config config)
     :wagoe/router          (get active :wagoe/router {:adapter :reitit :coercion :malli :middleware []})

     :wagoe/http-handler
     (merge {:config          config
             ;; Routes of the scaffolded modules. The handler cannot name a
             ;; generated module, so they reach it as a collection; without
             ;; this a scaffolded module initialises and serves nothing.
             :module-routes   module-routes
             :router          (ig/ref :wagoe/router)
             :logger          (ig/ref :wagoe/logging)
             :metrics-emitter (ig/ref :wagoe/metrics)
             :tracer          (ig/ref :wagoe/tracing)
             :error-reporter  (ig/ref :wagoe/error-reporting)
             :db-context      (ig/ref :wagoe/db-context)
             :i18n-middleware (ig/ref :wagoe/i18n-http-middleware)}
            http-extras)

     :wagoe/http-server (merge http-cfg
                               {:handler (ig/ref :wagoe/http-handler)
                                :config  http-cfg})}))

(defn system-config
  "The Integrant configuration for `config`.

   Options:
     :extra-modules  modules the application enables in code rather than in
                     config. `wagoe new` writes `#{:wagoe/user}` here, or an
                     empty set for `--no-user`.
     :base-ns        namespace prefix for scaffolded modules, default \"wagoe\"
                     — what `bb scaffold generate` uses unless told otherwise.

   Merge your own components over the result; nothing here is privileged."
  ([config] (system-config config {}))
  ([config {:keys [extra-modules base-ns] :or {extra-modules #{} base-ns "wagoe"}}]
   (let [active     (:active config)
         known      (into core-keys (keys modules/framework-modules))
         {:keys [components http]}
         (modules/framework-module-config
          active
          {:config            config
           :validation-config (get-in active [:wagoe/settings :user-validation] {})
           :extra-modules     (into modules/always-on-modules extra-modules)}
          modules/require-wiring!
          requiring-resolve)]
     (merge (core-components config http (modules/discovered-route-refs active known))
            components
            (modules/discover-module-config active known base-ns modules/require-wiring!)))))
