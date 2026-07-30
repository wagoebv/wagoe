(ns wagoe.geo.schema
  "Malli validation schemas for the geo module."
  (:require [malli.core :as m]))

;; =============================================================================
;; GeoPoint — a latitude/longitude coordinate pair
;; =============================================================================

(def GeoPoint
  "A geographic coordinate pair.
   :lat — latitude  in decimal degrees (-90  to  90)
   :lng — longitude in decimal degrees (-180 to 180)"
  [:map
   [:lat :double]
   [:lng :double]])

;; =============================================================================
;; AddressQuery — input to geocode!
;; =============================================================================

(def AddressQuery
  "Address lookup query. At least one field should be provided.
   :country defaults to \"Netherlands\" when not supplied."
  [:map
   [:address  {:optional true} :string]
   [:postcode {:optional true} :string]
   [:city     {:optional true} :string]
   [:country  {:optional true} :string]])

;; =============================================================================
;; GeoResult — output from geocode! / reverse-geocode!
;; =============================================================================

(def GeoResult
  "Result of a geocoding or reverse-geocoding operation.
   :provider — keyword identifying which provider returned the result
   :cached?  — true if this result was served from the DB cache"
  [:map
   [:lat               :double]
   [:lng               :double]
   [:formatted-address {:optional true} :string]
   [:postcode          {:optional true} :string]
   [:city              {:optional true} :string]
   [:country           {:optional true} :string]
   [:provider          :keyword]
   [:cached?           :boolean]])

;; =============================================================================
;; GeoConfig — Integrant component configuration
;; =============================================================================

(def GeoConfig
  "Configuration map for the :wagoe/geo-service Integrant component."
  [:map
   [:provider   [:or :keyword [:vector :keyword]]]  ; single or fallback chain
   [:cache-ttl  {:optional true} :int]              ; cache TTL in seconds
   [:rate-limit {:optional true} :int]              ; requests per second
   [:api-key    {:optional true} [:maybe :string]]  ; nil for OSM
   [:db         {:optional true} :any]])             ; next.jdbc datasource

;; =============================================================================
;; Validation helpers
;; =============================================================================

(def ^:private geo-point-validator (m/validator GeoPoint))
(def ^:private address-query-validator (m/validator AddressQuery))
(def ^:private geo-result-validator (m/validator GeoResult))

(defn valid-geo-point?
  "Returns true if the given map satisfies GeoPoint schema."
  [point]
  (geo-point-validator point))

(defn valid-address-query?
  "Returns true if the given map satisfies AddressQuery schema."
  [query]
  (address-query-validator query))

(defn valid-geo-result?
  "Returns true if the given map satisfies GeoResult schema."
  [result]
  (geo-result-validator result))
