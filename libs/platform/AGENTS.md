# Platform Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Infrastructure layer for HTTP routing/interceptors, database integration, CLI/runtime wiring, and shared platform adapters used by feature modules.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.platform.shell.http.interceptors` | HTTP-specific interceptor pipeline execution |
| `boundary.platform.shell.interceptors` | Universal cross-cutting interceptors (logging, metrics, error) |
| `boundary.platform.shell.interfaces.http.routes` | Reitit router and Ring handler creation |
| `boundary.platform.ports.http` | HTTP router and server protocols |
| `boundary.platform.db.*` | Database context, connection pooling, shared persistence utilities |

---

## HTTP Interceptor Architecture

### Interceptor Shape

Every interceptor is a map with three optional phase functions:

```clojure
{:name   :my-interceptor
 :enter  (fn [context] ...)  ; Process request, modify context
 :leave  (fn [context] ...)  ; Process response, modify context
 :error  (fn [context] ...)} ; Handle exceptions, produce safe response
```

### HTTP Context Model

The context map threaded through all interceptors:

```clojure
{:request        ; Ring request map
 :response       ; Ring response map (built up by handlers/interceptors)
 :route          ; Route metadata from Reitit match
 :path-params    ; Extracted path parameters
 :query-params   ; Extracted query parameters
 :system         ; Observability services {:logger :metrics-emitter :error-reporter}
 :attrs          ; Additional attributes set by interceptors
 :correlation-id ; Unique request ID
 :started-at}    ; Request start timestamp (for latency metrics)
```

### Built-in HTTP Interceptors

From `boundary.platform.shell.http.interceptors`:

| Interceptor | Phase | What it does |
|-------------|-------|--------------|
| `http-request-logging` | enter + leave | Logs request entry and completion with timing |
| `http-request-metrics` | enter + leave | Collects timing and status code metrics |
| `http-error-reporting` | error | Captures exceptions to error tracking service |
| `http-correlation-header` | leave | Adds correlation ID to response headers |
| `http-error-handler` | error | Converts `:type` in ex-data to HTTP status codes |
| `http-security-headers` | leave | Adds CSP, HSTS, X-Frame-Options, etc. |
| `http-csrf-protection` | enter + leave | Validates CSRF tokens, issues tokens, mints pre-session cookie (see [CSRF Protection](#csrf-protection)) |
| `http-rate-limit-protection` | enter + leave | Config-driven fixed-window rate limiting in the default stack; reads `(:rate-limit system)` + `(:cache system)`. Opt-in (default off). See [Rate Limiting](#rate-limiting) |
| `http-rate-limit(limit, window-ms, cache?)` | enter + leave | Fixed-limit form for explicit per-route use / standalone wiring (Redis or in-process) |

### Universal Cross-Cutting Interceptors

From `boundary.platform.shell.interceptors`:

| Interceptor | Purpose |
|-------------|---------|
| `context-interceptor` | Adds correlation-id, now timestamp, ensures `:op` present |
| `logging-start` / `logging-complete` / `logging-error` | Operation logging with timing |
| `metrics-start` / `metrics-complete` / `metrics-error` | Attempts, success, latency metrics |
| `error-capture` | Captures exceptions in error reporting system |
| `error-normalize` | Maps error types to HTTP status codes |
| `error-response-converter` | Converts failed contexts into HTTP error responses |
| `effects-dispatch` | Executes side effects from core function results |
| `response-shape-http` | Shapes results into HTTP responses (201 for creates) |
| `response-shape-cli` | Shapes results into CLI format with exit codes |

### Pipeline Templates

```clojure
;; Pre-built pipelines for common scenarios
base-observability-pipeline   ; context, logging, metrics, error-capture
error-handling-pipeline       ; error-normalize, error-response-converter
http-response-pipeline        ; error-handling + response-shape-http
cli-response-pipeline         ; error-handling + response-shape-cli

;; Create custom pipelines
(create-http-pipeline & custom-interceptors)   ; Complete HTTP pipeline
(create-cli-pipeline & custom-interceptors)    ; Complete CLI pipeline
(add-error-handling pipeline)                  ; Add error handling to any pipeline
```

---

## CSRF Protection

`http-csrf-protection` (in the default interceptor stack) protects session-cookie
authenticated, state-changing requests. Pure token functions live in
`boundary.platform.core.csrf`; the interceptor (shell) does validation, issuance,
and pre-session cookie minting.

### Token model

A token is `base64url(nonce).base64url(HMAC-SHA256(secret, nonce ‖ binding))`, signed
with `mac/hash` and verified in constant time with `mac/verify` (buddy). The `binding`
ties a token to one client so a forged cross-site request cannot produce a match:

- **Authenticated** requests bind to the session (`session-token` cookie or
  `X-Session-Token` header).
- **Unauthenticated `/web` flows** (login, register, MFA) bind to a `csrf-session`
  cookie (`SameSite=Strict`, `HttpOnly`) minted on the page GET and validated on POST.

### What is protected

A state-changing request (POST/PUT/DELETE/PATCH) is validated — **403** on missing or
invalid token — when CSRF is enabled, the path is not exempt, and it is either
session-authenticated or a `/web` route. This includes `/web/admin` and any
session-authenticated `/api` route. **Not** validated: safe methods (GET/HEAD/OPTIONS),
token-auth API clients sending no session cookie (not CSRF-vulnerable), and exempt
paths (webhooks/callbacks).

### Configuration

```clojure
;; resources/conf/<env>/config.edn under :active
:wagoe/http
{:security
 {:csrf {:enabled?     true                                  ; OPT-IN: lib default is false
         :secret       #or [#env CSRF_SECRET #env JWT_SECRET] ; defaults to JWT_SECRET
         :exempt-paths ["/api/v1/payments/webhook"]}}}        ; trailing /* = prefix match
```

**Enforcement is opt-in: the library default is `:enabled? false`** so a framework upgrade
can't 403 consumers that don't yet emit tokens. An app turns it on with the block above
(after emitting tokens in its `/web` forms). The secret falls back to `JWT_SECRET`; the
system wiring **fails loud** (throws, app refuses to boot) if enabled with a blank secret,
rather than letting the interceptor fail open. In this repo, dev/prod/acc
set `:enabled? true` explicitly; the test profile ships `:enabled? false` so the broad suite
need not carry tokens (CSRF-specific tests enable it explicitly).

### Emitting tokens in views

The interceptor exposes the token on the request as `:anti-forgery-token` and binds
`csrf/*token*` around handler execution (Hiccup renders to a string synchronously
inside the handler), so views emit it with no per-handler threading:

- **HTMX** — either (a) merge `(csrf/hx-headers)` (0-arity reads `*token*`) onto an
  element's attrs, e.g. `<body>`, so all inherited `hx-*` requests carry the header; or
  (b) rely on the shared `page-layout`'s `<meta name="csrf-token">` + the global
  `htmx:configRequest` listener in `init.js`, which attaches `X-CSRF-Token` to every HTMX
  request (new HTMX actions then need nothing).
- **Plain `<form method=post>`** — splice `(csrf/hidden-field)` (0-arity reads
  `*token*`) as the first child.

Test helper: `support.handler-test-helpers/with-valid-csrf-token` signs a token bound
to a request's session/pre-session binding for CSRF-enabled handler tests.

### Ring consumers (handlers outside the interceptor stack)

`http-csrf-protection` only protects handlers that run **through** the default
interceptor stack. An app that mounts its own routes as a Ring handler **in front of**
the platform handler (matching and serving requests before they reach the interceptor
chain) bypasses CSRF entirely — `csrf/*token*` is never bound (so `hidden-field` /
`<meta>` emit nothing) and POSTs are never validated.

For that case, wrap the bypassing handler with `interceptors/wrap-csrf`, the Ring
form of the interceptor (same binding model, same opt-in/exempt/safe-method rules):

```clojure
(require '[boundary.platform.shell.http.interceptors :as interceptors])

;; csrf-config is the same {:enabled? :secret :exempt-paths} map read from
;; [:active :wagoe/http :security :csrf]; thread it in from system config.
(def app-web-handler
  (interceptors/wrap-csrf my-web-routes-handler csrf-config))
```

It returns 403 on a bad/absent token for state-changing requests and binds
`csrf/*token*` around the handler on safe/authenticated requests, so `hidden-field`,
`hx-headers`, and `page-layout`'s `<meta>` work exactly as under the interceptor.
Disabled or secretless config makes it a pass-through.

---

## Route Configuration

### Route Specification Format

```clojure
{:path    "/api/users"
 :methods {:get  {:handler  'boundary.user.shell.http/list-users-handler
                  :summary  "List users"
                  :tags     ["users"]
                  :parameters {:query [:map [:limit {:optional true} :int]]}}
           :post {:handler  'boundary.user.shell.http/create-user-handler
                  :summary  "Create user"
                  :tags     ["users"]
                  :coercion {:body CreateUserRequest}}}}
```

### Creating a Router

```clojure
(require '[boundary.platform.shell.interfaces.http.routes :as routes])

;; Creates complete Reitit router with health, api-docs, and your routes
(def router
  (routes/create-router config module-routes {:api-prefix "/api/v1"}))

;; Creates Ring handler from router
(def handler
  (routes/create-handler router {}))

;; Complete app in one call
(def app
  (routes/create-app config module-routes {:api-prefix "/api/v1"}))
```

### Per-Route Interceptors

```clojure
;; Define a custom interceptor
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response {:status 403 :body {:error "Forbidden"}})))
   :leave (fn [ctx] ctx)
   :error (fn [ctx _error]
            (assoc ctx :response {:status 500 :body {:error "Internal error"}}))})

;; Apply to specific routes
[{:path    "/api/admin"
  :methods {:post {:handler     'handlers/create-resource
                   :interceptors [require-admin
                                  'audit/log-action
                                  (http-rate-limit 10 60000 cache)]
                   :summary     "Create admin resource"}}}]
```

---

## Database Integration

The platform library provides database context and connection pooling:

```clojure
;; Integrant key: :wagoe/db-context
;; Config in resources/conf/{env}/config.edn
{:wagoe/db-context
 {:jdbc-url #env ["DATABASE_URL" "jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1"]}}
```

The `db-context` map is injected into services via Integrant:

```clojure
;; In your module's shell/service.clj
(defrecord MyService [db-context user-repo])

;; db-context has :datasource key for next.jdbc
(next.jdbc/execute! (:datasource db-context) ["SELECT * FROM my_table"])
```

---

## CLI Wiring

The platform library manages CLI entry and system wiring via Integrant keys:

```clojure
;; Start the system (use in REPL)
(require '[integrant.repl :as ig-repl])
(ig-repl/go)     ; Start
(ig-repl/reset)  ; Reload and restart
(ig-repl/halt)   ; Stop
```

---

## Error Type → HTTP Status Mapping

Exceptions thrown with `:type` in `ex-data` are automatically converted:

| `:type` | HTTP Status |
|---------|------------|
| `:validation-error` | 400 |
| `:unauthorized` | 401 |
| `:forbidden` | 403 |
| `:not-found` | 404 |
| `:conflict` | 409 |
| `:internal-error` | 500 |

```clojure
;; Always include :type in ex-info calls
(throw (ex-info "User not found"
                {:type :not-found
                 :user-id id}))
```

---

## Rate Limiting

### Default pipeline (config-driven)

`http-rate-limit-protection` runs in the default interceptor stack for every
route. It is **opt-in** — disabled unless configured, so a framework upgrade
cannot start 429-ing existing consumers. Configure per env under
`:wagoe/http`:

```clojure
:wagoe/http
{:rate-limit {:enabled?  true
              :limit     300       ; max requests per window per client
              :window-ms 60000}}   ; window length
;; env overrides: HTTP_RATE_LIMIT, HTTP_RATE_LIMIT_WINDOW_MS
```

The wiring injects the policy and the `:wagoe/cache` into the interceptor
`system` map automatically. **With an active Redis cache the limit is shared
across replicas** (fixed-window counter keyed in Redis). **With no cache it
falls back to a per-process counter — correct on a single node only; across N
replicas the effective global limit is `limit × N`.** The wiring logs a warning
at startup when rate limiting is enabled without a cache. The bundled prod/acc
configs ship rate limiting **disabled**; enable it only together with an active
Redis cache by setting `HTTP_RATE_LIMIT_ENABLED=true` (the config wires that env
var via `#boolean`), so production never silently runs the per-process fallback.

The in-process fallback is **heap-bounded by a hard cap**: the tracked-client map
can never exceed `max-tracked-clients` (10000). Before a *new* client is recorded
at the cap, stale clients (no requests in the window) are swept; if the map is
still full — i.e. every tracked client is in-window — the least-recently-active
client is evicted to make room. So even a sustained stream of fresh client ids
(rotating API keys, many remote addresses) cannot grow the state map past the cap
on a long-running node.

Client identity is resolved as user id → `x-api-key` header → remote address.

### Explicit / per-route form

```clojure
;; With Redis cache (distributed, recommended for production)
(http-rate-limit 100 60000 cache-service)   ; 100 req/min per IP

;; Without cache (in-process fallback, single-node only)
(http-rate-limit 100 60000)

;; Returns 429 Too Many Requests when limit exceeded.
;; Response includes Retry-After + X-RateLimit-* headers.
```

---

## Gotchas

- Ensure interceptor response structures match HTMX target contracts for fragment endpoints.
- Keep exceptions typed (`:type` in `ex-data`) so HTTP error mapping stays deterministic.
- Static resource routes bypass content negotiation — serve from `/public` directory.
- Rate limiting without Redis is per-process only and resets on restart.

---

## Testing

```bash
clojure -M:test:db/h2 :platform
clojure -M:test:db/h2 :platform --focus-meta :unit
clojure -M:test:db/h2 :platform --focus-meta :integration
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
