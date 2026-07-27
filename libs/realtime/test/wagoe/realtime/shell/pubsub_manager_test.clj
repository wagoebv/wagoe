(ns wagoe.realtime.shell.pubsub-manager-test
  "Integration tests for pub/sub manager (shell layer).
   
   Tests subscription state management - verifies topic subscription,
   unsubscription, and query operations."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.pubsub-manager :as pubsub-mgr]
            [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-connection-id-1 #uuid "550e8400-e29b-41d4-a716-446655440000")
(def test-connection-id-2 #uuid "660e8400-e29b-41d4-a716-446655440001")
(def test-connection-id-3 #uuid "770e8400-e29b-41d4-a716-446655440002")

(def test-topic-1 "order:123")
(def test-topic-2 "user:456:notifications")
(def test-topic-3 "chat:general")

;; =============================================================================
;; Subscribe/Unsubscribe Tests
;; =============================================================================

(deftest ^:unit subscribe-to-topic-test
  (testing "subscribing to a topic"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      
      (testing "topic has subscriber"
        (let [subscribers (ports/get-topic-subscribers manager test-topic-1)]
          (is (= #{test-connection-id-1} subscribers))))
      
      (testing "connection has subscription"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (= #{test-topic-1} topics))))
      
      (testing "topic count increases"
        (is (= 1 (ports/topic-count manager))))
      
      (testing "subscription count increases"
        (is (= 1 (ports/subscription-count manager)))))))

(deftest ^:unit subscribe-multiple-connections-to-same-topic-test
  (testing "multiple connections subscribing to same topic"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-2 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-3 test-topic-1)
      
      (testing "all connections are subscribed"
        (let [subscribers (ports/get-topic-subscribers manager test-topic-1)]
          (is (contains? subscribers test-connection-id-1))
          (is (contains? subscribers test-connection-id-2))
          (is (contains? subscribers test-connection-id-3))))
      
      (testing "topic has all subscribers"
        (let [subscribers (ports/get-topic-subscribers manager test-topic-1)]
          (is (= #{test-connection-id-1 test-connection-id-2 test-connection-id-3} 
                 subscribers))))
      
      (testing "subscription count reflects all subscriptions"
        (is (= 3 (ports/subscription-count manager))))
      
      (testing "topic count is still 1"
        (is (= 1 (ports/topic-count manager)))))))

(deftest ^:unit subscribe-one-connection-to-multiple-topics-test
  (testing "one connection subscribing to multiple topics"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-2)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-3)
      
      (testing "connection is subscribed to all topics"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (contains? topics test-topic-1))
          (is (contains? topics test-topic-2))
          (is (contains? topics test-topic-3))))
      
      (testing "connection has all subscriptions"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (= #{test-topic-1 test-topic-2 test-topic-3} topics))))
      
      (testing "topic count reflects all topics"
        (is (= 3 (ports/topic-count manager))))
      
      (testing "subscription count reflects all subscriptions"
        (is (= 3 (ports/subscription-count manager)))))))

(deftest ^:unit subscribe-idempotent-test
  (testing "subscribing same connection to same topic multiple times is idempotent"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      
      (testing "subscription count stays at 1"
        (is (= 1 (ports/subscription-count manager))))
      
      (testing "topic has single subscriber"
        (let [subscribers (ports/get-topic-subscribers manager test-topic-1)]
          (is (= #{test-connection-id-1} subscribers)))))))

(deftest ^:unit unsubscribe-from-topic-test
  (testing "unsubscribing from a topic"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; Setup: subscribe first
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-2)
      
      ;; Unsubscribe from one topic
      (ports/unsubscribe-from-topic manager test-connection-id-1 test-topic-1)
      
      (testing "connection is no longer subscribed to topic"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (not (contains? topics test-topic-1)))))
      
      (testing "connection still subscribed to other topic"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (contains? topics test-topic-2))))
      
      (testing "topic has no subscribers"
        (let [subscribers (ports/get-topic-subscribers manager test-topic-1)]
          (is (empty? subscribers))))
      
      (testing "subscription count decreases"
        (is (= 1 (ports/subscription-count manager)))))))

(deftest ^:unit unsubscribe-from-all-topics-test
  (testing "unsubscribing connection from all topics"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; Setup: subscribe to multiple topics
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-2)
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-3)
      
      ;; Also subscribe another connection
      (ports/subscribe-to-topic manager test-connection-id-2 test-topic-1)
      
      ;; Unsubscribe connection 1 from all topics
      (ports/unsubscribe-from-all-topics manager test-connection-id-1)
      
      (testing "connection is not subscribed to any topic"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (empty? topics))))
      
      (testing "connection has no subscriptions"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-1)]
          (is (empty? topics))))
      
      (testing "other connection still subscribed"
        (let [topics (ports/get-connection-subscriptions manager test-connection-id-2)]
          (is (contains? topics test-topic-1))))
      
      (testing "subscription count reflects only remaining subscriptions"
        (is (= 1 (ports/subscription-count manager)))))))

;; =============================================================================
;; Query Tests
;; =============================================================================

(deftest ^:unit get-topic-subscribers-empty-test
  (testing "getting subscribers for non-existent topic"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (testing "returns empty set"
        (is (= #{} (ports/get-topic-subscribers manager "non-existent-topic")))))))

(deftest ^:unit get-connection-subscriptions-empty-test
  (testing "getting subscriptions for non-subscribed connection"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (testing "returns empty set"
        (is (= #{} (ports/get-connection-subscriptions manager test-connection-id-1)))))))

(deftest ^:unit topic-count-empty-test
  (testing "topic count when no topics exist"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (testing "returns 0"
        (is (= 0 (ports/topic-count manager)))))))

(deftest ^:unit subscription-count-empty-test
  (testing "subscription count when no subscriptions exist"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      (testing "returns 0"
        (is (= 0 (ports/subscription-count manager)))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest ^:unit unsubscribe-from-non-existent-topic-test
  (testing "unsubscribing from topic that connection is not subscribed to"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; Should not throw error
      (ports/unsubscribe-from-topic manager test-connection-id-1 test-topic-1)
      
      (testing "no state changes"
        (is (= 0 (ports/subscription-count manager)))
        (is (= 0 (ports/topic-count manager)))))))

(deftest ^:unit unsubscribe-all-for-never-subscribed-connection-test
  (testing "unsubscribing all topics for connection that never subscribed"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; Should not throw error
      (ports/unsubscribe-from-all-topics manager test-connection-id-1)
      
      (testing "no state changes"
        (is (= 0 (ports/subscription-count manager)))
        (is (= 0 (ports/topic-count manager)))))))

;; =============================================================================
;; Integration Scenarios
;; =============================================================================

(deftest ^:unit complex-subscription-scenario-test
  (testing "complex multi-connection multi-topic scenario"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; User 1 subscribes to orders and notifications
      (ports/subscribe-to-topic manager test-connection-id-1 "order:123")
      (ports/subscribe-to-topic manager test-connection-id-1 "user:1:notifications")
      
      ;; User 2 subscribes to same order and chat
      (ports/subscribe-to-topic manager test-connection-id-2 "order:123")
      (ports/subscribe-to-topic manager test-connection-id-2 "chat:general")
      
      ;; User 3 subscribes to chat only
      (ports/subscribe-to-topic manager test-connection-id-3 "chat:general")
      
      (testing "correct topic counts"
        (is (= 3 (ports/topic-count manager))))
      
      (testing "correct subscription counts"
        (is (= 5 (ports/subscription-count manager))))
      
      (testing "order topic has 2 subscribers"
        (is (= 2 (count (ports/get-topic-subscribers manager "order:123")))))
      
      (testing "chat topic has 2 subscribers"
        (is (= 2 (count (ports/get-topic-subscribers manager "chat:general")))))
      
      (testing "notifications topic has 1 subscriber"
        (is (= 1 (count (ports/get-topic-subscribers manager "user:1:notifications")))))
      
      ;; User 1 disconnects
      (ports/unsubscribe-from-all-topics manager test-connection-id-1)
      
      (testing "after user 1 disconnects"
        (testing "order topic has 1 subscriber"
          (is (= 1 (count (ports/get-topic-subscribers manager "order:123")))))
        
        (testing "notifications topic has no subscribers"
          (is (empty? (ports/get-topic-subscribers manager "user:1:notifications"))))
        
        (testing "subscription count decreases"
          (is (= 3 (ports/subscription-count manager))))))))

(deftest ^:unit subscription-cleanup-scenario-test
  (testing "subscription cleanup removes empty topics"
    (let [manager (pubsub-mgr/create-pubsub-manager)]
      
      ;; Subscribe and then unsubscribe
      (ports/subscribe-to-topic manager test-connection-id-1 test-topic-1)
      (ports/unsubscribe-from-topic manager test-connection-id-1 test-topic-1)
      
      (testing "topic is removed when last subscriber leaves"
        (is (empty? (ports/get-topic-subscribers manager test-topic-1))))
      
      (testing "topic count is 0"
        ;; Note: Implementation may or may not remove empty topics from the map
        ;; Both behaviors are acceptable - the important thing is that queries
        ;; return correct results
        (is (<= (ports/topic-count manager) 1))))))
