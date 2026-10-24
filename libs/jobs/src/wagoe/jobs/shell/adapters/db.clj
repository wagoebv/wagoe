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

   The authoritative job value is the transit `payload` column (same wire format
   as the Redis adapter, `wagoe.jobs.shell.adapters.codec`). The
   indexed columns (queue, priority_rank, status, execute_at, locked_at) exist
   only for ordering, claiming, and reclaim.

   The worker renews the lease while a handler runs (`ports/ILeasedJobQueue`),
   so `lease-ms` bounds how long a crashed worker's job waits, not how long a
   job may run (BOU-823).

   Caveats:
   - `payload` is `VARCHAR(1000000)` (`LONGTEXT` on MySQL); keep serialized
     jobs under ~1 MB."
  (:require [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.platform.lease :as lease]
            [wagoe.platform.core.database.constraint :as constraint]
            [wagoe.platform.ports.database :as db-ports]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [wagoe.core.utils.text :as text]
            [clojure.tools.logging :as log]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.core.outcome :as outcome]
            [wagoe.jobs.shell.adapters.codec :as codec]
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
  (db/create-index-if-not-exists! ctx "ix_job_store_dead_letter" :job_store [:dead_letter :updated_at])
  ;; IJobOutcomes: one row per job type. Times are epoch millis, as in
  ;; job_recurring, so no driver turns them into a local date-time.
  (execute! (:datasource ctx)
            [(format "CREATE TABLE IF NOT EXISTS job_outcomes (
                        job_type               VARCHAR(255) PRIMARY KEY,
                        last_outcome           VARCHAR(20) NOT NULL,
                        last_started_ms        BIGINT NOT NULL,
                        last_duration_ms       BIGINT NOT NULL,
                        last_succeeded_ms      BIGINT NULL,
                        last_failed_ms         BIGINT NULL,
                        last_error             VARCHAR(%d) NULL,
                        failures_since_success BIGINT NOT NULL)" outcome/max-error-length)]))

(defn create-job-store-table!
  "Create the `job_store` table + its indexes if absent, on H2, PostgreSQL or
   MySQL. Call once at startup, or ship as a migration."
  [ds]
  (let [ctx (db/context-of ds)]
    (create-job-store-table* ctx (ddl-types ctx))))

(defn- column-names
  "`table`'s lower-case column names, from an empty result's metadata: the
   platform's introspection expects its own datasource, and this may be any."
  [ds table]
  (with-open [conn (jdbc/get-connection ds)
              stmt (.createStatement conn)
              rs   (.executeQuery stmt (str "SELECT * FROM " table " WHERE 1 = 0"))]
    (let [md (.getMetaData rs)]
      (into #{} (map #(text/lower-case (.getColumnName md (int %)))) (range 1 (inc (.getColumnCount md)))))))

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
                             created_at    %s NOT NULL,
                             unique_key    VARCHAR(255) NULL)" id ts ts payload ts)])
    (db/create-index-if-not-exists! ctx "ix_job_queue_ready" :job_queue
                                    [:queue :status :priority_rank :created_at])
    ;; A table from before BOU-834 lacks the column. Every engine lets a unique
    ;; index hold many NULLs, so only jobs that carry a key are constrained.
    (db/add-columns-if-not-exist! ctx :job_queue [[:unique_key "VARCHAR(255) NULL"]]
                                  (column-names ds "job_queue"))
    (db/create-index-if-not-exists! ctx "ux_job_queue_unique_key" :job_queue [:unique_key]
                                    {:unique? true})))

(defn- kw-name [x] (when x (name x)))

(defn- upsert-job!
  "Write `job`, preserving `dead-letter?` when it is nil (i.e. leave the flag
   alone). UPDATE-then-INSERT rather than MERGE or ON CONFLICT: those spell
   differently on H2 and PostgreSQL, and this adapter's contract is portable SQL."
  [ds job dead-letter?]
  (let [now     (ts (Instant/now))
        flag    (boolean dead-letter?)
        payload (codec/encode-job job)
        n       (::jdbc/update-count
              (execute-one!
               ds
               (if (nil? dead-letter?)
                 ["UPDATE job_store SET job_type = ?, queue = ?, status = ?,
                     updated_at = ?, payload = ? WHERE id = ?"
                  (kw-name (:job-type job)) (kw-name (:queue job))
                  (kw-name (or (:status job) :pending)) now payload (:id job)]
                 ["UPDATE job_store SET job_type = ?, queue = ?, status = ?,
                     dead_letter = ?, updated_at = ?, payload = ? WHERE id = ?"
                  (kw-name (:job-type job)) (kw-name (:queue job))
                  (kw-name (or (:status job) :pending)) flag now payload (:id job)])))]
    (when (zero? n)
      (execute-one!
       ds
       ["INSERT INTO job_store (id, job_type, queue, status, dead_letter, updated_at, payload)
           VALUES (?,?,?,?,?,?,?)"
        (:id job) (kw-name (:job-type job)) (kw-name (:queue job))
        (kw-name (or (:status job) :pending)) flag now payload]))
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

(defn- duplicate-key? [^java.sql.SQLException e]
  (= :unique (:kind (constraint/violation {:sql-state  (.getSQLState e)
                                           :error-code (.getErrorCode e)
                                           :message    (ex-message e)}))))

(defn- insert-queue-row!
  "INSERT `job`'s queue row; false when its :unique-key is held. In a savepoint
   inside a caller's transaction: PostgreSQL refuses every later statement of
   a transaction after a failed one."
  [connectable sqlv unique?]
  (try
    (db/in-savepoint (when (open-transaction? connectable) connectable)
                     #(execute-one! connectable sqlv))
    true
    (catch java.sql.SQLException e
      (if (and unique? (duplicate-key? e)) false (throw e)))))

(defn- insert-job!
  "Write a job row using the given `connectable` — a datasource, a connection,
   or an open `next.jdbc` transaction. Returns the job id, or nil when the
   job's :unique-key is held by a job still in the queue.

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
        payload    (codec/encode-job job)
        updated    (::jdbc/update-count
                    (execute-one!
                     connectable
                     ["UPDATE job_queue
                         SET queue = ?, priority_rank = ?, status = ?, execute_at = ?,
                             payload = ?, locked_by = NULL, locked_at = NULL
                         WHERE id = ?"
                      (name queue-name) (priority-rank (:priority job)) status
                      (ts (:execute-at job)) payload job-id]))]
    ;; An UPDATE first, so a job re-enqueued by the worker keeps its key: the
    ;; row, and with it the key, goes only when an ack deletes it.
    (if (and (zero? updated)
             (not (insert-queue-row!
                   connectable
                   ["INSERT INTO job_queue
                       (id, queue, priority_rank, status, execute_at, payload, created_at, unique_key)
                       VALUES (?,?,?,?,?,?,?,?)"
                    job-id (name queue-name) (priority-rank (:priority job)) status
                    (ts (:execute-at job))
                    payload
                    (ts (:created-at job))
                    (some-> (:unique-key job) str)]
                   (some? (:unique-key job)))))
      (do (log/info "Job refused: its unique key is held" {:job-id job-id :unique-key (:unique-key job)})
          nil)
      (do
        ;; And into the store, on the same connectable — so a transactional enqueue
        ;; commits the history row with the business change too. Without this the
        ;; store never saw a job it had not been handed directly: the worker's
        ;; `update-job-status!` found nothing, `ack-job!` deleted the queue row, and
        ;; a completed job left no history while a failed one never dead-lettered
        ;; (BOU-418 review).
        (upsert-job! connectable (assoc job :queue queue-name) nil)
        job-id))))

(defrecord DbJobQueue [ds lease-ms]
  ;; Implemented here and NOT by the Redis / in-memory adapters: outbox
  ;; semantics need the queue to live in the same database as the business data.
  ports/ITransactionalJobQueue

  (enqueue-in-tx! [_ tx queue-name job]
    (assert-open-transaction! tx queue-name)
    (when-let [job-id (insert-job! tx queue-name job)]
      (log/info "Enqueued job in caller transaction"
                {:job-id job-id :queue queue-name})
      job-id))

  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    ;; :scheduled? distinguishes an immediate enqueue from schedule-job!, which
    ;; funnels through here too. It was lost when the INSERT moved into
    ;; insert-job!, leaving the two indistinguishable in logs (BOU-252).
    (when-let [job-id (insert-job! ds queue-name job)]
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
            (codec/decode-job (:payload cand))
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
            codec/decode-job))

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
       (ts (Instant/now))])))

  ports/ILeasedJobQueue

  (renew-interval-ms [_]
    ;; A third of the lease: two renewals can fail before it lapses.
    (max 10 (quot (long lease-ms) 3)))

  (renew-lease! [_ _queue-name worker-id job-id]
    (pos? (::jdbc/update-count
           (execute-one! ds ["UPDATE job_queue SET locked_at = ?
                                WHERE id = ? AND locked_by = ? AND status = 'processing'"
                             (ts (Instant/now)) job-id worker-id])))))

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
            codec/decode-job))

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
           (map (comp codec/decode-job :payload))
           vec)))

  (failed-jobs [_ limit]
    (->> (execute! ds ["SELECT payload FROM job_store
                          WHERE dead_letter = TRUE ORDER BY updated_at LIMIT ?"
                       limit]
                   (opts))
         (map (comp codec/decode-job :payload))
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

(extend-type DbJobStore
  ports/IDeadLetterRetention
  (purge-dead-letters-before! [{:keys [ds]} cutoff]
    ;; updated_at is when the job last failed: a dead letter is not written again.
    (db/delete-in-batches! (db/context-of ds) :job_store
                           [:and [:= :dead_letter true] [:< :updated_at (ts cutoff)]])))

(defn create-db-job-store
  "Create a DB-backed job store over the given `next.jdbc` datasource `ds`.
   Call `create-job-store-table!` once before use (or run the migration)."
  [ds]
  (->DbJobStore ds))

;; =============================================================================
;; Idempotency keys and recurring state
;; =============================================================================
;;
;; Times are epoch-millis BIGINTs read from the database clock, so replicas
;; with skewed clocks agree on who holds the lease and which claim expired.

(defn- insert-unless-present!
  "INSERT `sqlv`; false instead of an exception when the row already exists."
  [ds sqlv]
  (try (execute-one! ds sqlv) true
       (catch java.sql.SQLException e
         (if (duplicate-key? e) false (throw e)))))

(defn create-recurring-tables!
  "Create `job_idempotency_keys`, `job_recurring` and the ticker lease row if
   absent. Called at boot under `:provider :db`."
  [ds]
  (execute! ds ["CREATE TABLE IF NOT EXISTS job_idempotency_keys (
                   scope      VARCHAR(255) NOT NULL,
                   idem_key   VARCHAR(255) NOT NULL,
                   expires_ms BIGINT NOT NULL,
                   PRIMARY KEY (scope, idem_key))"])
  (db/create-index-if-not-exists! (db/context-of ds) "ix_job_idempotency_keys_expires" :job_idempotency_keys [:expires_ms])
  (execute! ds ["CREATE TABLE IF NOT EXISTS job_recurring (
                   name         VARCHAR(255) PRIMARY KEY,
                   last_tick_ms BIGINT NOT NULL)"])
  (lease/create-table! ds :job_ticker_lease "ticker"))

(defrecord DbIdempotencyStore [ds now-sql]
  ports/IIdempotencyStore
  (claim-key! [_ scope key ttl-ms]
    (or (insert-unless-present!
         ds [(str "INSERT INTO job_idempotency_keys (scope, idem_key, expires_ms) VALUES (?, ?, "
                  now-sql " + ?)")
             scope key ttl-ms])
        ;; Present: take it over only once it has expired.
        (= 1 (::jdbc/update-count
              (execute-one! ds [(str "UPDATE job_idempotency_keys SET expires_ms = " now-sql " + ?
                                      WHERE scope = ? AND idem_key = ? AND expires_ms <= " now-sql)
                                ttl-ms scope key])))))
  (release-key! [_ scope key]
    (execute-one! ds ["DELETE FROM job_idempotency_keys WHERE scope = ? AND idem_key = ?" scope key]))
  (purge-expired-keys! [_]
    (::jdbc/update-count
     (execute-one! ds [(str "DELETE FROM job_idempotency_keys WHERE expires_ms <= " now-sql)]))))

(defn- now-sql [ds] (db-ports/now-ms-sql (:adapter (db/context-of ds))))

(defn create-db-idempotency-store
  "An idempotency store on `ds`. Call `create-recurring-tables!` first."
  [ds]
  (->DbIdempotencyStore ds (now-sql ds)))

(defrecord DbRecurringState [ds lease]
  ports/IRecurringState
  (acquire-lease! [_ owner lease-ms]
    (lease/claim! lease owner lease-ms))
  (release-lease! [_ owner]
    (lease/release! lease owner))
  (last-tick [_ schedule-name]
    (some-> (execute-one! ds ["SELECT last_tick_ms FROM job_recurring WHERE name = ?" schedule-name]
                          (opts))
            :last_tick_ms
            long
            Instant/ofEpochMilli))
  (record-tick! [_ schedule-name tick]
    ;; UPDATE first: after the first tick the row exists, and a duplicate-key
    ;; exception on every tick is wasted work.
    (let [ms      (.toEpochMilli ^Instant tick)
          update! #(::jdbc/update-count
                    (execute-one! ds ["UPDATE job_recurring SET last_tick_ms = ? WHERE name = ?" ms schedule-name]))]
      (when (zero? (update!))
        (when-not (insert-unless-present! ds ["INSERT INTO job_recurring (name, last_tick_ms) VALUES (?, ?)"
                                              schedule-name ms])
          (update!)))
      tick)))

(defn create-db-recurring-state
  "Recurring-job state on `ds`, shared by every replica on it. Call
   `create-recurring-tables!` first."
  [ds]
  (->DbRecurringState ds (lease/create-lease ds :job_ticker_lease "ticker")))

;; =============================================================================
;; Job outcomes (IJobOutcomes)
;; =============================================================================

(defn- job-type-key [job-type] (str (symbol job-type)))

(defn- epoch-ms [^Instant t] (some-> t .toEpochMilli))

(defn- update-outcome!
  "One UPDATE: the failure count goes up or back to 0 in the statement itself,
   so concurrent workers need no lock or read. The number of rows it changed."
  [ds {:keys [job-type outcome started-at finished-at duration-ms] :as o}]
  (::jdbc/update-count
   (execute-one!
    ds
    (if (= :success outcome)
      ["UPDATE job_outcomes SET last_outcome = 'success', last_started_ms = ?, last_duration_ms = ?,
          last_succeeded_ms = ?, failures_since_success = 0
          WHERE job_type = ?"
       (epoch-ms started-at) duration-ms (epoch-ms finished-at) (job-type-key job-type)]
      ["UPDATE job_outcomes SET last_outcome = 'failure', last_started_ms = ?, last_duration_ms = ?,
          last_failed_ms = ?, last_error = ?, failures_since_success = failures_since_success + 1
          WHERE job_type = ?"
       (epoch-ms started-at) duration-ms (epoch-ms finished-at) (outcome/error-message o)
       (job-type-key job-type)]))))

(defn- ->outcome-record [row]
  (let [at #(some-> % long Instant/ofEpochMilli)]
    {:job-type               (keyword (:job_type row))
     :last-outcome           (keyword (:last_outcome row))
     :last-started-at        (at (:last_started_ms row))
     :last-duration-ms       (long (:last_duration_ms row))
     :last-succeeded-at      (at (:last_succeeded_ms row))
     :last-failed-at         (at (:last_failed_ms row))
     :last-error             (:last_error row)
     :failures-since-success (long (:failures_since_success row))}))

(extend-type DbJobStore
  ports/IJobOutcomes
  (record-outcome! [{:keys [ds]} o]
    ;; UPDATE first: after a job type's first run the row exists.
    (when (zero? (update-outcome! ds o))
      (let [{:keys [last-outcome last-started-at last-duration-ms last-succeeded-at
                    last-failed-at last-error failures-since-success]}
            (outcome/after-outcome nil o)]
        (when-not (insert-unless-present!
                   ds ["INSERT INTO job_outcomes (job_type, last_outcome, last_started_ms, last_duration_ms,
                          last_succeeded_ms, last_failed_ms, last_error, failures_since_success)
                          VALUES (?,?,?,?,?,?,?,?)"
                       (job-type-key (:job-type o)) (name last-outcome) (epoch-ms last-started-at)
                       last-duration-ms (epoch-ms last-succeeded-at) (epoch-ms last-failed-at)
                       last-error failures-since-success])
          (update-outcome! ds o))))
    nil)
  (job-outcome [{:keys [ds]} job-type]
    (some-> (execute-one! ds ["SELECT * FROM job_outcomes WHERE job_type = ?" (job-type-key job-type)] (opts))
            ->outcome-record))
  (job-outcomes [{:keys [ds]}]
    (into {} (map (comp (juxt :job-type identity) ->outcome-record))
          (execute! ds ["SELECT * FROM job_outcomes"] (opts)))))
