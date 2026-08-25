# HTTP Interface Module

Shared HTTP utilities: RFC 7807 error responses, health check handlers, and a
set of Ring middleware.

> **This is not how you build an application's route table.** Routes reach the
> router by module contribution — see [Contributing routes](#contributing-routes)
> below. Until BOU-358 this README documented a `routes/create-app` entry point
> that nothing wired and that applied none of the framework's security stack.
> That namespace is gone.

```
wagoe.platform.shell.interfaces.http/
├── common.clj      # RFC 7807 problem details, health check handlers
└── middleware.clj  # Ring middleware (see the note under Middleware)
```

## Contributing routes

An application does not build its own router. Each module returns a route
contribution from its `:wagoe/<name>-routes` component, and
`:wagoe/http-handler` merges every module's contribution and compiles the result
through `wagoe.platform.shell.http.reitit-router/compile-routes` — the one path
that attaches the security stack, the interceptor pipeline, coercion and the
Swagger routes.

```clojure
(ns my-module.shell.http)

(defn api-routes [service]
  ;; Paths are relative to the mount point: :api is versioned under /api/v1,
  ;; so do not write the prefix yourself.
  [["/items"     {:get {:handler (list-items service) :summary "List items"}}]
   ["/items/:id" {:get {:handler (get-item service)   :summary "Get an item"}}]])

(defn my-routes [service config]
  {:api    (api-routes service)
   :web    (web-routes service config)   ; mounted under /web
   :static []})
```

Health checks and `/swagger.json` are mounted by `:wagoe/http-handler`; a module
does not add them. `bb scaffold endpoint` writes the shape above.

## Common utilities (`common.clj`)

### RFC 7807 problem details

```clojure
(require '[wagoe.platform.shell.interfaces.http.common :as http-common])

(http-common/exception->problem
  (ex-info "User not found" {:type :user-not-found})
  correlation-id
  request-uri)
;; => {:status 404
;;     :body {:type "https://wagoe.example.com/problems/user-not-found"
;;            :title "User Not Found"
;;            :status 404
;;            :detail "User not found"
;;            :instance "/api/users/123"
;;            :correlation-id "abc-123"}}
```

### Health check handlers

`:wagoe/http-handler` resolves these itself to mount `/health`, `/health/ready`
and `/health/live`; they are here for a caller that needs one directly.

```clojure
(def health-handler
  (http-common/health-check-handler "my-service" "1.0.0" additional-checks))

(def not-found-handler
  (http-common/create-not-found-handler))
```

## Middleware (`middleware.clj`)

`wrap-correlation-id`, `wrap-request-logging`, `wrap-exception-handling`,
`wrap-tenant-context`, `wrap-user-context`, `wrap-observability-context`.

> **No application runs these.** The wired path builds correlation ids, request
> logging and exception handling in
> `wagoe.platform.shell.http.interceptors`, and `routes.clj` was this
> namespace's only caller. What remains of it after BOU-358 is a follow-up
> decision, not a supported surface. Do not reach for it in new code.

## Standard endpoints

Mounted by `:wagoe/http-handler` on every application:

| Path | Purpose |
|---|---|
| `/health` | Full status, service name and version, plus any additional checks |
| `/health/ready` | Readiness probe |
| `/health/live` | Liveness probe |
| `/swagger.json` | OpenAPI specification |
| `/api-docs/` | Swagger UI |

## Dependencies

Reitit, Ring, Muuntaja, Malli, Integrant.
