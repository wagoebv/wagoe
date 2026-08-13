(ns wagoe.events.shell.adapters.redis-streams-test
  "The Redis Streams bus against a real Redis.

   Skipped when none is reachable on localhost:6379. The in-memory tests prove
   the contract; these prove the part that only a broker can: that an event
   outlives the process that published it, and reaches one that was not running
   at the time."
  (:require [wagoe.events.shell.adapters.redis-streams :as redis-streams]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.events.ports :as ports]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [integrant.core])
  (:import (redis.clients.jedis JedisPool JedisPoolConfig)))

(defn- redis-available? []
  (try
    (let [pool (JedisPool. (JedisPoolConfig.) "localhost" 6379 500)]
      (with-open [j (.getResource pool)] (.ping j))
      (.close pool)
      true)
    (catch Exception _ false)))

(defmacro when-redis [& body]
  `(if (redis-available?)
     (do ~@body)
     (is (not (redis-available?)) "Redis not available — test skipped")))

(defn- pool [] (JedisPool. (JedisPoolConfig.) "localhost" 6379 2000))

(defn- wait-for [f & [timeout-ms]]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 8000))]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 25)
            (recur))))))

(defn- unique-topic []
  (keyword (str "test-" (subs (str (random-uuid)) 0 8))))

(deftest ^:integration an-event-published-here-arrives-there
  (when-redis
   (let [topic (unique-topic)
         p     (pool)
         bus   (redis-streams/create-redis-streams-bus p {:group (str "g-" (name topic))})
         seen  (atom [])]
     (try
       (ports/subscribe! bus topic #(swap! seen conj %))
       ;; The group is created on subscribe; give the poller a moment to be
       ;; reading before publishing, or the first event waits for the next poll.
       (Thread/sleep 300)
       (publisher/emit! bus topic :order/placed :orders {:order-id 7}
                        {:correlation-id "corr-1" :tenant-id "tenant-a"})

       (testing "the event arrives"
         (is (wait-for #(seq @seen))))

       (testing "with everything it was published with"
         (let [e (first @seen)]
           (is (= :order/placed (:type e)))
           (is (= :orders (:source e)))
           (is (= {:order-id 7} (:payload e)) "a nested payload survives the wire")
           (is (= "corr-1" (:correlation-id e)) "the trace continues across the hop")
           (is (= "tenant-a" (:tenant-id e)))))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration keywords-survive-the-broker
  ;; transit rather than JSON, for the same reason as the RPC envelope: a
  ;; consumer matching on `(= :order/placed (:type e))` must not start failing
  ;; because the value came back as a string.
  (when-redis
   (let [topic (unique-topic)
         p     (pool)
         bus   (redis-streams/create-redis-streams-bus p {:group (str "g-" (name topic))})
         seen  (atom [])]
     (try
       (ports/subscribe! bus topic #(swap! seen conj %))
       (Thread/sleep 300)
       (publisher/emit! bus topic :a/b :test {:status :paid :tags #{:new} :n 42})
       (is (wait-for #(seq @seen)))
       (let [payload (:payload (first @seen))]
         (is (= :paid (:status payload)) "a keyword value, not \"paid\"")
         (is (= #{:new} (:tags payload)) "a set, which JSON has no notion of")
         (is (= 42 (:n payload))))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration an-event-outlives-the-consumer-that-was-not-there
  ;; The reason this is Streams and not Redis pub/sub. With pub/sub an event
  ;; published while a subscriber is restarting is gone; here it is waiting.
  (when-redis
   (let [topic (unique-topic)
         p     (pool)
         bus   (redis-streams/create-redis-streams-bus p {:group (str "g-" (name topic))})]
     (try
       (publisher/emit! bus topic :happened/before :test {:n 1})

       (testing "history returns it although nobody was subscribed"
         (is (wait-for #(seq (ports/history bus topic))))
         (is (= [:happened/before] (map :type (ports/history bus topic)))))

       (testing "and a subscriber starting now still receives it"
         ;; The consumer group reads from the beginning of the stream, so
         ;; joining late is not the same as missing it.
         (let [seen (atom [])]
           (ports/subscribe! bus topic #(swap! seen conj %))
           (is (wait-for #(seq @seen))
               "an event published before the subscription was delivered")))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration a-throwing-handler-leaves-the-event-for-a-retry
  ;; At-least-once lives or dies on where the acknowledgement happens. If the
  ;; entry were acked before the handler ran, a consumer crashing mid-handler
  ;; would lose the event silently.
  (when-redis
   (let [topic    (unique-topic)
         group    (str "g-" (name topic))
         p        (pool)
         bus      (redis-streams/create-redis-streams-bus p {:group group})
         attempts (atom 0)]
     (try
       (ports/subscribe! bus topic (fn [_]
                                     (swap! attempts inc)
                                     (throw (ex-info "handler failed" {}))))
       (Thread/sleep 300)
       (publisher/emit! bus topic :a/b :test nil)
       (is (wait-for #(pos? @attempts)) "it was delivered once")

       (testing "and is still pending, not acknowledged away"
         (with-open [j (.getResource p)]
           (let [pending (.xpending j (str "wagoe:events:" (name topic)) group)]
             (is (pos? (.getTotal pending))
                 "an unacknowledged entry is what makes redelivery possible"))))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration a-publish-failure-is-returned-not-thrown
  ;; A failed analytics event must not roll back the transaction that produced
  ;; it. The caller decides, so the failure has to be a value.
  (when-redis
   (let [p   (JedisPool. (JedisPoolConfig.) "localhost" 6399 300)  ; nothing there
         bus (redis-streams/create-redis-streams-bus p {})]
     (try
       (let [result (ports/publish! bus :topic (publisher/build :a/b :test nil))]
         (is (map? result))
         (is (= :events/publish-failed (get-in result [:error :type]))))
       (finally (.close p))))))

(deftest ^:integration an-event-crosses-a-real-process-boundary
  ;; The acceptance criterion, and the one every other test here only
  ;; approximates. Two adapters in one JVM share a heap: they prove the wire
  ;; format and the consumer group, and they would keep passing if the whole
  ;; thing quietly depended on something process-local. BOU-90 shipped with
  ;; exactly that gap — a real socket, one process — so this starts a second
  ;; JVM and has it do the publishing.
  (when-redis
   (let [topic  (unique-topic)
         group  (str "g-" (name topic))
         p      (pool)
         bus    (redis-streams/create-redis-streams-bus p {:group group})
         seen   (atom [])]
     (try
       (ports/subscribe! bus topic #(swap! seen conj %))
       ;; The group must exist before the other process publishes, or the
       ;; event lands in a stream nobody is reading yet — which is a real
       ;; scenario, but a different test.
       (Thread/sleep 500)

       (let [publisher-code
             (format
              "(require '[wagoe.events.shell.adapters.redis-streams :as rs]
                        '[wagoe.events.shell.publisher :as pub]
                        '[wagoe.events.ports :as ports])
               (import '(redis.clients.jedis JedisPool JedisPoolConfig))
               (let [pool (JedisPool. (JedisPoolConfig.) \"localhost\" 6379 2000)
                     bus  (rs/create-redis-streams-bus pool {:group \"%s\"})]
                 (println :published (ports/publish! bus %s
                                                     (pub/build :order/placed :orders
                                                                {:from :another-process}
                                                                {:correlation-id \"corr-x\"})))
                 (rs/stop! bus)
                 (.close pool))"
              group (str topic))
             result (shell/sh "clojure" "-M" "-e" publisher-code)]

         (testing "the other process published successfully"
           (is (zero? (:exit result))
               (str "publisher process failed:\n" (:err result))))

         (testing "and this process received what it sent"
           (is (wait-for #(seq @seen) 20000)
               "no event arrived from the other process")
           (let [e (first @seen)]
             (is (= :order/placed (:type e)))
             (is (= {:from :another-process} (:payload e))
                 "keyword values survived a process boundary, not just a heap")
             (is (= "corr-x" (:correlation-id e))
                 "and the correlation id came with it"))))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration a-failed-handler-actually-sees-the-event-again
  ;; The at-least-once claim itself, rather than its precondition. The test
  ;; above asserts the entry stays pending, which it always did — reading only
  ;; `>` meant nothing ever offered it again, so a pending entry was a lost
  ;; event with better paperwork. That test would pass on the broken adapter.
  (when-redis
   (let [topic    (unique-topic)
         p        (pool)
         bus      (redis-streams/create-redis-streams-bus
                   p {:group (str "g-" (name topic)) :min-idle-ms 100})
         attempts (atom 0)
         handled  (promise)]
     (try
       (ports/subscribe! bus topic
                         (fn [event]
                           (if (= 1 (swap! attempts inc))
                             (throw (ex-info "first attempt fails" {}))
                             (deliver handled event))))
       (Thread/sleep 300)
       (publisher/emit! bus topic :order/placed :orders {:order-id 1})

       (testing "the second attempt receives the same event"
         (let [event (deref handled 15000 ::timeout)]
           (is (not= ::timeout event) "the event was never redelivered")
           (is (= :order/placed (:type event)))))

       (testing "and it is the same event, so a consumer can recognise it"
         (is (>= @attempts 2)))

       (testing "once handled, it stops being redelivered"
         (let [settled @attempts]
           (Thread/sleep 2500)
           (is (= settled @attempts) "an acknowledged entry came back")))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration a-restarted-consumer-picks-up-what-its-predecessor-dropped
  ;; A consumer name is unique per subscription, so a process that restarts
  ;; polls under a new one. Without XAUTOCLAIM the previous consumer's
  ;; unacknowledged entries sit pending forever — data loss dressed as
  ;; durability, and invisible because the stream still shows the event.
  (when-redis
   (let [topic  (unique-topic)
         group  (str "g-" (name topic))
         p      (pool)
         first-bus (redis-streams/create-redis-streams-bus
                    p {:group group :min-idle-ms 100})
         saw-it (atom 0)]
     (try
       ;; First consumer takes the event and never acknowledges it.
       (ports/subscribe! first-bus topic
                         (fn [_] (swap! saw-it inc) (throw (ex-info "crash" {}))))
       (Thread/sleep 300)
       (publisher/emit! first-bus topic :a/b :test {:n 1})
       (is (wait-for #(pos? @saw-it)) "the first consumer received it")
       (redis-streams/stop! first-bus)

       ;; A different consumer in the same group, as a restart produces.
       (let [second-bus (redis-streams/create-redis-streams-bus
                         p {:group group :min-idle-ms 100})
             recovered  (promise)]
         (try
           (Thread/sleep 300)
           (ports/subscribe! second-bus topic #(deliver recovered %))
           (testing "the new consumer reclaims the abandoned entry"
             (let [event (deref recovered 15000 ::timeout)]
               (is (not= ::timeout event)
                   "the entry stayed pending against a consumer that is gone")
               (is (= {:n 1} (:payload event)))))
           (finally (redis-streams/stop! second-bus))))
       (finally (.close p))))))

(deftest ^:integration an-event-that-always-fails-does-not-stall-the-topic
  ;; The other half of retrying: without a limit, a poison message is re-offered
  ;; every cycle forever and everything behind it waits. "Retries forever" is
  ;; not a stronger guarantee, it is an outage.
  (when-redis
   (let [topic    (unique-topic)
         group    (str "g-" (name topic))
         p        (pool)
         bus      (redis-streams/create-redis-streams-bus
                   p {:group group :min-idle-ms 100 :max-deliveries 2})
         poison   (atom 0)
         good     (promise)]
     (try
       (ports/subscribe! bus topic
                         (fn [event]
                           (if (= :poison (:type event))
                             (do (swap! poison inc) (throw (ex-info "always fails" {})))
                             (deliver good event))))
       (Thread/sleep 300)
       (publisher/emit! bus topic :poison :test nil)
       (publisher/emit! bus topic :fine :test {:ok true})

       (testing "the event behind the poison one still gets through"
         (is (not= ::timeout (deref good 15000 ::timeout))))

       (testing "the poison event stops being retried"
         (let [settled @poison]
           (Thread/sleep 3000)
           (is (= settled @poison) "still being redelivered after its limit")
           (is (<= settled 4) "retried far past the limit")))

       (testing "and it is kept, not discarded"
         (with-open [j (.getResource p)]
           (let [dead (.xrange j (str "wagoe:events:" (name topic) ":dead") "-" "+")]
             (is (seq dead) "the failing event should be inspectable, not gone"))))
       (finally (redis-streams/stop! bus) (.close p))))))

(deftest ^:integration configured-retry-options-reach-the-bus
  ;; `select-keys` in the module wiring dropped :max-deliveries and
  ;; :min-idle-ms, so a deployment that configured "retry forever" got the
  ;; default of five and its poison events were dead-lettered anyway — the
  ;; documentation and the behaviour disagreeing, with nothing to say so.
  (let [built (atom nil)]
    (with-redefs [redis-streams/create-redis-streams-bus
                  (fn [_pool opts] (reset! built opts) {:stub true})]
      (require 'wagoe.events.shell.module-wiring)
      (let [init (get-method integrant.core/init-key :wagoe/events)]
        (init :wagoe/events {:provider       :redis-streams
                             :host           "localhost"
                             :group          "g"
                             :max-deliveries nil
                             :min-idle-ms    250
                             :max-len        7})))

    (testing "every documented knob is passed through"
      (is (= "g" (:group @built)))
      (is (= 7 (:max-len @built)))
      (is (= 250 (:min-idle-ms @built))))

    (testing "including an explicit nil, which means retry forever"
      ;; `contains?` and not truthiness: dropping the key and passing nil are
      ;; different instructions, and only one of them is what was configured.
      (is (contains? @built :max-deliveries))
      (is (nil? (:max-deliveries @built))))))
