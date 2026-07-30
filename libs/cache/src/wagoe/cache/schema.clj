(ns wagoe.cache.schema
  "Malli schemas for distributed caching."
  (:require [malli.core :as m]))

;; =============================================================================
;; Cache Entry Schemas
;; =============================================================================

(def CacheKey
  "Cache key schema."
  [:or :string :keyword])

(def CacheValue
  "Cache value schema (any serializable value)."
  any?)

(def TTL
  "Time-to-live in seconds."
  [:int {:min 0}])

(def CacheEntry
  "Cache entry with metadata."
  [:map
   [:key CacheKey]
   [:value CacheValue]
   [:created-at inst?]
   [:expires-at {:optional true} inst?]
   [:hits {:optional true} [:int {:min 0}]]
   [:metadata {:optional true} [:map-of keyword? any?]]])

;; =============================================================================
;; Cache Configuration
;; =============================================================================

(def CacheConfig
  "Cache configuration schema."
  [:map
   [:default-ttl {:optional true} TTL]
   [:max-size {:optional true} [:int {:min 1}]]
   [:eviction-policy {:optional true} [:enum :lru :lfu :fifo :random]]
   [:track-stats? {:optional true} :boolean]
   [:namespace {:optional true} :string]])

(def RedisConfig
  "Redis cache configuration."
  [:map
   [:host {:optional true} :string]
   [:port {:optional true} [:int {:min 1 :max 65535}]]
   [:password {:optional true} :string]
   [:database {:optional true} [:int {:min 0 :max 15}]]
   [:timeout {:optional true} [:int {:min 0}]]
   [:max-total {:optional true} [:int {:min 1}]]
   [:max-idle {:optional true} [:int {:min 0}]]
   [:min-idle {:optional true} [:int {:min 0}]]
   [:prefix {:optional true} :string]])

;; =============================================================================
;; Cache Statistics
;; =============================================================================

(def CacheStats
  "Cache statistics schema."
  [:map
   [:size [:int {:min 0}]]
   [:hits {:optional true} [:int {:min 0}]]
   [:misses {:optional true} [:int {:min 0}]]
   [:hit-rate {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:memory-usage {:optional true} [:int {:min 0}]]
   [:evictions {:optional true} [:int {:min 0}]]])

;; =============================================================================
;; Batch Operations
;; =============================================================================

(def KeyValueMap
  "Map of keys to values for batch operations."
  [:map-of CacheKey CacheValue])

(def KeySet
  "Set of cache keys."
  [:set CacheKey])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private cache-config-validator (m/validator CacheConfig))
(def ^:private cache-config-explainer (m/explainer CacheConfig))
(def ^:private redis-config-validator (m/validator RedisConfig))
(def ^:private redis-config-explainer (m/explainer RedisConfig))
(def ^:private cache-entry-validator (m/validator CacheEntry))

(defn valid-cache-config?
  "Validate cache configuration."
  [config]
  (cache-config-validator config))

(defn valid-redis-config?
  "Validate Redis configuration."
  [config]
  (redis-config-validator config))

(defn valid-cache-entry?
  "Validate cache entry."
  [entry]
  (cache-entry-validator entry))

(defn explain-cache-config-errors
  "Get human-readable validation errors for cache config."
  [config]
  (cache-config-explainer config))

(defn explain-redis-config-errors
  "Get human-readable validation errors for Redis config."
  [config]
  (redis-config-explainer config))
