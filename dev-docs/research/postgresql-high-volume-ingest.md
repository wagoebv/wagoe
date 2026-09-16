# PostgreSQL as a high-volume message sink

Research notes, 2026-09-08. Question: can PostgreSQL receive millions of
messages as table inserts, without losing any, and how fast?

Short answer: yes. A single mid-size server sustains a few hundred thousand
rows per second with batched inserts and full durability. The design choices
that matter are how inserts are grouped and when the sender is acknowledged.

---

## 1. What sets the ceiling

Rough figures for one server on local NVMe, a narrow table with a primary key
and one payload column:

| Insert style | Rows per second |
|---|---|
| One row per transaction, autocommit, `synchronous_commit = on` | 5k to 20k |
| Same, `synchronous_commit = off` | 30k to 80k |
| Multi-row INSERT, 500 to 1000 rows per transaction | 100k to 400k |
| COPY, or JDBC batch with `reWriteBatchedInserts=true` | 300k to 1M+ |

The bottleneck for single-row inserts is the fsync of the write-ahead log at
each commit, not CPU. Everything else follows from that.

Scale check: 1M per day is 12 rows per second and needs nothing. 1M per
minute is about 17k per second and needs batching or relaxed commit.

## 2. What is needed

- **Batch on the producer side.** Collect messages for a few milliseconds or
  up to N rows and insert them in one transaction. With next.jdbc that is
  `execute-batch!` plus `?reWriteBatchedInserts=true` on the pgjdbc URL,
  which rewrites the batch into one multi-row statement. For bulk feeds use
  COPY through pgjdbc `CopyManager`.
- **Keep `synchronous_commit = on`** if messages must not be lost. `off`
  triples single-row throughput but a crash loses up to about 600 ms of
  already-acknowledged commits. Never corrupts data.
- **Narrow hot table.** `bigint generated always as identity` key, not random
  UUIDv4 (scatters index writes, bloats the index). UUIDv7 is fine. Each extra
  index costs roughly a third of the throughput.
- **Partition by time** (hour or day, pg_partman or cron). Retention becomes
  `DROP PARTITION`, which is instant, instead of `DELETE`, which bloats and
  loads autovacuum. Insert-only partitions need almost no vacuuming.
- **Limit connections.** Small HikariCP pool per process. PgBouncer in
  transaction mode if there are many producer processes. Postgres degrades past
  a few hundred connections.
- **WAL tuning.** Data directory on local SSD. `max_wal_size` several GB,
  `checkpoint_completion_target = 0.9`, `wal_buffers = 64MB`,
  `shared_buffers` about a quarter of RAM. A latency sawtooth every few minutes
  means checkpoints are still forced by write volume.
- **UNLOGGED** only as a landing table drained into a logged one. It skips WAL
  and is truncated on crash.
- **Payload size.** Over about 2 KB goes to TOAST and doubles the writes.
- **If consumers read it as a queue,** claim with
  `SELECT ... FOR UPDATE SKIP LOCKED` and mark or delete in batches. This is
  PostgreSQL-only; the Wagoe jobs adapter deliberately avoids it to stay
  portable across H2 and SQLite. The events library already has a Redis
  Streams adapter to compare against.

## 3. Guaranteeing no loss

Data can be lost in exactly three windows:

| Window | At risk | Closed by |
|---|---|---|
| In-memory batch buffer before COMMIT returns | Everything buffered | Do not ack the sender until its batch committed |
| WAL not yet fsynced | Last few hundred ms of commits | `synchronous_commit = on` (default) |
| Primary disk destroyed | Everything since last replica sync | Synchronous replication (`synchronous_standby_names`) |

With ack-after-commit and `synchronous_commit = on`, the first two are zero.
The third is a hosting decision and applies equally to single-row inserts.

### The queue of batches

Same pattern as Kafka's producer (`linger.ms`): each message carries a
completion handle, a flusher drains the queue into one transaction, handles
complete after COMMIT. The HTTP handler waits on the handle before answering
201.

```clojure
(ns ingest.batcher
  (:require [next.jdbc :as jdbc])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit CompletableFuture]))

(defn start-batcher
  "Returns {:submit! f :stop! f}. submit! returns a CompletableFuture that
   completes after the row is committed, or fails."
  [ds {:keys [capacity max-batch linger-ms]
       :or   {capacity 10000 max-batch 500 linger-ms 10}}]
  (let [q       (ArrayBlockingQueue. capacity)
        running (atom true)
        drain   (fn []
                  (let [first-item (.poll q 100 TimeUnit/MILLISECONDS)]
                    (when first-item
                      (let [deadline (+ (System/nanoTime) (* linger-ms 1000000))
                            batch    (java.util.ArrayList. [first-item])]
                        (while (and (< (.size batch) max-batch)
                                    (pos? (- deadline (System/nanoTime))))
                          (when-let [it (.poll q (- deadline (System/nanoTime)) TimeUnit/NANOSECONDS)]
                            (.add batch it)))
                        batch))))
        flush!  (fn [batch]
                  (try
                    (jdbc/with-transaction [tx ds]
                      (jdbc/execute-batch! tx
                        "insert into msg (msg_id, topic, payload) values (?, ?, ?::jsonb)
                         on conflict (msg_id) do nothing"
                        (map (comp :params first) batch)
                        {}))
                    (doseq [[_ ^CompletableFuture f] batch] (.complete f true))
                    (catch Exception e
                      (doseq [[_ ^CompletableFuture f] batch] (.completeExceptionally f e)))))
        worker  (Thread. (fn [] (while @running
                                  (when-let [b (drain)] (flush! b)))))]
    (.start worker)
    {:submit! (fn [row]
                (let [f (CompletableFuture.)]
                  (if (.offer q [{:params row} f] 50 TimeUnit/MILLISECONDS)
                    f
                    (doto f (.completeExceptionally
                              (ex-info "ingest overloaded" {:type :unavailable}))))))
     :stop!   (fn [] (reset! running false) (.join worker))}))
```

Handler: `(.get (submit! row) 2 TimeUnit/SECONDS)` then 201, or map the
exception to 503.

Properties that matter:

- **No ack before commit.** A process crash loses only messages whose senders
  never got a 201. They retry: the normal at-least-once contract.
- **Idempotency key.** Retries after a crash, or after a lost response to a
  successful commit, would otherwise duplicate. Client-supplied `msg_id`,
  unique index, `on conflict do nothing`. Without it you get no-loss or
  no-duplicates, not both.
- **Bounded queue with backpressure.** `offer` with timeout turns overload
  into 503 instead of unbounded memory growth. An unbounded queue turns a slow
  disk into an OutOfMemoryError that loses everything buffered.
- **Whole-batch failure.** One bad row rolls back the batch. If bad rows are
  expected, retry that batch row by row and dead-letter the offender.

Run 2 to 4 workers on separate connections. Postgres merges concurrent
commits into shared WAL flushes (group commit), so parallel flushers raise
throughput without extra fsync cost.

### Latency cost, per message

| Component | Typical |
|---|---|
| Linger, waiting for the batch to fill | 0 to linger-ms, average 5 ms at 10 ms |
| Multi-row insert of 500 rows | 2 to 5 ms |
| COMMIT with fsync on NVMe | 1 to 3 ms |

About 10 to 20 ms added end to end. At 20k per second a 10 ms linger fills
batches of about 200, which is most of the gain. Past 1000 rows per batch the
gain flattens and latency keeps growing.

### If you ack before commit anyway

Exposure on process crash is `rate × (linger + commit time) + queued backlog`.
At 20k per second, 10 ms linger and a 10k queue: 300 to 10,300 messages,
unknown which. Queue capacity dominates, not the linger. Only acceptable for
sources that cannot retry (metrics, sensor samples). In that case
`synchronous_commit = off` server-side gives a similar speedup with a
smaller, better-defined window of about 600 ms.

## 4. Maximum rows per second with ack-after-commit

Ack-after-commit does not lower the ceiling. Batching sets throughput; waiting
for commit only adds latency, provided enough senders are in flight to fill
the batches.

Estimates for 8 cores, local NVMe, `msg` with primary key plus a unique index
on `msg_id`, payload about 200 bytes:

| Bottleneck | Arithmetic | Ceiling |
|---|---|---|
| Commit cycles per flusher | 500-row insert 2 to 5 ms, fsync 1 to 3 ms, so 150 to 250 commits/s per flusher | 4 flushers × 500 × 200 = 400k rows/s |
| Server CPU per row | Heap insert, two btree entries, jsonb parse: 5 to 10 µs per row per core | 100k to 200k rows/s per busy core |
| WAL bandwidth | 200 to 300 bytes of WAL per row | 400k rows/s is about 100 MB/s, fine on NVMe |

Realistic sustained figure for this design: **200k to 400k rows per second**
on such a server. COPY without secondary index: 500k to 1M. A laptop through
Docker Desktop: 20k to 60k, because volume I/O makes fsync slow; not
representative.

### The ceiling you hit first: in-flight requests

Little's law: rows/s = in-flight requests / latency. At 15 ms latency, 200k
rows/s needs 3000 requests in flight.

A Jetty pool of 200 threads blocking on `.get` caps you at about 200 / 0.015
= 13k requests/s and batches of at most 200 rows. Fixes, cheapest first:

1. **Client-side batching.** One request carrying 100 messages needs 30
   in-flight requests for the same row rate. Most ingest APIs settle here.
2. **Async handlers.** Hand the CompletableFuture to Ring's async arity so
   thousands of requests wait without thousands of threads.
3. **Bigger thread pool.** Works to a point; thousands of blocked threads cost
   memory and scheduler time for nothing.

## 5. How to test

Run each stage at least 5 minutes so it includes a checkpoint. Use a real
volume, not tmpfs.

### Disposable server

```bash
docker run -d --name pgbench-test -e POSTGRES_PASSWORD=pw -p 5433:5432 \
  -v pgbench-data:/var/lib/postgresql \
  postgres:18 -c max_wal_size=4GB -c checkpoint_completion_target=0.9 \
  -c wal_buffers=64MB -c shared_buffers=2GB -c synchronous_commit=on

export PGPASSWORD=pw
psql -h localhost -p 5433 -U postgres -c "
create table msg (
  id bigint generated always as identity primary key,
  received_at timestamptz not null default now(),
  topic text not null,
  payload jsonb not null
);"
```

### Three insert styles with pgbench

Single-row autocommit:

```bash
cat > single.sql <<'SQL'
\set t random(1, 50)
insert into msg (topic, payload) values ('topic-' || :t, '{"n":' || :t || ',"body":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}');
SQL
pgbench -h localhost -p 5433 -U postgres -n -c 16 -j 8 -T 300 -P 10 -f single.sql
```

Batched, 500 rows per transaction (multiply tps by 500 for rows/s):

```bash
cat > batch.sql <<'SQL'
\set t random(1, 50)
insert into msg (topic, payload)
select 'topic-' || :t, jsonb_build_object('n', g, 'body', repeat('x', 64))
from generate_series(1, 500) g;
SQL
pgbench -h localhost -p 5433 -U postgres -n -c 8 -j 8 -T 300 -P 10 -f batch.sql
```

Repeat both after
`alter system set synchronous_commit = off; select pg_reload_conf();`
to see the durability trade.

COPY as the upper bound:

```bash
psql -h localhost -p 5433 -U postgres -c "
select 'topic-' || (g % 50), jsonb_build_object('n', g, 'body', repeat('x', 64))
from generate_series(1, 5000000) g" -A -F $'\t' -t > msgs.tsv
time psql -h localhost -p 5433 -U postgres -c "\copy msg (topic, payload) from 'msgs.tsv'"
```

### Measure more than tps

Before and after each run:

```sql
select * from pg_stat_wal;                 -- wal_bytes: WAL volume per row
select * from pg_stat_checkpointer;        -- checkpoints forced by write volume
select pg_size_pretty(pg_total_relation_size('msg')),
       pg_size_pretty(pg_relation_size('msg_pkey'));
select n_tup_ins, n_dead_tup, last_autovacuum
  from pg_stat_user_tables where relname = 'msg';
```

`pgbench -P 10` prints latency per interval. A sawtooth is checkpoint stall.

### From Clojure

pgbench gives the server ceiling. The real ceiling includes JVM, HikariCP and
serialization. Point a next.jdbc loop at the same container, URL ending in
`?reWriteBatchedInserts=true`, `execute-batch!` with 500 to 1000 rows, 8 to
16 threads through a pool of the same size. Within a factor of two of the
pgbench batch figure means the application side is not the bottleneck.

### Prove no loss

- **Crash the ingest process.** Load generator records every `msg_id` it got
  a 201 for. `kill -9` the JVM mid-run, restart, continue. Afterwards every
  acked id must exist, and row count must equal distinct sent ids. Repeat ten
  times.
- **Crash Postgres.** Same run, `docker kill` the container, with
  `synchronous_commit` on and then off. On must show zero acked-but-missing
  rows. Off shows a few hundred, which is why the default stays on.
- **Overload.** Drive three times the sustainable rate. Expect 503s, flat
  memory, no missing acked rows.

### Pass criteria, written down before running

Target sustained rows/s, acceptable p99 insert latency, acceptable loss on
crash. Run at 1.5× target for 30 minutes: throughput flat, no latency
sawtooth, table plus index size growing linearly.

## 6. Local tooling available

psql, pgbench and pg_ctl 18.6 (`/opt/homebrew/opt/libpq/bin`), Docker.
The repo also carries embedded PostgreSQL 18.4 for tests via the `:test/pg`
and `:test/pg-mac` aliases in `deps.edn`.
