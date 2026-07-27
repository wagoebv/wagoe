(ns wagoe.realtime.shell.adapters.redis-pubsub
  "Redis-backed IPubSubManager: topic subscriptions in Redis sets so they are
   visible cluster-wide.

   Keys (optionally prefixed):
     topic:{t}  -> SET of connection-id strings
     conn:{id}  -> SET of topic strings   (reverse index)

   subscribe / unsubscribe apply both SADD/SREM atomically in a MULTI/EXEC.
   No explicit DEL on topic sets: Redis auto-removes a set key when its last
   member is removed, so empty topics disappear and the check-then-act DEL race
   cannot occur."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.schema :as schema]
            [clojure.tools.logging :as log])
  (:import [redis.clients.jedis JedisPool Jedis]
           [redis.clients.jedis.params ScanParams]
           [java.util UUID]))

(defn- topic-key [prefix t]
  (str (when prefix (str prefix ":")) "topic:" t))

(defn- conn-key [prefix id]
  (str (when prefix (str prefix ":")) "conn:" id))

(defn- topic-key-pattern [prefix]
  (str (when prefix (str prefix ":")) "topic:*"))

(defn- with-redis [^JedisPool pool f]
  (with-open [^Jedis j (.getResource pool)] (f j)))

(defn- ->uuid
  "Parse a Redis set member back to a UUID, skipping (and logging) any stray
   non-UUID member instead of throwing the whole lookup."
  [s]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (log/warn "Skipping non-UUID topic subscriber member" {:member s})
      nil)))

(defn- scan-keys
  "SCAN all keys matching pattern (non-blocking, unlike KEYS). Accumulates into a
   set — SCAN may return the same key more than once across cursor iterations
   during a rehash, so dedup keeps the counts accurate."
  [^Jedis j ^String pattern]
  (let [params (doto (ScanParams.) (.match pattern) (.count (int 100)))]
    (loop [cursor "0" acc #{}]
      (let [result (.scan j cursor params)
            acc    (into acc (.getResult result))
            cursor (.getCursor result)]
        (if (= cursor "0")
          acc
          (recur cursor acc))))))

(defrecord RedisPubSubManager [^JedisPool pool prefix]
  ports/IPubSubManager

  (subscribe-to-topic [_ connection-id topic]
    (when-not (schema/valid-topic? topic)
      (throw (ex-info "Invalid topic name"
                      {:type    :validation-error
                       :topic   topic
                       :errors  (schema/explain-topic topic)})))
    (with-redis pool
      (fn [^Jedis j]
        (let [tx (.multi j)]
          (.sadd tx (topic-key prefix topic) (into-array String [(str connection-id)]))
          (.sadd tx (conn-key prefix connection-id) (into-array String [topic]))
          (.exec tx))))
    nil)

  (unsubscribe-from-topic [_ connection-id topic]
    (with-redis pool
      (fn [^Jedis j]
        (let [tx (.multi j)]
          (.srem tx (topic-key prefix topic) (into-array String [(str connection-id)]))
          (.srem tx (conn-key prefix connection-id) (into-array String [topic]))
          (.exec tx))))
    nil)

  (unsubscribe-from-all-topics [_ connection-id]
    (with-redis pool
      (fn [^Jedis j]
        (let [topics (.smembers j (conn-key prefix connection-id))]
          (when (seq topics)
            (let [tx (.multi j)]
              (doseq [t topics]
                (.srem tx (topic-key prefix t) (into-array String [(str connection-id)])))
              (.del tx (into-array String [(conn-key prefix connection-id)]))
              (.exec tx))))))
    nil)

  (get-topic-subscribers [_ topic]
    (with-redis pool
      (fn [^Jedis j]
        (into #{} (keep ->uuid) (.smembers j (topic-key prefix topic))))))

  (get-connection-subscriptions [_ connection-id]
    (with-redis pool
      (fn [^Jedis j]
        (set (.smembers j (conn-key prefix connection-id))))))

  (topic-count [_]
    (with-redis pool
      (fn [^Jedis j]
        (count (scan-keys j (topic-key-pattern prefix))))))

  (subscription-count [_]
    (with-redis pool
      (fn [^Jedis j]
        (reduce + 0 (map #(.scard j %) (scan-keys j (topic-key-pattern prefix))))))))

(defn create-redis-pubsub-manager
  "Create a Redis-backed IPubSubManager.

   Args:
     pool    - JedisPool instance
     opts    - Optional map:
               :prefix - String prefix for all Redis keys (default: nil)"
  ([pool]
   (create-redis-pubsub-manager pool {}))
  ([pool {:keys [prefix]}]
   (->RedisPubSubManager pool prefix)))
