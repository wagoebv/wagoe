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
│   │   ├── index.clj       # defsearch (data only), collect-definitions, api-enabled?, build-document* (pure)
│   │   ├── query.clj       # SQL builders (PostgreSQL FTS + H2/SQLite LIKE fallback)
│   │   ├── ranking.clj     # pure relevance scoring: field weights, recency boost, score normalization (z-score/min-max), combine
│   │   ├── highlighting.clj # pure snippet extraction + match highlighting (highlight-field, extract-snippet-with-highlight)
│   │   └── ui.clj          # Hiccup: indices-page, index-detail-page, search-results-fragment
│   └── shell/
│       ├── registry.clj    # deprecated global registry, read as a fallback
│       ├── trigram.clj      # pg_trgm migration + installed? check
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

### Index definitions

An index is a `SearchDefinition` map, held by the `:wagoe/search` component.
Hand definitions to it, keyed by index id, in either of two places:

```edn
;; config.edn — data only
:wagoe/search
{:indexes {:product-search {:entity-type :product
                            :fields      [{:name :title :weight :a}]
                            :api?        true}}}
```

```clojure
;; your module's ig-config — the :search part, like :http and :auth
(require '[wagoe.search.core.index :refer [defsearch]])

(defsearch product-search
  {:entity-type :product
   :language    "english"
   :fields      [{:name :title   :weight :a}
                 {:name :body    :weight :b}
                 {:name :tags    :weight :c}]
   :filters     [:tenant-id :category-id]})  ; optional: filterable dimensions

(defn ig-config [_settings _ctx]
  {:components {:shop/order-index {}}
   :search     {:product-search product-search
                :orders         (ig/ref :shop/order-index)}})  ; a component works too
```

`defsearch` only binds the var. `:id` defaults to the key; one that disagrees,
or an id defined twice, fails the boot. Two systems in one JVM keep their own
indexes, so tests need no fixture.

`wagoe.search.shell.registry/defsearch` still works: the service reads that
global registry for an id its component lacks, and logs a deprecation warning
once.

### Which indexes the HTTP API serves

Search, suggest and the admin pages serve an index only when its definition
opts in:

| Definition                              | Served to                                           |
|-----------------------------------------|-----------------------------------------------------|
| neither key                             | nobody: 404                                         |
| `:api? true`                            | any signed-in user                                  |
| `:api {:authorize (fn [request def])}`  | requests the hook returns truthy for; 404 otherwise |

A refused index answers the same 404 as an unknown one, so ids cannot be
enumerated. An index behind your own page, with its own visibility rules,
should leave both keys off and call `ports/search` from that page. The
framework ships no indexes.

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

| Adapter capability | Strategy |
|--------------------|----------|
| `:full-text` (PostgreSQL) | `to_tsvector` / `plainto_tsquery` / `ts_rank` / `ts_headline` |
| no `:full-text` (H2, SQLite, MySQL) | `LOWER(content_all) LIKE LOWER('%q%')` |

Suggestions on PostgreSQL use pg_trgm `word_similarity`. A migration creates
the extension; where the migration user may not, it logs a warning and
suggestions use LIKE until it exists (BOU-661). They query with `<%`, served
by a GIN `gin_trgm_ops` index a later migration builds when pg_trgm is there;
an extension created after that migration needs the index built by hand. pg_trgm
in a schema off the `search_path` makes the store fall back to LIKE (BOU-676).

`create-search-store` takes the adapter: `:full-text` picks the query and
`:on-conflict` the upsert (ADR-039).

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

Searching takes a signed-in user; indexing, removing and the web UI take the
admin role (BOU-568). Search, suggest and the web UI serve only an index that
opts in (see above).

An index with `:tenant-id` in its `:filters` is tenant-scoped. Search and
suggest then filter on the request's resolved tenant, and only for an active
member of it; a `tenant_id` the caller sends is ignored. With no tenant
resolved, only a global admin may search it (across tenants, with their own
filters); anyone else gets 403.

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
| GET    | `/search`                        | List served indices |
| GET    | `/search/:index-id`              | Index detail page  |
| GET    | `/search/:index-id/results`      | HTMX search fragment |

---

## Database Table

```sql
CREATE TABLE search_documents (
  id          VARCHAR(255) NOT NULL PRIMARY KEY,
  index_id    VARCHAR(255) NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  entity_id   VARCHAR(255) NOT NULL,
  language    TEXT NOT NULL DEFAULT 'english',
  weight_a    TEXT NOT NULL DEFAULT '',   -- highest priority (A)
  weight_b    TEXT NOT NULL DEFAULT '',
  weight_c    TEXT NOT NULL DEFAULT '',
  weight_d    TEXT NOT NULL DEFAULT '',   -- lowest priority
  content_all TEXT NOT NULL DEFAULT '',   -- all fields joined (LIKE fallback)
  metadata    TEXT,                       -- pr-str encoded EDN map
  filters     TEXT,                       -- JSON: {"tenant_id":"abc","category_id":"hw"}
  updated_at  VARCHAR(64) NOT NULL,
  UNIQUE (index_id, entity_id)
);
```

Upsert uses `ON CONFLICT (index_id, entity_id) DO UPDATE SET ...` so re-indexing
an existing entity is always safe to call.

The library ships this table as migrations (`resources/wagoe/search/migrations/`);
`bb migrate up` creates it once search is enabled. Do not add `filters` by hand:
the shipped `20260312000000-search-filters` migration adds it, and fails if it exists.

---

## Testing

```bash
# Search library only
clojure -M:test :search

# Unit tests only
clojure -M:test :search --focus-meta :unit

# Full suite
clojure -M:test:test/all
```

H2 integration tests (`persistence_test.clj`) create their own in-memory database
with the `search_documents` table — no external dependencies required.

---

**Linting:** the macro binds a var clj-kondo cannot see from a published jar,
so the bound name reads as an unresolved symbol. From 1.0.0-rc-4,
`wagoe-search` ships a clj-kondo export carrying the rule (BOU-503) — but an
export applies only once copied into `.clj-kondo/imports/`. clj-kondo does not
read exports during an ordinary lint, and `bb check` passes source paths rather
than the classpath. Import them once, and again after changing dependencies:

```bash
bb lint:imports     # writes .clj-kondo/imports/ — commit it
```

Before rc-4, or if you would rather not commit the imports, keep the rule local:

```clojure
{:lint-as {wagoe.search.core.index/defsearch clojure.core/def}}
```

## Common Pitfalls

### 1. An index is not searchable over HTTP

Search and suggest answer 404 for an index without `:api? true` or an `:api`
map. That is the default; opt in per index.

Code still on the deprecated `wagoe.search.shell.registry` shares one global
atom across tests; reset it with `registry/clear-registry!`, or move the
definitions to `:wagoe/search`.

### 2. Pass the adapter in tests

Pass the test database's adapter to `create-search-store`, e.g. `(h2/new-adapter)`.

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
{:enabled? true
 :indexes  {}}   ; optional: index id -> definition
```

The search module auto-detects the database type from the shared
`:wagoe/db-context`.

---

**Last Updated**: 2026-03-12
