(ns wagoe.realtime.shell.bus.redis
  "Redis-backed IMessageBus: routing envelopes travel as Nippy-frozen bytes over
   a binary Redis pub/sub channel. publish borrows a pooled connection; the
   subscriber owns ONE dedicated connection (not from the publish pool, so a
   blocking SUBSCRIBE never starves publishers) on a daemon thread.

   Concurrency:
   - Singleton subscriber: start-subscriber! is idempotent (running? guard) so a
     node never holds two channel subscriptions (which would double-deliver).
   - Reconnect: the daemon loops with backoff, acquiring the connection INSIDE
     the loop so a Redis outage at startup retries instead of killing the daemon
     and leaving the node permanently deaf. Gap messages are missed
     (at-most-once, accepted).

   Trust boundary: onMessage thaws Nippy bytes off the channel. Envelopes are
   produced only by this framework's own publishers on a trusted Redis instance.
   Do NOT point this at a Redis shared with untrusted writers — Nippy thaw of
   attacker-controlled bytes is a deserialization risk."
  (:require [wagoe.realtime.ports :as ports]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy])
  (:import [redis.clients.jedis JedisPool JedisPoolConfig Jedis BinaryJedisPubSub HostAndPort]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.nio.charset StandardCharsets]))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn create-pool
  "Build a JedisPool from a config map. Supports auth, database selection,
   socket timeout, and pool sizing (mirrors the cache Redis adapter).

   Config keys (all optional):
     :host :port :password :database :timeout
     :max-total :max-idle :min-idle"
  ^JedisPool [{:keys [host port password database timeout max-total max-idle min-idle]}]
  (let [cfg (doto (JedisPoolConfig.)
              (.setMaxTotal (int (or max-total 8)))
              (.setMaxIdle (int (or max-idle 8)))
              (.setMinIdle (int (or min-idle 0)))
              (.setTestOnBorrow true))
        host (or host "localhost")
        port (int (or port 6379))
        timeout (int (or timeout 2000))]
    (if password
      (JedisPool. cfg ^String host port timeout ^String password (int (or database 0)))
      (JedisPool. cfg ^String host port timeout))))

(defn- make-subscriber-conn
  "Create the dedicated (non-pooled) connection the subscriber blocks on.
   Uses the HostAndPort ctor (unambiguous, reflection-free); auth/db are applied
   explicitly. The subscriber connects once then blocks indefinitely, so the
   default connect timeout is fine — :timeout still tunes the publish pool."
  ^Jedis [{:keys [host port password database]}]
  (let [j (Jedis. (HostAndPort. ^String (or host "localhost") (int (or port 6379))))]
    (when password (.auth j ^String password))
    (when (and database (pos? (int database))) (.select j (int database)))
    j))

(defrecord RedisMessageBus [^JedisPool pool conn-config channel-bytes subscribe-timeout-ms state]
  ;; state: atom of {:running? bool :pubsub BinaryJedisPubSub :thread Thread
  ;;                 :sub-conn Jedis :delivery-fn fn}
  ports/IMessageBus

  (publish [_this envelope]
    (try
      (with-open [^Jedis j (.getResource pool)]
        (.publish j ^bytes channel-bytes ^bytes (nippy/freeze envelope)))
      (catch Exception e
        (log/error e "Redis bus publish failed")))
    nil)

  (start-subscriber! [_this delivery-fn]
    (when-not (:running? @state)
      (let [latch  (CountDownLatch. 1)
            pubsub (proxy [BinaryJedisPubSub] []
                     (onSubscribe [_chan _cnt]
                       (.countDown latch))
                     (onMessage [_chan ^bytes message]
                       (try
                         (delivery-fn (nippy/thaw message))
                         (catch Exception e
                           (log/error e "Redis bus delivery failed")))))
            thread (Thread.
                    ^Runnable
                    (fn []
                      (loop [backoff 100]
                        (when (:running? @state)
                          ;; Open the dedicated connection INSIDE the try: if
                          ;; Redis is unreachable (e.g. at startup), this throws
                          ;; — catch it so the daemon keeps retrying instead of
                          ;; dying and leaving the node permanently deaf.
                          (let [^Jedis conn (try
                                              (make-subscriber-conn conn-config)
                                              (catch Exception e
                                                (when (:running? @state)
                                                  (log/warn e "Redis subscriber connect failed; retrying"))
                                                nil))]
                            (when conn
                              (swap! state assoc :sub-conn conn)
                              (try
                                (.subscribe conn
                                            ^BinaryJedisPubSub pubsub
                                            ^"[[B" (into-array (Class/forName "[B") [channel-bytes]))
                                (catch Exception e
                                  (when (:running? @state)
                                    (log/warn e "Redis subscriber dropped; reconnecting")))
                                (finally
                                  (try (.close conn) (catch Exception _ nil)))))
                            (when (:running? @state)
                              (Thread/sleep backoff)
                              (recur (min 5000 (* 2 backoff)))))))))]
        (swap! state assoc :running? true :pubsub pubsub :thread thread :delivery-fn delivery-fn)
        (.setDaemon thread true)
        (.start thread)
        ;; Wait for the subscription to go live. If Redis is down at startup the
        ;; latch won't count down within the window — don't fail init; the
        ;; background loop keeps retrying and the node becomes live once Redis is
        ;; reachable. Log so the not-yet-live state is observable.
        (when-not (.await latch (long subscribe-timeout-ms) TimeUnit/MILLISECONDS)
          (log/warn "Realtime Redis subscriber not live within"
                    subscribe-timeout-ms "ms; retrying in background"))))
    nil)

  (stop-subscriber! [_this]
    (when (:running? @state)
      (swap! state assoc :running? false)
      (let [{:keys [^BinaryJedisPubSub pubsub ^Thread thread]} @state]
        (try
          (when (and pubsub (.isSubscribed pubsub))
            (.unsubscribe pubsub))
          (catch Exception e
            (log/warn e "Redis unsubscribe failed")))
        (when thread
          (try (.join thread 2000) (catch Exception _ nil)))))
    nil)

  java.io.Closeable
  (close [this]
    (ports/stop-subscriber! this)
    (try (.close pool) (catch Exception _ nil))))

(defn create-redis-bus
  "Create a Redis message bus.

   Config keys (all optional unless noted):
     :host :port           - Redis location (default localhost:6379)
     :password :database   - auth + db selection (production)
     :timeout              - socket timeout ms (default 2000)
     :max-total :max-idle :min-idle - publish-pool sizing
     :channel              - pub/sub channel (default \"boundary:realtime:bus\")
     :subscribe-timeout-ms - how long start-subscriber! waits for the
                             subscription to go live before returning and
                             leaving the background loop to keep retrying
                             (default 5000)"
  [{:keys [channel subscribe-timeout-ms] :as config}]
  (->RedisMessageBus (create-pool config)
                     (select-keys config [:host :port :password :database :timeout])
                     (->bytes (or channel "boundary:realtime:bus"))
                     (long (or subscribe-timeout-ms 5000))
                     (atom {:running? false})))
