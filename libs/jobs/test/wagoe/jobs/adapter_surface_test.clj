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
      "Redis is not reachable on localhost:6379 — this run compared two backends, not three"))

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
