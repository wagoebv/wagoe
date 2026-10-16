(ns wagoe.jobs.shell.adapters.db
  "Database-backed job queue (IJobQueue) on next.jdbc.

   Lets an app run durable background jobs on its existing SQL database (H2,
   PostgreSQL or MySQL) without standing up Redis, and survive a Redis outage. Implements
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
   retry the next candidate. This runs identically on H2, PostgreSQL and MySQL, so the
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
   - `payload` is `VARCHAR(1000000)` (`LONGTEXT` on MySQL); keep serialized
     jobs under ~1 MB."
  (:require [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as db-ports]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.jobs.core.job :as job]
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

;; Ids go as text: MySQL Java-serialises a UUID parameter, so a WHERE on it
;; matched nothing, and PostgreSQL and H2 cast text to UUID (BOU-650).
(defn- encode-params [sqlv] (mapv #(if (uuid? %) (str %) %) sqlv))

(defn- execute-one! [conn sqlv & opts] (apply jdbc/execute-one! conn (encode-params sqlv) opts))

(defn- execute! [conn sqlv & opts] (apply jdbc/execute! conn (encode-params sqlv) opts))

;; --- schema -----------------------------------------------------------------

(defn- ddl-types
  "Column types from the engine's adapter: MySQL has no UUID type, its VARCHAR
   stops at 65535 bytes and its TIMESTAMP at whole seconds (BOU-650)."
  [{:keys [adapter]}]
  (assoc (if (= :mysql (db-ports/engine adapter))
           {:payload (db-ports/column-type adapter :text) :ts (db-ports/column-type adapter :instant)}
           {:payload "VARCHAR(1000000)" :ts "TIMESTAMP"})
         :id (db-ports/column-type adapter :uuid)))

(defn- create-job-store-table* [ctx {:keys [id payload ts]}]
  (execute! (:datasource ctx)
            [(format "CREATE TABLE IF NOT EXISTS job_store (
                        id           %s PRIMARY KEY,
                        job_type     VARCHAR(255),
                        queue        VARCHAR(255),
                        status       VARCHAR(20) NOT NULL,
                        dead_letter  BOOLEAN NOT NULL DEFAULT FALSE,
                        updated_at   %s NOT NULL,
                        payload      %s NOT NULL)" id ts payload)])
  (db/create-index-if-not-exists! ctx "ix_job_store_filters" :job_store [:status :job_type :queue])
  (db/create-index-if-not-exists! ctx "ix_job_store_dead_letter" :job_store [:dead_letter :updated_at]))

(defn create-job-store-table!
  "Create the `job_store` table + its indexes if absent, on H2, PostgreSQL or
   MySQL. Call once at startup, or ship as a migration."
  [ds]
  (let [ctx (db/context-of ds)]
    (create-job-store-table* ctx (ddl-types ctx))))

(defn create-jobs-table!
  "Create the `job_queue` table + ready-scan index if absent, and `job_store`
   with it, on H2, PostgreSQL or MySQL. Call once at startup, or ship as a
   migration.

   Both, because `enqueue-job!` writes a history row: a caller who created only
   the queue table would fail on the next insert (BOU-418 review)."
  [ds]
  (let [ctx                          (db/context-of ds)
        {:keys [id payload ts] :as types} (ddl-types ctx)]
    (create-job-store-table* ctx types)
    (execute! ds [(format "CREATE TABLE IF NOT EXISTS job_queue (
                             id            %s PRIMARY KEY,
                             queue         VARCHAR(255) NOT NULL,
                             priority_rank INT NOT NULL DEFAULT 2,
                             status        VARCHAR(20) NOT NULL DEFAULT 'ready',
                             execute_at    %s NULL,
                             locked_by     VARCHAR(255),
                             locked_at     %s NULL,
                             payload       %s NOT NULL,
                             created_at    %s NOT NULL)" id ts ts payload ts)])
    (db/create-index-if-not-exists! ctx "ix_job_queue_ready" :job_queue
                                    [:queue :status :priority_rank :created_at])))

(defn- kw-name [x] (when x (name x)))

(defn- upsert-job!
  "Write `job`, preserving `dead-letter?` when it is nil (i.e. leave the flag
   alone). UPDATE-then-INSERT rather than MERGE or ON CONFLICT: those spell
   differently on H2 and PostgreSQL, and this adapter's contract is portable SQL."
  [ds job dead-letter?]
  (let [now  (ts (Instant/now))
        flag (boolean dead-letter?)
        n    (::jdbc/update-count
              (execute-one!
               ds
               (if (nil? dead-letter?)
                 ["UPDATE job_store SET job_type = ?, queue = ?, status = ?,
                     updated_at = ?, payload = ? WHERE id = ?"
                  (kw-name (:job-type job)) (kw-name (:queue job))
                  (kw-name (or (:status job) :pending)) now (serialize-job job) (:id job)]
                 ["UPDATE job_store SET job_type = ?, queue = ?, status = ?,
                     dead_letter = ?, updated_at = ?, payload = ? WHERE id = ?"
                  (kw-name (:job-type job)) (kw-name (:queue job))
                  (kw-name (or (:status job) :pending)) flag now
                  (serialize-job job) (:id job)])))]
    (when (zero? n)
      (execute-one!
       ds
       ["INSERT INTO job_store (id, job_type, queue, status, dead_letter, updated_at, payload)
           VALUES (?,?,?,?,?,?,?)"
        (:id job) (kw-name (:job-type job)) (kw-name (:queue job))
        (kw-name (or (:status job) :pending)) flag now (serialize-job job)]))
    job))

;; --- adapter ----------------------------------------------------------------

(defn- opts [] {:builder-fn rs/as-unqualified-lower-maps})

(defn- open-transaction?
  "True only for a `java.sql.Connection` with autocommit OFF — i.e. a connection
   inside a caller-managed transaction.

   A datasource is not rejected because it fails; it is rejected because it
   SUCCEEDS. Passing one autocommits the insert, so the job is durable
   independently of the business change — silently restoring the dual-write
   window that transactional enqueue exists to close. The failure would be
   invisible: no error, no log, correct-looking code."
  [connectable]
  (and (instance? java.sql.Connection connectable)
       (not (.getAutoCommit ^java.sql.Connection connectable))))

(defn- assert-open-transaction!
  "Throw unless `tx` is an open transaction. Single definition on purpose: the
   port method and the deprecated adapter fn both need it, and two copies of a
   safety check drift — the first version of this PR already had them throwing
   different ex-data, leaving the deprecated path with nothing actionable."
  [tx queue-name]
  (when-not (open-transaction? tx)
    (throw (ex-info "enqueue-in-tx! requires an open transaction, not a datasource"
                    {:type       :validation-error
                     :queue-name queue-name
                     :hint       (str "pass the tx bound by jdbc/with-transaction; "
                                      "a datasource would autocommit and reopen the "
                                      "dual-write window")}))))

(defn- insert-job!
  "Write a job row using the given `connectable` — a datasource, a connection,
   or an open `next.jdbc` transaction. Returns the job id.

   An upsert, not a plain INSERT, because a worker re-enqueues a job it still
   holds: on a retry or a re-route the row exists and is `processing`, and this
   returns it to the ready (or scheduled) set and releases the claim. Writing it
   as a fresh INSERT would violate the primary key; enqueueing under a new id
   instead would break job identity across retries (BOU-418 review).

   PRIVATE on purpose. It accepts a datasource because `enqueue-job!` legitimately
   passes one (autocommit is correct for a non-transactional enqueue), so the
   transaction guard cannot live here. Public, it would be a third entry point
   that skips the guard entirely: `insert-job!` with a datasource reproduces the
   exact silent dual-write this change exists to prevent. Callers use
   `ports/enqueue-in-tx!`."
  [connectable queue-name job]
  (let [job-id     (:id job)
        scheduled? (some? (:execute-at job))
        status     (if scheduled? "scheduled" "ready")
        updated    (::jdbc/update-count
                    (execute-one!
                     connectable
                     ["UPDATE job_queue
                         SET queue = ?, priority_rank = ?, status = ?, execute_at = ?,
                             payload = ?, locked_by = NULL, locked_at = NULL
                         WHERE id = ?"
                      (name queue-name) (priority-rank (:priority job)) status
                      (ts (:execute-at job)) (serialize-job job) job-id]))]
    (when (zero? updated)
      (execute-one!
       connectable
       ["INSERT INTO job_queue
           (id, queue, priority_rank, status, execute_at, payload, created_at)
           VALUES (?,?,?,?,?,?,?)"
        job-id (name queue-name) (priority-rank (:priority job)) status
        (ts (:execute-at job))
        (serialize-job job)
        (ts (:created-at job))]))
    ;; And into the store, on the same connectable — so a transactional enqueue
    ;; commits the history row with the business change too. Without this the
    ;; store never saw a job it had not been handed directly: the worker's
    ;; `update-job-status!` found nothing, `ack-job!` deleted the queue row, and
    ;; a completed job left no history while a failed one never dead-lettered
    ;; (BOU-418 review).
    (upsert-job! connectable (assoc job :queue queue-name) nil)
    job-id))

(defrecord DbJobQueue [ds lease-ms]
  ;; Implemented here and NOT by the Redis / in-memory adapters: outbox
  ;; semantics need the queue to live in the same database as the business data.
  ports/ITransactionalJobQueue

  (enqueue-in-tx! [_ tx queue-name job]
    (assert-open-transaction! tx queue-name)
    (let [job-id (insert-job! tx queue-name job)]
      (log/info "Enqueued job in caller transaction"
                {:job-id job-id :queue queue-name})
      job-id))

  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    ;; :scheduled? distinguishes an immediate enqueue from schedule-job!, which
    ;; funnels through here too. It was lost when the INSERT moved into
    ;; insert-job!, leaving the two indistinguishable in logs (BOU-252).
    (let [job-id (insert-job! ds queue-name job)]
      (log/info "Enqueued job" {:job-id     job-id
                                :queue      queue-name
                                :scheduled? (some? (:execute-at job))})
      job-id))

  (schedule-job! [this queue-name job execute-at]
    (ports/enqueue-job! this queue-name (assoc job :execute-at execute-at)))

  (dequeue-job! [_ queue-name worker-id]
    ;; Claim the best ready candidate; on a lost race (someone else claimed it
    ;; first) try the next one, until we win a row or the ready set is empty.
    (loop []
      (if-let [cand (execute-one!
                     ds
                     ["SELECT id, payload FROM job_queue
                        WHERE queue = ? AND status = 'ready'
                        ORDER BY priority_rank, created_at LIMIT 1"
                      (name queue-name)]
                     (opts))]
        (let [n (::jdbc/update-count
                 (execute-one!
                  ds
                  ["UPDATE job_queue SET status = 'processing', locked_by = ?, locked_at = ?
                      WHERE id = ? AND status = 'ready'"
                   worker-id (ts (Instant/now)) (:id cand)]))]
          (if (pos? n)
            (deserialize-job (:payload cand))
            (recur)))
        nil)))

  (ack-job! [_ _queue-name worker-id job-id]
    ;; Only the claim this worker still holds. An unconditional delete removed
    ;; the row whatever its state, so a job re-enqueued for a retry — ready
    ;; again, unlocked — was deleted by the ack that followed it, and a job
    ;; another worker had reclaimed after a lease expiry was deleted out from
    ;; under it (BOU-418 review).
    (execute-one! ds ["DELETE FROM job_queue
                         WHERE id = ? AND locked_by = ? AND status = 'processing'"
                      job-id worker-id])
    true)

  (reclaim-abandoned-jobs! [_ queue-name]
    (let [cutoff (ts (.minusMillis (Instant/now) (long lease-ms)))
          n (::jdbc/update-count
             (execute-one!
              ds
              ["UPDATE job_queue
                  SET status = 'ready', locked_by = NULL, locked_at = NULL
                  WHERE queue = ? AND status = 'processing' AND locked_at < ?"
               (name queue-name) cutoff]))]
      (when (pos? n) (log/info "Reclaimed abandoned jobs" {:queue queue-name :count n}))
      {:reclaimed n}))

  (peek-job [_ queue-name]
    (some-> (execute-one!
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
           (execute-one! ds ["DELETE FROM job_queue WHERE id = ?" job-id]))))

  (queue-size [_ queue-name]
    (-> (execute-one!
         ds
         ["SELECT COUNT(*) AS n FROM job_queue WHERE queue = ? AND status = 'ready'"
          (name queue-name)]
         (opts))
        :n long))

  (list-queues [_]
    (->> (execute! ds ["SELECT DISTINCT queue FROM job_queue"] (opts))
         (map (comp keyword :queue))
         vec))

  (process-scheduled-jobs! [_]
    (::jdbc/update-count
     (execute-one!
      ds
      ["UPDATE job_queue SET status = 'ready'
          WHERE status = 'scheduled' AND execute_at <= ?"
       (ts (Instant/now))]))))

(defn ^:deprecated enqueue-in-tx!
  "DEPRECATED — use `wagoe.jobs.ports/enqueue-in-tx!` on the queue component.

   Kept so existing callers keep working, but reaching into this adapter
   namespace couples application code to one implementation. The capability now
   lives on `ports/ITransactionalJobQueue`, so callers can stay on the port and
   ask `ports/transactional-queue?` instead of knowing which adapter they have."
  [tx queue-name job]
  (assert-open-transaction! tx queue-name)
  (insert-job! tx queue-name job))

(defn create-db-job-queue
  "Create a DB-backed job queue over the given `next.jdbc` datasource `ds`.
   `:lease-ms` (default 60s) is the in-flight lease after which a claimed job is
   reclaimable. Call `create-jobs-table!` once before use (or run the migration).
   Ids go as text, so on PostgreSQL `ds` must be the platform's, whose URL sets
   `stringtype=unspecified`."
  [ds & {:keys [lease-ms] :or {lease-ms default-lease-ms}}]
  (->DbJobQueue ds lease-ms))

;; =============================================================================
;; Job store (IJobStore) — history and the dead-letter queue
;; =============================================================================
;;
;; `job_queue` holds work still to do; `ack-job!` deletes the row. The worker
;; records outcomes through IJobStore, and the dead-letter queue lives there
;; too (`worker.clj` saves the exhausted job and marks it failed). So a durable
;; queue paired with an in-memory store loses every failed job on restart —
;; which is why the DB adapter has a store of its own (BOU-418).

(defrecord DbJobStore [ds]
  ports/IJobStore

  (save-job! [_ job]
    (upsert-job! ds job nil))

  (find-job [_ job-id]
    (some-> (execute-one! ds ["SELECT payload FROM job_store WHERE id = ?" job-id]
                          (opts))
            :payload
            deserialize-job))

  (update-job-status! [this job-id status result]
    (when-let [job (ports/find-job this job-id)]
      (let [now     (Instant/now)
            updated (case status
                      :running   (job/start-job job now)
                      :completed (job/complete-job job result now)
                      :failed    (job/fail-job job result now)
                      :cancelled (job/cancel-job job now)
                      job)
            ;; Dead-letter exactly when the in-memory store does: a failure with
            ;; no retries left. Any other outcome clears the flag, so a retried
            ;; job does not linger in the dead-letter list.
            dead?   (and (= status :failed) (not (job/can-retry? updated)))]
        (upsert-job! ds updated dead?)
        updated)))

  (find-jobs [_ filters]
    (let [{:keys [status job-type queue]} filters
          clauses (cond-> []
                    status   (conj "status = ?")
                    job-type (conj "job_type = ?")
                    queue    (conj "queue = ?"))
          params  (cond-> []
                    status   (conj (kw-name status))
                    job-type (conj (kw-name job-type))
                    queue    (conj (kw-name queue)))
          sql     (str "SELECT payload FROM job_store"
                       (when (seq clauses) (str " WHERE " (str/join " AND " clauses))))]
      (->> (execute! ds (into [sql] params) (opts))
           (map (comp deserialize-job :payload))
           vec)))

  (failed-jobs [_ limit]
    (->> (execute! ds ["SELECT payload FROM job_store
                          WHERE dead_letter = TRUE ORDER BY updated_at LIMIT ?"
                       limit]
                   (opts))
         (map (comp deserialize-job :payload))
         vec))

  (retry-job! [this job-id]
    (when-let [job (ports/find-job this job-id)]
      (let [retry-config {:backoff-strategy :exponential
                          :initial-delay-ms 1000
                          :max-delay-ms     60000}
            retried      (job/prepare-retry job retry-config (Instant/now) (rand-int 100))]
        (upsert-job! ds retried false)
        ;; Back onto the queue as a scheduled row. The in-memory store does this
        ;; through state it shares with its queue; here the two adapters share
        ;; the database instead, which is the same guarantee by other means.
        (insert-job! ds (or (:queue retried) :default) retried)
        retried))))

(defn create-db-job-store
  "Create a DB-backed job store over the given `next.jdbc` datasource `ds`.
   Call `create-job-store-table!` once before use (or run the migration)."
  [ds]
  (->DbJobStore ds))
