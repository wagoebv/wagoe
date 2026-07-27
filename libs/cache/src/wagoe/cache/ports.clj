(ns wagoe.cache.ports
  "Port definitions for distributed caching.

   This module defines protocols for distributed caching, providing a
   unified interface for different cache backends (Redis, Memcached, in-memory).

   Key Features:
   - Get/Set/Delete operations with TTL
   - Batch operations for performance
   - Atomic operations (increment/decrement)
   - Pattern-based operations (keys, delete by pattern)
   - Namespace support for multi-tenancy
   - Cache statistics and monitoring")

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defprotocol ICache
  "Protocol for basic cache operations."

  (get-value [this key]
    "Get value from cache by key.

    Args:
      key - Cache key (string or keyword)

    Returns:
      Cached value or nil if not found")

  (set-value! [this key value] [this key value ttl-seconds]
    "Set value in cache with optional TTL.

    Args:
      key - Cache key (string or keyword)
      value - Value to cache (must be serializable)
      ttl-seconds - Time-to-live in seconds (optional)

    Returns:
      true if successful")

  (delete-key! [this key]
    "Delete key from cache.

    Args:
      key - Cache key

    Returns:
      true if key was deleted, false if key didn't exist")

  (exists? [this key]
    "Check if key exists in cache.

    Args:
      key - Cache key

    Returns:
      true if key exists, false otherwise")

  (ttl [this key]
    "Get remaining TTL for key.

    Args:
      key - Cache key

    Returns:
      Remaining seconds or nil if key doesn't exist or has no expiration")

  (expire! [this key ttl-seconds]
    "Set expiration time for existing key.

    Args:
      key - Cache key
      ttl-seconds - Time-to-live in seconds

    Returns:
      true if successful"))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(defprotocol IBatchCache
  "Protocol for batch cache operations."

  (get-many [this keys]
    "Get multiple values at once.

    Args:
      keys - Collection of cache keys

    Returns:
      Map of key -> value (missing keys are omitted)")

  (set-many! [this key-value-map] [this key-value-map ttl-seconds]
    "Set multiple key-value pairs at once.

    Args:
      key-value-map - Map of key -> value
      ttl-seconds - Optional TTL for all keys

    Returns:
      Number of keys set")

  (delete-many! [this keys]
    "Delete multiple keys at once.

    Args:
      keys - Collection of cache keys

    Returns:
      Number of keys deleted"))

;; =============================================================================
;; Atomic Operations
;; =============================================================================

(defprotocol IAtomicCache
  "Protocol for atomic cache operations."

  (increment! [this key] [this key delta]
    "Atomically increment numeric value.

    Args:
      key - Cache key
      delta - Amount to increment (default 1)

    Returns:
      New value after increment")

  (decrement! [this key] [this key delta]
    "Atomically decrement numeric value.

    Args:
      key - Cache key
      delta - Amount to decrement (default 1)

    Returns:
      New value after decrement")

  (set-if-absent! [this key value] [this key value ttl-seconds]
    "Set value only if key doesn't exist (SETNX).

    Args:
      key - Cache key
      value - Value to set
      ttl-seconds - Optional TTL

    Returns:
      true if value was set, false if key already exists")

  (compare-and-swap! [this key expected-value new-value]
    "Atomically set value only if current value equals expected value (CAS).

    Args:
      key - Cache key
      expected-value - Value to compare against current value
      new-value - Value to set if comparison succeeds

    Returns:
      true if value was updated, false if current value didn't match expected"))

;; =============================================================================
;; Pattern Operations
;; =============================================================================

(defprotocol IPatternCache
  "Protocol for pattern-based cache operations."

  (keys-matching [this pattern]
    "Get all keys matching pattern.

    Args:
      pattern - Pattern string (e.g., 'user:*', 'session:*')

    Returns:
      Set of matching keys")

  (delete-matching! [this pattern]
    "Delete all keys matching pattern.

    Args:
      pattern - Pattern string

    Returns:
      Number of keys deleted")

  (count-matching [this pattern]
    "Count keys matching pattern.

    Args:
      pattern - Pattern string

    Returns:
      Count of matching keys"))

;; =============================================================================
;; Namespace Operations
;; =============================================================================

(defprotocol INamespacedCache
  "Protocol for namespaced cache operations."

  (with-namespace [this namespace]
    "Create a namespaced cache view.

    Args:
      namespace - Namespace prefix (e.g., 'user', 'session')

    Returns:
      Cache instance with namespace prefix")

  (clear-namespace! [this namespace]
    "Clear all keys in a namespace.

    Args:
      namespace - Namespace prefix

    Returns:
      Number of keys deleted"))

;; =============================================================================
;; Cache Statistics
;; =============================================================================

(defprotocol ICacheStats
  "Protocol for cache statistics and monitoring."

  (cache-stats [this]
    "Get cache statistics.

    Returns:
      Map with:
        :size - Number of keys in cache
        :hits - Cache hit count (if tracked)
        :misses - Cache miss count (if tracked)
        :hit-rate - Hit rate percentage (if tracked)
        :memory-usage - Memory usage in bytes (if available)")

  (clear-stats! [this]
    "Clear cache statistics.

    Returns:
      true if successful"))

;; =============================================================================
;; Cache Management
;; =============================================================================

(defprotocol ICacheManagement
  "Protocol for cache management operations."

  (flush-all! [this]
    "Clear entire cache (use with caution!).

    Returns:
      Number of keys deleted")

  (ping [this]
    "Check cache connection health.

    Returns:
      true if cache is reachable, false otherwise")

  (close! [this]
    "Close cache connection and release resources.

    Returns:
      true if successful"))
