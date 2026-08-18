(ns wagoe.platform.shell.http.interceptors
  "HTTP interceptor pipeline for Ring request/response processing.
   
   This namespace provides an HTTP-specific interceptor chain that runs over Ring handlers,
   giving Pedestal-like enter/leave/error semantics while maintaining Ring compatibility.
   
   Key Benefits:
   - Consistent cross-layer observability (HTTP → service → persistence)
   - Declarative per-route policies (auth, rate-limit, auditing)
   - Clean separation of concerns (routing vs cross-cutting logic)
   - Reusable interceptor components across routes
   
   HTTP Context Model:
   {:request       Ring request map
    :response      Ring response map (built up through pipeline)
    :route         Route metadata from Reitit match
    :path-params   Extracted path parameters
    :query-params  Extracted query parameters
    :system        Observability services {:logger :metrics-emitter :error-reporter}
    :attrs         Additional attributes set by interceptors
    :correlation-id Unique request ID
    :started-at    Request start timestamp}
   
   Interceptor Shape:
   {:name   :my-interceptor
    :enter  (fn [context] ...) ; Process request, modify context
    :leave  (fn [context] ...) ; Process response, modify context
    :error  (fn [context] ...)} ; Handle exceptions, produce safe response
   
   Usage:
   ;; As Ring middleware (global)
   (def handler
     (-> app-handler
         (wrap-http-interceptors [logging metrics error-reporting])))
   
   ;; Per-route via Reitit :middleware
   {:get {:handler my-handler
          :middleware [(interceptor-middleware [auth rate-limit])]}}
   
   Integration with Normalized Routes:
   - Normalized routes can specify :interceptors vector
   - Reitit adapter translates :interceptors → :middleware with this runner"
  (:require [wagoe.cache.ports :as cache-ports]
            [wagoe.core.interceptor :as interceptor]
            [wagoe.observability.logging.ports :as log-ports]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.observability.tracing.ports :as tracing-ports]
            [wagoe.observability.errors.core :as error-reporting]
            [wagoe.platform.core.csrf :as csrf]
            [buddy.core.nonce :as nonce]
            [buddy.core.codecs :as codecs]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

(set! *warn-on-reflection* true)

;; ==============================================================================
;; CSRF Token Helpers
;; ==============================================================================

(defn- request-session-binding
  "Raw session identifier the CSRF token is bound to: the X-Session-Token header
   or session-token cookie value, verbatim. Token generation (form/HTMX emission)
   and validation must use the identical value, so no decoding is applied here.
   Returns nil when no session is present (request is then not CSRF-protected)."
  [request]
  (or (get-in request [:headers "x-session-token"])
      (get-in request [:cookies "session-token" :value])))

(defn- path-exempt?
  "True when uri matches any exempt pattern. A pattern ending in \"/*\" matches the
   base path itself and anything beneath it on a path-segment boundary (so \"/api/*\"
   matches \"/api\" and \"/api/x\" but NOT \"/apix\"); any other pattern matches exactly."
  [exempt-paths uri]
  (boolean
   (some (fn [p]
           (if (str/ends-with? p "/*")
             (let [base (subs p 0 (- (count p) 2))]    ; drop "/*"
               (or (= uri base)
                   (str/starts-with? uri (str base "/"))))
             (= p uri)))
         exempt-paths)))

(defn- issue-csrf-token
  "Generate a fresh CSRF token bound to `binding`. The CSPRNG nonce lives here
   (shell); the signing itself is the pure core function."
  [secret binding]
  (csrf/generate-token secret binding (nonce/random-bytes 16)))

(defn- reuse-or-issue-csrf-token
  "Token to expose for rendering: when the request already carries a token
   (form/header — HTMX sends x-csrf-token on safe requests too) that validates
   for `binding`, re-issue that same token instead of minting a new one. Tokens
   carry no expiry and are bound only to (secret, binding), so a still-valid
   token stays valid for the same binding — re-use is cryptographically
   equivalent and skips the per-request CSPRNG draw + re-signing. Absent or
   invalid tokens fall back to a fresh mint."
  [secret binding request]
  (let [submitted (csrf/extract-token request)]
    (if (csrf/valid-token? secret binding submitted)
      submitted
      (issue-csrf-token secret binding))))

(def ^:private pre-session-cookie
  "Cookie holding the pre-session CSRF binding for unauthenticated flows (login,
   register, MFA), where no session token exists yet. SameSite=Strict so it is not
   sent on cross-site navigations."
  "csrf-session")

(defn- mint-pre-session-id
  "Random opaque value used as the CSRF binding before a session exists."
  []
  (codecs/bytes->b64-str (nonce/random-bytes 16) true))

(defn- web-route? [uri]
  (str/starts-with? (or uri "") "/web"))

(defn- attach-pre-session-cookie
  "On :leave, set the pre-session cookie when :enter minted one. Marked Secure only
   in non-dev is left to the platform's cookie policy; we mirror the session cookie's
   SameSite=Strict, HttpOnly, root path."
  [ctx]
  (if-let [id (::pre-session-id ctx)]
    (update-in ctx [:response :cookies] assoc pre-session-cookie
               {:value id :http-only true :path "/" :same-site :strict})
    ctx))

(defn- csrf-403
  "Reject with 403 and HALT the pipeline. :halt? is required — run-pipeline does not
   short-circuit on :response alone, so without it the downstream ring-handler
   interceptor would run and overwrite this response with the handler's output."
  [ctx]
  (assoc ctx
         :halt? true
         :response {:status 403
                    :headers {"Content-Type" "application/json"}
                    :body {:error "CSRF token validation failed"
                           :message "Invalid or missing CSRF token"
                           :type :csrf-validation-failed}}))

;; ==============================================================================
;; HTTP Context Management
;; ==============================================================================

(defn create-http-context
  "Creates an HTTP interceptor context from a Ring request.
   
   Args:
     request: Ring request map
     system: Map containing observability services {:logger :metrics-emitter :error-reporter}
     route-data: Optional Reitit route match data
   
   Returns:
     HTTP context map with request details and observability services"
  [request system & [route-data]]
  {:request request
   :response nil
   :route route-data
   :path-params (:path-params request)
   :query-params (:query-params request)
   :system system
   :attrs {}
   ;; Internal trace id, not a security token — ThreadLocalRandom avoids the
   ;; shared SecureRandom that UUID/randomUUID contends on under load.
   :correlation-id (or (get-in request [:headers "x-correlation-id"])
                       (let [tlr (java.util.concurrent.ThreadLocalRandom/current)]
                         (str (UUID. (.nextLong tlr) (.nextLong tlr)))))
   :started-at (Instant/now)
   :timing {}})

(defn extract-response
  "Extracts the Ring response from an HTTP context.
   
   If :response exists in context, returns it.
   If :response is nil, returns a safe 500 error response.
   
   Args:
     context: HTTP interceptor context
   
   Returns:
     Ring response map"
  [context]
  (or (:response context)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body {:error "Internal server error"
              :message "No response generated"
              :correlation-id (:correlation-id context)}}))

(defn set-response
  "Sets the Ring response in the HTTP context.
   
   Args:
     context: HTTP interceptor context
     response: Ring response map
   
   Returns:
     Updated context with response"
  [context response]
  (assoc context :response response))

(defn merge-response-headers
  "Merges additional headers into the response.
   
   Args:
     context: HTTP interceptor context
     headers: Map of headers to merge
   
   Returns:
     Updated context with merged headers"
  [context headers]
  (update-in context [:response :headers] merge headers))

(defn update-response-body
  "Updates the response body using a function.
   
   Args:
     context: HTTP interceptor context
     f: Function to apply to existing body
   
   Returns:
     Updated context with modified body"
  [context f]
  (update-in context [:response :body] f))

;; ==============================================================================
;; Environment / Enforcement Helpers
;; ==============================================================================

(def ^:private truthy-config-values #{"1" "true" "yes" "on"})
(def ^:private falsy-config-values #{"0" "false" "no" "off"})

(defn- parse-boolean-config
  "Parse common textual boolean values."
  [value]
  (when value
    (let [token (-> value str str/lower-case str/trim)]
      (cond
        (truthy-config-values token) true
        (falsy-config-values token) false
        :else nil))))

(def ^:private dev-like-environments
  #{"dev" "development" "test" "testing" "local"})

(defn- detect-environment
  "Derive the current runtime environment."
  [system]
  (some-> (or (:environment system)
              (get-in system [:config :environment])
              (System/getenv "WAG_ENV")
              (System/getProperty "environment")
              "development")
          str
          str/trim
          str/lower-case))

(defn- dev-like?
  "True when the runtime environment is one people develop against."
  [system]
  (boolean (dev-like-environments (or (detect-environment system) "development"))))

(defn dev-error-info
  "BND code, category and fix for `exception`, or nil.

   Two conditions, both required. The app must have wired an enricher —
   `:wagoe/dev-error-enricher`, which only a dev config asks for — and the
   environment must be dev-like. A generated project sets no WAG_ENV, so
   `detect-environment` answers \"development\" by default; the wiring is what
   makes this deliberate rather than accidental, and the environment check is
   what stops a dev config copied into production from explaining its own
   internals to the internet (BOU-321).

   Never throws: an enricher that dies must not replace the error the user
   needed to see.

   Public because the router needs it too: a request-coercion failure is thrown
   by middleware sitting outside this interceptor chain, so the router handles
   that one and asks here for the same enrichment."
  [system exception]
  (when-let [enrich (:error-enricher system)]
    (when (dev-like? system)
      (try
        (let [info (enrich exception)]
          (when (map? info)
            (not-empty (select-keys info [:code :category :fix :docs-url]))))
        (catch Throwable _ nil)))))

(defn- enforce-error-type?
  "Return true when exceptions must include :type in ex-data."
  [system]
  (let [override (or (System/getenv "WAGOE_ENFORCE_TYPED_ERRORS")
                     (System/getProperty "wagoe.enforceTypedErrors"))
        parsed-override (parse-boolean-config override)
        env (detect-environment system)]
    (if (some? parsed-override)
      parsed-override
      ;; Default: enforce in dev-like environments, skip in production
      (dev-like-environments (or env "development")))))

(defn- respond-missing-error-type
  "Emit a diagnostic response for ex-info missing :type."
  [{:keys [exception request correlation-id system] :as ctx} ex-data]
  (when-let [logger (:logger system)]
    (log-ports/warn logger
                    "Exception reached HTTP boundary without :type in ex-data"
                    {:exception-class (.getName (class exception))
                     :uri (:uri request)
                     :correlation-id correlation-id
                     :ex-data-keys (sort (keys ex-data))}))
  (set-response ctx
                {:status 500
                 :headers {"Content-Type" "application/json"
                           "X-Correlation-ID" correlation-id}
                 :body {:error "missing-error-type"
                        :message "Exceptions reaching the HTTP boundary must include :type in ex-data."
                        :hint "Add :type to the ex-info data map (e.g., {:type :validation-error})."
                        :correlation-id correlation-id
                        :exception-class (.getName (class exception))
                        :ex-data-keys (sort (keys ex-data))
                        :uri (:uri request)}}))

;; ==============================================================================
;; HTTP-Specific Interceptors
;; ==============================================================================

(def http-request-logging
  "Logs HTTP requests at entry and completion."
  {:name :http-request-logging
   :enter (fn [{:keys [request system correlation-id] :as ctx}]
            (when-let [logger (:logger system)]
              (log-ports/info logger "HTTP request received"
                              {:method (:request-method request)
                               :uri (:uri request)
                               :correlation-id correlation-id}))
            ctx)
   :leave (fn [{:keys [request response system correlation-id] :as ctx}]
            (when-let [logger (:logger system)]
              (log-ports/info logger "HTTP request completed"
                              {:method (:request-method request)
                               :uri (:uri request)
                               :status (:status response)
                               :correlation-id correlation-id}))
            ctx)
   :error (fn [{:keys [request system correlation-id exception] :as ctx}]
            (when-let [logger (:logger system)]
              (log-ports/error logger "HTTP request failed"
                               {:method (:request-method request)
                                :uri (:uri request)
                                :error (.getMessage ^Throwable exception)
                                :correlation-id correlation-id}))
            ctx)})

(def http-metrics-buckets
  "Default latency histogram buckets in SECONDS (Prometheus/OTel convention)."
  [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10])

(defn register-http-metrics!
  "Register the standard HTTP request metrics on a metrics component once (at
   wiring time). Returns a handles map `{:requests :errors :duration}`, or nil
   when there is no metrics component. Registering once — rather than per request
   — is required: some adapters (e.g. datadog) reset a counter on re-register."
  [metrics]
  (when (and metrics (satisfies? metrics-ports/IMetricsRegistry metrics))
    {:requests (metrics-ports/register-counter!   metrics :http.requests
                                                  "HTTP requests received" {})
     :errors   (metrics-ports/register-counter!   metrics :http.requests.errors
                                                  "HTTP requests that errored" {})
     :duration (metrics-ports/register-histogram! metrics :http.request.duration
                                                  "HTTP request duration (seconds)"
                                                  http-metrics-buckets {})}))

(defn- request-method-label [request]
  (name (or (:request-method request) :unknown)))

(defn- record-request!
  "Increment the `http.requests` counter and observe latency (seconds) for one
   completed request, labelled by method + status. Runs on both the success
   (:leave) and error (:error) paths so the counter covers *every* response —
   error rate is then `http.requests.errors / http.requests`."
  [metrics handles ctx status]
  (let [{:keys [requests duration]} handles
        tags {:method (request-method-label (:request ctx))
              :status (str status)}]
    (when requests
      (metrics-ports/inc-counter! metrics requests 1 tags))
    (when-let [start (get-in ctx [:timing :start])]
      (when duration
        (metrics-ports/observe-histogram!
         metrics duration
         (/ (double (- (System/nanoTime) start)) 1e9)
         tags)))))

(def http-request-metrics
  "Records HTTP request count, error count, and latency (seconds) on the metrics
   emitter. `http.requests` is incremented for every response (success *and*
   error, with a `status` label); `http.requests.errors` additionally counts the
   error path. Handles are registered once at wiring and threaded in as
   `:metrics-handles` on the system map; a no-op when no metrics component is
   configured, so this is safe always-on."
  {:name :http-request-metrics
   :enter (fn [ctx]
            (assoc-in ctx [:timing :start] (System/nanoTime)))
   :leave (fn [{:keys [response system] :as ctx}]
            (when-let [metrics (:metrics-emitter system)]
              (record-request! metrics (:metrics-handles system) ctx (:status response)))
            ctx)
   :error (fn [{:keys [request response system] :as ctx}]
            (when-let [metrics (:metrics-emitter system)]
              (let [handles (:metrics-handles system)
                    ;; the http-error-handler interceptor runs before this one on
                    ;; the reverse :error path, so a 5xx response is usually set.
                    status   (or (:status response) 500)]
                (record-request! metrics handles ctx status)
                (when-let [errors (:errors handles)]
                  (metrics-ports/inc-counter! metrics errors 1
                                              {:method (request-method-label request)
                                               :status (str status)}))))
            ctx)})

(def http-request-tracing
  "Starts a distributed-tracing span per HTTP request and ends it on completion,
   recording the response status (and any exception). No-op unless a tracer is
   wired into `:system` (:no-op tracer by default), so this is safe always-on.

   The span is a per-request root: cross-thread propagation into service /
   persistence spans is a later enhancement (would need context support on the
   `ITracer` port)."
  {:name :http-request-tracing
   :enter (fn [{:keys [request system correlation-id] :as ctx}]
            (if-let [tracer (:tracer system)]
              (let [method (name (:request-method request))
                    span   (tracing-ports/start-span!
                            tracer
                            (str "HTTP " (str/upper-case method) " " (:uri request))
                            {:http.request.method method
                             :url.path            (:uri request)
                             :correlation-id      (str correlation-id)})]
                (assoc-in ctx [:tracing :span] span))
              ctx))
   :leave (fn [{:keys [response system] :as ctx}]
            (when-let [tracer (:tracer system)]
              (when-let [span (get-in ctx [:tracing :span])]
                (when-let [status (:status response)]
                  (tracing-ports/set-attributes! tracer span
                                                 {:http.response.status_code status}))
                (tracing-ports/end-span! tracer span)))
            ctx)
   :error (fn [{:keys [system exception] :as ctx}]
            (when-let [tracer (:tracer system)]
              (when-let [span (get-in ctx [:tracing :span])]
                (when exception
                  (tracing-ports/record-exception! tracer span exception))
                (tracing-ports/end-span! tracer span)))
            ctx)})

(def http-error-reporting
  "Captures HTTP exceptions and reports them to error tracking."
  {:name :http-error-reporting
   :enter (fn [{:keys [request system correlation-id] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/add-breadcrumb
               error-reporter
               "HTTP request started"
               "http"
               :info
               {:method (name (:request-method request))
                :uri (:uri request)
                :correlation-id correlation-id}))
            ctx)
   :error (fn [{:keys [request exception system correlation-id] :as ctx}]
            (when-let [error-reporter (:error-reporter system)]
              (error-reporting/report-application-error
               error-reporter
               exception
               "HTTP request failed"
               {:context "http-request"
                :method (name (:request-method request))
                :uri (:uri request)
                :correlation-id correlation-id}))
            ctx)})

(def http-correlation-header
  "Adds correlation ID to response headers."
  {:name :http-correlation-header
   :leave (fn [{:keys [correlation-id] :as ctx}]
            (merge-response-headers ctx {"X-Correlation-ID" correlation-id}))
   :error (fn [{:keys [correlation-id] :as ctx}]
            (merge-response-headers ctx {"X-Correlation-ID" correlation-id}))})

(def http-error-handler
  "Converts exceptions into safe HTTP error responses."
  {:name :http-error-handler
   :error (fn [{:keys [exception correlation-id system] :as ctx}]
            (let [ex-data (ex-data exception)]
              (if (and (map? ex-data)
                       (nil? (:type ex-data))
                       (enforce-error-type? system))
                (respond-missing-error-type ctx ex-data)
                (let [error-type (or (:type ex-data) :internal-error)]
                  ;; Handle redirects specially
                  (if (= error-type :redirect)
                    (set-response ctx
                                  {:status (or (:status ex-data) 302)
                                   :headers {"Location" (:location ex-data)}
                                   :body ""})
                    ;; Handle errors
                    (let [status (case error-type
                                   :validation-error 400
                                   :not-found 404
                                   :unauthorized 401
                                   :forbidden 403
                                   :conflict 409
                                   500)
                          server-error? (>= status 500)
                          ;; Dev only, and only when the app wired an enricher:
                          ;; the BND code and its fix. The one place a 5xx says
                          ;; more than "Internal Server Error", which is why it
                          ;; is gated twice (BOU-321).
                          dev-info      (dev-error-info system exception)]
                      ;; Never leak internals on a 5xx: log the full exception
                      ;; (message, class, ex-data, stacktrace) server-side, keyed
                      ;; by correlation-id, and return only a generic body to the
                      ;; client. Typed 4xx errors carry an intended domain message
                      ;; and details, but never fall back to the raw .getMessage.
                      (when server-error?
                        (when-let [logger (:logger system)]
                          (log-ports/error logger "Unhandled error at HTTP boundary"
                                           {:correlation-id correlation-id
                                            :error-type error-type
                                            :exception-class (some-> ^Throwable exception class .getName)
                                            :message (some-> ^Throwable exception .getMessage)
                                            :ex-data ex-data})))
                      (set-response ctx
                                    {:status status
                                     :headers {"Content-Type" "application/json"
                                               "X-Correlation-ID" correlation-id}
                                     :body (cond-> (if server-error?
                                                     {:error "internal-error"
                                                      :message "Internal Server Error"
                                                      :correlation-id correlation-id}
                                                     ;; A typed 4xx carries a domain-authored
                                                     ;; message (ex-data :message or the
                                                     ;; ex-info message) — safe to return.
                                                     {:error (name error-type)
                                                      :message (or (:message ex-data)
                                                                   (.getMessage ^Throwable exception)
                                                                   "An error occurred")
                                                      :correlation-id correlation-id
                                                      :details (dissoc ex-data :type :message)})
                                             dev-info (assoc :dev dev-info))})))))))})

(def http-csrf-protection
  "Validates CSRF tokens for state-changing requests (POST, PUT, DELETE, PATCH) and
   issues tokens for rendering.

   Binding model — a token is signed against, and validated with, a per-client
   binding so a forged cross-site request cannot produce a matching token:
   - Authenticated requests bind to the session (session-token cookie / header).
   - Unauthenticated /web flows (login, register, MFA) bind to a pre-session cookie
     (csrf-session, SameSite=Strict) minted on the page GET.

   A state-changing request is validated (403 on failure) when CSRF is enabled, the
   path is not in :exempt-paths, and it is either session-authenticated or a /web
   route. This protects /web/admin and any session-authenticated /api route; it does
   NOT check token-auth API clients that send no session cookie (not CSRF-vulnerable)
   or exempt paths (webhooks/callbacks). Safe methods (GET/HEAD/OPTIONS) are never
   validated.

   For rendering, the interceptor exposes a token on the request as
   :anti-forgery-token and binds csrf/*token* around handler execution, so the page
   <meta> tag (HTMX) and form hidden fields emit it without per-handler threading.
   When the request already carries a token valid for the current binding, that
   token is re-issued instead of minting a new one (tokens have no expiry, so this
   is equivalent); a fresh token is minted only when absent or invalid.

   Config is read from (:csrf system), injected by the HTTP handler wiring:
     {:enabled? bool, :secret <signing-key>, :exempt-paths [\"/api/v1/...\"]}
   Enforcement is opt-in: when :enabled? is absent or false the interceptor is a
   no-op (no validation, no issuance). Apps enable it after emitting tokens."
  {:name :http-csrf-protection
   :enter (fn [{:keys [request system] :as ctx}]
            (let [{:keys [enabled? secret exempt-paths]
                   :or   {enabled? false}} (:csrf system)]
              ;; Disabled or misconfigured — no validation, no issuance. Checked before
              ;; any per-request work (CSRF is off by default, so this is the hot path).
              ;; Wiring aborts startup on enabled-but-secretless config, so the
              ;; secretless case here is a defensive second layer for direct/test
              ;; invocation that bypasses wiring.
              (if-not (and enabled? secret)
                ctx
                (let [method          (:request-method request)
                      uri             (or (:uri request) "")
                      state-changing? (contains? #{:post :put :delete :patch} method)
                      session-binding (request-session-binding request)
                      pre-cookie      (get-in request [:cookies pre-session-cookie :value])
                      expose          (fn [ctx binding]
                                        (assoc-in ctx [:request :anti-forgery-token]
                                                  (reuse-or-issue-csrf-token secret binding request)))]
                  (cond
                    ;; Protected: state-changing, not exempt, and either session-auth or
                    ;; a /web route (which uses the pre-session cookie binding).
                    (and state-changing?
                         (not (path-exempt? exempt-paths uri))
                         (or session-binding (web-route? uri)))
                    (let [binding   (or session-binding pre-cookie)
                          submitted (csrf/extract-token request)]
                      (if (and binding (csrf/valid-token? secret binding submitted))
                        ;; Valid — re-expose the just-validated token (still valid for
                        ;; this binding) for any form re-rendered in the response.
                        (assoc-in ctx [:request :anti-forgery-token] submitted)
                        (csrf-403 ctx)))

                    ;; Authenticated safe/exempt request — expose a token for the page
                    ;; (re-using the request's still-valid one when present).
                    session-binding
                    (expose ctx session-binding)

                    ;; Unauthenticated /web page load — mint a pre-session binding + token
                    ;; so login/register/MFA forms carry one; cookie is set on :leave.
                    (and (web-route? uri) (not state-changing?))
                    (let [id (or pre-cookie (mint-pre-session-id))]
                      (cond-> (expose ctx id)
                        (not pre-cookie) (assoc ::pre-session-id id)))

                    :else ctx)))))
   :leave attach-pre-session-cookie})

(def ^:private csrf-reject-response
  "Ring 403 returned by `wrap-csrf` on a failed/absent token. Body is a literal
   JSON string so the middleware is self-contained — it does not depend on a
   downstream response serializer (unlike the interceptor, whose map body is
   serialized later in the stack)."
  {:status  403
   :headers {"Content-Type" "application/json"}
   :body    "{\"error\":\"CSRF token validation failed\",\"message\":\"Invalid or missing CSRF token\",\"type\":\"csrf-validation-failed\"}"})

(defn wrap-csrf
  "Ring-middleware form of `http-csrf-protection`, for handlers that run OUTSIDE
   the interceptor stack — e.g. an app that mounts its own routes in front of the
   platform handler and so never passes through the default interceptor chain.

   Identical binding model and semantics to the interceptor:
   - State-changing (POST/PUT/DELETE/PATCH) requests that are not exempt and are
     either session-authenticated or a /web route are validated against the
     session token / pre-session cookie binding; a bad or absent token yields 403
     and the wrapped handler does not run.
   - Safe or authenticated requests issue a token and bind `csrf/*token*` around
     the handler, so form hidden-fields and the page `<meta>` tag emit it without
     per-handler threading.
   - An unauthenticated /web page load mints a pre-session binding and sets the
     `csrf-session` cookie on the response (for login/register/MFA forms).
   - Safe methods (GET/HEAD/OPTIONS) and token-auth API clients (no session
     cookie, not CSRF-vulnerable) are never blocked.

   Opt-in: a falsy/absent `:enabled?` or a blank `:secret` makes it a pass-through.

   Config: {:enabled? bool :secret <signing-key> :exempt-paths [\"/...\"]}."
  [handler {:keys [enabled? secret exempt-paths] :or {enabled? false}}]
  ;; Disabled or secretless — no validation, no issuance; decided once at wrap
  ;; time (config is fixed here), so disabled deployments pay zero per-request cost.
  (if-not (and enabled? secret)
    handler
    (fn [request]
      (let [method          (:request-method request)
            uri             (or (:uri request) "")
            state-changing? (contains? #{:post :put :delete :patch} method)
            session-binding (request-session-binding request)
            pre-cookie      (get-in request [:cookies pre-session-cookie :value])
            ;; Bind *token* around the handler so any Hiccup rendered while
            ;; handling can emit it (re-using the request's still-valid token
            ;; when present, minting fresh otherwise).
            render          (fn [bind-val]
                              (binding [csrf/*token* (reuse-or-issue-csrf-token secret bind-val request)]
                                (handler request)))]
        (cond
          ;; Protected: state-changing, not exempt, session-auth or /web route.
          (and state-changing?
               (not (path-exempt? exempt-paths uri))
               (or session-binding (web-route? uri)))
          (let [bind-val  (or session-binding pre-cookie)
                submitted (csrf/extract-token request)]
            (if (and bind-val (csrf/valid-token? secret bind-val submitted))
              ;; Valid — re-bind the just-validated token (still valid for this
              ;; binding) for any form re-rendered in the response.
              (binding [csrf/*token* submitted]
                (handler request))
              csrf-reject-response))

          ;; Authenticated safe/exempt request — issue a token for the page.
          session-binding
          (render session-binding)

          ;; Unauthenticated /web page load — mint a pre-session binding + token and
          ;; set the cookie on the response so the login/register/MFA form carries one.
          (and (web-route? uri) (not state-changing?))
          (let [id   (or pre-cookie (mint-pre-session-id))
                resp (render id)]
            (if pre-cookie
              resp
              (assoc-in resp [:cookies pre-session-cookie]
                        {:value id :http-only true :path "/" :same-site :strict})))

          :else (handler request))))))
;; ==============================================================================
;; Rate Limiting
;; ==============================================================================

;; In-memory rate limit tracking. Maps client-id to request timestamps.
(defn get-client-id
  "Extracts client identifier from request for rate limiting.

   Priority order:
   1. Authenticated user ID (from :user in request)
   2. API key (from headers)
   3. Remote IP address

   Args:
     request: Ring request map

   Returns:
     String client identifier"
  [request]
  (or (get-in request [:user :id])
      (get-in request [:headers "x-api-key"])
      (:remote-addr request)
      "unknown"))

(defn- check-rate-limit-redis
  "Check rate limit using Redis fixed-window counter.

   Each window period gets its own Redis key derived from the epoch index.
   Keys expire automatically at the end of their window via Redis TTL.
   Safe for multi-instance deployments — no shared in-process state.

   Args:
     cache: IAtomicCache + ICache implementation (Redis)
     client-id: Client identifier string
     limit: Maximum requests allowed per window
     window-seconds: Window size in seconds

   Returns:
     {:allowed? boolean :current-count long :limit int :retry-after-seconds int}"
  [cache client-id limit window-seconds]
  (let [now-ms      (System/currentTimeMillis)
        epoch-window (quot now-ms (* window-seconds 1000))
        key          (str "ratelimit:" client-id ":" epoch-window)
        count        (cache-ports/increment! cache key 1)]
    ;; Set TTL only on first request in this window so the key self-expires
    (when (= count 1)
      (cache-ports/expire! cache key window-seconds))
    {:allowed?             (<= count limit)
     :current-count        count
     :limit                limit
     :retry-after-seconds  (if (<= count limit) 0 window-seconds)}))

;; In-memory fallback used when no distributed cache is configured.
;; Not suitable for multi-instance deployments.
(defonce ^:private rate-limit-state (atom {}))

(def ^:private max-tracked-clients
  "Hard ceiling on the number of distinct clients the in-memory limiter tracks.
   The map can never exceed this: before a *new* client is recorded at the cap,
   stale clients (no requests in the window) are swept, and if the map is still
   full the least-recently-active client is evicted. This bounds heap under
   high-cardinality client identifiers (rotating API keys, many remote addresses)
   even when every tracked client is within the window — a sustained stream of
   fresh ids can't grow the map past the cap."
  10000)

(defn- prune-stale-clients
  "Drop clients whose timestamps all fall outside the window (older than cutoff).
   Such clients cannot affect any future limit decision, so retaining them only
   leaks heap."
  [state cutoff-ms]
  (persistent!
   (reduce-kv (fn [acc client-id timestamps]
                (let [recent (filterv #(> % cutoff-ms) timestamps)]
                  (if (seq recent)
                    (assoc! acc client-id recent)
                    acc)))
              (transient {})
              state)))

(defn- evict-least-recent
  "Drop the single least-recently-active client (smallest most-recent timestamp).
   Used as a last resort to keep the map at the cap when every client is in-window."
  [state]
  (let [oldest (key (apply min-key (fn [[_ ts]] (reduce max 0 ts)) state))]
    (dissoc state oldest)))

(defn- make-room-for-new-client
  "Ensure there is room to record a new client without exceeding the cap: prune
   stale clients first, then evict the least-recently-active if still at the cap.
   Only invoked when a previously-unseen client is about to be recorded at the
   cap, so the common path (existing client, or below cap) does no extra work."
  [state cutoff-ms]
  (let [pruned (prune-stale-clients state cutoff-ms)]
    (if (>= (count pruned) max-tracked-clients)
      (evict-least-recent pruned)
      pruned)))

(defn- check-rate-limit-memory
  "Check rate limit using an in-process fixed-window atom.

   Heap-bounded: the tracked-client map can never exceed `max-tracked-clients`.
   Before recording a *new* client at the cap, stale clients are swept and, if the
   map is still full (every client in-window), the least-recently-active client is
   evicted — so a sustained stream of fresh client ids cannot grow the map past
   the cap. The read-modify-write happens inside a single `swap!` so concurrent
   requests for the same client count correctly.

   Args:
     client-id: Client identifier string
     limit: Maximum requests allowed in time window
     window-ms: Time window in milliseconds

   Returns:
     {:allowed? boolean :current-count int :limit int :retry-after-seconds int}"
  [client-id limit window-ms]
  (let [now-ms (System/currentTimeMillis)
        cutoff (- now-ms window-ms)
        result (volatile! nil)]
    (swap! rate-limit-state
           (fn [state]
             (let [recent      (filterv #(> % cutoff) (get state client-id []))
                   cnt         (count recent)
                   allowed?    (< cnt limit)
                   new-client? (not (contains? state client-id))
                   ;; Only a NEW client grows the map; bound it before insertion.
                   state       (if (and allowed? new-client?
                                        (>= (count state) max-tracked-clients))
                                 (make-room-for-new-client state cutoff)
                                 state)]
               (vreset! result {:allowed?            allowed?
                                :current-count       cnt
                                :limit               limit
                                :retry-after-seconds (if allowed? 0 (int (/ window-ms 1000)))})
               (cond
                 allowed?     (assoc state client-id (conj recent now-ms))
                 (seq recent) (assoc state client-id recent)   ; at limit: keep trimmed window
                 :else        (dissoc state client-id)))))     ; nothing in-window to retain
    @result))

(defn- rate-limit-enter
  "Run a single rate-limit check and either annotate the context or short-circuit
   with a 429. Uses the Redis fixed-window counter when a cache is supplied (safe
   across replicas), otherwise the in-process fallback (single-node only)."
  [{:keys [request] :as ctx} limit window-ms cache]
  (let [window-seconds (max 1 (int (/ window-ms 1000)))
        client-id      (get-client-id request)
        rate-check     (if cache
                         (check-rate-limit-redis cache client-id limit window-seconds)
                         (check-rate-limit-memory client-id limit window-ms))]
    (if (:allowed? rate-check)
      (assoc-in ctx [:attrs :rate-limit] rate-check)
      ;; Setting :response now halts the enter chain on its own (ZZP-117); :halt?
      ;; is kept here as an explicit, self-documenting short-circuit.
      (-> ctx
          (assoc :halt? true)
          (set-response {:status  429
                         :headers {"Content-Type"          "application/json"
                                   "Retry-After"           (str (:retry-after-seconds rate-check))
                                   "X-RateLimit-Limit"     (str limit)
                                   "X-RateLimit-Remaining" "0"}
                         :body    {:error               "Rate limit exceeded"
                                   :message             (format "Too many requests. Limit: %d requests per %d seconds"
                                                                limit window-seconds)
                                   :retry-after-seconds (:retry-after-seconds rate-check)
                                   :type                :rate-limit-exceeded}})))))

(defn- rate-limit-leave
  "Attach informational X-RateLimit headers when a check ran on :enter."
  [{:keys [attrs] :as ctx}]
  (if-let [rate-limit (:rate-limit attrs)]
    (merge-response-headers ctx
                            {"X-RateLimit-Limit"     (str (:limit rate-limit))
                             "X-RateLimit-Remaining" (str (max 0 (- (:limit rate-limit)
                                                                    (:current-count rate-limit))))})
    ctx))

(defn http-rate-limit
  "Creates a rate limiting interceptor with fixed limit/window/cache.

   With a cache argument, uses Redis fixed-window counting — safe for
   multi-instance deployments and survives process restarts.
   Without a cache argument, falls back to an in-process sliding window.

   For the framework default pipeline use the config-driven
   `http-rate-limit-protection` instead; this fixed-arg form is for explicit
   per-route limits and standalone wiring (e.g. the devtools dashboard).

   Args:
     limit:        Maximum requests per time window (default: 100)
     window-ms:    Time window in milliseconds (default: 60000 = 1 minute)
     cache:        (optional) IAtomicCache + ICache implementation for distributed limiting

   Returns:
     Rate limiting interceptor map

   Example:
     (http-rate-limit)                   ; 100 req/min, in-memory
     (http-rate-limit 30 60000)          ; 30 req/min, in-memory
     (http-rate-limit 100 60000 cache)   ; 100 req/min, Redis-backed"
  ([]
   (http-rate-limit 100 60000 nil))
  ([limit window-ms]
   (http-rate-limit limit window-ms nil))
  ([limit window-ms cache]
   {:name  :http-rate-limit
    :enter (fn [ctx] (rate-limit-enter ctx limit window-ms cache))
    :leave rate-limit-leave}))

(def http-rate-limit-protection
  "Config-driven rate limiter applied in the default HTTP interceptor stack.

   Reads its policy from the system map injected by the HTTP handler wiring:
     (:rate-limit system) => {:enabled? bool, :limit int, :window-ms int}
     (:cache system)      => IAtomicCache (Redis) for cross-replica limiting

   Enforcement is opt-in: when :enabled? is absent or false the interceptor is a
   no-op, so a framework upgrade cannot start 429-ing consumers that have not
   configured limits. When enabled it uses the Redis cache for a limit shared
   across all replicas; with no cache it falls back to a per-process counter —
   correct on a single node only, NOT a global limit across replicas."
  {:name  :http-rate-limit
   :enter (fn [{:keys [system] :as ctx}]
            (let [{:keys [enabled? limit window-ms]
                   :or   {enabled? false limit 100 window-ms 60000}} (:rate-limit system)]
              (if enabled?
                (rate-limit-enter ctx limit window-ms (:cache system))
                ctx)))
   :leave rate-limit-leave})

;; ==============================================================================
;; Security Headers Interceptor
;; ==============================================================================

(def default-security-headers
  "Default security headers for production deployment.

   Provides defense-in-depth protection against common web vulnerabilities:
   - Content-Security-Policy: Prevent XSS attacks
   - X-Frame-Options: Prevent clickjacking
   - Strict-Transport-Security: Force HTTPS (with preload)
   - X-Content-Type-Options: Prevent MIME sniffing
   - Cross-Origin-Opener-Policy: Isolate top-level browsing context
   - Referrer-Policy: Control referrer information

   Note: Uses Alpine.js CSP build (@alpinejs/csp) which avoids eval()/new Function().
   All Alpine components are registered via Alpine.data() in components.js / admin-ux.js.
   'unsafe-inline' is kept in script-src because several UI components still use inline
   event handlers (onclick, onchange, onsubmit) and inline [:script] blocks (e.g. Alpine
   store init, audit modal wiring). Remove 'unsafe-inline' only after those are externalised.
   'unsafe-eval' is kept in script-src because HTMX 2.x has allowEval:true by default and
   hx-on::* expression attrs (e.g. hx-on::afterRequest in admin entity forms) depend on it.
   Remove 'unsafe-eval' only after htmx.config.allowEval is disabled or all hx-on attrs
   are replaced."
  {"Content-Security-Policy" (str "default-src 'self'; "
                                  "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                                  "style-src 'self' 'unsafe-inline'; "
                                  "img-src 'self' data: https:; "
                                  "font-src 'self'; "
                                  "connect-src 'self' wss:; "
                                  "object-src 'none'; "
                                  "frame-ancestors 'none'; "
                                  "base-uri 'self'; "
                                  "form-action 'self'")
   "X-Frame-Options" "DENY"
   "Strict-Transport-Security" "max-age=31536000; includeSubDomains; preload"
   "X-Content-Type-Options" "nosniff"
   "Cross-Origin-Opener-Policy" "same-origin"
   "Referrer-Policy" "strict-origin-when-cross-origin"
   "Permissions-Policy" "geolocation=(), microphone=(), camera=()"})

(defn http-security-headers
  "Creates security headers interceptor with configurable headers.

   Args:
     headers - Optional map of security headers (defaults to default-security-headers)

   Returns:
     Interceptor that adds security headers to responses

   Examples:
     ;; Use default security headers
     (http-security-headers)

     ;; Custom CSP for development (allows unsafe-eval for hot reload)
     (http-security-headers
       {\"Content-Security-Policy\" \"default-src 'self' 'unsafe-eval'; ...\"
        \"X-Frame-Options\" \"SAMEORIGIN\"})

     ;; Disable HSTS in development
     (http-security-headers
       (dissoc default-security-headers \"Strict-Transport-Security\"))

   Security Headers Explained:

   - Content-Security-Policy (CSP):
     Defines approved sources for loading content (scripts, styles, images, etc.)
     Prevents XSS attacks by restricting where resources can be loaded from
     Current policy:
       * default-src 'self' - Only load resources from same origin by default
       * script-src - Allow scripts from self + HTMX from CDN
       * style-src - Allow styles from self + Pico CSS from CDN
       * img-src - Allow images from self, data URLs, and HTTPS
       * frame-ancestors 'none' - Prevent embedding in iframes (clickjacking)

   - X-Frame-Options: DENY
     Legacy header (superseded by CSP frame-ancestors)
     Prevents page from being embedded in iframe/frame
     Protects against clickjacking attacks

   - Strict-Transport-Security (HSTS):
     Forces browsers to use HTTPS for 1 year
     Prevents protocol downgrade attacks
     includeSubDomains applies to all subdomains

   - X-Content-Type-Options: nosniff
     Prevents browser from MIME-sniffing responses
     Forces browser to respect Content-Type header

   - X-XSS-Protection: 1; mode=block
     Legacy XSS filter (modern browsers use CSP instead)
     Tells browser to block page if XSS attack detected

   - Referrer-Policy: strict-origin-when-cross-origin
     Controls Referer header sent with requests
     Only sends origin (not full URL) on cross-origin requests

   - Permissions-Policy:
     Controls browser features (geolocation, camera, microphone)
     Denies access to sensitive features by default

   Environment-Specific Recommendations:

   Development:
     - Relax CSP to allow hot reload: 'unsafe-eval', 'unsafe-inline'
     - Use X-Frame-Options: SAMEORIGIN (for development tools)
     - Omit HSTS (HTTP development servers)

   Staging:
     - Stricter CSP (fewer 'unsafe-*' directives)
     - Include HSTS with shorter max-age (e.g., 86400 = 1 day)

   Production:
     - Strictest CSP (no 'unsafe-*' if possible)
     - HSTS with max-age=31536000 (1 year)
     - Consider adding preload directive for HSTS"
  ([]
   (http-security-headers default-security-headers))
  ([headers]
   {:name :http-security-headers
    :leave (fn [ctx]
             ;; Add security headers to response
             (merge-response-headers ctx headers))}))

;; ==============================================================================
;; Default HTTP Interceptor Stack
;; ==============================================================================

(def default-http-interceptors
  "Default HTTP interceptor stack for observability and error handling."
  [http-request-logging
   http-request-tracing
   http-request-metrics
   http-error-reporting
   http-correlation-header
   http-rate-limit-protection ; Reject over-limit clients early (opt-in via config)
   http-csrf-protection
   (http-security-headers)   ; Add security headers to all responses
   http-error-handler])

;; ==============================================================================
;; HTTP Interceptor Runner
;; ==============================================================================

(defn run-http-interceptors
  "Runs an HTTP interceptor pipeline around a Ring handler.
   
   Args:
     handler: Ring handler function (request → response)
     interceptors: Vector of interceptor maps
     request: Ring request map
     system: Map containing observability services
   
   Returns:
     Ring response map
   
   Pipeline execution:
   1. Create HTTP context from request
   2. Run :enter phase on all interceptors
   3. If successful, call handler and set :response in context
   4. Run :leave phase on all interceptors (reverse order)
   5. If exception occurs, run :error phase to produce safe response
   6. Extract and return final Ring response"
  [handler interceptors request system]
  (let [;; Create initial HTTP context
        initial-ctx (create-http-context request system)

        ;; Create handler interceptor that bridges to Ring handler.
        ;; Bind the CSRF token (issued by http-csrf-protection onto the request)
        ;; for the duration of handler execution — Hiccup is rendered to a string
        ;; synchronously inside the handler, so layouts/forms can read csrf/*token*.
        handler-interceptor {:name :ring-handler
                             :enter (fn [ctx]
                                      (binding [csrf/*token* (get-in ctx [:request :anti-forgery-token])]
                                        (let [response (handler (:request ctx))]
                                          (set-response ctx response))))}

        ;; Combine interceptors with handler at the end
        full-pipeline (conj (vec interceptors) handler-interceptor)

        ;; Run the interceptor pipeline
        final-ctx (interceptor/run-pipeline initial-ctx full-pipeline)]

    ;; Extract and return the Ring response
    (extract-response final-ctx)))

;; ==============================================================================
;; Ring Middleware Wrapper
;; ==============================================================================

(defn wrap-http-interceptors
  "Ring middleware that runs HTTP interceptors around a handler.
   
   Usage:
     (def handler
       (-> my-handler
           (wrap-http-interceptors [auth logging metrics] system)))
   
   Args:
     handler: Ring handler function
     interceptors: Vector of interceptor maps
     system: Map containing observability services
   
   Returns:
     Wrapped Ring handler function"
  [handler interceptors system]
  (fn [request]
    (run-http-interceptors handler interceptors request system)))

(defn interceptor-middleware
  "Creates a Ring middleware function from interceptors.
   
   This is useful for Reitit per-route :middleware.
   
   Usage:
     {:get {:handler my-handler
            :middleware [(interceptor-middleware [auth rate-limit] system)]}}
   
   Args:
     interceptors: Vector of interceptor maps
     system: Map containing observability services
   
   Returns:
     Ring middleware function (handler → wrapped-handler)"
  [interceptors system]
  (fn [handler]
    (wrap-http-interceptors handler interceptors system)))

;; ==============================================================================
;; Helper Functions
;; ==============================================================================

(defn create-global-http-middleware
  "Creates global HTTP middleware with default observability stack.
   
   Args:
     system: Map containing observability services
     additional-interceptors: Optional vector of additional interceptors to prepend
   
   Returns:
     Ring middleware function"
  ([system]
   (create-global-http-middleware system []))
  ([system additional-interceptors]
   (let [interceptors (concat additional-interceptors default-http-interceptors)]
     (fn [handler]
       (wrap-http-interceptors handler interceptors system)))))

(defn validate-http-interceptor
  "Validates that an interceptor is suitable for HTTP use.
   
   HTTP interceptors should handle HTTP context properly and produce valid Ring responses."
  [interceptor]
  (interceptor/validate-interceptor interceptor)
  ;; Additional HTTP-specific validations could go here
  interceptor)
