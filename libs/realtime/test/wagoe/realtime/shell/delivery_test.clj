(ns wagoe.realtime.shell.delivery-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.connection :as conn]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.pubsub-manager :as pubsub-mgr]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.shell.delivery :as delivery]))

(def user-id #uuid "550e8400-e29b-41d4-a716-446655440000")

(defn- register! [reg id roles]
  (let [c (conn/create-connection user-id roles {} id (java.time.Instant/now))
        a (ws/create-test-websocket-adapter id)]
    (ports/register reg id c a)
    a))

(deftest ^:unit delivery-routes-test
  (let [reg (registry/create-in-memory-registry)
        pubsub (pubsub-mgr/create-pubsub-manager)
        id-1 (java.util.UUID/randomUUID)
        id-2 (java.util.UUID/randomUUID)
        a1 (register! reg id-1 #{:user :admin})
        _a2 (register! reg id-2 #{:user})
        f  (delivery/make-delivery-fn reg pubsub)
        msg {:type :x :payload {}}]
    (testing "broadcast hits all, returns count"
      (is (= 2 (f {:route :broadcast :target nil :message msg}))))
    (testing "role filters"
      (is (= 1 (f {:route :role :target :admin :message msg}))))
    (testing "connection targets one"
      (is (= 1 (f {:route :connection :target id-1 :message msg}))))
    (testing "topic uses pubsub-manager ∩ local registry"
      (ports/subscribe-to-topic pubsub id-2 "order:1")
      (is (= 1 (f {:route :topic :target "order:1" :message msg}))))
    (testing "topic with nil pubsub-manager → 0"
      (let [f0 (delivery/make-delivery-fn reg nil)]
        (is (= 0 (f0 {:route :topic :target "order:1" :message msg})))))
    (testing "messages actually delivered to adapters"
      (is (pos? (count @(:sent-messages a1)))))))
