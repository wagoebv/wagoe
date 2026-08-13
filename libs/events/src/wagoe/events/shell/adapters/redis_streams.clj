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
           (redis.clients.jedis.params XAddParams XAutoClaimParams XPendingParams
                                       XReadGroupParams)
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
  "The Redis stream name for `topic`.

   The whole keyword, namespace included: `(name :orders/placed)` is
   \"placed\", so `:orders/placed` and `:billing/placed` would share one stream
   and each subscriber would see the other's events. The in-memory adapter keys
   on the keyword itself and keeps them apart, so this is also the adapters
   disagreeing — the same shape of bug as transit not knowing Instant."
  [prefix topic]
  (str prefix (if (keyword? topic)
                (subs (str topic) 1)          ; :orders/placed -> "orders/placed"
                (str topic))))

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

(defn- ack!
  [pool stream group ^StreamEntryID id]
  (with-open [jedis (.getResource pool)]
    (.xack jedis stream group (into-array StreamEntryID [id]))))

(defn- delivery-count
  "How many times this entry has been handed out.

   Redis tracks it per pending entry, so it survives a consumer restart —
   which a counter kept in this process would not."
  [pool stream group ^StreamEntryID id]
  (try
    (with-open [jedis (.getResource pool)]
      (some-> (.xpending jedis stream group
                         (-> (XPendingParams/xPendingParams)
                             (.start id) (.end id) (.count (int 1))))
              first
              .getDeliveredTimes))
    (catch Exception _ nil)))

(defn- dead-letter!
  "Move an entry that keeps failing out of the way.

   Retrying forever is not at-least-once, it is a stalled topic: the entry is
   re-offered every cycle, and every event behind it waits. Redis has no
   dead-letter concept, so this is an explicit stream — the event is preserved
   for someone to look at, and acknowledged so the topic moves on."
  [pool stream group handler-error ^StreamEntry entry parsed]
  (try
    (with-open [jedis (.getResource pool)]
      (.xadd jedis (str stream ":dead") (XAddParams/xAddParams)
             (java.util.HashMap. {payload-field (get (.getFields entry) payload-field)
                                  "error"       (str handler-error)
                                  "original-id" (str (.getID entry))})))
    (log/error handler-error
               "event exceeded its delivery limit; moved to the dead-letter stream"
               {:stream stream :dead (str stream ":dead") :id (:id parsed)})
    (catch Exception e
      ;; If even that fails, keep the entry pending rather than acking it away.
      (log/error e "could not dead-letter event; leaving it pending"
                 {:stream stream :id (:id parsed)})
      (throw e)))
  (ack! pool stream group (.getID entry)))

(defn- handle-entry!
  [pool stream group handler ^StreamEntry entry max-deliveries]
  (let [raw (get (.getFields entry) payload-field)
        parsed (try (decode raw)
                    (catch Exception e
                      (log/warn e "undecodable event on stream" {:stream stream})
                      nil))]
    (if-not parsed
      ;; Nothing will ever decode it, so redelivering is a loop with no exit.
      (ack! pool stream group (.getID entry))
      (try
        (handler parsed)
        ;; Acknowledged only after the handler returns. A handler that throws
        ;; leaves the entry pending, so it is redelivered rather than lost —
        ;; which is the difference between at-least-once and at-most-once, and
        ;; is decided entirely by where this line sits.
        (ack! pool stream group (.getID entry))
        (catch Throwable t
          (let [delivered (delivery-count pool stream group (.getID entry))]
            (if (and max-deliveries delivered (>= delivered max-deliveries))
              (dead-letter! pool stream group t entry parsed)
              (log/warn t "event handler threw; entry left for redelivery"
                        {:stream stream :id (:id parsed) :delivered delivered}))))))))

(defn- read-entries
  "XREADGROUP for `from` — `>` for new entries, `0` for this consumer's
   unacknowledged ones."
  [jedis stream group consumer from block-ms]
  (let [params (cond-> (-> (XReadGroupParams/xReadGroupParams) (.count (int 10)))
                 block-ms (.block (int block-ms)))]
    (->> (.xreadGroup jedis group consumer params
                      (java.util.HashMap. {stream from}))
         (mapcat #(.getValue %)))))

(defn- reclaim-abandoned!
  "Take over entries pending for a consumer that is not coming back.

   A consumer name is unique per subscription, so a process that restarts polls
   under a new name and would never see what its predecessor left unacked —
   the entries would sit pending forever, which is data loss wearing the
   costume of durability. XAUTOCLAIM moves anything idle longer than
   `min-idle-ms` to this consumer."
  [jedis stream group consumer min-idle-ms]
  (try
    (-> (.xautoclaim jedis stream group consumer (long min-idle-ms)
                     (StreamEntryID. "0-0")
                     (-> (XAutoClaimParams/xAutoClaimParams) (.count (int 10))))
        .getValue)
    (catch JedisDataException _
      ;; NOGROUP while a stream is being set up, or a Redis too old for
      ;; XAUTOCLAIM. Neither is worth ending the subscription over.
      nil)))

(defn- poll-once!
  "One cycle: reclaim, retry, then wait for new.

   Order matters. Reading only `>` — which is what this did — means an entry
   left unacknowledged by a throwing handler is never offered again, so the
   at-least-once this library promises was at-most-once in the one case that
   makes the difference."
  [pool stream group consumer handler block-ms max-deliveries min-idle-ms]
  (with-open [jedis (.getResource pool)]
    (doseq [^StreamEntry entry (reclaim-abandoned! jedis stream group consumer min-idle-ms)]
      (handle-entry! pool stream group handler entry max-deliveries))

    ;; This consumer's own unacknowledged entries: a handler that threw on the
    ;; previous cycle gets another go.
    (doseq [^StreamEntry entry (read-entries jedis stream group consumer
                                             (StreamEntryID. "0-0") nil)]
      (handle-entry! pool stream group handler entry max-deliveries))

    (doseq [^StreamEntry entry (read-entries jedis stream group consumer
                                             StreamEntryID/XREADGROUP_UNDELIVERED_ENTRY
                                             block-ms)]
      (handle-entry! pool stream group handler entry max-deliveries))))

;; =============================================================================
;; Adapter
;; =============================================================================

(defrecord RedisStreamsEventBus [pool prefix group ^ExecutorService executor subscriptions
                                 max-len max-deliveries min-idle-ms]
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
      (let [stream (stream-key prefix topic)
            id     (str (random-uuid))]
        (ensure-group! pool stream group)
        ;; One Redis consumer per topic, fanning out to every local handler.
        ;;
        ;; A consumer per `subscribe!` looks equivalent and is not: consumers
        ;; in one group *share* a stream, so Redis hands each entry to exactly
        ;; one of them. Two modules subscribing to the same topic would then
        ;; each receive roughly half the events, at random — while the port
        ;; says `subscribe!` calls the handler with each event, and the
        ;; in-memory adapter does. `:group` is for splitting work between
        ;; *processes*; within one, everybody hears everything.
        (swap! subscriptions update stream
               (fn [{:keys [handlers] :as existing}]
                 (if existing
                   (assoc existing :handlers (assoc handlers id handler))
                   {:running  (atom true)
                    :handlers {id handler}})))
        (let [{:keys [running]} (get @subscriptions stream)]
          (when (= 1 (count (:handlers (get @subscriptions stream))))
            (let [consumer (str (name group) "-" (subs (str (random-uuid)) 0 8))
                  fan-out  (fn [event]
                             (doseq [[_ h] (:handlers (get @subscriptions stream))]
                               (try
                                 (h event)
                                 (catch Throwable t
                                   ;; One handler must not stop the others, and
                                   ;; must not ack the entry either — rethrown
                                   ;; below so the event is redelivered.
                                   (log/warn t "event handler threw" {:stream stream})
                                   (throw t)))))]
              (.submit executor
                       ^Runnable
                       (fn []
                         (while @running
                           (try
                             (poll-once! pool stream group consumer fan-out 1000
                                         max-deliveries min-idle-ms)
                             (catch Exception e
                               (when @running
                                 ;; Keep polling. A Redis that is briefly
                                 ;; unreachable must not end the subscription —
                                 ;; it would come back and deliver to nobody.
                                 (log/warn e "event poll failed; retrying"
                                           {:stream stream})
                                 (Thread/sleep 500))))))))))
        id)))

  (unsubscribe! [_ subscription]
    (doseq [[stream {:keys [running handlers]}] @subscriptions]
      (when (contains? handlers subscription)
        (let [remaining (dissoc handlers subscription)]
          (if (seq remaining)
            (swap! subscriptions assoc-in [stream :handlers] remaining)
            (do
              ;; Last handler for this topic: stop polling it.
              (reset! running false)
              (swap! subscriptions dissoc stream))))))
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
            :max-len  approximate stream length to keep (default 10000)
            :max-deliveries  attempts before an event is dead-lettered
                             (default 5; nil retries forever, which stalls the
                             topic behind a poison message)
            :min-idle-ms     how long an entry must be untouched before this
                             consumer reclaims it from another (default 30000)"
  [pool & [{:keys [prefix group max-len max-deliveries min-idle-ms] :as opts}]]
  (->RedisStreamsEventBus pool
                          (or prefix "wagoe:events:")
                          (or group "wagoe")
                          (Executors/newCachedThreadPool)
                          (atom {})
                          (or max-len 10000)
                          ;; `contains?` and not `or`: an explicit nil means retry forever,
                          ;; which is a choice, and `or` would silently override it.
                          (if (contains? opts :max-deliveries) max-deliveries 5)
                          (or min-idle-ms 30000)))

(defn stop!
  "Stop every subscription and release the threads."
  [{:keys [^ExecutorService executor subscriptions]}]
  (when subscriptions
    (doseq [[_ {:keys [running]}] @subscriptions]
      (reset! running false))
    (reset! subscriptions {}))
  (when executor
    (.shutdown executor)
    (when-not (.awaitTermination executor 3 TimeUnit/SECONDS)
      (.shutdownNow executor))))
