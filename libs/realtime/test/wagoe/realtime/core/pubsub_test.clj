(ns wagoe.realtime.core.pubsub-test
  "Unit tests for pub/sub core functions (pure, no I/O).
  
  Tests topic subscription management, subscriber filtering, and cleanup logic.
  All tests are deterministic and fast (no I/O)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.realtime.core.pubsub :as pubsub])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def conn-1 (UUID/randomUUID))
(def conn-2 (UUID/randomUUID))
(def conn-3 (UUID/randomUUID))

;; =============================================================================
;; subscribe tests
;; =============================================================================

(deftest ^:unit subscribe-test
  (testing "subscribe adds connection to topic"
    (let [subs {}
          result (pubsub/subscribe subs conn-1 "order:123")]
      (is (= #{ conn-1} (get result "order:123")))))

  (testing "subscribe to existing topic adds to set"
    (let [subs {"order:123" #{conn-1}}
          result (pubsub/subscribe subs conn-2 "order:123")]
      (is (= #{conn-1 conn-2} (get result "order:123")))))

  (testing "subscribe is idempotent"
    (let [subs {"order:123" #{conn-1}}
          result (pubsub/subscribe subs conn-1 "order:123")]
      (is (= #{conn-1} (get result "order:123")))))

  (testing "subscribe to different topics"
    (let [subs {}
          result (-> subs
                     (pubsub/subscribe conn-1 "order:123")
                     (pubsub/subscribe conn-1 "user:456"))]
      (is (= #{conn-1} (get result "order:123")))
      (is (= #{conn-1} (get result "user:456"))))))

;; =============================================================================
;; unsubscribe tests
;; =============================================================================

(deftest ^:unit unsubscribe-test
  (testing "unsubscribe removes connection from topic"
    (let [subs {"order:123" #{conn-1 conn-2}}
          result (pubsub/unsubscribe subs conn-1 "order:123")]
      (is (= #{conn-2} (get result "order:123")))))

  (testing "unsubscribe removes topic when no subscribers left"
    (let [subs {"order:123" #{conn-1}}
          result (pubsub/unsubscribe subs conn-1 "order:123")]
      (is (nil? (get result "order:123")))
      (is (empty? result))))

  (testing "unsubscribe is idempotent"
    (let [subs {"order:123" #{conn-1}}
          result (pubsub/unsubscribe subs conn-2 "order:123")]
      (is (= #{conn-1} (get result "order:123")))))

  (testing "unsubscribe from non-existent topic is safe"
    (let [subs {}
          result (pubsub/unsubscribe subs conn-1 "nonexistent")]
      (is (empty? result)))))

;; =============================================================================
;; unsubscribe-all tests
;; =============================================================================

(deftest ^:unit unsubscribe-all-test
  (testing "unsubscribe-all removes connection from all topics"
    (let [subs {"order:123" #{conn-1 conn-2}
                "user:456" #{conn-1 conn-3}
                "chat:general" #{conn-2 conn-3}}
          result (pubsub/unsubscribe-all subs conn-1)]
      (is (= #{conn-2} (get result "order:123")))
      (is (= #{conn-3} (get result "user:456")))
      (is (= #{conn-2 conn-3} (get result "chat:general")))))

  (testing "unsubscribe-all removes empty topics"
    (let [subs {"order:123" #{conn-1}
                "user:456" #{conn-1 conn-2}}
          result (pubsub/unsubscribe-all subs conn-1)]
      (is (nil? (get result "order:123")))
      (is (= #{conn-2} (get result "user:456")))))

  (testing "unsubscribe-all with no subscriptions returns empty map"
    (let [subs {"order:123" #{conn-2}}
          result (pubsub/unsubscribe-all subs conn-1)]
      (is (= subs result)))))

;; =============================================================================
;; get-subscribers tests
;; =============================================================================

(deftest ^:unit get-subscribers-test
  (testing "get-subscribers returns set of connection IDs"
    (let [subs {"order:123" #{conn-1 conn-2}}]
      (is (= #{conn-1 conn-2} (pubsub/get-subscribers subs "order:123")))))

  (testing "get-subscribers returns empty set for non-existent topic"
    (let [subs {"order:123" #{conn-1}}]
      (is (= #{} (pubsub/get-subscribers subs "nonexistent")))))

  (testing "get-subscribers returns empty set for empty subscriptions"
    (is (= #{} (pubsub/get-subscribers {} "order:123")))))

;; =============================================================================
;; get-connection-topics tests
;; =============================================================================

(deftest ^:unit get-connection-topics-test
  (testing "get-connection-topics returns all topics for connection"
    (let [subs {"order:123" #{conn-1}
                "user:456" #{conn-1 conn-2}
                "chat:general" #{conn-2}}]
      (is (= #{"order:123" "user:456"} (pubsub/get-connection-topics subs conn-1)))))

  (testing "get-connection-topics returns empty set when not subscribed"
    (let [subs {"order:123" #{conn-1}}]
      (is (= #{} (pubsub/get-connection-topics subs conn-2)))))

  (testing "get-connection-topics returns empty set for empty subscriptions"
    (is (= #{} (pubsub/get-connection-topics {} conn-1)))))

;; =============================================================================
;; topic-count tests
;; =============================================================================

(deftest ^:unit topic-count-test
  (testing "topic-count returns number of topics"
    (let [subs {"order:123" #{conn-1}
                "user:456" #{conn-2}
                "chat:general" #{conn-3}}]
      (is (= 3 (pubsub/topic-count subs)))))

  (testing "topic-count returns 0 for empty subscriptions"
    (is (= 0 (pubsub/topic-count {})))))

;; =============================================================================
;; subscriber-count tests
;; =============================================================================

(deftest ^:unit subscriber-count-test
  (testing "subscriber-count returns total subscriptions"
    (let [subs {"order:123" #{conn-1 conn-2}
                "user:456" #{conn-1}}]
      ;; conn-1 subscribed to 2 topics, conn-2 to 1 = 3 total
      (is (= 3 (pubsub/subscriber-count subs)))))

  (testing "subscriber-count returns 0 for empty subscriptions"
    (is (= 0 (pubsub/subscriber-count {})))))

;; =============================================================================
;; topic-exists? tests
;; =============================================================================

(deftest ^:unit topic-exists-test
  (testing "topic-exists? returns true for existing topic"
    (let [subs {"order:123" #{conn-1}}]
      (is (true? (pubsub/topic-exists? subs "order:123")))))

  (testing "topic-exists? returns false for non-existent topic"
    (let [subs {"order:123" #{conn-1}}]
      (is (false? (pubsub/topic-exists? subs "nonexistent")))))

  (testing "topic-exists? returns false for empty subscriptions"
    (is (false? (pubsub/topic-exists? {} "order:123")))))

;; =============================================================================
;; subscribed? tests
;; =============================================================================

(deftest ^:unit subscribed-test
  (testing "subscribed? returns true when subscribed"
    (let [subs {"order:123" #{conn-1 conn-2}}]
      (is (true? (pubsub/subscribed? subs conn-1 "order:123")))))

  (testing "subscribed? returns false when not subscribed"
    (let [subs {"order:123" #{conn-1}}]
      (is (false? (pubsub/subscribed? subs conn-2 "order:123")))))

  (testing "subscribed? returns false for non-existent topic"
    (let [subs {"order:123" #{conn-1}}]
      (is (false? (pubsub/subscribed? subs conn-1 "nonexistent"))))))

;; =============================================================================
;; Integration scenario tests
;; =============================================================================

(deftest ^:unit pubsub-workflow-test
  (testing "complete pub/sub workflow"
    (let [;; Start with empty subscriptions
          subs {}

          ;; Subscribe connections to topics
          subs (-> subs
                   (pubsub/subscribe conn-1 "order:123")
                   (pubsub/subscribe conn-2 "order:123")
                   (pubsub/subscribe conn-1 "user:456"))]

      ;; Verify subscriptions
      (is (= #{conn-1 conn-2} (pubsub/get-subscribers subs "order:123")))
      (is (= #{conn-1} (pubsub/get-subscribers subs "user:456")))
      (is (= #{"order:123" "user:456"} (pubsub/get-connection-topics subs conn-1)))
      (is (= 2 (pubsub/topic-count subs)))
      (is (= 3 (pubsub/subscriber-count subs)))

      ;; Unsubscribe one connection from one topic
      (let [subs (pubsub/unsubscribe subs conn-1 "order:123")]
        (is (= #{conn-2} (pubsub/get-subscribers subs "order:123")))
        (is (= #{"user:456"} (pubsub/get-connection-topics subs conn-1))))

      ;; Cleanup all subscriptions for conn-1
      (let [subs (pubsub/unsubscribe-all subs conn-1)]
        (is (= #{} (pubsub/get-connection-topics subs conn-1)))
        (is (= 1 (pubsub/topic-count subs)))))))
