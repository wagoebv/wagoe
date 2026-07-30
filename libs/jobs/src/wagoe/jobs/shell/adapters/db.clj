(ns wagoe.jobs.shell.adapters.db
  "Database-backed job queue (IJobQueue) on next.jdbc.

   Lets an app run durable background jobs on its existing SQL database (H2 or
   PostgreSQL) without standing up Redis, and survive a Redis outage. Implements
   the same reliable-dequeue contract as the Redis adapter:

   - `dequeue-job!` atomically claims the next ready row (sets status=processing,
     locked_by/locked_at) so a worker that crashes mid-job does not lose it.
   - `ack-job!` removes the row once the worker is done with it.
   - `reclaim-abandoned-jobs!` returns rows whose lease has expired
     (locked_at older than `lease-ms`) back to `ready`, i.e. at-least-once.

   Portable SQL only (no `SELECT ... FOR UPDATE SKIP LOCKED`): the claim is a
   SELECT of the best candidate followed by a conditional `UPDATE ... WHERE
   status='ready'`. Concurrent workers that pick the same row have their UPDATEs
   serialized by the row lock — exactly one wins (update count 1); the losers
   retry the next candidate. This runs identically on H2 and PostgreSQL, so the
   reliability tests run on the default in-memory H2 test database.

   The authoritative job value is the JSON `payload` column (same wire format as
   the Redis adapter — keyword fields restored, instants as epoch-millis). The
   indexed columns (queue, priority_rank, status, execute_at, locked_at) exist
   only for ordering, claiming, and reclaim.

   Caveats:
   - The lease is fixed, not renewed (no heartbeat): a job still running after
     `lease-ms` is reclaimed and may run again concurrently. Set `:lease-ms`
     comfortably above your longest job, and/or keep handlers idempotent.
     (Lease renewal is a possible future addition.)
   - `payload` is `VARCHAR(1000000)`; keep serialized jobs under ~1 MB."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [wagoe.jobs.ports :as ports])
  (:import [java.sql Timestamp]
           [java.time Instant]))

(def default-lease-ms
  "How long a claimed job may stay in-flight before `reclaim-abandoned-jobs!`
   considers its worker dead and returns it to the ready queue."
  (* 60 1000))

(defn- priority-rank
  "Lower rank dequeues first."
  [priority]
  (case (or priority :normal)
    :critical 0
    :high     1
    :normal   2
    :low      3
    2))

;; --- serialization (same JSON contract as the Redis adapter) ----------------

(defn- serialize-job [job]
  (json/generate-string
   (-> job
       (update :execute-at   #(when % (.toEpochMilli ^Instant %)))
       (update :created-at   #(.toEpochMilli ^Instant %))
       (update :updated-at   #(when % (.toEpochMilli ^Instant %)))
       (update :started-at   #(when % (.toEpochMilli ^Instant %)))
       (update :completed-at #(when % (.toEpochMilli ^Instant %))))))

(defn- deserialize-job [json-str]
  (when json-str
    (-> (json/parse-string json-str true)
        (update :id          #(java.util.UUID/fromString %))
        (update :job-type    #(when % (keyword %)))
        (update :status      #(when % (keyword %)))
        (update :queue       #(when % (keyword %)))
        (update :priority    #(when % (keyword %)))
        (update :execute-at   #(when % (Instant/ofEpochMilli %)))
        (update :created-at   #(Instant/ofEpochMilli %))
        (update :updated-at   #(when % (Instant/ofEpochMilli %)))
        (update :started-at   #(when % (Instant/ofEpochMilli %)))
        (update :completed-at #(when % (Instant/ofEpochMilli %))))))

(defn- ts ^Timestamp [^Instant instant] (when instant (Timestamp/from instant)))

;; --- schema -----------------------------------------------------------------

(defn create-jobs-table!
  "Create the `job_queue` table + ready-scan index if absent. Portable DDL
   (H2 + PostgreSQL). Call once at startup, or ship as a migration."
  [ds]
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS job_queue (
                        id            UUID PRIMARY KEY,
                        queue         VARCHAR(255) NOT NULL,
                        priority_rank INT NOT NULL DEFAULT 2,
                        status        VARCHAR(20) NOT NULL DEFAULT 'ready',
                        execute_at    TIMESTAMP,
                        locked_by     VARCHAR(255),
                        locked_at     TIMESTAMP,
                        payload       VARCHAR(1000000) NOT NULL,
                        created_at    TIMESTAMP NOT NULL)"])
  (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS ix_job_queue_ready
                        ON job_queue (queue, status, priority_rank, created_at)"]))

;; --- adapter ----------------------------------------------------------------

(defn- opts [] {:builder-fn rs/as-unqualified-lower-maps})

(defn insert-job!
  "INSERT a job row using the given `connectable` — a datasource, a connection,
   or (the point of this fn) an open `next.jdbc` transaction. Returns the job id.

   Used by `enqueue-job!` (autocommit via the adapter's datasource) and by
   `enqueue-in-tx!` (the caller's transaction, for a transactional enqueue)."
  [connectable queue-name job]
  (let [job-id     (:id job)
        scheduled? (some? (:execute-at job))]
    (jdbc/execute-one!
     connectable
     ["INSERT INTO job_queue
         (id, queue, priority_rank, status, execute_at, payload, created_at)
         VALUES (?,?,?,?,?,?,?)"
      job-id (name queue-name) (priority-rank (:priority job))
      (if scheduled? "scheduled" "ready")
      (ts (:execute-at job))
      (serialize-job job)
      (ts (:created-at job))])
    job-id))

(defrecord DbJobQueue [ds lease-ms]
  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    (let [job-id (insert-job! ds queue-name job)]
      (log/info "Enqueued job" {:job-id job-id :queue queue-name})
      job-id))

  (schedule-job! [this queue-name job execute-at]
    (ports/enqueue-job! this queue-name (assoc job :execute-at execute-at)))

  (dequeue-job! [_ queue-name worker-id]
    ;; Claim the best ready candidate; on a lost race (someone else claimed it
    ;; first) try the next one, until we win a row or the ready set is empty.
    (loop []
      (if-let [cand (jdbc/execute-one!
                     ds
                     ["SELECT id, payload FROM job_queue
                        WHERE queue = ? AND status = 'ready'
                        ORDER BY priority_rank, created_at LIMIT 1"
                      (name queue-name)]
                     (opts))]
        (let [n (::jdbc/update-count
                 (jdbc/execute-one!
                  ds
                  ["UPDATE job_queue SET status = 'processing', locked_by = ?, locked_at = ?
                      WHERE id = ? AND status = 'ready'"
                   worker-id (ts (Instant/now)) (:id cand)]))]
          (if (pos? n)
            (deserialize-job (:payload cand))
            (recur)))
        nil)))

  (ack-job! [_ _queue-name _worker-id job-id]
    (jdbc/execute-one! ds ["DELETE FROM job_queue WHERE id = ?" job-id])
    true)

  (reclaim-abandoned-jobs! [_ queue-name]
    (let [cutoff (ts (.minusMillis (Instant/now) (long lease-ms)))
          n (::jdbc/update-count
             (jdbc/execute-one!
              ds
              ["UPDATE job_queue
                  SET status = 'ready', locked_by = NULL, locked_at = NULL
                  WHERE queue = ? AND status = 'processing' AND locked_at < ?"
               (name queue-name) cutoff]))]
      (when (pos? n) (log/info "Reclaimed abandoned jobs" {:queue queue-name :count n}))
      {:reclaimed n}))

  (peek-job [_ queue-name]
    (some-> (jdbc/execute-one!
             ds
             ["SELECT payload FROM job_queue
                WHERE queue = ? AND status = 'ready'
                ORDER BY priority_rank, created_at LIMIT 1"
              (name queue-name)]
             (opts))
            :payload
            deserialize-job))

  (delete-job! [_ job-id]
    (pos? (::jdbc/update-count
           (jdbc/execute-one! ds ["DELETE FROM job_queue WHERE id = ?" job-id]))))

  (queue-size [_ queue-name]
    (-> (jdbc/execute-one!
         ds
         ["SELECT COUNT(*) AS n FROM job_queue WHERE queue = ? AND status = 'ready'"
          (name queue-name)]
         (opts))
        :n long))

  (list-queues [_]
    (->> (jdbc/execute! ds ["SELECT DISTINCT queue FROM job_queue"] (opts))
         (map (comp keyword :queue))
         vec))

  (process-scheduled-jobs! [_]
    (::jdbc/update-count
     (jdbc/execute-one!
      ds
      ["UPDATE job_queue SET status = 'ready'
          WHERE status = 'scheduled' AND execute_at <= ?"
       (ts (Instant/now))]))))

(defn enqueue-in-tx!
  "Transactional (outbox) enqueue: insert the job into the queue **within the
   caller's open transaction**, so the job commits atomically with the business
   change. If the business transaction rolls back, no job is left behind; if it
   commits, a worker picks the job up. This is the durable alternative to a
   dual-write (enqueueing to an external queue separately from the DB commit,
   where a crash between the two loses the job or runs it for a rolled-back
   change).

   `tx` is a next.jdbc transactable already inside a transaction, e.g.:

     (jdbc/with-transaction [tx ds]
       (orders/create! tx order)
       (db/enqueue-in-tx! tx :emails receipt-job))

   Because the queue is a table in the same database, no separate outbox table
   or relay is needed — the queue row *is* the outbox row. `tx` MUST be a
   transaction the caller manages (do not pass a bare datasource, or the enqueue
   would autocommit and defeat the point). Returns the job id."
  [tx queue-name job]
  (insert-job! tx queue-name job))

(defn create-db-job-queue
  "Create a DB-backed job queue over the given `next.jdbc` datasource `ds`.
   `:lease-ms` (default 60s) is the in-flight lease after which a claimed job is
   reclaimable. Call `create-jobs-table!` once before use (or run the migration)."
  [ds & {:keys [lease-ms] :or {lease-ms default-lease-ms}}]
  (->DbJobQueue ds lease-ms))
