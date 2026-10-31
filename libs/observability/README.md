# wagoe/observability

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-observability.svg)](https://clojars.org/com.wagoe/wagoe-observability)

Unified observability stack with pluggable adapters for logging, metrics, tracing and error reporting.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-observability {:mvn/version "1.1.1"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-observability "1.1.1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Structured Logging** | JSON-structured logging with context propagation |
| **Metrics Collection** | Counters, gauges, histograms with tags |
| **Tracing** | Spans with `with-span`, exported over OTLP/HTTP |
| **Error Reporting** | Exception tracking with breadcrumbs |
| **Protocol-based** | Easy to implement custom adapters |
| **Multiple Adapters** | No-op, stdout, SLF4J, Prometheus, OTLP, Datadog, Sentry |

## Requirements

- Clojure 1.12+
- wagoe/core

## Adapters

### Logging Adapters

`:provider` on each Integrant key picks the adapter; an unknown one logs a warning and falls back to `:no-op`.

| `:provider` | Use Case |
|---------|----------|
| `:no-op` | Testing, disabled logging |
| `:stdout` | Development, debugging (text or JSON) |
| `:slf4j` | Production with Logback |

A Datadog logging adapter exists (`create-datadog-logging-component`) but is not selectable in config.

### Metrics Adapters

| `:provider` | Use Case |
|---------|----------|
| `:no-op` | Testing, disabled metrics |
| `:prometheus` | In-memory registry served at `GET /metrics` |
| `:otlp` | Push to an OpenTelemetry collector |
| `:datadog-statsd` | Datadog over DogStatsD (UDP) |

### Tracing Adapters

| `:provider` | Use Case |
|---------|----------|
| `:no-op` | Records nothing |
| `:logging` | Span start/end in the logs |
| `:otlp` | Export to an OpenTelemetry collector |

### Error Reporting Adapters

| `:provider` | Use Case |
|---------|----------|
| `:no-op` | Testing, disabled reporting |
| `:sentry` | Sentry.io integration |

## Quick Start

### Logging

```clojure
(ns myapp.core
  (:require [wagoe.observability.logging.ports :as log]
            [wagoe.observability.logging.shell.adapters.stdout :as stdout]))

;; Create logger
(def logger (stdout/create-stdout-logger {:level :info}))

;; Log messages
(log/info logger "User logged in" {:user-id "123" :ip "192.168.1.1"})
(log/warn logger "Rate limit approaching" {:current 95 :max 100})
(log/error logger "Database connection failed" {:host "db.example.com"} exception)
```

### Metrics

```clojure
(ns myapp.metrics
  (:require [wagoe.observability.metrics.ports :as metrics]
            [wagoe.observability.metrics.shell.adapters.prometheus :as prometheus]))

;; Create metrics component (registry + emitter + exporter)
(def m (prometheus/create-metrics-component {}))

;; Emit metrics; these register the metric on first use
(metrics/increment m "http.requests" {:method "GET"})
(metrics/gauge m "db.connections.active" 5 {:pool "main"})
(metrics/observe m "http.response.time" 0.125 {:endpoint "/api/users"})
```

### Error Reporting

```clojure
(ns myapp.errors
  (:require [wagoe.observability.errors.ports :as errors]
            [wagoe.observability.errors.shell.adapters.no-op :as noop]))

;; Create error reporter (reporter + context + filter + config)
(def reporter (noop/create-error-reporting-component {}))

;; Add breadcrumbs for context
(errors/add-breadcrumb! reporter {:category "http" :message "GET /api/users"})
(errors/add-breadcrumb! reporter {:category "db" :message "SELECT * FROM users"})

;; Report exception
(try
  (dangerous-operation)
  (catch Exception e
    (errors/capture-exception reporter e {:user-id "123"})))
```

### Integration Configuration

```clojure
;; config.edn with Integrant
{:wagoe/logging
 {:provider :slf4j
  :level    :info}

 :wagoe/metrics
 {:provider :datadog-statsd
  :host     #or [#env DD_AGENT_HOST "127.0.0.1"]
  :port     8125
  :service  "myapp"}

 :wagoe/tracing
 {:provider :no-op}

 :wagoe/error-reporting
 {:provider    :sentry
  :dsn         #env SENTRY_DSN
  :environment "production"}}
```

See [AGENTS.md](AGENTS.md) for what each provider honours.

## Module Structure

```
libs/observability/src/wagoe/observability/
├── core/sql_redaction.clj    # Redacts SQL literals before logging
├── logging/
│   ├── ports.clj             # ILogger, IAuditLogger, ILoggingContext, ILoggingConfig
│   ├── core.clj              # Pure helpers
│   └── shell/adapters/
│       ├── no_op.clj
│       ├── stdout.clj
│       ├── slf4j.clj
│       └── datadog.clj
├── metrics/
│   ├── ports.clj             # IMetricsRegistry, IMetricsEmitter, IMetricsExporter, IMetricsConfig
│   ├── core.clj              # Pure helpers
│   └── shell/adapters/
│       ├── no_op.clj
│       ├── prometheus.clj
│       ├── otlp.clj
│       └── datadog.clj
├── tracing/
│   ├── ports.clj             # ITracer
│   ├── core.clj              # with-span
│   └── shell/adapters/
│       ├── no_op.clj
│       ├── logging.clj
│       └── otlp.clj
└── errors/
    ├── ports.clj             # IErrorReporter, IErrorContext, IErrorFilter, IErrorReportingConfig
    ├── core.clj              # Pure helpers and filter rules
    └── shell/adapters/
        ├── no_op.clj
        └── sentry.clj
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/core` | 1.1.1 | Foundation utilities |
| `org.clojure/tools.logging` | 1.3.1 | Logging abstraction |
| `ch.qos.logback/logback-classic` | 1.6.3 | SLF4J implementation |
| `io.sentry/sentry-clj` | 8.34.247 | Sentry integration |
| `io.opentelemetry/opentelemetry-sdk`, `-exporter-otlp` | 1.67.0 | OTLP traces and metrics |
| `metosin/malli` | 0.20.1 | Config schemas |
| `cheshire/cheshire` | 6.2.0 | JSON logging |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│      platform, user, admin, etc.        │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│         wagoe/observability          │
│ (logging, metrics, tracing, errors)     │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│            wagoe/core                │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/observability
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
