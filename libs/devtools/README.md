# wagoe/devtools

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-devtools.svg)](https://clojars.org/com.wagoe/wagoe-devtools)

Development-only tooling that gives x-ray vision into a running Wagoe application: WGE-coded error pipeline, REPL power tools, and a live dev dashboard.

> **Dev-only library.** This is loaded exclusively via the `:dev` / `:repl-clj` aliases and adds **zero production overhead**. Never wire it into your production runtime.

## Installation

Add as a **`:dev` alias** extra-dep so it never reaches production:

```clojure
;; deps.edn
{:aliases
 {:dev
  {:extra-deps
   {com.wagoe/wagoe-devtools {:mvn/version "1.1.0"}}}}}
```

## Features

| Feature | Description |
|---------|-------------|
| **Guidance Engine** | Startup dashboard, post-scaffold tips, contextual help, command palette (`:full` / `:minimal` / `:off` levels) |
| **Introspection** | Route table, config tree (secrets redacted), module/migration/test state, schema exploration |
| **Error Pipeline** | WGE-xxx error codes with a `classify → enrich → format` chain and auto-fix suggestions |
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

WGE error codes group failures by category: `WGE-1xx` config, `WGE-2xx` validation, `WGE-3xx` persistence, `WGE-4xx` auth, `WGE-5xx` interceptor, `WGE-6xx` FC/IS, `WGE-7xx` tooling, `WGE-8xx` MCP guardrails.

### Introspection & schema tools

```clojure
(routes)                                 ; route table
(config)                                 ; config tree (secrets redacted)
(modules)                                ; module summary
(schema UserSchema)                      ; Malli schema tree
(generate UserSchema)                    ; example value
(schema-diff CreateUser UpdateUser)
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

Editing a config value from the dashboard rebuilds the system with the prep function your REPL gave `integrant.repl/set-prep!`. To rebuild from something else, set `:ig-config-fn` on `:wagoe/dashboard` to a zero-argument function returning your Integrant config. Functions in a config section are left out of the editor and kept when you apply.

## Documentation

- [AGENTS.md](AGENTS.md) — full module reference (phases, key files, safety model)
- [Library docs](../../docs/modules/libraries/pages/devtools.adoc) — feature overview and namespaces

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
