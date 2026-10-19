# Platform Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Infrastructure layer for HTTP routing/interceptors, database integration, CLI/runtime wiring, and shared platform adapters used by feature modules.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.platform.shell.http.interceptors` | HTTP-specific interceptor pipeline execution |
| `wagoe.platform.shell.interceptors` | Universal cross-cutting interceptors (logging, metrics, error) |
| `wagoe.platform.shell.http.reitit-router` | Compiles module route contributions into the Ring handler |
| `wagoe.platform.ports.http` | HTTP router and server protocols |
| `wagoe.platform.db.*` | Database context, connection pooling, shared persistence utilities |
| `wagoe.platform.lease` | A lease row replicas take turns holding, timed by the database clock (jobs ticker, outbox relay) |

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

From `wagoe.platform.shell.http.interceptors`:

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

From `wagoe.platform.shell.interceptors`:

| Interceptor | Purpose |
|-------------|---------|
| `context-interceptor` | Adds correlation-id, now timestamp, ensures `:op` present |
| `logging-start` / `logging-complete` / `logging-error` | Operation logging with timing |
| `op-metrics` | Attempt, success/error, failure and latency per op, on `:metrics-emitter`. Built from `metrics-start` / `-complete` / `-error` |
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
`wagoe.platform.core.csrf`; the interceptor (shell) does validation, issuance,
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
(require '[wagoe.platform.shell.http.interceptors :as interceptors])

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

Reitit route data — `[path data & children]` — with handlers as vars, so a typo
is a lint error rather than a boot failure (ADR-037). Paths are relative: `/api/v1`
is added when the module's `:api` contribution is mounted.

```clojure
["/users"
 {:get  {:handler    user-http/list-users-handler
         :summary    "List users"
         :tags       ["users"]
         :parameters {:query [:map [:limit {:optional true} :int]]}}
  :post {:handler  user-http/create-user-handler
         :summary  "Create user"
         :tags     ["users"]
         :coercion {:body CreateUserRequest}}}]
```

### Contributing Routes

A module does not build a router — it returns a route contribution, and
`:wagoe/http-handler` compiles every module's together. That is the only path
that attaches the security stack, interceptors, coercion and the OpenAPI docs.

```clojure
(defn my-routes [service config]
  ;; Paths are relative to the mount point: :api is versioned under /api/v1
  ;; and :web is mounted under /web, so do not write the prefix yourself.
  {:api    [["/items" {:get {:handler (list-items service)}}]]
   :web    (web-routes service config)
   :static []})
```

Health checks and the OpenAPI document (`/openapi.json`, UI at `/api-docs/`) are mounted for you.

### Adding components and middleware to an app module

Discovery builds a scaffolded module's four keys (`:wagoe/<name>`, `-repository`,
`-service`, `-routes`). To add to them, define `ig-config` in the module's
`module_wiring.clj` and return only what you add:

```clojure
(defn ig-config [_settings _ctx]
  {:components {:wagoe/orders-ip-filter {:allow ["10.0.0.0/8"]}
                ;; merged into the scaffold's service, which keeps :repository
                :wagoe/orders-service   {:filter (ig/ref :wagoe/orders-ip-filter)}}
   :http       {:extra-middleware (ig/ref :wagoe/orders-ip-filter)}})
```

- A component the scaffold also builds is merged, your keys winning.
- Wire `:wagoe/<name>` yourself and the module owns its whole graph: no scaffold keys.
- `:extra-middleware` is a middleware fn or a seq of them. Every module may set
  one. They run outermost first: framework modules, then app modules, each group
  sorted by module key, then the handler's own `:extra-middleware`.
- Any other `:http` key is a single slot; two modules setting it fail the boot.
- `:auth {:mfa-required? … :login-redirect …}` hands sign-in hooks, often refs to
  your components, to the user module's `:wagoe/auth-hooks`. One module per hook;
  contributing one without the user module fails the boot. See `libs/user/AGENTS.md`.
- `:search {index-id definition}` hands search index definitions, or refs to
  components returning them, to `:wagoe/search`. One module per index id;
  contributing one without the search module fails the boot. See `libs/search/AGENTS.md`.

### Authentication is the default

Every route requires a signed-in user (`:user` on the request) unless its
route data says `:public true`. An anonymous caller gets a 401, or a redirect
to `/web/login` for a `/web` page, before the body is decoded (BOU-568).
`:public` may sit on the method or on the path:

```clojure
["/webhooks/stripe" {:post {:handler stripe-webhook :public true}}]  ; one method
["/status"          {:public true :get {:handler status}}]           ; the whole path
```

Mark a route public only when something else is its credential: a login form,
a signed webhook, a token in the path. Roles are still the module's to check;
`wagoe.platform.core.http.access` has `admin?` and the 403 shape.

### Permission registry

ADR-041 §1–2. At boot the platform collects one registry,
`:wagoe/permission-registry` (`wagoe.platform.ports.authorization`), from what
each module declares under `:authorization` in its `ig-config` (a scaffolded
module: a `def authorization` in its wiring namespace):

```clojure
{:permissions {:orders/refund {:label "Refund orders"}}
 :roles       {:orders/manager {:description "Everything in orders"
                                :permissions #{:orders/read :orders/write :orders/refund}}}
 :scopes      {:domain-id {:label "Domain" :entity :domains}}}
```

- Derived, never declared: `<module>/read` and `<module>/write` for every enabled
  module, and `admin.<entity>/read|write` for every admin entity.
- The `:wagoe/user :groups :permissions` enum is read in too, until 2.0.
- Two modules declaring one permission, role or differing scope key fail boot.
  So do a role or a route `:permission` naming something outside the registry,
  and a code role outside the admin module holding an `admin.*` permission.
- Every route carries `:wagoe/module` in its route data: the module key whose
  contribution it came from.
- `bb scaffold` writes `<module>/viewer`, `/editor` and `/manager`.
- Nothing is enforced yet: authorization behaves as before.

```clojure
(require '[wagoe.platform.ports.authorization :as authz])
(authz/roles (:wagoe/permission-registry (wagoe.platform.shell.system.wiring/system)))
```

### Per-Route Interceptors

```clojure
;; Define a custom interceptor. errors = wagoe.platform.core.http.errors,
;; which builds the one JSON error body.
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response (errors/response 403 :forbidden "Admin role required"))))
   :leave (fn [ctx] ctx)
   :error (fn [ctx _error]
            (assoc ctx :response (errors/response 500 :internal-error "Internal Server Error")))})

;; Apply to specific routes
[["/admin"
  {:post {:handler      handlers/create-resource
          :interceptors [require-admin
                         'audit/log-action
                         (http-rate-limit 10 60000 cache)]
          :summary      "Create admin resource"}}]]
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

Writing a row by its key: `(db/upsert! ctx :table row [:key-col] {:update-columns [...]})`
updates or inserts, retrying a lost insert race and, outside a transaction, a
deadlock (MySQL 1213). A case-sensitive key on MySQL needs
`(db/ensure-binary-collation! ctx :table :col "VARCHAR(512)" not-null?)`.

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
(many remote addresses) cannot grow the state map past the cap
on a long-running node.

The bucket is `user:<id>` for a signed-in request, else `ip:<client ip>`. No
header picks it: an unchecked `x-api-key` once did (BOU-690). A client the proxy
did not name (see below) goes in `unresolved:<proxy address>`: not exempt, and
apart from the named clients behind that proxy.

### Client IP

`wrap-client-ip`, outermost in the handler, sets `:client-ip` on every request.
Read it through `wagoe.platform.core.http.client-ip/request-ip`, never
`:remote-addr` or `X-Forwarded-For` directly. `X-Forwarded-For` is honoured only
when the peer is in `:wagoe/http :trusted-proxies` (CIDRs, default empty); the
client is then the right-most hop that is not a trusted proxy. An invalid CIDR
fails the boot.

When the hop the walk stops at is not an address (`unknown`), `request-ip` is
nil, not the proxy: the proxy's address would put an outside client inside the
proxy's network (BOU-695). Treat nil as "not allowed" wherever you authorise by
address.

Match addresses with `(client-ip/in-network? ip cidr)` and
`(client-ip/in-any? ip cidrs)` rather than a parser of your own. Strings or
parsed values; ports, brackets and zone ids are ignored, IPv4-mapped IPv6 is
IPv4, and anything unparseable (nil included) is false. Parse configured ranges
once with `parse-trusted-proxies` so a typo fails loudly instead of matching
nothing.

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
- `:virtual-threads? true` on `:wagoe/http` or `:wagoe/rpc` serves requests on virtual threads. Off by default; see the virtual threads section of `docs/modules/architecture/pages/scaling.adoc`.

---

## Testing

```bash
clojure -M:test:test/pg :platform
clojure -M:test:test/pg :platform --focus-meta :unit
clojure -M:test:test/pg :platform --focus-meta :integration
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
