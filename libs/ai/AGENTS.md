# boundary-ai — Dev Guide

## 1. Purpose

`boundary-ai` provides framework-aware AI tooling for Boundary-based applications. Unlike a generic AI assistant, it knows Boundary's own conventions (FC/IS, ports, kebab↔snake naming, Malli schemas, HoneySQL syntax) and uses that knowledge to deliver higher-quality output.

**Features:**
1. **NL Scaffolding** — parse a natural language description into a scaffolding spec (`bb scaffold ai "..."`)
2. **Error Explainer** — explain a stack trace with Boundary-specific context (`bb ai explain`)
3. **Test Generator** — generate a complete test namespace for a source file (`bb ai gen-tests <file>`)
4. **SQL Copilot** — translate a description into HoneySQL format (`bb ai sql "..."`)
5. **Documentation Wizard** — generate AGENTS.md, OpenAPI YAML, or README (`bb ai docs --module ...`)
6. **Admin Entity Generator** — generate admin UI entity EDN config from a description (`bb ai admin-entity "..."`)
7. **Setup Parser** — parse a NL project setup description into a config spec (`bb setup ai "..."`)

**Provider strategy:** offline-first via Ollama (no data leaves the machine by default), with cloud fallback via `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`.

**FC/IS rule:** `core/` is pure. All HTTP calls, file I/O, and env-var reads live in `shell/`.

---

## 2. Key Namespaces

| Namespace | Layer | Responsibility |
|-----------|-------|----------------|
| `wagoe.ai.schema` | shared | Malli schemas: `Message`, `AIRequest`, `AIResponse`, `ProviderConfig`, `AIConfig` |
| `wagoe.ai.ports` | shared | `IAIProvider` protocol: `complete`, `complete-json`, `provider-name` |
| `wagoe.ai.core.prompts` | core | Pure prompt builders for all 7 features |
| `wagoe.ai.core.context` | core | Pure context extractors (module names, stack traces, function signatures, schema) |
| `wagoe.ai.core.parsing` | core | Pure response parsers (JSON, module spec, SQL response, test code) |
| `wagoe.ai.shell.providers.ollama` | shell | Ollama HTTP adapter (`OllamaProvider`) |
| `wagoe.ai.shell.providers.anthropic` | shell | Anthropic API adapter (`AnthropicProvider`) |
| `wagoe.ai.shell.providers.openai` | shell | OpenAI API adapter (`OpenAIProvider`) |
| `wagoe.ai.shell.providers.no-op` | shell | Test stub (`NoOpProvider`) |
| `wagoe.ai.shell.service` | shell | Public API: `scaffold-from-description`, `explain-error`, `generate-tests`, `sql-from-description`, `generate-docs`, `generate-admin-entity`, `parse-setup-description` |
| `wagoe.ai.shell.repl` | shell | REPL helpers: `explain`, `sql`, `gen-tests` |
| `wagoe.ai.shell.cli-entry` | shell | `-main` for `clojure -M -m wagoe.ai.shell.cli-entry` |
| `wagoe.ai.shell.module-wiring` | shell | Integrant `:wagoe/ai-service` |

---

## 3. Integrant Configuration

Add to `resources/conf/{env}/config.edn`:

```edn
;; Offline-first (Ollama, no API key)
:wagoe/ai-service
{:provider :ollama
 :model    "qwen2.5-coder:7b"
 :base-url "http://localhost:11434"}

;; Anthropic (cloud)
:wagoe/ai-service
{:provider :anthropic
 :model    "claude-haiku-4-5-20251001"
 :api-key  #env ANTHROPIC_API_KEY}

;; Ollama primary + Anthropic fallback
:wagoe/ai-service
{:provider :ollama
 :model    "qwen2.5-coder:7b"
 :fallback {:provider :anthropic
            :model    "claude-haiku-4-5-20251001"
            :api-key  #env ANTHROPIC_API_KEY}}

;; Test environments
:wagoe/ai-service
{:provider :no-op}
```

Require the wiring namespace in your system config loader:
```clojure
(require '[wagoe.ai.shell.module-wiring])
```

---

## 4. Public API

### Service functions

```clojure
(require '[wagoe.ai.shell.service :as ai])

;; NL Scaffolding
(ai/scaffold-from-description service "product module with name, price, stock" ".")
;; => {:module-name "product" :entity "Product" :fields [...] :http true :web true}

;; Error Explainer
(ai/explain-error service stacktrace-string ".")
;; => {:text "Root cause: ..." :tokens 150 :provider :ollama :model "qwen2.5-coder:7b"}

;; Test Generator
(ai/generate-tests service "libs/user/src/wagoe/user/core/validation.clj")
;; => {:text "(ns wagoe.user.core.validation-test ...)" :tokens 320 ...}

;; SQL Copilot
(ai/sql-from-description service "find active users with orders in the last 7 days" ".")
;; => {:honeysql "{:select [...] :from [:users] ...}" :explanation "..." :raw-sql "SELECT ..."}

;; Documentation Wizard
(ai/generate-docs service "libs/user" :agents)
;; => {:text "# boundary-user — Dev Guide\n..." :tokens 800 ...}

;; Admin Entity Generator
(ai/generate-admin-entity service "products with name, price, status" ".")
;; => {:text "{:products {:label \"Products\" ...}}" :entity-name "products"}

;; Setup Parser (used by bb setup ai)
(ai/parse-setup-description service "PostgreSQL with Stripe and Redis")
;; => {:data {"project-name" "my-app" "database" "postgresql" "payment" "stripe" "cache" "redis" ...}}
```

### REPL helpers

```clojure
(require '[wagoe.ai.shell.repl :as ai])

;; Bind the service after system start
(ai/set-service! (integrant.repl.state/system :wagoe/ai-service))

(ai/explain *e)                                           ;; explain last exception
(ai/sql "find all active users")                          ;; HoneySQL from NL
(ai/gen-tests "libs/user/src/wagoe/user/core/v.clj")  ;; generate test ns
```

---

## 5. CLI (Babashka)

```bash
# NL Scaffolding
bb scaffold ai "product module with name string, price decimal required"
bb scaffold ai "product module with name string, price decimal required" --yes

# Error Explainer
cat stacktrace.txt | bb ai explain
bb ai explain --file stacktrace.txt

# Test Generator
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj --output libs/user/test/wagoe/user/core/validation_test.clj

# SQL Copilot
bb ai sql "find active users with orders in the last 7 days"

# Documentation Wizard
bb ai docs --module libs/user --type agents
bb ai docs --module libs/user --type openapi
bb ai docs --module libs/user --type readme

# Admin Entity Generator
bb ai admin-entity "products with name, price, status (active/archived), category"
bb ai admin-entity "invoices with number, customer, total, due-date, paid status" --yes

# Setup Parser (typically called via bb setup ai, not directly)
bb ai setup-parse "PostgreSQL with Stripe payments and Redis caching"
```

**Provider selection** (environment variables):
- `ANTHROPIC_API_KEY` → Anthropic (takes precedence)
- `OPENAI_API_KEY` → OpenAI
- `OLLAMA_URL` → Ollama (default `http://localhost:11434`)
- `AI_MODEL` → override default model

---

## 6. Common Pitfalls

### 1. IAIProvider protocol is in `wagoe.ai.ports`, not in individual provider namespaces
Always require `[wagoe.ai.ports :as ports]` and call `(ports/complete ...)`. Never call adapter methods directly.

### 2. `complete-json` does not validate the schema argument
The `schema` argument to `complete-json` is a descriptive hint string, not a Malli schema. JSON validation of the response is the caller's responsibility (see `parsing/parse-module-spec`).

### 3. Ollama must be running for live calls
Start with: `ollama serve`. The adapter will throw a connection exception if Ollama is not running; the fallback provider will be tried automatically if configured.

### 4. Module wiring uses the flat config (no nested `:config` map)
The `ig/init-key :wagoe/ai-service` handler receives the full config map directly. Unlike some other modules, there is no separate `:config` sub-key.

### 5. No-op provider for tests — not a mock
`NoOpProvider` returns deterministic canned responses. For integration tests that need to assert on specific AI outputs, use `reify IAIProvider` directly in the test.

### 6. `cli-entry` reads provider from environment, not Integrant
The CLI entrypoint (`-main`) constructs the provider from env vars at startup. It does not use the Integrant system. This is intentional — CLI scripts run outside the application lifecycle.

### 7. Context extraction is pure — file I/O stays in the service
`core/context.clj` functions receive already-loaded content. File reading happens only in `shell/service.clj`. Do not add `slurp` calls to `core/`.

### 8. Anthropic system messages are separate from the messages array
The Anthropic API requires system messages to be passed as a top-level `:system` field, not inside the messages array. The `AnthropicProvider` handles this automatically by filtering `:system` role messages out of the `messages` vector.

### 9. `generate-admin-entity` returns EDN text, not parsed data
The return value is `{:text "<edn-string>" :entity-name "products"}`. The EDN is returned as a string so it can be written directly to a file. Parse it with `read-string` if you need the data structure.

### 10. `generate-admin-entity` discovers existing entities from disk
It reads all `.edn` files from `resources/conf/dev/admin/` and includes them in the prompt as examples, so AI-generated entities follow the same style as existing ones. The `project-root` argument controls where it looks.

### 11. `setup-parse` returns JSON-like data, not EDN keywords
The setup parser returns a map with string keys (e.g., `{"database" "postgresql"}`) because it uses `complete-json`. The Babashka setup wizard handles keyword conversion.

---

## 7. Testing Commands

```bash
# All AI tests
clojure -M:test:db/h2 :ai

# Unit tests only (pure core functions — fast, no providers)
clojure -M:test:db/h2 --focus-meta :unit :ai

# Integration tests (mock providers)
clojure -M:test:db/h2 --focus-meta :integration :ai

# Contract tests (live Ollama — requires OLLAMA_URL)
OLLAMA_URL=http://localhost:11434 clojure -M:test:db/h2 --focus-meta :contract :ai

# Lint
clojure -M:clj-kondo --lint libs/ai/src libs/ai/test
```
