(ns wagoe.cache.shell.adapters.redis-test
  "Integration tests for the Redis cache adapter.

   These tests require a running Redis instance.
   If Redis is not available on localhost:6379 the tests are skipped."
  {:kaocha.testable/meta {:integration true :redis true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.cache.shell.adapters.redis :as redis-adapter]
            [wagoe.cache.ports :as ports])
  (:import [java.nio.charset StandardCharsets]
           [redis.clients.jedis Jedis]))

;; =============================================================================
;; Redis availability check
;; =============================================================================

(defonce ^:private redis-availability (atom nil))

(defn redis-available?
  "Check if Redis is reachable on localhost:6379."
  []
  (if-let [cached @redis-availability]
    cached
    (let [result (try
                   (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :timeout 1000})
                         cache (redis-adapter/create-redis-cache pool)
                         alive? (ports/ping cache)]
                     (ports/close! cache)
                     (.close pool)
                     alive?)
                   (catch Exception _
                     false))]
      (reset! redis-availability result)
      result)))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def ^:dynamic *cache* nil)
(def ^:dynamic *pool* nil)

(defn with-redis-cache
  "Fixture that creates a namespaced Redis cache and flushes the namespace after each test."
  [f]
  (if (redis-available?)
    (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :database 15})
          cache (redis-adapter/create-redis-cache pool {:prefix "test"})]
      (binding [*cache* cache *pool* pool]
        (try
          (f)
          (finally
            (ports/clear-namespace! cache "test")
            (redis-adapter/close-redis-pool! pool)))))
    (f)))

(use-fixtures :each with-redis-cache)

(defmacro when-redis [& body]
  `(if (redis-available?)
     (do ~@body)
     (is (not (redis-available?)) "Redis not available — test skipped")))

;; =============================================================================
;; Basic cache operations
;; =============================================================================

(deftest ^:integration redis-get-set-test
  (when-redis
   (testing "set and get a value"
     (ports/set-value! *cache* "greeting" {:message "hello"})
     (is (= {:message "hello"} (ports/get-value *cache* "greeting"))))

   (testing "get returns nil for missing key"
     (is (nil? (ports/get-value *cache* "missing-key-xyz"))))))

(deftest ^:integration redis-delete-test
  (when-redis
   (testing "delete removes existing key"
     (ports/set-value! *cache* "to-delete" 42)
     (is (true? (ports/delete-key! *cache* "to-delete")))
     (is (nil? (ports/get-value *cache* "to-delete"))))

   (testing "delete returns false for missing key"
     (is (false? (ports/delete-key! *cache* "never-set-xyz"))))))

(deftest ^:integration redis-exists-test
  (when-redis
   (testing "exists? returns true for present key"
     (ports/set-value! *cache* "existing" "value")
     (is (true? (ports/exists? *cache* "existing"))))

   (testing "exists? returns false for missing key"
     (is (false? (ports/exists? *cache* "absent-xyz"))))))

(deftest ^:integration redis-type-fidelity-test
  ;; Regression for BOU-47: JSON serialization lost java.time and Clojure
  ;; value types (Temporal -> String, keyword -> String, set -> vector),
  ;; which surfaced only against Redis. Nippy must round-trip them intact,
  ;; matching the in-memory adapter.
  (when-redis
   (testing "java.time/Temporal values round-trip as Temporal, not String"
     (let [instant (java.time.Instant/parse "2026-06-02T10:00:00Z")
           local-date (java.time.LocalDate/parse "2026-06-02")
           ldt (java.time.LocalDateTime/parse "2026-06-02T10:00:00")
           value {:created-at instant :on local-date :at ldt}]
       (ports/set-value! *cache* "temporal" value)
       (let [got (ports/get-value *cache* "temporal")]
         (is (= value got))
         (is (instance? java.time.temporal.Temporal (:created-at got)))
         (is (instance? java.time.Instant (:created-at got))))))

   (testing "keyword values and sets keep their Clojure types"
     (ports/set-value! *cache* "clj-types" {:role :admin :tags #{:a :b}})
     (let [got (ports/get-value *cache* "clj-types")]
       (is (= {:role :admin :tags #{:a :b}} got))
       (is (keyword? (:role got)))
       (is (set? (:tags got)))))))

(deftest ^:integration redis-stale-entry-self-heal-test
  ;; Regression for BOU-47 rollout: entries written by the previous JSON
  ;; format (or otherwise non-Nippy bytes) must not throw on read. They are
  ;; treated as a cache miss so the cache self-heals after the format change.
  (when-redis
   (testing "non-Nippy bytes deserialize to a cache miss instead of throwing"
     ;; Write a raw legacy JSON string under the cache's namespaced key,
     ;; bypassing the adapter's Nippy serialization.
     (with-open [^Jedis redis (.getResource *pool*)]
       (.set redis
             (.getBytes "test:legacy-json" StandardCharsets/UTF_8)
             (.getBytes "{\"message\":\"hello\"}" StandardCharsets/UTF_8)))
     (is (nil? (ports/get-value *cache* "legacy-json")))
     ;; A subsequent write through the adapter overwrites it cleanly.
     (ports/set-value! *cache* "legacy-json" {:message "hello"})
     (is (= {:message "hello"} (ports/get-value *cache* "legacy-json"))))))

;; =============================================================================
;; TTL operations
;; =============================================================================

(deftest ^:integration redis-ttl-test
  (when-redis
   (testing "set-value! with TTL stores key with expiration"
     (ports/set-value! *cache* "expiring" "value" 60)
     (let [remaining-ttl (ports/ttl *cache* "expiring")]
       (is (some? remaining-ttl))
       (is (pos? remaining-ttl))))

   (testing "expire! sets TTL on existing key"
     (ports/set-value! *cache* "no-ttl" "value")
     (ports/expire! *cache* "no-ttl" 30)
     (is (some? (ports/ttl *cache* "no-ttl"))))))

;; =============================================================================
;; Batch operations
;; =============================================================================

(deftest ^:integration redis-batch-test
  (when-redis
   (testing "set-many! stores all keys"
     (let [m {"batch1" 1 "batch2" 2 "batch3" 3}]
       (is (= 3 (ports/set-many! *cache* m)))
       (let [got (ports/get-many *cache* ["batch1" "batch2" "batch3"])]
         (is (= 1 (get got "batch1")))
         (is (= 2 (get got "batch2")))
         (is (= 3 (get got "batch3"))))))

   (testing "delete-many! removes all specified keys"
     (ports/set-many! *cache* {"del1" "a" "del2" "b"})
     (let [n (ports/delete-many! *cache* ["del1" "del2"])]
       (is (= 2 n))
       (is (nil? (ports/get-value *cache* "del1")))
       (is (nil? (ports/get-value *cache* "del2")))))

   (testing "get-many omits missing keys"
     (ports/set-value! *cache* "exists-a" 1)
     (let [result (ports/get-many *cache* ["exists-a" "missing-b"])]
       (is (= {"exists-a" 1} result))))))

;; =============================================================================
;; Atomic operations
;; =============================================================================

(deftest ^:integration redis-atomic-test
  (when-redis
   (testing "increment! increases value"
     (ports/set-value! *cache* "counter" 0)
     (let [v (ports/increment! *cache* "counter")]
       (is (= 1 v))))

   (testing "decrement! decreases value"
     (ports/set-value! *cache* "countdown" 10)
     (let [v (ports/decrement! *cache* "countdown")]
       (is (= 9 v))))

   (testing "set-if-absent! sets only when key is missing"
     (is (true? (ports/set-if-absent! *cache* "new-key" "first")))
     (is (false? (ports/set-if-absent! *cache* "new-key" "second")))
     (is (= "first" (ports/get-value *cache* "new-key"))))

   (testing "increment! on a missing key starts at zero"
     (is (= 5 (ports/increment! *cache* "fresh-counter" 5))))

   (testing "set-if-absent! seeds an integer counter that increment! advances (rate-limiter pattern)"
     ;; Regression for the nippy/INCR mismatch: set-if-absent! stored the seed
     ;; Nippy-encoded while increment! issued a raw INCR, so the second hit threw
     ;; "ERR value is not an integer or out of range".
     (is (true? (ports/set-if-absent! *cache* "rl-counter" 1 60)))
     (is (= 2 (ports/increment! *cache* "rl-counter")))
     (is (= 3 (ports/increment! *cache* "rl-counter")))
     (is (= 3 (ports/get-value *cache* "rl-counter")) "counter round-trips as a number")
     (is (some? (ports/ttl *cache* "rl-counter")) "increment! preserves the seeded TTL"))

   (testing "compare-and-swap! updates only when expected value matches"
     (ports/set-value! *cache* "cas-key" {:state :old})
     (is (true? (ports/compare-and-swap! *cache* "cas-key" {:state :old} {:state :new})))
     (is (= {:state :new} (ports/get-value *cache* "cas-key")))
     (is (false? (ports/compare-and-swap! *cache* "cas-key" {:state :old} {:state :newer})))
     (is (= {:state :new} (ports/get-value *cache* "cas-key"))))))

;; =============================================================================
;; Namespace operations
;; =============================================================================

(deftest ^:integration redis-namespace-test
  (when-redis
   (testing "with-namespace creates isolated namespace"
     (let [ns-cache (ports/with-namespace *cache* "users")]
       (ports/set-value! ns-cache "alice" {:role :admin})
       (is (= {:role :admin} (ports/get-value ns-cache "alice")))
        ;; key should not be visible in base namespace
       (is (nil? (ports/get-value *cache* "alice")))))

   (testing "clear-namespace! removes all keys in namespace"
     (let [ns-cache (ports/with-namespace *cache* "sessions")]
       (ports/set-many! ns-cache {"s1" "data" "s2" "data"})
       (ports/clear-namespace! *cache* "sessions")
       (is (nil? (ports/get-value ns-cache "s1")))
       (is (nil? (ports/get-value ns-cache "s2")))))))

;; =============================================================================
;; Ping and stats
;; =============================================================================

(deftest ^:integration redis-ping-test
  (when-redis
   (testing "ping returns true when Redis is available"
     (is (true? (ports/ping *cache*))))))

(deftest ^:integration redis-cache-stats-test
  (when-redis
   (testing "cache-stats returns a stats map"
     (let [stats (ports/cache-stats *cache*)]
       (is (map? stats))
       (is (contains? stats :hits))
       (is (contains? stats :misses))))))
