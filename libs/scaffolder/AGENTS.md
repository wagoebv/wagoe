# Scaffolder Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Generates new Wagoe modules with FC/IS structure, tests, and migrations. Produces correct, lint-clean, production-ready code in seconds.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.scaffolder.shell.cli-entry` | CLI entrypoint — dispatches commands |
| `wagoe.scaffolder.cli` | Command definitions, option specs, wizard structure |
| `wagoe.scaffolder.core.generators` | Pure generation logic (schemas, ports, handlers) |
| `wagoe.scaffolder.shell.templates.*` | File templates and output orchestration |

---

## Quick Usage

```bash
# Interactive wizard (recommended)
bb scaffold

# Show available commands
bb scaffold help

# AI-powered scaffolding from natural language
bb scaffold ai "product module with name, price, stock, active status"
bb scaffold ai "product module with name, price, stock" --yes   # non-interactive
```

---

## Commands

### `generate` — Create a New Module

The most common command. Creates a complete FC/IS module.

```bash
# Interactive wizard (guided prompts)
bb scaffold generate

# Or pass all arguments directly (useful in CI/scripts)
bb scaffold generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required \
  --field price:decimal:required \
  --field active:boolean \
  --field description:string

# Preview without writing files
bb scaffold generate --module-name product --entity Product \
  --field name:string:required --dry-run
```

### Creating a new project — not this library

The scaffolder works inside an existing project. New projects come from the
Wagoe CLI:

```bash
wagoe new my-app
```

`bb scaffold new` was removed in BOU-259: it generated projects from its own
copy of the templates, which had drifted until the result carried no
`com.wagoe` dependencies and no entry point. Supersedes ADR-002.

### `field` — Add a Field to an Existing Entity

```bash
bb scaffold field \
  --module-name product \
  --entity Product \
  --name weight \
  --type decimal \
  --required
```

### `endpoint` — Add an Endpoint to an Existing Module

```bash
bb scaffold endpoint \
  --module-name product \
  --path "/products/:id/publish" \
  --method POST \
  --handler-name publish-product-handler
```

### `adapter` — Generate a New Adapter Implementation

```bash
bb scaffold adapter \
  --module-name product \
  --port IProductNotifier \
  --adapter-name email-product-notifier \
  --method "notify-created:product" \
  --method "notify-updated:product,changes"
```

---

## Field Specification Format

Fields are specified as `name:type[:required][:unique]`:

| Type | Maps to Malli | Notes |
|------|--------------|-------|
| `string` | `[:string {:min 1 :max 255}]` | |
| `text` | `:string` | No length limit |
| `integer` / `int` | `:int` | |
| `decimal` | `:double` | |
| `boolean` | `:boolean` | |
| `email` | `[:re email-regex]` | |
| `uuid` | `:uuid` | |
| `enum` | `[:enum ...]` | Add values after generation |
| `date` / `datetime` / `inst` | `inst?` | |
| `json` | `:map` | |

Examples:

```bash
--field email:email:required:unique
--field name:string:required
--field age:integer
--field status:enum
--field price:decimal:required
--field active:boolean
--field notes:text
```

---

## Generated File Structure

Running `bb scaffold generate --module-name product --entity Product --field name:string:required --field price:decimal:required` creates:

```
libs/product/
├── src/wagoe/product/
│   ├── core/
│   │   ├── product.clj      # Pure business logic
│   │   └── validation.clj   # Validation rules
│   ├── shell/
│   │   ├── http.clj         # HTTP handlers & routes
│   │   ├── persistence.clj  # Database adapter (implements IProductRepository)
│   │   └── service.clj      # Shell orchestration (UserService record)
│   ├── ports.clj            # IProductRepository, IProductService protocols
│   └── schema.clj           # Malli schemas: Product, CreateProductRequest, etc.
├── test/wagoe/product/
│   ├── core/
│   │   └── product_test.clj # Unit tests (^:unit metadata)
│   ├── shell/
│   │   ├── service_test.clj # Integration tests (^:integration metadata)
│   │   └── persistence_contract_test.clj # Contract tests (^:contract metadata)
└── resources/wagoe/product/migrations/
    └── 001-create-product.sql
```

### Sample Generated schema.clj

```clojure
(ns wagoe.product.schema
  (:require [malli.core :as m]))

(def Product
  [:map {:closed true}
   [:id :uuid]
   [:name [:string {:min 1 :max 255}]]
   [:price :double]
   [:created-at inst?]
   [:updated-at [:maybe inst?]]])

(def CreateProductRequest
  [:map {:closed true}
   [:name [:string {:min 1 :max 255}]]
   [:price :double]])

(def UpdateProductRequest
  [:map {:closed true}
   [:name {:optional true} [:string {:min 1 :max 255}]]
   [:price {:optional true} :double]])

(defn validate-create [data]
  (m/validate CreateProductRequest data))

(defn explain-create [data]
  (m/explain CreateProductRequest data))
```

### Sample Generated ports.clj

```clojure
(ns wagoe.product.ports)

(defprotocol IProductRepository
  (find-by-id   [this id])
  (find-all     [this params])
  (create!      [this entity])
  (update!      [this entity])
  (delete!      [this id]))

(defprotocol IProductService
  (get-product    [this id])
  (list-products  [this params])
  (create-product [this data])
  (update-product [this id data])
  (delete-product [this id]))
```

---

## Post-Generation Integration Steps

After running `bb scaffold generate`, wire the new module into the system:

### 1. Add to `deps.edn`

```clojure
;; In root deps.edn, add to :paths or as a local lib:
{:deps {wagoe/product {:local/root "libs/product"}}}
```

### 2. Add to `tests.edn`

```clojure
;; Add a test suite entry
{:kaocha/tests
 [{:id :product
   :kaocha.testable/aliases [:product]
   :kaocha/source-paths ["libs/product/src"]
   :kaocha/test-paths   ["libs/product/test"]}]}
```

### 3. Wire into Integrant System Config

```clojure
;; In resources/conf/dev/config.edn
{:wagoe/product-service
 {:user-repository #ig/ref :wagoe/user-repository
  :db-context      #ig/ref :wagoe/db-context}}
```

### 4. Add Routes

```clojure
;; In src/wagoe/system.clj or your router config
(require '[wagoe.product.shell.http :as product-http])

(defn all-routes [config]
  (concat
    (user-http/user-routes config)
    (product-http/product-routes config)))   ; Add this
```

### 5. Run the Migration

```bash
clojure -M:migrate up
```

### 6. Run the Tests

```bash
clojure -M:test :product
```

---

## AI-Powered Mode

```bash
# Generate from a natural language description (interactive confirm)
bb scaffold ai "invoice module with customer name, line items, total, status and due date"

# Non-interactive — applies immediately
bb scaffold ai "invoice module with customer name, line items, total, status and due date" --yes
```

The AI mode calls the configured LLM (Anthropic/OpenAI/Ollama) to:
1. Parse the description into module name, entity name, and fields
2. Show a preview of the generation plan
3. Run `bb scaffold generate` with the parsed arguments

Configure the provider via environment variables: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, or `OLLAMA_URL`.

---

## Options Reference

| Flag | Default | Description |
|------|---------|-------------|
| `--module-name` | — | Module name in lowercase kebab-case (required) |
| `--entity` | — | Entity name in PascalCase (required) |
| `--field` | — | Repeatable: `name:type[:required][:unique]` |
| `--http` | true | Generate HTTP interface |
| `--cli` | true | Generate CLI interface |
| `--web` | true | Generate Web UI interface |
| `--audit` | true | Include audit logging |
| `--pagination` | true | Include pagination support |
| `--output-dir` | `.` | Output directory |
| `--force` | false | Overwrite existing files |
| `--dry-run` | false | Preview without writing |

---

## Gotchas

- Regenerating a module that already exists is refused, and the files it would replace are listed. Pass `--force` to overwrite them.
- Generated field names are always kebab-case internally; only converted at HTTP/DB boundaries.
- AI mode requires a configured LLM provider. Ollama works offline: set `OLLAMA_URL`.
- After scaffolding, always add the library to `deps.edn` and `tests.edn` — the scaffolder doesn't modify these.
- Template drift: if templates change after generation, run `bb ai gen-tests <file>` to regenerate tests.

---

## Testing

```bash
clojure -M:test :scaffolder
```

---

## Links

- [Library README](README.md)
- [AI Library](../ai/AGENTS.md) — LLM provider setup for AI-powered scaffolding
- [Root AGENTS Guide](../../AGENTS.md)
