(ns wagoe.realtime.shell.connection-registry-test
  "Integration tests for connection registry (shell layer).
   
   Tests registry state management - verifies connection storage,
   retrieval, and filtering operations."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.core.connection :as conn]
            [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-user-id-1 #uuid "550e8400-e29b-41d4-a716-446655440000")
(def test-user-id-2 #uuid "660e8400-e29b-41d4-a716-446655440001")
(def test-connection-id-1 #uuid "770e8400-e29b-41d4-a716-446655440002")
(def test-connection-id-2 #uuid "880e8400-e29b-41d4-a716-446655440003")
(def test-now (java.time.Instant/parse "2026-02-04T20:00:00Z"))

(defn create-test-connection
  "Create test connection record."
  [user-id roles]
  (conn/create-connection user-id roles {} (java.util.UUID/randomUUID) test-now))

(defn create-test-ws-adapter
  "Create test WebSocket adapter."
  [connection-id]
  (ws/create-test-websocket-adapter connection-id))

;; =============================================================================
;; Register/Unregister Tests
;; =============================================================================

(deftest ^:unit register-connection-test
  (testing "registering a connection"
    (let [reg (registry/create-in-memory-registry)
          connection (create-test-connection test-user-id-1 #{:user})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)

      (testing "increases connection count"
        (is (= 1 (ports/connection-count reg))))

      (testing "connection can be retrieved"
        (let [retrieved (ports/find-connection reg test-connection-id-1)]
          (is (some? retrieved))
          (is (= test-user-id-1 (:user-id retrieved)))
          (is (= #{:user} (:roles retrieved))))))))

(deftest ^:unit register-multiple-connections-test
  (testing "registering multiple connections"
    (let [reg (registry/create-in-memory-registry)
          connection-1 (create-test-connection test-user-id-1 #{:user})
          connection-2 (create-test-connection test-user-id-2 #{:admin})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
      (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)

      (testing "both connections registered"
        (is (= 2 (ports/connection-count reg)))
        (is (some? (ports/find-connection reg test-connection-id-1)))
        (is (some? (ports/find-connection reg test-connection-id-2)))))))

(deftest ^:unit unregister-connection-test
  (testing "unregistering a connection"
    (let [reg (registry/create-in-memory-registry)
          connection (create-test-connection test-user-id-1 #{:user})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)
      (is (= 1 (ports/connection-count reg)))

      (ports/unregister reg test-connection-id-1)

      (testing "decreases connection count"
        (is (= 0 (ports/connection-count reg))))

      (testing "connection cannot be retrieved"
        (is (nil? (ports/find-connection reg test-connection-id-1)))))))

(deftest ^:unit unregister-nonexistent-connection-test
  (testing "unregistering non-existent connection does not throw"
    (let [reg (registry/create-in-memory-registry)]

      (testing "gracefully handles missing connection"
        (is (nil? (ports/unregister reg #uuid "00000000-0000-0000-0000-000000000000")))
        (is (= 0 (ports/connection-count reg)))))))

;; =============================================================================
;; Find by User Tests
;; =============================================================================

(deftest ^:unit find-by-user-single-connection-test
  (testing "finding connections for user with single connection"
    (let [reg (registry/create-in-memory-registry)
          connection (create-test-connection test-user-id-1 #{:user})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)

      (let [ws-adapters (ports/find-by-user reg test-user-id-1)]

        (testing "returns vector with one adapter"
          (is (vector? ws-adapters))
          (is (= 1 (count ws-adapters)))
          (is (= test-connection-id-1 (ports/connection-id (first ws-adapters)))))))))

(deftest ^:unit find-by-user-multiple-connections-test
  (testing "finding connections for user with multiple connections"
    (let [reg (registry/create-in-memory-registry)
          ;; Same user, multiple connections
          connection-1 (create-test-connection test-user-id-1 #{:user})
          connection-2 (create-test-connection test-user-id-1 #{:user})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
      (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)

      (let [ws-adapters (ports/find-by-user reg test-user-id-1)]

        (testing "returns all user's connections"
          (is (= 2 (count ws-adapters)))
          (let [connection-ids (set (map ports/connection-id ws-adapters))]
            (is (contains? connection-ids test-connection-id-1))
            (is (contains? connection-ids test-connection-id-2))))))))

(deftest ^:unit find-by-user-no-connections-test
  (testing "finding connections for user with no connections"
    (let [reg (registry/create-in-memory-registry)
          ws-adapters (ports/find-by-user reg test-user-id-1)]

      (testing "returns empty vector"
        (is (vector? ws-adapters))
        (is (= 0 (count ws-adapters)))))))

(deftest ^:unit find-by-user-filters-correctly-test
  (testing "find-by-user returns only specified user's connections"
    (let [reg (registry/create-in-memory-registry)
          connection-1 (create-test-connection test-user-id-1 #{:user})
          connection-2 (create-test-connection test-user-id-2 #{:user})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
      (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)

      (let [user-1-adapters (ports/find-by-user reg test-user-id-1)
            user-2-adapters (ports/find-by-user reg test-user-id-2)]

        (testing "user 1 gets only their connections"
          (is (= 1 (count user-1-adapters)))
          (is (= test-connection-id-1 (ports/connection-id (first user-1-adapters)))))

        (testing "user 2 gets only their connections"
          (is (= 1 (count user-2-adapters)))
          (is (= test-connection-id-2 (ports/connection-id (first user-2-adapters)))))))))

;; =============================================================================
;; Find by Role Tests
;; =============================================================================

(deftest ^:unit find-by-role-single-connection-test
  (testing "finding connections by role with single match"
    (let [reg (registry/create-in-memory-registry)
          connection (create-test-connection test-user-id-1 #{:admin})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)

      (let [admin-adapters (ports/find-by-role reg :admin)]

        (testing "returns vector with one adapter"
          (is (= 1 (count admin-adapters)))
          (is (= test-connection-id-1 (ports/connection-id (first admin-adapters)))))))))

(deftest ^:unit find-by-role-multiple-connections-test
  (testing "finding connections by role with multiple matches"
    (let [reg (registry/create-in-memory-registry)
          connection-1 (create-test-connection test-user-id-1 #{:admin})
          connection-2 (create-test-connection test-user-id-2 #{:admin})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
      (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)

      (let [admin-adapters (ports/find-by-role reg :admin)]

        (testing "returns all admin connections"
          (is (= 2 (count admin-adapters)))
          (let [connection-ids (set (map ports/connection-id admin-adapters))]
            (is (contains? connection-ids test-connection-id-1))
            (is (contains? connection-ids test-connection-id-2))))))))

(deftest ^:unit find-by-role-no-matches-test
  (testing "finding connections by role with no matches"
    (let [reg (registry/create-in-memory-registry)
          connection (create-test-connection test-user-id-1 #{:user})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)

      (let [admin-adapters (ports/find-by-role reg :admin)]

        (testing "returns empty vector"
          (is (= 0 (count admin-adapters))))))))

(deftest ^:unit find-by-role-filters-correctly-test
  (testing "find-by-role returns only connections with specified role"
    (let [reg (registry/create-in-memory-registry)
          user-connection (create-test-connection test-user-id-1 #{:user})
          admin-connection (create-test-connection test-user-id-2 #{:admin})
          user-ws (create-test-ws-adapter test-connection-id-1)
          admin-ws (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 user-connection user-ws)
      (ports/register reg test-connection-id-2 admin-connection admin-ws)

      (let [user-adapters (ports/find-by-role reg :user)
            admin-adapters (ports/find-by-role reg :admin)]

        (testing "user role returns only user connections"
          (is (= 1 (count user-adapters)))
          (is (= test-connection-id-1 (ports/connection-id (first user-adapters)))))

        (testing "admin role returns only admin connections"
          (is (= 1 (count admin-adapters)))
          (is (= test-connection-id-2 (ports/connection-id (first admin-adapters)))))))))

(deftest ^:unit find-by-role-multiple-roles-test
  (testing "finding connections with users having multiple roles"
    (let [reg (registry/create-in-memory-registry)
          ;; User with both :user and :moderator roles
          connection (create-test-connection test-user-id-1 #{:user :moderator})
          ws-adapter (create-test-ws-adapter test-connection-id-1)]

      (ports/register reg test-connection-id-1 connection ws-adapter)

      (testing "connection found by either role"
        (let [user-adapters (ports/find-by-role reg :user)
              moderator-adapters (ports/find-by-role reg :moderator)]

          (is (= 1 (count user-adapters)))
          (is (= 1 (count moderator-adapters)))
          (is (= test-connection-id-1 (ports/connection-id (first user-adapters))))
          (is (= test-connection-id-1 (ports/connection-id (first moderator-adapters)))))))))

;; =============================================================================
;; All Connections Tests
;; =============================================================================

(deftest ^:unit all-connections-empty-test
  (testing "getting all connections from empty registry"
    (let [reg (registry/create-in-memory-registry)
          all-adapters (ports/all-connections reg)]

      (testing "returns empty vector"
        (is (vector? all-adapters))
        (is (= 0 (count all-adapters)))))))

(deftest ^:unit all-connections-test
  (testing "getting all connections"
    (let [reg (registry/create-in-memory-registry)
          connection-1 (create-test-connection test-user-id-1 #{:user})
          connection-2 (create-test-connection test-user-id-2 #{:admin})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
      (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)

      (let [all-adapters (ports/all-connections reg)]

        (testing "returns all registered adapters"
          (is (= 2 (count all-adapters)))
          (let [connection-ids (set (map ports/connection-id all-adapters))]
            (is (contains? connection-ids test-connection-id-1))
            (is (contains? connection-ids test-connection-id-2))))))))

;; =============================================================================
;; Connection Count Tests
;; =============================================================================

(deftest ^:unit connection-count-empty-test
  (testing "connection count for empty registry"
    (let [reg (registry/create-in-memory-registry)]

      (testing "returns zero"
        (is (= 0 (ports/connection-count reg)))))))

(deftest ^:unit connection-count-test
  (testing "connection count after registration"
    (let [reg (registry/create-in-memory-registry)
          connection-1 (create-test-connection test-user-id-1 #{:user})
          connection-2 (create-test-connection test-user-id-2 #{:admin})
          ws-adapter-1 (create-test-ws-adapter test-connection-id-1)
          ws-adapter-2 (create-test-ws-adapter test-connection-id-2)]

      (testing "count increases with registrations"
        (is (= 0 (ports/connection-count reg)))

        (ports/register reg test-connection-id-1 connection-1 ws-adapter-1)
        (is (= 1 (ports/connection-count reg)))

        (ports/register reg test-connection-id-2 connection-2 ws-adapter-2)
        (is (= 2 (ports/connection-count reg))))

      (testing "count decreases with unregistration"
        (ports/unregister reg test-connection-id-1)
        (is (= 1 (ports/connection-count reg)))

        (ports/unregister reg test-connection-id-2)
        (is (= 0 (ports/connection-count reg)))))))

;; =============================================================================
;; Thread Safety Tests
;; =============================================================================

(deftest ^:unit concurrent-registration-test
  (testing "concurrent registration of connections"
    (let [reg (registry/create-in-memory-registry)
          num-threads 10
          connections-per-thread 10

          ;; Register connections concurrently from multiple threads
          register-fn (fn [_thread-id]
                        (dotimes [_i connections-per-thread]
                          (let [connection-id (java.util.UUID/randomUUID)
                                user-id (java.util.UUID/randomUUID)
                                connection (create-test-connection user-id #{:user})
                                ws-adapter (create-test-ws-adapter connection-id)]
                            (ports/register reg connection-id connection ws-adapter))))

          threads (mapv #(Thread. (fn [] (register-fn %))) (range num-threads))]

      ;; Start all threads
      (doseq [thread threads] (.start thread))

      ;; Wait for all threads to complete
      (doseq [thread threads] (.join thread))

      (testing "all connections registered correctly"
        (is (= (* num-threads connections-per-thread) (ports/connection-count reg)))))))

(deftest ^:unit concurrent-unregistration-test
  (testing "concurrent unregistration of connections"
    (let [reg (registry/create-in-memory-registry)
          num-connections 100

          ;; Pre-register connections
          connection-ids (atom [])
          _ (dotimes [_i num-connections]
              (let [connection-id (java.util.UUID/randomUUID)
                    user-id (java.util.UUID/randomUUID)
                    connection (create-test-connection user-id #{:user})
                    ws-adapter (create-test-ws-adapter connection-id)]
                (ports/register reg connection-id connection ws-adapter)
                (swap! connection-ids conj connection-id)))

          ;; Unregister concurrently
          threads (mapv #(Thread. (fn [] (ports/unregister reg %))) @connection-ids)]

      ;; Start all threads
      (doseq [thread threads] (.start thread))

      ;; Wait for all threads to complete
      (doseq [thread threads] (.join thread))

      (testing "all connections unregistered correctly"
        (is (= 0 (ports/connection-count reg)))))))

;; =============================================================================
;; Find Adapters by IDs Tests
;; =============================================================================

(def user-id (java.util.UUID/randomUUID))

(deftest ^:unit find-adapters-by-ids-test
  (testing "returns adapters for present ids, skips missing"
    (let [reg (registry/create-in-memory-registry)
          id-1 (java.util.UUID/randomUUID)
          id-2 (java.util.UUID/randomUUID)
          missing (java.util.UUID/randomUUID)
          conn-1 (conn/create-connection user-id #{:user} {} id-1 (java.time.Instant/now))
          conn-2 (conn/create-connection user-id #{:user} {} id-2 (java.time.Instant/now))
          ws-1 (ws/create-test-websocket-adapter id-1)
          ws-2 (ws/create-test-websocket-adapter id-2)]
      (ports/register reg id-1 conn-1 ws-1)
      (ports/register reg id-2 conn-2 ws-2)
      (let [found (ports/find-adapters-by-ids reg [id-1 missing id-2])]
        (is (= 2 (count found)))
        (is (= #{ws-1 ws-2} (set found))))
      (testing "empty input → empty vector"
        (is (= [] (ports/find-adapters-by-ids reg [])))))))
