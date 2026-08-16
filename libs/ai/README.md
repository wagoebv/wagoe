# wagoe/ai

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-ai.svg)](https://clojars.org/com.wagoe/wagoe-ai)

Framework-aware AI tooling for Wagoe applications — offline-first via Ollama, with cloud fallback to Anthropic Claude or OpenAI.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-ai {:mvn/version "1.0.0-beta-5"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-ai "1.0.0-beta-5"]
```

## Features

| Feature | Description |
|---------|-------------|
| **NL Scaffolding** | Parse a natural language description into a scaffolding spec |
| **Error Explainer** | Explain a stack trace with Wagoe-specific context |
| **Test Generator** | Generate a complete test namespace for a source file |
| **SQL Copilot** | Translate a description into HoneySQL format |
| **Documentation Wizard** | Generate AGENTS.md, OpenAPI YAML, or README |
| **Admin Entity Generator** | Generate admin UI entity EDN config from a description |
| **Setup Parser** | Parse a natural language project setup into a config spec |
| **Multi-provider** | Ollama (local), Anthropic Claude, OpenAI, plus a no-op test stub |

Unlike a generic assistant, `wagoe/ai` knows Wagoe's own conventions (FC/IS, ports, kebab↔snake naming, Malli schemas, HoneySQL syntax) and uses that knowledge to produce higher-quality output. The functional core is pure — all HTTP, file I/O, and env-var reads live in the shell.

## Quick Start

### Configuration

```clojure
;; config.edn — offline-first (Ollama, no API key)
:wagoe/ai-service
{:provider :ollama
 :model    "qwen2.5-coder:7b"
 :base-url "http://localhost:11434"}

;; Ollama primary + Anthropic fallback
:wagoe/ai-service
{:provider :ollama
 :model    "qwen2.5-coder:7b"
 :fallback {:provider :anthropic
            :model    "claude-haiku-4-5-20251001"
            :api-key  #env ANTHROPIC_API_KEY}}
```

Require the wiring namespace in your system config loader:
```clojure
(require '[wagoe.ai.shell.module-wiring])  ; registers :wagoe/ai-service
```

### Service API

```clojure
(require '[wagoe.ai.shell.service :as ai])

;; Explain a stack trace with Wagoe-specific context
(ai/explain-error service stacktrace-string ".")
;; => {:text "Root cause: ..." :tokens 150 :provider :ollama :model "qwen2.5-coder:7b"}

;; Translate a description into HoneySQL
(ai/sql-from-description service "find active users with orders in the last 7 days" ".")
;; => {:honeysql "{:select [...] :from [:users] ...}" :explanation "..." :raw-sql "SELECT ..."}
```

### CLI (Babashka)

```bash
bb scaffold ai "product module with name, price, stock"   # NL scaffolding
bb ai explain --file stacktrace.txt                        # Error explainer
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj  # Test generator
bb ai sql "find active users with orders in the last 7 days"     # SQL copilot
bb ai docs --module libs/user --type agents                # Docs wizard (agents/openapi/readme)
bb ai admin-entity "products with name, price, status"     # Admin entity EDN config
```

## Documentation

- [AGENTS.md](AGENTS.md) — namespaces, Integrant config, full public API, CLI reference, and pitfalls
- [Library docs](../../docs/modules/libraries/pages/ai.adoc) — module overview on the docs site

**Provider selection** via environment variables: `ANTHROPIC_API_KEY` (takes precedence) → Anthropic, `OPENAI_API_KEY` → OpenAI, `OLLAMA_URL` (default `http://localhost:11434`) → Ollama, `AI_MODEL` to override the default model.

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
