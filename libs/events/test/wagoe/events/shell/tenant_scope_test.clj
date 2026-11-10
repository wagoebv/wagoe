(ns wagoe.events.shell.tenant-scope-test
  "A subscriber in a tenant receives that tenant's events and system events,
   never another tenant's (BOU-947)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.events.shell.adapters.in-memory :as in-memory]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.platform.database :as db]))

(def ^:private tenant-a "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b "b0000000-0000-0000-0000-00000000000b")

(defn- received
  "What a subscriber registered by `subscribe` saw of one event per tenant and a system event."
  [subscribe]
  (let [bus  (in-memory/create-in-memory-bus)
        seen (atom #{})]
    (try
      (subscribe bus #(swap! seen conj (or (:tenant-id %) :system)))
      (doseq [t [tenant-a tenant-b nil]]
        (publisher/emit! bus :orders :order/placed :orders {} {:tenant-id t}))
      (in-memory/drain! bus :orders)
      @seen
      (finally (in-memory/stop! bus)))))

(deftest ^:unit ^:security a-subscriber-in-a-tenant-sees-only-that-tenants-events
  (testing "subscribed while the tenant is bound"
    (is (= #{tenant-a :system}
           (received (fn [bus h] (binding [db/*tenant-id* tenant-a] (publisher/subscribe-for-tenant! bus nil :orders h)))))))
  (testing "subscribed for a tenant by name"
    (is (= #{tenant-b :system}
           (received (fn [bus h] (publisher/subscribe-for-tenant! bus tenant-b :orders h))))))
  (testing "subscribed outside any tenant, it opts in to every tenant's"
    (is (= #{tenant-a tenant-b :system}
           (received (fn [bus h] (publisher/subscribe-for-tenant! bus nil :orders h)))))))

(deftest ^:unit ^:security an-event-built-in-a-tenant-carries-it
  (is (= tenant-a (:tenant-id (binding [db/*tenant-id* tenant-a] (publisher/build :t :src {})))))
  (is (= tenant-b (:tenant-id (binding [db/*tenant-id* tenant-a] (publisher/build :t :src {} {:tenant-id tenant-b}))))
      "a tenant named in the context wins")
  (is (nil? (:tenant-id (binding [db/*tenant-id* tenant-a] (publisher/build :t :src {} {:tenant-id nil}))))
      "an explicit nil makes a system event")
  (is (nil? (:tenant-id (publisher/build :t :src {})))))
