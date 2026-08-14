(ns wagoe.cache.shell.adapters.in-memory-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.cache.ports :as ports]
            [wagoe.cache.shell.adapters.in-memory :as in-mem]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *cache* nil)

(defn cache-fixture [f]
  (binding [*cache* (in-mem/create-in-memory-cache)]
    (f)
    (in-mem/clear-all! *cache*)))

(use-fixtures :each cache-fixture)

;; =============================================================================
;; Basic Cache Operations
;; =============================================================================

(deftest ^:unit get-set-test
  (testing "Basic get/set operations"
    (is (nil? (ports/get-value *cache* :key1)))
    (is (true? (ports/set-value! *cache* :key1 "value1")))
    (is (= "value1" (ports/get-value *cache* :key1)))))

(deftest ^:unit get-set-different-types-test
  (testing "Store different value types"
    (ports/set-value! *cache* :string "hello")
    (ports/set-value! *cache* :number 42)
    (ports/set-value! *cache* :map {:a 1 :b 2})
    (ports/set-value! *cache* :vector [1 2 3])
    (ports/set-value! *cache* :boolean true)

    (is (= "hello" (ports/get-value *cache* :string)))
    (is (= 42 (ports/get-value *cache* :number)))
    (is (= {:a 1 :b 2} (ports/get-value *cache* :map)))
    (is (= [1 2 3] (ports/get-value *cache* :vector)))
    (is (= true (ports/get-value *cache* :boolean)))))

(deftest ^:unit delete-key-test
  (testing "Delete key"
    (ports/set-value! *cache* :key1 "value1")
    (is (true? (ports/delete-key! *cache* :key1)))
    (is (nil? (ports/get-value *cache* :key1)))
    (is (false? (ports/delete-key! *cache* :key1)))))

(deftest ^:unit exists-test
  (testing "Check key existence"
    (is (false? (ports/exists? *cache* :key1)))
    (ports/set-value! *cache* :key1 "value1")
    (is (true? (ports/exists? *cache* :key1)))
    (ports/delete-key! *cache* :key1)
    (is (false? (ports/exists? *cache* :key1)))))

;; =============================================================================
;; TTL Tests
;; =============================================================================

(deftest ^:unit ttl-test
  (testing "Set value with TTL"
    (ports/set-value! *cache* :key1 "value1" 10)
    (is (= "value1" (ports/get-value *cache* :key1)))
    (let [ttl (ports/ttl *cache* :key1)]
      (is (and (>= ttl 9) (<= ttl 10))))))

(deftest ^:unit expire-test
  (testing "Set expiration on existing key"
    (ports/set-value! *cache* :key1 "value1")
    (is (nil? (ports/ttl *cache* :key1)))
    (is (true? (ports/expire! *cache* :key1 5)))
    (let [ttl (ports/ttl *cache* :key1)]
      (is (and (>= ttl 4) (<= ttl 5))))))

(deftest ^:unit expiration-test
  (testing "Value expires after TTL"
    (ports/set-value! *cache* :key1 "value1" 1)
    (is (= "value1" (ports/get-value *cache* :key1)))
    (Thread/sleep 1100)  ; Wait for expiration
    (is (nil? (ports/get-value *cache* :key1)))
    (is (false? (ports/exists? *cache* :key1)))))

(deftest ^:unit default-ttl-test
  (testing "Use default TTL from config"
    (let [cache (in-mem/create-in-memory-cache {:default-ttl 10})]
      (ports/set-value! cache :key1 "value1")
      (let [ttl (ports/ttl cache :key1)]
        (is (and (>= ttl 9) (<= ttl 10)))))))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(deftest ^:unit get-many-test
  (testing "Get multiple keys at once"
    (ports/set-value! *cache* :key1 "value1")
    (ports/set-value! *cache* :key2 "value2")
    (ports/set-value! *cache* :key3 "value3")

    (let [result (ports/get-many *cache* [:key1 :key2 :key3 :key4])]
      (is (= {:key1 "value1" :key2 "value2" :key3 "value3"} result)))))

(deftest ^:unit set-many-test
  (testing "Set multiple keys at once"
    (let [kvs {:key1 "value1" :key2 "value2" :key3 "value3"}
          count (ports/set-many! *cache* kvs)]
      (is (= 3 count))
      (is (= "value1" (ports/get-value *cache* :key1)))
      (is (= "value2" (ports/get-value *cache* :key2)))
      (is (= "value3" (ports/get-value *cache* :key3))))))

(deftest ^:unit set-many-with-ttl-test
  (testing "Set multiple keys with TTL"
    (let [kvs {:key1 "value1" :key2 "value2"}
          count (ports/set-many! *cache* kvs 10)]
      (is (= 2 count))
      (let [ttl1 (ports/ttl *cache* :key1)
            ttl2 (ports/ttl *cache* :key2)]
        (is (and (>= ttl1 9) (<= ttl1 10)))
        (is (and (>= ttl2 9) (<= ttl2 10)))))))

(deftest ^:unit delete-many-test
  (testing "Delete multiple keys at once"
    (ports/set-value! *cache* :key1 "value1")
    (ports/set-value! *cache* :key2 "value2")
    (ports/set-value! *cache* :key3 "value3")

    (let [count (ports/delete-many! *cache* [:key1 :key2 :key4])]
      (is (= 2 count))
      (is (nil? (ports/get-value *cache* :key1)))
      (is (nil? (ports/get-value *cache* :key2)))
      (is (= "value3" (ports/get-value *cache* :key3))))))

;; =============================================================================
;; Atomic Operations
;; =============================================================================

(deftest ^:unit increment-test
  (testing "Increment numeric value"
    (is (= 1 (ports/increment! *cache* :counter)))
    (is (= 2 (ports/increment! *cache* :counter)))
    (is (= 7 (ports/increment! *cache* :counter 5)))
    (is (= 7 (ports/get-value *cache* :counter)))))

(deftest ^:unit decrement-test
  (testing "Decrement numeric value"
    (ports/set-value! *cache* :counter 10)
    (is (= 9 (ports/decrement! *cache* :counter)))
    (is (= 8 (ports/decrement! *cache* :counter)))
    (is (= 5 (ports/decrement! *cache* :counter 3)))
    (is (= 5 (ports/get-value *cache* :counter)))))

(deftest ^:unit set-if-absent-test
  (testing "Set only if key doesn't exist"
    (is (true? (ports/set-if-absent! *cache* :key1 "value1")))
    (is (= "value1" (ports/get-value *cache* :key1)))
    (is (false? (ports/set-if-absent! *cache* :key1 "value2")))
    (is (= "value1" (ports/get-value *cache* :key1)))))

(deftest ^:unit compare-and-swap-test
  (testing "Compare and swap atomic operation"
    (ports/set-value! *cache* :key1 "old-value")
    (is (true? (ports/compare-and-swap! *cache* :key1 "old-value" "new-value")))
    (is (= "new-value" (ports/get-value *cache* :key1)))
    (is (false? (ports/compare-and-swap! *cache* :key1 "old-value" "newer-value")))
    (is (= "new-value" (ports/get-value *cache* :key1)))))

;; =============================================================================
;; Pattern Operations
;; =============================================================================

(deftest ^:unit keys-matching-test
  (testing "Get keys matching pattern"
    (ports/set-value! *cache* :user:1 "Alice")
    (ports/set-value! *cache* :user:2 "Bob")
    (ports/set-value! *cache* :user:3 "Charlie")
    (ports/set-value! *cache* :session:1 "abc123")

    (let [user-keys (ports/keys-matching *cache* "user:*")]
      (is (= 3 (count user-keys)))
      (is (contains? user-keys "user:1"))
      (is (contains? user-keys "user:2"))
      (is (contains? user-keys "user:3")))))

(deftest ^:unit delete-matching-test
  (testing "Delete keys matching pattern"
    (ports/set-value! *cache* :user:1 "Alice")
    (ports/set-value! *cache* :user:2 "Bob")
    (ports/set-value! *cache* :session:1 "abc123")

    (let [count (ports/delete-matching! *cache* "user:*")]
      (is (= 2 count))
      (is (nil? (ports/get-value *cache* :user:1)))
      (is (nil? (ports/get-value *cache* :user:2)))
      (is (= "abc123" (ports/get-value *cache* :session:1))))))

(deftest ^:unit count-matching-test
  (testing "Count keys matching pattern"
    (ports/set-value! *cache* :product:1 "Widget")
    (ports/set-value! *cache* :product:2 "Gadget")
    (ports/set-value! *cache* :product:3 "Gizmo")

    (is (= 3 (ports/count-matching *cache* "product:*")))))

;; =============================================================================
;; Namespace Operations
;; =============================================================================

(deftest ^:unit with-namespace-test
  (testing "Use namespaced cache view"
    (let [user-cache (ports/with-namespace *cache* "user")
          session-cache (ports/with-namespace *cache* "session")]

      (ports/set-value! user-cache :123 {:name "Alice"})
      (ports/set-value! session-cache :123 {:token "abc"})

      (is (= {:name "Alice"} (ports/get-value user-cache :123)))
      (is (= {:token "abc"} (ports/get-value session-cache :123))))))

(deftest ^:unit clear-namespace-test
  (testing "Clear all keys in a namespace"
    (let [user-cache (ports/with-namespace *cache* "user")]
      (ports/set-value! user-cache :1 "Alice")
      (ports/set-value! user-cache :2 "Bob")
      (ports/set-value! *cache* :other "value")

      (let [count (ports/clear-namespace! *cache* "user")]
        (is (= 2 count))
        (is (nil? (ports/get-value user-cache :1)))
        (is (nil? (ports/get-value user-cache :2)))
        (is (= "value" (ports/get-value *cache* :other)))))))

;; =============================================================================
;; Statistics Tests
;; =============================================================================

(deftest ^:unit cache-stats-test
  (testing "Track cache statistics"
    (let [cache (in-mem/create-in-memory-cache {:track-stats? true})]
      (ports/set-value! cache :key1 "value1")
      (ports/set-value! cache :key2 "value2")

      ;; Generate hits and misses
      (ports/get-value cache :key1)  ; hit
      (ports/get-value cache :key1)  ; hit
      (ports/get-value cache :key3)  ; miss
      (ports/get-value cache :key4)  ; miss

      (let [stats (ports/cache-stats cache)]
        (is (= 2 (:size stats)))
        (is (= 2 (:hits stats)))
        (is (= 2 (:misses stats)))
        (is (= 0.5 (:hit-rate stats)))))))

(deftest ^:unit clear-stats-test
  (testing "Clear cache statistics"
    (let [cache (in-mem/create-in-memory-cache {:track-stats? true})]
      (ports/set-value! cache :key1 "value1")
      (ports/get-value cache :key1)  ; hit
      (ports/get-value cache :key2)  ; miss

      (ports/clear-stats! cache)

      (let [stats (ports/cache-stats cache)]
        (is (= 0 (:hits stats)))
        (is (= 0 (:misses stats)))))))

;; =============================================================================
;; Cache Management Tests
;; =============================================================================

(deftest ^:unit flush-all-test
  (testing "Flush entire cache"
    (ports/set-value! *cache* :key1 "value1")
    (ports/set-value! *cache* :key2 "value2")

    (let [count (ports/flush-all! *cache*)]
      (is (= 2 count))
      (is (nil? (ports/get-value *cache* :key1)))
      (is (nil? (ports/get-value *cache* :key2))))))

(deftest ^:unit ping-test
  (testing "Check cache health"
    (is (true? (ports/ping *cache*)))))

(deftest ^:unit close-test
  (testing "Close cache connection"
    (is (true? (ports/close! *cache*)))))

;; =============================================================================
;; Eviction Tests
;; =============================================================================

(deftest ^:unit lru-eviction-test
  (testing "LRU eviction when max-size is reached"
    (let [cache (in-mem/create-in-memory-cache {:max-size 3})]
      ;; Fill cache
      (ports/set-value! cache :key1 "value1")
      (ports/set-value! cache :key2 "value2")
      (ports/set-value! cache :key3 "value3")

      ;; Access key1 to make it recently used
      (ports/get-value cache :key1)

      ;; Add key4, should evict key2 (least recently used)
      (ports/set-value! cache :key4 "value4")

      (is (= "value1" (ports/get-value cache :key1)))
      (is (nil? (ports/get-value cache :key2)))  ; Evicted
      (is (= "value3" (ports/get-value cache :key3)))
      (is (= "value4" (ports/get-value cache :key4))))))

;; =============================================================================
;; Concurrency Tests
;; =============================================================================

(deftest ^:unit concurrent-increment-test
  (testing "Concurrent increments are atomic"
    (let [cache (in-mem/create-in-memory-cache)
          futures (doall
                   (for [_ (range 100)]
                     (future (ports/increment! cache :counter))))]
      ;; Wait for all futures to complete
      (doseq [f futures] @f)

      (is (= 100 (ports/get-value cache :counter))))))

(deftest ^:unit concurrent-set-get-test
  (testing "Concurrent set/get operations"
    (let [cache (in-mem/create-in-memory-cache)
          futures (doall
                   (for [i (range 50)]
                     (future
                       (ports/set-value! cache (keyword (str "key" i)) i))))]
      ;; Wait for all futures to complete
      (doseq [f futures] @f)

      (is (= 50 (count (in-mem/get-all-entries cache))))

      ;; Verify all values
      (doseq [i (range 50)]
        (is (= i (ports/get-value cache (keyword (str "key" i)))))))))

(deftest ^:unit incrementing-keeps-the-expiry
  ;; Redis INCR preserves the TTL, and callers rely on that: both the rate
  ;; limiter and the RPC circuit breaker set an expiry once, on the first
  ;; increment, because that is the only moment they can recognise. An adapter
  ;; that clears it on the second increment means the key never expires — so
  ;; rate-limit windows and failure counts accumulate for the life of the
  ;; process, and the breaker eventually opens on failures that were never
  ;; consecutive.
  (let [cache (in-mem/create-in-memory-cache {})]
    (is (= 1 (ports/increment! cache "counter")))
    (ports/expire! cache "counter" 60)
    (is (some? (ports/ttl cache "counter")))

    (testing "a later increment does not clear it"
      (ports/increment! cache "counter")
      (ports/increment! cache "counter")
      (is (some? (ports/ttl cache "counter"))
          "the TTL was lost, so this key will never expire")
      (is (pos? (ports/ttl cache "counter"))))

    (testing "and the value is still counted"
      (is (= 3 (ports/get-value cache "counter"))))))

(deftest ^:unit an-expired-key-is-absent-for-set-if-absent
  ;; SETNX semantics: callers use this as a lease, and a lease that cannot be
  ;; retaken once it expires is a lock that leaks. Redis honours the TTL here;
  ;; checking only for the key's presence did not, so a holder that died never
  ;; released it.
  (let [cache (in-mem/create-in-memory-cache {})]
    (is (true? (ports/set-if-absent! cache "lease" {:holder :a} 1)))
    (is (false? (ports/set-if-absent! cache "lease" {:holder :b} 1))
        "the lease was taken twice while still held")

    (testing "and once it expires another holder can take it"
      (Thread/sleep 1100)
      (is (true? (ports/set-if-absent! cache "lease" {:holder :c} 1))
          "an expired lease was never released")
      (is (= {:holder :c} (ports/get-value cache "lease"))))))

(deftest ^:unit incrementing-an-expired-counter-starts-again
  ;; There is no background sweep, so an expired entry sits in the map until
  ;; something reads it. Incrementing one must behave as Redis does — the key
  ;; is gone, so the count starts from zero and carries no expiry — rather than
  ;; continuing a lapsed count or returning a value that is already expired.
  (let [cache (in-mem/create-in-memory-cache {})]
    (ports/increment! cache "counter")
    (ports/increment! cache "counter")
    (ports/expire! cache "counter" 1)
    (is (= 2 (ports/get-value cache "counter")))

    (Thread/sleep 1100)

    (testing "the count starts again rather than continuing"
      (is (= 1 (ports/increment! cache "counter"))
          "a lapsed count carried on from its old value"))

    (testing "and the value it returns is usable, not already expired"
      (is (= 1 (ports/get-value cache "counter"))
          "increment! returned a value that get-value then discarded"))

    (testing "with no expiry carried from the entry that lapsed"
      (is (nil? (ports/ttl cache "counter"))))))
