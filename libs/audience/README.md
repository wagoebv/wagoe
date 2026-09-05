# wagoe-audience

[![Status](https://img.shields.io/badge/status-alpha-orange)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-audience.svg)](https://clojars.org/com.wagoe/wagoe-audience)

> Rule-based audience segmentation for the Wagoe framework — define segments with `defaudience`, resolve them via a SQL + predicate hybrid pipeline, and compose them with AND/OR/NOT. Batteries included: DB-backed membership cache and an HTMX builder UI.

---

## Quick Start

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-audience {:mvn/version "1.0.0-beta-7"}}}
```

```clojure
(require '[wagoe.audience.shell.registry :as audience])
(require '[wagoe.audience.ports :as ports])

;; Define a segment
(audience/defaudience active-free-users
  {:id      :active-free-users
   :label   "Active free-plan users"
   :filters [{:type :role        :field :plan :op :eq          :value "free"}
             {:type :last-active              :op :within-days :value 30}]
   :cache   {:ttl-minutes 60}})

;; Resolve the audience (via injected IAudienceResolver)
(ports/resolve-audience resolver :active-free-users)
;; => {:user-ids #{#uuid "..." ...} :count 142 :cached? false :evaluated-at #inst "..."}

;; Membership check
(ports/member? resolver :active-free-users user-uuid)
;; => true | false

;; Force cache bypass
(ports/resolve-audience resolver :active-free-users {:force-refresh? true})
```

---

## Integrant Configuration

Add to `resources/conf/{env}/config.edn` and require `wagoe.audience.shell.module-wiring` at system start:

```edn
:wagoe/audience
{:db-ctx           #ig/ref :wagoe/db-context
 :cache-service    #ig/ref :wagoe/cache
 :user-data-source #ig/ref :wagoe/user-data-source}

:wagoe/audience-routes
{:audience-service #ig/ref :wagoe/audience}
```

The `:wagoe/audience` component returns `{:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}`.

`:wagoe/audience-routes` returns `{:api [...] :web [...]}` for composition by the HTTP handler.

`:user-data-source` is required. Provide any implementation of `IUserDataSource`:

```clojure
(defrecord MyUserDataSource [db]
  ports/IUserDataSource
  (query-users-sql [_ clause] ...)
  (load-users      [_ user-ids] ...))
```

---

## API

```clojure
(require '[wagoe.audience.shell.registry :as audience])
(require '[wagoe.audience.ports :as ports])

;; Registry
(audience/get-audience :active-free-users)   ;; => {:id :active-free-users ...}
(audience/list-audiences)                    ;; => (:active-free-users ...)
(audience/clear-registry!)                   ;; tests only

;; Resolve
(ports/resolve-audience resolver :segment-id)
(ports/resolve-audience resolver :segment-id {:force-refresh? true})
(ports/member? resolver :segment-id user-uuid)

;; Cache invalidation
(ports/invalidate     cache :segment-id)
(ports/invalidate-all cache)
```

Segments can be composed with AND, OR, and NOT using the `:compose` key:

```clojure
(audience/defaudience paid-active
  {:id      :paid-active
   :label   "Paid and active"
   :compose {:and [{:ref :paid-users}
                   {:ref :active-users}]}})

(audience/defaudience paid-not-churned
  {:id      :paid-not-churned
   :label   "Paid and not churned"
   :compose {:and [{:ref :paid-users}
                   {:not {:ref :churned-users}}]}})
```

---

## DB Migration

Run once before using audience segmentation. When `wagoe-audience` is on the
classpath, `clojure -M:migrate up` auto-discovers these migrations:

```sql
-- libs/audience/resources/wagoe/audience/migrations/...-audience-segments.up.sql
CREATE TABLE audience_segments (
  audience_id   TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  definition    JSONB NOT NULL,
  cache_config  JSONB,
  cached_at     TIMESTAMP,
  member_count  INTEGER,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- libs/audience/resources/wagoe/audience/migrations/...-audience-memberships.up.sql
CREATE TABLE audience_memberships (
  audience_id TEXT      NOT NULL,
  user_id     UUID      NOT NULL,
  cached_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (audience_id, user_id)
);
```

---

## Filter Types

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

---

## Tests

```bash
clojure -M:test :audience
clojure -M:test --focus-meta :unit :audience
```

See [AGENTS.md](AGENTS.md) for full developer guide, common pitfalls, and composition examples.
