(ns wagoe.cache.shell.tenant-cache-test
  "Tests for tenant-aware caching with automatic key prefixing."
  (:require [wagoe.cache.ports :as ports]
            [wagoe.cache.shell.adapters.in-memory :as mem-cache]
            [wagoe.cache.shell.tenant-cache :as tenant-cache]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- create-test-cache
  "Create in-memory cache for testing."
  []
  (mem-cache/create-in-memory-cache {:track-stats? true}))

;; =============================================================================
;; Unit Tests - Key Prefixing
;; =============================================================================

(deftest ^:unit tenant-cache-key-test
  (testing "Prefixing keys with tenant ID"
    (is (= "tenant:acme:user-123"
           (tenant-cache/tenant-cache-key "acme" :user-123)))

    (is (= "tenant:globex:session:abc"
           (tenant-cache/tenant-cache-key "globex" "session:abc")))

    (is (= "tenant:123:key"
           (tenant-cache/tenant-cache-key "123" :key)))))

;; =============================================================================
;; Integration Tests - Basic Operations
;; =============================================================================

(deftest ^:integration basic-operations-test
  (testing "Get/Set/Delete with tenant isolation"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Set values for different tenants
      (ports/set-value! tenant-a :user-123 {:name "Alice" :tenant "A"})
      (ports/set-value! tenant-b :user-123 {:name "Bob" :tenant "B"})

      ;; Values are isolated
      (is (= {:name "Alice" :tenant "A"}
             (ports/get-value tenant-a :user-123)))

      (is (= {:name "Bob" :tenant "B"}
             (ports/get-value tenant-b :user-123)))

      ;; Delete from one tenant doesn't affect other
      (ports/delete-key! tenant-a :user-123)

      (is (nil? (ports/get-value tenant-a :user-123)))
      (is (= {:name "Bob" :tenant "B"}
             (ports/get-value tenant-b :user-123)))))

  (testing "Exists and TTL operations"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Set value with TTL
      (ports/set-value! tenant-cache :session-abc "token-xyz" 3600)

      ;; Check existence
      (is (true? (ports/exists? tenant-cache :session-abc)))
      (is (false? (ports/exists? tenant-cache :nonexistent)))

      ;; Check TTL
      (let [ttl (ports/ttl tenant-cache :session-abc)]
        (is (some? ttl))
        (is (>= ttl 3500))
        (is (<= ttl 3600)))

      ;; Update TTL
      (ports/expire! tenant-cache :session-abc 7200)
      (let [new-ttl (ports/ttl tenant-cache :session-abc)]
        (is (>= new-ttl 7100))
        (is (<= new-ttl 7200))))))

;; =============================================================================
;; Integration Tests - Batch Operations
;; =============================================================================

(deftest ^:integration batch-operations-test
  (testing "Set/Get many with tenant isolation"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Set multiple values
      (ports/set-many! tenant-a
                       {:user-1 "Alice"
                        :user-2 "Bob"
                        :user-3 "Charlie"})

      (ports/set-many! tenant-b
                       {:user-1 "David"
                        :user-2 "Eve"})

      ;; Get many from tenant A
      (let [results (ports/get-many tenant-a [:user-1 :user-2 :user-3])]
        (is (= "Alice" (:user-1 results)))
        (is (= "Bob" (:user-2 results)))
        (is (= "Charlie" (:user-3 results))))

      ;; Get many from tenant B
      (let [results (ports/get-many tenant-b [:user-1 :user-2])]
        (is (= "David" (:user-1 results)))
        (is (= "Eve" (:user-2 results))))

      ;; Delete many from tenant A
      (let [deleted (ports/delete-many! tenant-a [:user-1 :user-2])]
        (is (= 2 deleted)))

      ;; Verify deletion (tenant A)
      (is (nil? (ports/get-value tenant-a :user-1)))
      (is (nil? (ports/get-value tenant-a :user-2)))
      (is (= "Charlie" (ports/get-value tenant-a :user-3)))

      ;; Tenant B unaffected
      (is (= "David" (ports/get-value tenant-b :user-1)))
      (is (= "Eve" (ports/get-value tenant-b :user-2)))))

  (testing "Set many with TTL"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Set multiple with TTL
      (ports/set-many! tenant-cache
                       {:session-a "token-a"
                        :session-b "token-b"}
                       3600)

      ;; Verify values set
      (is (= "token-a" (ports/get-value tenant-cache :session-a)))
      (is (= "token-b" (ports/get-value tenant-cache :session-b)))

      ;; Verify TTL
      (let [ttl-a (ports/ttl tenant-cache :session-a)]
        (is (>= ttl-a 3500))
        (is (<= ttl-a 3600))))))

;; =============================================================================
;; Integration Tests - Atomic Operations
;; =============================================================================

(deftest ^:integration atomic-operations-test
  (testing "Increment/Decrement with tenant isolation"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Increment counters
      (is (= 1 (ports/increment! tenant-a :page-views)))
      (is (= 2 (ports/increment! tenant-a :page-views)))
      (is (= 12 (ports/increment! tenant-a :page-views 10)))

      (is (= 1 (ports/increment! tenant-b :page-views)))
      (is (= 6 (ports/increment! tenant-b :page-views 5)))

      ;; Counters are isolated
      (is (= 12 (ports/get-value tenant-a :page-views)))
      (is (= 6 (ports/get-value tenant-b :page-views)))

      ;; Decrement
      (is (= 11 (ports/decrement! tenant-a :page-views)))
      (is (= 6 (ports/decrement! tenant-a :page-views 5)))

      (is (= 6 (ports/get-value tenant-a :page-views)))))

  (testing "Set if absent with tenant isolation"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Set if absent succeeds for both tenants (different namespaces)
      (is (true? (ports/set-if-absent! tenant-a :lock:resource "worker-a")))
      (is (true? (ports/set-if-absent! tenant-b :lock:resource "worker-b")))

      ;; Second attempt fails (key exists)
      (is (false? (ports/set-if-absent! tenant-a :lock:resource "worker-x")))
      (is (false? (ports/set-if-absent! tenant-b :lock:resource "worker-y")))

      ;; Values remain unchanged
      (is (= "worker-a" (ports/get-value tenant-a :lock:resource)))
      (is (= "worker-b" (ports/get-value tenant-b :lock:resource)))))

  (testing "Compare and swap"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Set initial value
      (ports/set-value! tenant-cache :inventory 100)

      ;; CAS succeeds with correct value
      (is (true? (ports/compare-and-swap! tenant-cache :inventory 100 95)))
      (is (= 95 (ports/get-value tenant-cache :inventory)))

      ;; CAS fails with wrong value
      (is (false? (ports/compare-and-swap! tenant-cache :inventory 100 90)))
      (is (= 95 (ports/get-value tenant-cache :inventory))))))

;; =============================================================================
;; Integration Tests - Pattern Operations
;; =============================================================================

(deftest ^:integration pattern-operations-test
  (testing "Keys matching pattern with tenant isolation"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Set values with common patterns
      (ports/set-value! tenant-a :user:1 "Alice")
      (ports/set-value! tenant-a :user:2 "Bob")
      (ports/set-value! tenant-a :session:abc "token-a")

      (ports/set-value! tenant-b :user:1 "Charlie")
      (ports/set-value! tenant-b :user:3 "David")

      ;; Find keys matching pattern (tenant A)
      (let [keys (ports/keys-matching tenant-a "user:*")]
        (is (= 2 (count keys)))
        (is (contains? keys :user:1))
        (is (contains? keys :user:2)))

      ;; Find keys matching pattern (tenant B)
      (let [keys (ports/keys-matching tenant-b "user:*")]
        (is (= 2 (count keys)))
        (is (contains? keys :user:1))
        (is (contains? keys :user:3)))

      ;; Count matching keys
      (is (= 2 (ports/count-matching tenant-a "user:*")))
      (is (= 1 (ports/count-matching tenant-a "session:*")))
      (is (= 2 (ports/count-matching tenant-b "user:*")))

      ;; Delete matching keys (tenant A only)
      (let [deleted (ports/delete-matching! tenant-a "user:*")]
        (is (= 2 deleted)))

      ;; Verify deletion (tenant A)
      (is (= 0 (ports/count-matching tenant-a "user:*")))
      (is (= 1 (ports/count-matching tenant-a "session:*")))

      ;; Tenant B unaffected
      (is (= 2 (ports/count-matching tenant-b "user:*")))))

  (testing "Pattern matching with wildcards"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Set values with various patterns
      (ports/set-value! tenant-cache :user:admin:1 "Admin1")
      (ports/set-value! tenant-cache :user:admin:2 "Admin2")
      (ports/set-value! tenant-cache :user:guest:1 "Guest1")
      (ports/set-value! tenant-cache :product:widget "Widget")

      ;; Match all users
      (is (= 3 (ports/count-matching tenant-cache "user:*")))

      ;; Match admin users only
      (is (= 2 (ports/count-matching tenant-cache "user:admin:*")))

      ;; Match all keys
      (is (= 4 (ports/count-matching tenant-cache "*"))))))

;; =============================================================================
;; Integration Tests - Namespace Operations
;; =============================================================================

(deftest ^:integration namespace-operations-test
  (testing "Nested namespaces with tenant prefix"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")
          user-cache (ports/with-namespace tenant-cache "users")
          product-cache (ports/with-namespace tenant-cache "products")]

      ;; Set values in different namespaces
      (ports/set-value! user-cache :123 {:name "Alice"})
      (ports/set-value! product-cache :123 {:name "Widget"})

      ;; Values are isolated by namespace
      (is (= {:name "Alice"} (ports/get-value user-cache :123)))
      (is (= {:name "Widget"} (ports/get-value product-cache :123)))

      ;; Clear users namespace
      (let [deleted (ports/clear-namespace! tenant-cache "users")]
        (is (= 1 deleted)))

      ;; User cache cleared, product cache intact
      (is (nil? (ports/get-value user-cache :123)))
      (is (= {:name "Widget"} (ports/get-value product-cache :123)))))

  (testing "Namespace isolation between tenants"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")
          users-a (ports/with-namespace tenant-a "users")
          users-b (ports/with-namespace tenant-b "users")]

      ;; Set values in same namespace, different tenants
      (ports/set-value! users-a :123 "Alice")
      (ports/set-value! users-b :123 "Bob")

      ;; Values isolated by tenant
      (is (= "Alice" (ports/get-value users-a :123)))
      (is (= "Bob" (ports/get-value users-b :123)))

      ;; Clear namespace in tenant A
      (ports/clear-namespace! tenant-a "users")

      ;; Tenant A cleared, tenant B intact
      (is (nil? (ports/get-value users-a :123)))
      (is (= "Bob" (ports/get-value users-b :123))))))

;; =============================================================================
;; Integration Tests - Flush Operations
;; =============================================================================

(deftest ^:integration flush-operations-test
  (testing "Flush all only deletes tenant's keys"
    (let [base-cache (create-test-cache)
          tenant-a (tenant-cache/create-tenant-cache base-cache "tenant-a")
          tenant-b (tenant-cache/create-tenant-cache base-cache "tenant-b")]

      ;; Set values for both tenants
      (ports/set-value! tenant-a :key1 "A1")
      (ports/set-value! tenant-a :key2 "A2")
      (ports/set-value! tenant-b :key1 "B1")
      (ports/set-value! tenant-b :key2 "B2")

      ;; Flush tenant A
      (let [deleted (ports/flush-all! tenant-a)]
        (is (= 2 deleted)))

      ;; Tenant A keys deleted
      (is (nil? (ports/get-value tenant-a :key1)))
      (is (nil? (ports/get-value tenant-a :key2)))

      ;; Tenant B keys intact
      (is (= "B1" (ports/get-value tenant-b :key1)))
      (is (= "B2" (ports/get-value tenant-b :key2))))))

;; =============================================================================
;; Integration Tests - Extract from Request
;; =============================================================================

(deftest ^:integration extract-tenant-cache-test
  (testing "Extract tenant cache from request with tenant ID in :tenant map"
    (let [base-cache (create-test-cache)
          request {:tenant {:id "acme" :slug "acme-corp"}}
          tenant-cache (tenant-cache/extract-tenant-cache base-cache request)]

      ;; Set value via tenant cache
      (ports/set-value! tenant-cache :user-123 {:name "Alice"})

      ;; Verify isolation
      (let [other-request {:tenant {:id "globex"}}
            other-cache (tenant-cache/extract-tenant-cache base-cache other-request)]
        (is (nil? (ports/get-value other-cache :user-123))))))

  (testing "Extract tenant cache from request with :tenant-id key"
    (let [base-cache (create-test-cache)
          request {:tenant-id "acme"}
          tenant-cache (tenant-cache/extract-tenant-cache base-cache request)]

      ;; Set value
      (ports/set-value! tenant-cache :session-abc "token-xyz")

      ;; Verify value set with tenant prefix
      (is (= "token-xyz" (ports/get-value tenant-cache :session-abc)))))

  (testing "Extract returns base cache when no tenant context"
    (let [base-cache (create-test-cache)
          request {:user-id "123"}
          result-cache (tenant-cache/extract-tenant-cache base-cache request)]

      ;; Returns base cache (not wrapped)
      (is (= base-cache result-cache)))))

;; =============================================================================
;; Integration Tests - Cache Management
;; =============================================================================

(deftest ^:integration cache-management-test
  (testing "Ping operation delegates to base cache"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      (is (true? (ports/ping tenant-cache)))))

  (testing "Close does not close underlying cache"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Close tenant cache
      (ports/close! tenant-cache)

      ;; Base cache still works
      (is (true? (ports/ping base-cache)))

      ;; Can still use tenant cache
      (ports/set-value! tenant-cache :key "value")
      (is (= "value" (ports/get-value tenant-cache :key))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest ^:integration edge-cases-test
  (testing "Empty tenant ID validation"
    (let [base-cache (create-test-cache)]
      (is (thrown? AssertionError
                   (tenant-cache/create-tenant-cache base-cache "")))

      (is (thrown? AssertionError
                   (tenant-cache/create-tenant-cache base-cache nil)))))

  (testing "Special characters in keys"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")]

      ;; Keys with special chars
      (ports/set-value! tenant-cache "user:email@example.com" "data")
      (is (= "data" (ports/get-value tenant-cache "user:email@example.com")))))

  (testing "Large batch operations"
    (let [base-cache (create-test-cache)
          tenant-cache (tenant-cache/create-tenant-cache base-cache "acme")
          large-map (into {} (for [i (range 1000)]
                              [(keyword (str "key-" i)) (str "value-" i)]))]

      ;; Set 1000 keys
      (let [set-count (ports/set-many! tenant-cache large-map)]
        (is (= 1000 set-count)))

      ;; Get all 1000 keys
      (let [results (ports/get-many tenant-cache (keys large-map))]
        (is (= 1000 (count results)))
        (is (= "value-500" (:key-500 results)))))))
