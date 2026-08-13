(ns wagoe.events.adapter-surface-test
  "A deliberate sweep of the port's surface, against every adapter.

   `adapter_contract_test.clj` grew one finding at a time: five disagreements
   between the in-memory and Redis adapters were each found by review, and each
   time the case that would have caught it was the one nobody had written. This
   is the other approach — enumerate the surface rather than wait for it.

   The surface is: every protocol method, times every option, times every kind
   of value that serialises differently. A disagreement between adapters is a
   bug in whichever one is wrong; a test here says only that they must agree
   and that the documented behaviour is what they agree on."
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
      (.close pool) true)
    (catch Exception _ false)))

(defn- adapters []
  (cond-> [["in-memory" #(in-memory/create-in-memory-bus) in-memory/stop!]]
    (redis-available?)
    (conj ["redis-streams"
           #(redis-streams/create-redis-streams-bus
             (JedisPool. (JedisPoolConfig.) "localhost" 6379 2000)
             {:group (str "surface-" (subs (str (random-uuid)) 0 8))
              :min-idle-ms 100})
           redis-streams/stop!])))

(defn- wait-for
  "Poll until `f` is truthy, or give up.

   Twenty seconds, not two: these run alongside four thousand other tests
   against one Redis, and a delivery that is merely slow must not read as a
   delivery that never happened. A passing run takes milliseconds; the budget
   only matters when the machine is busy."
  [f & [ms]]
  (let [deadline (+ (System/currentTimeMillis) (or ms 20000))]
    (loop [] (or (f) (when (< (System/currentTimeMillis) deadline)
                       (Thread/sleep 25) (recur))))))

(defn- unique [] (keyword "surface" (str "t" (subs (str (random-uuid)) 0 8))))

(defn- round-trip
  "Publish `payload` and return what a subscriber received, or ::timeout.

   Unsubscribes afterwards. Each Redis subscription runs a poller that holds a
   connection for the duration of its blocking read, so leaving thirty of them
   behind starves the pool and the failures land on whichever case ran last —
   which looks exactly like a serialisation bug and is not one."
  [bus topic payload]
  (let [seen (atom nil)
        sub  (ports/subscribe! bus topic #(reset! seen %))]
    (try
      (Thread/sleep 400)
      (publisher/emit! bus topic :a/b :test payload)
      (if (wait-for #(some? @seen)) (:payload @seen) ::timeout)
      (finally (ports/unsubscribe! bus sub)))))

;; =============================================================================
;; Value types — anything that might serialise differently
;; =============================================================================

(def ^:private value-cases
  "Each is [label value]. A payload is a map, so these are its values."
  [["nil"                 nil]
   ["boolean true"        true]
   ["boolean false"       false]
   ["long"                42]
   ["negative long"       -7]
   ["max long"            Long/MAX_VALUE]
   ["double"              1.5]
   ["double with no fraction" 2.0]
   ["big integer"         (biginteger "123456789012345678901234567890")]
   ["big decimal"         1.10M]
   ["string"              "hello"]
   ["empty string"        ""]
   ["unicode string"      "héllo · 世界 · 🎉"]
   ["simple keyword"      :paid]
   ["namespaced keyword"  :order/paid]
   ["symbol"              'some-symbol]
   ["namespaced symbol"   'my.ns/thing]
   ["uuid"                (java.util.UUID/randomUUID)]
   ["instant"             (.plusNanos (java.time.Instant/now) 123456)]
   ["date"                (java.util.Date.)]
   ["vector"              [1 :two "three"]]
   ["empty vector"        []]
   ["list"                '(1 2 3)]
   ["set"                 #{:a :b}]
   ["empty set"           #{}]
   ["nested map"          {:a {:b {:c [1 #{:d}]}}}]
   ["empty map"           {}]
   ["map with keyword keys"  {:a 1 :b 2}]
   ["map with string keys"   {"a" 1 "b" 2}]
   ["map with numeric keys"  {1 :one 2 :two}]])

(def ^:private class-may-change
  "Values whose concrete class a wire format cannot preserve.

   Equality still holds — these are listed because `=` cannot see the
   difference and a consumer using `instance?` can. Anything not listed here
   must come back as the class it went in as, so a new entry is a decision
   rather than a surprise."
  {"big integer"
   (str "transit tags java.math.BigInteger and clojure.lang.BigInt alike, so "
        "the wire cannot tell them apart and BigInteger returns as BigInt. "
        "Preserving it would need a non-standard tag; the values are equal and "
        "arithmetic is unaffected.")})

(deftest ^:integration every-value-type-survives-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)]
        (try
          (doseq [[what value] value-cases]
            (let [topic (unique)
                  got   (round-trip bus topic {:v value})]
              (testing what
                (is (not= ::timeout got) (str label "/" what ": never delivered"))
                (when (not= ::timeout got)
                  (is (= value (:v got))
                      (str label "/" what ": " (pr-str value)
                           " came back as " (pr-str (:v got))))
                  (is (or (= (class value) (some-> (:v got) class))
                          (contains? class-may-change what))
                      (str label "/" what ": type changed from "
                           (some-> value class .getSimpleName) " to "
                           (some-> (:v got) class .getSimpleName)
                           " — if that is acceptable, say so in "
                           "class-may-change with the reason"))))))
          (finally (stop bus)))))))

;; =============================================================================
;; Topic forms
;; =============================================================================

(deftest ^:integration every-usable-topic-form-works-on-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            suffix (subs (str (random-uuid)) 0 8)]
        (try
          (doseq [[what topic] [["simple keyword"     (keyword (str "simple" suffix))]
                                ["namespaced keyword" (keyword (str "ns" suffix) "evt")]
                                ["string"             (str "string" suffix)]
                                ["dotted namespace"   (keyword (str "a.b" suffix) "evt")]]]
            (testing what
              (is (= {:ok what} (round-trip bus topic {:ok what}))
                  (str label "/" what ": topic form not usable"))))
          (finally (stop bus)))))))

(deftest ^:integration every-adapter-refuses-the-same-unusable-topics
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)]
        (try
          (doseq [[what topic] [["nil" nil] ["blank" ""] ["whitespace" "   "]
                                ["number" 42] ["map" {:a 1}]]]
            (testing what
              (is (= :events/invalid
                     (get-in (ports/publish! bus topic (publisher/build :a/b :test nil))
                             [:error :type]))
                  (str label "/" what ": publish accepted an unusable topic"))
              (is (= :events/invalid
                     (get-in (ports/subscribe! bus topic identity) [:error :type]))
                  (str label "/" what ": subscribe accepted an unusable topic"))))
          (finally (stop bus)))))))

;; =============================================================================
;; history — every option, and their combination
;; =============================================================================

(deftest ^:integration history-options-mean-the-same-thing-on-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique)
            stamps (atom [])]
        (try
          (doseq [n (range 5)]
            (publisher/emit! bus topic (keyword "e" (str n)) :test {:n n})
            (swap! stamps conj (java.time.Instant/now))
            (Thread/sleep 10))
          (is (wait-for #(= 5 (count (ports/history bus topic)))))

          (let [ns-of #(map (comp :n :payload) %)]
            (testing "no options: everything, oldest first"
              (is (= [0 1 2 3 4] (ns-of (ports/history bus topic)))))

            (testing "an empty options map is the same as none"
              (is (= [0 1 2 3 4] (ns-of (ports/history bus topic {})))))

            (testing ":limit takes the most recent, still oldest first"
              (is (= [4]       (ns-of (ports/history bus topic {:limit 1}))))
              (is (= [3 4]     (ns-of (ports/history bus topic {:limit 2}))))
              (is (= [0 1 2 3 4] (ns-of (ports/history bus topic {:limit 5})))))

            (testing ":limit beyond the history returns all of it"
              (is (= [0 1 2 3 4] (ns-of (ports/history bus topic {:limit 99})))))

            (testing ":limit 0 returns nothing"
              (is (= [] (ns-of (ports/history bus topic {:limit 0})))))

            (testing ":since excludes what came before it"
              ;; Taken after event 2 was published, so 0-2 are out and 3-4 in.
              (let [after-2 (nth @stamps 2)]
                (is (= [3 4] (ns-of (ports/history bus topic {:since after-2}))))))

            (testing ":since is exclusive, to the same millisecond"
              ;; The boundary, stated rather than left to timing. A consumer
              ;; saves the timestamp of the last event it handled and asks for
              ;; what came after; if `:since` included that millisecond it
              ;; would hand back the event it just processed. Redis stream ids
              ;; begin at the millisecond, so "<ms>-0" is inclusive of it —
              ;; which made this pass or fail depending on how the clock fell,
              ;; and it fell differently on CI than locally.
              (let [events (ports/history bus topic)
                    third  (nth events 2)]
                (is (= [3 4]
                       (ns-of (ports/history bus topic
                                             {:since (:published-at third)})))
                    (str label ": :since included the event at that exact instant"))))

            (testing ":since before everything returns everything"
              (is (= [0 1 2 3 4]
                     (ns-of (ports/history bus topic
                                           {:since (java.time.Instant/ofEpochMilli 0)})))))

            (testing ":since after everything returns nothing"
              (is (= [] (ns-of (ports/history bus topic
                                              {:since (.plusSeconds (java.time.Instant/now) 60)})))))

            (testing ":since and :limit together: the most recent n of what is left"
              (let [after-1 (nth @stamps 1)]
                (is (= [4]   (ns-of (ports/history bus topic {:since after-1 :limit 1}))))
                (is (= [3 4] (ns-of (ports/history bus topic {:since after-1 :limit 2})))))))

          (testing "an untouched topic is empty, not an error"
            (is (= [] (ports/history bus (unique)))))
          (finally (stop bus)))))))

;; =============================================================================
;; subscribe! / unsubscribe! lifecycle
;; =============================================================================

(deftest ^:integration subscription-lifecycle-behaves-the-same-on-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)
            topic (unique)
            a (atom 0) b (atom 0)]
        (try
          (let [sub-a (ports/subscribe! bus topic (fn [_] (swap! a inc)))
                sub-b (ports/subscribe! bus topic (fn [_] (swap! b inc)))]
            (Thread/sleep 500)

            (testing "a subscription id is a value, not an error"
              (is (some? sub-a))
              (is (not= sub-a sub-b) "two subscriptions must be distinguishable"))

            (publisher/emit! bus topic :a/b :test nil)
            (is (wait-for #(and (pos? @a) (pos? @b))))

            (testing "unsubscribing an unknown id is not an error"
              (is (nil? (ports/unsubscribe! bus "not-a-subscription"))))

            (testing "unsubscribing twice is not an error"
              (ports/unsubscribe! bus sub-b)
              (is (nil? (ports/unsubscribe! bus sub-b))))

            (testing "and after unsubscribing every handler, subscribing again works"
              ;; The Redis adapter stops its poller when the last handler goes,
              ;; so this is the path where it has to start a new one.
              (ports/unsubscribe! bus sub-a)
              (let [revived (atom 0)]
                (ports/subscribe! bus topic (fn [_] (swap! revived inc)))
                (Thread/sleep 500)
                (publisher/emit! bus topic :a/b :test nil)
                (is (wait-for #(pos? @revived))
                    (str label ": re-subscribing after the last unsubscribe delivered nothing")))))
          (finally (stop bus)))))))

(deftest ^:integration stopping-is-idempotent-on-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)]
        (ports/subscribe! bus (unique) identity)
        (stop bus)
        (is (nil? (stop bus)) (str label ": stopping twice threw"))))))

;; =============================================================================
;; Envelope fields
;; =============================================================================

(deftest ^:integration envelope-fields-round-trip-on-every-adapter
  (doseq [[label make stop] (adapters)]
    (testing label
      (let [bus (make)]
        (try
          (doseq [[what context] [["no context"        {}]
                                  ["correlation only"  {:correlation-id "c1"}]
                                  ["tenant only"       {:tenant-id "t1"}]
                                  ["both"              {:correlation-id "c2" :tenant-id "t2"}]]]
            (let [topic (unique)
                  seen  (atom nil)]
              (ports/subscribe! bus topic #(reset! seen %))
              (Thread/sleep 400)
              (let [sent (publisher/build :order/placed :orders {:n 1} context)]
                (ports/publish! bus topic sent)
                (is (wait-for #(some? @seen)) (str label "/" what ": never delivered"))
                (testing what
                  (is (= sent @seen)
                      (str label "/" what ": the envelope changed on the way through"))))))
          (finally (stop bus)))))))
