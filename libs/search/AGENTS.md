# wagoe-search — AI Agent Quick Reference

Full-text search library for the Wagoe Framework.
Provides document indexing, full-text search (PostgreSQL FTS / LIKE fallback), and an admin UI.

---

## Module Layout

```
libs/search/
├── src/wagoe/search/
│   ├── schema.clj          # Malli schemas: SearchDocument, SearchResult, SearchResponse, SearchDefinition (+ :filters)
│   ├── ports.clj           # ISearchStore (persistence), ISearchEngine (orchestration)
│   ├── core/
│   │   ├── index.clj       # build-document*, filter-key->json-key (pure)
│   │   ├── query.clj       # SQL builders (PostgreSQL FTS + H2/SQLite LIKE fallback)
│   │   ├── ranking.clj     # pure relevance scoring: field weights, recency boost, score normalization (z-score/min-max), combine
│   │   ├── highlighting.clj # pure snippet extraction + match highlighting (highlight-field, extract-snippet-with-highlight)
│   │   └── ui.clj          # Hiccup: indices-page, index-detail-page, search-results-fragment
│   └── shell/
│       ├── registry.clj    # defsearch macro + global definition registry (mutable state)
│       ├── persistence.clj  # SearchStore (next.jdbc, HoneySQL ON CONFLICT upsert)
│       ├── service.clj      # SearchService (orchestration, pagination, reindex)
│       ├── http.clj         # Ring routes: API + admin web UI
│       └── module_wiring.clj # Integrant keys :wagoe/search + :wagoe/search-routes
└── test/wagoe/search/
    ├── core/index_test.clj         # unit: registry (shell) + build-document*
    ├── core/query_test.clj         # unit: sanitize-query, SQL builders
    ├── core/ranking_test.clj       # unit: scoring/normalization/boost (pure)
    ├── core/highlighting_test.clj  # unit: highlighting + snippet extraction (pure)
    ├── shell/service_test.clj      # unit: MemorySearchStore double
    └── shell/persistence_test.clj  # integration: H2 upsert, delete, search, suggest
```

---

## Core Concepts

### defsearch Macro

Defines a search index and registers it in a global atom registry. The registry
is mutable process state, so it lives in the shell (`wagoe.search.shell.registry`);
the core (`wagoe.search.core.index`) stays pure:

```clojure
(require '[wagoe.search.shell.registry :as registry])

(registry/defsearch product-search
  {:id          :product-search
   :entity-type :product
   :language    "english"
   :fields      [{:name :title   :weight :a}
                 {:name :body    :weight :b}
                 {:name :tags    :weight :c}]
   :filters     [:tenant-id :category-id]})  ; optional: filterable dimensions
```

The var `product-search` holds the `SearchDefinition` map.
`registry/get-search :product-search` retrieves it from the registry.

### build-document*

Converts field values to weighted content columns (pure — `wagoe.search.core.index`):

```clojure
(require '[wagoe.search.core.index :as search])

(search/build-document* product-search entity-id
                       {:title "Widget Pro"
                        :body  "A professional widget"
                        :tags  ["tools" "hardware"]}
                       {:id            document-id
                        :updated-at    now
                        :metadata      {:sku "WGT-001"}
                        :filter-values {:tenant-id   tenant-uuid
                                        :category-id "hardware"}})
;; => {:index-id :product-search :entity-type :product :entity-id uuid
;;     :weight-a "Widget Pro" :weight-b "A professional widget"
;;     :weight-c "tools hardware" :weight-d ""
;;     :content-all "Widget Pro A professional widget tools hardware"
;;     :filter-values {:tenant-id uuid :category-id "hardware"}
;;     :metadata {:sku "WGT-001"} :updated-at #inst "..."})
```

### Dual Query Strategy

| Database   | Strategy                               |
|------------|----------------------------------------|
| PostgreSQL | `to_tsvector` / `plainto_tsquery` / `ts_rank` / `ts_headline` |
| H2, SQLite | `LOWER(content_all) LIKE LOWER('%q%')` |

Adapter is selected by `db-type` passed to `create-search-store`.
`module_wiring.clj` detects it automatically via `db-protocols/dialect`
(nil → assumes `:postgresql`).

---

## Integration: Indexing Documents

Call `ports/index-document!` from your module's service layer or event handler:

```clojure
(require '[wagoe.search.ports :as search-ports])
(require '[wagoe.search.core.index :as search])

;; 1. Get search service from system
(def search-svc (get integrant.repl.state/system :wagoe/search))

;; 2. Index a document (with optional filter values)
(search-ports/index-document!
  search-svc
  :product-search
  product-uuid
  {:title "Widget Pro"
   :body  "A professional widget"
   :tags  ["tools" "hardware"]}
  {:metadata      {:sku "WGT-001"}
   :filter-values {:tenant-id   tenant-uuid
                   :category-id "hardware"}})

;; 3. Remove a document
(search-ports/remove-document! search-svc :product-search product-uuid)
```

## Integration: Searching

```clojure
;; Full-text search with pagination
(search-ports/search search-svc :product-search "widget"
                     {:limit 20 :offset 0 :highlight? true})

;; Filter to a specific tenant or category
(search-ports/search search-svc :product-search "widget"
                     {:limit 20 :offset 0
                      :filters {:tenant-id tenant-uuid
                                :category-id "hardware"}})
;; => {:results [{:entity-type :product :entity-id uuid :rank 0.9 :snippet "..."}]
;;     :total 42 :query "widget" :took-ms 5}

;; Autocomplete / suggestions
(search-ports/suggest search-svc :product-search "wid" {:limit 5})
;; => [{:entity-type :product :entity-id uuid ...}]
```

---

## HTTP Endpoints

### API Endpoints (mounted at `/api/v1/search/...`)

| Method | Path                                        | Action            |
|--------|---------------------------------------------|-------------------|
| POST   | `/search/:index-id`                         | Full-text search  |
| POST   | `/search/:index-id/suggest`                 | Autocomplete      |
| POST   | `/search/documents`                         | Index a document  |
| DELETE | `/search/documents/:entity-type/:entity-id` | Remove a document |

### Web UI (mounted at `/web/admin/search/...`)

| Method | Path                            | Action             |
|--------|----------------------------------|-------------------|
| GET    | `/search`                        | List all indices   |
| GET    | `/search/:index-id`              | Index detail page  |
| GET    | `/search/:index-id/results`      | HTMX search fragment |

---

## Database Table

```sql
CREATE TABLE search_documents (
  id          TEXT NOT NULL PRIMARY KEY,
  index_id    TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   TEXT NOT NULL,
  language    TEXT NOT NULL DEFAULT 'english',
  weight_a    TEXT NOT NULL DEFAULT '',   -- highest priority (A)
  weight_b    TEXT NOT NULL DEFAULT '',
  weight_c    TEXT NOT NULL DEFAULT '',
  weight_d    TEXT NOT NULL DEFAULT '',   -- lowest priority
  content_all TEXT NOT NULL DEFAULT '',   -- all fields joined (LIKE fallback)
  metadata    TEXT,                       -- pr-str encoded EDN map
  filters     TEXT,                       -- JSON: {"tenant_id":"abc","category_id":"hw"}
  updated_at  TEXT NOT NULL,
  UNIQUE (index_id, entity_id)
);
```

Upsert uses `ON CONFLICT (index_id, entity_id) DO UPDATE SET ...` so re-indexing
an existing entity is always safe to call.

The `filters` column requires a migration on existing tables:
```sql
ALTER TABLE search_documents ADD COLUMN filters TEXT;
```
See `resources/migrations/20260312000000-search-filters.up.sql`.

---

## Testing

```bash
# Search library only
clojure -M:test:db/h2 :search

# Unit tests only
clojure -M:test:db/h2 --focus-meta :unit

# Full suite
clojure -M:test:db/h2
```

H2 integration tests (`persistence_test.clj`) create their own in-memory database
with the `search_documents` table — no external dependencies required.

---

## Common Pitfalls

### 1. Registry Leakage Between Tests

`defsearch` registers in a global atom that lives in the shell
(`wagoe.search.shell.registry`). Reset it in test fixtures:

```clojure
(require '[wagoe.search.shell.registry :as registry])

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)))
```

### 2. db-type Detection in module_wiring

`module_wiring.clj` calls `db-protocols/dialect` on the datasource.
`nil` return value is treated as `:postgresql` (FTS path).
In tests, always pass `:h2` explicitly to `create-search-store`.

### 3. Metadata Encoding

Metadata is stored as `pr-str` EDN and read back with `read-string`.
Only use plain Clojure data (maps, strings, numbers, keywords).
Do NOT store Java objects, functions, or records in metadata.

### 4. Empty Query Handling

`empty-query?` returns true for blank strings and strings shorter than 2 chars.
The service layer returns an empty `SearchResponse` (with `:total 0`) without
hitting the database when the query is empty — this is intentional.

### 5. Filter SQL Differs by Database

Filters are stored as compact JSON (`{"tenant_id":"abc"}`). The SQL used to filter
varies by database type:

- **PostgreSQL**: `d.filters::jsonb->>'tenant_id' = ?` (native JSONB operator)
- **H2 / SQLite**: `INSTR(filters, '"tenant_id":"abc"') > 0` (substring match)

H2 2.4.x does not support `JSON_VALUE`, `JSON_EXTRACT`, or `->>` via JDBC prepared
statements — the INSTR fallback is intentional and tested.

Filter keys follow snake_case in JSON: `:tenant-id` → `"tenant_id"`.
This conversion is done by `filter-key->json-key` in `wagoe.search.core.index`.

---

## Configuration

Enable in `resources/conf/<env>/config.edn`:

```edn
:wagoe/search
{:enabled? true}
```

No additional options are required — the search module auto-detects the database
type from the shared `:wagoe/db-context`.

---

**Last Updated**: 2026-03-12
