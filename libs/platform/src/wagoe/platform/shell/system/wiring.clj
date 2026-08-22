(ns wagoe.platform.shell.system.wiring
  "Integrant system lifecycle management for Wagoe application.
   
   This namespace defines init-key and halt-key! multimethods for all
   system components, providing proper lifecycle management and dependency
   injection for the entire application.
   
   Components:
   - :wagoe/db-context - Database connection pool and adapter
   - :wagoe/user-repository - User data persistence
   - :wagoe/session-repository - Session data persistence
   - :wagoe/user-service - User business logic orchestration
   - :wagoe/logging - Structured logging and audit trails
   - :wagoe/metrics - Application and business metrics collection
   - :wagoe/error-reporting - Error tracking and alerting
   - :wagoe/http-handler - HTTP request routing and handling
   - :wagoe/http-server - Jetty HTTP server
   
   Usage:
     (require '[wagoe.config :as config])
     (require '[wagoe.system-config :as sys-config])   ; your application's
     (require '[integrant.core :as ig])
     (def cfg (sys-config/ig-config (config/load-config)))
     (def system (ig/init cfg))
     (ig/halt! system)"
  (:require [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.metrics.shell.adapters.no-op :as metrics-no-op]
            [wagoe.observability.metrics.shell.adapters.datadog :as metrics-datadog]
            [wagoe.observability.metrics.shell.adapters.prometheus :as metrics-prometheus]
            [wagoe.observability.metrics.shell.adapters.otlp :as metrics-otlp]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.observability.tracing.shell.adapters.no-op :as tracing-no-op]
            [wagoe.observability.tracing.shell.adapters.logging :as tracing-logging]
            [wagoe.observability.tracing.shell.adapters.otlp :as tracing-otlp]
            [wagoe.observability.logging.shell.adapters.stdout :as logging-stdout]
            [wagoe.observability.logging.shell.adapters.slf4j :as logging-slf4j]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.errors.shell.adapters.sentry :as error-reporting-sentry]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.platform.shell.http.interceptors :as http-interceptors]
            [wagoe.platform.shell.http.versioning :as http-versioning]
            [wagoe.platform.shell.utils.port-manager :as port-manager]
            [wagoe.platform.shell.rpc.server :as rpc-server]
            ;; NOTE: no module-wiring is required here any more (BOU-131). Each
            ;; was a static require, so every consumer of platform had to ship
            ;; every module's jar whether or not it used it — a missing one is a
            ;; FileNotFoundException at load. zzp-guard could not drop
            ;; wagoe-payments after deleting all its payment code for exactly
            ;; this reason.
            ;;
            ;; The rule is the one the rest of the codebase already follows: the
            ;; layer that emits an Integrant key registers it. cache, payments
            ;; and i18n are emitted by wagoe.config, which now requires them.
            ;; realtime was emitted by nothing here at all.
            ;; NOTE: user module-wiring is loaded by the application entry point
            ;; (wagoe.main), NOT here — platform must not depend on the user
            ;; feature lib (BOU-171: dissolves the platform<->user cycle).
            ;; NOTE: feature-module module-wiring (admin/workflow/search/tenant/user)
            ;; is loaded by the application entry point (wagoe.main), NOT here —
            ;; platform must not statically depend on the feature libs
            ;; (BOU-192/BOU-198: dissolves the platform<->feature cycles).
            ;; NOTE: external module-wiring is loaded by the application entry
            ;; point (wagoe.main), NOT here — platform required it without
            ;; declaring it in deps.edn, the last entry in check:deps'
            ;; allowlist. Loading it here also made the SMTP/IMAP/Twilio
            ;; adapters a mandatory dependency of the framework's HTTP layer,
            ;; which is the same coupling BOU-171/192/198 removed for user and
            ;; the feature modules.
            [cheshire.core]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]))

;; =============================================================================
;; Application Settings
;; =============================================================================

;; :wagoe/settings is the application's own settings block, and components take
;; a ref to it. The init-key used to be written into each application's
;; namespace by `wagoe new`; this repository never wrote one, so emitting the
;; key here failed the boot with "No such namespace: wagoe" (BOU-326).
(defmethod ig/init-key :wagoe/settings [_ config] config)

(defmethod ig/halt-key! :wagoe/settings [_ _config] nil)

;; =============================================================================
;; Database Context
;; =============================================================================

(defmethod ig/init-key :wagoe/db-context
  [_ config]
  (log/info "Initializing database context" {:adapter (:adapter config)})
  (let [ctx (db-factory/db-context config)]
    (log/info "Database context initialized successfully"
              {:adapter (:adapter config)})
    ctx))

(defmethod ig/halt-key! :wagoe/db-context
  [_ ctx]
  (log/info "Halting database context")
  (db-factory/close-db-context! ctx)
  (log/info "Database context halted"))

;; =============================================================================
;; User and Session Repositories
;;
;; NOTE: The actual Integrant wiring for :wagoe/user-repository and
;; :wagoe/session-repository lives in
;; `wagoe.user.shell.module-wiring` to avoid system wiring depending
;; directly on user shell namespaces.
;; =============================================================================

;; =============================================================================
;; HTTP Router Adapter
;; =============================================================================

(defmethod ig/init-key :wagoe/router
  [_ {:keys [adapter]}]
  (log/info "Initializing HTTP router adapter" {:adapter adapter})
  (let [router (case adapter
                 :reitit (reitit-router/create-reitit-router)
                 ;; Future routers:
                 ;; :pedestal (pedestal-router/create-pedestal-router)
                 ;; :compojure (compojure-router/create-compojure-router)
                 (do
                   (log/warn "Unknown router adapter, falling back to reitit"
                             {:adapter adapter})
                   (reitit-router/create-reitit-router)))]
    (log/info "HTTP router adapter initialized" {:adapter adapter})
    router))

(defmethod ig/halt-key! :wagoe/router
  [_ _router]
  (log/info "HTTP router adapter halted"))

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(def ^:private default-web-prefix
  "Where a module's `:web` routes mount when it does not say.

   `/web` is the ordinary answer; admin, workflow and search mount under
   `/web/admin` and say so themselves. Platform used to hold that fact for all
   six modules, which is why a seventh could not contribute routes without
   editing this file (BOU-330)."
  "/web")

(defn- mount-web-routes
  "Prefix a module's web routes and settle their doc visibility.

   Web UI routes do not belong in the API documentation, so `:no-doc` defaults
   to true; a module that wants one listed says so.

   Both shapes, while modules migrate to Reitit data (ADR-037). A Reitit route
   is `[path data & children]` and the prefix goes on the path; a normalized one
   is a map whose `:meta` merges into the route root."
  [prefix routes]
  (mapv (fn [route]
          (if (vector? route)
            (let [[path data & children] route]
              (into [(str prefix path)
                     (if (map? data) (merge {:no-doc true} data) data)]
                    children))
            (let [{:keys [path meta]} route]
              (-> route
                  (dissoc :meta)
                  (merge {:no-doc true} meta)
                  (assoc :path (str prefix path))))))
        routes))

(defn module-route-contributions
  "Fold every module's routes into {:static [..] :web [..] :api [..]}.

   A contribution is what a module's `:wagoe/<name>-routes` component returns:

     {:static     [..]   mounted as-is
      :web        [..]   mounted under :web-prefix
      :api        [..]   versioned by the caller
      :web-prefix \"/web/admin\"   optional, defaults to \"/web\"
      :extra-web  [..]   already-pathed routes, appended after the prefixed ones}

   `:extra-web` exists for admin's slash redirect: `/web/admin/` is the module's
   `/` route once prefixed, and the un-slashed `/web/admin` that people type
   matched nothing and 404'd on a feature that works (BOU-229). That route is
   admin's, is already absolutely pathed, and must not be prefixed again.

   Order is the caller's: contributions are folded in the order given, and
   within a contribution static comes before web. Public so a test can drive it
   without an Integrant system."
  [contributions]
  (reduce (fn [acc {:keys [static web api web-prefix extra-web]}]
            (-> acc
                (update :static into (or static []))
                (update :web into (mount-web-routes (or web-prefix default-web-prefix)
                                                    (or web [])))
                (update :web into (or extra-web []))
                (update :api into (or api []))))
          {:static [] :web [] :api []}
          (remove nil? contributions)))

(defn- build-test-reset-routes
  "Return a one-element vector containing the POST /test/reset reitit route
   when :test/reset-endpoint-enabled? is true in `config`, otherwise nil.

   Loudly throws at startup if the flag is enabled in a non-test/non-dev
   profile — this is a production safety net, not graceful degradation.

   The handler is looked up via `requiring-resolve` so that libs/platform
   does NOT have a compile-time dependency on the monolith test-support
   namespace (which would reverse the library dependency direction)."
  [{:keys [config user-service tenant-service db-context]}]
  (when (true? (:test/reset-endpoint-enabled? config))
    (let [profile (:wagoe/profile config)]
      (when-not (contains? #{:test :dev} profile)
        (throw (ex-info "test/reset endpoint cannot be enabled outside :test/:dev"
                        {:type    :configuration-error
                         :profile profile
                         :flag    :test/reset-endpoint-enabled?}))))
    ;; Resolved rather than required: wagoe.test-support lives in the
    ;; application, not in any library, so platform cannot declare it and a
    ;; downstream project has nothing to resolve. That is only survivable
    ;; because this whole branch is behind a flag that throws outside :test and
    ;; :dev. It is the same shape wagoe.config had before BOU-306, and the
    ;; honest fix is the same: a small published library, or the application
    ;; passing its own handler in.
    (let [make-handler (requiring-resolve 'wagoe.test-support.shell.handler/make-reset-handler)
          truncate!    (requiring-resolve 'wagoe.test-support.shell.reset/truncate-all!)
          seed!        (requiring-resolve 'wagoe.test-support.shell.reset/seed-baseline!)
          datasource   (:datasource db-context)
          deps         {:user-service   user-service
                        :tenant-service tenant-service
                        :datasource     datasource
                        :truncate!      (fn [_] (truncate! datasource))
                        :seed!          (fn [d] (seed! d))}
          handler      (make-handler deps)]
      (log/warn "Mounting /test/reset endpoint — test-profile only, DO NOT enable in production")
      [["/test/reset"
        {:post {:handler handler
                :summary "Playwright e2e reset + seed endpoint"
                :no-doc  true
                :skip-interceptors? true}}]])))

(defn- interceptor-services
  "What the HTTP interceptors are handed.

   The standard HTTP metrics are registered once here rather than per request:
   some adapters reset a counter when it is re-registered."
  [{:keys [logger metrics-emitter tracer error-reporter error-enricher cache config
           csrf-config rate-limit-config]}]
  (cond-> {:logger          logger
           :metrics-emitter metrics-emitter
           :metrics-handles (http-interceptors/register-http-metrics! metrics-emitter)
           :tracer          tracer
           :error-reporter  error-reporter
           :csrf            csrf-config
           :rate-limit      rate-limit-config
           :cache           cache}
    ;; Optional, dev-only: a fn from exception to {:code :category :fix},
    ;; supplied by devtools via :wagoe/dev-error-enricher. Injected rather than
    ;; resolved here — platform must not know the name of an optional library
    ;; (BOU-131, BOU-321). Absent rather than nil, so an app that wired none is
    ;; byte-identical to one built before this existed.
    error-enricher (assoc :error-enricher error-enricher)

    ;; The profile the app booted with. Interceptors decide what an error may
    ;; say by environment, and they were reading only WAG_ENV and a `:config`
    ;; key nothing put here — so a project that sets no WAG_ENV (every generated
    ;; one) read as development wherever it ran. The config knows; pass it
    ;; (BOU-321). Absent rather than nil when there is no profile, so the
    ;; detector falls through to WAG_ENV as it always did.
    (:wagoe/profile config) (assoc :environment (name (:wagoe/profile config)))))

(defn- request-middleware
  "The middleware every request passes through, outermost first.

   `extra-middleware` is a seq of `(fn [handler] ...)` supplied by the
   application — tenant resolution arrives this way. Platform provides the
   pipeline; feature libraries inject their middleware, which is why platform
   does not require the tenant library (BOU-200)."
  [{:keys [extra-middleware i18n i18n-middleware]}]
  (let [;; `:i18n` is still accepted. An app that upgrades platform without
        ;; regenerating its config still passes the component under that key —
        ;; the documented input until now — and dropping it would take the
        ;; translation middleware out of the pipeline without erroring:
        ;; handlers read `(get request :i18n/t identity)`, so every marker would
        ;; render as its own keyword and the page would just look wrong. The
        ;; resolve is dynamic and only runs when an app supplied the component,
        ;; which means it already ships the lib, so this reintroduces no static
        ;; dependency.
        i18n-mw (or i18n-middleware
                    (when i18n
                      (log/warn (str "Deprecated: :wagoe/http-handler received :i18n. "
                                     "Wire :wagoe/i18n-http-middleware from the i18n "
                                     "module and pass it as :i18n-middleware instead "
                                     "(BOU-131). Building the middleware here for now."))
                      (let [wrap (requiring-resolve 'wagoe.i18n.shell.middleware/wrap-i18n)]
                        (fn [handler] (wrap handler i18n)))))]
    (concat
     (or extra-middleware [])
     (when i18n-mw [i18n-mw])
     ;; HTML forms can only GET and POST, so a POST carrying _method=delete is
     ;; how a form issues a DELETE. Applied innermost, after the app's own.
     [(fn [handler]
        (fn [request]
          (if (= :post (:request-method request))
            (let [method (or (get-in request [:form-params "_method"])
                             (get-in request [:params "_method"]))]
              (if method
                (handler (assoc request :request-method (keyword (str/lower-case method))))
                (handler request)))
            (handler request))))])))

(defn- security-config
  "CSRF and rate-limit settings, and the two guards that refuse to boot without
   them.

   Both are opt-in: a framework upgrade must not start 403-ing or 429-ing an
   application that was working. What is not optional is failing loudly when the
   configuration cannot do what it claims — an enabled CSRF interceptor with no
   secret fails OPEN, and rate limiting without a shared cache is a per-process
   counter wearing a global limit's name.

   Lifted out of `:wagoe/http-handler` whole, guards included, so that init-key
   is about assembling a handler and this is about refusing to build a unsafe
   one (BOU-330)."
  [{:keys [config cache]}]
  (let [
        ;; CSRF config consumed by http-csrf-protection interceptor.
        ;; Opt-in: disabled by default so a framework upgrade cannot 403 consumers
        ;; that don't yet emit tokens. Each app enables it (after emitting tokens in
        ;; its /web forms) via :wagoe/http :security :csrf :enabled? true. Secret
        ;; prefers a dedicated CSRF_SECRET, falling back to JWT_SECRET so existing
        ;; deployments keep working while allowing key separation. Config overrides.
        csrf-config (merge {:enabled?     false
                            :secret       (or (System/getenv "CSRF_SECRET")
                                              (System/getenv "JWT_SECRET"))
                            :exempt-paths []}
                           (get-in config [:active :wagoe/http :security :csrf]))
        ;; Fail-loud guard: an enabled CSRF interceptor with no secret cannot validate
        ;; (the interceptor short-circuits to a no-op — fail OPEN). Refuse to boot rather
        ;; than start an app that silently accepts unvalidated state-changing requests.
        _ (when (and (:enabled? csrf-config) (str/blank? (:secret csrf-config)))
            (throw (ex-info (str "CSRF protection is enabled but no secret is configured. "
                                 "An enabled interceptor with a blank secret fails OPEN — "
                                 "state-changing requests would NOT be CSRF-validated. "
                                 "Set CSRF_SECRET or JWT_SECRET, or :wagoe/http :security :csrf :secret.")
                            {:type :configuration-error :csrf/enabled? true :csrf/secret-present? false})))

        ;; Rate-limit config consumed by the http-rate-limit-protection interceptor.
        ;; Opt-in like CSRF: disabled by default so a framework upgrade cannot start
        ;; 429-ing consumers. When enabled with a cache present, limiting is shared
        ;; across replicas via Redis; without a cache it falls back to a per-process
        ;; counter (single-node only — NOT a global limit). Config keys override.
        rate-limit-config (merge {:enabled?  false
                                  :limit     100
                                  :window-ms 60000}
                                 (get-in config [:active :wagoe/http :rate-limit]))
        ;; Fail-loud in :prod, warn elsewhere. In production a per-process fallback
        ;; is almost never intended (multi-replica → effective limit is limit x N,
        ;; i.e. not a real limit), so refuse to boot rather than provide a false
        ;; sense of protection. dev/test/acc are single-node by default, so a warn
        ;; is enough there.
        _ (when (and (:enabled? rate-limit-config) (nil? cache))
            (if (= :prod (:wagoe/profile config))
              (throw (ex-info (str "Rate limiting is enabled in the :prod profile but no "
                                   ":wagoe/cache (Redis) is active. Without a shared cache the "
                                   "limiter falls back to a per-process counter — across replicas the "
                                   "effective global limit is limit x N, so it is NOT a real limit. "
                                   "Activate :wagoe/cache (Redis), or disable rate limiting "
                                   "(HTTP_RATE_LIMIT_ENABLED=false).")
                              {:type :configuration-error
                               :rate-limit/enabled? true :cache/present? false :profile :prod}))
              (log/warn (str "Rate limiting is enabled but no cache is configured — "
                             "falling back to a per-process counter. This is correct on a "
                             "single node only; across replicas each instance counts "
                             "independently, so the effective global limit is limit x N. "
                             "Configure :wagoe/cache (Redis) for a shared limit."))))

        ]
    {:csrf csrf-config :rate-limit rate-limit-config}))

(defn- platform-routes
  "The endpoints every Wagoe application serves, whoever it is.

   Health, readiness, liveness, the Prometheus scrape and the `/` redirect.
   `:skip-interceptors?` on all of them: a liveness probe that needs a session
   is not a liveness probe.

   Reitit route data, used as-is — handlers are vars and functions rather than
   quoted symbols resolved at boot, so a typo is a lint error (ADR-037)."
  [{:keys [config db-context cache metrics-emitter]}]
  (require 'wagoe.platform.shell.interfaces.http.common)
  (let [health-fn      (ns-resolve 'wagoe.platform.shell.interfaces.http.common 'health-check-handler)
        readiness-fn   (ns-resolve 'wagoe.platform.shell.interfaces.http.common 'readiness-handler)
        health-handler (health-fn
                        (get-in config [:active :wagoe/settings :name] "wagoe")
                        (get-in config [:active :wagoe/settings :version] "unknown")
                        nil)
        ready-handler  (readiness-fn db-context cache)
        internal       {:no-doc true :skip-interceptors? true}]
    [["/" {:get (merge internal
                       {:handler (fn [request]
                                   ;; Signed in or not decides where "/" goes.
                                   (if (:user request)
                                     {:status 302 :headers {"Location" "/web/users"}}
                                     {:status 302 :headers {"Location" "/web/login"}}))
                        :summary "Home page (redirects to login or users)"})}]
     ["/health" {:get (merge internal {:handler health-handler
                                       :summary "Health check endpoint"})}]
     ["/health/ready" {:get (merge internal {:handler ready-handler
                                             :summary "Readiness check with dependency health"})}]
     ["/health/live" {:get (merge internal
                                  {:handler (fn [_] {:status 200
                                                     :headers {"Content-Type" "application/json"}
                                                     :body (cheshire.core/generate-string {:status "alive"})})
                                   :summary "Liveness check"})}]
     ;; Serves the text exposition format from the active metrics component; the
     ;; :no-op and :datadog-statsd providers return an empty body, :prometheus
     ;; returns real metrics.
     ["/metrics" {:get (merge internal
                              {:handler (fn [_]
                                          {:status  200
                                           :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
                                           :body    (if (satisfies? metrics-ports/IMetricsExporter metrics-emitter)
                                                      (metrics-ports/export-metrics metrics-emitter :prometheus)
                                                      "")})
                               :summary "Prometheus metrics scrape endpoint"})}]]))



(defmethod ig/init-key :wagoe/http-handler
  [_ {:keys [module-routes router logger metrics-emitter tracer error-reporter error-enricher config tenant-service db-context cache i18n i18n-middleware user-service request-capture? extra-middleware]}]
  (log/info "Initializing top-level HTTP handler with normalized routing and API versioning")
  (require 'wagoe.platform.ports.http)
  (require 'wagoe.platform.shell.interfaces.http.common)
  (let [compile-routes (ns-resolve 'wagoe.platform.ports.http 'compile-routes)

        platform-routes (platform-routes {:config          config
                                          :db-context      db-context
                                          :cache           cache
                                          :metrics-emitter metrics-emitter})

        ;; Every module's routes, framework and scaffolded alike, arrive as a
        ;; collection. Six named slots used to live in this destructuring —
        ;; user, admin, tenant, membership, workflow, search — each with its own
        ;; copy of the same prefix-and-normalise block, so a seventh library
        ;; could not contribute a route without editing this file. Middleware
        ;; was given this treatment in BOU-131; routes get it here (BOU-330).
        {module-static :static
         module-web    :web
         module-api    :api} (module-route-contributions module-routes)

        ;; Combine all API routes (unversioned at this point)
        all-api-routes module-api

        ;; Apply API versioning to all API routes
        ;; This wraps routes with /api/v1 prefix and creates backward compatibility redirects
        versioned-api-routes (if (seq all-api-routes)
                               (http-versioning/apply-versioning all-api-routes config)
                               [])

        ;; Conditionally build /test/reset route (test profile only).
        ;; Deliberately NOT routed through apply-versioning — we want the
        ;; literal path /test/reset, not /api/v1/test/reset.
        test-reset-routes (build-test-reset-routes
                           {:config         config
                            :user-service   user-service
                            :tenant-service tenant-service
                            :db-context     db-context})

        ;; Combine all routes: platform, static, web, and versioned API
        all-normalized-routes (concat platform-routes
                                      module-static
                                      module-web
                                      versioned-api-routes
                                      (or test-reset-routes []))

        {csrf-config :csrf
         rate-limit-config :rate-limit} (security-config {:config config :cache cache})

        system (interceptor-services
                {:logger            logger
                 :metrics-emitter   metrics-emitter
                 :tracer            tracer
                 :error-reporter    error-reporter
                 :error-enricher    error-enricher
                 :cache             cache
                 :config            config
                 :csrf-config       csrf-config
                 :rate-limit-config rate-limit-config})

        ;; i18n middleware is built by the i18n lib and injected
        ;; (:wagoe/i18n-http-middleware), the shape BOU-200 established for
        ;; tenant. Platform no longer requires wagoe.i18n.shell.middleware, so
        ;; a consumer that translates nothing need not ship the jar (BOU-131).
        ;; Kept as its own injection point rather than folded into
        ;; extra-middleware so the pipeline position does not change.
        ;;
        router-config {:middleware (request-middleware
                                    {:extra-middleware extra-middleware
                                     :i18n             i18n
                                     :i18n-middleware  i18n-middleware})
                       :system system}
        handler (compile-routes router all-normalized-routes router-config)

        ;; Wrap handler with version headers middleware
        versioned-handler (http-versioning/wrap-handler-with-version-headers handler config)

        ;; Conditionally wrap with request capture middleware (dev only).
        ;; Uses requiring-resolve to avoid hard dependency on devtools from platform.
        ;; Guarded with try/catch because devtools may not be on the classpath
        ;; in non-REPL dev boots (e.g. WAG_ENV=development clojure -M -m wagoe.main).
        final-handler (if request-capture?
                        (try
                          (let [wrap-fn (requiring-resolve 'wagoe.devtools.shell.dashboard.middleware/wrap-request-capture)]
                            (wrap-fn versioned-handler))
                          (catch Exception _
                            (log/debug "Request capture middleware not available, skipping")
                            versioned-handler))
                        versioned-handler)]

    (log/info "Top-level HTTP handler initialized successfully"
              {;; Counts rather than six named modules: this line named the
               ;; same six the destructuring did, so a seventh library's routes
               ;; were invisible in the boot log as well as unwireable.
               :module-contributions (count (remove nil? module-routes))
               :module-routes {:static (count module-static)
                               :web    (count module-web)
                               :api    (count module-api)}
               :versioned-api-routes (count versioned-api-routes)
               :total-normalized-routes (count all-normalized-routes)
               :router-adapter (class router)
               :system-services (keys system)
               :api-versioning-enabled true
               :request-capture-enabled (boolean request-capture?)})
    final-handler))

(defmethod ig/halt-key! :wagoe/http-handler
  [_ _handler]
  (log/info "HTTP handler halted"))

;; =============================================================================
;; Logging Component
;; =============================================================================

(defmethod ig/init-key :wagoe/logging
  [_ config]
  (log/info "Initializing logging component" {:provider (:provider config)})
  (let [logger (case (:provider config)
                 :no-op (logging-no-op/create-logging-component config)
                 :stdout (logging-stdout/create-logging-component config)
                 :slf4j (logging-slf4j/create-logging-component config)
                 (do
                   (log/warn "Unknown logging provider, falling back to no-op"
                             {:provider (:provider config)})
                   (logging-no-op/create-logging-component config)))]
    (log/info "Logging component initialized" {:provider (:provider config)})
    logger))

(defmethod ig/halt-key! :wagoe/logging
  [_ _logger]
  (log/info "Logging component halted"))

;; =============================================================================
;; Metrics Component
;; =============================================================================

(defmethod ig/init-key :wagoe/metrics
  [_ config]
  (log/info "Initializing metrics component" {:provider (:provider config)})
  (let [metrics (case (:provider config)
                  :no-op          (metrics-no-op/create-metrics-component config)
                  :datadog-statsd (metrics-datadog/create-datadog-metrics-component config)
                  :prometheus     (metrics-prometheus/create-metrics-component config)
                  :otlp           (metrics-otlp/create-metrics-component config)
                  ;; Future providers will be added here:
                  ;; :statsd (statsd-adapter/create-metrics-component config)
                  ;; :cloudwatch (cloudwatch-adapter/create-metrics-component config)
                  (do
                    (log/warn "Unknown metrics provider, falling back to no-op"
                              {:provider (:provider config)})
                    (metrics-no-op/create-metrics-component config)))]
    (log/info "Metrics component initialized" {:provider (:provider config)})
    metrics))

(defmethod ig/halt-key! :wagoe/metrics
  [_ _metrics]
  (log/info "Metrics component halted"))

;; =============================================================================
;; Tracing Component
;; =============================================================================

(defmethod ig/init-key :wagoe/tracing
  [_ config]
  (log/info "Initializing tracing component" {:provider (:provider config)})
  (let [tracer (case (:provider config)
                 :no-op   (tracing-no-op/create-tracing-component config)
                 :logging (tracing-logging/create-tracing-component config)
                 :otlp    (tracing-otlp/create-tracing-component config)
                 (do
                   (log/warn "Unknown tracing provider, falling back to no-op"
                             {:provider (:provider config)})
                   (tracing-no-op/create-tracing-component config)))]
    (log/info "Tracing component initialized" {:provider (:provider config)})
    tracer))

(defmethod ig/halt-key! :wagoe/tracing
  [_ tracer]
  ;; OTLP tracer owns an OpenTelemetry SDK with a batch processor — flush + shut
  ;; it down so buffered spans are exported before the process exits.
  (when (instance? wagoe.observability.tracing.shell.adapters.otlp.OtlpTracer tracer)
    (tracing-otlp/shutdown! tracer))
  (log/info "Tracing component halted"))

;; =============================================================================
;; Error Reporting Component
;; =============================================================================

(defmethod ig/init-key :wagoe/error-reporting
  [_ config]
  (log/info "Initializing error reporting component" {:provider (:provider config)})
  (let [error-reporter (case (:provider config)
                         :no-op (error-reporting-no-op/create-error-reporting-component config)
                         :sentry (error-reporting-sentry/create-sentry-error-reporting-component config)
                         ;; Future providers will be added here:
                         ;; :rollbar (rollbar-adapter/create-error-reporting-component config)
                         ;; :bugsnag (bugsnag-adapter/create-error-reporting-component config)
                         (do
                           (log/warn "Unknown error reporting provider, falling back to no-op"
                                     {:provider (:provider config)})
                           (error-reporting-no-op/create-error-reporting-component config)))]
    (log/info "Error reporting component initialized" {:provider (:provider config)})
    error-reporter))

(defmethod ig/halt-key! :wagoe/error-reporting
  [_ _error-reporter]
  (log/info "Error reporting component halted"))

;; =============================================================================
;; Handler Atom (runtime handler swapping)
;; =============================================================================

(def ^:private active-handler-atom
  "Atom holding the handler atom for the most recently started HTTP server.
   Each init-key creates a fresh atom so multiple systems in the same JVM
   don't share a single mutable pointer. halt-key! only nils its own atom."
  (atom nil))

(defn- make-dispatch-handler
  "Create a dispatch function closed over a specific handler atom.
   Jetty holds this stable reference; the atom underneath can be swapped."
  [handler-atom]
  (fn [request]
    (if-let [handler @handler-atom]
      (handler request)
      {:status 503
       :headers {"Content-Type" "text/plain"}
       :body "Handler not initialized"})))

(defn current-handler
  "Return the current live HTTP handler, or nil if not initialized."
  []
  (when-let [ha @active-handler-atom]
    @ha))

(defn swap-handler!
  "Replace the live HTTP handler. Called by devtools for router rebuilds."
  [new-handler]
  (when-let [ha @active-handler-atom]
    (reset! ha new-handler)))

;; =============================================================================
;; HTTP Server (Jetty)
;; =============================================================================

(defn- configure-graceful-shutdown!
  "Enable Jetty graceful shutdown on the given server.

   Wraps the configured handler in a GracefulHandler and sets the server's
   stop timeout. On (.stop server) Jetty then stops accepting new connections,
   rejects new requests with 503, and waits up to drain-timeout-ms for in-flight
   requests to complete before forcing the shutdown. A nil/0 timeout is a no-op."
  [^org.eclipse.jetty.server.Server s drain-timeout-ms]
  (when (and drain-timeout-ms (pos? drain-timeout-ms))
    (.setStopTimeout s (long drain-timeout-ms))
    (let [existing (.getHandler s)
          graceful (org.eclipse.jetty.server.handler.GracefulHandler.)]
      (.setHandler graceful existing)
      (.setHandler s graceful))))

;; A listener serving one module's protocol to the rest of the deployment.
;;
;; Its own port, not the application's. The endpoint invokes port methods
;; directly, so it belongs on a listener reachable from inside the deployment
;; and nowhere else — and mounting it on the app's router is not possible
;; anyway, since every route slot rewrites the path (BOU-90).
;;
;; `:protocol` is a symbol so the service catalogue stays plain data; it is
;; resolved here, where a name that no longer exists fails the boot rather than
;; the first call.
(defmethod ig/init-key :wagoe/rpc-server
  [_ {:keys [protocol implementation port host service-key auth]}]
  (let [protocol-var (or (requiring-resolve protocol)
                         (throw (ex-info (str "RPC protocol not found: " protocol)
                                         {:type :configuration-error})))
        app          (rpc-server/rpc-app @protocol-var implementation
                                         (cond-> {}
                                           service-key (assoc :service-key service-key)
                                           auth        (assoc :auth auth)))
        server       (jetty/run-jetty app {:port  port
                                           :host  (or host "0.0.0.0")
                                           :join? false})]
    (log/info "RPC endpoint started"
              {:protocol (str protocol) :port port :path rpc-server/default-path})
    server))

(defmethod ig/halt-key! :wagoe/rpc-server
  [_ server]
  (when server
    (log/info "Stopping RPC endpoint")
    (.stop server)))

(defmethod ig/init-key :wagoe/http-server
  [_ {:keys [handler port host join? config drain-timeout-ms]}]
  (let [http-config (or config {})
        port-allocation (port-manager/allocate-port port http-config)
        allocated-port (:port port-allocation)]

    (port-manager/log-port-allocation port allocated-port http-config "HTTP Server")

    (let [ha (atom handler)
          _  (reset! active-handler-atom ha)
          server (jetty/run-jetty (make-dispatch-handler ha)
                                  {:port allocated-port
                                   :host host
                                   :join? (or join? false)
                                   ;; Store the handler atom on the server so
                                   ;; halt-key! can clear the right one, and
                                   ;; enable graceful connection draining.
                                   :configurator (fn [s]
                                                   (.setAttribute s "handler-atom" ha)
                                                   (configure-graceful-shutdown! s drain-timeout-ms))})]
      (log/info "HTTP server started successfully"
                {:port allocated-port
                 :host host
                 :url (str "http://" host ":" allocated-port)
                 :drain-timeout-ms drain-timeout-ms
                 :allocation-message (:message port-allocation)})
      server)))

(defmethod ig/halt-key! :wagoe/http-server
  [_ server]
  (log/info "Stopping HTTP server")
  (when server
    ;; Clear only this server's handler atom
    (when-let [ha (.getAttribute server "handler-atom")]
      (reset! ha nil)
      ;; If this was the active server, clear the active reference
      (compare-and-set! active-handler-atom ha nil))
    (.stop server)
    (log/info "HTTP server stopped")))

;; =============================================================================
;; REPL Utilities
;; =============================================================================

(defonce ^:private system-state (atom nil))

(declare stop!)

(defn start!
  "Start the system from `ig-cfg` and remember it, halting any running one.

   Takes the Integrant config rather than building it. Which components an
   application runs is the application's decision — this used to resolve
   `wagoe.config/ig-config` at runtime, so a library reached into the
   composition root of whatever project happened to be on the classpath
   (BOU-306).

   Example:
     (start! (wagoe.system-config/ig-config (wagoe.config/load-config)))"
  [ig-cfg]
  (when @system-state
    (log/warn "System already started, halting existing system first")
    (stop!))
  (log/info "Starting Wagoe system")
  (let [system (ig/init ig-cfg)]
    (reset! system-state system)
    (log/info "Wagoe system started successfully")
    system))

(defn stop!
  "Stop the running system.
   
   Returns:
     nil
   
   Example:
     (stop!)"
  []
  (when-let [system @system-state]
    (log/info "Stopping Wagoe system")
    (ig/halt! system)
    (reset! system-state nil)
    (log/info "Wagoe system stopped"))
  nil)

(defn restart!
  "Restart the system from `ig-cfg` (stop then start).

   Takes the config for the same reason `start!` does.

   Example:
     (restart! (wagoe.system-config/ig-config (wagoe.config/load-config)))"
  [ig-cfg]
  (stop!)
  (start! ig-cfg))

;; =============================================================================
;; Integrant REPL Setup
;; =============================================================================

(comment
  ;; For use with integrant.repl
  ;; In your user.clj or REPL, set up integrant.repl:
  ;;
  ;; (require '[integrant.repl :as ig-repl])
  ;; (require '[wagoe.config :as config])
  ;; (require '[wagoe.system-config :as sys-config])
  ;; (ig-repl/set-prep! #(sys-config/ig-config (config/load-config)))
  ;; (ig-repl/go)     ; Start system
  ;; (ig-repl/reset)  ; Reload and restart
  ;; (ig-repl/halt)   ; Stop system

  ;; Or use the convenience functions, passing your application's config:
  ;;   (start!   (sys-config/ig-config (config/load-config)))
  ;;   (restart! (sys-config/ig-config (config/load-config)))
  (stop!)

  ;; Check system state
  @system-state

  ...)
