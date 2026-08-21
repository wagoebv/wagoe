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
      [{:path "/test/reset"
        :methods {:post {:handler handler
                         :summary "Playwright e2e reset + seed endpoint"
                         :no-doc  true
                         :skip-interceptors? true}}}])))

(defmethod ig/init-key :wagoe/http-handler
  [_ {:keys [user-routes admin-routes tenant-routes membership-routes workflow-routes search-routes module-routes router logger metrics-emitter tracer error-reporter error-enricher config tenant-service db-context cache i18n i18n-middleware user-service request-capture? extra-middleware]}]
  (log/info "Initializing top-level HTTP handler with normalized routing and API versioning")
  (require 'wagoe.platform.ports.http)
  (require 'wagoe.platform.shell.interfaces.http.common)
  (let [;; Import compile-routes function
        compile-routes (ns-resolve 'wagoe.platform.ports.http 'compile-routes)

        ;; Create health check handler
        health-handler (let [health-fn (ns-resolve 'wagoe.platform.shell.interfaces.http.common 'health-check-handler)]
                         (health-fn
                          (get-in config [:active :wagoe/settings :name] "wagoe")
                          (get-in config [:active :wagoe/settings :version] "unknown")
                          nil))

        ;; Create readiness handler with dependency checks
        readiness-handler-fn (ns-resolve 'wagoe.platform.shell.interfaces.http.common 'readiness-handler)
        ready-handler (readiness-handler-fn db-context cache)

        ;; Define platform routes (health checks, etc.) in normalized format
        platform-routes [{:path "/"
                          :methods {:get {:handler (fn [request]
                                                     ;; Redirect to login if not authenticated, users if authenticated
                                                     (if (:user request)
                                                       {:status 302
                                                        :headers {"Location" "/web/users"}}
                                                       {:status 302
                                                        :headers {"Location" "/web/login"}}))
                                          :summary "Home page (redirects to login or users)"
                                          :no-doc true
                                          :skip-interceptors? true}}}
                         {:path "/health"
                          :methods {:get {:handler health-handler
                                          :summary "Health check endpoint"
                                          :no-doc true
                                          :skip-interceptors? true}}}
                         {:path "/health/ready"
                          :methods {:get {:handler ready-handler
                                          :summary "Readiness check with dependency health"
                                          :no-doc true
                                          :skip-interceptors? true}}}
                         {:path "/health/live"
                          :methods {:get {:handler (fn [_] {:status 200
                                                            :headers {"Content-Type" "application/json"}
                                                            :body (cheshire.core/generate-string {:status "alive"})})
                                          :summary "Liveness check"
                                          :no-doc true
                                          :skip-interceptors? true}}}
                         ;; Prometheus scrape endpoint. Serves the text exposition
                         ;; format from the active metrics component; the :no-op /
                         ;; :datadog-statsd providers return an empty body, the
                         ;; :prometheus provider returns real metrics.
                         {:path "/metrics"
                          :methods {:get {:handler (fn [_]
                                                     {:status  200
                                                      :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
                                                      :body    (if (satisfies? metrics-ports/IMetricsExporter metrics-emitter)
                                                                 (metrics-ports/export-metrics metrics-emitter :prometheus)
                                                                 "")})
                                          :summary "Prometheus metrics scrape endpoint"
                                          :no-doc true
                                          :skip-interceptors? true}}}]

        ;; Extract user module routes (normalized format)
        user-static-routes (or (:static user-routes) [])
        user-web-routes (or (:web user-routes) [])
        user-api-routes (or (:api user-routes) [])

        ;; User routes are in normalized format - use directly
        user-normalized-static (when (seq user-static-routes) user-static-routes)
        user-normalized-web (when (seq user-web-routes)
                             ;; Add /web prefix to web routes and merge :meta into route root.
                             ;; Always set :no-doc true — web UI routes never belong in API docs.
                              (mapv (fn [{:keys [path meta] :as route}]
                                      (-> route
                                          (dissoc :meta)
                                          (merge {:no-doc true} meta)
                                          (assoc :path (str "/web" path))))
                                    user-web-routes))
        user-normalized-api (when (seq user-api-routes) user-api-routes)

        ;; Extract admin module routes (normalized format) - may be nil if admin disabled
        admin-static-routes (or (:static admin-routes) [])
        admin-web-routes (or (:web admin-routes) [])
        admin-api-routes (or (:api admin-routes) [])

        ;; Admin routes are in normalized format - use directly
        admin-normalized-static (when (seq admin-static-routes) admin-static-routes)
        admin-normalized-web (when (seq admin-web-routes)
                              ;; Add /web/admin prefix to web routes and merge :meta into route root.
                              ;; Always set :no-doc true — web UI routes never belong in API docs.
                               (let [transformed (mapv (fn [{:keys [path meta] :as route}]
                                                         (let [result (-> route
                                                                          (dissoc :meta)
                                                                          (merge {:no-doc true} meta)
                                                                          (assoc :path (str "/web/admin" path)))]
                                                           (log/info "Admin route transformation"
                                                                     {:original-path path
                                                                      :new-path (:path result)
                                                                      :had-meta (some? meta)
                                                                      :has-middleware (contains? result :middleware)
                                                                      :result-keys (keys result)})
                                                           result))
                                                       admin-web-routes)
                                     ;; The admin module's root route is "/", which the prefix above
                                     ;; turns into "/web/admin/" — so the un-slashed "/web/admin" that
                                     ;; people actually type matched nothing and 404'd on a feature
                                     ;; that works. Redirect it to the canonical path (BOU-229).
                                     ;; 302 rather than 301: browsers cache permanent redirects hard,
                                     ;; and this mount point is configurable via :base-path.
                                     slash-redirect {:path    "/web/admin"
                                                     :no-doc  true
                                                     :methods {:get {:handler (fn [_]
                                                                                {:status  302
                                                                                 :headers {"Location" "/web/admin/"}
                                                                                 :body    ""})}}}]
                                 (log/info "Total admin web routes transformed" {:count (count transformed)})
                                 (conj transformed slash-redirect)))
        admin-normalized-api (when (seq admin-api-routes) admin-api-routes)

        ;; Extract tenant module routes (normalized format)
        tenant-api-routes (or (:api tenant-routes) [])
        tenant-normalized-api (when (seq tenant-api-routes) tenant-api-routes)

        ;; Extract tenant membership module routes (normalized format)
        membership-api-routes (or (:api membership-routes) [])
        membership-normalized-api (when (seq membership-api-routes) membership-api-routes)

        ;; Extract workflow module routes (normalized format) — may be nil if workflow disabled
        workflow-web-routes-raw (or (:web workflow-routes) [])
        workflow-api-routes (or (:api workflow-routes) [])

        ;; Workflow web routes — mounted under /web/admin (same prefix as admin routes)
        workflow-normalized-web (when (seq workflow-web-routes-raw)
                                  (mapv (fn [{:keys [path meta] :as route}]
                                          (-> route
                                              (dissoc :meta)
                                              (merge {:no-doc true} meta)
                                              (assoc :path (str "/web/admin" path))))
                                        workflow-web-routes-raw))
        workflow-normalized-api (when (seq workflow-api-routes) workflow-api-routes)

        ;; Extract search module routes (normalized format) — may be nil if search disabled
        search-web-routes-raw (or (:web search-routes) [])
        search-api-routes     (or (:api search-routes) [])

        ;; Search web routes — mounted under /web/admin (same prefix as admin/workflow routes)
        search-normalized-web (when (seq search-web-routes-raw)
                                (mapv (fn [{:keys [path meta] :as route}]
                                        (-> route
                                            (dissoc :meta)
                                            (merge {:no-doc true} meta)
                                            (assoc :path (str "/web/admin" path))))
                                      search-web-routes-raw))
        search-normalized-api (when (seq search-api-routes) search-api-routes)

        ;; Scaffolded modules, discovered from config rather than named here
        ;; (BOU-311). Every other key in this let is a framework module this
        ;; namespace knows by name; a generated module cannot be, so its routes
        ;; arrive as a collection. Without this the module initialises and
        ;; serves nothing, which is not what "scaffold, never hand-write" can
        ;; mean.
        discovered-api    (mapcat #(or (:api %) []) (or module-routes []))
        discovered-web    (mapcat #(or (:web %) []) (or module-routes []))
        discovered-static (mapcat #(or (:static %) []) (or module-routes []))

        ;; Combine all API routes (unversioned at this point)
        all-api-routes (concat (or user-normalized-api [])
                               (or admin-normalized-api [])
                               (or tenant-normalized-api [])
                               (or membership-normalized-api [])
                               (or workflow-normalized-api [])
                               (or search-normalized-api [])
                               ;; Versioned like everyone else's — a generated
                               ;; module's /api routes should not sit outside
                               ;; /api/v1 because nothing named them here.
                               discovered-api)

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
                                      (or user-normalized-static [])
                                      (or user-normalized-web [])
                                      (or admin-normalized-static [])
                                      (or admin-normalized-web [])
                                      (or workflow-normalized-web [])
                                      (or search-normalized-web [])
                                      discovered-static
                                      discovered-web
                                      versioned-api-routes
                                      (or test-reset-routes []))

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

        ;; Build system services map for HTTP interceptors
        ;; Register the standard HTTP metrics once so the request interceptor can
        ;; emit them (registering per request would reset counters on some adapters).
        metrics-handles (http-interceptors/register-http-metrics! metrics-emitter)

        system (cond-> {:logger logger
                        :metrics-emitter metrics-emitter
                        :metrics-handles metrics-handles
                        :tracer tracer
                        :error-reporter error-reporter
                        ;; The profile the app booted with. Interceptors decide
                        ;; what an error may say by environment, and they were
                        ;; reading only WAG_ENV and a `:config` key nothing put
                        ;; here — so a project that sets no WAG_ENV (every
                        ;; generated one) read as development wherever it ran.
                        ;; The config knows; pass it (BOU-321).
                        :csrf csrf-config
                        :rate-limit rate-limit-config
                        :cache cache}
                 ;; Optional, dev-only: a fn from exception to {:code :category
                 ;; :fix}, supplied by devtools via :wagoe/dev-error-enricher.
                 ;; Injected rather than resolved here — platform must not know
                 ;; the name of an optional library (BOU-131, BOU-321). Absent
                 ;; rather than nil, so an app that wired none is byte-identical
                 ;; to one built before this existed.
                 error-enricher (assoc :error-enricher error-enricher)
                 ;; Absent rather than nil when the app has no profile, so the
                 ;; detector falls through to WAG_ENV as it always did.
                 (:wagoe/profile config) (assoc :environment (name (:wagoe/profile config))))

        ;; i18n middleware is built by the i18n lib and injected
        ;; (:wagoe/i18n-http-middleware), the shape BOU-200 established for
        ;; tenant. Platform no longer requires wagoe.i18n.shell.middleware, so
        ;; a consumer that translates nothing need not ship the jar (BOU-131).
        ;; Kept as its own injection point rather than folded into
        ;; extra-middleware so the pipeline position does not change.
        ;;
        ;; `:i18n` is still accepted. An app that upgrades platform without
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
                        (fn [handler] (wrap handler i18n)))))

        ;; Compile routes using router adapter with system services.
        ;; `extra-middleware` is a seq of (fn [handler] ...) supplied by the app
        ;; (e.g. tenant/membership middleware via :wagoe/tenant-http-middleware).
        ;; Platform provides the pipeline; feature libs inject their middleware —
        ;; platform no longer requires the tenant lib (BOU-200). Applied before the
        ;; method-override middleware, matching the previous ordering.
        router-config {:middleware (concat
                                    (or extra-middleware [])
                                    (when i18n-mw [i18n-mw])
                                    [(fn [handler]
                                       (fn [request]
                                         (if (= :post (:request-method request))
                                           (let [method (or (get-in request [:form-params "_method"])
                                                            (get-in request [:params "_method"]))]
                                             (if method
                                               (let [override-method (keyword (str/lower-case method))]
                                                 (handler (assoc request :request-method override-method)))
                                               (handler request)))
                                           (handler request))))])
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
              {:user-routes {:static (count (or user-static-routes []))
                             :web (count (or user-web-routes []))
                             :api (count (or user-api-routes []))}
               :admin-routes {:static (count (or admin-static-routes []))
                              :web (count (or admin-web-routes []))
                              :api (count (or admin-api-routes []))}
               :tenant-routes {:api (count (or tenant-api-routes []))}
               :membership-routes {:api (count (or membership-api-routes []))}
               :workflow-routes {:web (count (or workflow-web-routes-raw []))
                                 :api (count (or workflow-api-routes []))}
               :search-routes {:web (count (or search-web-routes-raw []))
                               :api (count (or search-api-routes []))}
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
