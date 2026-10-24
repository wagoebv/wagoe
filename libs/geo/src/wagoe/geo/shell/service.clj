(ns wagoe.geo.shell.service
  "Public API for the geo module.

   Entry points:
   - geocode!         — forward geocoding with cache-first lookup
   - reverse-geocode! — reverse geocoding (no caching, coordinates not hashed)
   - distance         — pure Haversine distance delegate

   The first two take {:only #{:pdok}} to use only the named providers. The
   component, GeoService, answers them through ports/GeoServiceProtocol too.

   `geocode!` flow:
     1. Compute query-hash from the AddressQuery (and :only, when given)
     2. If a cache is configured, attempt cache-lookup
     3. On cache miss, iterate providers until one returns a result
     4. Cache-store! the result (if cache configured and result non-nil)
     5. Return result or nil"
  (:require [wagoe.geo.core.address :as address]
            [wagoe.geo.core.math :as math]
            [wagoe.geo.ports :as ports]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Provider selection
;; =============================================================================

(defn- pick-providers
  "The providers named in `only`, in configured order; every one without it.
   A name is known from :provider-names, which the wiring sets beside :providers."
  [{:keys [providers provider-names]} only]
  (if (nil? only)
    providers
    (let [only (set only)]
      (keep (fn [[n p]] (when (only n) p))
            (map vector provider-names providers)))))

(defn- no-provider [service only]
  (log/warn "No configured geo provider is named in :only"
            {:only only :configured (:provider-names service)}))

;; =============================================================================
;; geocode!
;; =============================================================================

(defn geocode!
  "Forward geocode an address query.

   Args:
     service - GeoService (from module_wiring) or a map
               {:providers [provider ...] :provider-names [kw ...] :cache cache-or-nil}
     query   - AddressQuery map
     opts    - optional {:only #{:pdok}}: the providers this call may use, by
               name. Results are cached per restriction.

   Returns:
     GeoResult map or nil if no provider returned a result."
  ([service query]
   (geocode! service query nil))
  ([{:keys [cache] :as service} query {:keys [only]}]
   (let [providers (pick-providers service only)]
     (if (and only (empty? providers))
       (no-provider service only)
       (let [h (address/query-hash query only)]
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
              result))))))))

;; =============================================================================
;; reverse-geocode!
;; =============================================================================

(defn reverse-geocode!
  "Reverse geocode coordinates to an address.

   Args:
     service - GeoService or map, as for geocode!
     point   - GeoPoint map with :lat and :lng
     opts    - optional {:only #{:pdok}}, as for geocode!

   Returns:
     GeoResult map or nil."
  ([service point]
   (reverse-geocode! service point nil))
  ([service point {:keys [only]}]
   (let [providers (pick-providers service only)]
     (if (and only (empty? providers))
       (no-provider service only)
       (some (fn [p]
               (log/debug "reverse-geocode via provider" {:provider (type p)})
               (ports/reverse-geocode p point))
             providers)))))

;; =============================================================================
;; GeoService — the :wagoe/geo-service component
;; =============================================================================

(defrecord GeoService [providers provider-names cache]
  ports/GeoServiceProtocol
  (lookup [this query opts] (geocode! this query opts))
  (reverse-lookup [this point opts] (reverse-geocode! this point opts)))

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
