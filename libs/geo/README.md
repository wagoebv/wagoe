# wagoe-geo

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-geo.svg)](https://clojars.org/com.wagoe/wagoe-geo)

> Multi-provider geocoding for the Wagoe framework — addresses to coordinates, coordinates to addresses, and distance calculations. Batteries included: rate limiting, DB-backed caching, and a provider fallback chain.

---

## Quick Start

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-geo {:mvn/version "1.0.0-beta-6"}}}
```

```clojure
(require '[wagoe.geo.shell.service :as geo])
(require '[wagoe.geo.shell.adapters.osm :as osm])

;; Create an adapter (no API key needed for OpenStreetMap)
(def adapter (osm/create-nominatim-adapter
               {:user-agent "MyApp/1.0 (contact@example.com)"}))

;; Wire up a service (no caching)
(def geo-service {:providers [adapter] :cache nil})

;; Forward geocode
(geo/geocode! geo-service {:postcode "1012 JS" :city "Amsterdam"})
;; => {:lat 52.374 :lng 4.890 :formatted-address "..." :provider :openstreetmap :cached? false}

;; Distance between two points (pure, no I/O)
(geo/distance {:lat 52.3676 :lng 4.9041}
               {:lat 51.9225 :lng 4.4792})
;; => 56.8  (km)
```

---

## Integrant Configuration

Add to `resources/conf/{env}/config.edn` and require `wagoe.geo.shell.module-wiring` at system start:

```edn
;; OpenStreetMap (free, no key)
:wagoe/geo-service
{:provider   :openstreetmap
 :user-agent "MyApp/1.0 (contact@example.com)"
 :cache-ttl  86400
 :db         #ig/ref :wagoe/db}

;; Google Maps
:wagoe/geo-service
{:provider  :google
 :api-key   #env WAG_GEO_API_KEY
 :cache-ttl 86400
 :db        #ig/ref :wagoe/db}

;; Fallback chain (try OSM first, then Google)
:wagoe/geo-service
{:provider   [:openstreetmap :google]
 :api-key    #env WAG_GEO_API_KEY
 :cache-ttl  86400
 :rate-limit 1
 :db         #ig/ref :wagoe/db}
```

---

## API

```clojure
(require '[wagoe.geo.shell.service :as geo])

;; Geocode an address → coordinates (cache-first)
(geo/geocode! service {:address "Damrak 1" :city "Amsterdam"})
(geo/geocode! service {:postcode "1012 JS"})

;; Reverse geocode coordinates → address
(geo/reverse-geocode! service {:lat 52.374 :lng 4.890})

;; Distance between two points (pure, no I/O, no service needed)
(geo/distance {:lat 52.3676 :lng 4.9041}
               {:lat 51.5074 :lng -0.1278})
;; => 357.4 km
```

---

## DB Migration

Run once before using DB-backed caching. When `wagoe-geo` is on the
classpath, `clojure -M:migrate up` now auto-discovers this migration:

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
```

---

## Providers

| Provider | Key `:provider` | API Key | Rate Limit | Notes |
|----------|----------------|---------|------------|-------|
| OpenStreetMap Nominatim | `:openstreetmap` | None | 1 req/sec | Must set `:user-agent` |
| Google Maps Geocoding API | `:google` | Required | 50 req/sec | Returns structured address components |
| Mapbox Geocoding API | `:mapbox` | Required | 10 req/sec | Returns GeoJSON (lng, lat order) |

---

## Tests

```bash
clojure -M:test :geo
clojure -M:test --focus-meta :unit :geo
```

See [AGENTS.md](AGENTS.md) for full developer guide, common pitfalls, and REPL smoke checks.
