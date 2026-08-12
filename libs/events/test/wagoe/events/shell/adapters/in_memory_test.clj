(ns wagoe.events.shell.adapters.in-memory-test
  (:require [wagoe.events.shell.adapters.in-memory :as in-memory]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.events.ports :as ports]
            [clojure.test :refer [deftest is testing]]))

(defn- wait-for
  "Poll `f` until it is truthy or the timeout passes.

   Delivery is asynchronous on purpose, so a test that reads immediately after
   publishing is testing the scheduler rather than the bus."
  [f & [timeout-ms]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 2000))]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(defn- with-bus [f]
  (let [bus (in-memory/create-in-memory-bus)]
    (try (f bus) (finally (in-memory/stop! bus)))))

(deftest ^:unit a-subscriber-receives-what-is-published
  (with-bus
    (fn [bus]
      (let [received (atom [])]
        (ports/subscribe! bus :orders #(swap! received conj %))
        (publisher/emit! bus :orders :order/placed :orders {:order-id 1})

        (testing "the event arrives"
          (is (wait-for #(seq @received))))

        (testing "with its payload and provenance intact"
          (let [e (first @received)]
            (is (= :order/placed (:type e)))
            (is (= :orders (:source e)))
            (is (= {:order-id 1} (:payload e)))
            (is (string? (:id e)))))))))

(deftest ^:unit publishing-does-not-wait-for-the-handler
  ;; The contract says delivery is asynchronous, and this adapter honours it
  ;; even though it need not. A bus that delivered on the publisher's thread
  ;; would let callers assume the handler had finished — an assumption that
  ;; then fails against Redis, where it cannot hold.
  (with-bus
    (fn [bus]
      (let [gate (promise)
            done (atom false)]
        (ports/subscribe! bus :slow (fn [_] @gate (reset! done true)))
        (publisher/emit! bus :slow :thing/happened :test nil)
        (is (false? @done) "publish! returned before the handler finished")
        (deliver gate :go)
        (is (wait-for #(true? @done)))))))

(deftest ^:unit one-slow-or-broken-handler-does-not-stop-the-others
  (with-bus
    (fn [bus]
      (let [good (atom 0)]
        (ports/subscribe! bus :topic (fn [_] (throw (ex-info "handler is broken" {}))))
        (ports/subscribe! bus :topic (fn [_] (swap! good inc)))
        (publisher/emit! bus :topic :a/b :test nil)
        (publisher/emit! bus :topic :a/b :test nil)

        (testing "a throwing handler does not take the delivery thread with it"
          ;; Otherwise the second event is never delivered to anyone.
          (is (wait-for #(= 2 @good))))))))

(deftest ^:unit a-subscriber-only-hears-its-own-topic
  (with-bus
    (fn [bus]
      (let [orders (atom 0) users (atom 0)]
        (ports/subscribe! bus :orders (fn [_] (swap! orders inc)))
        (ports/subscribe! bus :users (fn [_] (swap! users inc)))
        (publisher/emit! bus :orders :order/placed :orders nil)
        (is (wait-for #(= 1 @orders)))
        (is (zero? @users))))))

(deftest ^:unit unsubscribing-stops-delivery-and-is-idempotent
  (with-bus
    (fn [bus]
      (let [received (atom 0)
            sub (ports/subscribe! bus :topic (fn [_] (swap! received inc)))]
        (publisher/emit! bus :topic :a/b :test nil)
        (is (wait-for #(= 1 @received)))

        (ports/unsubscribe! bus sub)
        (publisher/emit! bus :topic :a/b :test nil)
        (Thread/sleep 100)
        (is (= 1 @received) "no further delivery")

        (testing "unsubscribing twice is not an error"
          (is (nil? (ports/unsubscribe! bus sub))))))))

(deftest ^:unit a-malformed-publish-is-refused-rather-than-stored
  (with-bus
    (fn [bus]
      (testing "a blank topic"
        (is (= :events/invalid
               (get-in (ports/publish! bus "" (publisher/build :a/b :test nil)) [:error :type]))))

      (testing "an event with no id"
        (is (= :events/invalid
               (get-in (ports/publish! bus :topic {:type :a/b :source :test}) [:error :type]))))

      (testing "and nothing was written to history"
        (is (empty? (ports/history bus :topic)))))))

(deftest ^:unit history-returns-what-was-published-before-subscribing
  (with-bus
    (fn [bus]
      (publisher/emit! bus :topic :first/event :test {:n 1})
      (publisher/emit! bus :topic :second/event :test {:n 2})

      (testing "oldest first"
        (is (= [:first/event :second/event] (map :type (ports/history bus :topic)))))

      (testing "a limit takes the most recent"
        (is (= [:second/event] (map :type (ports/history bus :topic {:limit 1})))))

      (testing "and an untouched topic is empty rather than an error"
        (is (= [] (ports/history bus :never-used)))))))

(deftest ^:unit history-is-bounded
  ;; A process-local buffer with no bound is a memory leak that only appears in
  ;; the longest-running deployment.
  (let [bus (in-memory/create-in-memory-bus {:history-limit 3})]
    (try
      (dotimes [n 10] (publisher/emit! bus :topic :a/b :test {:n n}))
      (let [kept (ports/history bus :topic)]
        (is (= 3 (count kept)))
        (is (= [7 8 9] (map (comp :n :payload) kept)) "the most recent, not the first"))
      (finally (in-memory/stop! bus)))))
