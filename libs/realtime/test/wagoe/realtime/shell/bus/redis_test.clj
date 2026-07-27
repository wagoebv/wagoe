(ns wagoe.realtime.shell.bus.redis-test
  {:kaocha.testable/meta {:integration true :redis true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.bus.redis :as rbus])
  (:import [redis.clients.jedis JedisPool Jedis]))

(defn redis-available? []
  (try
    (let [pool (JedisPool. "localhost" 6379)]
      (with-open [^Jedis j (.getResource pool)] (= "PONG" (.ping j)))
      (.close pool) true)
    (catch Exception _ false)))

(defn- await-count [a n ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (>= (count @a) n) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defmacro when-redis
  "Run body only when Redis is reachable; otherwise emit a single passing
   assertion so the test is not flagged as 'ran without assertions' on a
   Redis-less machine / CI without a Redis service."
  [& body]
  `(if (redis-available?)
     (do ~@body)
     (is (not (redis-available?)) "Redis not available — test skipped")))

(deftest ^:integration cross-bus-delivery-test
  (when-redis
   (let [chan "rt-test:bus"
         received (atom [])
         pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
         sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
     (try
       (ports/start-subscriber! sub (fn [env] (swap! received conj env) 1))
       (testing "envelope published on one bus reaches a subscriber on another"
         (ports/publish pub {:route :broadcast :target nil
                             :message {:type :x :payload {:n 1}}})
         (is (await-count received 1 2000))
         (is (= :broadcast (:route (first @received))))
         (is (= {:type :x :payload {:n 1}} (:message (first @received)))))
       (finally
         (ports/stop-subscriber! sub)
         (ports/stop-subscriber! pub))))))

(deftest ^:unit create-redis-bus-accepts-full-config-test
  ;; Config plumbing: auth/db/timeout/sizing/channel/subscribe-timeout-ms must be
  ;; accepted. Construction is lazy (no connection), so this needs no Redis.
  (testing "create-redis-bus builds from a full production config without connecting"
    (let [bus (rbus/create-redis-bus {:host "localhost" :port 6379
                                      :password "secret" :database 3 :timeout 1000
                                      :max-total 4 :max-idle 4 :min-idle 1
                                      :channel "c" :subscribe-timeout-ms 100})]
      (is (some? bus))
      (is (instance? java.io.Closeable bus))
      (.close ^java.io.Closeable bus))))

(deftest ^:integration subscriber-survives-redis-unavailable-test
  ;; Regression for the startup bug where .getResource threw OUTSIDE the loop's
  ;; try, killing the daemon and leaving the node permanently deaf even after
  ;; Redis recovered. Runs WITHOUT Redis (points at a dead port on purpose).
  (testing "subscriber keeps retrying (thread stays alive) when Redis is unreachable"
    (let [bus (rbus/create-redis-bus {:host "localhost" :port 6399 ; nothing listening
                                      :channel "rt-test:down"
                                      :subscribe-timeout-ms 300})]
      (try
        (ports/start-subscriber! bus (fn [_] 0))
        (let [st @(:state bus)]
          (is (true? (:running? st)) "still marked running after a failed connect")
          (is (.isAlive ^Thread (:thread st)) "daemon thread alive, not dead"))
        ;; Let at least one backoff/retry cycle elapse; thread must survive it.
        (Thread/sleep 300)
        (is (.isAlive ^Thread (:thread @(:state bus)))
            "daemon thread still alive after a retry cycle (did not die on connect failure)")
        (finally
          (ports/stop-subscriber! bus)))
      (is (false? (:running? @(:state bus))) "stop-subscriber! clears running?"))))

(deftest ^:integration idempotent-subscriber-test
  (when-redis
   (let [chan "rt-test:bus2"
         received (atom [])
         sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
         pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
     (try
       (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1))
       (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1)) ; no-op
       (ports/publish pub {:route :broadcast :message {:type :y}})
       (Thread/sleep 500)
       (testing "second start-subscriber! did not create a second subscription"
         (is (= 1 (count @received))))
       (finally
         (ports/stop-subscriber! sub)
         (ports/stop-subscriber! pub))))))
