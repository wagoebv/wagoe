# Observability Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Cross-cutting observability for services and persistence layers: structured logging, metrics, tracing breadcrumbs, and error reporting — without polluting core business logic.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.observability.logging.ports` | `ILogger`, `IAuditLogger` protocols |
| `wagoe.observability.errors.ports` | `IErrorReporter`, `IErrorContext`, `IErrorFilter` protocols |
| `wagoe.observability.metrics.ports` | `IMetricsRegistry`, `IMetricsEmitter` protocols |
| `wagoe.observability.tracing.ports` | `ITracer` protocol (spans) |
| `wagoe.observability.tracing.core` | `with-span` macro |
| `wagoe.observability.shell.service-interceptors` | Service-layer operation wrappers |
| `wagoe.observability.shell.persistence-interceptors` | Persistence-layer query wrappers |
| `wagoe.observability.shell.adapters.*` | Provider implementations (no-op, Datadog, Sentry) |

---

## Multi-Layer Interceptor Pattern

The recommended way to add observability is to wrap operations with interceptors. This gives automatic logging, metrics, error reporting, and breadcrumbs with no per-call boilerplate.

### Service Layer

```clojure
(require '[wagoe.observability.shell.service-interceptors :as service-interceptors])

;; Wrap each service method call
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
   :create-user
   {:user-data user-data}
   (fn [{:keys [params]}]
     ;; Business logic here — observability is automatic
     (let [user (user-core/prepare-user (:user-data params))]
       (ports/save-user (:repository this) user)))))
```

The interceptor automatically:
- Logs operation start, success, and failure
- Records latency metrics
- Captures exceptions to the error reporter
- Adds breadcrumbs for distributed tracing

### Persistence Layer

```clojure
(require '[wagoe.observability.shell.persistence-interceptors :as persistence-interceptors])

;; Wrap each database call
(defn find-user-by-email [this email]
  (persistence-interceptors/execute-persistence-operation
   (:logger this) (:error-reporter this)
   "find-user-by-email"
   {:email email}
   (fn []
     ;; Database query here
     (jdbc/execute-one! (:datasource (:ctx this)) query))))
```

---

## Protocols Reference

### ILogger

```clojure
;; In wagoe.observability.logging.ports
(defprotocol ILogger
  (log* [this level message context exception])
  (trace [this message] [this message context])
  (debug [this message] [this message context])
  (info  [this message] [this message context])
  (warn  [this message] [this message context] [this message context exception])
  (error [this message] [this message context] [this message context exception])
  (fatal [this message] [this message context] [this message context exception]))
```

#### Log Context Map

```clojure
{:correlation-id string  ; Request correlation ID (auto-injected by HTTP pipeline)
 :request-id    string   ; HTTP request ID
 :tenant-id     string   ; Multi-tenant context
 :user-id       string   ; Authenticated user (if available)
 :span-id       string   ; Distributed tracing span ID
 :trace-id      string   ; Distributed tracing trace ID
 :tags          map}     ; Additional structured tags
```

### IAuditLogger

```clojure
(defprotocol IAuditLogger
  (audit-event [this event-type actor resource action result context])
  (security-event [this event-type severity details context]))
```

### IErrorReporter

```clojure
(defprotocol IErrorReporter
  (capture-exception [this exception] [this exception context] [this exception context tags])
  (capture-message   [this message level] [this message level context] [this message level context tags])
  (capture-event     [this event-map]))
```

### IErrorContext

```clojure
(defprotocol IErrorContext
  (with-context    [this context-map f])
  (add-breadcrumb! [this breadcrumb])
  (clear-breadcrumbs! [this])
  (set-user!       [this user-info])
  (set-tags!       [this tags])
  (set-extra!      [this extra])
  (current-context [this]))
```

### IMetricsEmitter

```clojure
(defprotocol IMetricsEmitter
  (inc-counter!      [this metric-handle] [this metric-handle value] [this metric-handle value tags])
  (set-gauge!        [this metric-handle value] [this metric-handle value tags])
  (observe-histogram![this metric-handle value] [this metric-handle value tags])
  (time-histogram!   [this metric-handle f] [this metric-handle tags f])
  (time-summary!     [this metric-handle f] [this metric-handle tags f]))
```

---

## Tracing

Backend-agnostic spans behind `ITracer`. Wrap work with `with-span`; the span is
started, has exceptions recorded + rethrown, and is always ended:

```clojure
(require '[wagoe.observability.tracing.core :refer [with-span]]
         '[wagoe.observability.tracing.ports :as t])

(with-span tracer [sp "handle-order" {:order-id id}]
  (t/add-event! tracer sp "validated")
  (process! order))
```

Providers via `:wagoe/tracing {:provider …}`:

| Provider | Behaviour |
|----------|-----------|
| `:no-op` (default) | Inert. Satisfies the port; records nothing. |
| `:logging` | Logs span start/end + duration; dev visibility, not exportable. |
| `:otlp` | Real OpenTelemetry export over OTLP/HTTP to **any** OTel collector — SigNoz, Grafana Tempo, Jaeger, Honeycomb, Datadog-via-OTel. Only the endpoint changes. |

### Automatic spans

- **HTTP** — the platform `http-request-tracing` interceptor starts a span per
  request (`"HTTP <METHOD> <path>"`, tagged method/path/correlation-id/status);
  no-op unless a tracer is wired, so it is always-on safe.
- **Jobs** — the worker wraps each job execution in a `"job <type>"` span when a
  `:tracer` is present in the worker config.

Each is currently a per-unit **root** span; cross-thread parent/child linking
(request → service → persistence) is a later enhancement (needs context support
on the `ITracer` port).

### `:otlp` configuration

```clojure
;; resources/conf/prod/config.edn
{:wagoe/tracing {:provider     :otlp
                    ;; OTEL_EXPORTER_OTLP_ENDPOINT base; /v1/traces is appended.
                    :endpoint     #or [#env OTEL_EXPORTER_OTLP_ENDPOINT "http://localhost:4318"]
                    :protocol     :http/protobuf   ; only HTTP is bundled (no gRPC)
                    :service-name #or [#env OTEL_SERVICE_NAME "wagoe"]
                    :timeout-ms   10000}}
```

Transport is OTLP/HTTP protobuf (okhttp sender bundled with
`opentelemetry-exporter-otlp`). gRPC is intentionally **not** bundled — it would
add grpc-netty to every classpath. The SDK is flushed + shut down on Integrant
halt so buffered spans are exported before exit.

## Provider Configuration

### Automatic HTTP metrics

The platform `http-request-metrics` interceptor emits, per request, on whatever
provider is active (registered once at wiring, so nothing is emitted through a
no-op stub):

| Metric | Type | Labels |
|--------|------|--------|
| `http.requests` | counter | `method`, `status` |
| `http.requests.errors` | counter | `method`, `status` |
| `http.request.duration` | histogram (seconds) | `method`, `status` |

`http.requests` counts **every** response (success and error), so error rate is
`http.requests.errors / http.requests`. No-op safe: with `:provider :no-op`
(default) these are inert.

### Prometheus (`/metrics` scrape)

Pure-Clojure in-memory registry (`metrics.shell.adapters.prometheus`) that renders
the Prometheus text exposition format — no external client dependency. When
`:wagoe/metrics {:provider :prometheus}` is active, platform mounts a
**`GET /metrics`** endpoint that serves the scrape output (counters, gauges,
histograms with `_bucket`/`_sum`/`_count`). Other providers leave `/metrics`
returning an empty body.

```clojure
;; resources/conf/prod/config.edn
{:wagoe/metrics {:provider :prometheus
                    :include-help-text true
                    ;; optional default histogram buckets (seconds)
                    :histogram-buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}}
```

Point a Prometheus/Grafana Agent (or any OpenMetrics scraper) at `GET /metrics`.

### OTLP (push to an OpenTelemetry collector)

Bridges the metrics ports onto OpenTelemetry instruments (counter→LongCounter,
gauge→DoubleGauge, histogram/summary→DoubleHistogram) and pushes them over
OTLP/HTTP on a fixed interval. No backend-specific code — the same exporter
feeds SigNoz, Grafana, Datadog-via-OTel, etc.

```clojure
;; resources/conf/prod/config.edn
{:wagoe/metrics {:provider     :otlp
                    :endpoint     #or [#env OTEL_EXPORTER_OTLP_ENDPOINT "http://localhost:4318"]
                    :protocol     :http/protobuf
                    :service-name #or [#env OTEL_SERVICE_NAME "wagoe"]
                    :interval-ms  60000        ; push interval to the collector
                    :timeout-ms   10000}}
```

Metrics are push-only, so the `IMetricsExporter` local-render methods
(`export-metrics`) throw; `flush!` forces an OTLP flush (call on shutdown).

### SigNoz (self-hosted OTLP validation target)

SigNoz is OpenTelemetry-native — it ingests standard OTLP, so **no SigNoz adapter
exists**. Point `:otlp` at a local SigNoz to see traces + metrics land:

```bash
git clone -b main https://github.com/SigNoz/signoz.git
cd signoz/deploy/docker && docker compose up -d      # UI on http://localhost:3301
```

```bash
# App → SigNoz OTLP receiver (HTTP)
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318"
export OTEL_SERVICE_NAME="my-app"
```

Set both `:wagoe/tracing` and `:wagoe/metrics` to `:provider :otlp`. The
same env vars retarget Grafana Tempo, Jaeger, Honeycomb, or Datadog-via-OTel —
only the endpoint changes.

### no-op (Development and Tests)

The default provider — all operations are silent. No configuration required.

```clojure
;; resources/conf/dev/config.edn
{:wagoe/observability
 {:logger         {:type :no-op}
  :error-reporter {:type :no-op}
  :metrics        {:type :no-op}}}
```

### Datadog

```clojure
;; resources/conf/prod/config.edn
{:wagoe/observability
 {:metrics {:type    :datadog
            :api-key #env DATADOG_API_KEY
            :host    #env ["DATADOG_HOST" "datadoghq.com"]
            :tags    {:env #env WAG_ENV
                      :service "wagoe"}}}}
```

### Sentry

```clojure
;; resources/conf/prod/config.edn
{:wagoe/observability
 {:error-reporter {:type  :sentry
                   :dsn   #env SENTRY_DSN
                   :env   #env WAG_ENV
                   :release #env APP_VERSION}}}
```

---

## Writing a Custom Adapter

Implement the protocols for your observability provider:

```clojure
(ns my-app.observability.adapters.my-logger
  (:require [wagoe.observability.logging.ports :as ports]))

(defrecord MyLogger [config]
  ports/ILogger
  (log* [this level message context exception]
    ;; Send to your logging backend
    (my-backend/log {:level   level
                     :message message
                     :context context
                     :error   exception}))
  (info [this message]
    (log* this :info message {} nil))
  (info [this message context]
    (log* this :info message context nil))
  ;; ... implement remaining methods
  )

(defn create-my-logger [config]
  (->MyLogger config))
```

Register it in your Integrant config:

```clojure
;; In your system config
{:wagoe/observability {:logger (my-app.observability.adapters.my-logger/create-my-logger
                                   {:endpoint "..."})}}}
```

---

## Where to Place Interceptors

**Rule**: Wrap at the service boundary (one level above the database) and the persistence boundary (one level above the SQL/driver call). Never wrap inside core functions.

```
HTTP handler
  └── service interceptor   ← service-interceptors/execute-service-operation
       └── core functions    ← no observability here
       └── persistence
            └── persistence interceptor  ← persistence-interceptors/execute-persistence-operation
                 └── JDBC/SQL call
```

---

## Gotchas

- Wrap operations at boundaries only — avoid duplicating interceptor execution inside already-instrumented paths.
- Keep PII out of logs and breadcrumbs — sanitize before passing `params` to interceptors.
- No-op adapters are used in tests automatically; never hardcode provider-specific calls in core.
- `add-breadcrumb!` is thread-local in most implementations — don't share error context across threads.

---

## Testing

```bash
clojure -M:test :observability
clojure -M:test :observability --focus-meta :unit
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
