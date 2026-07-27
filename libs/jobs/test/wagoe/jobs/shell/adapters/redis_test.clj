(ns wagoe.jobs.shell.adapters.redis-test
  "Integration tests for the Redis job queue adapter.

   These tests require a running Redis instance.
   If Redis is not available on localhost:6379 the tests are skipped."
  {:kaocha.testable/meta {:integration true :redis true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.jobs.shell.adapters.redis :as redis-adapter]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports])
  (:import [java.time Instant]
           [java.util UUID]
           [redis.clients.jedis JedisPool]))

;; =============================================================================
;; Redis availability check
;; =============================================================================

(defonce ^:private redis-availability (atom nil))

(defn redis-available?
  "Check if Redis is reachable on localhost:6379."
  []
  (if-let [cached @redis-availability]
    cached
    (let [result (try
                   (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :timeout 1000})
                         ^redis.clients.jedis.Jedis jedis (.getResource pool)
                         pong (.ping jedis)]
                     (.close jedis)
                     (redis-adapter/close-redis-pool! pool)
                     (= "PONG" pong))
                   (catch Exception _
                     false))]
      (reset! redis-availability result)
      result)))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def ^:dynamic *queue* nil)
(def ^:dynamic *store* nil)
(def ^:dynamic *stats* nil)
(def ^:dynamic *pool* nil)

(defn flush-test-db!
  "Flush Redis DB 14 used for tests."
  [^JedisPool pool]
  (let [jedis (.getResource pool)]
    (try
      (.flushDB jedis)
      (finally
        (.close jedis)))))

(defn with-redis-jobs
  "Fixture that creates Redis-backed jobs components using DB 14."
  [f]
  (if (redis-available?)
    (let [pool (redis-adapter/create-redis-pool {:host "localhost" :port 6379 :database 14})]
      (try
        (flush-test-db! pool)
        (binding [*pool*  pool
                  *queue* (redis-adapter/create-redis-job-queue pool)
                  *store* (redis-adapter/create-redis-job-store pool)
                  *stats* (redis-adapter/create-redis-job-stats pool)]
          (f))
        (finally
          (redis-adapter/close-redis-pool! pool))))
    (f)))

(use-fixtures :each with-redis-jobs)

(defmacro when-redis [& body]
  `(if (redis-available?)
     (do ~@body)
     (is (not (redis-available?)) "Redis not available — test skipped")))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-job
  ([] (make-job {}))
  ([overrides]
   (job/create-job
    (merge {:job-type :send-email
            :args {:to "user@example.com"}
            :queue :default}
           overrides)
    (UUID/randomUUID)
    (Instant/now))))

;; =============================================================================
;; Queue operations
;; =============================================================================

(deftest ^:integration redis-enqueue-dequeue-test
  (when-redis
   (let [test-job (make-job)]

     (testing "enqueue adds job to queue"
       (ports/enqueue-job! *queue* :default test-job)
       (is (= 1 (ports/queue-size *queue* :default))))

     (testing "dequeue returns job"
       (let [dequeued (ports/dequeue-job! *queue* :default "test-worker")]
         (is (= (:id test-job) (:id dequeued)))
         (is (zero? (ports/queue-size *queue* :default))))))))

(deftest ^:integration redis-reliable-queue-reclaim-test
  (when-redis
   (testing "a job dequeued by a crashed worker is reclaimed and re-runnable"
     (let [job (make-job)]
       (ports/enqueue-job! *queue* :default job)
       ;; A worker takes the job but never acks (simulating a crash mid-job).
       ;; It is unregistered, so its heartbeat key is absent → treated as dead.
       (let [taken (ports/dequeue-job! *queue* :default "worker-dead")]
         (is (= (:id job) (:id taken)))
         (is (zero? (ports/queue-size *queue* :default))
             "job is in-flight (processing list), not on the ready queue"))
       ;; Reclaim returns the stranded job to the ready queue.
       (is (= 1 (:reclaimed (ports/reclaim-abandoned-jobs! *queue* :default))))
       (is (= 1 (ports/queue-size *queue* :default)) "job back on the ready queue")
       ;; A fresh worker picks it up again (at-least-once).
       (is (= (:id job) (:id (ports/dequeue-job! *queue* :default "worker-live"))))
       (ports/ack-job! *queue* :default "worker-live" (:id job))))

   (testing "an acked job is not reclaimed"
     (let [job (make-job)]
       (ports/enqueue-job! *queue* :default job)
       (ports/dequeue-job! *queue* :default "worker-ack")
       (ports/ack-job! *queue* :default "worker-ack" (:id job))
       (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! *queue* :default))))
       (is (zero? (ports/queue-size *queue* :default)))))

   (testing "a live worker's in-flight job is NOT reclaimed"
     (let [job (make-job)]
       (ports/enqueue-job! *queue* :default job)
       (redis-adapter/register-worker! *queue* "worker-alive" :default)
       (ports/dequeue-job! *queue* :default "worker-alive")
       (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! *queue* :default)))
           "a heartbeating worker keeps its in-flight job")
       (ports/ack-job! *queue* :default "worker-alive" (:id job))
       (redis-adapter/unregister-worker! *queue* "worker-alive")))))

(deftest ^:integration redis-priority-queue-test
  (when-redis
   (let [low      (make-job {:priority :low})
         high     (make-job {:priority :high})
         critical (make-job {:priority :critical})]

     (ports/enqueue-job! *queue* :default low)
     (ports/enqueue-job! *queue* :default high)
     (ports/enqueue-job! *queue* :default critical)

     (testing "dequeues in priority order (critical first)"
       (is (= (:id critical) (:id (ports/dequeue-job! *queue* :default "test-worker"))))
       (is (= (:id high)     (:id (ports/dequeue-job! *queue* :default "test-worker"))))
       (is (= (:id low)      (:id (ports/dequeue-job! *queue* :default "test-worker"))))))))

(deftest ^:integration redis-queue-size-test
  (when-redis
   (dotimes [_ 5]
     (ports/enqueue-job! *queue* :default (make-job)))

   (testing "queue-size returns correct count"
     (is (= 5 (ports/queue-size *queue* :default))))))

;; =============================================================================
;; Job Store operations
;; =============================================================================

(deftest ^:integration redis-save-find-job-test
  (when-redis
   (let [test-job (make-job)]

     (ports/save-job! *store* test-job)

     (testing "find-job retrieves saved job"
       (let [found (ports/find-job *store* (:id test-job))]
         (is (= (:id test-job) (:id found)))
         (is (= :send-email (:job-type found)))
         (is (= :pending (:status found))))))))

(deftest ^:integration redis-update-job-status-test
  (when-redis
   (let [test-job (make-job {:max-retries 3})]

     (ports/save-job! *store* test-job)

     (testing "update to :running sets started-at"
       (ports/update-job-status! *store* (:id test-job) :running nil)
       (let [running (ports/find-job *store* (:id test-job))]
         (is (= :running (:status running)))
         (is (some? (:started-at running)))))

     (testing "update to :completed sets result and completed-at"
       (ports/update-job-status! *store* (:id test-job) :completed {:ok true})
       (let [done (ports/find-job *store* (:id test-job))]
         (is (= :completed (:status done)))
         (is (= {:ok true} (:result done)))
         (is (some? (:completed-at done))))))))

(deftest ^:integration redis-find-jobs-by-status-test
  (when-redis
   (let [job1 (make-job)
         job2 (make-job)]

     (ports/save-job! *store* job1)
     (ports/save-job! *store* job2)
     (ports/update-job-status! *store* (:id job1) :running nil)
     (ports/update-job-status! *store* (:id job1) :completed {:result "ok"})

     (testing "finds completed jobs"
       (let [completed (ports/find-jobs *store* {:status :completed})]
         (is (= 1 (count completed)))
         (is (= (:id job1) (:id (first completed)))))))))

(deftest ^:integration redis-failed-and-retry-test
  (when-redis
   (let [test-job (make-job {:max-retries 0})]
     (ports/save-job! *store* test-job)
     (ports/update-job-status! *store* (:id test-job) :failed {:message "boom" :type "TestError"})

     (testing "failed-jobs includes exhausted failed jobs"
       (let [failed (ports/failed-jobs *store* 10)]
         (is (= 1 (count failed)))
         (is (= (:id test-job) (:id (first failed))))))

     (testing "retry-job! moves failed job back to pending with execute-at"
       (let [retried (ports/retry-job! *store* (:id test-job))]
         (is (= :pending (:status retried)))
         (is (some? (:execute-at retried)))
         (is (nil? (:error retried))))

       (is (empty? (ports/failed-jobs *store* 10)))))))

;; =============================================================================
;; Scheduled jobs
;; =============================================================================

(deftest ^:integration redis-scheduled-jobs-test
  (when-redis
   (let [past-time   (.minusSeconds (Instant/now) 30)
         future-time (.plusSeconds (Instant/now) 300)
         due-job     (make-job {:execute-at past-time})
         future-job  (make-job {:execute-at future-time})]

     (ports/enqueue-job! *queue* :default due-job)
     (ports/enqueue-job! *queue* :default future-job)

     (testing "scheduled jobs are not in execution queue immediately"
       (is (zero? (ports/queue-size *queue* :default))))

     (testing "process-scheduled-jobs! moves due jobs to execution queue"
       (let [moved (ports/process-scheduled-jobs! *queue*)]
         (is (= 1 moved))
         (is (= 1 (ports/queue-size *queue* :default))))))))

(deftest ^:integration redis-job-history-and-stats-test
  (when-redis
   (let [job-a (make-job {:job-type :send-email})
         job-b (make-job {:job-type :send-email})]
     (ports/save-job! *store* job-a)
     (ports/save-job! *store* job-b)
     (ports/update-job-status! *store* (:id job-a) :completed {:ok true})
     (ports/update-job-status! *store* (:id job-b) :failed {:message "err"})

     (testing "job-history returns entries for job type"
       (let [history (ports/job-history *stats* :send-email 10)]
         (is (seq history))
         (is (every? #(= :send-email (:job-type %)) history))))

     (testing "job-stats and queue-stats return expected keys"
       (let [stats (ports/job-stats *stats*)
             qstats (ports/queue-stats *stats* :default)]
         (is (map? stats))
         (is (contains? stats :total-processed))
         (is (contains? stats :queues))
         (is (>= (:total-processed stats) 1))
         (is (map? qstats))
         (is (contains? qstats :size))
         (is (contains? qstats :processed-total))
         (is (>= (:processed-total qstats) 1)))))))
