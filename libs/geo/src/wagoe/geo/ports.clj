(ns wagoe.geo.ports
  "Protocol definitions for the geo module.

   FC/IS rule: protocols are interfaces — no implementation here.
   Adapters (shell layer) implement these protocols.")

;; =============================================================================
;; GeoProviderProtocol
;; =============================================================================

(defprotocol GeoProviderProtocol
  "Contract for geocoding provider adapters.

   Implementations live in shell/adapters/:
   - NominatimAdapter (OpenStreetMap, free, rate-limited to 1 req/sec)
   - GoogleAdapter    (Google Maps Geocoding API, requires API key)
   - MapboxAdapter    (Mapbox Geocoding API, requires access token)"

  (geocode [this query]
    "Forward geocode an address query to coordinates.

     Args:
       query - AddressQuery map with optional :address :postcode :city :country

     Returns:
       GeoResult map on success, nil on no results or error.")

  (reverse-geocode [this point]
    "Reverse geocode coordinates to an address.

     Args:
       point - GeoPoint map with :lat and :lng

     Returns:
       GeoResult map on success, nil on no results or error."))

;; =============================================================================
;; GeoCacheProtocol
;; =============================================================================

(defprotocol GeoCacheProtocol
  "Contract for geo result caching.

   The default implementation (DbGeoCache) persists results in a
   `geo_cache` database table. An atom-backed implementation is used
   in tests."

  (cache-lookup [this query-hash]
    "Look up a cached geocoding result by its query hash.

     Args:
       query-hash - SHA-256 hex string (produced by address/query-hash)

     Returns:
       GeoResult map with :cached? true if found and not expired, else nil.")

  (cache-store! [this query-hash result]
    "Persist a geocoding result in the cache.

     Args:
       query-hash - SHA-256 hex string
       result     - GeoResult map to store

     Returns:
       nil (side-effect only)."))
