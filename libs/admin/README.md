# wagoe/admin

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-admin.svg)](https://clojars.org/com.wagoe/wagoe-admin)

Auto-generated CRUD admin interface with database schema introspection, filtering, sorting, and role-based access control.

---

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-admin {:mvn/version "1.0.0-beta-5"}}}
```

---

## Features

| Feature | Description |
|---------|-------------|
| **Auto-CRUD** | Automatic CRUD interface from database schema |
| **Schema Introspection** | Discover database structure at runtime |
| **Filtering** | Dynamic filter builder with multiple operators |
| **Sorting** | Click-to-sort column headers |
| **Pagination** | Built-in pagination for large datasets |
| **Role-based Access** | Restrict access by user role |
| **Field Ordering** | Configure preferred field order in forms |
| **Field Grouping** | Group related fields with section headings |
| **Soft Delete** | Mark records as deleted without removing from DB |
| **Has-Many Relations** | Show related records inline on detail page |
| **JOIN Queries** | Override queries for multi-table views |
| **HTMX Integration** | Dynamic updates without full page reloads |

---

## Requirements

- Clojure 1.12+
- wagoe/platform
- wagoe/user

---

## Quick Start

### 1. Module Registration

```clojure
(ns myapp.main
  (:require [wagoe.admin.shell.module-wiring]  ; Auto-registers module
            [wagoe.user.shell.module-wiring]   ; Required for auth
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.config :as config]
            [myapp.system-config :as sys-config]))   ; your Integrant config

(defn -main [& args]
  (wiring/start! (sys-config/ig-config (config/load-config))))
```

### 2. Top-level Configuration

Add to `resources/conf/dev/config.edn`:

```clojure
:wagoe/admin
{:enabled?         true
 :base-path        "/web/admin"       ; URL prefix for all admin pages
 :require-role     :admin             ; Role required to access admin

 ;; Which entities to expose in the admin UI
 :entity-discovery {:mode      :allowlist
                    :allowlist #{:users :products :orders}}

 ;; Per-entity configuration (use Aero #include for separate files)
 :entities         #merge [#include "admin/users.edn"
                           #include "admin/products.edn"
                           #include "admin/orders.edn"]

 ;; Pagination defaults
 :pagination       {:default-page-size 20
                    :max-page-size     200}

 ;; Optional: global UI overrides
 :ui               {:field-grouping {:other-label "Other"}}}
```

**Entity discovery modes:**
- `:allowlist` — only the entities in `:allowlist` are accessible (recommended)
- `:denylist` — all tables except those listed (not yet implemented)
- `:all` — every table in the database (not yet implemented)

### 3. Entity Config Files

Each entity gets its own file in `resources/conf/{env}/admin/<entity>.edn`.
Use Aero's `#merge` + `#include` to load them:

```clojure
;; config.edn
:entities #merge [#include "admin/users.edn"
                  #include "admin/products.edn"]
```

---

## Entity Configuration Reference

This is the full schema for a single entity config file. All keys are optional unless noted.

```clojure
{:my-entity
 {;; ─────────────────────────────────────────────────────────────────────
  ;; DISPLAY
  ;; ─────────────────────────────────────────────────────────────────────

  :label           "My Entities"      ; Plural display name in UI (default: humanized table name)
  :sidebar-hidden  false              ; true = hide from sidebar nav (still accessible via URL)

  ;; ─────────────────────────────────────────────────────────────────────
  ;; LIST VIEW
  ;; ─────────────────────────────────────────────────────────────────────

  :list-fields     [:name :status :created-at]  ; Columns shown in the list table
  :search-fields   [:name :description]         ; Fields searched by the free-text search box
  :default-sort    :created-at                  ; Default sort field (default: :id)
  :default-sort-dir :desc                       ; :asc or :desc (default: :asc)

  ;; ─────────────────────────────────────────────────────────────────────
  ;; FORM / DETAIL VIEW
  ;; ─────────────────────────────────────────────────────────────────────

  :hide-fields     #{:internal-token :deleted-at}  ; Never show these fields anywhere in UI
  :readonly-fields #{:id :created-at :updated-at}  ; Show but never allow editing

  ;; Order of fields in the create/edit form.
  ;; Fields not listed appear after these in their original DB order.
  :field-order     [:name :status :active :created-at :updated-at]

  ;; Group form fields under collapsible section headings.
  ;; Empty groups are automatically excluded.
  :field-groups
  [{:id :identity :label "Identity" :fields [:name :email]}
   {:id :status   :label "Status"   :fields [:status :active]}
   {:id :meta     :label "Metadata" :fields [:created-at :updated-at]}]

  ;; ─────────────────────────────────────────────────────────────────────
  ;; DATABASE / PERSISTENCE
  ;; ─────────────────────────────────────────────────────────────────────

  :soft-delete     true               ; true = set deleted_at on delete, false = hard delete
  :primary-key     :id                ; Primary key field (default: :id)

  ;; ─────────────────────────────────────────────────────────────────────
  ;; PER-FIELD CONFIGURATION
  ;; ─────────────────────────────────────────────────────────────────────

  :fields
  {;; Simple type override + label
   :name        {:type :string  :label "Full name" :required true}

   ;; Enum field: renders as a <select> dropdown
   :status      {:type :enum    :label "Status" :filterable true
                 :options [[:draft     "Draft"]
                           [:published "Published"]
                           [:archived  "Archived"]]}

   ;; Boolean: renders as a checkbox
   :active      {:type :boolean :label "Active" :filterable true}

   ;; Integer
   :sort-order  {:type :int     :label "Sort order"}

   ;; Decimal (price, weight, etc.)
   :price       {:type :decimal :label "Price"}

   ;; Timestamp: renders as formatted date/time, not editable in text form
   :created-at  {:type :instant :label "Created" :filterable true}

   ;; Disable filter dropdown for this field (use free-text search instead)
   :email       {:filterable false}}

  ;; ─────────────────────────────────────────────────────────────────────
  ;; HAS-MANY RELATIONSHIPS
  ;; ─────────────────────────────────────────────────────────────────────

  ;; Show related records inline on the detail page.
  :has-many
  [{:entity      :order-items          ; Entity keyword (must be in entity-discovery allowlist)
    :table       :order_items          ; Actual DB table name (snake_case keyword)
    :foreign-key :order-id             ; FK column in the related table (kebab-case keyword)
    :label       "Order Items"         ; Section heading
    :fields      [:product-name :quantity :total-cents]  ; Columns to show
    :editable    true}]                ; false = read-only inline table

  ;; For has-many child entities: show parent info at top of child detail page.
  ;; (Set this on the CHILD entity, not the parent.)
  :parent-context
  {:label  "Order"
   :fields [:order-number :status :customer-name]}

  ;; ─────────────────────────────────────────────────────────────────────
  ;; QUERY OVERRIDES — multi-table JOINs
  ;; ─────────────────────────────────────────────────────────────────────

  ;; Override the default SELECT * FROM <table> query.
  ;; Use when your entity spans multiple tables (e.g. auth_users + users profile table).
  :query-overrides
  {:from   [[:primary_table :p]]           ; HoneySQL :from clause (required)
   :join   [[:other_table :o] [:= :p.id :o.primary_id]]  ; Optional JOIN
   :select [:p.id :p.email :p.active       ; Explicit column list
            :o.name :o.role]

   ;; Map from kebab-case field names to qualified column references.
   ;; Needed for WHERE clauses (search, filters, sort) to use the right table.
   :field-aliases {:id         :p.id
                   :email      :p.email
                   :active     :p.active
                   :deleted-at :p.deleted_at
                   :name       :o.name
                   :role       :o.role}

   ;; For soft-delete with query-overrides: which table to UPDATE.
   :soft-delete-table :primary_table}

  ;; ─────────────────────────────────────────────────────────────────────
  ;; UI OVERRIDES (per-entity, overrides global :ui in :wagoe/admin)
  ;; ─────────────────────────────────────────────────────────────────────

  :ui {:field-grouping {:other-label "Advanced"}}}}
```

---

## Field Types

The admin UI auto-detects field types from the database schema. You can override with `:type` in `:fields`.

| Type | Auto-detected from | Widget rendered |
|------|--------------------|-----------------|
| `:uuid` | `UUID`, `CHAR(36)` | Text input (readonly by default) |
| `:string` | `VARCHAR`, `CHARACTER VARYING` | Text input |
| `:text` | `TEXT`, `LONGTEXT` | Textarea |
| `:int` | `INTEGER`, `BIGINT`, `SMALLINT` | Number input |
| `:decimal` | `DECIMAL`, `NUMERIC`, `FLOAT` | Number input (decimal) |
| `:boolean` | `BOOLEAN`, `BOOL` | Checkbox |
| `:instant` | `TIMESTAMP`, `DATETIME`, `TEXT` fields ending in `-at` | Formatted date display |
| `:date` | `DATE` | Date input |
| `:time` | `TIME` | Time input |
| `:json` | `JSON`, `JSONB` | Textarea |
| `:binary` | `BLOB`, `BYTEA` | Hidden (not editable) |
| `:enum` | Manual only — must provide `:options` | Select dropdown |

**Name heuristics for `TEXT` columns** — the system also infers type from field name:
- Ends in `-at`, `-until`, contains `login` or `timestamp` → `:instant`
- Named `email` → treated as email input
- Named `password` / `token` / `secret` / `hash` → hidden input

---

## Filter Operators

When `:filterable true` is set on a field, the admin UI renders an "Add filter" control for that field. The following operators are supported:

| Operator | Meaning |
|----------|---------|
| `:eq` | equals |
| `:ne` | not equals |
| `:gt` | greater than |
| `:gte` | greater than or equal |
| `:lt` | less than |
| `:lte` | less than or equal |
| `:contains` | case-insensitive substring match (`ILIKE %value%`) |
| `:starts-with` | starts with (`ILIKE value%`) |
| `:ends-with` | ends with (`ILIKE %value`) |
| `:in` | value is in a set |
| `:not-in` | value is not in a set |
| `:is-null` | field is NULL |
| `:is-not-null` | field is not NULL |
| `:between` | value is between min and max |

For `:enum` and `:boolean` fields, the UI builds the filter UI automatically from `:options`. For `:instant` fields, a date-range filter is used.

Fields with `:filterable false` (or not listed) are excluded from the filter dropdown. Use this for fields already covered by free-text search (`:search-fields`).

---

## Malli Schema Integration

If you register your Malli schemas with the admin schema provider, enum types and their options are **auto-detected** — you don't need to list `:options` manually in the `.edn` file.

```clojure
;; In your Integrant config:
:wagoe/admin-schema-provider
{:db-ctx       (ig/ref :wagoe/database-context)
 :config       (ig/ref :wagoe/admin)
 :malli-schemas {:users wagoe.user.schema/User
                 :products myapp.product.schema/Product}}
```

When a Malli schema is provided, the introspection layer reads `:enum` types and their values from the schema and enriches the field config automatically.

---

## Soft Delete

When `:soft-delete true` is set on an entity:
- Deleting a record sets `deleted_at` to the current timestamp (and `active` to `false` if the column exists)
- The list view automatically excludes soft-deleted records (`WHERE deleted_at IS NULL`)
- Bulk delete also soft-deletes

When `:soft-delete false` (the default), delete is permanent (`DELETE FROM …`).

For entities using `:query-overrides`, set `:soft-delete-table` to tell the service which table to `UPDATE` on delete (defaults to the primary table in `:from`).

---

## Has-Many Relationships

Show child records inline on a parent's detail page:

```clojure
;; orders.edn
{:orders
 {:has-many [{:entity      :order-items
              :table       :order_items
              :foreign-key :order-id
              :label       "Order Items"
              :fields      [:product-name :quantity :total-cents]
              :editable    true}]}}
```

On the child entity, use `:parent-context` to show parent info at the top of the child's detail page:

```clojure
;; order-items.edn
{:order-items
 {:sidebar-hidden true          ; Hide from nav; accessed only from parent
  :parent-context {:label  "Order"
                   :fields [:order-number :status :customer-name]}}}
```

---

## Multi-table JOIN Queries

When entity data lives across multiple tables (e.g. `auth_users` + `users`), use `:query-overrides` to define the full SELECT:

```clojure
{:users
 {:query-overrides
  {:from             [[:auth_users :a]]
   :join             [[:users :u] [:= :a.id :u.id]]
   :select           [:a.id :a.email :a.active :a.created_at :a.deleted_at
                      :u.name :u.role]
   :field-aliases    {:id         :a.id
                      :email      :a.email
                      :active     :a.active
                      :deleted-at :a.deleted_at
                      :created-at :a.created_at
                      :name       :u.name
                      :role       :u.role}
   :soft-delete-table :auth_users}}}
```

**Rules:**
- `:from` — primary table, same syntax as HoneySQL `:from`
- `:join` — optional, pairs of `[table condition]` (HoneySQL inner join)
- `:select` — explicit column list; use qualified names (`table.column`) to avoid ambiguity
- `:field-aliases` — map from kebab-case field name → qualified column reference; used for `WHERE`, `ORDER BY`, and soft-delete
- `:soft-delete-table` — which table the `UPDATE … SET deleted_at = …` runs on (defaults to first table in `:from`)
- Updates and creates always target the actual entity table (`:table-name`), not the joined tables

---

## Complete Example

```clojure
;; resources/conf/dev/admin/orders.edn
{:orders
 {:label            "Orders"
  :list-fields      [:order-number :customer-email :status :total-cents :created-at]
  :search-fields    [:order-number :customer-email :customer-name]
  :hide-fields      #{:payment-intent-id :shipping-address}
  :readonly-fields  #{:id :order-number :created-at :total-cents :currency}
  :default-sort     :created-at
  :default-sort-dir :desc
  :soft-delete      false

  :fields
  {:status {:type :enum :label "Status" :filterable true
            :options [[:pending   "Pending"]
                      [:paid      "Paid"]
                      [:shipped   "Shipped"]
                      [:delivered "Delivered"]
                      [:cancelled "Cancelled"]]}}

  :field-groups
  [{:id :customer :label "Customer"   :fields [:customer-name :customer-email]}
   {:id :order    :label "Order info" :fields [:order-number :status :currency]}
   {:id :amounts  :label "Amounts"    :fields [:total-cents :shipping-cents :tax-cents]}]

  :has-many
  [{:entity      :order-items
    :table       :order_items
    :foreign-key :order-id
    :label       "Order Items"
    :fields      [:product-name :quantity :product-price-cents :total-cents]
    :editable    true}]}}
```

---

## Web Routes

| Path | Method | Description |
|------|--------|-------------|
| `/web/admin` | GET | Dashboard |
| `/web/admin/:entity` | GET | List entities |
| `/web/admin/:entity/new` | GET | Create form |
| `/web/admin/:entity/new` | POST | Create entity |
| `/web/admin/:entity/:id` | GET | View entity detail |
| `/web/admin/:entity/:id/edit` | GET | Edit form |
| `/web/admin/:entity/:id` | PUT | Update entity |
| `/web/admin/:entity/:id` | DELETE | Delete entity |

---

## Module Structure

```
libs/admin/src/wagoe/
├── admin/
│   ├── core/
│   │   ├── ui.clj                   # Admin UI rendering (tables, forms, layout)
│   │   ├── schema_introspection.clj # Pure: DB type → field type mapping, config merging
│   │   └── permissions.clj          # Pure: role/access checks
│   ├── ports.clj                    # ISchemaProvider, IAdminService, IActionExecutor
│   ├── schema.clj                   # Malli schemas for entity configs
│   └── shell/
│       ├── service.clj              # CRUD operations (list, get, create, update, delete)
│       ├── http.clj                 # HTTP handlers and HTMX fragment endpoints
│       ├── schema_repository.clj   # ISchemaProvider: DB metadata + config merging
│       └── module_wiring.clj       # Integrant lifecycle
└── shared/
    └── ui/
        └── core/
            ├── components.clj       # Reusable UI components (buttons, badges, etc.)
            ├── icons.clj            # Lucide icon definitions (50+)
            ├── layout.clj           # Page layouts (admin-pilot-page-layout)
            └── table.clj            # Data table component
```

---

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│              wagoe/admin             │
│      (auto-CRUD, schema introspection)  │
└───────────┬─────────────┬───────────────┘
            │             │
            ▼             ▼
┌───────────────┐ ┌───────────────────────┐
│ wagoe/user │ │   wagoe/platform   │
└───────┬───────┘ └───────────┬───────────┘
        │                     │
        └──────────┬──────────┘
                   ▼
┌─────────────────────────────────────────┐
│  wagoe/observability + wagoe/core │
└─────────────────────────────────────────┘
```

---

## Development

```bash
# Run tests
clojure -M:test :admin

# Lint
clojure -M:clj-kondo --lint libs/admin/src libs/admin/test
```

See [`AGENTS.md`](AGENTS.md) for UI development workflow, CSS architecture, and common pitfalls.

---

## License

Copyright © 2024–2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
