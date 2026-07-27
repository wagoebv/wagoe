# boundary/scaffolder

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.boundary-app/boundary-scaffolder.svg)](https://clojars.org/org.boundary-app/boundary-scaffolder)

Code generation tool for creating new Boundary modules following the Functional Core / Imperative Shell architecture.

## Installation

**deps.edn** (as dev dependency):
```clojure
{:aliases
 {:dev {:extra-deps {org.boundary-app/boundary-scaffolder {:mvn/version "1.0.0-beta-1"}}}}}
```

## Features

| Feature | Description |
|---------|-------------|
| **Project Generation** | Create new Boundary starter projects |
| **Module Generation** | Scaffold complete modules with FC/IS architecture |
| **Entity Templates** | Generate entity CRUD with validation schemas |
| **Migration Generation** | Create database migrations |
| **Test Scaffolding** | Generate unit, integration, and contract tests |
| **CLI Interface** | Easy-to-use command-line tool |
| **Customizable** | Template system for custom patterns |

## Creating a New Project

> **Use `boundary new` for new projects.** The Boundary CLI (`boundary new <name>`,
> from `wagoe-cli`) is the canonical, actively-maintained project generator — it
> produces the current template (`src/boundary/config.clj`, `src/<project>/system.clj`,
> `.env`, `bb.edn`, tests, hooks). The scaffolder's own `new` command below is a
> lower-level/legacy generator kept for embedding; it emits a different, simpler
> layout (`src/<name>/app.clj`) and does **not** match a `boundary new` project.

To generate the legacy starter layout directly from the scaffolder:

```bash
clojure -M:dev -m boundary.scaffolder.shell.cli-entry new --name myapp
```

Generated projects are self-contained and use SQLite by default, making them ready to run immediately.

### Generated Project Structure

```
myapp/
├── deps.edn                      # Dependencies (Integrant, Ring, SQLite, etc.)
├── README.md                     # Project documentation with REPL workflow
├── src/myapp/
│   └── app.clj                   # Integrant system with database & HTTP server
└── resources/conf/dev/config.edn # Configuration (SQLite by default)
```

**Features included:**
- ✓ Full Integrant system lifecycle
- ✓ Database connection pooling (SQLite)
- ✓ HTTP server setup (Ring/Jetty)
- ✓ Component-based architecture
- ✓ Comprehensive README with development guide

**Next Steps:**
1. `cd myapp`
2. Run `clojure -M:repl-clj` to start the development environment
3. Run `(ig-repl/go)` in the REPL to start the system
4. Check the generated `README.md` for a full development workflow guide

## Requirements

- Clojure 1.12+
- boundary/core

## Quick Start

### 1. Create a New Project

```bash
clojure -M:dev -m boundary.scaffolder.shell.cli-entry new --name myapp
cd myapp
```

### 2. Generate a Module

```bash
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
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
libs/product/src/boundary/product/
├── core/
│   └── product.clj           # Pure business logic
├── ports.clj                 # Service protocol
├── schema.clj                # Malli validation schemas
└── shell/
    ├── service.clj           # Service implementation
    ├── http.clj              # HTTP handlers
    ├── persistence.clj       # Database adapter
    └── module-wiring.clj     # Integrant configuration

libs/product/test/boundary/product/
├── core/
│   └── product_test.clj      # Unit tests
└── shell/
    ├── service_test.clj      # Integration tests
    └── persistence_test.clj  # Contract tests

resources/migrations/
└── 20240115120000-create-products-table.up.sql
```

## CLI Options

### `new` Command

Used to generate a new starter project.

| Option         | Description                  | Example                 |
|----------------|------------------------------|-------------------------|
| `--name`       | Project name (required)      | `--name myapp`          |
| `--output-dir` | Output directory (optional)  | `--output-dir /tmp`     |
| `--dry-run`    | Preview without writing      | `--dry-run`             |

**Examples:**

```bash
# Basic usage
clojure -M:dev -m boundary.scaffolder.shell.cli-entry new --name myapp

# Custom output directory
clojure -M:dev -m boundary.scaffolder.shell.cli-entry new --name myapp --output-dir ./projects

# Dry run (preview only)
clojure -M:dev -m boundary.scaffolder.shell.cli-entry new --name myapp --dry-run
```

### `generate` Command

| Option          | Description                  | Example                        |
|-----------------|------------------------------|--------------------------------|
| `--module-name` | Module name (kebab-case)     | `--module-name order-item`     |
| `--entity`      | Entity name (PascalCase)     | `--entity OrderItem`           |
| `--field`       | Field definition (repeatable)| `--field name:string:required` |
| `--output-dir`  | Output directory             | `--output-dir src`             |
| `--dry-run`     | Preview without writing      | `--dry-run`                    |
| `--force`       | Overwrite existing files     | `--force`                      |

### Field Definitions

Format: `name:type[:modifier...]`

**Types**:

| Type      | Clojure Type       | Database Type   |
|-----------|--------------------| ----------------|
| `string`  | `:string`          | `TEXT`          |
| `int`     | `:int`             | `INTEGER`       |
| `decimal` | `[:fn decimal?]`   | `DECIMAL(19,4)` |
| `boolean` | `:boolean`         | `BOOLEAN`       |
| `uuid`    | `:uuid`            | `TEXT`          |
| `instant` | `inst?`            | `TEXT`          |
| `date`    | `inst?`            | `TEXT`          |

**Modifiers**:

| Modifier | Description |
|----------|-------------|
| `required` | Non-nullable field |
| `optional` | Nullable field (default) |
| `unique` | Unique constraint |
| `indexed` | Database index |

### Examples

```bash
# Simple entity
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
  --module-name category \
  --entity Category \
  --field name:string:required \
  --field description:string

# Entity with all field types
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
  --module-name order \
  --entity Order \
  --field order-number:string:required:unique \
  --field customer-id:uuid:required:indexed \
  --field total:decimal:required \
  --field status:string:required \
  --field shipped:boolean \
  --field shipped-at:instant

# Dry run to preview
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
  --module-name test \
  --entity Test \
  --field name:string \
  --dry-run
```

## Generated Code Examples

### Schema (schema.clj)

```clojure
(ns myapp.product.schema
  (:require [malli.core :as m]))

(def product-schema
  [:map
   [:id {:optional true} :uuid]
   [:name [:string {:min 1 :max 255}]]
   [:sku [:string {:min 1 :max 100}]]
   [:description {:optional true} [:maybe :string]]
   [:price [:fn {:error/message "Must be a positive decimal"} 
            #(and (decimal? %) (pos? %))]]
   [:active {:optional true :default true} :boolean]
   [:created-at {:optional true} inst?]
   [:updated-at {:optional true} inst?]])

(def create-product-schema
  (m/schema
    [:map
     [:name [:string {:min 1 :max 255}]]
     [:sku [:string {:min 1 :max 100}]]
     [:description {:optional true} [:maybe :string]]
     [:price [:fn pos-decimal?]]
     [:active {:optional true} :boolean]]))
```

### Core Logic (core/product.clj)

```clojure
(ns myapp.product.core.product
  (:require [myapp.product.schema :as schema]))

(defn prepare-product
  "Prepare product for creation (pure function)"
  [product-data]
  (let [now (java.time.Instant/now)]
    (-> product-data
        (assoc :id (random-uuid))
        (assoc :created-at now)
        (assoc :updated-at now)
        (update :active #(if (nil? %) true %)))))

(defn prepare-product-update
  "Prepare product for update (pure function)"
  [existing-product updates]
  (-> existing-product
      (merge updates)
      (assoc :updated-at (java.time.Instant/now))))
```

### Service (shell/service.clj)

```clojure
(ns myapp.product.shell.service
  (:require [myapp.product.ports :as ports]
            [myapp.product.core.product :as product-core]
            [boundary.core.validation :as validation]
            [myapp.product.schema :as schema]))

(defrecord ProductService [repository logger]
  ports/ProductServiceProtocol
  
  (create-product [this product-data]
    (validation/validate! schema/create-product-schema product-data)
    (let [product (product-core/prepare-product product-data)]
      (ports/save-product repository product)))
  
  (get-product [this product-id]
    (ports/find-product-by-id repository product-id))
  
  (update-product [this product-id updates]
    (let [existing (ports/find-product-by-id repository product-id)]
      (when-not existing
        (throw (ex-info "Product not found" {:type :not-found :id product-id})))
      (let [updated (product-core/prepare-product-update existing updates)]
        (ports/save-product repository updated)))))
```

## Module Structure

```
libs/scaffolder/src/boundary/scaffolder/
├── core/
│   ├── generators.clj        # Template generation (pure)
│   ├── field-parser.clj      # Field definition parsing
│   └── templates/            # Template strings
└── shell/
    ├── cli-entry.clj         # CLI entry point
    └── file-writer.clj       # File system operations
```

## Programmatic Usage

```clojure
(ns myapp.dev
  (:require [boundary.scaffolder.core.generators :as gen]))

;; Generate module programmatically
(gen/generate-module
  {:module-name "product"
   :entity-name "Product"
   :fields [{:name "name" :type :string :required? true}
            {:name "price" :type :decimal :required? true}
            {:name "active" :type :boolean}]})
;; => {:files [{:path "..." :content "..."} ...]}
```

## Customization

### Custom Templates

Place custom templates in `resources/scaffolder/templates/`:

```
resources/scaffolder/templates/
├── core.clj.mustache
├── ports.clj.mustache
├── schema.clj.mustache
├── service.clj.mustache
└── test.clj.mustache
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `boundary/core` | 1.0.0-beta-1 | Utilities |
| `org.clojure/tools.cli` | 1.3.250 | CLI parsing |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│         Development Tooling             │
└─────────────────┬───────────────────────┘
                  │ uses
                  ▼
┌─────────────────────────────────────────┐
│          boundary/scaffolder            │
│        (code generation, CLI)           │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│            boundary/core                │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/scaffolder
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test

# Test CLI
clojure -M -m boundary.scaffolder.shell.cli-entry --help
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
