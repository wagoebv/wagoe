# wagoe/observability

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-observability.svg)](https://clojars.org/org.wagoe/wagoe-observability)

Unified observability stack with pluggable adapters for logging, metrics, and error reporting.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {org.wagoe/wagoe-observability {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[org.wagoe/wagoe-observability "1.0.0-beta-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Structured Logging** | JSON-structured logging with context propagation |
| **Metrics Collection** | Counters, gauges, histograms with tags |
| **Error Reporting** | Exception tracking with breadcrumbs |
| **Protocol-based** | Easy to implement custom adapters |
| **Multiple Adapters** | No-op, stdout, SLF4J, Datadog, Sentry |

## Requirements

- Clojure 1.12+
- wagoe/core

## Adapters

### Logging Adapters

| Adapter | Use Case |
|---------|----------|
| `noop` | Testing, disabled logging |
| `stdout` | Development, debugging |
| `slf4j` | Production with Logback/Log4j |
| `datadog` | Datadog APM integration |

### Metrics Adapters

| Adapter | Use Case |
|---------|----------|
| `noop` | Testing, disabled metrics |
| `datadog` | Datadog metrics |

### Error Reporting Adapters

| Adapter | Use Case |
|---------|----------|
| `noop` | Testing, disabled reporting |
| `sentry` | Sentry.io integration |

## Quick Start

### Logging

```clojure
(ns myapp.core
  (:require [wagoe.observability.logging.core :as log]
            [wagoe.observability.logging.shell.adapters.stdout :as stdout]))

;; Create logger
(def logger (stdout/create-logger {:level :info}))

;; Log messages
(log/info logger "User logged in" {:user-id "123" :ip "192.168.1.1"})
(log/warn logger "Rate limit approaching" {:current 95 :max 100})
(log/error logger "Database connection failed" {:host "db.example.com"} exception)
```

### Metrics

```clojure
(ns myapp.metrics
  (:require [wagoe.observability.metrics.core :as metrics]
            [wagoe.observability.metrics.shell.adapters.no-op :as noop]))

;; Create metrics emitter
(def emitter (noop/create-emitter))

;; Emit metrics
(metrics/increment emitter "http.requests" {:tags {:method "GET" :path "/api/users"}})
(metrics/gauge emitter "db.connections.active" 5 {:tags {:pool "main"}})
(metrics/histogram emitter "http.response.time" 125 {:tags {:endpoint "/api/users"}})
```

### Error Reporting

```clojure
(ns myapp.errors
  (:require [wagoe.observability.errors.core :as errors]
            [wagoe.observability.errors.shell.adapters.no-op :as noop]))

;; Create error reporter
(def reporter (noop/create-reporter))

;; Add breadcrumbs for context
(errors/add-breadcrumb reporter {:category "http" :message "GET /api/users"})
(errors/add-breadcrumb reporter {:category "db" :message "SELECT * FROM users"})

;; Report exception
(try
  (dangerous-operation)
  (catch Exception e
    (errors/capture-exception reporter e {:user-id "123"})))
```

### Integration Configuration

```clojure
;; config.edn with Integrant
{:wagoe/logger
 {:adapter :slf4j
  :level :info
  :context {:app "myapp" :env "production"}}
 
 :wagoe/metrics
 {:adapter :datadog
  :prefix "myapp"
  :tags {:env "production"}}
 
 :wagoe/error-reporter
 {:adapter :sentry
  :dsn #env SENTRY_DSN
  :environment "production"}}
```

## Module Structure

```
libs/observability/src/wagoe/observability/
├── logging/
│   ├── core.clj              # Logging protocol
│   └── shell/adapters/
│       ├── noop.clj
│       ├── stdout.clj
│       ├── slf4j.clj
│       └── datadog.clj
├── metrics/
│   ├── core.clj              # Metrics protocol
│   └── shell/adapters/
│       ├── noop.clj
│       └── datadog.clj
└── errors/
    ├── core.clj              # Error reporting protocol
    └── shell/adapters/
        ├── noop.clj
        └── sentry.clj
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/core` | 1.0.0-beta-1 | Foundation utilities |
| `org.clojure/tools.logging` | 1.3.1 | Logging abstraction |
| `ch.qos.logback/logback-classic` | 1.5.23 | SLF4J implementation |
| `io.sentry/sentry-clj` | 8.29.238 | Sentry integration |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│      platform, user, admin, etc.        │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│         wagoe/observability          │
│   (logging, metrics, error-reporting)   │
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
