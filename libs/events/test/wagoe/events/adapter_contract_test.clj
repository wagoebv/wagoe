(ns wagoe.events.adapter-contract-test
  "Behaviour every adapter must share, checked against each of them.

   Two adapters have now disagreed twice, both times in a way only the broker
   could reveal: transit did not know `java.time.Instant`, and the Redis stream
   key dropped a keyword's namespace so `:orders/placed` and `:billing/placed`
   were the same topic. Both passed every in-memory test.

   Anything that must hold regardless of backend belongs here, run against all
   of them, rather than in one adapter's suite where the other is free to
   differ."
  (:require [wagoe.events.shell.adapters.in-memory :as in-memory]
            [wagoe.events.shell.adapters.redis-streams :as redis-streams]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.events.ports :as ports]
            [clojure.test :refer [deftest is testing]])
  (:import (redis.clients.jedis JedisPool JedisPoolConfig)))

(defn- redis-available? []
  (try
    (let [pool (JedisPool. (JedisPoolConfig.) "localhost" 6379 500)]
      (with-open [j (.getResource pool)] (.ping j))
      (.close pool)
      true)
    (catch Exception _ false)))

(defn- adapters
  "Every adapter available here, as [label make stop] triples.

   Redis is included only when reachable; the in-memory one always runs, so the
   contract is never entirely unchecked."
  []
  (cond-> [["in-memory"
            (fn [] (in-memory/create-in-memory-bus))
            in-memory/stop!]]
    (redis-available?)
    (conj ["redis-streams"
           (fn [] (redis-streams/create-redis-streams-bus
                   (JedisPool. (JedisPoolConfig.) "localhost" 6379 2000)
                   {:group (str "contract-" (subs (str (random-uuid)) 0 8))
                    :min-idle-ms 100}))
           redis-streams/stop!])))

(defn- wait-for [f & [timeout-ms]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 8000))]
    (loop []
      (or (f) (when (< (System/currentTimeMillis) deadline)
                (Thread/sleep 25) (recur))))))

(defn- unique [base] (keyword base (str "e" (subs (str (random-uuid)) 0 8))))

(deftest ^:integration topics-that-differ-only-by-namespace-are-different-topics
  ;; `(name :orders/placed)` is "placed". Deriving the Redis stream key from it
  ;; put `:orders/placed` and `:billing/placed` in one stream, so each
  ;; subscriber saw the other's events — a cross-module leak that the in-memory
  ;; adapter, which keys on the keyword, never had.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            suffix (subs (str (random-uuid)) 0 8)
            orders  (keyword "orders" suffix)
            billing (keyword "billing" suffix)
            to-orders  (atom [])
            to-billing (atom [])]
        (try
          (ports/subscribe! bus orders  #(swap! to-orders conj %))
          (ports/subscribe! bus billing #(swap! to-billing conj %))
          (Thread/sleep 400)

          (publisher/emit! bus orders :order/placed :orders {:which :orders})
          (is (wait-for #(seq @to-orders)) "the orders subscriber heard nothing")

          (testing "the other topic holds nothing"
            ;; Asserted on history rather than on the second subscriber. Both
            ;; subscriptions share one consumer group, so if the topics did
            ;; collide the single stream's entry would go to exactly one of
            ;; them — and which one is luck. History reads the stream itself,
            ;; so it answers the question that was actually asked.
            (is (empty? (ports/history bus billing))
                (str label ": both topics resolve to the same stream")))

          (testing "and the other subscriber was not delivered it"
            (Thread/sleep 1500)
            (is (empty? @to-billing)
                (str label ": events leaked between topics sharing a name")))
          (finally (stop bus)))))))

(deftest ^:integration a-payload-comes-back-as-what-went-in
  ;; The Instant bug in shape: values that survive a heap do not necessarily
  ;; survive a wire, and the in-memory adapter cannot tell you which is which.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            seen (atom nil)
            instant (java.time.Instant/ofEpochMilli 1786500000000)
            payload {:status   :paid                ; keyword value
                     :tags     #{:new :urgent}      ; a set
                     :count    42
                     :ratio    1.5
                     :when     instant              ; java.time, not java.util
                     :nested   {:deep {:vector [1 :two "three"]}}
                     :nothing  nil}]
        (try
          (ports/subscribe! bus topic #(reset! seen %))
          (Thread/sleep 400)
          (publisher/emit! bus topic :thing/happened :test payload)
          (is (wait-for #(some? @seen)))
          (is (= payload (:payload @seen))
              (str label ": the payload changed on the way through"))
          (finally (stop bus)))))))

(deftest ^:integration provenance-survives-whichever-adapter-carries-it
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            seen (atom nil)]
        (try
          (ports/subscribe! bus topic #(reset! seen %))
          (Thread/sleep 400)
          (publisher/emit! bus topic :order/placed :orders {:n 1}
                           {:correlation-id "corr-9" :tenant-id "tenant-b"})
          (is (wait-for #(some? @seen)))
          (let [e @seen]
            (is (= :order/placed (:type e)))
            (is (= :orders (:source e)) "source is a keyword, not a string")
            (is (= "corr-9" (:correlation-id e)))
            (is (= "tenant-b" (:tenant-id e)))
            (is (string? (:id e)))
            (is (inst? (:published-at e)) "timestamps must round-trip as instants"))
          (finally (stop bus)))))))

(deftest ^:integration a-malformed-publish-is-refused-by-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)]
        (try
          (is (= :events/invalid
                 (get-in (ports/publish! bus "" (publisher/build :a/b :test nil))
                         [:error :type]))
              (str label ": a blank topic was accepted"))
          (is (= :events/invalid
                 (get-in (ports/publish! bus :topic {:type :a/b :source :test})
                         [:error :type]))
              (str label ": an event with no id was accepted"))
          (finally (stop bus)))))))

(deftest ^:integration every-subscriber-to-a-topic-receives-every-event
  ;; The port says `subscribe!` calls the handler with each event. Redis
  ;; consumers in one group *share* a stream — each entry goes to exactly one —
  ;; so a consumer per subscription meant two modules listening to the same
  ;; topic each got roughly half the events, at random. The in-memory adapter
  ;; fanned out, so this is the third way the two have disagreed.
  ;;
  ;; The earlier topic test has two subscribers on *different* topics, which is
  ;; why it did not catch this.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            a (atom []) b (atom []) c (atom [])]
        (try
          (ports/subscribe! bus topic #(swap! a conj %))
          (ports/subscribe! bus topic #(swap! b conj %))
          (ports/subscribe! bus topic #(swap! c conj %))
          (Thread/sleep 500)

          (publisher/emit! bus topic :order/placed :orders {:n 1})

          (testing "all three handlers see it, not one of them"
            (is (wait-for #(and (seq @a) (seq @b) (seq @c)))
                (str label ": delivered to "
                     (count (filter seq [@a @b @c])) " of 3 subscribers")))

          (testing "and exactly once each"
            (Thread/sleep 1500)
            (is (= [1 1 1] [(count @a) (count @b) (count @c)])
                (str label ": duplicate delivery to a local subscriber")))
          (finally (stop bus)))))))

(deftest ^:integration unsubscribing-one-handler-leaves-the-others
  ;; Sharing one Redis consumer per topic makes this worth stating: stopping
  ;; the poller when any handler goes would silently deafen the rest.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            kept (atom []) dropped (atom [])
            _ (ports/subscribe! bus topic #(swap! kept conj %))
            sub (ports/subscribe! bus topic #(swap! dropped conj %))]
        (try
          (Thread/sleep 500)
          (ports/unsubscribe! bus sub)
          (publisher/emit! bus topic :a/b :test {:n 1})

          (testing "the remaining handler still receives events"
            (is (wait-for #(seq @kept))
                (str label ": unsubscribing one handler stopped the others")))

          (testing "and the unsubscribed one does not"
            (Thread/sleep 1000)
            (is (empty? @dropped)))
          (finally (stop bus)))))))

(deftest ^:integration a-failing-handler-does-not-deafen-the-others
  ;; A handler that throws must not cost its neighbours the event. Raising from
  ;; inside the fan-out loop skipped every handler after the failing one, and
  ;; redelivery did not save them — once the entry is dead-lettered they have
  ;; missed it permanently.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            before (atom 0) after (atom 0)]
        (try
          ;; Subscription order is not guaranteed, so both a handler
          ;; registered before and one after the failing one are checked.
          (ports/subscribe! bus topic (fn [_] (swap! before inc)))
          (ports/subscribe! bus topic (fn [_] (throw (ex-info "this one fails" {}))))
          (ports/subscribe! bus topic (fn [_] (swap! after inc)))
          (Thread/sleep 500)

          (publisher/emit! bus topic :a/b :test {:n 1})

          (testing "both working handlers receive it"
            (is (wait-for #(and (pos? @before) (pos? @after)))
                (str label ": a throwing handler stopped its neighbours "
                     "(before=" @before " after=" @after ")")))
          (finally (stop bus)))))))
