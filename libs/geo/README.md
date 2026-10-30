# wagoe-geo

[![Status](https://img.shields.io/badge/status-incubating-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-geo.svg)](https://clojars.org/com.wagoe/wagoe-geo)

> Multi-provider geocoding for the Wagoe framework — addresses to coordinates, coordinates to addresses, and distance calculations. Batteries included: rate limiting, DB-backed caching, and a provider fallback chain.

---

## Quick Start

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-geo {:mvn/version "1.1.0"}}}
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
 :cache?     true}

;; PDOK Locatieserver (Dutch addresses, free, no key)
:wagoe/geo-service
{:provider  :pdok
 :cache-ttl 86400
 :cache?    true}

;; Google Maps
:wagoe/geo-service
{:provider  :google
 :api-key   #env WAG_GEO_API_KEY
 :cache-ttl 86400
 :cache?    true}

;; Fallback chain (try OSM first, then Google)
:wagoe/geo-service
{:provider   [:openstreetmap :google]
 :api-key    #env WAG_GEO_API_KEY
 :cache-ttl  86400
 :rate-limit 1
 :cache?     true}
```

`:timeout-ms` (default 3000) bounds each provider call's connect and read. A
timeout is a miss: logged, and the chain moves on.

`:cache? true` caches lookups in `geo_cache` through `:wagoe/db-context`, which the
module wires itself; run the migration first. A config that does not match
`wagoe.geo.schema/GeoConfig` fails at boot.

---

## API

```clojure
(require '[wagoe.geo.shell.service :as geo])

;; Geocode an address → coordinates (cache-first)
(geo/geocode! service {:address "Damrak 1" :city "Amsterdam"})
(geo/geocode! service {:postcode "1012 JS"})

;; Postcode + house number → street, city and point (PDOK fills :street and :house-number)
(geo/geocode! service {:postcode "1012 JS" :house-number 1 :house-number-addition "A"})
;; => {:street "Dam" :house-number "1" :house-number-addition "A" :postcode "1012JS"
;;     :city "Amsterdam" :lat 52.37 :lng 4.89 :provider :pdok :cached? false ...}

;; Only some providers, by name, for this call (cached per restriction)
(geo/geocode! service {:postcode "1012 JS" :house-number 1} {:only #{:pdok}})

;; Or through the component's port, without requiring a shell namespace
(require '[wagoe.geo.ports :as geo-ports])
(geo-ports/lookup service {:postcode "1012 JS" :house-number 1} {:only #{:pdok}})

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
  street            TEXT,  -- these three: 20261023100001-geo-cache-street
  house_number      TEXT,
  house_number_addition TEXT,
  postcode          TEXT,
  city              TEXT,
  country           TEXT,
  provider          TEXT NOT NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## Providers

| Provider | Key `:provider` | API Key | Min. interval (default) | Notes |
|----------|----------------|---------|------------|-------|
| OpenStreetMap Nominatim | `:openstreetmap` | None | 1000 ms | Set `:user-agent` (warns and uses a shared default without) |
| Google Maps Geocoding API | `:google` | Required | 100 ms | Returns structured address components |
| Mapbox Geocoding API | `:mapbox` | Required | 100 ms | Returns GeoJSON (lng, lat order) |
| PDOK Locatieserver | `:pdok` | None | 100 ms | Netherlands only; exact on postcode + house number |

---

## Tests

```bash
clojure -M:test :geo
clojure -M:test --focus-meta :unit :geo
```

See [AGENTS.md](AGENTS.md) for full developer guide, common pitfalls, and REPL smoke checks.
