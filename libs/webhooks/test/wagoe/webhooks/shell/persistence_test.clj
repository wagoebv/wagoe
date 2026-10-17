(ns wagoe.webhooks.shell.persistence-test
  "The store on the engine WAGOE_TEST_DB names: h2 (default), pg or mysql."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.shell.persistence :as persistence])
  (:import (java.time Instant)))

(defn- with-store [f]
  (test-db/with-db "webhooks"
    (fn [ctx]
      (persistence/create-tables! ctx)
      (persistence/create-tables! ctx)          ; the boot runs it every time
      (f (persistence/create-store ctx)))))

(defn- subscription [id tenant active?]
  (let [now (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/MILLIS)]
    {:id id :tenant-id tenant :event-pattern "orders/*" :url "https://example.com/h"
     :secret "enc:v1:x" :active? active? :description "d" :created-at now :updated-at now}))

(deftest ^:integration subscriptions-round-trip
  (with-store
    (fn [store]
      (let [s (subscription "s1" "t1" true)]
        (ports/save-subscription! store s)
        (is (= s (ports/find-subscription store "s1")))
        (is (= false (:active? (ports/update-subscription! store "s1" {:active? false}))))
        (is (= "https://example.com/other"
               (:url (ports/update-subscription! store "s1" {:url "https://example.com/other"}))))
        (is (nil? (ports/update-subscription! store "missing" {:active? true})))
        (is (true? (ports/delete-subscription! store "s1")))
        (is (nil? (ports/find-subscription store "s1")))))))

(deftest ^:integration candidates-respect-tenant-and-active
  (with-store
    (fn [store]
      (doseq [s [(subscription "global" nil true) (subscription "t1" "t1" true)
                 (subscription "t2" "t2" true) (subscription "t1-off" "t1" false)]]
        (ports/save-subscription! store s))
      (is (= #{"global" "t1"} (set (map :id (ports/candidate-subscriptions store "t1")))))
      (is (= #{"global"} (set (map :id (ports/candidate-subscriptions store nil)))))
      (testing "listing"
        (is (= #{"t1" "t1-off"} (set (map :id (ports/list-subscriptions store {:tenant-id "t1"})))))
        (is (= #{"t1-off"} (set (map :id (ports/list-subscriptions store {:active? false})))))
        (is (= 2 (count (ports/list-subscriptions store {:limit 2}))))))))

(deftest ^:integration attempts-are-logged
  (with-store
    (fn [store]
      (let [a {:id "a1" :subscription-id "s1" :tenant-id "t1" :event-id "e1" :topic "orders/placed"
               :attempt 1 :status :failed :status-code 500 :latency-ms 12 :response "oops"
               :error "Receiver answered HTTP 500"
               :created-at (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/MILLIS)}]
        (ports/record-attempt! store a)
        (ports/record-attempt! store (assoc a :id "a2" :attempt 2 :status :delivered :status-code 200))
        (ports/record-attempt! store (assoc a :id "a3" :event-id "e2"))
        (is (= 2 (ports/attempt-count store "s1" "e1")))
        (is (= 0 (ports/attempt-count store "s1" "nope")))
        (is (= a (last (ports/list-attempts store {:event-id "e1"}))))
        (is (= [:delivered :failed] (mapv :status (ports/list-attempts store {:event-id "e1"}))))))))
