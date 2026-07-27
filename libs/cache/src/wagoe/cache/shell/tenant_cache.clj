(ns wagoe.cache.shell.tenant-cache
  "Tenant-aware caching with automatic key prefixing.

   This module provides tenant-scoped cache operations to prevent cache
   collisions between tenants in multi-tenant applications.

   Key Features:
   - Automatic tenant-id prefixing for all cache keys
   - Transparent tenant context extraction from middleware
   - Compatible with all ICache implementations (Redis, in-memory)
   - No changes required to existing cache calls
   - Complete cache isolation between tenants

   Usage:
     ;; Wrap existing cache with tenant context
     (def tenant-cache (create-tenant-cache cache tenant-id))

     ;; Use normally - keys automatically prefixed
     (ports/set-value! tenant-cache :user-123 user-data)
     ;; Actual key: \"tenant:abc123:user-123\"

     ;; Bulk operations work too
     (ports/set-many! tenant-cache {:user-1 data1 :user-2 data2})
     ;; Keys: \"tenant:abc123:user-1\", \"tenant:abc123:user-2\"

   Pattern:
     All cache keys are prefixed with: tenant:<tenant-id>:<original-key>
     Example: tenant:acme_corp:user:456"
  (:require [wagoe.cache.ports :as ports]
            [clojure.string :as str]))

;; =============================================================================
;; Key Prefixing
;; =============================================================================

(defn tenant-cache-key
  "Prefix cache key with tenant ID for isolation.

   Format: tenant:<tenant-id>:<original-key>

   Args:
     tenant-id - Tenant identifier (string)
     key - Original cache key (string or keyword)

   Returns:
     Prefixed key string

   Examples:
     (tenant-cache-key \"acme\" :user-123)
     => \"tenant:acme:user-123\"

     (tenant-cache-key \"globex\" \"session:abc\")
     => \"tenant:globex:session:abc\""
  [tenant-id key]
  (str "tenant:" tenant-id ":" (name key)))

(defn- prefix-key
  "Prefix a single key with tenant context."
  [tenant-id key]
  (tenant-cache-key tenant-id key))

(defn- prefix-keys
  "Prefix multiple keys with tenant context."
  [tenant-id keys]
  (map (partial prefix-key tenant-id) keys))

(defn- prefix-key-map
  "Prefix all keys in a map with tenant context."
  [tenant-id key-map]
  (into {}
        (map (fn [[k v]]
               [(prefix-key tenant-id k) v]))
        key-map))

(defn- unprefix-key
  "Remove tenant prefix from key (for results).

   Args:
     tenant-id - Tenant identifier
     prefixed-key - Key with tenant prefix

   Returns:
     Original key without prefix (as keyword)"
  [tenant-id prefixed-key]
  (let [prefix (str "tenant:" tenant-id ":")
        key-str (name prefixed-key)]
    (if (str/starts-with? key-str prefix)
      (keyword (subs key-str (count prefix)))
      (keyword key-str))))

(defn- unprefix-key-map
  "Remove tenant prefix from all keys in result map."
  [tenant-id result-map]
  (into {}
        (map (fn [[k v]]
               [(unprefix-key tenant-id k) v]))
        result-map))

;; =============================================================================
;; Tenant-Scoped Cache Adapter
;; =============================================================================

(defrecord TenantCache [cache tenant-id]
  ports/ICache
  (get-value [_this key]
    (ports/get-value cache (prefix-key tenant-id key)))

  (set-value! [_this key value]
    (ports/set-value! cache (prefix-key tenant-id key) value))

  (set-value! [_this key value ttl-seconds]
    (ports/set-value! cache (prefix-key tenant-id key) value ttl-seconds))

  (delete-key! [_this key]
    (ports/delete-key! cache (prefix-key tenant-id key)))

  (exists? [_this key]
    (ports/exists? cache (prefix-key tenant-id key)))

  (ttl [_this key]
    (ports/ttl cache (prefix-key tenant-id key)))

  (expire! [_this key ttl-seconds]
    (ports/expire! cache (prefix-key tenant-id key) ttl-seconds))

  ports/IBatchCache
  (get-many [_this keys]
    (let [prefixed-keys (prefix-keys tenant-id keys)
          results (ports/get-many cache prefixed-keys)]
      (unprefix-key-map tenant-id results)))

  (set-many! [_this key-value-map]
    (ports/set-many! cache (prefix-key-map tenant-id key-value-map)))

  (set-many! [_this key-value-map ttl-seconds]
    (ports/set-many! cache (prefix-key-map tenant-id key-value-map) ttl-seconds))

  (delete-many! [_this keys]
    (ports/delete-many! cache (prefix-keys tenant-id keys)))

  ports/IAtomicCache
  (increment! [_this key]
    (ports/increment! cache (prefix-key tenant-id key)))

  (increment! [_this key delta]
    (ports/increment! cache (prefix-key tenant-id key) delta))

  (decrement! [_this key]
    (ports/decrement! cache (prefix-key tenant-id key)))

  (decrement! [_this key delta]
    (ports/decrement! cache (prefix-key tenant-id key) delta))

  (set-if-absent! [_this key value]
    (ports/set-if-absent! cache (prefix-key tenant-id key) value))

  (set-if-absent! [_this key value ttl-seconds]
    (ports/set-if-absent! cache (prefix-key tenant-id key) value ttl-seconds))

  (compare-and-swap! [_this key expected-value new-value]
    (ports/compare-and-swap! cache (prefix-key tenant-id key) expected-value new-value))

  ports/IPatternCache
  (keys-matching [_this pattern]
    ;; Prefix pattern with tenant context
    (let [prefixed-pattern (prefix-key tenant-id pattern)
          results (ports/keys-matching cache prefixed-pattern)]
      (set (map (partial unprefix-key tenant-id) results))))

  (delete-matching! [_this pattern]
    (ports/delete-matching! cache (prefix-key tenant-id pattern)))

  (count-matching [_this pattern]
    (ports/count-matching cache (prefix-key tenant-id pattern)))

  ports/INamespacedCache
  (with-namespace [_this namespace]
    ;; Namespace is added AFTER tenant prefix
    ;; Format: tenant:<tenant-id>:<namespace>:<key>
    (let [tenant-prefix (str "tenant:" tenant-id ":")
          full-namespace (str tenant-prefix namespace)]
      (ports/with-namespace cache full-namespace)))

  (clear-namespace! [_this namespace]
    (let [tenant-prefix (str "tenant:" tenant-id ":")
          full-namespace (str tenant-prefix namespace)]
      (ports/clear-namespace! cache full-namespace)))

  ports/ICacheStats
  (cache-stats [_this]
    ;; Return global stats (not tenant-specific)
    (ports/cache-stats cache))

  (clear-stats! [_this]
    ;; Clear global stats
    (ports/clear-stats! cache))

  ports/ICacheManagement
  (flush-all! [_this]
    ;; IMPORTANT: Only flush tenant's keys, not entire cache
    (let [tenant-pattern (str "tenant:" tenant-id ":*")]
      (ports/delete-matching! cache tenant-pattern)))

  (ping [_this]
    (ports/ping cache))

  (close! [_this]
    ;; Do NOT close underlying cache (shared across tenants)
    ;; Return true to indicate operation understood
    true))

;; =============================================================================
;; Public API
;; =============================================================================

(defn create-tenant-cache
  "Create a tenant-scoped cache wrapper.

   Wraps an existing cache implementation with automatic tenant key prefixing.
   All cache operations will be scoped to the specified tenant.

   Args:
     cache - Base cache implementation (ICache)
     tenant-id - Tenant identifier (string)

   Returns:
     Tenant-scoped cache instance implementing all cache protocols

   Example:
     (def base-cache (redis-cache/create-redis-cache pool))
     (def tenant-cache (create-tenant-cache base-cache \"acme-corp\"))

     ;; Keys automatically prefixed
     (ports/set-value! tenant-cache :user-123 user-data)
     ;; Stored as: \"tenant:acme-corp:user-123\"

     ;; Complete cache isolation
     (def other-tenant (create-tenant-cache base-cache \"globex\"))
     (ports/get-value other-tenant :user-123)
     ;; => nil (different tenant namespace)

   Notes:
     - Thread-safe (delegates to underlying cache)
     - Supports all cache protocols (ICache, IBatchCache, etc.)
     - flush-all! only deletes tenant's keys, not entire cache
     - close! does NOT close underlying cache (shared resource)"
  [cache tenant-id]
  {:pre [(some? cache)
         (string? tenant-id)
         (not (str/blank? tenant-id))]}
  (->TenantCache cache tenant-id))

(defn extract-tenant-cache
  "Extract tenant-scoped cache from request context.

   Convenience function to create tenant cache from middleware context.

   Args:
     cache - Base cache implementation
     request - HTTP request with tenant context (from middleware)

   Returns:
     Tenant-scoped cache or base cache if no tenant context

   Example:
     (defn my-handler [request]
       (let [tenant-cache (extract-tenant-cache cache request)
             user-data (ports/get-value tenant-cache :user-123)]
         {:status 200 :body user-data}))

   Middleware contract:
     Request must contain either:
       {:tenant {:id \"tenant-123\" ...}} OR
       {:tenant-id \"tenant-123\"}

   If no tenant context found, returns base cache (public namespace)."
  [cache request]
  {:pre [(some? cache)
         (map? request)]}
  (if-let [tenant-id (or (get-in request [:tenant :id])
                         (:tenant-id request))]
    (create-tenant-cache cache tenant-id)
    cache))

(comment
  ;; Usage Examples
  ;; ==============

  (require '[wagoe.cache.shell.adapters.in-memory])

  ;; 1. Create tenant cache manually
  (def base-cache (wagoe.cache.shell.adapters.in-memory/create-in-memory-cache))
  (def tenant-cache (create-tenant-cache base-cache "acme-corp"))

  (ports/set-value! tenant-cache :user-123 {:name "Alice"})
  (ports/get-value tenant-cache :user-123)
  ;; => {:name "Alice"}

  ;; 2. Keys are isolated per tenant
  (def tenant-a (create-tenant-cache base-cache "tenant-a"))
  (def tenant-b (create-tenant-cache base-cache "tenant-b"))

  (ports/set-value! tenant-a :shared-key "A's value")
  (ports/set-value! tenant-b :shared-key "B's value")

  (ports/get-value tenant-a :shared-key)
  ;; => "A's value"

  (ports/get-value tenant-b :shared-key)
  ;; => "B's value"

  ;; 3. Extract from request (in handler)
  (def request {:tenant {:id "acme" :slug "acme-corp"}})
  (def tenant-cache (extract-tenant-cache base-cache request))

  (ports/set-value! tenant-cache :session-abc "token-xyz")
  ;; Stored as: "tenant:acme:session-abc"

  ;; 4. Batch operations
  (ports/set-many! tenant-cache
                   {:user-1 "Alice"
                    :user-2 "Bob"
                    :user-3 "Charlie"})

  (ports/get-many tenant-cache [:user-1 :user-2])
  ;; => {:user-1 "Alice", :user-2 "Bob"}

  ;; 5. Pattern matching
  (ports/keys-matching tenant-cache "user-*")
  ;; => #{:user-1 :user-2 :user-3}

  (ports/count-matching tenant-cache "user-*")
  ;; => 3

  ;; 6. Flush only tenant's data
  (ports/flush-all! tenant-cache)
  ;; Deletes only "tenant:acme:*" keys
  ;; Other tenants' data remains intact
  )
