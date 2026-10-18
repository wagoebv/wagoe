(ns wagoe.jobs.shell.adapters.db-test
  "Reliability + round-trip tests for the DB-backed job queue, on in-memory H2.
   Unlike the Redis adapter tests these need no external service, so they run in
   the default CI test database."
  (:require [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.db :as db]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.adapters.codec-test :refer [legacy-json]]
            [support.test-db :as test-db])
  (:import [java.sql Timestamp]
           [java.time Instant ZoneId]))

(defn- fresh-queue
  "A DbJobQueue over a private in-memory H2 db with the table created."
  [& {:keys [lease-ms] :or {lease-ms db/default-lease-ms}}]
  (let [ds (jdbc/get-datasource
            {:dbtype "h2:mem"
             ;; unique db name per call + keep it alive for the JVM
             :dbname (str "jobs_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (db/create-jobs-table! ds)
    (db/create-db-job-queue ds :lease-ms lease-ms)))

(defn- mk-job [& {:keys [priority queue] :or {priority :normal queue :default}}]
  {:id         (random-uuid)
   :job-type   :send-email
   :queue      queue
   :priority   priority
   :status     :pending
   :args       {:to "a@b.c"}
   :created-at (Instant/now)
   :updated-at (Instant/now)})

(deftest ^:integration enqueue-dequeue-round-trips-the-job
  (let [q   (fresh-queue)
        job (mk-job)]
    (is (= (:id job) (ports/enqueue-job! q :default job)))
    (is (= 1 (ports/queue-size q :default)))
    (testing "dequeue returns the job with keyword + instant fields intact"
      (let [d (ports/dequeue-job! q :default "w1")]
        (is (= (:id job) (:id d)))
        (is (= :send-email (:job-type d)))
        (is (= :default (:queue d)))
        (is (instance? Instant (:created-at d)))))
    (testing "a claimed job is no longer on the ready queue"
      (is (zero? (ports/queue-size q :default)))
      (is (nil? (ports/dequeue-job! q :default "w2"))))))

(deftest ^:integration dequeue-honours-priority-then-fifo
  (let [q (fresh-queue)
        low  (mk-job :priority :low)
        crit (mk-job :priority :critical)
        norm (mk-job :priority :normal)]
    (doseq [j [low norm crit]] (ports/enqueue-job! q :default j))
    (is (= (:id crit) (:id (ports/dequeue-job! q :default "w"))) "critical first")
    (is (= (:id norm) (:id (ports/dequeue-job! q :default "w"))) "then normal")
    (is (= (:id low)  (:id (ports/dequeue-job! q :default "w"))) "low last")))

(deftest ^:integration reliable-queue-reclaims-crashed-worker
  (testing "a job dequeued by a crashed worker is reclaimed once its lease expires"
    (let [q   (fresh-queue :lease-ms 40)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      ;; worker takes it but never acks (crash mid-job)
      (is (= (:id job) (:id (ports/dequeue-job! q :default "worker-dead"))))
      (is (zero? (ports/queue-size q :default)) "in-flight, not on ready")
      (Thread/sleep 60) ; let the 40ms lease expire
      (is (= 1 (:reclaimed (ports/reclaim-abandoned-jobs! q :default))))
      (is (= 1 (ports/queue-size q :default)) "job back on the ready queue")
      ;; a fresh worker picks it up again (at-least-once)
      (is (= (:id job) (:id (ports/dequeue-job! q :default "worker-live"))))
      (is (true? (ports/ack-job! q :default "worker-live" (:id job))))))

  (testing "an acked job is not reclaimed"
    (let [q   (fresh-queue :lease-ms 0)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      (ports/dequeue-job! q :default "worker-ack")
      (ports/ack-job! q :default "worker-ack" (:id job))
      (Thread/sleep 5)
      (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! q :default))))))

  (testing "a live worker's in-flight job (lease not expired) is not reclaimed"
    (let [q   (fresh-queue :lease-ms 60000)
          job (mk-job)]
      (ports/enqueue-job! q :default job)
      (ports/dequeue-job! q :default "worker-live")
      (is (zero? (:reclaimed (ports/reclaim-abandoned-jobs! q :default)))))))

(deftest ^:integration scheduled-jobs-become-ready-when-due
  (let [q    (fresh-queue)
        job  (mk-job)]
    (testing "a job scheduled in the future is not ready"
      (ports/schedule-job! q :default job (.plusSeconds (Instant/now) 3600))
      (is (zero? (ports/queue-size q :default)))
      (is (nil? (ports/dequeue-job! q :default "w"))))
    (testing "a due scheduled job is promoted to ready by process-scheduled-jobs!"
      (let [due (mk-job)]
        (ports/schedule-job! q :default due (.minusSeconds (Instant/now) 1))
        (is (= 1 (ports/process-scheduled-jobs! q)))
        (is (= (:id due) (:id (ports/dequeue-job! q :default "w"))))))))

(deftest ^:integration concurrent-workers-never-double-claim
  (testing "N workers racing on one queue each claim distinct jobs; none twice"
    (let [q   (fresh-queue)
          n   200
          ids (doall (for [_ (range n)]
                       (let [job (mk-job)]
                         (ports/enqueue-job! q :default job)
                         (:id job))))
          claimed (atom [])
          workers (mapv (fn [w]
                          (future
                            (loop []
                              (when-let [j (ports/dequeue-job! q :default (str "w" w))]
                                (swap! claimed conj (:id j))
                                (recur)))))
                        (range 8))]
      (doseq [f workers] @f)
      (let [c @claimed]
        (is (= n (count c)) "every job claimed exactly once (no loss)")
        (is (= n (count (distinct c))) "no job claimed twice")
        (is (= (set ids) (set c)))
        (is (zero? (ports/queue-size q :default)) "queue drained")))))

(deftest ^:integration enqueue-in-tx-commits-atomically-with-business-write
  (testing "job enqueued in the caller's tx is there after commit"
    (let [q   (fresh-queue)
          ds  (:ds q)
          job (mk-job)
          order-id (random-uuid)]
      (jdbc/execute! ds ["CREATE TABLE orders (id UUID PRIMARY KEY)"])
      (jdbc/with-transaction [tx ds]
        (jdbc/execute! tx ["INSERT INTO orders (id) VALUES (?)" order-id])
        (ports/enqueue-in-tx! q tx :default job))
      (is (= 1 (count (jdbc/execute! ds ["SELECT id FROM orders"]))) "business write committed")
      (is (= (:id job) (:id (ports/dequeue-job! q :default "w"))) "job committed in the same tx"))))

(deftest ^:integration enqueue-in-tx-leaves-no-orphan-when-business-tx-rolls-back
  (testing "a rolled-back business tx leaves neither the business row nor the job"
    (let [q   (fresh-queue)
          ds  (:ds q)
          job (mk-job)]
      (jdbc/execute! ds ["CREATE TABLE orders (id UUID PRIMARY KEY)"])
      (jdbc/with-transaction [tx ds {:rollback-only true}]
        (jdbc/execute! tx ["INSERT INTO orders (id) VALUES (?)" (random-uuid)])
        (ports/enqueue-in-tx! q tx :default job))
      (is (zero? (count (jdbc/execute! ds ["SELECT id FROM orders"]))) "business write rolled back")
      (is (zero? (ports/queue-size q :default)) "no orphan job on the queue")
      (is (nil? (ports/dequeue-job! q :default "w"))))))

(deftest ^:integration enqueue-in-tx-rejects-a-datasource
  (testing "passing a datasource throws instead of silently autocommitting"
    ;; The regression this guards: a datasource does not FAIL, it SUCCEEDS —
    ;; autocommitting the job independently of the business change and silently
    ;; restoring the dual-write window. Before the guard this test would have
    ;; passed the insert and left a job on the queue (BOU-252).
    (let [q  (fresh-queue)
          ds (:ds q)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires an open transaction"
                            (ports/enqueue-in-tx! q ds :default (mk-job))))
      (is (zero? (ports/queue-size q :default))
          "nothing was enqueued by the rejected call"))))

(deftest ^:unit transactional-capability-is-advertised-only-where-it-can-be-honoured
  (testing "the DB queue advertises it — the queue lives in the business database"
    (is (ports/transactional-queue? (fresh-queue))))

  (testing "the in-memory queue does NOT — it cannot join the caller's DB transaction"
    ;; The negative case is the whole point of a capability protocol. Without
    ;; this, extending ITransactionalJobQueue to an adapter that cannot honour it
    ;; would go unnoticed, and callers branching on transactional-queue? would
    ;; silently take the unsafe path believing it was safe (BOU-252 review).
    (is (not (ports/transactional-queue? (mem/create-in-memory-job-queue)))
        "outbox semantics require the queue to share the business database")))

(deftest ^:integration deprecated-adapter-fn-still-works-and-still-guards
  (testing "the pre-BOU-252 adapter entry point keeps working for existing callers"
    ;; Deliberately exercises the deprecated path: it is kept for back-compat, so
    ;; it needs coverage, and it must carry the same datasource guard as the port.
    (let [q   (fresh-queue)
          ds  (:ds q)
          job (mk-job)]
      #_{:clj-kondo/ignore [:deprecated-var]}
      (jdbc/with-transaction [tx ds]
        (db/enqueue-in-tx! tx :default job))
      (is (= (:id job) (:id (ports/dequeue-job! q :default "w"))))
      #_{:clj-kondo/ignore [:deprecated-var]}
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires an open transaction"
                            (db/enqueue-in-tx! ds :default (mk-job)))))))

(deftest ^:integration peek-delete-and-list-queues
  (let [q (fresh-queue)
        a (mk-job :queue :alpha)
        b (mk-job :queue :beta)]
    (ports/enqueue-job! q :alpha a)
    (ports/enqueue-job! q :beta b)
    (testing "peek returns the next ready job without claiming it"
      (is (= (:id a) (:id (ports/peek-job q :alpha))))
      (is (= 1 (ports/queue-size q :alpha)) "peek did not claim"))
    (testing "list-queues reports the distinct queues"
      (is (= #{:alpha :beta} (set (ports/list-queues q)))))
    (testing "delete-job! removes a job"
      (is (true? (ports/delete-job! q (:id a))))
      (is (false? (ports/delete-job! q (random-uuid))))
      (is (zero? (ports/queue-size q :alpha))))))

(deftest ^:integration args-keep-their-types-on-each-engine
  ;; WAGOE_TEST_DB=pg or mysql runs this on that engine (BOU-689).
  (test-db/with-db "jobs_args"
    (fn [{:keys [datasource]}]
      (db/create-jobs-table! datasource)
      (let [q    (db/create-db-job-queue datasource)
            args {:report-id :sales
                  :zone-id   (ZoneId/of "Europe/Amsterdam")
                  :at        (Instant/parse "2026-10-17T08:23:28.536003Z")
                  :user-id   (random-uuid)}]
        (ports/enqueue-job! q :default (assoc (mk-job) :args args))
        (is (= args (:args (ports/dequeue-job! q :default "w"))))))))

(deftest ^:integration dead-letter-retention-runs-on-each-engine
  (test-db/with-db "jobs_dlq_retention"
    (fn [{:keys [datasource]}]
      (db/create-jobs-table! datasource)
      (let [store (db/create-db-job-store datasource)
            dead  (fn [] (let [j (assoc (mk-job) :max-retries 1 :retry-count 5)]
                           (ports/save-job! store j)
                           (ports/update-job-status! store (:id j) :failed {:error "boom"})
                           j))
            jobs  (vec (repeatedly 3 dead))
            kept  (mk-job)]
        (ports/save-job! store kept)
        (is (zero? (ports/purge-dead-letters-before! store (.minusSeconds (Instant/now) 3600))))
        (is (= 3 (ports/purge-dead-letters-before! store (.plusSeconds (Instant/now) 3600))))
        (is (every? nil? (map #(ports/find-job store (:id %)) jobs)))
        (is (some? (ports/find-job store (:id kept))))))))

(deftest ^:integration a-job-queued-as-json-before-the-upgrade-still-runs
  (test-db/with-db "jobs_legacy"
    (fn [{:keys [datasource]}]
      (db/create-jobs-table! datasource)
      (let [q (db/create-db-job-queue datasource)]
        (jdbc/execute! datasource
                       ["INSERT INTO job_queue (id, queue, priority_rank, status, payload, created_at)
                         VALUES (?, 'default', 2, 'ready', ?, ?)"
                        "7f8c1f0e-3b5a-4c1e-9a52-1d2f0c9b8e11" legacy-json
                        (Timestamp/from (Instant/now))])
        (let [job (ports/dequeue-job! q :default "w")]
          (is (= #uuid "7f8c1f0e-3b5a-4c1e-9a52-1d2f0c9b8e11" (:id job)))
          (is (= :send-email (:job-type job)))
          (is (= {:to "a@b.c" :kind "receipt"} (:args job))))))))
