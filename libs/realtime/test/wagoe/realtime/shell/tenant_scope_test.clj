(ns wagoe.realtime.shell.tenant-scope-test
  "Role, broadcast and topic messages stay inside the sender's tenant (BOU-947, BOU-986)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.database :as db]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.pubsub-manager :as pubsub]
            [wagoe.realtime.shell.service :as service]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(defn- reached
  "Which of three admins, in tenant A, tenant B and no tenant, each subscribed
   to `topics`, `send` reaches."
  ([send] (reached send []))
  ([send topics]
   (let [svc     (service/create-realtime-service (registry/create-in-memory-registry)
                                                  (jwt/create-test-jwt-adapter {})
                                                  :pubsub-manager (pubsub/create-pubsub-manager))
         sockets (into {} (for [[k t] {:a tenant-a :b tenant-b :none nil}]
                            (let [socket (ws/create-test-websocket-adapter (random-uuid))]
                              (ports/connect-identified svc socket (cond-> {:user-id (random-uuid) :roles #{:admin}}
                                                                     t (assoc :tenant-id t))
                                                        topics)
                              [k socket])))]
     (send svc)
     (set (keep (fn [[k socket]] (when (seq @(:sent-messages socket)) k)) sockets)))))

(deftest ^:unit ^:security role-and-broadcast-stay-in-the-senders-tenant
  (testing "a message naming its tenant"
    (is (= #{:a} (reached #(ports/send-to-role % :admin {:type :n :tenant-id tenant-a}))))
    (is (= #{:b} (reached #(ports/broadcast % {:type :n :tenant-id tenant-b})))))
  (testing "sent while a tenant is bound"
    (is (= #{:a} (reached #(binding [db/*tenant-id* tenant-a] (ports/broadcast % {:type :n})))))
    (is (= #{:a} (reached #(binding [db/*tenant-id* tenant-a] (ports/broadcast % {:type :n :tenant-id tenant-b}))))
        "a message a tenant's request forwards cannot name another tenant"))
  (testing "a system message, sent in no tenant, reaches everyone"
    (is (= #{:a :b :none} (reached #(ports/send-to-role % :admin {:type :n}))))))

(deftest ^:unit ^:security a-topic-stays-in-the-senders-tenant
  ;; Ids are per tenant schema, so order:123 is a different order in each
  ;; tenant, and its topic reached every tenant's subscribers (BOU-986)
  (let [publish #(ports/publish-to-topic % "order:123" {:type :order/updated})]
    (is (= #{:a} (reached #(binding [db/*tenant-id* tenant-a] (publish %)) ["order:123"])))
    (testing "a system message, sent in no tenant, reaches every subscriber"
      (is (= #{:a :b :none} (reached publish ["order:123"]))))))
