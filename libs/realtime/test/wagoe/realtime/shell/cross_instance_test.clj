(ns wagoe.realtime.shell.cross-instance-test
  "Two RealtimeService instances (= two replicas) share ONE in-memory bus.
   A publish on node A must reach node B's local sockets — proving the relay."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.connection :as conn]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.pubsub-manager :as pubsub-mgr]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.shell.bus.in-memory :as in-memory-bus]))

(def user-a #uuid "550e8400-e29b-41d4-a716-446655440000")
(def user-b #uuid "660e8400-e29b-41d4-a716-446655440001")

(defn- node [shared-bus]
  (let [reg (registry/create-in-memory-registry)
        pubsub (pubsub-mgr/create-pubsub-manager)
        jwt-adapter (jwt/create-test-jwt-adapter
                     {:expected-token "valid-token" :user-id user-a
                      :email "a@example.com" :roles #{:user}})
        svc (service/create-realtime-service reg jwt-adapter
                                             :pubsub-manager pubsub :bus shared-bus)]
    {:reg reg :pubsub pubsub :svc svc}))

(defn- register! [{:keys [reg]} id user-id roles]
  (let [c (conn/create-connection user-id roles {} id (java.time.Instant/now))
        a (ws/create-test-websocket-adapter id)]
    (ports/register reg id c a)
    a))

(deftest ^:integration broadcast-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        ws-a (register! a (java.util.UUID/randomUUID) user-a #{:user})
        ws-b (register! b (java.util.UUID/randomUUID) user-b #{:user})]
    (testing "broadcast from node A reaches a socket on node B"
      (ports/broadcast (:svc a) {:type :announce :payload {:m "hi"}})
      (is (= 1 (count @(:sent-messages ws-a))) "node A delivered locally")
      (is (= 1 (count @(:sent-messages ws-b))) "node B received via the relay"))))

(deftest ^:integration send-to-user-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        _ws-a (register! a (java.util.UUID/randomUUID) user-a #{:user})
        ws-b (register! b (java.util.UUID/randomUUID) user-b #{:user})]
    (testing "send-to-user reaches the right node"
      (ports/send-to-user (:svc a) user-b {:type :dm :payload {}})
      (is (= 1 (count @(:sent-messages ws-b))) "user-b socket on node B got it"))))

(deftest ^:integration publish-to-topic-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        id-b (java.util.UUID/randomUUID)
        ws-b (register! b id-b user-b #{:user})]
    (ports/subscribe-to-topic (:pubsub b) id-b "order:9")
    (testing "topic publish on A reaches subscriber on B"
      ;; node A must also see the subscription for its delivery-fn to resolve it;
      ;; with the in-memory pubsub manager each node has its own — so subscribe on
      ;; A's pubsub too (mirrors what RedisPubSubManager makes global automatically).
      (ports/subscribe-to-topic (:pubsub a) id-b "order:9")
      (ports/publish-to-topic (:svc a) "order:9" {:type :upd :payload {}})
      (is (= 1 (count @(:sent-messages ws-b)))))))

;; =============================================================================
;; Server-side subscribers (BOU-233)
;; =============================================================================

(deftest ^:integration service-subscriber-receives-published-messages-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)
        seen   (atom [])]
    (ports/subscribe-service (:pubsub a) "order:events"
                             (fn [msg] (swap! seen conj (:payload msg))))

    (testing "an in-process handler receives a topic message"
      (ports/publish-to-topic (:svc a) "order:events" {:type "created" :payload {:id 1}})
      (is (= [{:id 1}] @seen)))

    (testing "and keeps receiving them"
      (ports/publish-to-topic (:svc a) "order:events" {:type "created" :payload {:id 2}})
      (is (= [{:id 1} {:id 2}] @seen)))

    (testing "a different topic does not reach it"
      (ports/publish-to-topic (:svc a) "other:events" {:type "x" :payload {:id 3}})
      (is (= [{:id 1} {:id 2}] @seen)))))

(deftest ^:integration service-subscriber-fires-once-across-nodes-test
  ;; The reason handlers are registered node-locally and invoked by the
  ;; delivery-fn: the bus fans an envelope out to every node, and each node
  ;; runs the handlers that live in it. A handler registered once therefore
  ;; runs once, wherever the publish happened.
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)
        b      (node shared)
        on-a   (atom 0)
        on-b   (atom 0)]
    (ports/subscribe-service (:pubsub a) "orders" (fn [_] (swap! on-a inc)))
    (ports/subscribe-service (:pubsub b) "orders" (fn [_] (swap! on-b inc)))

    (testing "publishing from node A runs each node's handler exactly once"
      (ports/publish-to-topic (:svc a) "orders" {:type "e" :payload {}})
      (is (= 1 @on-a))
      (is (= 1 @on-b)))

    (testing "publishing from node B is symmetric"
      (ports/publish-to-topic (:svc b) "orders" {:type "e" :payload {}})
      (is (= 2 @on-a))
      (is (= 2 @on-b)))))

(deftest ^:integration service-subscribers-and-sockets-coexist-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)
        b      (node shared)
        conn-id (java.util.UUID/randomUUID)
        ws-b   (register! b conn-id user-b #{:user})
        seen   (atom 0)]
    (ports/subscribe-to-topic (:pubsub b) conn-id "mixed")
    (ports/subscribe-service (:pubsub a) "mixed" (fn [_] (swap! seen inc)))

    (testing "one publish reaches both a socket and a handler"
      (ports/publish-to-topic (:svc a) "mixed" {:type "e" :payload {:v 1}})
      (is (= 1 @seen) "the in-process handler ran")
      (is (= 1 (count @(:sent-messages ws-b))) "the websocket received it"))))

(deftest ^:integration service-subscriber-errors-are-isolated-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)
        ok     (atom 0)]
    (ports/subscribe-service (:pubsub a) "risky" (fn [_] (throw (ex-info "boom" {}))))
    (ports/subscribe-service (:pubsub a) "risky" (fn [_] (swap! ok inc)))

    (testing "a throwing handler does not stop the others"
      (ports/publish-to-topic (:svc a) "risky" {:type "e" :payload {}})
      (is (= 1 @ok)))))

(deftest ^:integration unsubscribe-service-stops-delivery-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)
        seen   (atom 0)
        sub-id (ports/subscribe-service (:pubsub a) "t" (fn [_] (swap! seen inc)))]
    (ports/publish-to-topic (:svc a) "t" {:type "e" :payload {}})
    (is (= 1 @seen))

    (testing "unsubscribing stops delivery and reports that it removed one"
      (is (true? (ports/unsubscribe-service (:pubsub a) sub-id)))
      (ports/publish-to-topic (:svc a) "t" {:type "e" :payload {}})
      (is (= 1 @seen)))

    (testing "unsubscribing an unknown id is not an error"
      (is (false? (ports/unsubscribe-service (:pubsub a) (java.util.UUID/randomUUID)))))))

(deftest ^:integration publish-to-topic-counts-service-subscribers-test
  ;; Reporting 0 while three handlers ran would describe something other than
  ;; what happened.
  (let [shared (in-memory-bus/create-in-memory-bus)
        a      (node shared)]
    (dotimes [_ 3] (ports/subscribe-service (:pubsub a) "counted" (fn [_] nil)))

    (testing "the return value includes in-process handlers"
      (is (= 3 (ports/publish-to-topic (:svc a) "counted" {:type "e" :payload {}}))))))
