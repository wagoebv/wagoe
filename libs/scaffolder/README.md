# wagoe/scaffolder

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-scaffolder.svg)](https://clojars.org/com.wagoe/wagoe-scaffolder)

Code generation tool for creating new Wagoe modules following the Functional Core / Imperative Shell architecture.

## Installation

**deps.edn** (as dev dependency):
```clojure
{:aliases
 {:dev {:extra-deps {com.wagoe/wagoe-scaffolder {:mvn/version "1.1.0-rc-1"}}}}}
```

## Features

| Feature | Description |
|---------|-------------|
| **Module Generation** | Scaffold complete modules with FC/IS architecture |
| **Entity Templates** | Generate entity CRUD with validation schemas |
| **Migration Generation** | Create database migrations |
| **Test Scaffolding** | Generate core, repository and service tests |
| **CLI Interface** | Easy-to-use command-line tool |

## Creating a New Project

The scaffolder works inside an existing project. New projects come from the
Wagoe CLI: `wagoe new <name>` (from `wagoe-cli`). The scaffolder's own `new`
command was removed in BOU-259.

## Requirements

- Clojure 1.12+
- wagoe/core

## Quick Start

### 1. Create a New Project

```bash
wagoe new myapp
cd myapp
```

### 2. Generate a Module

```bash
bb scaffold generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field description:string \
  --field price:decimal:required \
  --field active:boolean
```

### Generated Files

```
src/myapp/product/
├── core/
│   ├── product.clj           # Pure business logic
│   └── ui.clj                # Web UI (unless --no-web)
├── ports.clj                 # Repository and service protocols
├── schema.clj                # Malli validation schemas
└── shell/
    ├── service.clj           # Service implementation
    ├── http.clj              # HTTP handlers (unless --no-http)
    ├── persistence.clj       # Database adapter
    ├── web_handlers.clj      # Web UI handlers (unless --no-web)
    └── module_wiring.clj     # Integrant configuration

test/myapp/product/
├── core/
│   └── product_test.clj
└── shell/
    ├── product_repository_test.clj
    └── service_test.clj

migrations/
├── 20240115120000-create-products.up.sql
└── 20240115120000-create-products.down.sql
```

Then `bb scaffold integrate product` writes its config key.

## CLI Options

### `generate` Command

| Option          | Description                  | Example                        |
|-----------------|------------------------------|--------------------------------|
| `--module-name` | Module name (kebab-case)     | `--module-name order-item`     |
| `--entity`      | Entity name (PascalCase)     | `--entity OrderItem`           |
| `--field`       | Field definition (repeatable)| `--field name:string:required` |
| `--base-ns`     | Namespace the module goes under (default: the project's) | `--base-ns myapp` |
| `--workflow`    | A status as a forward-only workflow | `--workflow 'status:draft>sent'` |
| `--[no-]http` / `--[no-]web` | Generate the REST API / web UI (default: both) | `--no-web` |
| `--public-api`  | Routes open to anyone (default: signed-in users only) | `--public-api` |
| `--output-dir`  | Project root to write into   | `--output-dir ../myapp`        |
| `--dry-run`     | Preview without writing      | `--dry-run`                    |
| `--force`       | Overwrite existing files     | `--force`                      |

### Field Definitions

Format: `name:type[:modifier...]`, e.g. `status:enum:values=draft,sent:required`

**Types**:

| Type      | Clojure Type       | Database Type   |
|-----------|--------------------| ----------------|
| `string`  | `:string`          | `VARCHAR(255)`  |
| `text`    | `:string`          | `TEXT`          |
| `int` / `integer` | `:int`     | `INTEGER`       |
| `decimal` | `decimal?`         | `DECIMAL(19,4)` |
| `boolean` | `:boolean`         | `BOOLEAN`       |
| `email`   | `[:re …]`          | `VARCHAR(255)`  |
| `uuid`    | `:uuid`            | `UUID`          |
| `enum`    | `[:enum …]`        | `VARCHAR(50)`   |
| `datetime` / `inst` | `inst?`  | `TIMESTAMP WITH TIME ZONE` |
| `date`    | `YYYY-MM-DD` string | `DATE`         |
| `json`    | `:map`             | `JSONB`         |
| `relation` | `:uuid`           | `UUID` foreign key, `<name>_id` |

**Modifiers**:

| Modifier | Description |
|----------|-------------|
| `values=a,b,c` | Allowed values; required on an `enum` field |
| `references=entity` | Target entity; required on a `relation` field (`references-table=` when its table is not the default plural) |
| `on-delete=x` | `cascade` (default), `restrict`, `set-null` or `no-action`, on a `relation` |
| `required` | Non-nullable field |
| `optional` | Nullable field (default) |
| `unique` | Unique constraint |
| `default=v` | Column `DEFAULT`; a required `enum` without one defaults to its first value. A datetime needs an offset (`default=2026-01-01T00:00:00Z`). Quote a value holding a colon and a modifier word: `default='a:unique'` |
| `indexed` | Database index |

### Examples

```bash
# Simple entity
bb scaffold generate \
  --module-name category \
  --entity Category \
  --field name:string:required \
  --field description:string

# Entity with all field types
bb scaffold generate \
  --module-name order \
  --entity Order \
  --field order-number:string:required:unique \
  --field customer-id:uuid:required:indexed \
  --field total:decimal:required \
  --field status:string:required \
  --field shipped:boolean \
  --field shipped-at:datetime

# Dry run to preview
bb scaffold generate \
  --module-name test \
  --entity Test \
  --field name:string \
  --dry-run
```

### `entity` Command

Adds an entity to a module that exists — invoice line items to an invoicing
module:

```bash
bb scaffold entity --module-name billing --entity InvoiceLineItem \
  --belongs-to invoice \
  --field description:string:required \
  --field quantity:int:required:default=1
```

`--belongs-to` adds a required `invoice_id` column with a foreign key
(`ON DELETE CASCADE`) and an index; the parent must be an entity of the module.
Do not also declare an `invoice-id` or `invoice` field: that is refused.

The entity gets its own `core/<entity>.clj`, `shell/<entity>_service.clj`,
`shell/<entity>_persistence.clj`, `shell/<entity>_http.clj`, create migration
and tests. Its defs are appended to `schema.clj` and `ports.clj`, whose
existing text is left as it is, and it is wired in `module_wiring.clj`: after a
restart its CRUD API answers at `/api/v1/invoice-line-items`. In a module with
web pages it gets the first entity's too, from `core/<entity>_ui.clj` and
`shell/<entity>_web_handlers.clj`: a list at `/web/invoice-line-items` and a
create form whose invoice is picked from a select of invoices. `--no-http`
leaves out its API routes.

Wiring the first extra entity adds an `ig-config` and an `entity-wiring`
multimethod to `module_wiring.clj`, and replaces its routes init-key with one
that also mounts the extra entities' routes. If you have edited that init-key,
the command refuses rather than drop your change. Later entities only append.
The `ig-config` returns only what it adds to the four keys discovery builds;
see the platform library's guide to add components or middleware of your own.

A file it would write that already exists, or a name the module already
defines, refuses the run and writes nothing.

A second entity's repository methods carry its name
(`find-invoice-line-item-by-id`, …): both protocols live in one `ports.clj`,
and a second `find-by-id` would replace the first.

`generate` through the API or MCP takes several entities at once and writes
the same files.

## Generated Code Examples

See [AGENTS.md](AGENTS.md) for a generated `schema.clj` and `ports.clj`.

## Module Structure

```
libs/scaffolder/src/wagoe/scaffolder/
├── cli.clj                   # Command definitions, option specs, wizards
├── ports.clj                 # IScaffolderService
├── schema.clj                # Request and field schemas
├── core/
│   ├── generators.clj        # File content generation (pure)
│   └── template.clj          # Names, types, template context (pure)
└── shell/
    ├── cli_entry.clj         # CLI entry point
    └── service.clj           # Orchestration and file writing
```

## Programmatic Usage

```clojure
(ns myapp.dev
  (:require [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]))

(ports/generate-module
  (service/create-scaffolder-service)
  {:module-name "product"
   :base-ns     "myapp"
   :entities    [{:name "Product"
                  :fields [{:name :name :type :string :required true}
                           {:name :price :type :decimal :required true}
                           {:name :active :type :boolean}]}]
   :dry-run     true})
;; => {:success true :files [{:path "..." :content "..." :action :create} ...] ...}
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/core` | 1.1.0-rc-1 | Utilities |
| `org.clojure/tools.cli` | 1.4.256 | CLI parsing |
| `metosin/malli` | 0.20.1 | Request schemas |
| `rewrite-clj/rewrite-clj` | 1.2.57 | Editing existing source files |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│         Development Tooling             │
└─────────────────┬───────────────────────┘
                  │ uses
                  ▼
┌─────────────────────────────────────────┐
│          wagoe/scaffolder            │
│        (code generation, CLI)           │
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
cd libs/scaffolder
clojure -M:test

# ...including the PostgreSQL round trip (embedded PostgreSQL; add :test/pg-mac on Apple Silicon)
clojure -M:test:test/pg

# Lint
clojure -M:clj-kondo --lint src test

# Test CLI
clojure -M -m wagoe.scaffolder.shell.cli-entry --help
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
