(ns wagoe.events.shell.tenant-scope-test
  "A subscriber in a tenant receives that tenant's events and system events,
   never another tenant's (BOU-947)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.events.ports]
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

(deftest ^:unit ^:security a-handler-runs-in-its-events-tenant
  ;; A handler subscribed at boot ran with no tenant bound: its writes went to
  ;; public and a push it sent reached every tenant (BOU-985)
  (let [ran (atom [])]
    (testing "with a tenant runner, through it"
      (let [bus  (in-memory/create-in-memory-bus
                  {:tenant-runner (fn [t f] (binding [db/*tenant-id* t] (swap! ran conj [:runner t]) (f)))})
            seen (atom [])]
        (try
          (publisher/subscribe-for-tenant! bus nil :orders (fn [_] (swap! seen conj db/*tenant-id*)))
          (doseq [t [tenant-a nil]]
            (publisher/emit! bus :orders :order/placed :orders {} {:tenant-id t}))
          (in-memory/drain! bus :orders)
          (is (= [tenant-a nil] @seen))
          (is (= [[:runner tenant-a]] @ran) "a system event needs no tenant")
          (finally (in-memory/stop! bus)))))
    (testing "without one, with the tenant bound"
      (let [bus  (in-memory/create-in-memory-bus)
            seen (atom nil)]
        (try
          (publisher/subscribe-for-tenant! bus nil :orders (fn [_] (reset! seen db/*tenant-id*)))
          (publisher/emit! bus :orders :order/placed :orders {} {:tenant-id tenant-b})
          (in-memory/drain! bus :orders)
          (is (= tenant-b @seen))
          (finally (in-memory/stop! bus)))))))

(deftest ^:unit ^:security history-in-a-tenant-shows-its-own-and-system-events
  (let [bus (in-memory/create-in-memory-bus)]
    (try
      (doseq [t [tenant-a tenant-b nil]]
        (publisher/emit! bus :orders :order/placed :orders {} {:tenant-id t}))
      (in-memory/drain! bus :orders)
      (is (= 3 (count (wagoe.events.ports/history bus :orders))))
      (is (= #{tenant-a :system}
             (set (map #(or (:tenant-id %) :system)
                       (binding [db/*tenant-id* tenant-a] (wagoe.events.ports/history bus :orders))))))
      (finally (in-memory/stop! bus)))))
