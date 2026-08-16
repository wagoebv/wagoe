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
  (:import [java.time Instant Duration]
           [java.util.regex Pattern]))

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

(defn- expired-at?
  "Whether `entry` has expired as of `at`.

   The instant is a parameter so a read and the decision taken on it can share
   one: an operation that asks twice can see the entry cross its expiry in
   between and act on one answer while reporting the other."
  [entry ^Instant at]
  (when-let [expires-at (:expires-at entry)]
    (.isAfter at expires-at)))

(defn- expired?
  "Check if cache entry has expired."
  [entry]
  (expired-at? entry (now)))

(defn- live-entry
  "The entry at `namespaced-key`, or nil if it is absent or has expired.

   Nothing sweeps expired entries, so one can sit in the map indefinitely.
   Every operation has to read that as absence, or the adapter keeps answering
   questions about a key that is, to the caller, gone."
  [entries namespaced-key]
  (let [entry (get @entries namespaced-key)]
    (when (and entry (not (expired? entry)))
      entry)))

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

(defn- evict-if-full!
  "Evict before adding, so the new entry is not the one evicted.

   Every path that can add a key has to call this, or `:max-size` holds only
   for the paths that remember to."
  [state namespaced-key]
  (let [entries (:entries state)]
    (when-let [max-size (:max-size (:config state))]
      (when (and (>= (count @entries) max-size)
                 (not (contains? @entries namespaced-key)))
        (evict-lru! entries (:stats state) (:track-stats? (:config state)))))))

(defn- wildcard-pattern->regex
  "Compile a Redis-style glob to a regex.

   Only `*`, `?` and `[…]` are wildcards; everything else is literal. Handing
   the pattern to `re-pattern` unchanged made every regex metacharacter a
   wildcard as well, so `a.b` matched `axb` — and `.` is in half the key
   schemes there are.
   Example: 'user:*' -> #'\\Qu\\E\\Qs\\E…:.*'"
  [pattern]
  (let [^String s (str pattern)
        n         (count s)]
    (re-pattern
     (loop [i 0 out (StringBuilder.)]
       (if (>= i n)
         (str out)
         (let [c (.charAt s i)]
           (case c
             \* (recur (inc i) (.append out ".*"))
             \? (recur (inc i) (.append out "."))
             \[ (let [close (.indexOf s "]" (inc i))]
                  (if (neg? close)
                    (recur (inc i) (.append out (Pattern/quote (str c))))
                    (recur (inc close)
                           (.append out (str "[" (subs s (inc i) close) "]")))))
             (recur (inc i) (.append out (Pattern/quote (str c)))))))))))

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
      (evict-if-full! state namespaced-key)
      (swap! entries assoc namespaced-key entry)
      true))

  (delete-key! [_this key]
    ;; One `swap-vals!`, so the entry reported on is the entry removed. Reading
    ;; first and dropping afterwards leaves a window: a value written in between
    ;; is deleted by the `dissoc` and reported as never having been there.
    (let [namespaced-key (add-namespace (:namespace state) key)
          ;; Read before the removal, and used for the answer: asking the clock
          ;; again afterwards lets an entry lapse in between, so a live entry is
          ;; removed and reported as though it had never been there.
          at             (now)
          [before _]     (swap-vals! (:entries state) dissoc namespaced-key)
          entry          (get before namespaced-key)]
      ;; Expired entries go too — this is as good a moment to reclaim one as
      ;; any — but only a live one counts as deleted.
      (boolean (and entry (not (expired-at? entry at))))))

  (exists? [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          entry (get @entries namespaced-key)]
      (boolean (and entry (not (expired? entry))))))

  (ttl [_this key]
    (let [namespaced-key (add-namespace (:namespace state) key)]
      (when-let [entry (live-entry (:entries state) namespaced-key)]
        (when-let [expires-at (:expires-at entry)]
          ;; Rounded up, as Redis TTL is. Truncating reports 29 for a key set
          ;; to 30 a millisecond ago, and a caller comparing the two adapters
          ;; sees an off-by-one it cannot account for.
          (max 1 (long (Math/ceil (/ (.toMillis (Duration/between (now) expires-at))
                                     1000.0))))))))

  (expire! [_this key ttl-seconds]
    ;; Decided and applied in one `swap-vals!`, against one instant. Reading
    ;; first and writing afterwards gets both branches wrong under contention:
    ;; the `assoc-in` recreates a key deleted in between as an entry with no
    ;; value, and the `dissoc` drops a value written in between while reporting
    ;; that there was nothing there.
    (let [namespaced-key (add-namespace (:namespace state) key)
          at             (now)
          [before _]
          (swap-vals! (:entries state)
                      (fn [cache]
                        (let [entry (get cache namespaced-key)]
                          (cond
                            (nil? entry) cache
                            ;; An expired entry is not a key to put a new expiry
                            ;; on; giving it one brings back a value the caller
                            ;; was told had gone.
                            (expired-at? entry at) (dissoc cache namespaced-key)
                            :else (assoc-in cache [namespaced-key :expires-at]
                                            (calculate-expires-at ttl-seconds))))))
          entry (get before namespaced-key)]
      (boolean (and entry (not (expired-at? entry at))))))

  ;; =============================================================================
  ;; Batch Operations
  ;; =============================================================================

  ports/IBatchCache

  (get-many [this keys]
    ;; A key that is present is in the result whatever its value. `nil` and
    ;; `false` are things callers cache — a negative answer is an answer — and
    ;; dropping them makes a hit indistinguishable from a miss, so the caller
    ;; recomputes it every time.
    (into {}
          (keep (fn [k]
                  (let [v (ports/get-value this k)]
                    (when (or (some? v) (ports/exists? this k))
                      [k v]))))
          keys))

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
                   ;; An expired entry is gone, as it is in Redis: nothing is
                   ;; carried from it. Reusing its value continues a count that
                   ;; should have lapsed, and carrying its expiry returns a
                   ;; value that is already expired — there is no background
                   ;; sweep here, so an expired entry can sit unread for a long
                   ;; time and still be found by this.
                   (let [entry     (get cache namespaced-key)
                         live      (when (and entry (not (expired? entry))) entry)
                         new-value (+ (:value live 0) delta)]
                     (assoc cache namespaced-key
                            ;; A live counter keeps its expiry. Redis INCR does,
                            ;; and callers rely on it: the breaker and the rate
                            ;; limiter each set a TTL once, on the first
                            ;; increment, because that is the only moment they
                            ;; can recognise.
                            (->CacheEntry new-value
                                          (or (:created-at live) (now))
                                          (:expires-at live)
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

  (set-if-absent! [_this key value ttl-seconds]
    ;; The check and the write happen in one `swap!`. Reading `@entries` and
    ;; then calling `set-value!` lets two callers both find the key absent and
    ;; both claim it — and callers use this as a lease, where "exactly one
    ;; wins" is the entire point. Redis SETNX is one operation.
    ;;
    ;; An expired entry counts as absent: `contains?` alone made a lease
    ;; permanent until something happened to prune it, so a holder that died
    ;; never released it.
    (let [namespaced-key (add-namespace (:namespace state) key)
          won            (volatile! false)]
      (evict-if-full! state namespaced-key)
      (swap! (:entries state)
             (fn [cache]
               (let [entry (get cache namespaced-key)]
                 (if (and entry (not (expired? entry)))
                   (do (vreset! won false) cache)
                   (do (vreset! won true)
                       (assoc cache namespaced-key
                              (->CacheEntry value (now)
                                            (calculate-expires-at ttl-seconds)
                                            0 (now)
                                            (swap! (:access-counter state) inc))))))))
      @won))

  (compare-and-swap! [_this key expected-value new-value]
    (let [namespaced-key (add-namespace (:namespace state) key)
          entries (:entries state)
          result (atom false)]
      (swap! entries
             (fn [cache]
               ;; An expired entry is absent, so `nil` is what matches it —
               ;; the same thing a caller says to mean "only if it is not
               ;; there".
               (let [entry         (get cache namespaced-key)
                     current-entry (when (and entry (not (expired? entry))) entry)
                     current-value (:value current-entry)]
                 (if (= current-value expected-value)
                   (do
                     (reset! result true)
                     (assoc cache namespaced-key
                            (->CacheEntry new-value (now) (:expires-at current-entry) 0 (now)
                                          (swap! (:access-counter state) inc))))
                   ;; Reset, not left alone: `swap!` re-runs this under
                   ;; contention, and a retry that loses must not report the
                   ;; win of the attempt before it.
                   (do (reset! result false)
                       cache)))))
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
             (remove (fn [[_ entry]] (expired? entry)))
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
