# wagoe/search

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-search.svg)](https://clojars.org/org.wagoe/wagoe-search)

**Full-text search for the Wagoe Framework**

Document indexing, full-text search (PostgreSQL FTS or LIKE fallback), trigram suggestions,
and an admin UI — all wired in via a single Integrant key.

---

## Quick Start

### 1. Define a Search Index

```clojure
(require '[wagoe.search.shell.registry :as registry])

(registry/defsearch product-search
  {:id          :product-search
   :entity-type :product
   :language    :english
   :fields      [{:name :title       :weight :A}
                 {:name :description :weight :B}
                 {:name :tags        :weight :C}]})
```

`defsearch` binds the var and registers the definition in the in-process registry.
The registry is mutable process state, so it lives in the shell
(`wagoe.search.shell.registry`); `wagoe.search.core.index` stays pure.

### 2. Index Documents

```clojure
(require '[wagoe.search.ports :as search-ports])

;; Get the service from the system
(def search-svc (get integrant.repl.state/system :wagoe/search))

(search-ports/index-document!
  search-svc
  :product-search
  product-uuid
  {:title "Widget Pro"
   :description "A professional widget"
   :tags ["tools" "hardware"]}
  {:metadata {:sku "WGT-001"}})
```

### 3. Search and Suggest

```clojure
;; Full-text search
(search-ports/search search-svc :product-search "widget"
                     {:limit 20 :offset 0 :highlight? true})
;; => {:results [{:entity-type :product :entity-id uuid :rank 0.9 :snippet "..."}]
;;     :total 42 :query "widget" :took-ms 5}

;; Autocomplete suggestions
(search-ports/suggest search-svc :product-search "wid" {:limit 5})
;; => [{:entity-type :product :entity-id uuid ...}]
```

### 4. Remove a Document

```clojure
(search-ports/remove-document! search-svc :product-search product-uuid)
```

---

## Core Concepts

### Field Weights

Fields are distributed into four weight buckets, which PostgreSQL FTS uses to rank results.
Higher-weight fields boost ranking:

| Weight | Priority | Typical use |
|--------|----------|-------------|
| `:A` | Highest | Title, name |
| `:B` | High | Summary, short description |
| `:C` | Normal | Body text, tags |
| `:D` | Lowest | Auxiliary metadata |

### Dual Query Strategy

| Database | Strategy |
|----------|----------|
| PostgreSQL | `to_tsvector` / `plainto_tsquery` / `ts_rank` / `ts_headline` |
| H2, SQLite | `LOWER(content_all) LIKE LOWER('%q%')` |

The adapter is selected automatically from the shared `:wagoe/db-context` dialect.

### Empty Query Handling

Queries shorter than 2 characters (after sanitisation) are treated as empty. The service
returns an empty `SearchResponse` with `:total 0` without touching the database.

---

## Configuration

```edn
;; resources/conf/dev/config.edn
{:wagoe/search {:enabled? true}}
```

No additional options are needed — the module auto-detects the database type.

---

## HTTP Endpoints

### API (mounted at `/api/v1`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/search/:index-id` | Full-text search |
| `POST` | `/search/:index-id/suggest` | Autocomplete suggestions |
| `POST` | `/search/documents` | Index a document |
| `DELETE` | `/search/documents/:entity-type/:entity-id` | Remove a document |

### Admin Web UI (mounted at `/web/admin`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/search` | List all registered indices |
| `GET` | `/search/:index-id` | Index detail + live search form |
| `POST` | `/search/:index-id/search` | HTMX search results fragment |

---

## Database Schema

```sql
CREATE TABLE search_documents (
  id          TEXT NOT NULL PRIMARY KEY,
  index_id    TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   TEXT NOT NULL,
  language    TEXT NOT NULL DEFAULT 'english',
  weight_a    TEXT NOT NULL DEFAULT '',
  weight_b    TEXT NOT NULL DEFAULT '',
  weight_c    TEXT NOT NULL DEFAULT '',
  weight_d    TEXT NOT NULL DEFAULT '',
  content_all TEXT NOT NULL DEFAULT '',   -- all fields joined (LIKE fallback)
  metadata    TEXT,                       -- pr-str encoded EDN
  updated_at  TEXT NOT NULL,
  UNIQUE (index_id, entity_id)
);
```

Re-indexing an existing entity is safe — upsert uses `ON CONFLICT (index_id, entity_id) DO UPDATE`.

---

## Testing

```bash
# Search library only
clojure -M:test:db/h2 :search

# Unit tests only
clojure -M:test:db/h2 --focus-meta :unit
```

H2 integration tests create their own in-memory database — no external dependencies required.

Test fixture pattern (reset the global registry between tests):

```clojure
(require '[wagoe.search.shell.registry :as registry])

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)))
```

---

## License

Part of Wagoe Framework — see main LICENSE file.
