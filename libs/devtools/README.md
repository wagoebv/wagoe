# wagoe/devtools

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-devtools.svg)](https://clojars.org/com.wagoe/wagoe-devtools)

Development-only tooling that gives x-ray vision into a running Wagoe application: BND-coded error pipeline, REPL power tools, and a live dev dashboard.

> **Dev-only library.** This is loaded exclusively via the `:dev` / `:repl-clj` aliases and adds **zero production overhead**. Never wire it into your production runtime.

## Installation

Add as a **`:dev` alias** extra-dep so it never reaches production:

```clojure
;; deps.edn
{:aliases
 {:dev
  {:extra-deps
   {com.wagoe/wagoe-devtools {:mvn/version "1.0.0-beta-1"}}}}}
```

## Features

| Feature | Description |
|---------|-------------|
| **Guidance Engine** | Startup dashboard, post-scaffold tips, contextual help, command palette (`:full` / `:minimal` / `:off` levels) |
| **Introspection** | Route table, config tree (secrets redacted), module/migration/test state, schema exploration |
| **Error Pipeline** | BND-xxx error codes with a `classify → enrich → format` chain and auto-fix suggestions |
| **Dev Dashboard** | Browser UI at `localhost:9999` with live system views |
| **Advanced REPL** | HTTP request/response recording, live route testing, rapid prototyping from Malli schemas |
| **AI REPL Commands** | AI-powered code review, test-idea suggestions, and FC/IS refactoring guidance |

## Quick Start

### Error auto-fix

```clojure
;; Fix the last error — safe fixes auto-apply, risky ones always confirm
(fix!)

;; Fix a specific exception
(fix! ex)
```

BND error codes group failures by category: `BND-1xx` config, `BND-2xx` validation, `BND-3xx` persistence, `BND-4xx` auth, `BND-5xx` interceptor, `BND-6xx` FC/IS.

### Introspection & schema tools

```clojure
(routes)                                 ; route table
(config)                                 ; config tree (secrets redacted)
(modules)                                ; module summary
(schema-tree :user/CreateUser)           ; Malli schema tree
(schema-example :user/CreateUser)        ; example value
(schema-diff :user/CreateUser :user/UpdateUser)
```

### AI REPL commands

Require a configured AI provider; all degrade gracefully when none is set.

```clojure
(ai/review "path/to/file.clj")           ; AI code review
(ai/test-ideas "path/to/file.clj")       ; suggest missing test cases
(ai/refactor-fcis 'wagoe.product.core.validation)  ; FC/IS refactor guide
```

### Dev dashboard

The `:wagoe/dashboard` Integrant component starts Jetty on port 9999. Pages:
`/dashboard` (overview), `/routes`, `/requests`, `/schemas`, `/db`, `/errors`,
`/jobs`, `/config`, `/security`, `/docs`.

## Documentation

- [AGENTS.md](AGENTS.md) — full module reference (phases, key files, safety model)
- [Library docs](../../docs/modules/libraries/pages/devtools.adoc) — feature overview and namespaces

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
