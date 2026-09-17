(ns wagoe.jobs.adapter-surface-test
  "A deliberate sweep of `IJobQueue`, against every backend.

   `db_test.clj`, `in_memory_test.clj` and `redis_test.clj` test three
   interchangeable backends and share no cases, so nothing says they behave
   alike. That is the arrangement that hid seven divergences in `libs/events`
   and twelve in `libs/cache`, and a job queue has more to disagree about than
   either: ordering, priority, what a delete does to a queued id, and what
   at-least-once means when a worker dies.

   A disagreement is a bug in whichever backend is wrong. Where they genuinely
   cannot agree, the case is listed in `known-differences` with its reason, so
   everything unlisted is required to match."
  {:kaocha.testable/meta {:integration true}}
  (:require [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.db :as db]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.adapters.redis :as redis]
            [next.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [redis.clients.jedis Jedis]))

;; =============================================================================
;; Backends under test
;; =============================================================================

;; Database 14, and flushed: the Redis adapter's keys carry no configurable
;; prefix, and `list-queues` scans the whole keyspace. 15 belongs to the cache
;; suite.
(def ^:private redis-db 14)

(defonce ^:private redis-up?
  (delay (try
           (let [pool (redis/create-redis-pool {:host "localhost" :port 6379
                                                :timeout 1000 :database redis-db})]
             (with-open [^Jedis j (.getResource pool)] (.ping j))
             (redis/close-redis-pool! pool)
             true)
           (catch Exception _ false))))

(defn- backends
  "Each is [label make]. `make` takes options and returns [queue stop!].

   `:lease-ms` is how long a claimed job stays claimed before it can be
   reclaimed — the DB adapter's notion of a dead worker."
  []
  (cond-> [["in-memory"
            (fn [_opts] [(mem/create-in-memory-job-queue) (fn [_] nil)])]
           ["db"
            (fn [opts]
              (let [ds (jdbc/get-datasource
                        {:dbtype "h2:mem"
                         :dbname (str "surface_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
                (db/create-jobs-table! ds)
                [(db/create-db-job-queue ds :lease-ms (:lease-ms opts db/default-lease-ms))
                 (fn [_] nil)]))]]
    @redis-up?
    (conj ["redis"
           (fn [_opts]
             (let [pool (redis/create-redis-pool {:host "localhost" :port 6379
                                                  :database redis-db})]
               (with-open [^Jedis j (.getResource pool)] (.flushDB j))
               [(redis/create-redis-job-queue pool)
                (fn [_] (redis/close-redis-pool! pool))]))])))

(defn- each-backend
  "Call `(f queue label)` with a fresh queue from every backend."
  ([f] (each-backend {} f))
  ([opts f]
   (doseq [[label make] (backends)]
     (testing label
       (let [[queue stop!] (make opts)]
         (try (f queue label)
              (finally (stop! queue))))))))

(def ^:private known-differences
  "Cases a backend cannot meet, and why.

   Anything not listed here is required to match."
  {"in-memory/reclaim"
   (str "In-memory jobs live in the process that enqueued them, so a crash "
        "loses the queue itself — there is no surviving in-flight list to "
        "reclaim from. `ack-job!` and `reclaim-abandoned-jobs!` are no-ops that "
        "keep worker code adapter-agnostic.")})

(defn- job
  [& {:keys [priority queue at]
      :or   {priority :normal queue :default}}]
  (cond-> {:id         (random-uuid)
           :job-type   :send-email
           :queue      queue
           :priority   priority
           :status     :pending
           :args       {:to "a@b.c"}
           :created-at (Instant/now)
           :updated-at (Instant/now)}
    at (assoc :execute-at at)))

(defn- drain
  "Dequeue everything on `q`, returning the job ids in the order they came out."
  [q queue-name]
  (loop [out []]
    (if-let [j (ports/dequeue-job! q queue-name "w1")]
      (do (ports/ack-job! q queue-name "w1" (:id j))
          (recur (conj out (:id j))))
      out)))

(deftest ^:integration the-sweep-covers-every-backend
  ;; With a backend missing, everything below agrees with whatever is left.
  (is (= 3 (count (backends)))
      (str "Redis is not reachable on localhost:6379 — this run compared "
           (count (backends)) " backends, not three.\n"
           "  Start it with `bb test:services up`.")))

;; =============================================================================
;; Round trip
;; =============================================================================

(deftest ^:integration a-job-comes-back-as-it-went-in
  (each-backend
   (fn [q label]
     (let [j (job)]
       (is (= (:id j) (ports/enqueue-job! q :default j))
           (str label ": enqueue-job! did not return the job id"))
       (let [got (ports/dequeue-job! q :default "w1")]
         (is (= (:id j) (:id got))          (str label ": :id"))
         (is (= :send-email (:job-type got)) (str label ": :job-type"))
         (is (= :default (:queue got))       (str label ": :queue"))
         (is (= :normal (:priority got))     (str label ": :priority"))
         (is (= {:to "a@b.c"} (:args got))   (str label ": :args"))
         (is (instance? Instant (:created-at got))
             (str label ": :created-at came back as "
                  (some-> (:created-at got) class .getSimpleName))))))))

(deftest ^:integration an-empty-queue-answers-the-same-way
  (each-backend
   (fn [q label]
     (is (nil? (ports/dequeue-job! q :nothing-here "w1")) (str label ": dequeue-job!"))
     (is (nil? (ports/peek-job q :nothing-here))          (str label ": peek-job"))
     (is (zero? (ports/queue-size q :nothing-here))       (str label ": queue-size")))))

;; =============================================================================
;; Order
;; =============================================================================

(deftest ^:integration jobs-of-one-priority-come-out-in-the-order-they-went-in
  ;; A queue that reorders work of equal priority is a queue that runs the
  ;; newest first under load, which is when order matters most.
  (each-backend
   (fn [q label]
     (doseq [priority [:critical :high :normal :low]]
       (testing (name priority)
         (let [queue-name (keyword (str "fifo-" (name priority)))
               ids        (vec (for [_ (range 4)]
                                 (let [j (job :priority priority :queue queue-name)]
                                   (ports/enqueue-job! q queue-name j)
                                   (:id j))))]
           (is (= ids (drain q queue-name))
               (str label "/" (name priority) ": came out in a different order"))))))))

(deftest ^:integration a-higher-priority-job-goes-first
  (each-backend
   (fn [q label]
     (let [low      (job :priority :low      :queue :prio)
           normal   (job :priority :normal   :queue :prio)
           high     (job :priority :high     :queue :prio)
           critical (job :priority :critical :queue :prio)]
       ;; Enqueued worst-first, so order alone cannot produce the right answer.
       (doseq [j [low normal high critical]]
         (ports/enqueue-job! q :prio j))
       (is (= [(:id critical) (:id high) (:id normal) (:id low)]
              (drain q :prio))
           (str label ": priority was not honoured"))))))

;; =============================================================================
;; Claiming
;; =============================================================================

(deftest ^:integration a-claimed-job-is-off-the-ready-queue
  (each-backend
   (fn [q label]
     (let [j (job :queue :claim)]
       (ports/enqueue-job! q :claim j)
       (is (= 1 (ports/queue-size q :claim)))
       (is (some? (ports/dequeue-job! q :claim "w1")))
       (is (zero? (ports/queue-size q :claim))
           (str label ": a claimed job still counted towards the queue"))
       (is (nil? (ports/dequeue-job! q :claim "w2"))
           (str label ": two workers were handed the same job"))))))

(deftest ^:integration peek-shows-the-next-job-without-taking-it
  (each-backend
   (fn [q label]
     (testing "the job that peek shows is the job dequeue returns"
       (let [normal   (job :priority :normal   :queue :peeking)
             critical (job :priority :critical :queue :peeking)]
         (ports/enqueue-job! q :peeking normal)
         (ports/enqueue-job! q :peeking critical)
         (is (= (:id critical) (:id (ports/peek-job q :peeking)))
             (str label ": peek ignored priority"))
         (is (= 2 (ports/queue-size q :peeking))
             (str label ": peek removed the job"))
         (is (= (:id critical) (:id (ports/dequeue-job! q :peeking "w1")))
             (str label ": peek and dequeue disagreed")))))))

;; =============================================================================
;; Deleting
;; =============================================================================

(deftest ^:integration deleting-a-job-takes-it-out-of-the-queue
  (each-backend
   (fn [q label]
     (let [doomed (job :queue :deleting)
           kept   (job :queue :deleting)]
       (ports/enqueue-job! q :deleting doomed)
       (ports/enqueue-job! q :deleting kept)

       (is (true? (ports/delete-job! q (:id doomed)))
           (str label ": delete-job! did not report the deletion"))
       (is (= 1 (ports/queue-size q :deleting))
           (str label ": a deleted job still counted towards the queue"))

       (testing "and the one behind it is still delivered"
         ;; A deleted id left in the queue is a tombstone: the dequeue that
         ;; reaches it returns nil, and the work behind it waits for a poll that
         ;; may not come.
         (is (= (:id kept) (:id (ports/dequeue-job! q :deleting "w1")))
             (str label ": the job behind a deleted one was not delivered")))))))

(deftest ^:integration deleting-a-job-that-is-not-there-says-so
  (each-backend
   (fn [q label]
     (is (false? (ports/delete-job! q (random-uuid)))
         (str label ": delete-job! of an unknown id did not return false")))))

;; =============================================================================
;; Queues
;; =============================================================================

(deftest ^:integration queues-do-not-see-each-other
  (each-backend
   (fn [q label]
     (let [a (job :queue :qa) b (job :queue :qb)]
       (ports/enqueue-job! q :qa a)
       (ports/enqueue-job! q :qb b)
       (is (= 1 (ports/queue-size q :qa)))
       (is (= 1 (ports/queue-size q :qb)))
       (is (= (:id a) (:id (ports/dequeue-job! q :qa "w1")))
           (str label ": a queue delivered another queue's job"))))))

(deftest ^:integration list-queues-names-each-queue-that-has-work-once
  (each-backend
   (fn [q label]
     (ports/enqueue-job! q :listed-a (job :queue :listed-a))
     (ports/enqueue-job! q :listed-a (job :queue :listed-a :priority :critical))
     (ports/enqueue-job! q :listed-b (job :queue :listed-b))
     (let [queues (ports/list-queues q)]
       (is (= (sort queues) (sort (distinct queues)))
           (str label ": list-queues repeated a queue: " (pr-str queues)))
       (is (= #{:listed-a :listed-b} (set queues))
           (str label ": list-queues returned " (pr-str queues))))

     (testing "and stops naming one once its work is gone"
       (drain q :listed-a)
       (is (= [:listed-b] (ports/list-queues q))
           (str label ": a drained queue was still listed"))))))

;; =============================================================================
;; Scheduling
;; =============================================================================

(deftest ^:integration a-scheduled-job-waits-for-its-time
  (each-backend
   (fn [q label]
     (let [later (job :queue :sched)]
       (ports/schedule-job! q :sched later (.plusSeconds (Instant/now) 3600))
       (is (zero? (ports/queue-size q :sched))
           (str label ": a job scheduled for later was ready immediately"))
       (is (nil? (ports/dequeue-job! q :sched "w1"))
           (str label ": a job scheduled for later was handed to a worker"))

       (testing "and process-scheduled-jobs! does not promote it early"
         (ports/process-scheduled-jobs! q)
         (is (zero? (ports/queue-size q :sched))
             (str label ": a job not yet due was promoted")))))))

(deftest ^:integration a-job-scheduled-for-the-past-is-due
  (each-backend
   (fn [q label]
     (let [j (job :queue :due)]
       (ports/schedule-job! q :due j (.minusSeconds (Instant/now) 1))
       (is (= 1 (ports/process-scheduled-jobs! q))
           (str label ": process-scheduled-jobs! did not report the promotion"))
       (is (= 1 (ports/queue-size q :due))
           (str label ": a due job was not put on the ready queue"))
       (is (= (:id j) (:id (ports/dequeue-job! q :due "w1")))
           (str label ": the promoted job was not the one scheduled"))

       (testing "and it is promoted once"
         (is (zero? (ports/process-scheduled-jobs! q))
             (str label ": the same scheduled job was promoted twice")))))))

;; =============================================================================
;; At-least-once
;; =============================================================================

(deftest ^:integration a-job-a-dead-worker-was-holding-comes-back
  ;; The promise the library exists for. A worker that dequeues and dies must
  ;; not take the job with it.
  (each-backend
   {:lease-ms 1}
   (fn [q label]
     (if (contains? known-differences (str label "/reclaim"))
       (is (= {:reclaimed 0} (ports/reclaim-abandoned-jobs! q :abandoned))
           (str label ": listed as unable to reclaim, but did not answer zero"))
       (let [j (job :queue :abandoned)]
         (ports/enqueue-job! q :abandoned j)
         (is (some? (ports/dequeue-job! q :abandoned "dead-worker")))
         (is (zero? (ports/queue-size q :abandoned)))

         (Thread/sleep 50)
         (is (= {:reclaimed 1} (ports/reclaim-abandoned-jobs! q :abandoned))
             (str label ": the job of a dead worker was not reclaimed"))
         (is (= 1 (ports/queue-size q :abandoned))
             (str label ": reclaim reported a job it did not put back"))
         (is (= (:id j) (:id (ports/dequeue-job! q :abandoned "w2")))
             (str label ": the reclaimed job was not the one abandoned")))))))

(deftest ^:integration an-acked-job-does-not-come-back
  (each-backend
   {:lease-ms 1}
   (fn [q label]
     (if (contains? known-differences (str label "/reclaim"))
       (is (= {:reclaimed 0} (ports/reclaim-abandoned-jobs! q :acked)))
       (let [j (job :queue :acked)]
         (ports/enqueue-job! q :acked j)
         (let [got (ports/dequeue-job! q :acked "w1")]
           (is (true? (ports/ack-job! q :acked "w1" (:id got)))
               (str label ": ack-job! did not return true")))
         (Thread/sleep 50)
         (is (= {:reclaimed 0} (ports/reclaim-abandoned-jobs! q :acked))
             (str label ": an acked job was reclaimed"))
         (is (zero? (ports/queue-size q :acked))
             (str label ": an acked job came back to the ready queue")))))))

;; =============================================================================
;; IJobStore — the same sweep, for the other half of the contract
;; =============================================================================
;;
;; The DB adapter shipped a queue and no store (BOU-418), so wiring it meant
;; pairing a durable queue with an in-memory dead-letter queue: every failed
;; job lost on restart. It has a store now, and this is what says it agrees
;; with the two that already existed.

(defn- stores
  "Each is [label make]. `make` returns [store stop!]."
  []
  (cond-> [["in-memory"
            (fn [] [(mem/create-in-memory-job-store) (fn [_] nil)])]
           ["db"
            (fn []
              (let [ds (jdbc/get-datasource
                        {:dbtype "h2:mem"
                         :dbname (str "store_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
                (db/create-jobs-table! ds)
                (db/create-job-store-table! ds)
                [(db/create-db-job-store ds) (fn [_] nil)]))]]
    @redis-up?
    (conj ["redis"
           (fn []
             (let [pool (redis/create-redis-pool {:host "localhost" :port 6379
                                                  :database redis-db})]
               (with-open [^Jedis j (.getResource pool)] (.flushDB j))
               [(redis/create-redis-job-store pool)
                (fn [_] (redis/close-redis-pool! pool))]))])))

(defn- each-store
  [f]
  (doseq [[label make] (stores)]
    (testing label
      (let [[store stop!] (make)]
        (try (f store label)
             (finally (stop! store)))))))

(defn- stored
  "A job as the worker hands it to the store: retry budget included."
  [& {:keys [max-retries retry-count job-type queue]
      :or   {max-retries 3 retry-count 0 job-type :send-email queue :default}}]
  (assoc (job :queue queue)
         :job-type    job-type
         :max-retries max-retries
         :retry-count retry-count))

(deftest ^:integration the-store-sweep-covers-every-backend
  (is (= 3 (count (stores)))
      (str "Redis is not reachable on localhost:6379 — this run compared "
           (count (stores)) " stores, not three.\n"
           "  Start it with `bb test:services up`.")))

(deftest ^:integration a-saved-job-comes-back-as-it-went-in
  (each-store
   (fn [s label]
     (let [j (stored)]
       (ports/save-job! s j)
       (let [got (ports/find-job s (:id j))]
         (is (= (:id j) (:id got))           (str label ": :id"))
         (is (= :send-email (:job-type got)) (str label ": :job-type"))
         (is (= :default (:queue got))       (str label ": :queue"))
         (is (= {:to "a@b.c"} (:args got))   (str label ": :args"))
         (is (instance? Instant (:created-at got))
             (str label ": :created-at came back as " (type (:created-at got)))))
       (is (nil? (ports/find-job s (random-uuid)))
           (str label ": an unknown id did not answer nil"))))))

(deftest ^:integration a-status-update-moves-the-job-through-its-lifecycle
  (each-store
   (fn [s label]
     (let [j (stored)]
       (ports/save-job! s j)
       (is (= :running (:status (ports/update-job-status! s (:id j) :running nil)))
           (str label ": :running"))
       (is (= :completed (:status (ports/update-job-status! s (:id j) :completed {:ok true})))
           (str label ": :completed"))
       (is (= :completed (:status (ports/find-job s (:id j))))
           (str label ": the completed status was not persisted"))
       (is (nil? (ports/update-job-status! s (random-uuid) :running nil))
           (str label ": an unknown id did not answer nil"))))))

(deftest ^:integration only-a-job-out-of-retries-is-dead-lettered
  (each-store
   (fn [s label]
     ;; Retries left: a failure is not the end, so it must not appear in the
     ;; dead-letter list — that list is what an operator inspects after a
     ;; night of failures.
     (let [retryable (stored :max-retries 3 :retry-count 0)
           exhausted (stored :max-retries 1 :retry-count 5)]
       (ports/save-job! s retryable)
       (ports/save-job! s exhausted)
       (ports/update-job-status! s (:id retryable) :failed {:error "boom"})
       (ports/update-job-status! s (:id exhausted) :failed {:error "boom"})
       (let [dead (set (map :id (ports/failed-jobs s 10)))]
         (is (contains? dead (:id exhausted))
             (str label ": a job with no retries left was not dead-lettered"))
         (is (not (contains? dead (:id retryable)))
             (str label ": a job with retries left was dead-lettered")))))))

(deftest ^:integration a-retried-job-leaves-the-dead-letter-list
  (each-store
   (fn [s label]
     (let [j (stored :max-retries 1 :retry-count 5)]
       (ports/save-job! s j)
       (ports/update-job-status! s (:id j) :failed {:error "boom"})
       (is (= 1 (count (ports/failed-jobs s 10))) (str label ": setup"))
       (is (some? (ports/retry-job! s (:id j)))
           (str label ": retry-job! answered nil for a job it holds"))
       (is (empty? (ports/failed-jobs s 10))
           (str label ": a retried job stayed in the dead-letter list"))
       (is (nil? (ports/retry-job! s (random-uuid)))
           (str label ": retry-job! on an unknown id did not answer nil"))))))

(deftest ^:integration find-jobs-filters-on-what-it-documents
  (each-store
   (fn [s label]
     (let [a (stored :job-type :send-email :queue :default)
           b (stored :job-type :send-sms   :queue :urgent)]
       (ports/save-job! s a)
       (ports/save-job! s b)
       (is (= #{(:id a) (:id b)} (set (map :id (ports/find-jobs s {}))))
           (str label ": no filter did not return everything"))
       (is (= [(:id a)] (map :id (ports/find-jobs s {:job-type :send-email})))
           (str label ": :job-type"))
       (is (= [(:id b)] (map :id (ports/find-jobs s {:queue :urgent})))
           (str label ": :queue"))
       (ports/update-job-status! s (:id a) :running nil)
       (is (= [(:id a)] (map :id (ports/find-jobs s {:status :running})))
           (str label ": :status"))))))

;; =============================================================================
;; Queue and store together — where the backends disagreed
;; =============================================================================
;;
;; Both cases below passed on in-memory and failed on db, which is exactly the
;; shape a per-backend test file cannot see (BOU-418 review).

(defn- pairs
  "Each is [label make]; `make` takes options and returns [queue store stop!]
   sharing a backend."
  []
  (cond-> [["in-memory"
            (fn [_opts] (let [{:keys [queue store]} (mem/create-in-memory-jobs-system)]
                          [queue store (fn [] nil)]))]
           ["db"
            (fn [opts]
              (let [ds (jdbc/get-datasource
                        {:dbtype "h2:mem"
                         :dbname (str "pair_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
                (db/create-jobs-table! ds)
                [(db/create-db-job-queue ds :lease-ms (:lease-ms opts db/default-lease-ms))
                 (db/create-db-job-store ds)
                 (fn [] nil)]))]]
    @redis-up?
    (conj ["redis"
           (fn [_opts]
             (let [pool (redis/create-redis-pool {:host "localhost" :port 6379
                                                  :database redis-db})]
               (with-open [^Jedis j (.getResource pool)] (.flushDB j))
               [(redis/create-redis-job-queue pool)
                (redis/create-redis-job-store pool)
                (fn [] (redis/close-redis-pool! pool))]))])))

(defn- each-pair
  ([f] (each-pair {} f))
  ([opts f]
   (doseq [[label make] (pairs)]
     (testing label
       (let [[queue store stop!] (make opts)]
         (try (f queue store label) (finally (stop!))))))))

(deftest ^:integration an-enqueued-job-is-known-to-the-store
  ;; The DB queue wrote only `job_queue`, so the worker's `update-job-status!`
  ;; found nothing: a completed job left no history and a failed one never
  ;; reached the dead-letter queue.
  (each-pair
   (fn [queue store label]
     (let [j (job)]
       (ports/enqueue-job! queue :default j)
       (is (some? (ports/find-job store (:id j)))
           (str label ": the store has never heard of a job that was enqueued"))
       (is (some? (ports/update-job-status! store (:id j) :running nil))
           (str label ": the store cannot record an outcome for it"))))))

(deftest ^:integration a-re-enqueued-job-survives-the-ack-that-follows-it
  ;; The worker writes a replacement (a retry, or a re-route for a missing
  ;; handler) and then acks the claim it holds. The replacement must go first:
  ;; acking first leaves a window in which the job is neither queued nor in
  ;; flight, and a process that dies there loses it with nothing to reclaim.
  ;;
  ;; That order needs an ack that only releases a claim. The DB ack deleted the
  ;; row unconditionally, so it removed the replacement it found there — and
  ;; would have deleted a job another worker had already reclaimed after a lease
  ;; expiry. In-memory's ack is a no-op, so neither showed there (BOU-418
  ;; review).
  (each-pair
   (fn [queue _store label]
     (let [j (job :queue :requeue)]
       (ports/enqueue-job! queue :requeue j)
       (is (= (:id j) (:id (ports/dequeue-job! queue :requeue "w1")))
           (str label ": setup"))
       ;; Replacement first, ack second — the order the worker uses.
       (ports/enqueue-job! queue :requeue (assoc j :retry-count 1))
       (ports/ack-job! queue :requeue "w1" (:id j))
       (is (= 1 (ports/queue-size queue :requeue))
           (str label ": the ack removed the replacement that preceded it"))
       (is (= 1 (:retry-count (ports/dequeue-job! queue :requeue "w2")))
           (str label ": the queued job is not the replacement"))))))

(deftest ^:integration a-late-ack-does-not-destroy-a-job-someone-else-took-over
  ;; Lease expiry hands the job to a second worker. The first worker's late ack
  ;; must not destroy it — asserted on the job still being obtainable, not on a
  ;; reclaim count: with no live worker heartbeats the Redis reclaim treats both
  ;; owners as dead, so a count says nothing about who holds it.
  (each-pair
   {:lease-ms 1}
   (fn [queue _store label]
     (if (contains? known-differences (str label "/reclaim"))
       ;; No in-flight list, so no takeover to protect — the documented
       ;; difference, asserted rather than skipped.
       (is (= {:reclaimed 0} (ports/reclaim-abandoned-jobs! queue :stolen)))
       (let [j (job :queue :stolen)]
         (ports/enqueue-job! queue :stolen j)
         (ports/dequeue-job! queue :stolen "slow")
         (Thread/sleep 50)
         (ports/reclaim-abandoned-jobs! queue :stolen)
         (is (= (:id j) (:id (ports/dequeue-job! queue :stolen "fast")))
             (str label ": setup — the job was not reclaimed"))
         ;; The previous owner acks, far too late.
         (ports/ack-job! queue :stolen "slow" (:id j))
         (Thread/sleep 50)
         (ports/reclaim-abandoned-jobs! queue :stolen)
         (is (= (:id j) (:id (ports/dequeue-job! queue :stolen "fast")))
             (str label ": a late ack from the previous owner destroyed the job")))))))
