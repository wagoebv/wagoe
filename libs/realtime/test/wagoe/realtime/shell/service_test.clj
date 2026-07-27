(ns wagoe.realtime.shell.service-test
  "Integration tests for realtime service (shell layer).
   
   Tests service orchestration with test adapters - verifies I/O operations
   like connection lifecycle, JWT authentication, and message routing."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.shell.pubsub-manager :as pubsub-mgr]
            [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-user-id #uuid "550e8400-e29b-41d4-a716-446655440000")
(def test-user-id-2 #uuid "660e8400-e29b-41d4-a716-446655440001")

(defn create-test-service
  "Create service with test adapters for integration testing."
  ([]
   (create-test-service nil))
  ([pubsub-manager]
   (let [connection-registry (registry/create-in-memory-registry)
         jwt-adapter (jwt/create-test-jwt-adapter
                      {:expected-token "valid-token"
                       :user-id test-user-id
                       :email "test@example.com"
                       :roles #{:user}})
         service (service/create-realtime-service
                  connection-registry
                  jwt-adapter
                  :pubsub-manager pubsub-manager)]
     {:service service
      :registry connection-registry
      :jwt-adapter jwt-adapter
      :pubsub-manager pubsub-manager})))

(defn create-test-ws-adapter
  "Create test WebSocket adapter with unique connection ID."
  []
  (ws/create-test-websocket-adapter (java.util.UUID/randomUUID)))

;; =============================================================================
;; Connection Lifecycle Tests
;; =============================================================================

(deftest ^:integration connect-with-valid-jwt-test
  (testing "establishing connection with valid JWT"
    (let [{:keys [service registry]} (create-test-service)
          ws-adapter (create-test-ws-adapter)

          connection-id (ports/connect service ws-adapter {"token" "valid-token"})]

      (testing "returns connection ID"
        (is (uuid? connection-id)))

      (testing "registers connection in registry"
        (is (= 1 (ports/connection-count registry)))
        (let [connection (ports/find-connection registry connection-id)]
          (is (some? connection))
          (is (= test-user-id (:user-id connection)))
          (is (= #{:user} (:roles connection))))))))

(deftest ^:integration connect-with-invalid-jwt-test
  (testing "connection fails with invalid JWT"
    (let [{:keys [service registry]} (create-test-service)
          ws-adapter (create-test-ws-adapter)]

      (testing "throws unauthorized exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unauthorized"
             (ports/connect service ws-adapter {"token" "invalid-token"}))))

      (testing "does not register connection"
        (is (= 0 (ports/connection-count registry)))))))

(deftest ^:integration connect-without-token-test
  (testing "connection fails without token in query params"
    (let [{:keys [service registry]} (create-test-service)
          ws-adapter (create-test-ws-adapter)]

      (testing "throws unauthorized exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unauthorized.*Missing JWT token"
             (ports/connect service ws-adapter {}))))

      (testing "does not register connection"
        (is (= 0 (ports/connection-count registry)))))))

(deftest ^:integration disconnect-test
  (testing "disconnecting active connection"
    (let [{:keys [service registry]} (create-test-service)
          ws-adapter (create-test-ws-adapter)

          connection-id (ports/connect service ws-adapter {"token" "valid-token"})]

      (testing "connection exists before disconnect"
        (is (= 1 (ports/connection-count registry))))

      (ports/disconnect service connection-id)

      (testing "removes connection from registry"
        (is (= 0 (ports/connection-count registry)))
        (is (nil? (ports/find-connection registry connection-id)))))))

(deftest ^:integration disconnect-nonexistent-connection-test
  (testing "disconnecting non-existent connection does not throw"
    (let [{:keys [service]} (create-test-service)]

      (testing "gracefully handles missing connection"
        (is (nil? (ports/disconnect service #uuid "00000000-0000-0000-0000-000000000000")))))))

;; =============================================================================
;; Message Routing Tests
;; =============================================================================

(deftest ^:integration send-to-user-test
  (testing "sending message to user's connections"
    (let [{:keys [service _registry]} (create-test-service)
          ws-adapter-1 (create-test-ws-adapter)
          ws-adapter-2 (create-test-ws-adapter)

          ;; Connect two WebSockets for same user
          _ (ports/connect service ws-adapter-1 {"token" "valid-token"})
          _ (ports/connect service ws-adapter-2 {"token" "valid-token"})

          message {:type "notification" :content "Hello user"}
          send-count (ports/send-to-user service test-user-id message)]

      (testing "sends to all user's connections"
        (is (= 2 send-count)))

      (testing "both adapters received message"
        (is (= 1 (count @(:sent-messages ws-adapter-1))))
        (is (= 1 (count @(:sent-messages ws-adapter-2))))

        (let [msg-1 (first @(:sent-messages ws-adapter-1))
              msg-2 (first @(:sent-messages ws-adapter-2))]
          (is (= "notification" (:type msg-1)))
          (is (= "Hello user" (:content msg-1)))
          (is (= "notification" (:type msg-2)))
          (is (= "Hello user" (:content msg-2)))
          (is (some? (:timestamp msg-1))) ; Timestamp added by service
          (is (some? (:timestamp msg-2))))))))

(deftest ^:integration send-to-user-no-connections-test
  (testing "sending to user with no connections"
    (let [{:keys [service]} (create-test-service)
          message {:type "notification" :content "Hello"}

          send-count (ports/send-to-user service test-user-id-2 message)]

      (testing "returns zero count"
        (is (= 0 send-count))))))

(deftest ^:integration send-to-role-test
  (testing "sending message to role-based connections"
    (let [{:keys [service]} (create-test-service)

          ;; Create separate JWT adapter for admin role
          admin-jwt-adapter (jwt/create-test-jwt-adapter
                             {:expected-token "admin-token"
                              :user-id test-user-id-2
                              :email "admin@example.com"
                              :roles #{:admin}})
          admin-registry (registry/create-in-memory-registry)
          admin-service (service/create-realtime-service admin-registry admin-jwt-adapter)

          ;; Connect user (regular role)
          user-ws (create-test-ws-adapter)
          _ (ports/connect service user-ws {"token" "valid-token"})

          ;; Connect admin
          admin-ws (create-test-ws-adapter)
          _ (ports/connect admin-service admin-ws {"token" "admin-token"})

          message {:type "admin-alert" :content "Server maintenance"}]

      (testing "sends only to admin connections"
        (let [admin-count (ports/send-to-role admin-service :admin message)
              user-count (ports/send-to-role service :admin message)]

          (is (= 1 admin-count))
          (is (= 0 user-count))

          (testing "admin received message"
            (is (= 1 (count @(:sent-messages admin-ws))))
            (let [msg (first @(:sent-messages admin-ws))]
              (is (= "admin-alert" (:type msg)))
              (is (= "Server maintenance" (:content msg)))))

          (testing "user did not receive message"
            (is (= 0 (count @(:sent-messages user-ws))))))))))

(deftest ^:integration broadcast-test
  (testing "broadcasting message to all connections"
    (let [{:keys [service]} (create-test-service)
          ws-adapter-1 (create-test-ws-adapter)
          ws-adapter-2 (create-test-ws-adapter)
          ws-adapter-3 (create-test-ws-adapter)

          _ (ports/connect service ws-adapter-1 {"token" "valid-token"})
          _ (ports/connect service ws-adapter-2 {"token" "valid-token"})
          _ (ports/connect service ws-adapter-3 {"token" "valid-token"})

          message {:type "broadcast" :content "System announcement"}
          send-count (ports/broadcast service message)]

      (testing "sends to all connections"
        (is (= 3 send-count)))

      (testing "all adapters received message"
        (is (= 1 (count @(:sent-messages ws-adapter-1))))
        (is (= 1 (count @(:sent-messages ws-adapter-2))))
        (is (= 1 (count @(:sent-messages ws-adapter-3))))))))

(deftest ^:integration send-to-connection-test
  (testing "sending message to specific connection"
    (let [{:keys [service]} (create-test-service)
          ws-adapter-1 (create-test-ws-adapter)
          ws-adapter-2 (create-test-ws-adapter)

          connection-id-1 (ports/connect service ws-adapter-1 {"token" "valid-token"})
          _ (ports/connect service ws-adapter-2 {"token" "valid-token"})

          message {:type "job-progress" :content "50% complete"}
          result (ports/send-to-connection service connection-id-1 message)]

      (testing "returns true on success"
        (is (true? result)))

      (testing "only target connection received message"
        (is (= 1 (count @(:sent-messages ws-adapter-1))))
        (is (= 0 (count @(:sent-messages ws-adapter-2))))

        (let [msg (first @(:sent-messages ws-adapter-1))]
          (is (= "job-progress" (:type msg)))
          (is (= "50% complete" (:content msg))))))))

(deftest ^:integration send-to-connection-not-found-test
  (testing "sending to non-existent connection"
    (let [{:keys [service]} (create-test-service)
          message {:type "test" :content "test"}

          result (ports/send-to-connection service #uuid "00000000-0000-0000-0000-000000000000" message)]

      (testing "returns false"
        (is (false? result))))))

(deftest ^:integration send-to-closed-connection-test
  (testing "sending to closed connection"
    (let [{:keys [service]} (create-test-service)
          ws-adapter (create-test-ws-adapter)

          connection-id (ports/connect service ws-adapter {"token" "valid-token"})]

      ;; Close the connection
      (ports/close ws-adapter)

      (let [message {:type "test" :content "test"}
            result (ports/send-to-connection service connection-id message)]

        (testing "returns false for closed connection"
          (is (false? result)))

        (testing "message not added to closed adapter"
          (is (= 0 (count @(:sent-messages ws-adapter)))))))))

;; =============================================================================
;; Message Timestamp Tests
;; =============================================================================

(deftest ^:integration message-timestamp-auto-added-test
  (testing "service adds timestamp if missing"
    (let [{:keys [service]} (create-test-service)
          ws-adapter (create-test-ws-adapter)

          _ (ports/connect service ws-adapter {"token" "valid-token"})

          message-without-timestamp {:type "test" :content "test"}
          _ (ports/send-to-user service test-user-id message-without-timestamp)]

      (testing "timestamp added to sent message"
        (let [sent-msg (first @(:sent-messages ws-adapter))]
          (is (some? (:timestamp sent-msg))))))))

(deftest ^:integration message-timestamp-preserved-test
  (testing "service preserves existing timestamp"
    (let [{:keys [service]} (create-test-service)
          ws-adapter (create-test-ws-adapter)

          _ (ports/connect service ws-adapter {"token" "valid-token"})

          custom-timestamp (java.time.Instant/parse "2024-01-01T12:00:00Z")
          message-with-timestamp {:type "test" :content "test" :timestamp custom-timestamp}
          _ (ports/send-to-user service test-user-id message-with-timestamp)]

      (testing "original timestamp preserved"
        (let [sent-msg (first @(:sent-messages ws-adapter))]
          (is (= custom-timestamp (:timestamp sent-msg))))))))

;; =============================================================================
;; Pub/Sub Integration Tests
;; =============================================================================

(deftest ^:integration publish-to-topic-with-subscribers-test
  (testing "publishing message to topic with subscribers"
    (let [pubsub-manager (pubsub-mgr/create-pubsub-manager)
          {:keys [service]} (create-test-service pubsub-manager)
          ws-adapter-1 (create-test-ws-adapter)
          ws-adapter-2 (create-test-ws-adapter)

          conn-id-1 (ports/connect service ws-adapter-1 {"token" "valid-token"})
          conn-id-2 (ports/connect service ws-adapter-2 {"token" "valid-token"})]

      ;; Subscribe both connections to topic
      (ports/subscribe-to-topic pubsub-manager conn-id-1 "order:123")
      (ports/subscribe-to-topic pubsub-manager conn-id-2 "order:123")

      ;; Publish message to topic
      (let [message {:type "order-updated" :status "shipped"}
            subscriber-count (ports/publish-to-topic service "order:123" message)]

        (testing "returns count of subscribers"
          (is (= 2 subscriber-count)))

        (testing "message sent to all subscribers"
          (is (= 1 (count @(:sent-messages ws-adapter-1))))
          (is (= 1 (count @(:sent-messages ws-adapter-2))))

          (let [msg-1 (first @(:sent-messages ws-adapter-1))
                msg-2 (first @(:sent-messages ws-adapter-2))]
            (is (= "order-updated" (:type msg-1)))
            (is (= "shipped" (:status msg-1)))
            (is (= msg-1 msg-2))))))))

(deftest ^:integration publish-to-topic-without-subscribers-test
  (testing "publishing message to topic with no subscribers"
    (let [pubsub-manager (pubsub-mgr/create-pubsub-manager)
          {:keys [service]} (create-test-service pubsub-manager)
          message {:type "test" :content "test"}
          subscriber-count (ports/publish-to-topic service "empty-topic" message)]

      (testing "returns 0 subscribers"
        (is (= 0 subscriber-count))))))

(deftest ^:integration publish-to-topic-partial-subscribers-test
  (testing "publishing to topic with only some connections subscribed"
    (let [pubsub-manager (pubsub-mgr/create-pubsub-manager)
          {:keys [service]} (create-test-service pubsub-manager)
          ws-adapter-1 (create-test-ws-adapter)
          ws-adapter-2 (create-test-ws-adapter)

          conn-id-1 (ports/connect service ws-adapter-1 {"token" "valid-token"})
          _conn-id-2 (ports/connect service ws-adapter-2 {"token" "valid-token"})]

      ;; Only subscribe first connection
      (ports/subscribe-to-topic pubsub-manager conn-id-1 "notifications")

      ;; Publish message
      (let [message {:type "notification" :content "New message"}
            subscriber-count (ports/publish-to-topic service "notifications" message)]

        (testing "returns count of subscribed connections only"
          (is (= 1 subscriber-count)))

        (testing "message sent only to subscriber"
          (is (= 1 (count @(:sent-messages ws-adapter-1))))
          (is (= 0 (count @(:sent-messages ws-adapter-2)))))))))

(deftest ^:integration disconnect-unsubscribes-from-all-topics-test
  (testing "disconnect removes subscriptions from all topics"
    (let [pubsub-manager (pubsub-mgr/create-pubsub-manager)
          {:keys [service]} (create-test-service pubsub-manager)
          ws-adapter (create-test-ws-adapter)

          conn-id (ports/connect service ws-adapter {"token" "valid-token"})]

      ;; Subscribe to multiple topics
      (ports/subscribe-to-topic pubsub-manager conn-id "topic-1")
      (ports/subscribe-to-topic pubsub-manager conn-id "topic-2")
      (ports/subscribe-to-topic pubsub-manager conn-id "topic-3")

      (testing "connection subscribed to topics before disconnect"
        (is (= 3 (count (ports/get-connection-subscriptions pubsub-manager conn-id)))))

      ;; Disconnect
      (ports/disconnect service conn-id)

      (testing "connection unsubscribed from all topics after disconnect"
        (is (= 0 (count (ports/get-connection-subscriptions pubsub-manager conn-id)))))

      (testing "topics have no subscribers"
        (is (= 0 (count (ports/get-topic-subscribers pubsub-manager "topic-1"))))
        (is (= 0 (count (ports/get-topic-subscribers pubsub-manager "topic-2"))))
        (is (= 0 (count (ports/get-topic-subscribers pubsub-manager "topic-3"))))))))

(deftest ^:integration service-without-pubsub-manager-test
  (testing "service works without pubsub manager (backward compatibility)"
    (let [{:keys [service]} (create-test-service nil)
          ws-adapter (create-test-ws-adapter)
          conn-id (ports/connect service ws-adapter {"token" "valid-token"})]

      (testing "connection established"
        (is (uuid? conn-id)))

      (testing "can send to user"
        (let [sent-count (ports/send-to-user service test-user-id {:type "test"})]
          (is (= 1 sent-count))))

      (testing "can disconnect"
        (ports/disconnect service conn-id)
        (is (= 0 (ports/send-to-user service test-user-id {:type "test"})))))))

(deftest ^:integration publish-adds-timestamp-test
  (testing "publish-to-topic adds timestamp if missing"
    (let [pubsub-manager (pubsub-mgr/create-pubsub-manager)
          {:keys [service]} (create-test-service pubsub-manager)
          ws-adapter (create-test-ws-adapter)

          conn-id (ports/connect service ws-adapter {"token" "valid-token"})]

      ;; Subscribe to topic
      (ports/subscribe-to-topic pubsub-manager conn-id "test-topic")

      ;; Publish message without timestamp
      (let [message {:type "test" :content "test"}]
        (ports/publish-to-topic service "test-topic" message)

        (testing "timestamp added to published message"
          (let [sent-msg (first @(:sent-messages ws-adapter))]
            (is (some? (:timestamp sent-msg)))))))))
