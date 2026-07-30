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
