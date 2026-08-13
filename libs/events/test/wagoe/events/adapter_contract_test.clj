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
            ;; `Instant/now`, not `ofEpochMilli`: the latter has nothing below
            ;; the millisecond, so it cannot notice a wire format that rounds
            ;; there — which is precisely what the Redis one did.
            instant (java.time.Instant/now)
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

(deftest ^:integration history-agrees-about-order-and-about-limit
  ;; `:limit` means the most recent n, and the result is oldest-first. Redis
  ;; returned the *oldest* n — XRANGE with a count counts from the beginning —
  ;; so a consumer replaying history after a restart got the start of the
  ;; stream and none of what it had just missed. Stale, silent, and only on one
  ;; backend.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")]
        (try
          (doseq [n (range 5)]
            (publisher/emit! bus topic (keyword "e" (str n)) :test {:n n})
            ;; Distinct millisecond ids, so "most recent" is unambiguous rather
            ;; than a tie broken by insertion order.
            (Thread/sleep 5))
          (is (wait-for #(= 5 (count (ports/history bus topic)))))

          (testing "unlimited history is oldest-first"
            (is (= [0 1 2 3 4]
                   (map (comp :n :payload) (ports/history bus topic)))))

          (testing "a limit takes the most recent, still oldest-first"
            (is (= [3 4]
                   (map (comp :n :payload) (ports/history bus topic {:limit 2})))
                (str label ": a limited read returned the wrong end of the stream")))

          (testing "a limit larger than the history returns all of it"
            (is (= [0 1 2 3 4]
                   (map (comp :n :payload) (ports/history bus topic {:limit 50})))))

          (testing "and an untouched topic is empty rather than an error"
            (is (= [] (ports/history bus (unique "never-used")))))
          (finally (stop bus)))))))

(deftest ^:integration an-instant-keeps-the-precision-it-was-created-with
  ;; `Instant/now` is microsecond-precision on a modern JVM. Encoding it as
  ;; epoch millis rounded every event's :published-at on the way through, so
  ;; the value a subscriber saw was never the value that was published — not an
  ;; edge case, every event. The in-memory adapter does not serialise, so it
  ;; kept the original and the two disagreed.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            seen (atom nil)
            ;; A value with something below the millisecond, whatever the
            ;; platform clock happens to offer.
            precise (.plusNanos (java.time.Instant/now) 123456)]
        (try
          (ports/subscribe! bus topic #(reset! seen %))
          (Thread/sleep 400)
          ;; Built here rather than by `emit!`, so the exact envelope that was
          ;; published is available to compare against — no guessing about what
          ;; the clock produced.
          (let [sent (publisher/build :a/b :test {:at precise})]
            (ports/publish! bus topic sent)
            (is (wait-for #(some? @seen)))

            (testing "the payload instant is unchanged"
              (is (= precise (:at (:payload @seen)))
                  (str label ": expected " precise
                       " got " (:at (:payload @seen)))))

            (testing "and so is the envelope's own timestamp"
              (is (= (:published-at sent) (:published-at @seen))
                  (str label ": :published-at changed on the way through — "
                       (:published-at sent) " became " (:published-at @seen)))))
          (finally (stop bus)))))))

(deftest ^:integration subscribing-concurrently-to-one-topic-still-delivers
  ;; Both threads could see two handlers and each conclude the other had
  ;; started the poller, leaving a topic with subscribers and nothing polling
  ;; it — indistinguishable from a broker that has stopped delivering.
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique "contract")
            received (atom 0)
            latch (java.util.concurrent.CountDownLatch. 1)
            subscribers (doall
                         (for [_ (range 8)]
                           (future
                             ;; All eight wait, then subscribe together.
                             (.await latch)
                             (ports/subscribe! bus topic (fn [_] (swap! received inc))))))]
        (try
          (.countDown latch)
          (doseq [f subscribers] (deref f 5000 nil))
          (Thread/sleep 600)

          (publisher/emit! bus topic :a/b :test {:n 1})

          (testing "the topic is being polled at all"
            (is (wait-for #(pos? @received))
                (str label ": nothing polled the topic after concurrent subscribes")))

          (testing "and every subscriber got it exactly once"
            (Thread/sleep 1500)
            (is (= 8 @received)
                (str label ": expected 8 deliveries, got " @received)))
          (finally (stop bus)))))))
