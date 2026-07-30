# Cache Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Distributed caching — Redis or in-memory, TTL, atomic ops. A thin **adapter
library** (no `core/`): two interchangeable backends behind one set of
protocols, plus a tenant-scoped wrapper for multi-tenant isolation.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.cache.ports` | Protocol definitions (the cache port) — see below |
| `wagoe.cache.schema` | Malli schemas: `CacheKey`, `CacheValue`, `TTL`, `CacheEntry`, `CacheConfig`, `RedisConfig`, `CacheStats` + `valid-*?` / `explain-*` validators |
| `wagoe.cache.shell.adapters.in-memory` | In-memory adapter (atoms, TTL, LRU eviction, stats) — `create-in-memory-cache` |
| `wagoe.cache.shell.adapters.redis` | Redis adapter (Jedis pool, Nippy serialization) — `create-redis-pool`, `create-redis-cache` |
| `wagoe.cache.shell.tenant-cache` | Tenant-scoped wrapper with automatic key prefixing — `create-tenant-cache`, `extract-tenant-cache` |
| `wagoe.cache.shell.module-wiring` | Integrant `:wagoe/cache` init/halt; selects adapter by `:provider` |

## The Cache Port

The port is **split across seven protocols** in `wagoe.cache.ports`. Both
adapters (and `TenantCache`) implement all of them, so any cache instance
supports every method below.

**`ICache`** — basic operations
- `(get-value [this key])` — value or `nil` if absent
- `(set-value! [this key value])` / `(set-value! [this key value ttl-seconds])` — store, optional TTL; returns `true`
- `(delete-key! [this key])` — `true` if deleted, `false` if absent
- `(exists? [this key])` — boolean
- `(ttl [this key])` — remaining seconds, or `nil` if no expiration / missing
- `(expire! [this key ttl-seconds])` — set expiration on an existing key

**`IBatchCache`** — bulk operations
- `(get-many [this keys])` — map of key→value, missing keys omitted
- `(set-many! [this kv-map])` / `(set-many! [this kv-map ttl-seconds])` — returns count set
- `(delete-many! [this keys])` — returns count deleted

**`IAtomicCache`** — atomic operations
- `(increment! [this key])` / `(increment! [this key delta])` — returns new value
- `(decrement! [this key])` / `(decrement! [this key delta])` — returns new value
- `(set-if-absent! [this key value])` / `(... ttl-seconds])` — SETNX; `true` if set, `false` if key exists
- `(compare-and-swap! [this key expected new])` — CAS; `true` if swapped

**`IPatternCache`** — pattern operations (glob-style, e.g. `"user:*"`)
- `(keys-matching [this pattern])` — set of matching keys
- `(delete-matching! [this pattern])` — count deleted
- `(count-matching [this pattern])` — count

**`INamespacedCache`** — `(with-namespace [this ns])` → prefixed cache view; `(clear-namespace! [this ns])`

**`ICacheStats`** — `(cache-stats [this])` → `{:size :hits :misses :hit-rate :memory-usage ...}`; `(clear-stats! [this])`

**`ICacheManagement`** — `(flush-all! [this])`; `(ping [this])` health check; `(close! [this])` release resources

## Adapters

| Adapter | Backing | Use for | Not for |
|---------|---------|---------|---------|
| **in-memory** | Clojure atoms | dev, tests, CI, single-process | distributed / multi-replica (not shared across processes), production persistence |
| **redis** | Jedis pool + Nippy | production, multi-replica, microservices | environments without a Redis instance |

Selection happens in `module-wiring` via the config `:provider` key
(`:redis` or `:in-memory`). Unknown/absent providers fall back to in-memory with
a warning.

## Integrant Wiring

Component key: **`:wagoe/cache`**. Config is passed straight to the adapter.

```clojure
;; Redis (production) — resources/conf/{dev,prod,acc}/config.edn
:wagoe/cache
{:provider    :redis
 :host        "localhost"
 :port        6379
 :password    "..."          ; omit the key entirely if no password
 :database    0
 :timeout     2000           ; ms
 :default-ttl 300            ; seconds
 :max-total   10             ; pool sizing
 :max-idle    5
 :min-idle    1}

;; In-memory (dev without Docker / tests)
:wagoe/cache
{:provider     :in-memory
 :default-ttl  300
 :max-size     10000         ; entries before LRU eviction
 :track-stats? true}
```

`halt-key!` calls `close!` when the instance satisfies `ICacheManagement`
(closes the Jedis pool; no-op for in-memory). In the sample configs
`:wagoe/cache` ships under `:inactive` — move it to `:active` to enable it.
Downstream components reference it with `(ig/ref :wagoe/cache)` (e.g. HTTP
rate-limiting and the user service session/user caches).

## Usage

```clojure
(require '[wagoe.cache.ports :as cache]
         '[wagoe.cache.shell.adapters.in-memory :as in-mem])

(def c (in-mem/create-in-memory-cache {:max-size 1000 :track-stats? true}))

;; Basic get/set with TTL (seconds)
(cache/set-value! c :user-123 {:name "Alice"} 3600)
(cache/get-value  c :user-123)          ; => {:name "Alice"}
(cache/ttl        c :user-123)          ; => ~3600
(cache/delete-key! c :user-123)

;; Atomic ops (rate-limiting pattern, as used by platform interceptors)
(cache/increment! c "rl:client-1" 1)    ; => 1, then 2, ...
(cache/expire!    c "rl:client-1" 60)
(cache/set-if-absent! c :lock "holder" 30)  ; distributed lock (SETNX)

;; Batch + pattern
(cache/set-many! c {:a 1 :b 2} 300)
(cache/get-many  c [:a :b])             ; => {:a 1 :b 2}
(cache/keys-matching c "user:*")

;; Redis
(require '[wagoe.cache.shell.adapters.redis :as redis])
(def pool (redis/create-redis-pool {:host "localhost" :port 6379}))
(def rc   (redis/create-redis-cache pool {:default-ttl 300 :prefix "app"}))

;; Tenant-scoped — keys become "tenant:<id>:<key>"
(require '[wagoe.cache.shell.tenant-cache :as tc])
(def tenant-a (tc/create-tenant-cache c "acme"))
(cache/set-value! tenant-a :user-123 data)   ; stored as "tenant:acme:user-123"
;; In a handler: (tc/extract-tenant-cache c request) reads :tenant/:tenant-id off the request
```

## Common Pitfalls

1. **Keys**: strings or keywords; internally `name`-d to strings. Keep a consistent format.
2. **TTL is in seconds**, not ms. `ttl` returning `nil` means "missing or no expiration" — distinguish with `exists?`.
3. **Serialization**: in-memory stores Clojure values as-is; Redis uses Nippy (binary, type-preserving — keywords, sets, ratios, `java.time`/Temporal round-trip). Values must be Nippy-freezable; raw Java objects without a freezer and functions are not supported. Integers are stored in Redis' native decimal form so `increment!`/`decrement!` work and preserve TTL.
4. **Pattern cost**: in-memory does an O(n) scan; Redis uses cursor-based SCAN. Expensive on large caches — avoid hot-path `keys-matching`/`delete-matching!`.
5. **LRU eviction is in-memory only** (`:max-size`). The `:eviction-policy` schema enum lists `:lru/:lfu/:fifo/:random` but the in-memory adapter implements LRU; Redis relies on the server's own eviction config.
6. **Tenant isolation**: `flush-all!` on a `TenantCache` only deletes that tenant's `tenant:<id>:*` keys, and `close!` does **not** close the shared underlying cache. But `cache-stats` returns **global** (not tenant-scoped) stats.
7. **Rate-limiting fallback**: without an active `:wagoe/cache`, the HTTP rate limiter falls back to a per-process counter — not a shared limit across replicas.

## Testing

```bash
clojure -M:test:db/h2 :cache
```

Redis adapter tests self-skip when no Redis is reachable; in-memory and
tenant-cache tests run unconditionally. `in-memory/clear-all!` is a test helper
for resetting state between cases.

## Links

- [Root AGENTS Guide](../../AGENTS.md)
