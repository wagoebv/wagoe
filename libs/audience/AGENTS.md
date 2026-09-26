# wagoe-audience — Dev Guide

## 1. Overview

`wagoe-audience` provides declarative audience segmentation for Wagoe applications. It resolves "who belongs to this audience right now?" using a hybrid SQL + predicate pipeline:

1. Filters that map cleanly to SQL are pushed to the database (fewer rows returned).
2. Filters that need in-process logic (arbitrary predicates, feature-usage lookups) run as Clojure functions over the candidate set.
3. Results are cached in the `audience_memberships` table with a configurable TTL.

The library saves boilerplate around:
- Segment definitions with `defaudience` (code) or DB-persisted JSON (dynamic)
- AND/OR/NOT composition across multiple segments
- Builder UI served via HTMX with Replicant widget mount points
- Cache invalidation strategies (on-demand, scheduled, manual)

**FC/IS rule**: `core/` is pure — no I/O, no logging, no DB. All side effects live in `shell/`.

---

## 2. Quick Start

### Defining a segment (code)

```clojure
;; The definition registry + `defaudience` live in the shell (mutable state)
(require '[wagoe.audience.shell.registry :as audience])

(audience/defaudience active-free-users
  {:id      :active-free-users
   :label   "Active free-plan users"
   :filters [{:type :role   :field :plan   :op :eq          :value "free"}
             {:type :last-active            :op :within-days :value 30}]
   :cache   {:ttl-minutes 60}})

;; Registry operations
(audience/get-audience :active-free-users)   ;; => {:id :active-free-users ...}
(audience/list-audiences)                    ;; => (:active-free-users ...)
(audience/clear-registry!)                   ;; tests only
```

### Resolving an audience

```clojure
(require '[wagoe.audience.ports :as ports])

;; Via IAudienceResolver (injected by Integrant)
(ports/resolve-audience resolver :active-free-users)
;; => {:user-ids #{#uuid "..." ...} :count 142 :cached? false :evaluated-at #inst "..."}

;; Force cache bypass
(ports/resolve-audience resolver :active-free-users {:force-refresh? true})

;; Membership check
(ports/member? resolver :active-free-users user-uuid)
;; => true | false
```

---

## 3. Filter Types

Seven built-in filter types are supported. Dispatch is via the `:type` key.

| Type | SQL phase | Predicate phase | Notes |
|------|-----------|-----------------|-------|
| `:demographics` | yes | yes | General field equality/comparison |
| `:location` | yes | yes | Geographic field — same logic as demographics |
| `:role` | yes | yes | Role equality/comparison |
| `:account-tenure` | yes | yes | Days since `:created-at`; ops: `:gte :gt :lte :lt :eq :neq` |
| `:last-active` | yes | yes | `:within-days N` checks `:last-active-at >= now - N days` |
| `:behavior` | no | yes | `:value` must be a `(fn [user] -> bool)` — code segments only |
| `:feature-usage` | no | yes | `:used-within N` checks `(get-in user [:feature-usage field])` |

**Operators** for field-based types: `:eq :neq :gt :gte :lt :lte :in :contains`

### Registering a custom filter type

```clojure
(require '[wagoe.audience.core.filter :as f])

;; SQL representation (return nil if not SQL-evaluable)
(defmethod f/filter->sql :subscription-tier [filt]
  [:= :subscription_tier (name (:value filt))])

;; In-process predicate
(defmethod f/filter->predicate :subscription-tier [{:keys [op value]}]
  (fn [user]
    (= (get user :subscription-tier) value)))
```

---

## 4. Code vs Dynamic Segments

### Code segments (preferred for app-defined audiences)

Defined with `defaudience`, registered in the in-process atom. Supports fn refs in `:value` (for `:behavior` filters). Not persistable to the database.

```clojure
(audience/defaudience power-users
  {:id      :power-users
   :label   "Power users"
   :filters [{:type :behavior :op :fn :value (fn [u] (> (:login-count u) 100))}]})
```

### Dynamic segments (DB-persisted, data-only)

Stored as JSON in `audience_segments`. Validated against `DynamicAudienceDefinition`, which rejects fn-typed `:value` fields. Loaded at resolve time when the in-process registry misses.

```clojure
(require '[wagoe.audience.schema :as schema])
(require '[malli.core :as m])

;; fn values are rejected by the dynamic schema
(m/validate schema/DynamicAudienceDefinition
  {:id :x :label "X" :filters [{:type :behavior :op :fn :value identity}]})
;; => false
```

---

## 5. Composition

Segments can be composed with AND, OR, and NOT. The `:compose` key accepts a tree built from `{:and [...]}`, `{:or [...]}`, and `{:not {...}}` nodes. Leaves can be result maps `{:user-ids #{...}}` or `{:ref :segment-id}` references.

```clojure
;; AND — intersection
(defaudience paid-active
  {:id      :paid-active
   :label   "Paid and active"
   :compose {:and [{:ref :paid-users}
                   {:ref :active-users}]}})

;; OR — union
(defaudience any-trial-or-paid
  {:id      :any-trial-or-paid
   :label   "Trial or paid"
   :compose {:or [{:ref :trial-users}
                  {:ref :paid-users}]}})

;; NOT — subtract
(defaudience paid-not-churned
  {:id      :paid-not-churned
   :label   "Paid and not churned"
   :compose {:and [{:ref :paid-users}
                   {:not {:ref :churned-users}}]}})
```

Circular `:ref` chains throw `ex-info` with `:type :circular-reference`.

---

## 6. Caching

Results are persisted to `audience_memberships` with a timestamp in `audience_segments.cached_at`.

```clojure
;; Define TTL in the segment
(defaudience weekly-digest-targets
  {:id    :weekly-digest-targets
   :label "Weekly digest recipients"
   :filters [{:type :demographics :field :email-opt-in :op :eq :value true}]
   :cache {:ttl-minutes 1440}})   ;; 24 hours
```

### Refresh modes

| Mode | How |
|------|-----|
| On-demand | Call `resolve-audience` normally — stale cache triggers re-evaluation |
| Force refresh | Pass `{:force-refresh? true}` as opts to bypass cache |
| Manual invalidate | Call `ports/invalidate` or `ports/invalidate-all` on the cache instance |

```clojure
(require '[wagoe.audience.ports :as ports])

;; Invalidate one segment
(ports/invalidate cache :weekly-digest-targets)

;; Invalidate all
(ports/invalidate-all cache)
```

---

## 7. Builder UI

The builder UI is served by `wagoe.audience.shell.http`.

### Routes

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/web/audiences` | List all segments |
| GET | `/web/audiences/builder` | New segment form |
| GET | `/web/audiences/builder/:id` | Edit existing segment |
| POST | `/api/audiences` | Create audience |
| PUT | `/api/audiences/:id` | Update audience |
| DELETE | `/api/audiences/:id` | Delete audience |
| POST | `/api/audiences/preview` | Evaluate filters, return count + sample |
| POST | `/api/audiences/:id/evaluate` | Trigger full evaluation and cache |
| GET | `/api/audiences/:id/members` | List member user-ids |

### HTMX endpoints + Replicant widget mount points

The builder page renders two placeholder `div`s for Replicant widgets:

- `#audience-filter-panel` — filter panel widget mount point
- `#audience-composition-builder` — composition builder widget mount point

A hidden input `#audience-filters-data` carries the serialized filter state to the form POST. The preview section `#audience-preview` is updated via HTMX swap from `POST /api/audiences/preview`.

---

## 8. Consumer Integration

### IAudienceResolver protocol

```clojure
(ns my-app.notifications
  (:require [wagoe.audience.ports :as ports]))

(defn send-campaign [resolver]
  (let [result (ports/resolve-audience resolver :newsletter-subscribers)]
    (doseq [uid (:user-ids result)]
      (send-email! uid))))
```

### Integrant config

```clojure
;; resources/conf/dev/config.edn
{:wagoe/audience
 {:db-ctx           #ig/ref :wagoe/db-context
  :cache-service    #ig/ref :wagoe/cache
  :user-data-source #ig/ref :wagoe/user-data-source}

 :wagoe/audience-routes
 {:audience-service #ig/ref :wagoe/audience}}
```

The `:wagoe/audience` component returns `{:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}`.

`:wagoe/audience-routes` returns `{:api [...] :web [...]}` for composition by the HTTP handler.

**Filter columns.** `:last-active` compiles to `last_active_at`; Wagoe's users table records
`last_login`, so the shipped source translates it. Override with
`:wagoe/audience {:users-field-mapping {…}}` for a schema that spells it differently again.

**As a service.** `service audience` refuses to start: its routes need the admin-only guard,
which is a user-module component that service selection drops. Run `service audience user`.
Refusing is deliberate — segment management served without authorization is worse than a
service that does not come up (BOU-419).

**Date filters.** `:account-tenure` and `:last-active` need `:now`. `compile-segment`'s
two-argument arity supplies it, and a filter may carry its own. Without one they are neither
SQL nor a predicate, and `compile-segment` lists them under `:unsupported` — the service
refuses to resolve an audience that has any, because a definition whose only filter is
dropped compiles to an empty plan, and an empty plan is every user (BOU-425). The cutoff is a bound parameter, not `CURRENT_DATE - INTERVAL`, which only
PostgreSQL parses (BOU-425).

The core emits a `java.time.Instant` and the SQL source decides how it is compared. Everywhere
but SQLite that is a `java.sql.Timestamp` against a timestamp column. SQLite stores timestamps
as ISO-8601 `TEXT`, and compares across storage classes by ordering integers before text, so a
bound Timestamp there does not fail — `>=` matches every row and `<=` matches none.

Text alone is not enough either: `Instant/toString` omits the fraction when it is zero, and
`.` sorts before `Z`, so `…T00:00:00.001Z` sorts *before* a `…T00:00:00Z` cutoff. Both sides
go through SQLite's `datetime()` instead, which is fixed width. Comparison is then to the
second — two instants inside the cutoff second count as equal — which is inside what a filter
defined in whole days promises.

This applies to whatever shape a comparison arrives in, `[:between :col from to]` as well as
`[:>= :col cutoff]`, so a custom `filter->sql` gets the same treatment as the built-in ones.
An `Instant` in a shape the conversion does not recognise still becomes the dialect's storage
value rather than being left for the driver to guess at.

If you point `:users-table` at a schema that stores timestamps differently again, this is the
knob to think about.

**Adapters.** The schema, store and cache are exercised against all four the framework
ships — H2, SQLite, PostgreSQL and MySQL — by `test/wagoe/audience_dialect_test.clj`. MySQL
needs a server: CI runs one as a service, and locally you point the sweep at a container with
`WAGOE_TEST_MYSQL_PORT`. Without one the sweep fails saying so, rather than comparing three
and reporting a pass. An adapter with no entry in the type table fails loudly at schema
initialisation rather than being handed H2 syntax.

**Mounting.** `:wagoe/audience-routes` is mounted only when the user module is enabled, and
every route carries `:wagoe/admin-only-middleware` — these endpoints create and delete
segments and read their member ids, and the global authentication only *sets* `:user` when
credentials are present rather than demanding them. The routes component refuses to
initialise with an empty middleware vector (BOU-419).

**Note**: `:user-data-source` is required. Without it, `resolve-audience` will throw. Provide any implementation of `IUserDataSource`:

```clojure
(defrecord MyUserDataSource [db]
  ports/IUserDataSource
  (query-users-sql [_ clause] ...)
  (load-users [_ user-ids] ...))
```

---

## 9. Testing

### Fixture warning — registry pollution

`defaudience` at namespace load time registers into a global atom (in the shell). Always reset it between tests:

```clojure
(use-fixtures :each
  (fn [f]
    (wagoe.audience.shell.registry/clear-registry!)
    (f)
    (wagoe.audience.shell.registry/clear-registry!)))
```

### Test commands

```bash
# All audience tests
clojure -M:test :audience

# Unit tests only
clojure -M:test --focus-meta :unit :audience

# Single namespace
clojure -M:test :audience --focus wagoe.audience.core.filter-test

# Security-tagged tests
clojure -M:test --focus-meta :security :audience

# Lint
clojure -M:clj-kondo --lint libs/audience/src libs/audience/test
```

---

## 10. Common Pitfalls

### 1. Registry pollution across tests
`defaudience` at top-level in a test namespace registers on load. Use `:each` fixtures with `clear-registry!`. See section 9.

### 2. fn refs in dynamic segments
`:behavior` filters with fn values cannot be serialized to JSON. The `DynamicAudienceDefinition` schema will reject them. If you need behavioral filters in a DB-persisted segment, store a named dispatch key and implement `filter->predicate` for it.

### 3. UUID vs keyword audience ID
Code segments use keyword IDs (`:active-users`). DB-persisted segments use UUIDs as the primary key in `audience_segments.audience_id`, stored as a string. `load-definition` resolves by keyword first (registry), then falls back to the repository. Keep the `:id` keyword consistent across registry and DB.

### 4. H2 vs PostgreSQL JSON handling
The cache reads `cache_config` from the DB as either a `PGobject` (PostgreSQL) or a plain string (H2). The cache layer handles both with `cheshire/parse-string`. If you write custom queries against `cache_config`, always parse the column before accessing keys.

### 5. `compose` vs `filters` — mutually exclusive evaluation paths
If a segment has a `:compose` key, the service takes the composition path and ignores `:filters` on the top-level definition. Compose leaves should be other resolved segments, not raw filter maps.

### 6. Member count vs user-id set
`SegmentResult` always carries both `:count` (integer) and `:user-ids` (set of UUIDs). The `:count` in the DB row (`member_count`) is a cached integer for display. Always use `:user-ids` for actual membership decisions.

### 7. `query-users-sql` with nil clause
Passing `nil` as the HoneySQL clause means "all users". Implement your `IUserDataSource` to handle `nil` explicitly, otherwise you may return an empty set rather than the universe.

---

## 11. FC/IS Rules

| What | Where |
|------|-------|
| Filter multimethod dispatch (`filter->sql`, `filter->predicate`) | `core/filter.clj` |
| Segment registry + `defaudience` macro (mutable state) | `shell/registry.clj` |
| SQL/predicate plan compilation | `core/compiler.clj` |
| AND/OR/NOT set composition | `core/composition.clj` |
| Pure Hiccup UI components | `core/ui.clj` |
| Protocol definitions | `ports.clj` |
| Malli schemas | `schema.clj` |
| DB persistence (next.jdbc, HoneySQL) | `shell/persistence.clj` |
| DB-backed cache (memberships, TTL stamp) | `shell/cache.clj` |
| Evaluation service (IAudienceResolver impl) | `shell/service.clj` |
| HTTP handlers + route definitions | `shell/http.clj` |
| Integrant lifecycle keys | `shell/module_wiring.clj` |

**Strict rule**: `core/` namespaces must not require anything from `shell/`, `clojure.tools.logging`, `next.jdbc`, `honey.sql`, or any I/O library. Violations are caught by `bb check:fcis`.
