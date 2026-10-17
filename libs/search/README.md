# wagoe/search

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-search.svg)](https://clojars.org/com.wagoe/wagoe-search)

**Full-text search for the Wagoe Framework**

Document indexing, full-text search (PostgreSQL FTS or LIKE fallback), trigram suggestions,
and an admin UI — all wired in via a single Integrant key.

---

## Quick Start

### 1. Define a Search Index

```clojure
(require '[wagoe.search.core.index :refer [defsearch]])

(defsearch product-search
  {:entity-type :product
   :language    :english
   :fields      [{:name :title       :weight :a}
                 {:name :description :weight :b}
                 {:name :tags        :weight :c}]
   :api?        true})   ; serve it over the HTTP API
```

`defsearch` only binds the var. Hand it to `:wagoe/search` from your module's
`ig-config`, as its `:search` part:

```clojure
(defn ig-config [_settings _ctx]
  {:search {:product-search product-search}})
```

or put the same map in config, under `:wagoe/search :indexes`. The search API
and admin pages serve an index only with `:api? true`, or an `:api`
`:authorize` fn, `(fn [request definition])`, accepts the request; any other
index answers 404.

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

| Adapter capability | Strategy |
|--------------------|----------|
| `:full-text` (PostgreSQL) | `to_tsvector` / `plainto_tsquery` / `ts_rank` / `ts_headline` |
| no `:full-text` (H2, SQLite, MySQL) | `LOWER(content_all) LIKE LOWER('%q%')` |

`create-search-store` takes the adapter: `:full-text` picks the query and
`:on-conflict` the upsert (ADR-039).

### Empty Query Handling

Queries shorter than 2 characters (after sanitisation) are treated as empty. The service
returns an empty `SearchResponse` with `:total 0` without touching the database.

---

## Configuration

```edn
;; resources/conf/dev/config.edn
{:wagoe/search {:enabled? true}}
```

`:indexes` (index id -> definition) is the one option; the module auto-detects
the database type.

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
| `GET` | `/search` | List served indices |
| `GET` | `/search/:index-id` | Index detail + live search form |
| `POST` | `/search/:index-id/search` | HTMX search results fragment |

---

## Database Schema

Shipped as the library's migrations (`wagoe/search/migrations/`); `bb migrate up`
creates it once search is enabled.

```sql
CREATE TABLE search_documents (
  id          VARCHAR(255) NOT NULL PRIMARY KEY,
  index_id    VARCHAR(255) NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  entity_id   VARCHAR(255) NOT NULL,
  language    TEXT NOT NULL DEFAULT 'english',
  weight_a    TEXT NOT NULL DEFAULT '',
  weight_b    TEXT NOT NULL DEFAULT '',
  weight_c    TEXT NOT NULL DEFAULT '',
  weight_d    TEXT NOT NULL DEFAULT '',
  content_all TEXT NOT NULL DEFAULT '',   -- all fields joined (LIKE fallback)
  metadata    TEXT,                       -- pr-str encoded EDN
  updated_at  VARCHAR(64) NOT NULL,
  filters     TEXT,                       -- JSON of filter key/values
  UNIQUE (index_id, entity_id)
);
```

Re-indexing an existing entity is safe — upsert uses `ON CONFLICT (index_id, entity_id) DO UPDATE`.

---

## Testing

```bash
# Search library only
clojure -M:test :search

# Unit tests only
clojure -M:test :search --focus-meta :unit
```

H2 integration tests create their own in-memory database — no external dependencies required.

Definitions live on the component, so tests need no registry fixture. Code
still on the deprecated `wagoe.search.shell.registry` resets it with
`registry/clear-registry!`.

---

## License

Part of Wagoe Framework — see main LICENSE file.
