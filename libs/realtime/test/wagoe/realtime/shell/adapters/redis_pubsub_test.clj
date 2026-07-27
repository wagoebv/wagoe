(ns wagoe.realtime.shell.adapters.redis-pubsub-test
  "Integration tests for the Redis pub/sub manager. Require Redis on
   localhost:6379; skipped (no-op pass) when unavailable."
  {:kaocha.testable/meta {:integration true :redis true :realtime true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.adapters.redis-pubsub :as rpub])
  (:import [redis.clients.jedis JedisPool Jedis]))

(defonce ^:private availability (atom nil))

(defn redis-available? []
  (if (some? @availability)
    @availability
    (reset! availability
            (try
              (let [pool (JedisPool. "localhost" 6379)]
                (with-open [^Jedis j (.getResource pool)]
                  (= "PONG" (.ping j)))
                (.close pool)
                true)
              (catch Exception _ false)))))

(def ^:dynamic *mgr* nil)
(def ^:dynamic *pool* nil)

(defn with-mgr [f]
  (if (redis-available?)
    (let [pool (JedisPool. "localhost" 6379)
          mgr (rpub/create-redis-pubsub-manager pool {:prefix "rt-test"})]
      (binding [*mgr* mgr *pool* pool]
        (try (f)
             (finally
               (with-open [^Jedis j (.getResource pool)]
                 (.flushDB j))
               (.close pool)))))
    (f)))

(use-fixtures :each with-mgr)

(defmacro when-redis
  "Run body only when Redis is reachable; otherwise emit a single passing
   assertion so the test is not flagged as 'ran without assertions' on a
   Redis-less machine / CI without a Redis service."
  [& body]
  `(if (redis-available?)
     (do ~@body)
     (is (not (redis-available?)) "Redis not available — test skipped")))

(deftest ^:integration subscribe-get-unsubscribe-roundtrip-test
  (when-redis
   (let [c1 (java.util.UUID/randomUUID)
         c2 (java.util.UUID/randomUUID)]
     (ports/subscribe-to-topic *mgr* c1 "order:1")
     (ports/subscribe-to-topic *mgr* c2 "order:1")
     (testing "subscribers parsed back to UUIDs"
       (is (= #{c1 c2} (ports/get-topic-subscribers *mgr* "order:1"))))
     (testing "reverse index"
       (is (= #{"order:1"} (ports/get-connection-subscriptions *mgr* c1))))
     (testing "missing topic -> empty set"
       (is (= #{} (ports/get-topic-subscribers *mgr* "nope"))))
     (testing "last-member unsubscribe auto-removes the key (no manual DEL)"
       (ports/unsubscribe-from-topic *mgr* c1 "order:1")
       (ports/unsubscribe-from-topic *mgr* c2 "order:1")
       (is (= #{} (ports/get-topic-subscribers *mgr* "order:1")))
       (with-open [^Jedis j (.getResource *pool*)]
         (is (false? (.exists j "rt-test:topic:order:1"))))))))

(deftest ^:integration unsubscribe-all-test
  (when-redis
   (let [c (java.util.UUID/randomUUID)]
     (ports/subscribe-to-topic *mgr* c "a")
     (ports/subscribe-to-topic *mgr* c "b")
     (ports/unsubscribe-from-all-topics *mgr* c)
     (is (= #{} (ports/get-connection-subscriptions *mgr* c)))
     (is (= #{} (ports/get-topic-subscribers *mgr* "a"))))))

(deftest ^:integration topic-and-subscription-counts-test
  ;; Exercises the SCAN-based counters (replacing the old blocking KEYS).
  (when-redis
   (let [c1 (java.util.UUID/randomUUID)
         c2 (java.util.UUID/randomUUID)]
     (ports/subscribe-to-topic *mgr* c1 "room:1")
     (ports/subscribe-to-topic *mgr* c2 "room:1")
     (ports/subscribe-to-topic *mgr* c1 "room:2")
     (testing "topic-count counts distinct topics with subscribers"
       (is (= 2 (ports/topic-count *mgr*))))
     (testing "subscription-count sums members across topics"
       (is (= 3 (ports/subscription-count *mgr*)))))))
