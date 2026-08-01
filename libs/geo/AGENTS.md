# wagoe-geo — Dev Guide

## 1. Purpose

`wagoe-geo` provides a multi-provider geocoding abstraction for any Wagoe-based application that needs address-to-coordinate conversion or distance calculations. It eliminates ~50–100 lines of per-project boilerplate by offering:

- **Multi-provider geocoding**: OpenStreetMap (Nominatim), Google Maps, Mapbox
- **Automatic fallback chain**: try providers in order; first non-nil result wins
- **DB-backed caching**: `geo_cache` table with configurable TTL (default 24 h)
- **Per-provider rate limiting**: OSM enforced at 1 req/sec; Google/Mapbox at 100 ms
- **Haversine distance calculation**: pure core function, no HTTP calls

**FC/IS rule**: `core/` is pure. All HTTP calls, DB writes, and rate-limiting side effects live in `shell/`.

---

## 2. Key Namespaces

| Namespace | Layer | Responsibility |
|-----------|-------|----------------|
| `wagoe.geo.schema` | shared | Malli schemas: `GeoPoint`, `AddressQuery`, `GeoResult`, `GeoConfig` |
| `wagoe.geo.ports` | shared | `GeoProviderProtocol`, `GeoCacheProtocol` |
| `wagoe.geo.core.math` | core | `haversine-distance`, `bearing` |
| `wagoe.geo.core.address` | core | `normalize-query`, `query-hash` (SHA-256) |
| `wagoe.geo.shell.adapters.osm` | shell | Nominatim adapter (`NominatimAdapter`) |
| `wagoe.geo.shell.adapters.google` | shell | Google Maps adapter (`GoogleAdapter`) |
| `wagoe.geo.shell.adapters.mapbox` | shell | Mapbox adapter (`MapboxAdapter`) |
| `wagoe.geo.shell.cache` | shell | `DbGeoCache` — next.jdbc-backed cache |
| `wagoe.geo.shell.service` | shell | Public API: `geocode!`, `reverse-geocode!`, `distance` |
| `wagoe.geo.shell.module-wiring` | shell | Integrant `:wagoe/geo-service` |

---

## 3. Integrant Configuration

Add to your `resources/conf/{env}/config.edn`:

```edn
;; OpenStreetMap only (no API key needed)
:wagoe/geo-service
{:provider   :openstreetmap
 :user-agent "MyApp/1.0 (contact@example.com)"
 :cache-ttl  86400
 :db         #ig/ref :wagoe/db}

;; Google Maps with DB cache
:wagoe/geo-service
{:provider  :google
 :api-key   #env WAG_GEO_API_KEY
 :cache-ttl 86400
 :db        #ig/ref :wagoe/db}

;; Fallback chain: try OSM first, fall back to Google
:wagoe/geo-service
{:provider   [:openstreetmap :google]
 :api-key    #env WAG_GEO_API_KEY
 :cache-ttl  86400
 :rate-limit 1
 :db         #ig/ref :wagoe/db}
```

Require the wiring namespace in your system config loader:
```clojure
(require '[wagoe.geo.shell.module-wiring])
```

---

## 4. Public API

```clojure
(require '[wagoe.geo.shell.service :as geo])

;; Forward geocoding (cache-first)
(geo/geocode! geo-service {:postcode "1012 JS" :city "Amsterdam"})
;; => {:lat 52.374 :lng 4.890 :formatted-address "..." :provider :openstreetmap :cached? false}

;; Second call returns from cache
(geo/geocode! geo-service {:postcode "1012 JS" :city "Amsterdam"})
;; => {:lat 52.374 :lng 4.890 ... :provider :openstreetmap :cached? true}

;; Reverse geocoding (no caching)
(geo/reverse-geocode! geo-service {:lat 52.374 :lng 4.890})
;; => {:lat 52.374 :lng 4.890 :formatted-address "..." :provider :openstreetmap :cached? false}

;; Distance (pure, no I/O)
(geo/distance {:lat 52.3676 :lng 4.9041}
              {:lat 51.9225 :lng 4.4792})
;; => 56.8  (km)
```

---

## 5. Core Functions

```clojure
(require '[wagoe.geo.core.math :as math])
(require '[wagoe.geo.core.address :as addr])

;; Haversine distance
(math/haversine-distance {:lat 52.3676 :lng 4.9041}
                          {:lat 51.5074 :lng -0.1278})
;; => ~357 km

;; Bearing (clockwise from north)
(math/bearing {:lat 52.3676 :lng 4.9041}
               {:lat 51.5074 :lng -0.1278})
;; => ~255° (roughly west-southwest)

;; Normalised query string (for inspection/debugging)
(addr/normalize-query {:postcode "1012 LJ" :city "Amsterdam"})
;; => "1012 lj, amsterdam, netherlands"

;; SHA-256 cache key
(addr/query-hash {:postcode "1012 LJ" :city "Amsterdam"})
;; => "3f4a2b..."  (64-char hex)
```

---

## 6. DB Migration

Run before first use. When `wagoe-geo` is on the classpath, `clojure -M:migrate up`
now auto-discovers the library migration. Manual application is only needed if
you are not using the standard Wagoe migration CLI:

```sql
-- libs/geo/resources/wagoe/geo/migrations/20260324010000-geo-cache.up.sql
CREATE TABLE geo_cache (
  address_hash      TEXT PRIMARY KEY,
  lat               NUMERIC(10, 7) NOT NULL,
  lng               NUMERIC(10, 7) NOT NULL,
  formatted_address TEXT,
  postcode          TEXT,
  city              TEXT,
  country           TEXT,
  provider          TEXT NOT NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_geo_cache_coords ON geo_cache(lat, lng);
```

---

## 7. Common Pitfalls

### 1. OSM lon vs lng
The Nominatim JSON response uses the field name `"lon"` (not `"lng"`). The adapter handles this, but if you call the OSM API directly, remember `lon` → `lng` mapping.

### 2. OSM rate limiting
OSM usage policy requires **at most 1 request per second**. The adapter enforces this with `Thread/sleep`. Never create multiple `NominatimAdapter` instances for the same application — each has its own `last-request-ms` atom and would bypass the limit.

### 3. Mapbox coordinate order
Mapbox GeoJSON returns coordinates as `[longitude, latitude]` (not `[lat, lng]`). The adapter extracts them correctly (`(first coords)` = lng, `(second coords)` = lat).

### 4. Google ZERO_RESULTS vs error
When Google Maps returns `"status": "ZERO_RESULTS"`, the HTTP status is still 200 and `"results"` is an empty array. `parse-result` receives `nil` and returns `nil`. This is correct behaviour — handled transparently.

### 5. Cache key collision
The SHA-256 cache key is computed from the **normalised** query (lowercased, trimmed, default country applied). Two queries that normalise to the same string will share the same cache entry. This is intentional and usually correct.

### 6. No cache for reverse geocoding
`reverse-geocode!` does not use the cache. Reverse geocoding typically produces different addresses for slightly different coordinates, making coordinate-based hashing impractical. Cache at the application layer if needed.

### 7. Provider nil → fallback continues
When a provider throws an exception, the adapter returns `nil` and logs a warning. The service layer treats this as a miss and continues to the next provider. Do not rely on exceptions propagating from `geocode!`.

### 8. Thread safety of rate-limit atoms
Each adapter record holds a mutable `last-request-ms` atom. **Atoms are thread-safe** for reads and updates, so concurrent requests from multiple threads will not cause corruption. However, two threads might both pass the rate limit check before either updates the atom, causing brief rate limit overshoots (e.g., 1.1 req/sec instead of 1.0 req/sec). This is acceptable — worst case is slightly exceeding the rate limit by a few milliseconds, not data corruption.

### 9. Multi-instance deployments
**Rate limiting is per-JVM instance, not distributed.** In multi-instance deployments (Kubernetes, ECS), each pod independently enforces rate limits. This may exceed provider rate limits.

**Example**: 3 pods × 1 req/sec = up to 3 req/sec sent to OpenStreetMap (violates usage policy).

**Solutions for production multi-instance deployments**:
- Use API keys with per-credential rate limits (Google Maps, Mapbox — provider enforces globally)
- Add distributed rate limiting via Redis (future enhancement — see `wagoe.platform.shell.http.interceptors` for pattern)
- Run geo service as singleton deployment (scale-to-1, use pod anti-affinity for HA)

For single-instance deployments and development, the current atom-based approach is correct and efficient.

---

## 10. Testing Commands

```bash
# All geo tests
clojure -M:test :geo

# Unit tests only (pure core functions)
clojure -M:test --focus-meta :unit :geo

# Integration tests (mock provider + atom cache)
clojure -M:test --focus-meta :integration :geo

# Contract tests (DbGeoCache against H2)
clojure -M:test --focus-meta :contract :geo

# Lint
clojure -M:clj-kondo --lint libs/geo/src libs/geo/test
```

---

## 11. REPL Smoke Check

```clojure
;; Pure math — no system needed
(require '[wagoe.geo.core.math :as math])
(math/haversine-distance {:lat 52.3676 :lng 4.9041}
                          {:lat 51.5074 :lng -0.1278})
;; => ~357.4

;; Live OSM geocoding (no API key, rate-limited)
(require '[wagoe.geo.shell.adapters.osm :as osm])
(require '[wagoe.geo.shell.service :as geo])

(def adapter (osm/create-nominatim-adapter
               {:user-agent "wagoe-dev/1.0 (dev@example.com)"}))
(def service {:providers [adapter] :cache nil})

(geo/geocode! service {:postcode "1012 JS" :city "Amsterdam"})
;; => {:lat 52.374... :lng 4.890... :formatted-address "..." :provider :openstreetmap :cached? false}

;; Distance
(geo/distance {:lat 52.3676 :lng 4.9041}
               {:lat 51.9225 :lng 4.4792})
;; => ~56.8 km
```
