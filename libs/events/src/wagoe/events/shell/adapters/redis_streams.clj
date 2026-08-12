(ns wagoe.events.shell.adapters.redis-streams
  "An event bus over Redis Streams.

   Streams rather than Redis pub/sub, and the difference is the whole reason
   this is usable. Pub/sub is fire-and-forget: a subscriber that is restarting
   when an event is published never learns it happened, and there is no way to
   find out afterwards. A stream keeps its entries, hands each consumer group
   its own cursor, and tracks what has not been acknowledged — which is what
   makes at-least-once possible at all.

   At-least-once, specifically: an event is delivered until a consumer
   acknowledges it, so a consumer that crashes mid-handler sees the event again
   when it comes back. Consumers must be idempotent. `:id` on the envelope is
   there for that — it is assigned by the publisher, so it is the same across
   redeliveries, unlike the stream entry id."
  (:require [wagoe.events.core.event :as event]
            [wagoe.events.ports :as ports]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util.concurrent Executors ExecutorService TimeUnit)
           (redis.clients.jedis StreamEntryID)
           (redis.clients.jedis.params XAddParams XReadGroupParams)
           (redis.clients.jedis.exceptions JedisDataException)
           (redis.clients.jedis.resps StreamEntry)))

;; =============================================================================
;; Wire format
;; =============================================================================

(def ^:private payload-field
  "The single stream field the envelope lives in. One field, not one per key:
   a stream entry is a flat string map, and spreading a nested payload across
   it would lose the nesting and the types both."
  "envelope")

(def ^:private write-handlers
  "transit knows java.util.Date and not java.time.Instant.

   `:published-at` is an Instant, and so is anything time-shaped a caller puts
   in a payload, so without this every publish fails with \"Not supported:
   class java.time.Instant\" — at the broker, which the in-memory adapter never
   reaches. The two adapters agreed on everything except the one thing only a
   real broker exercises."
  {java.time.Instant
   (transit/write-handler "instant" #(str (.toEpochMilli ^java.time.Instant %)))})

(def ^:private read-handlers
  "Read an Instant back as an Instant.

   Mapping it onto transit's built-in time type would hand the consumer a
   java.util.Date instead, so a round trip would change the type of a value
   nobody asked to convert."
  {"instant" (transit/read-handler #(java.time.Instant/ofEpochMilli (Long/parseLong %)))})

(defn- encode
  [event]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json {:handlers write-handlers}) event)
    (.toString out "UTF-8")))

(defn- decode
  [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json
                                {:handlers read-handlers})))

(defn- stream-key
  [prefix topic]
  (str prefix (name topic)))

;; =============================================================================
;; Consuming
;; =============================================================================

(defn- ensure-group!
  "Create the consumer group, tolerating one that already exists.

   `MKSTREAM` so a consumer can start before the first publisher — otherwise
   whichever process boots first decides whether the other one works."
  [pool stream group]
  (with-open [jedis (.getResource pool)]
    (try
      ;; "0-0" and not StreamEntryID/MINIMUM_ID, which renders as "-": valid
      ;; for XRANGE, rejected by XGROUP CREATE. Starting at 0 rather than "$"
      ;; is what lets a consumer that starts late still receive what it missed.
      (.xgroupCreate jedis stream group (StreamEntryID. "0-0") true)
      (catch JedisDataException e
        (when-not (re-find #"BUSYGROUP" (str (.getMessage e)))
          (throw e))))))

(defn- handle-entry!
  [pool stream group handler ^StreamEntry entry]
  (let [raw (get (.getFields entry) payload-field)
        parsed (try (decode raw)
                    (catch Exception e
                      (log/warn e "undecodable event on stream" {:stream stream})
                      nil))]
    (when parsed
      (try
        (handler parsed)
        ;; Acknowledged only after the handler returns. A handler that throws
        ;; leaves the entry pending, so it is redelivered rather than lost —
        ;; which is the difference between at-least-once and at-most-once, and
        ;; is decided entirely by where this line sits.
        (with-open [jedis (.getResource pool)]
          (.xack jedis stream group (into-array StreamEntryID [(.getID entry)])))
        (catch Throwable t
          (log/warn t "event handler threw; entry left unacknowledged"
                    {:stream stream :id (:id parsed)}))))))

(defn- poll-once!
  [pool stream group consumer handler block-ms]
  (with-open [jedis (.getResource pool)]
    (let [params (-> (XReadGroupParams/xReadGroupParams)
                     (.count (int 10))
                     (.block (int block-ms)))
          streams (java.util.HashMap. {stream StreamEntryID/XREADGROUP_UNDELIVERED_ENTRY})]
      (when-let [result (.xreadGroup jedis group consumer params streams)]
        (doseq [entry-list result
                ^StreamEntry entry (.getValue entry-list)]
          (handle-entry! pool stream group handler entry))))))

;; =============================================================================
;; Adapter
;; =============================================================================

(defrecord RedisStreamsEventBus [pool prefix group ^ExecutorService executor subscriptions max-len]
  ports/IEventPublisher
  (publish! [_ topic event]
    (if-let [problem (or (event/topic-problem topic) (event/event-problem event))]
      {:error {:type :events/invalid :message problem}}
      (try
        (with-open [jedis (.getResource pool)]
          (let [params (cond-> (XAddParams/xAddParams)
                         ;; Trimmed approximately: an unbounded stream is a
                         ;; disk leak, and `~` lets Redis trim on node
                         ;; boundaries rather than walking the whole stream on
                         ;; every publish.
                         max-len (.maxLen (long max-len))
                         max-len (.approximateTrimming))]
            (str (.xadd jedis (stream-key prefix topic) params
                        (java.util.HashMap. {payload-field (encode event)})))))
        (catch Exception e
          ;; Returned, not thrown: a failed analytics event should not roll
          ;; back the transaction that produced it. The caller decides.
          (log/warn e "event publish failed" {:topic topic :type (:type event)})
          {:error {:type :events/publish-failed :message (.getMessage e)}}))))

  ports/IEventSubscriber
  (subscribe! [_ topic handler]
    (if-let [problem (event/topic-problem topic)]
      {:error {:type :events/invalid :message problem}}
      (let [stream   (stream-key prefix topic)
            consumer (str (name group) "-" (subs (str (random-uuid)) 0 8))
            id       (str (random-uuid))
            running  (atom true)]
        (ensure-group! pool stream group)
        (swap! subscriptions assoc id {:running running :stream stream})
        (.submit executor
                 ^Runnable
                 (fn []
                   (while @running
                     (try
                       (poll-once! pool stream group consumer handler 1000)
                       (catch Exception e
                         (when @running
                           ;; Keep polling. A Redis that is briefly unreachable
                           ;; must not end the subscription — it would come back
                           ;; and deliver to nobody.
                           (log/warn e "event poll failed; retrying" {:stream stream})
                           (Thread/sleep 500)))))))
        id)))

  (unsubscribe! [_ subscription]
    (when-let [{:keys [running]} (get @subscriptions subscription)]
      (reset! running false))
    (swap! subscriptions dissoc subscription)
    nil)

  ports/IEventHistory
  (history [this topic] (ports/history this topic {}))
  (history [_ topic {:keys [limit since]}]
    (with-open [jedis (.getResource pool)]
      (let [;; "-" and "+" are the stream's own sentinels for first and last;
            ;; `since` becomes a millisecond id, which is exactly how Redis
            ;; orders entries.
            start   (if since (str (inst-ms since) "-0") "-")
            stream  (stream-key prefix topic)
            entries (if limit
                      (.xrange jedis stream start "+" (int limit))
                      (.xrange jedis stream start "+"))]
        (into [] (keep (fn [^StreamEntry e]
                         (try (decode (get (.getFields e) payload-field))
                              (catch Exception _ nil))))
              entries)))))

(defn create-redis-streams-bus
  "An event bus backed by Redis Streams.

   Args:
     pool - a JedisPool
     opts - :prefix   stream name prefix (default \"wagoe:events:\")
            :group    consumer group name — one per logical consumer, since a
                      group shares a cursor and each event goes to exactly one
                      member of it. Two services that both need every event
                      need two groups, not two consumers in one.
            :max-len  approximate stream length to keep (default 10000)"
  [pool & [{:keys [prefix group max-len]}]]
  (->RedisStreamsEventBus pool
                          (or prefix "wagoe:events:")
                          (or group "wagoe")
                          (Executors/newCachedThreadPool)
                          (atom {})
                          (or max-len 10000)))

(defn stop!
  "Stop every subscription and release the threads."
  [{:keys [^ExecutorService executor subscriptions]}]
  (when subscriptions
    (doseq [[_ {:keys [running]}] @subscriptions]
      (reset! running false)))
  (when executor
    (.shutdown executor)
    (when-not (.awaitTermination executor 3 TimeUnit/SECONDS)
      (.shutdownNow executor))))
