(ns wagoe.jobs.recurring-surface-test
  "`IIdempotencyStore`, `IRecurringState` and two tickers sharing them,
   against every backend. The database is the one WAGOE_TEST_DB names; Redis
   comes from `bb test:services up`, and its absence fails the sweep."
  {:kaocha.testable/meta {:integration true}}
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.db :as db]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.adapters.redis :as redis]
            [wagoe.jobs.shell.recurring :as recurring]
            [support.test-db :as test-db])
  (:import [java.time Instant]
           [redis.clients.jedis Jedis]))

;; 13: the jobs sweep flushes 14 and the cache suite 15.
(def ^:private redis-db 13)

(defonce ^:private redis-up?
  (delay (try
           (let [pool (redis/create-redis-pool {:host "localhost" :port 6379
                                                :timeout 1000 :database redis-db})]
             (with-open [^Jedis j (.getResource pool)] (.ping j))
             (redis/close-redis-pool! pool)
             true)
           (catch Exception _ false))))

(defn- backends
  "Each is [label (fn [f] (f {:queue :state :idempotency}))]."
  []
  (cond-> [["in-memory"
            (fn [f] (f {:queue       (mem/create-in-memory-job-queue)
                        :state       (mem/create-in-memory-recurring-state)
                        :idempotency (mem/create-in-memory-idempotency-store)}))]
           [(str "db/" (name test-db/engine))
            (fn [f]
              (test-db/with-db "jobs_recurring"
                (fn [{:keys [datasource]}]
                  (db/create-jobs-table! datasource)
                  (db/create-recurring-tables! datasource)
                  ;; Twice: a second replica booting finds the tables and the lease row.
                  (db/create-recurring-tables! datasource)
                  (f {:queue       (db/create-db-job-queue datasource)
                      :state       (db/create-db-recurring-state datasource)
                      :idempotency (db/create-db-idempotency-store datasource)}))))]]
    @redis-up?
    (conj ["redis"
           (fn [f]
             (let [pool (redis/create-redis-pool {:host "localhost" :port 6379 :database redis-db})]
               (try
                 (with-open [^Jedis j (.getResource pool)] (.flushDB j))
                 (f {:queue       (redis/create-redis-job-queue pool)
                     :state       (redis/create-redis-recurring-state pool)
                     :idempotency (redis/create-redis-idempotency-store pool)})
                 (finally (redis/close-redis-pool! pool)))))])))

(defn- each-backend [f]
  (doseq [[label with-backend] (backends)]
    (testing label (with-backend f))))

(deftest ^:integration the-sweep-covers-every-backend
  (is (= 3 (count (backends)))
      "Redis is not reachable on localhost:6379. Start it with `bb test:services up`."))

(deftest ^:integration a-key-is-claimed-once-until-it-expires-or-is-released
  (each-backend
   (fn [{:keys [idempotency]}]
     (is (true? (ports/claim-key! idempotency "send-receipt" "order-42" 60000)))
     (is (false? (ports/claim-key! idempotency "send-receipt" "order-42" 60000)))
     (testing "scope and key both count"
       (is (true? (ports/claim-key! idempotency "send-receipt" "order-43" 60000)))
       (is (true? (ports/claim-key! idempotency "send-invoice" "order-42" 60000))))
     (testing "released"
       (ports/release-key! idempotency "send-receipt" "order-42")
       (is (true? (ports/claim-key! idempotency "send-receipt" "order-42" 60000))))
     (testing "expired"
       (is (true? (ports/claim-key! idempotency "short" "k" 50)))
       (Thread/sleep 1100)
       (is (true? (ports/claim-key! idempotency "short" "k" 60000)))
       (is (false? (ports/claim-key! idempotency "short" "k" 60000)))))))

(deftest ^:integration the-lease-has-one-holder
  (each-backend
   (fn [{:keys [state]}]
     (is (true? (ports/acquire-lease! state "a" 60000)))
     (is (true? (ports/acquire-lease! state "a" 60000)) "its holder renews it")
     (is (false? (ports/acquire-lease! state "b" 60000)))
     (testing "released, it is free"
       (ports/release-lease! state "b")
       (is (false? (ports/acquire-lease! state "b" 60000)) "only by its holder")
       (ports/release-lease! state "a")
       (is (true? (ports/acquire-lease! state "b" 1000))))
     (testing "expired, it is free"
       (Thread/sleep 1100)
       (is (true? (ports/acquire-lease! state "a" 60000)))))))

(deftest ^:integration the-last-tick-round-trips
  (each-backend
   (fn [{:keys [state]}]
     (let [t1 (Instant/parse "2026-01-01T02:00:00Z")
           t2 (Instant/parse "2026-01-02T02:00:00Z")]
       (is (nil? (ports/last-tick state "nightly-report")))
       (ports/record-tick! state "nightly-report" t1)
       (is (= t1 (ports/last-tick state "nightly-report")))
       (ports/record-tick! state "nightly-report" t2)
       (is (= t2 (ports/last-tick state "nightly-report")))
       (is (nil? (ports/last-tick state "other")))))))

(deftest ^:integration two-tickers-enqueue-each-tick-once
  (each-backend
   (fn [{:keys [queue state] :as backend}]
     (let [tickers (repeatedly 2 #(recurring/create-scheduler backend))]
       (doseq [s tickers]
         (recurring/schedule-recurring! s :nightly-report "0 2 * * *"))
       (ports/record-tick! state "nightly-report" (Instant/parse "2026-01-01T02:00:00Z"))
       (doseq [day ["2026-01-02" "2026-01-03"]
               s   tickers]
         (recurring/tick! s (Instant/parse (str day "T02:00:05Z"))))
       (is (= 2 (ports/queue-size queue :default)) "two days, two jobs")))))

(deftest ^:integration overlapping-leaders-still-enqueue-once
  ;; Each believes it holds the lease; only the idempotency key stands between
  ;; them and a second job.
  (each-backend
   (fn [{:keys [queue idempotency]}]
     (let [tickers (repeatedly 2 #(recurring/create-scheduler
                                   {:queue       queue
                                    :idempotency idempotency
                                    :state       (mem/create-in-memory-recurring-state)}))]
       (doseq [s tickers]
         (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
         (ports/record-tick! (:state s) "nightly-report" (Instant/parse "2026-01-01T02:00:00Z"))
         (recurring/tick! s (Instant/parse "2026-01-02T02:00:05Z")))
       (is (= 1 (ports/queue-size queue :default)))))))
