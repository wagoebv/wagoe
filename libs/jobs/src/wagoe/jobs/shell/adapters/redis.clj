(ns wagoe.jobs.shell.adapters.redis
  "Redis-backed job queue implementation.

   Uses Redis for distributed job queuing with the following Redis data structures:
   - Sorted Sets: For scheduled jobs (scored by execute-at timestamp)
   - Lists: For priority queues (critical, high, normal, low)
   - Hashes: For job data storage
   - Sets: For tracking workers

   This adapter provides production-grade job queuing with:
   - Distributed queue across multiple workers
   - Priority-based job processing
   - Scheduled job execution
   - Job persistence
   - Atomic operations"
  (:require [wagoe.jobs.ports :as ports]
            [wagoe.jobs.core.job :as job]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:import [redis.clients.jedis Jedis JedisPool JedisPoolConfig]
           [redis.clients.jedis.params ScanParams]
           [java.time Instant]))

;; =============================================================================
;; Redis Key Management
;; =============================================================================

(defn- job-key
  "Generate Redis key for job data."
  [job-id]
  (str "job:" job-id))

(defn- queue-key
  "Generate Redis key for queue list."
  [queue-name]
  (str "queue:" (name queue-name)))

(def ^:private priorities
  "Highest first — the order dequeue and peek consider them in."
  [:critical :high :normal :low])

(defn- priority-key
  "The list a job of `priority` lives on. `:normal` is the bare queue key."
  [queue-key priority]
  (if (= :normal priority)
    queue-key
    (str queue-key ":" (name priority))))

(defn- scheduled-key
  "Generate Redis key for scheduled jobs sorted set."
  []
  "jobs:scheduled")

(defn- dead-letter-key
  "Generate Redis key for dead letter queue."
  []
  "jobs:failed")

(defn- global-stats-key
  "Redis key for global job counters."
  []
  "jobs:stats:global")

(defn- queue-stats-key
  "Redis key for queue-specific counters."
  [queue-name]
  (str "jobs:stats:queue:" (name queue-name)))

(defn- workers-key
  "Redis key for active worker IDs."
  []
  "jobs:workers")

(defn- worker-key
  "Redis key for per-worker heartbeat metadata."
  [worker-id]
  (str "jobs:worker:" worker-id))

(defn- processing-key
  "Redis key for a worker's in-flight (processing) list for a queue.
   Job ids live here between dequeue and ack; reclaim drains dead workers'."
  [queue-name worker-id]
  (str "jobs:processing:" (name queue-name) ":" worker-id))

;; =============================================================================
;; Redis Key Scanning (non-blocking alternative to KEYS)
;; =============================================================================

(defn- scan-keys
  "Return all keys matching pattern using cursor-based SCAN.

   Unlike KEYS, SCAN is non-blocking and safe for production use.
   Iterates in batches of 100 until the full keyspace is scanned.

   Args:
     redis   - Jedis connection
     pattern - Glob-style key pattern (e.g. \"job:*\")

   Returns:
     Vector of matching key strings"
  [^Jedis redis pattern]
  (let [params (doto (ScanParams.) (.match pattern) (.count (int 100)))]
    (loop [cursor "0" acc []]
      (let [result (.scan redis cursor params)
            keys   (.getResult result)
            next   (.getCursor result)
            acc'   (into acc keys)]
        (if (= next "0")
          acc'
          (recur next acc'))))))

;; =============================================================================
;; Job Serialization
;; =============================================================================

(defn- serialize-job
  "Serialize job to JSON string."
  [job]
  (json/generate-string
   (-> job
       (update :execute-at #(when % (.toEpochMilli %)))
       (update :created-at #(.toEpochMilli %))
       (update :updated-at #(.toEpochMilli %))
       (update :started-at #(when % (.toEpochMilli %)))
       (update :completed-at #(when % (.toEpochMilli %))))))

(defn- deserialize-job
  "Deserialize job from JSON string.

   JSON round-trips keyword VALUES as plain strings, so the keyword-typed
   fields (:job-type :status :queue :priority) must be restored explicitly —
   without this every keyword comparison downstream (status filters, terminal
   checks, history lookups) silently misses."
  [json-str]
  (when json-str
    (-> (json/parse-string json-str true)
        (update :id #(java.util.UUID/fromString %))
        (update :job-type #(when % (keyword %)))
        (update :status #(when % (keyword %)))
        (update :queue #(when % (keyword %)))
        (update :priority #(when % (keyword %)))
        (update :execute-at #(when % (Instant/ofEpochMilli %)))
        (update :created-at #(Instant/ofEpochMilli %))
        (update :updated-at #(Instant/ofEpochMilli %))
        (update :started-at #(when % (Instant/ofEpochMilli %)))
        (update :completed-at #(when % (Instant/ofEpochMilli %)))
        ;; :error :type is a keyword too (ADR-036 §3), and it round-tripped as a
        ;; string — so a consumer matching :no-handler matched against the
        ;; in-memory store and missed against Redis. Jobs stored before the
        ;; migration carry the old spelling ("NoHandlerError" -> :NoHandlerError);
        ;; they were strings before this and are keywords of the old name now,
        ;; which is no worse and is at least one type.
        (update :error #(when % (cond-> % (:type %) (update :type keyword)))))))

;; =============================================================================
;; Redis Operations
;; =============================================================================

(defn- with-redis
  "Execute function with Redis connection from pool."
  [^JedisPool pool f]
  (with-open [^Jedis redis (.getResource pool)]
    (f redis)))

(defn- parse-long-safe
  [v]
  (if v
    (Long/parseLong (str v))
    0))

(defn register-worker!
  "Register worker as active and initialize heartbeat metadata.
   No-op for non-Redis queues."
  [queue worker-id queue-name]
  (when (and (map? queue) (instance? JedisPool (:pool queue)))
    (with-redis (:pool queue)
      (fn [^Jedis redis]
        (let [worker-id-str (str worker-id)
              now-ms (.toEpochMilli (Instant/now))
              w-key (worker-key worker-id-str)]
          (.sadd redis (workers-key) (into-array String [worker-id-str]))
          (.hset redis w-key "worker-id" worker-id-str)
          (.hset redis w-key "queue-name" (name queue-name))
          (.hset redis w-key "last-heartbeat-ms" (str now-ms))
          (.expire redis w-key 60)
          true)))))

(defn heartbeat-worker!
  "Refresh worker heartbeat metadata.
   No-op for non-Redis queues."
  [queue worker-id]
  (when (and (map? queue) (instance? JedisPool (:pool queue)))
    (with-redis (:pool queue)
      (fn [^Jedis redis]
        (let [worker-id-str (str worker-id)
              now-ms (.toEpochMilli (Instant/now))
              w-key (worker-key worker-id-str)]
          (.hset redis w-key "last-heartbeat-ms" (str now-ms))
          (.expire redis w-key 60)
          true)))))

(defn unregister-worker!
  "Unregister worker from active worker set and remove metadata.
   No-op for non-Redis queues."
  [queue worker-id]
  (when (and (map? queue) (instance? JedisPool (:pool queue)))
    (with-redis (:pool queue)
      (fn [^Jedis redis]
        (let [worker-id-str (str worker-id)
              w-key (worker-key worker-id-str)]
          (.srem redis (workers-key) (into-array String [worker-id-str]))
          (.del redis (into-array String [w-key]))
          true)))))

;; =============================================================================
;; Job Queue Implementation
;; =============================================================================

;; Forward declaration for use in protocol implementation
(declare process-scheduled-jobs-internal!)

(defrecord RedisJobQueue [^JedisPool pool]
  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-id (:id job)
              job-key (job-key job-id)
              queue-key (queue-key queue-name)
              serialized (serialize-job job)]

          ;; Store job data
          (.set redis job-key serialized)

          ;; Add to appropriate queue based on priority and execute-at
          (if (:execute-at job)
            ;; Scheduled job: add to sorted set with execute-at as score
            (.zadd redis (scheduled-key)
                   (double (.toEpochMilli (:execute-at job)))
                   (str job-id))

            ;; Immediate job: add to priority queue. LPUSH for every priority,
            ;; because dequeue is RPOPLPUSH — RPUSH on :low made it the one
            ;; priority that ran newest-first.
            (.lpush redis (priority-key queue-key (:priority job :normal))
                    (into-array String [(str job-id)])))

          (log/info "Enqueued job" {:job-id job-id :queue queue-name :priority (:priority job)})
          job-id))))

  (schedule-job! [this queue-name job execute-at]
    (let [scheduled-job (assoc job :execute-at execute-at)]
      (ports/enqueue-job! this queue-name scheduled-job)))

  (dequeue-job! [_ queue-name worker-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)
              proc-key  (processing-key queue-name worker-id)
              ;; Atomically move the next job into the worker's processing list
              ;; (RPOPLPUSH) so a crash between dequeue and ack cannot lose it.
              ;; Try priority queues in order.
              job-id (some #(.rpoplpush redis (priority-key queue-key %) proc-key)
                           priorities)]

          (when job-id
            (let [job-key (job-key (java.util.UUID/fromString job-id))
                  job-data (.get redis job-key)]
              (when job-data
                (deserialize-job job-data))))))))

  (ack-job! [_ queue-name worker-id job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (.lrem redis (processing-key queue-name worker-id) 0 (str job-id))
        true)))

  (reclaim-abandoned-jobs! [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key     (queue-key queue-name)
              proc-keys     (scan-keys redis (str "jobs:processing:" (name queue-name) ":*"))
              worker-alive? (fn [^String proc-key]
                              (let [worker-id (subs proc-key (inc (.lastIndexOf proc-key ":")))]
                                (pos? (.exists redis (into-array String [(worker-key worker-id)])))))
              drain!        (fn [proc-key]
                              ;; Move every stranded job id back onto the ready queue.
                              (loop [moved 0]
                                (if (.rpoplpush redis proc-key queue-key)
                                  (recur (inc moved))
                                  moved)))]
          (reduce
           (fn [acc proc-key]
             (if (worker-alive? proc-key)
               acc
               (let [n (drain! proc-key)]
                 (when (pos? n)
                   (log/warn "Reclaimed jobs from abandoned worker"
                             {:processing-key proc-key :count n}))
                 (update acc :reclaimed + n))))
           {:reclaimed 0}
           proc-keys)))))

  (peek-job [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        ;; Every priority, in the order dequeue takes them, and the tail of the
        ;; list, which is the end RPOPLPUSH pops from. Reading only the :normal
        ;; list meant peek reported an empty queue while a critical job sat in
        ;; it, and disagreed with the very next dequeue.
        (let [queue-key (queue-key queue-name)
              job-ids (some (fn [p]
                              (seq (.lrange redis (priority-key queue-key p) -1 -1)))
                            priorities)]
          (when-let [job-id (first job-ids)]
            (let [job-key (job-key (java.util.UUID/fromString job-id))
                  job-data (.get redis job-key)]
              (when job-data
                (deserialize-job job-data))))))))

  (delete-job! [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              result (.del redis (into-array String [job-key]))]
          ;; Also remove from scheduled set if present.
          ;; Jedis zrem is (String key, String... members) — varargs need an array.
          (.zrem redis (scheduled-key) (into-array String [(str job-id)]))
          ;; And off the ready queues. Deleting only the job data left the id in
          ;; its list: it still counted towards queue-size, and the dequeue that
          ;; reached it found no data and returned nil — so the work queued
          ;; behind it waited for a poll that may not come.
          (doseq [queue-name (ports/list-queues (->RedisJobQueue pool))
                  priority   priorities]
            (.lrem redis (priority-key (queue-key queue-name) priority) 0 (str job-id)))
          (pos? result)))))

  (queue-size [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)]
          (+ (.llen redis (str queue-key ":critical"))
             (.llen redis (str queue-key ":high"))
             (.llen redis queue-key)
             (.llen redis (str queue-key ":low")))))))

  (list-queues [_this]
    (with-redis pool
      (fn [^Jedis redis]
        ;; One entry per queue: a queue with work at more than one priority has
        ;; a key per priority — "queue:default" and "queue:default:critical" —
        ;; and both name the same queue.
        (->> (scan-keys redis "queue:*")
             (map #(second (re-find #"queue:([^:]+)" %)))
             (filter some?)
             (map keyword)
             distinct
             vec))))

  (process-scheduled-jobs! [this]
    (process-scheduled-jobs-internal! this)))

;; =============================================================================
;; Scheduled Job Processor
;; =============================================================================

(defn- process-scheduled-jobs-internal!
  "Move scheduled jobs that are due to execution queues.

   This should be called periodically (e.g., every 5 seconds) by a worker.

   Args:
     queue - RedisJobQueue instance

   Returns:
     Number of jobs moved to execution queues"
  [^RedisJobQueue queue]
  (with-redis (:pool queue)
    (fn [^Jedis redis]
      (let [now (Instant/now)
            now-ms (.toEpochMilli now)
            ;; Get all jobs with score <= now
            due-job-ids (.zrangeByScore redis (scheduled-key) 0.0 (double now-ms))
            moved (atom 0)]

        (doseq [job-id-str due-job-ids]
          ;; Atomic claim: ZREM removes the member and returns the number removed.
          ;; With multiple workers polling concurrently, only the one whose ZREM
          ;; returns 1 owns this job; the others get 0 and skip it — so a scheduled
          ;; job is promoted to an execution queue exactly once across all workers.
          (when (pos? (.zrem redis (scheduled-key) (into-array String [job-id-str])))
            (let [job-id (java.util.UUID/fromString job-id-str)
                  job-key (job-key job-id)
                  job-data (.get redis job-key)]
              (if job-data
                (let [job (deserialize-job job-data)
                      queue-name (:queue job)
                      queue-key (queue-key queue-name)]
                  (.lpush redis (priority-key queue-key (:priority job :normal))
                          (into-array String [job-id-str]))
                  (swap! moved inc)
                  (log/debug "Moved scheduled job to execution queue"
                             {:job-id job-id :queue queue-name}))
                ;; Claimed the slot but the job data is gone — nothing to enqueue.
                (log/warn "Claimed due scheduled job has no stored data; skipping"
                          {:job-id job-id-str})))))

        @moved))))

;; =============================================================================
;; Job Store Implementation
;; =============================================================================

(defrecord RedisJobStore [^JedisPool pool]
  ports/IJobStore

  (save-job! [_ job]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key (:id job))
              serialized (serialize-job job)]
          (.set redis job-key serialized)
          ;; Set expiration: keep completed jobs for 7 days
          (when (#{:completed :failed :cancelled} (:status job))
            (.expire redis job-key (int (* 7 24 60 60))))
          job))))

  (find-job [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (deserialize-job job-data))))))

  (update-job-status! [_ job-id status result]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (let [job (deserialize-job job-data)
                  now (Instant/now)
                  updated-job (case status
                                :running (job/start-job job now)
                                :completed (job/complete-job job result now)
                                :failed (job/fail-job job result now)
                                :cancelled (job/cancel-job job now)
                                job)
                  serialized (serialize-job updated-job)]
              (.set redis job-key serialized)

              ;; If job failed and no more retries, move to dead letter queue
              (when (and (= status :failed) (not (job/can-retry? updated-job)))
                (.lpush redis (dead-letter-key) (into-array String [(str job-id)])))

              ;; Track processed counters only for terminal outcomes.
              (let [queue-name (:queue updated-job)]
                (cond
                  (= :completed (:status updated-job))
                  (do
                    (.hincrBy redis (global-stats-key) "total-processed" 1)
                    (.hincrBy redis (global-stats-key) "total-succeeded" 1)
                    (.hincrBy redis (queue-stats-key queue-name) "processed-total" 1)
                    (.hincrBy redis (queue-stats-key queue-name) "succeeded-total" 1))

                  (= :failed (:status updated-job))
                  (do
                    (.hincrBy redis (global-stats-key) "total-processed" 1)
                    (.hincrBy redis (global-stats-key) "total-failed" 1)
                    (.hincrBy redis (queue-stats-key queue-name) "processed-total" 1)
                    (.hincrBy redis (queue-stats-key queue-name) "failed-total" 1))))

              updated-job))))))

  (find-jobs [_ filters]
    (with-redis pool
      (fn [^Jedis redis]
        (->> (scan-keys redis "job:*")
             (map (fn [key]
                    (let [job-data (.get redis key)]
                      (when job-data
                        (deserialize-job job-data)))))
             (filter some?)
             (filter (fn [job]
                       (and (or (nil? (:status filters))
                                (= (:status filters) (:status job)))
                            (or (nil? (:job-type filters))
                                (= (:job-type filters) (:job-type job)))
                            (or (nil? (:queue filters))
                                (= (:queue filters) (:queue job))))))
             vec))))

  (failed-jobs [_ limit]
    (with-redis pool
      (fn [^Jedis redis]
        (let [failed-job-ids (.lrange redis (dead-letter-key) 0 (dec limit))]
          (->> failed-job-ids
               (map (fn [job-id-str]
                      (let [job-id (java.util.UUID/fromString job-id-str)
                            job-key (job-key job-id)
                            job-data (.get redis job-key)]
                        (when job-data
                          (deserialize-job job-data)))))
               (filter some?)
               vec)))))

  (retry-job! [_ job-id]
    (with-redis pool
      (fn [^Jedis redis]
        (let [job-key (job-key job-id)
              job-data (.get redis job-key)]
          (when job-data
            (let [job (deserialize-job job-data)
                  retry-config {:backoff-strategy :exponential
                                :initial-delay-ms 1000
                                :max-delay-ms 60000}
                  now (Instant/now)
                  jitter-ms (rand-int 100)
                  retry-job (job/prepare-retry job retry-config now jitter-ms)
                  serialized (serialize-job retry-job)]

              ;; Update job data
              (.set redis job-key serialized)

              ;; Remove from dead letter queue
              (.lrem redis (dead-letter-key) 0 (str job-id))

              ;; Add back to scheduled jobs
              (.zadd redis (scheduled-key)
                     (double (.toEpochMilli (:execute-at retry-job)))
                     (str job-id))

              retry-job)))))))

;; =============================================================================
;; Job Statistics Implementation
;; =============================================================================

(defrecord RedisJobStats [^JedisPool pool]
  ports/IJobStats

  (job-stats [this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queues (->> (scan-keys redis "queue:*")
                          (map #(second (re-find #"queue:([^:]+)" %)))
                          (filter some?)
                          (map keyword)
                          distinct)
              global-stats (.hgetAll redis (global-stats-key))
              active-workers (->> (.smembers redis (workers-key))
                                  (filter (fn [worker-id]
                                            (.exists redis (worker-key worker-id))))
                                  vec)]
          {:total-processed (parse-long-safe (get global-stats "total-processed"))
           :total-failed (parse-long-safe (get global-stats "total-failed"))
           :total-succeeded (parse-long-safe (get global-stats "total-succeeded"))
           :queues (mapv (fn [queue-name]
                           (let [stats (ports/queue-stats this queue-name)]
                             (assoc stats :queue-name queue-name)))
                         queues)
           :workers active-workers}))))

  (queue-stats [_ queue-name]
    (with-redis pool
      (fn [^Jedis redis]
        (let [queue-key (queue-key queue-name)
              stats-key (queue-stats-key queue-name)
              stats (.hgetAll redis stats-key)]
          {:queue-name queue-name
           :size (+ (.llen redis (str queue-key ":critical"))
                    (.llen redis (str queue-key ":high"))
                    (.llen redis queue-key)
                    (.llen redis (str queue-key ":low")))
           :processed-total (parse-long-safe (get stats "processed-total"))
           :failed-total (parse-long-safe (get stats "failed-total"))
           :succeeded-total (parse-long-safe (get stats "succeeded-total"))
           :avg-duration-ms nil}))))

  (job-history [_ job-type limit]
    (with-redis pool
      (fn [^Jedis redis]
        (->> (scan-keys redis "job:*")
             (map (fn [key]
                    (let [job-data (.get redis key)]
                      (when job-data
                        (deserialize-job job-data)))))
             (filter #(= (:job-type %) job-type))
             (sort-by :created-at #(compare %2 %1))  ; Newest first
             (take limit)
             vec)))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-redis-pool
  "Create a Jedis connection pool.

   Args:
     config - Map with:
              :host - Redis host (default localhost)
              :port - Redis port (default 6379)
              :password - Redis password (optional)
              :database - Redis database number (default 0)
              :max-total - Max connections (default 20)
              :max-idle - Max idle connections (default 10)

   Returns:
     JedisPool instance"
  [config]
  (let [pool-config (doto (JedisPoolConfig.)
                      (.setMaxTotal (or (:max-total config) 20))
                      (.setMaxIdle (or (:max-idle config) 10))
                      (.setMinIdle (or (:min-idle config) 2))
                      (.setTestOnBorrow true)
                      (.setTestOnReturn true))
        host (or (:host config) "localhost")
        port (or (:port config) 6379)
        timeout (or (:timeout config) 2000)
        password (:password config)
        database (or (:database config) 0)]

    (if password
      (JedisPool. pool-config host port timeout password database)
      (JedisPool. pool-config host port timeout))))

(defn create-redis-job-queue
  "Create Redis-backed job queue.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobQueue implementing IJobQueue"
  [pool]
  (->RedisJobQueue pool))

(defn create-redis-job-store
  "Create Redis-backed job store.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobStore implementing IJobStore"
  [pool]
  (->RedisJobStore pool))

(defn create-redis-job-stats
  "Create Redis-backed job stats.

   Args:
     pool - JedisPool instance

   Returns:
     RedisJobStats implementing IJobStats"
  [pool]
  (->RedisJobStats pool))

(defn close-redis-pool!
  "Close Redis connection pool.

   Args:
     pool - JedisPool instance"
  [^JedisPool pool]
  (.close pool))
