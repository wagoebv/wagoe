(ns wagoe.cache.shell.adapters.in-memory
  "In-memory cache implementation for development and testing.

   Features:
   - Thread-safe operations using Clojure atoms
   - TTL support with automatic expiration
   - LRU eviction policy
   - Statistics tracking
   - Pattern matching
   - Namespace support

   Suitable for:
   - Local development without Redis
   - Fast unit testing
   - CI/CD pipelines
   - Single-process applications

   NOT suitable for:
   - Distributed systems (not shared across processes)
   - Production use (no persistence)
   - High-memory workloads (limited by JVM heap)"
  (:require [wagoe.cache.ports :as ports]
            [clojure.string :as str])
  (:import [java.time Instant Duration]))

;; =============================================================================
;; State Management
;; =============================================================================

(defrecord CacheEntry
           [value created-at expires-at access-count last-accessed-at access-order])

(defrecord InMemoryState
           [entries         ; atom: map of key -> CacheEntry
            stats           ; atom: {:hits :misses :evictions}
            config          ; {:max-size :default-ttl :track-stats?}
            namespace       ; optional namespace prefix
            access-counter]) ; atom: monotonic counter for LRU ordering

(defn- create-state
  "Create initial cache state."
  ([config] (create-state config nil))
  ([config namespace]
   (->InMemoryState
    (atom {})
    (atom {:hits 0 :misses 0 :evictions 0})
    config
    namespace
    (atom 0))))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- now
  "Get current time as Instant."
  []
  (Instant/now))

(defn- add-namespace
  "Add namespace prefix to key if namespace is set."
  [namespace key]
  (if namespace
    (str namespace ":" (name key))
    (name key)))

(defn- strip-namespace
  "Remove namespace prefix from key."
  [namespace key]
  (if namespace
    (let [prefix (str namespace ":")]
      (if (str/starts-with? key prefix)
        (subs key (count prefix))
        key))
    key))

(defn- expired?
  "Check if cache entry has expired."
  [entry]
  (when-let [expires-at (:expires-at entry)]
    (.isAfter (now) expires-at)))

(defn- calculate-expires-at
  "Calculate expiration time from TTL seconds."
  [ttl-seconds]
  (when ttl-seconds
    (.plusSeconds (now) ttl-seconds)))

(defn- record-hit!
  "Record cache hit in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :hits inc)))

(defn- record-miss!
  "Record cache miss in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :misses inc)))

(defn- record-eviction!
  "Record cache eviction in statistics."
  [stats-atom track-stats?]
  (when track-stats?
    (swap! stats-atom update :evictions inc)))

(defn- evict-lru!
  "Evict least recently used entry. Uses monotonic access-order for deterministic
   ordering when timestamps are identical (sub-millisecond operations)."
  [entries-atom stats-atom track-stats?]
  (let [entries @entries-atom]
    (when (seq entries)
      (let [lru-key (first (sort-by (fn [[_ entry]]
                                      (:access-order entry))
                                    entries))]
        (swap! entries-atom dissoc (first lru-key))
        (record-eviction! stats-atom track-stats?)))))

(defn- wildcard-pattern->regex
  "Convert wildcard pattern to regex.
   Example: 'user:*' -> #'user:.*'"
  [pattern]
  (-> pattern
      (str/replace "*" ".*")
      (str/replace "?" ".")
      re-pattern))

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defrecord InMemoryCache [state]
  ports/ICache

  (get-value [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (cond
        (nil? entry)
        (do
          (record-miss! (:stats state) (:track-stats? (:config state)))
          nil)

        (expired? entry)
        (do
          (swap! entries dissoc namespaced-key)
          (record-miss! (:stats state) (:track-stats? (:config state)))
          nil)

        :else
        (do
          (record-hit! (:stats state) (:track-stats? (:config state)))
          ;; Update access count, last accessed time, and monotonic order
          (swap! entries update namespaced-key
                 (fn [e]
                   (-> e
                       (update :access-count inc)
                       (assoc :last-accessed-at (now))
                       (assoc :access-order (swap! (:access-counter state) inc)))))
          (:value entry)))))

  (set-value! [this key value]
    (ports/set-value! this key value (:default-ttl (:config state))))

  (set-value! [_this key value ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (->CacheEntry
                 value
                 (now)
                 (calculate-expires-at ttl-seconds)
                 0
                 (now)
                 (swap! (:access-counter state) inc))]
      ;; Evict before adding to prevent evicting the newly added entry
      (when-let [max-size (:max-size (:config state))]
        (when (and (>= (count @entries) max-size)
                   (not (contains? @entries namespaced-key)))
          (evict-lru! entries (:stats state) (:track-stats? (:config state)))))
      (swap! entries assoc namespaced-key entry)
      true))

  (delete-key! [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (if (contains? @entries namespaced-key)
        (do
          (swap! entries dissoc namespaced-key)
          true)
        false)))

  (exists? [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (boolean (and entry (not (expired? entry))))))

  (ttl [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (when (and entry (not (expired? entry)))
        (when-let [expires-at (:expires-at entry)]
          (.getSeconds (Duration/between (now) expires-at))))))

  (expire! [_this key ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (if-let [_entry (get @entries namespaced-key)]
        (do
          (swap! entries assoc-in [namespaced-key :expires-at]
                 (calculate-expires-at ttl-seconds))
          true)
        false)))

  ;; =============================================================================
  ;; Batch Operations
  ;; =============================================================================

  ports/IBatchCache

  (get-many [this keys]
    (into {}
          (keep (fn [k]
                  (when-let [v (ports/get-value this k)]
                    [k v]))
                keys)))

  (set-many! [this key-value-map]
    (ports/set-many! this key-value-map (:default-ttl (:config state))))

  (set-many! [this key-value-map ttl-seconds]
    (doseq [[k v] key-value-map]
      (ports/set-value! this k v ttl-seconds))
    (count key-value-map))

  (delete-many! [this keys]
    (reduce (fn [count k]
              (if (ports/delete-key! this k)
                (inc count)
                count))
            0
            keys))

  ;; =============================================================================
  ;; Atomic Operations
  ;; =============================================================================

  ports/IAtomicCache

  (increment! [this key]
    (ports/increment! this key 1))

  (increment! [_this key delta]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)]
      (-> (swap! entries
                 (fn [cache]
                   (let [current-entry (get cache namespaced-key)
                         current-value (if current-entry
                                         (:value current-entry)
                                         0)
                         new-value (+ current-value delta)]
                     (assoc cache namespaced-key
                            ;; Keep the existing expiry. Redis INCR does, and a
                            ;; counter that loses its TTL never expires — the
                            ;; callers here set it once, on the first increment,
                            ;; so wiping it on the second means it is never set
                            ;; again. Rate-limit windows and circuit-breaker
                            ;; failure counts then accumulate for the life of
                            ;; the process.
                            (->CacheEntry new-value
                                          (or (:created-at current-entry) (now))
                                          (:expires-at current-entry)
                                          0 (now)
                                          (swap! (:access-counter state) inc))))))
          (get namespaced-key)
          :value)))

  (decrement! [this key]
    (ports/decrement! this key 1))

  (decrement! [this key delta]
    (ports/increment! this key (- delta)))

  (set-if-absent! [this key value]
    (ports/set-if-absent! this key value (:default-ttl (:config state))))

  (set-if-absent! [this key value ttl-seconds]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry   (get @entries namespaced-key)]
      ;; An expired entry is absent. `contains?` alone made a lease taken with a
      ;; TTL permanent until something else happened to prune it — so a holder
      ;; that died never released it, and nothing could take it again. Redis
      ;; SETNX honours expiry, and callers write against that.
      (if (and entry (not (expired? entry)))
        false
        (do
          (ports/set-value! this key value ttl-seconds)
          true))))

  (compare-and-swap! [_this key expected-value new-value]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          result (atom false)]
      (swap! entries
             (fn [cache]
               (let [current-entry (get cache namespaced-key)
                     current-value (:value current-entry)]
                 (if (= current-value expected-value)
                   (do
                     (reset! result true)
                     (assoc cache namespaced-key
                            (->CacheEntry new-value (now) (:expires-at current-entry) 0 (now)
                                          (swap! (:access-counter state) inc))))
                   cache))))
      @result))

  ;; =============================================================================
  ;; Pattern Operations
  ;; =============================================================================

  ports/IPatternCache

  (keys-matching [_this pattern]
    (let [namespaced-pattern (add-namespace (:namespace state) pattern)
          regex (wildcard-pattern->regex namespaced-pattern)
          entries (:entries state)
          namespace (:namespace state)]
      (into #{}
            (comp
             (filter (fn [[k _]] (re-matches regex k)))
             (map (fn [[k _]] (strip-namespace namespace k))))
            @entries)))

  (delete-matching! [this pattern]
    (let [matching-keys (ports/keys-matching this pattern)]
      (ports/delete-many! this matching-keys)))

  (count-matching [this pattern]
    (count (ports/keys-matching this pattern)))

  ;; =============================================================================
  ;; Namespace Operations
  ;; =============================================================================

  ports/INamespacedCache

  (with-namespace [_this namespace]
    (->InMemoryCache
     (->InMemoryState
      (:entries state)
      (:stats state)
      (:config state)
      namespace
      (:access-counter state))))

  (clear-namespace! [_this namespace]
    ;; Clear the given namespace absolutely, through a namespace-free view, so a
    ;; cache that itself has a namespace doesn't double-prefix the match pattern.
    (let [bare (->InMemoryCache
                (->InMemoryState (:entries state) (:stats state) (:config state)
                                 nil (:access-counter state)))]
      (ports/delete-matching! bare (str namespace ":*"))))

  ;; =============================================================================
  ;; Cache Statistics
  ;; =============================================================================

  ports/ICacheStats

  (cache-stats [_this]
    (let [entries @(:entries state)
          stats @(:stats state)
          total-requests (+ (:hits stats) (:misses stats))
          hit-rate (if (pos? total-requests)
                     (/ (:hits stats) (double total-requests))
                     0.0)]
      {:size (count entries)
       :hits (:hits stats)
       :misses (:misses stats)
       :hit-rate hit-rate
       :evictions (:evictions stats)
       :memory-usage nil}))  ; Not available for in-memory

  (clear-stats! [_this]
    (reset! (:stats state) {:hits 0 :misses 0 :evictions 0})
    true)

  ;; =============================================================================
  ;; Cache Management
  ;; =============================================================================

  ports/ICacheManagement

  (flush-all! [_this]
    (let [entries (:entries state)
          size (count @entries)]
      (reset! entries {})
      size))

  (ping [_this]
    true)  ; In-memory cache is always available

  (close! [_this]
    true))  ; Nothing to close for in-memory cache

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-in-memory-cache
  "Create in-memory cache instance.

   Args:
     config - Optional configuration map:
              :default-ttl - Default TTL in seconds
              :max-size - Maximum number of entries (LRU eviction)
              :track-stats? - Track hit/miss statistics (default true)

   Returns:
     InMemoryCache instance implementing all cache protocols"
  ([]
   (create-in-memory-cache {}))
  ([config]
   (let [default-config {:track-stats? true}
         merged-config (merge default-config config)
         state (create-state merged-config)]
     (->InMemoryCache state))))

;; =============================================================================
;; Testing Utilities
;; =============================================================================

(defn clear-all!
  "Clear all cache entries and statistics. Useful for testing.

   Args:
     cache - InMemoryCache instance"
  [cache]
  (ports/flush-all! cache)
  (ports/clear-stats! cache))

(defn get-all-entries
  "Get all cache entries. Useful for testing.

   Args:
     cache - InMemoryCache instance

   Returns:
     Map of key -> CacheEntry"
  [cache]
  @(:entries (:state cache)))
