(ns wagoe.geo.shell.service
  "Public API for the geo module.

   Entry points:
   - geocode!         — forward geocoding with cache-first lookup
   - reverse-geocode! — reverse geocoding (no caching, coordinates not hashed)
   - distance         — pure Haversine distance delegate

   `geocode!` flow:
     1. Compute query-hash from the AddressQuery
     2. If a cache is configured, attempt cache-lookup
     3. On cache miss, iterate providers until one returns a result
     4. Cache-store! the result (if cache configured and result non-nil)
     5. Return result or nil"
  (:require [wagoe.geo.core.address :as address]
            [wagoe.geo.core.math :as math]
            [wagoe.geo.ports :as ports]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; geocode!
;; =============================================================================

(defn geocode!
  "Forward geocode an address query.

   Args:
     service - GeoService map (from module_wiring or test fixtures):
               {:providers [provider ...] :cache cache-or-nil}
     query   - AddressQuery map

   Returns:
     GeoResult map or nil if no provider returned a result."
  [{:keys [providers cache]} query]
  (let [h (address/query-hash query)]
    (or
     ;; 1. Cache hit
     (when cache
       (when-let [cached (ports/cache-lookup cache h)]
         (log/debug "geo cache hit" {:hash h})
         cached))
     ;; 2. Provider chain
     (let [result (some (fn [p]
                          (log/debug "trying provider" {:provider (type p)})
                          (ports/geocode p query))
                        providers)]
       (when result
         ;; 3. Cache store
         (when cache
           (ports/cache-store! cache h result))
         result)))))

;; =============================================================================
;; reverse-geocode!
;; =============================================================================

(defn reverse-geocode!
  "Reverse geocode coordinates to an address.

   Args:
     service - GeoService map
     point   - GeoPoint map with :lat and :lng

   Returns:
     GeoResult map or nil."
  [{:keys [providers]} point]
  (some (fn [p]
          (log/debug "reverse-geocode via provider" {:provider (type p)})
          (ports/reverse-geocode p point))
        providers))

;; =============================================================================
;; distance
;; =============================================================================

(defn distance
  "Calculate the great-circle distance between two points.

   Delegates to core/math/haversine-distance — this function is pure.

   Args:
     point-a - GeoPoint map with :lat and :lng
     point-b - GeoPoint map with :lat and :lng

   Returns:
     Distance in kilometres as a double."
  [point-a point-b]
  (math/haversine-distance point-a point-b))
