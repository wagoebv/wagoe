(ns wagoe.events.core.event-test
  (:require [wagoe.events.core.event :as event]
            [clojure.test :refer [deftest is testing]]))

(def ^:private now #inst "2026-08-12T10:00:00.000-00:00")

(deftest ^:unit an-event-carries-where-it-came-from
  (let [e (event/event {:id "e1" :type :order/placed :source :orders
                        :payload {:order-id 7} :published-at now})]

    (testing "type and source are keywords whatever they arrived as"
      (is (= :order/placed (:type e)))
      (is (= :orders (:source e)))
      (is (= :order/placed (:type (event/event {:id "e" :type "order/placed"
                                                :source "orders" :payload nil
                                                :published-at now})))))

    (testing "the payload is untouched"
      (is (= {:order-id 7} (:payload e))))

    (testing "context keys are omitted when absent, not set to nil"
      ;; So a consumer can tell "not propagated" from "explicitly none".
      (is (not (contains? e :correlation-id)))
      (is (not (contains? e :tenant-id))))

    (testing "and carried when present"
      (let [e (event/event {:id "e" :type :t :source :s :payload nil
                            :published-at now :correlation-id "corr-1"
                            :tenant-id "tenant-a"})]
        (is (= "corr-1" (:correlation-id e)))
        (is (= "tenant-a" (:tenant-id e)))))))

(deftest ^:unit an-event-that-cannot-be-consumed-is-refused-before-it-is-sent
  ;; A malformed event fails at the consumer — another process, minutes later,
  ;; with nothing to say where it came from. Checking here is the difference
  ;; between a stack trace at the publisher and a mystery in a subscriber.
  (testing "a well-formed event has no problem"
    (is (nil? (event/event-problem {:id "e" :type :t :source :s :published-at now}))))

  (testing "an event with no id is refused"
    ;; At-least-once means redelivery; without an id a consumer cannot be
    ;; idempotent, so this is not cosmetic.
    (is (re-find #":id" (event/event-problem {:type :t :source :s :published-at now})))
    (is (some? (event/event-problem {:id "" :type :t :source :s :published-at now}))))

  (testing "type and source must be keywords"
    (is (some? (event/event-problem {:id "e" :type "t" :source :s :published-at now})))
    (is (re-find #"emitted it"
                 (event/event-problem {:id "e" :type :t :source nil :published-at now}))))

  (testing "and there must be a timestamp"
    (is (some? (event/event-problem {:id "e" :type :t :source :s}))))

  (testing "a non-map is refused rather than destructured"
    (is (some? (event/event-problem "not an event")))))

(deftest ^:unit a-topic-that-would-vanish-is-refused
  ;; A broker will happily create a stream called "" — the publish succeeds,
  ;; nothing consumes it, and the events are gone.
  (testing "keywords and strings are fine"
    (is (nil? (event/topic-problem :orders)))
    (is (nil? (event/topic-problem "orders"))))

  (testing "blank and nil are not"
    (is (some? (event/topic-problem "")))
    (is (some? (event/topic-problem "   ")))
    (is (some? (event/topic-problem nil))))

  (testing "nor is a non-name"
    (is (some? (event/topic-problem 42)))
    (is (some? (event/topic-problem {:a 1})))))

(deftest ^:unit a-consumer-can-recognise-a-redelivery
  ;; At-least-once is what a broker offers; exactly-once is what a consumer
  ;; builds on top of it with this.
  (let [e {:id "e1" :type :t}]
    (is (event/redelivery? #{"e1"} e))
    (is (not (event/redelivery? #{"e2"} e)))
    (is (not (event/redelivery? #{} e)))))

(deftest ^:security ^:unit an-event-never-crosses-a-tenant
  ;; A subscriber must not see another tenant's events because it happened to
  ;; listen to the same topic. That is a data leak, not an ordering bug.
  (testing "an event for this tenant matches"
    (is (event/matches-tenant? "tenant-a" {:tenant-id "tenant-a"})))

  (testing "an event for another tenant does not"
    (is (not (event/matches-tenant? "tenant-a" {:tenant-id "tenant-b"}))))

  (testing "a system event with no tenant goes to everyone"
    (is (event/matches-tenant? "tenant-a" {:type :system/started}))
    (is (event/matches-tenant? nil {:type :system/started})))

  (testing "and a tenant-scoped event is not delivered to an unscoped consumer"
    ;; The direction that leaks: nil consumer tenant must not act as a wildcard.
    (is (not (event/matches-tenant? nil {:tenant-id "tenant-a"})))))
