(ns wagoe.platform.shell.system.wiring-test
  (:require [wagoe.platform.shell.system.wiring]
            [wagoe.platform.ports.http]
            [wagoe.platform.shell.http.reitit-router]
            [wagoe.platform.shell.http.versioning]
            [wagoe.platform.shell.interfaces.http.common]
            [wagoe.observability.logging.shell.adapters.no-op]
            [wagoe.observability.metrics.shell.adapters.no-op]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.observability.tracing.ports :as tracing-ports]
            [wagoe.observability.errors.shell.adapters.no-op]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest ^:unit the-router-component-is-settings-not-an-adapter
  ;; It used to dispatch on :adapter to one of three routers, two of which were
  ;; comments. Reitit is the router; this key is what an application configures
  ;; about it (ADR-037).
  (is (= {:coercion :malli} (ig/init-key :wagoe/router {:coercion :malli})))
  (is (= {} (ig/init-key :wagoe/router {}))))

(deftest ^:unit http-handler-includes-membership-routes-and-optional-middleware
  (let [captured-routes (atom nil)
        captured-config (atom nil)
        compiled-handler (fn [request] {:status 200 :body request})
        config {:active {:wagoe/settings {:name "Wagoe"
                                          :version "1.2.3"}}}
        ;; Tenant/membership middleware now arrives via :extra-middleware, built
        ;; by the tenant lib's :wagoe/tenant-http-middleware component (BOU-200);
        ;; platform's http-handler no longer constructs it. Two stand-in wrappers.
        extra-middleware [(fn [h] (fn [request] (h (assoc request :tenant true))))
                          (fn [h] (fn [request] (h (assoc request :membership true))))]
        i18n-middleware  (fn [h] (fn [request] (h (assoc request :i18n true))))
        handler (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                              (fn [routes router-config]
                                (reset! captured-routes routes)
                                (reset! captured-config router-config)
                                compiled-handler)
                              wagoe.platform.shell.interfaces.http.common/health-check-handler
                              (fn [_app-name _version _details]
                                (fn [_request] {:status 200}))
                              wagoe.platform.shell.http.versioning/apply-versioning
                              (fn [routes _config] (vec routes))
                              wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers
                              (fn [wrapped-handler _config] wrapped-handler)]
                  (ig/init-key :wagoe/http-handler
                               {;; One collection, not a slot per module — a
                                ;; module platform has never heard of
                                ;; contributes the same way (BOU-330).
                                :module-routes
                                [{:api [["/users" {:get {:handler identity}}]]}
                                 {:api [["/tenants" {:get {:handler identity}}]]}
                                 {:api [["/tenants/:tenant-id/memberships"
                                         {:get {:handler identity}}]]}]
                                :router ::router
                                :logger ::logger
                                :metrics-emitter ::metrics
                                :tracer ::tracer
                                :error-reporter ::error-reporter
                                :config config
                                :tenant-service ::tenant-service
                                :db-context ::db-context
                                :extra-middleware extra-middleware
                                ;; i18n middleware is built by the i18n lib and
                                ;; injected, like tenant's (BOU-131). Platform
                                ;; no longer requires wagoe.i18n.shell.middleware,
                                ;; so there is nothing to redef above — the seam
                                ;; is this argument.
                                :i18n-middleware i18n-middleware}))]
    (testing "the compiled route set includes membership endpoints"
      (is (some #(= "/tenants/:tenant-id/memberships" (first %)) @captured-routes))
      (is (some #(= "/tenants" (first %)) @captured-routes))
      (is (some #(= "/users" (first %)) @captured-routes)))

    (testing "router config receives the injected extra middleware, i18n, and method override"
      (is (= 4 (count (:middleware @captured-config))))
      (is (= {:logger ::logger
              :metrics-emitter ::metrics
              :tracer ::tracer
              :error-reporter ::error-reporter}
             (dissoc (:system @captured-config) :csrf :rate-limit :cache :metrics-handles)))
      ;; CSRF enforcement is opt-in: with no :csrf config block the wiring default is off.
      (is (false? (get-in @captured-config [:system :csrf :enabled?])))
      ;; Rate limiting is opt-in too: with no :rate-limit config block it defaults off.
      (is (false? (get-in @captured-config [:system :rate-limit :enabled?]))))

    (testing "the returned handler is the compiled handler after wrapping"
      (is (= {:status 200 :body {:request-method :get}}
             (handler {:request-method :get}))))))

(deftest ^:unit http-handler-normalizes-web-routes-and-skips-optional-middleware-when-disabled
  (let [captured-routes (atom nil)
        captured-config (atom nil)
        compiled-handler (fn [request] {:status 200 :body request})
        handler (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                              (fn [routes router-config]
                                (reset! captured-routes routes)
                                (reset! captured-config router-config)
                                compiled-handler)
                              wagoe.platform.shell.interfaces.http.common/health-check-handler
                              (fn [_app-name _version _details]
                                (fn [_request] {:status 200}))
                              wagoe.platform.shell.http.versioning/apply-versioning
                              (fn [routes _config] (vec routes))
                              wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers
                              (fn [wrapped-handler _config] wrapped-handler)]
                  (ig/init-key :wagoe/http-handler
                               {;; The prefixes are the modules' own now: user
                                ;; mounts at /web, admin/workflow/search under
                                ;; /web/admin, and each says so (BOU-330).
                                :module-routes
                                [{:web [["/profile" {:middleware [:user-mw]
                                                     :get {:handler identity}}]]}
                                 {:web-prefix "/web/admin"
                                  :web [["/users" {:middleware [:admin-mw]
                                                   :get {:handler identity}}]]}
                                 {:web-prefix "/web/admin"
                                  :web [["/workflow" {:middleware [:workflow-mw]
                                                      :get {:handler identity}}]]}
                                 {:web-prefix "/web/admin"
                                  :web [["/search" {:middleware [:search-mw]
                                                    :get {:handler identity}}]]}]
                                :router ::router
                                :logger ::logger
                                :metrics-emitter ::metrics
                                :tracer ::tracer
                                :error-reporter ::error-reporter
                                :config {:active {:wagoe/settings {:name "Wagoe"
                                                                   :version "1.2.3"}}}}))]
    (testing "web routes are prefixed, keep their data, and stay out of the docs"
      (let [mounted (into {} (map (juxt first second)) @captured-routes)]
        (doseq [[path mw] {"/web/profile"         [:user-mw]
                           "/web/admin/users"     [:admin-mw]
                           "/web/admin/workflow"  [:workflow-mw]
                           "/web/admin/search"    [:search-mw]}]
          (is (= mw (:middleware (mounted path))) (str path " lost its middleware"))
          (is (true? (:no-doc (mounted path))) (str path " would show up in the API docs")))))

    (testing "only method override middleware is configured when tenant, membership, and i18n are absent"
      (is (= 1 (count (:middleware @captured-config))))
      (is (= {:logger ::logger
              :metrics-emitter ::metrics
              :tracer ::tracer
              :error-reporter ::error-reporter}
             (dissoc (:system @captured-config) :csrf :rate-limit :cache :metrics-handles)))
      ;; CSRF enforcement is opt-in: with no :csrf config block the wiring default is off.
      (is (false? (get-in @captured-config [:system :csrf :enabled?])))
      ;; Rate limiting is opt-in too: with no :rate-limit config block it defaults off.
      (is (false? (get-in @captured-config [:system :rate-limit :enabled?]))))

    (testing "method override middleware rewrites POST requests to the requested verb"
      (let [wrapped-handler ((first (:middleware @captured-config)) compiled-handler)]
        (is (= {:status 200 :body {:request-method :delete
                                   :form-params {"_method" "DELETE"}}}
               (wrapped-handler {:request-method :post
                                 :form-params {"_method" "DELETE"}})))
        (is (= {:status 200 :body {:request-method :patch
                                   :params {"_method" "PATCH"}}}
               (wrapped-handler {:request-method :post
                                 :params {"_method" "PATCH"}})))
        (is (= {:status 200 :body {:request-method :get}}
               (handler {:request-method :get})))))))

(deftest ^:security ^:unit http-handler-fails-loud-when-csrf-enabled-without-secret
  (testing "CSRF enabled with a blank secret throws at startup (fail closed, not fail open)"
    ;; :secret "" forces a blank secret regardless of the ambient JWT_SECRET env var,
    ;; so the guard trips deterministically.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"CSRF protection is enabled but no secret is configured"
         (ig/init-key :wagoe/http-handler
                      {:router ::router
                       :logger ::logger
                       :metrics-emitter ::metrics
                       :error-reporter ::error-reporter
                       :config {:active {:wagoe/http {:security {:csrf {:enabled? true
                                                                        :secret ""}}}}}})))))

(deftest ^:security ^:unit rate-limit-fails-loud-in-prod-without-cache
  (testing "rate limiting enabled in :prod with no cache throws at startup (no false protection)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Rate limiting is enabled in the :prod profile but no"
         (ig/init-key :wagoe/http-handler
                      {:router ::router
                       :logger ::logger
                       :metrics-emitter ::metrics
                       :error-reporter ::error-reporter
                       ;; no :cache -> cache is nil
                       :config {:wagoe/profile :prod
                                :active {:wagoe/http {:rate-limit {:enabled? true}}}}})))))

(deftest ^:unit component-init-falls-back-to-no-op-providers-for-unknown-adapters
  (with-redefs [wagoe.observability.logging.shell.adapters.no-op/create-logging-component
                (fn [config] [:logging-no-op config])
                wagoe.observability.metrics.shell.adapters.no-op/create-metrics-component
                (fn [config] [:metrics-no-op config])
                wagoe.observability.errors.shell.adapters.no-op/create-error-reporting-component
                (fn [config] [:errors-no-op config])]
    (is (= [:logging-no-op {:provider :mystery}]
           (ig/init-key :wagoe/logging {:provider :mystery})))
    (is (= [:metrics-no-op {:provider :mystery}]
           (ig/init-key :wagoe/metrics {:provider :mystery})))
    (is (= [:errors-no-op {:provider :mystery}]
           (ig/init-key :wagoe/error-reporting {:provider :mystery})))))

;; =============================================================================
;; Prometheus metrics provider + /metrics endpoint (BOU-174)
;; =============================================================================

(deftest ^:unit metrics-component-selects-prometheus-provider
  (testing ":provider :prometheus wires the Prometheus adapter, and it exports"
    (let [c (ig/init-key :wagoe/metrics {:provider :prometheus})]
      (is (satisfies? metrics-ports/IMetricsExporter c))
      (let [h (metrics-ports/register-counter! c :requests_total "Total requests" {})]
        (metrics-ports/inc-counter! c h))
      (let [out (metrics-ports/export-metrics c :prometheus)]
        (is (re-find #"# TYPE requests_total counter" out))
        (is (re-find #"requests_total\S* 1" out))))))

(deftest ^:unit metrics-endpoint-serves-prometheus-text
  (testing "the /metrics route exports the active component's Prometheus text"
    (let [metrics (ig/init-key :wagoe/metrics {:provider :prometheus})
          h       (metrics-ports/register-counter! metrics :http_requests_total "reqs" {})
          _       (metrics-ports/inc-counter! metrics h)
          captured-routes (atom nil)
          config  {:active {:wagoe/settings {:name "B" :version "1"}}}]
      (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                    (fn [routes _cfg] (reset! captured-routes routes) (fn [_] {:status 200}))
                    wagoe.platform.shell.interfaces.http.common/health-check-handler
                    (fn [_ _ _] (fn [_] {:status 200}))
                    wagoe.platform.shell.http.versioning/apply-versioning (fn [routes _] (vec routes))
                    wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers (fn [hh _] hh)]
        (ig/init-key :wagoe/http-handler
                     {:module-routes [] :router ::r :logger ::l :metrics-emitter metrics
                      :error-reporter ::e :config config}))
      ;; Reitit data: ["/metrics" {:get {:handler ...}}] (ADR-037).
      (let [metrics-route (first (filter #(= "/metrics" (first %)) @captured-routes))
            handler       (get-in (second metrics-route) [:get :handler])
            resp          (handler {})]
        (is (some? metrics-route) "/metrics route is mounted")
        (is (= 200 (:status resp)))
        (is (re-find #"text/plain" (get-in resp [:headers "Content-Type"])))
        (is (re-find #"http_requests_total" (:body resp)))))))

(deftest ^:unit tracing-component-selects-provider
  (testing ":no-op, :logging, and an unknown provider (falls back to no-op) all satisfy ITracer"
    (is (satisfies? tracing-ports/ITracer (ig/init-key :wagoe/tracing {:provider :no-op})))
    (is (satisfies? tracing-ports/ITracer (ig/init-key :wagoe/tracing {:provider :logging})))
    (is (satisfies? tracing-ports/ITracer (ig/init-key :wagoe/tracing {:provider :mystery})))))

(deftest ^:unit tracing-component-selects-otlp-provider
  (testing ":provider :otlp builds an OpenTelemetry tracer (no collector needed to construct)"
    (let [tracer (ig/init-key :wagoe/tracing
                              {:provider :otlp :endpoint "http://localhost:4318"
                               :service-name "wiring-test"})]
      (is (satisfies? tracing-ports/ITracer tracer))
      (let [span (tracing-ports/start-span! tracer "probe")]
        (is (re-matches #"[0-9a-f]{32}" (:trace-id (tracing-ports/span-context tracer span))))
        (tracing-ports/end-span! tracer span))
      ;; halt flushes + shuts the SDK down (no dangling batch thread)
      (ig/halt-key! :wagoe/tracing tracer))))

(deftest ^:unit metrics-component-selects-otlp-provider
  (testing ":provider :otlp builds an OTLP metrics component that emits without error"
    (let [c (ig/init-key :wagoe/metrics
                         {:provider :otlp :endpoint "http://localhost:4318"
                          :service-name "wiring-test" :interval-ms 60000})]
      (is (satisfies? metrics-ports/IMetricsEmitter c))
      (let [h (metrics-ports/register-counter! c :requests_total "reqs" {})]
        (is (nil? (metrics-ports/inc-counter! c h))))
      (is (nil? (metrics-ports/flush! c)))
      (ig/halt-key! :wagoe/metrics c))))

;; =============================================================================
;; Platform must not require any module's wiring (BOU-131)
;; =============================================================================

(deftest ^:unit platform-requires-no-module-wiring
  ;; These were static requires, so every consumer of platform had to ship
  ;; every module's jar whether it used one or not — a missing one is a
  ;; FileNotFoundException at load. zzp-guard could not drop wagoe-payments
  ;; after deleting all its payment code for exactly this reason.
  ;;
  ;; Measured before: requiring platform's wiring with payments, realtime or
  ;; i18n off the classpath failed. After: all three load.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["libs/platform/src/wagoe/platform/shell/system/wiring.clj"
                       "src/wagoe/platform/shell/system/wiring.clj"])
                (throw (ex-info "wiring.clj not found — cannot check"
                                {:cwd (System/getProperty "user.dir")})))
        ;; Requires only: a `[wagoe.<lib>.shell.module-wiring]` vector, not the
        ;; word in a comment.
        required (map second (re-seq #"(?m)^\s*\[(wagoe\.[a-z0-9-]+\.shell\.module-wiring)\]" src))]

    (testing "the source was read — otherwise this passes vacuously"
      (is (str/includes? src "wagoe/http-handler")))

    (testing "no module-wiring is required"
      (is (empty? required)
          (str "platform requires " (pr-str required)
               " — every consumer must then ship those jars. The layer that "
               "emits a key registers it: put the require in wagoe.config.")))

    (testing "and no module's shell namespace is reached into"
      ;; ports are the seam and are fine; shell is not.
      (let [shells (map second (re-seq #"\[(wagoe\.(?!platform)[a-z0-9-]+\.shell\.[a-z0-9.-]+)\s" src))]
        (is (empty? shells)
            (str "platform requires another module's shell: " (pr-str shells)))))))

;; =============================================================================
;; The legacy :i18n input still works (BOU-131 upgrade path)
;; =============================================================================

(deftest ^:unit legacy-i18n-key-still-produces-middleware
  ;; An app that upgrades platform without regenerating its config still passes
  ;; the i18n component as :i18n — the documented input until BOU-131. Ignoring
  ;; it would not error: handlers read `(get request :i18n/t identity)`, so
  ;; every marker would resolve to its own keyword and the page would merely
  ;; look wrong. Silent, and therefore worth a test.
  (let [captured-config (atom nil)
        compiled-handler (fn [_] {:status 200})
        config {:active {:wagoe/settings {:name "test" :version "0.0.1"}}}
        build (fn [extra-keys]
                (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                              (fn [_routes router-config]
                                (reset! captured-config router-config)
                                compiled-handler)
                              wagoe.platform.shell.interfaces.http.common/health-check-handler
                              (fn [_ _ _] (fn [_] {:status 200}))
                              wagoe.platform.shell.http.versioning/apply-versioning
                              (fn [routes _] (vec routes))
                              wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers
                              (fn [h _] h)]
                  (ig/init-key :wagoe/http-handler
                               (merge {:module-routes []
                                       :router ::router :logger ::logger
                                       :metrics-emitter ::metrics :tracer ::tracer
                                       :error-reporter ::error-reporter :config config
                                       :db-context ::db-context}
                                      extra-keys))
                  @captured-config))]

    (testing "the new :i18n-middleware key is used as given"
      (let [mw (fn [h] h)]
        (is (= 2 (count (:middleware (build {:i18n-middleware mw})))))))

    (testing "the legacy :i18n key still contributes middleware"
      ;; A real i18n component shape; wrap-i18n is resolved dynamically.
      (let [component {:catalogue {} :default-locale :en :dev? false}
            mws (:middleware (build {:i18n component}))]
        (is (= 2 (count mws))
            "an app passing :i18n lost its translation middleware")))

    (testing "neither key means no i18n middleware, and no error"
      (is (= 1 (count (:middleware (build {}))))))))

(deftest ^:unit ^:security the-profile-reaches-the-interceptors-that-gate-on-environment
  ;; Interceptors decide what an error may say by environment, and they read it
  ;; from the system map they are given. Nothing put it there: the detector fell
  ;; through to WAG_ENV, which a generated project never sets, so every
  ;; deployment read as development wherever it ran (BOU-321).
  (let [captured (atom nil)
        build    (fn [config]
                   (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                                 (fn [_routes router-config]
                                   (reset! captured router-config)
                                   (fn [_] {:status 200}))
                                 wagoe.platform.shell.interfaces.http.common/health-check-handler
                                 (fn [_ _ _] (fn [_] {:status 200}))
                                 wagoe.platform.shell.http.versioning/apply-versioning
                                 (fn [routes _] (vec routes))
                                 wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers
                                 (fn [handler _] handler)]
                     (ig/init-key :wagoe/http-handler
                                  {:module-routes [] :router ::router :logger ::logger
                                   :metrics-emitter ::metrics :tracer ::tracer
                                   :error-reporter ::error-reporter :db-context ::db-context
                                   :config config})
                     (get-in @captured [:system :environment])))]

    (is (= "prod" (build {:wagoe/profile :prod :active {}})))
    (is (= "dev"  (build {:wagoe/profile :dev  :active {}})))

    (testing "an app with no profile leaves the key absent, as before"
      ;; Absent, not nil: the detector then falls through to WAG_ENV exactly as
      ;; it did before this existed.
      (is (nil? (build {:active {}}))))))

(deftest ^:unit an-application-can-switch-conflict-detection-off-from-config
  ;; BOU-356 made an ambiguous route table fail the boot. An application that
  ;; has to ship before untangling its routes says so in config, and this is
  ;; the one :wagoe/router setting that reaches the router — the rest are
  ;; still inert (BOU-357).
  (let [captured (atom nil)
        compiled (fn [_ _] identity)]
    (with-redefs [wagoe.platform.shell.http.reitit-router/compile-routes
                  (fn [_routes cfg] (reset! captured cfg) compiled)
                  wagoe.platform.shell.interfaces.http.common/health-check-handler
                  (fn [_ _ _] (fn [_] {:status 200}))
                  wagoe.platform.shell.http.versioning/apply-versioning
                  (fn [routes _] (vec routes))
                  wagoe.platform.shell.http.versioning/wrap-handler-with-version-headers
                  (fn [h _] h)]

      (testing "unset — the router keeps its own default"
        (ig/init-key :wagoe/http-handler
                     {:module-routes [] :config {:active {:wagoe/settings {:name "t"}}}
                      :router {}})
        (is (not (contains? @captured :conflicts))
            "nothing passed means the router decides, not the wiring"))

      (testing ":conflicts nil reaches the router"
        (ig/init-key :wagoe/http-handler
                     {:module-routes [] :config {:active {:wagoe/settings {:name "t"}}}
                      :router {:conflicts nil}})
        (is (contains? @captured :conflicts))
        (is (nil? (:conflicts @captured)))))))
