(ns wagoe.config
  "Configuration management using Aero for environment-based config.
   
   This namespace provides configuration loading and Integrant system
   configuration generation for the Wagoe application.
   
   Usage:
     (def config (load-config))
     (def ig-config (ig-config config))
     (integrant.core/init ig-config)"
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            ;; core-system-config emits :wagoe/email unconditionally, so the
            ;; config that references it self-registers the init/halt methods —
            ;; every full-system boot (app + tests) then resolves the key.
            [wagoe.email.shell.module-wiring]
            ;; Same rule, and the reason it is a rule: external-module-config
            ;; emits :wagoe.external/{smtp,imap,twilio} whenever they are
            ;; active, so this namespace must register them. Requiring it from
            ;; wagoe.main instead covered the app and left every other caller of
            ;; ig-config short — dev/repl/user.clj, the platform port tests, and
            ;; the devtools dashboard, which resolves wagoe.config/ig-config at
            ;; runtime. The layer that emits a key registers it.
            ;; i18n is the only one of these that is emitted unconditionally
            ;; (i18n-module-config falls back to defaults), so it is the only
            ;; one that can be required statically.
            ;;
            ;; cache, payments and the external adapters are opt-in, and a
            ;; static require here would recreate the problem BOU-131 removed
            ;; from platform one layer down: loading this namespace would demand
            ;; jars the app may not ship. They are required where the config
            ;; decides they are active — see the module-config fns below.
            [wagoe.i18n.shell.module-wiring]
            [wagoe.user.schema :as user-schema]))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(def ^:private env-aliases
  "Map long-form environment names to the short directory names under resources/conf/."
  {"development" "dev"
   "production"  "prod"
   "acceptance"  "acc"
   "testing"     "test"})

(defn- normalize-env
  "Normalize a WAG_ENV value to one of the short config directory names (dev, test, prod, acc)."
  [env]
  (let [s (some-> env str .trim .toLowerCase)]
    (get env-aliases s s)))

(defn load-config
  "Load configuration from resources/conf/dev/config.edn using Aero.

   Args:
     opts: Optional map with :profile key (defaults to :dev)

   Returns:
     Configuration map with resolved environment variables and profile selection

   Example:
     (load-config)
     (load-config {:profile :test})"
  ([] (load-config {}))
  ([{:keys [profile] :or {profile (keyword (or (System/getenv "WAG_ENV") "dev"))}}]
   (let [profile (keyword (normalize-env (name profile)))
         config-path (str "conf/" (name profile) "/config.edn")
         config-resource (io/resource config-path)]
     (if config-resource
       (do
         (log/info "Loading configuration" {:profile profile :path config-path})
         (assoc (aero/read-config config-resource {:profile profile})
                :wagoe/profile profile))
       (throw (ex-info "Configuration file not found"
                       {:profile profile
                        :path config-path
                        :available-profiles [:dev :test :prod]}))))))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn- active-database-adapter
  "Determine which database adapter is active from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql) or nil"
  [config]
  (let [active-config (:active config)]
    (cond
      (:wagoe/sqlite active-config) :sqlite
      (:wagoe/h2 active-config) :h2
      (:wagoe/postgresql active-config) :postgresql
      (:wagoe/mysql active-config) :mysql
      :else nil)))

(defn db-adapter
  "Extract database adapter keyword from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql)"
  [config]
  (or (active-database-adapter config)
      (throw (ex-info "No active database adapter found in configuration"
                      {:active-keys (keys (:active config))}))))

(defn db-spec
  "Extract database specification from config for the active adapter.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Database spec map appropriate for the adapter
   
   Example:
     {:adapter :sqlite :database-path \"dev-database.db\"}"
  [config]
  (let [adapter (db-adapter config)
        adapter-key (keyword "wagoe" (name adapter))
        slash-key (keyword (str "wagoe/" (name adapter)))
        adapter-config (or (get-in config [:active adapter-key])
                           (get-in config [:active slash-key]))]

    (when-not adapter-config
      (throw (ex-info "No configuration found for active adapter"
                      {:adapter adapter
                       :adapter-key adapter-key
                       :slash-key slash-key})))

    (case adapter
      :sqlite
      {:adapter :sqlite
       :database-path (:db adapter-config)
       :pool (:pool adapter-config)}

      :h2
      {:adapter :h2
       :database-path (if (:memory adapter-config)
                        "mem:wagoe;DB_CLOSE_DELAY=-1"
                        (:db adapter-config))
       :pool (:pool adapter-config)}

      :postgresql
      {:adapter :postgresql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      :mysql
      {:adapter :mysql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      (throw (ex-info "Unsupported database adapter"
                      {:adapter adapter
                       :supported [:sqlite :h2 :postgresql :mysql]})))))

(defn http-config
  "Extract HTTP server configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with :port, :host, :join?, :port-range, and :drain-timeout-ms keys

   :drain-timeout-ms controls graceful shutdown: on stop the server stops
   accepting new connections, lets in-flight requests finish (bounded by this
   timeout, in milliseconds), then halts. 0 or nil disables graceful draining."
  [config]
  (let [http-cfg (get-in config [:active :wagoe/http])]
    {:port (or (:port http-cfg) 3000)
     :host (or (:host http-cfg) "0.0.0.0")
     :join? (or (:join? http-cfg) false)
     :port-range (:port-range http-cfg)
     ;; default only when the key is absent — an explicit nil disables draining
     :drain-timeout-ms (get http-cfg :drain-timeout-ms 30000)}))

(defn app-config
  "Extract application-level configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with application settings"
  [config]
  (get-in config [:active :wagoe/settings] {}))

(defn default-tenant-id
  "Extract default tenant ID for development/testing.
   
   This provides a consistent tenant context for:
   - REPL development and testing
   - CLI operations without explicit tenant specification  
   - Default test fixtures
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     UUID string of default tenant ID
   
   Note:
     Production systems should NOT rely on defaults and must
     always specify tenant-id explicitly in requests."
  [config]
  (get-in config [:active :wagoe/settings :default-tenant-id]))

(defn user-validation-config
  "Extract user validation configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with user validation settings including:
     - :email-domain-allowlist - Set of allowed email domains
     - :password-policy - Password complexity requirements
     - :name-restrictions - Name validation rules
     - :role-restrictions - Role assignment rules
     - :tenant-limits - Per-tenant user limits
     - :cross-field-validation - Cross-field validation rules"
  [config]
  (get-in config [:active :wagoe/settings :user-validation] {}))

(defn logging-config
  "Extract logging configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with logging provider and settings"
  [config]
  (get-in config [:active :wagoe/logging] {:provider :no-op}))

(defn metrics-config
  "Extract metrics configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with metrics provider and settings"
  [config]
  (get-in config [:active :wagoe/metrics] {:provider :no-op}))

(defn tracing-config
  "Extract tracing configuration (defaults to the inert no-op tracer)."
  [config]
  (get-in config [:active :wagoe/tracing] {:provider :no-op}))

(defn error-reporting-config
  "Extract error reporting configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with error reporting provider and settings"
  [config]
  (get-in config [:active :wagoe/error-reporting] {:provider :no-op}))

;; =============================================================================
;; Integrant Configuration Generation
;; =============================================================================

(defn- cache-config
  "Extract cache configuration from active config, or nil if not configured."
  [config]
  (get-in config [:active :wagoe/cache]))

(defn- email-config
  "Extract email sender configuration (defaults to the dev logging sender)."
  [config]
  (get-in config [:active :wagoe/email] {:provider :logging}))

(defn- core-system-config
  "Return core system components (database, observability) independent of modules."
  [config]
  (let [db-cfg (db-spec config)
        logging-cfg (logging-config config)
        metrics-cfg (metrics-config config)
        tracing-cfg (tracing-config config)
        error-reporting-cfg (error-reporting-config config)
        email-cfg (email-config config)
        router-cfg (get-in config [:active :wagoe/router] {:adapter :reitit})
        cache-cfg (cache-config config)
        ;; Required only when the app enables it — see the ns docstring's note
        ;; on why these are not static requires (BOU-131).
        _         (when cache-cfg (require 'wagoe.cache.shell.module-wiring))]
    (cond-> {:wagoe/db-context db-cfg
             :wagoe/logging logging-cfg
             :wagoe/metrics metrics-cfg
             :wagoe/tracing tracing-cfg
             :wagoe/error-reporting error-reporting-cfg
             :wagoe/email email-cfg
             :wagoe/router router-cfg}
      cache-cfg (assoc :wagoe/cache cache-cfg))))

(defn- user-module-config
  "Return Integrant configuration for the user module.
   
   This wiring is specific to the user module and includes:
   - Database schema initialization
   - User and session repositories
   - User service
   - User module routes (structured format: {:api :web :static})
   - Top-level HTTP handler (composes routes from all modules)
   - HTTP server
   
   Future modules should follow this pattern: define a *-module-config function
   that returns a partial Integrant map and merge it into ig-config."
  [config]
  (let [http-cfg (http-config config)
        validation-cfg (user-validation-config config)
        pagination-cfg (get-in config [:active :wagoe/pagination] {:default-limit 20 :max-limit 100})
        admin-enabled? (get-in config [:active :wagoe/admin :enabled?])
        workflow-enabled? (get-in config [:active :wagoe/workflow :enabled?])
        search-enabled? (get-in config [:active :wagoe/search :enabled?])
        cache-enabled? (boolean (cache-config config))
        http-handler-config (cond-> {:config config
                                     :user-routes (ig/ref :wagoe/user-routes)
                                     :tenant-routes (ig/ref :wagoe/tenant-routes)
                                     :membership-routes (ig/ref :wagoe/membership-routes)
                                     :router (ig/ref :wagoe/router)
                                     :logger (ig/ref :wagoe/logging)
                                     :metrics-emitter (ig/ref :wagoe/metrics)
                                     :tracer (ig/ref :wagoe/tracing)
                                     :error-reporter (ig/ref :wagoe/error-reporting)
                                     :user-service (ig/ref :wagoe/user-service)
                                     :tenant-service (ig/ref :wagoe/tenant-service)
                                     :db-context (ig/ref :wagoe/db-context)
                                     :extra-middleware (ig/ref :wagoe/tenant-http-middleware)
                                     :i18n-middleware (ig/ref :wagoe/i18n-http-middleware)}
                              cache-enabled? (assoc :cache (ig/ref :wagoe/cache))
                              admin-enabled?
                              (assoc :admin-routes (ig/ref :wagoe/admin-routes))
                              workflow-enabled?
                              (assoc :workflow-routes (ig/ref :wagoe/workflow-routes))
                              search-enabled?
                              (assoc :search-routes (ig/ref :wagoe/search-routes)))
        http-handler-config (cond-> http-handler-config
                              (= (:wagoe/profile config) :dev)
                              (assoc :request-capture? true))]
    {:wagoe/user-db-schema
     {:ctx (ig/ref :wagoe/db-context)}

     :wagoe/user-repository
     {:ctx (ig/ref :wagoe/db-context)}

     :wagoe/session-repository
     {:ctx (ig/ref :wagoe/db-context)}

     :wagoe/audit-repository
     {:ctx (ig/ref :wagoe/db-context)
      :pagination-config pagination-cfg}

     :wagoe/mfa-service
     {:user-repository (ig/ref :wagoe/user-repository)
      :mfa-config {}} ; Add actual MFA config if needed

     :wagoe/auth-service
     {:user-repository (ig/ref :wagoe/user-repository)
      :session-repository (ig/ref :wagoe/session-repository)
      :mfa-service (ig/ref :wagoe/mfa-service)
      :auth-config {}} ; Add actual auth config if needed

     :wagoe/user-service
     (cond-> {:user-repository (ig/ref :wagoe/user-repository)
              :session-repository (ig/ref :wagoe/session-repository)
              :audit-repository (ig/ref :wagoe/audit-repository)
              :validation-config validation-cfg
              :auth-service (ig/ref :wagoe/auth-service)
              :logger (ig/ref :wagoe/logging)
              :metrics (ig/ref :wagoe/metrics)
              :error-reporter (ig/ref :wagoe/error-reporting)}
       cache-enabled? (assoc :cache (ig/ref :wagoe/cache)))

     :wagoe/user-routes
     {:user-service (ig/ref :wagoe/user-service)
      :mfa-service (ig/ref :wagoe/mfa-service)
      :email-sender (ig/ref :wagoe/email)
      :config config}

     :wagoe/http-handler
     http-handler-config

     :wagoe/http-server
     (merge http-cfg
            {:handler (ig/ref :wagoe/http-handler)
             :config http-cfg})}))

(defn- admin-module-config
  "Return Integrant configuration for the admin module.

   This wiring enables the auto-generated admin CRUD interface:
   - Schema provider for database introspection
   - Admin service for CRUD operations
   - Admin routes for web UI

   The admin module is only active if :wagoe/admin is present
   in the active config with :enabled? true."
  [config]
  (let [admin-cfg (get-in config [:active :wagoe/admin])]
    (when (and admin-cfg (:enabled? admin-cfg))
      {:wagoe/admin-schema-provider
       {:db-ctx (ig/ref :wagoe/db-context)
        :config admin-cfg
        :malli-schemas {:users user-schema/User}}

       :wagoe/admin-service
       {:db-ctx (ig/ref :wagoe/db-context)
        :schema-provider (ig/ref :wagoe/admin-schema-provider)
        :logger (ig/ref :wagoe/logging)
        :error-reporter (ig/ref :wagoe/error-reporting)
        :config admin-cfg}

       :wagoe/admin-routes
       {:admin-service (ig/ref :wagoe/admin-service)
        :schema-provider (ig/ref :wagoe/admin-schema-provider)
        :user-service (ig/ref :wagoe/user-service)
        :config admin-cfg}})))

(defn- tenant-module-config
  "Return Integrant configuration for the tenant module.
   
   This wiring enables multi-tenancy support:
   - Tenant repository for tenant persistence
   - Tenant service for business logic
   - Tenant routes for CRUD and provisioning API
   
   The tenant module provides:
   - Tenant CRUD operations (create, read, update, delete)
   - Tenant provisioning (schema creation, data seeding)
   - Tenant activation/suspension
   
   Multi-tenant middleware is integrated separately in the HTTP handler."
  [config]
  (let [validation-cfg (user-validation-config config)]
    {:wagoe/tenant-db-schema
     {:ctx (ig/ref :wagoe/db-context)}

     :wagoe/tenant-repository
     {:ctx (ig/ref :wagoe/db-context)
      :logger (ig/ref :wagoe/logging)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/membership-repository
     {:ctx (ig/ref :wagoe/db-context)
      :logger (ig/ref :wagoe/logging)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/invite-repository
     {:ctx (ig/ref :wagoe/db-context)
      :logger (ig/ref :wagoe/logging)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/tenant-service
     {:tenant-repository (ig/ref :wagoe/tenant-repository)
      :validation-config validation-cfg
      :logger (ig/ref :wagoe/logging)
      :metrics-emitter (ig/ref :wagoe/metrics)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/membership-service
     {:repository (ig/ref :wagoe/membership-repository)
      :logger (ig/ref :wagoe/logging)
      :metrics-emitter (ig/ref :wagoe/metrics)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/invite-service
     {:repository (ig/ref :wagoe/invite-repository)
      :membership-repository (ig/ref :wagoe/membership-repository)
      :logger (ig/ref :wagoe/logging)
      :metrics-emitter (ig/ref :wagoe/metrics)
      :error-reporter (ig/ref :wagoe/error-reporting)}

     :wagoe/tenant-routes
     {:tenant-service (ig/ref :wagoe/tenant-service)
      :db-context (ig/ref :wagoe/db-context)
      :config config}

     :wagoe/membership-routes
     {:service (ig/ref :wagoe/membership-service)
      :config config}

     ;; Tenant HTTP middleware seq, injected into platform's http-handler via
     ;; :extra-middleware (BOU-200). Built in the tenant lib so platform's
     ;; http-handler does not require the tenant lib.
     :wagoe/tenant-http-middleware
     {:tenant-service (ig/ref :wagoe/tenant-service)
      :membership-service (ig/ref :wagoe/membership-service)
      :db-context (ig/ref :wagoe/db-context)}}))

(defn- workflow-module-config
  "Return Integrant configuration for the workflow module.

   Wires the workflow state machine engine, persistence store, and admin UI routes.
   Enabled when :wagoe/workflow {:enabled? true} is present in the active config."
  [config]
  (let [wf-cfg (get-in config [:active :wagoe/workflow])]
    (when (and wf-cfg (:enabled? wf-cfg))
      {:wagoe/workflow
       {:db-ctx        (ig/ref :wagoe/db-context)
        :guard-registry {}}

       :wagoe/workflow-routes
       {:workflow-service (ig/ref :wagoe/workflow)
        :user-service     (ig/ref :wagoe/user-service)}})))

(defn- search-module-config
  "Return Integrant configuration for the search module.

   Wires the full-text search engine, persistence store, and admin UI routes.
   Enabled when :wagoe/search {:enabled? true} is present in the active config."
  [config]
  (let [search-cfg (get-in config [:active :wagoe/search])]
    (when (and search-cfg (:enabled? search-cfg))
      {:wagoe/search
       {:db-ctx (ig/ref :wagoe/db-context)}

       :wagoe/search-routes
       {:search-service (ig/ref :wagoe/search)}})))

(defn- external-module-config
  "Extract external service adapter configs from active config.

   Each of the four adapters is opt-in: move the key from :inactive to :active
   in config.edn to enable it. Returns only the keys that are present in :active."
  [config]
  (let [active (:active config)]
    (when (some active [:wagoe.external/smtp :wagoe.external/imap :wagoe.external/twilio])
      (require 'wagoe.external.shell.module-wiring))
    (cond-> {}
      (:wagoe.external/smtp   active) (assoc :wagoe.external/smtp   (:wagoe.external/smtp   active))
      (:wagoe.external/imap   active) (assoc :wagoe.external/imap   (:wagoe.external/imap   active))
      (:wagoe.external/twilio active) (assoc :wagoe.external/twilio (:wagoe.external/twilio active)))))

(defn- payments-module-config
  "Return Integrant configuration for the payments module.

   Wires the :wagoe/payment-provider component with the configured PSP adapter
   (mock, mollie, or stripe). Enabled when :wagoe/payment-provider is present
   in the active config."
  [config]
  (let [payments-cfg (get-in config [:active :wagoe/payment-provider])]
    (when payments-cfg
      (require 'wagoe.payments.shell.module-wiring)
      {:wagoe/payment-provider payments-cfg})))

(defn- i18n-module-config
  "Return Integrant configuration for the i18n module.

   Reads :wagoe/i18n from active config. Falls back to sensible defaults
   (English-only, classpath catalogue) when the key is absent."
  [config]
  (let [i18n-cfg (get-in config [:active :wagoe/i18n]
                         {:catalogue-path "wagoe/i18n/translations"
                          :default-locale :en})]
    {:wagoe/i18n i18n-cfg
     ;; Built in the i18n lib and injected into the HTTP handler, so platform
     ;; does not require wagoe.i18n.shell.middleware (BOU-131). Same shape as
     ;; :wagoe/tenant-http-middleware (BOU-200).
     :wagoe/i18n-http-middleware {:i18n (ig/ref :wagoe/i18n)}}))

(defn- dashboard-module-config
  "Dashboard config — only active in dev profile.
   Uses requiring-resolve to load the init-key defmethod lazily,
   so non-REPL dev boots (wagoe.main) don't fail when the
   devtools namespace isn't pre-loaded."
  [config]
  (when (= (:wagoe/profile config) :dev)
    (let [dashboard-cfg (get-in config [:active :wagoe/dashboard])]
      (when dashboard-cfg
        ;; Ensure the init-key/halt-key! defmethods are registered.
        ;; Wrapped in try/catch because devtools may not be on the classpath
        ;; in non-REPL dev boots (e.g. wagoe.main or WAG_ENV=development).
        (try
          (require 'wagoe.devtools.shell.dashboard.server)
          {:wagoe/dashboard
           {:port         (:port dashboard-cfg 9999)
            :http-handler (ig/ref :wagoe/http-handler)
            :http-server  (ig/ref :wagoe/http-server)
            :db-context   (ig/ref :wagoe/db-context)
            :router       (ig/ref :wagoe/router)
            :logging      (ig/ref :wagoe/logging)}}
          (catch Exception _
            nil))))))

(defn- events-module-config
  "Integrant configuration for the event bus.

   Emitted only when `:wagoe/events` is active, and the wiring namespace is
   required at the same moment (BOU-131): a static require would make every
   consumer of this namespace ship the events jar and its Jedis dependency
   whether or not they use a bus."
  [config]
  (when-let [events-cfg (get-in config [:active :wagoe/events])]
    (require 'wagoe.events.shell.module-wiring)
    {:wagoe/events events-cfg}))

(defn ig-config
  "Generate Integrant configuration map from loaded config.

   The configuration is composed from:
   - Core system components (database, observability)
   - Module-specific components (:user, :admin, :tenant, :workflow)
   - External service adapters (:wagoe.external/* — opt-in via config.edn)

   Future modules can be added by:
   1. Creating a *-module-config function (like user-module-config)
   2. Merging it into the final config map
   3. Ensuring the module's wiring namespace is required

   The returned map is data. Initialising it needs the `init-key` methods, and
   most of them are registered by the namespace that starts the system, not by
   this one — `ig/init` on the result of `(ig-config (load-config))` alone fails
   with \"No method in multimethod 'init-key'\".

   Two rules, and the difference matters:

   * **Conditional keys register themselves.** A module-config fn that emits a
     key only when it is configured requires that module's wiring at the same
     moment — see `events-module-config` below. Keeping those requires at the
     top would force every consumer of this namespace to ship jars it may not
     use (BOU-131).
   * **Unconditional keys are the entry point's job.** `wagoe.main`,
     `dev/user.clj` and the generated `config.clj` require the wiring for what
     they always emit.

   Args:
     config: Configuration map from load-config

   Returns:
     Integrant config map — data, not a started system

   Example:
     (require 'wagoe.main)           ; registers the unconditional init-keys
     (def ig-cfg (ig-config (load-config)))
     (integrant.core/init ig-cfg)"
  [config]
  (merge (core-system-config config)
         (i18n-module-config config)
         (user-module-config config)
         (tenant-module-config config)
         (admin-module-config config)
         (workflow-module-config config)
         (search-module-config config)
         (external-module-config config)
         (payments-module-config config)
         (events-module-config config)
         (dashboard-module-config config)))

;; =============================================================================
;; Service catalogue (BOU-91)
;; =============================================================================

(def default-service-catalogue
  "Which Integrant keys belong to which module, for `service` launch mode.

   Only the framework's own modules. A key listed nowhere here is treated as
   platform and runs in every service — see
   `wagoe.platform.core.system-selection/core-keys` — so the failure mode of an
   omission is a service that is larger than it needs to be, not one missing a
   component.

   `:rpc` says which protocol a service offers to the rest of the deployment.
   Without it a module can be booted alone but nothing can call it, which is
   the half of BOU-90 that needed this ticket. The protocol is a symbol so the
   catalogue stays plain data: it is resolved when the endpoint starts.

   An application overrides or extends this with `:wagoe/services` in its
   config.edn, and `service-catalogue` merges the two. Kept in code rather than
   copied into the four profile files because a copy in each is a copy to
   forget: the keys change when modules change, and nothing would notice."
  {:user     {:keys [:wagoe/user-db-schema :wagoe/user-repository
                     :wagoe/session-repository :wagoe/audit-repository
                     :wagoe/mfa-service :wagoe/auth-service
                     :wagoe/user-service :wagoe/user-routes]
              ;; What this module offers the rest of a split deployment. Only
              ;; served when it is run as a service *and* :wagoe/rpc is
              ;; configured — a `server` boot never starts the listener.
              :rpc  {:protocol  'wagoe.user.ports/IUserService
                     :component :wagoe/user-service}}

   :tenant   {:keys [:wagoe/tenant-db-schema :wagoe/tenant-repository
                     :wagoe/tenant-service :wagoe/tenant-routes
                     :wagoe/tenant-http-middleware
                     :wagoe/membership-repository :wagoe/membership-service
                     :wagoe/membership-routes
                     :wagoe/invite-repository :wagoe/invite-service]}

   :admin    {:keys [:wagoe/admin-schema-provider :wagoe/admin-service
                     :wagoe/admin-routes]}

   ;; :wagoe/workflow and :wagoe/search, not :wagoe/*-service — the service
   ;; component carries the bare module name here, and the invented names left
   ;; the real components unclaimed, which meant `core-keys` counted them as
   ;; platform and ran them in every service. `main-test` now asserts every key
   ;; the config emits is claimed or listed as platform.
   :workflow {:keys [:wagoe/workflow :wagoe/workflow-routes]}

   :search   {:keys [:wagoe/search :wagoe/search-routes]}

   :payments {:keys [:wagoe/payment-provider]
              :rpc  {:protocol  'wagoe.payments.ports/IPaymentProvider
                     :component :wagoe/payment-provider}}})

(defn service-catalogue
  "The service catalogue for `config`: the framework's, plus the app's own.

   An entry in config.edn replaces the default one of the same name outright
   rather than merging into it, so an application that has taken a module apart
   is not left with the framework's idea of its keys.

   Read from `:active` — where everything else in these files lives — and, for
   an application that put it at the top level next to `:test/reset-endpoint-
   enabled?`, from there too. Accepting only one placement meant a catalogue
   written in the obvious spot was silently ignored, and the symptom was
   `service my-module` reporting the module as unknown, which reads as a typo."
  [config]
  (merge default-service-catalogue
         (:wagoe/services config)
         (get-in config [:active :wagoe/services])))

(defn rpc-config
  "Settings for the RPC endpoint a service exposes, or nil if none configured."
  [config]
  (get-in config [:active :wagoe/rpc]))

;; =============================================================================
;; REPL Utilities
;; =============================================================================

(comment
  ;; Load configuration
  (def config (load-config))

  ;; Check active adapter
  (db-adapter config)

  ;; Extract database spec
  (db-spec config)

  ;; HTTP config
  (http-config config)

  ;; Get default tenant ID for development
  (default-tenant-id config)
  ;; => "00000000-0000-0000-0000-000000000001" (or value from DEFAULT_TENANT_ID env var)

  ;; Use default tenant ID in REPL development
  (require '[wagoe.core.utils.type-conversion :as tc])
  (def tenant-id (tc/string->uuid (default-tenant-id config)))
  ;; => #uuid "00000000-0000-0000-0000-000000000001"

  ;; Generate Integrant config
  (def ig-cfg (ig-config config))

  ;; Initialize system
  (def system (ig/init ig-cfg))

  ;; Halt system
  (ig/halt! system)
  ...)

