# Jobs Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Background job processing with priority queues, scheduled execution, automatic retries with exponential backoff, and multi-tenant support. In-memory (dev/test) and Redis (production) backends.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.jobs.core.job` | Pure functions: job creation, state transitions, retry logic |
| `wagoe.jobs.ports` | Protocols: IJobQueue, IJobStore, IJobStats, IJobWorker, IJobRegistry |
| `wagoe.jobs.schema` | Malli schemas: job states, priorities, retry config |
| `wagoe.jobs.shell.adapters.in-memory` | In-memory adapter (atoms, for dev/test) |
| `wagoe.jobs.shell.adapters.redis` | Redis adapter (sorted sets, lists, hashes) |
| `wagoe.jobs.shell.adapters.db` | DB adapter (next.jdbc, H2/PostgreSQL) — durable jobs on your existing SQL database |
| `wagoe.jobs.shell.worker` | Worker implementation: polling, processing, pool management |
| `wagoe.jobs.shell.tenant-context` | Multi-tenant job execution with schema switching |
| `wagoe.jobs.shell.module-wiring` | Integrant lifecycle: runtime, queue, store, registry, workers |

## Integrant wiring

Enabling `:wagoe/jobs` builds the runtime. Nothing else to assemble.

```clojure
:wagoe/jobs {:provider :memory        ; :memory | :db | :redis
             :lease-ms 60000          ; :db only — in-flight lease
             :redis    {:host "localhost" :port 6379}
             :workers  {:count  1
                        :queues [:default]}}   ; one pool per queue
```

| Key | What it is |
|---|---|
| `:wagoe/job-queue` | `IJobQueue` — what you enqueue on, and what other modules take a ref to |
| `:wagoe/job-store` | `IJobStore` — job history and the dead-letter queue |
| `:wagoe/job-stats` | `IJobStats` — what the devtools dashboard reads. nil under `:provider :db` |
| `:wagoe/job-registry` | The handlers every enabled module contributed |
| `:wagoe/job-workers` | One pool of `:count` workers per queue in `:queues` |

Set `:workers {:count 0}` on a web node in the web/worker split — it enqueues
and processes nothing. The default of 1 is a single process that does both,
which is what an application without a deployment topology has.

**A worker polls one queue.** Work enqueued on a queue no pool names is never
processed and nothing reports it — push enqueued on `:push` while the only pool
polled `:default`, and every push sat there (BOU-418). If a module enqueues on
its own queue, list that queue here.

**Contributing handlers.** A module ships a component returning
`{job-type handler-fn}` and lists it under `:job-handlers` in its `ig-config`,
the way routes are contributed:

```clojure
(defn ig-config [settings {:keys [enabled]}]
  (cond-> {:components {:my/job-handlers {:service (ig/ref :my/service)}}}
    (contains? enabled :wagoe/jobs)
    (assoc :job-handlers [(ig/ref :my/job-handlers)])))
```

Guard it on `:wagoe/jobs` being enabled: without jobs there is no registry, and
a ref to one is a dangling ref that fails the boot. `libs/push` is the worked
example.

Until BOU-418 this namespace wired none of this — `:wagoe/jobs` was a settings
passthrough, so `java -jar wagoe.jar worker` booted and processed nothing.

## DB-backed adapter (`adapters.db`)

Durable jobs on your existing SQL database (H2 or PostgreSQL) — no Redis
required, survives a Redis outage.

```clojure
(require '[wagoe.jobs.shell.adapters.db :as db])
(db/create-jobs-table! ds)                 ; once at startup (or ship as a migration)
(db/create-job-store-table! ds)
(def q (db/create-db-job-queue ds :lease-ms 60000))
(def s (db/create-db-job-store ds))
```

`:provider :db` in the config above does both calls for you.

- Implements the same reliable-dequeue contract as Redis: `dequeue-job!` claims
  a row (status `processing`, `locked_by`/`locked_at`); `ack-job!` removes it;
  `reclaim-abandoned-jobs!` returns rows whose **lease** (`locked_at` older than
  `:lease-ms`) has expired back to `ready` (at-least-once).
- **Portable SQL** (no `SELECT … FOR UPDATE SKIP LOCKED`): claim is a SELECT of
  the best candidate + a conditional `UPDATE … WHERE status='ready'`; the row
  lock serialises concurrent workers (one wins, losers retry). Runs identically
  on H2 and PostgreSQL, so its reliability tests run on the default H2 test DB.
- The authoritative job is the `payload` column (same wire format as the
  Redis adapter); the other columns exist only for ordering/claim/reclaim.
- `job_store` is the **history and dead-letter** table, separate from
  `job_queue`, which holds only work still to do (`ack-job!` deletes the row).
  The worker records outcomes on `IJobStore`, so a DB queue without a DB store
  would lose every failed job on restart — which is what this adapter had until
  BOU-418. `adapter_surface_test.clj` sweeps all three stores against one table
  of cases, so the three cannot drift apart.

### Transactional enqueue (outbox)

Because the queue is a table in the same database, you can enqueue a job **in
the caller's transaction** — it commits atomically with the business change, so
a rolled-back business tx leaves no orphan job (no dual-write window). The queue
row *is* the outbox row; no separate outbox table or relay is needed.

```clojure
(require '[next.jdbc :as jdbc]
         '[wagoe.jobs.ports :as ports])

(jdbc/with-transaction [tx ds]
  (orders/create! tx order)                        ; business write
  (ports/enqueue-in-tx! queue tx :emails receipt-job))  ; job, same tx
;; commit -> both; rollback -> neither
```

**This is a capability, not a base guarantee.** `enqueue-in-tx!` lives on its own
protocol, `ports/ITransactionalJobQueue`, implemented only by the DB adapter —
outbox semantics require the queue to sit in the same database as the business
data, which Redis and in-memory cannot offer. Putting it on `IJobQueue` would
force every adapter to declare a method most of them could only `throw` from.

Ask before calling, rather than discovering the limitation at runtime:

```clojure
(if (ports/transactional-queue? queue)
  (jdbc/with-transaction [tx ds]
    (orders/create! tx order)
    (ports/enqueue-in-tx! queue tx :emails receipt-job))
  (do (orders/create! ds order)
      (ports/enqueue-job! queue :emails receipt-job)))  ; accepts the dual-write window
```

`tx` must be a caller-managed transaction. Passing a datasource **throws**
(`:type :validation-error`) — it would otherwise autocommit and silently
reintroduce the dual-write window this exists to close, with no error and no log.
A worker on the DB queue picks the job up after commit.

> `wagoe.jobs.shell.adapters.db/enqueue-in-tx!` still exists but is deprecated —
> calling into the adapter namespace couples application code to one
> implementation. Use the port.

## Job Handler Signature

A handler gets `:args` back with the types it was enqueued with — keywords,
UUIDs, `java.time` values — on every queue: `:db` and `:redis` store jobs as
transit (`wagoe.jobs.shell.adapters.codec`). Until BOU-689 they stored JSON and
handed keywords back as strings; a job written then still decodes, with its
`:args` as JSON left them. A type transit has no handler for arrives as its `str`.

```clojure
;; Handlers receive args map, return result map
(defn my-handler [args]
  {:success? true
   :result {:processed (:id args)}})

;; Failed handler
(defn failing-handler [args]
  {:success? false
   :error {:message "Processing failed" :type :processing-error}})
```

## Usage Patterns

```clojure
;; Register handler
(require '[wagoe.jobs.shell.worker :as worker])
(def registry (worker/create-job-registry))
(ports/register-handler! registry :send-email email-handler)

;; Enqueue job
(require '[wagoe.jobs.core.job :as job])
(let [new-job (job/create-job {:job-type :send-email
                                :args {:to "user@example.com"}
                                :priority :high
                                :max-retries 3}
                               (java.util.UUID/randomUUID))]
  (ports/enqueue-job! job-queue :default new-job))

;; Start worker
(def w (worker/create-worker
         {:queue-name :default :poll-interval-ms 1000}
         queue store registry))

;; Or worker pool
(def pool (worker/create-worker-pool
            {:queue-name :default :worker-count 5 :poll-interval-ms 1000}
            queue store registry))
(worker/stop-worker-pool! pool)
```

## Job State Transitions

```
pending → running → completed
                  → failed (after max retries)
running → retrying → pending → running → ...
```

## Retry Logic

- Default max-retries: 3
- Exponential backoff: `delay = initial-delay * 2^retry-count`
- Capped at 60 seconds with random jitter (±10%)
- Dead letter queue after max retries exhausted
- Tune with `:workers {:retry-config {:initial-delay-ms … :max-delay-ms … :jitter false}}`

Until BOU-418 this section described something the worker did not do: a
retryable failure was recorded as `:retrying`, acked, and never run again.

The replacement is written **before** the claim is released, never after: acking
first leaves a window in which the job is neither queued nor in flight, and a
process that dies there loses it with nothing to reclaim. That order requires an
ack that only releases a claim — the DB `ack-job!` deletes `WHERE id = ? AND
locked_by = ? AND status = 'processing'`, and `enqueue-job!` upserts, so a
re-enqueued job is ready and unlocked by the time the ack runs and is left
alone. The same condition stops a late ack from deleting a job another worker
reclaimed after a lease expiry.

## Missing Handlers (multi-instance)

The handler registry is per-process. When a worker dequeues a job whose type it
has no handler for, it does **not** silently dead-letter it — the job is
**re-enqueued** for another instance that registered the handler, and dead-lettered
(with `{:error {:type :no-handler}}`) only once it has gone unhandled for too long. A worker
created with an **empty** registry logs a loud warning at startup.

Two safeguards make this correct on a heterogeneous, loaded fleet:

- **Delayed re-enqueue** — the job is parked in the scheduled set
  `:requeue-delay-ms` into the future (default 1000), not pushed back onto the
  ready queue. A handlerless worker treats a re-enqueue as "processed", so without
  the delay it would re-dequeue the same job on its very next poll and spin.
  Parking removes it from the ready queue during the delay so the worker backs off
  and peers get a fair poll.
- **Age-based give-up** — the job is dead-lettered only after it has been
  continuously unhandled for `:max-requeue-age-ms` (default 300000 / 5 min),
  tracked via `[:metadata :first-missing-at]`. Give-up is deliberately **not**
  attempt-based: counting re-enqueue attempts would let wrong-worker misses (more
  handlerless workers than the budget, or simply high load) exhaust a budget and
  drop a job a slow handler-owning worker hadn't polled yet. A wall-clock window
  is independent of fleet size and load. `:max-requeues` (default 10000) remains
  only as a runaway backstop, not the primary policy.

Keep handler sets consistent across instances: a job-type that *no* worker
registers still burns its full requeue budget before dead-lettering.

## Scheduled-job Atomic Claim

Promotion of a due scheduled job to an execution queue is an **atomic claim**, so
concurrent workers can't both move (and thus run) the same job:
- **Redis**: `ZREM` on `jobs:scheduled` — only the worker whose `ZREM` returns 1 enqueues.
- **In-memory**: `swap-vals!` on the scheduled set — only the caller that removed the entry enqueues.

## Priority Queue Order

Jobs dequeued by priority: critical > high > normal > low.

## Gotchas

1. **Register handlers BEFORE starting workers** - late registration doesn't affect running workers
2. **Handlers are NOT transactional** - use `process-tenant-job!` for tenant-scoped DB operations
3. **Tenant jobs fail explicitly** if tenant not found (safety-first, not silent fallback)
4. **Scheduled jobs** are stored separately and periodically moved to execution queues (default every 5s)
5. **In-memory adapter** is single-process only - NOT for production
6. **Redis key patterns**: `job:{id}`, `queue:{name}:{priority}`, `jobs:scheduled`, `jobs:failed`

## Testing

```bash
clojure -M:test :jobs
```

Test fixture pattern:
```clojure
(def ^:dynamic *system* nil)
(def ^:dynamic *registry* nil)

(defn with-clean-system [f]
  (let [system (in-memory/create-in-memory-jobs-system)
        registry (worker/create-job-registry)]
    (binding [*system* system *registry* registry]
      (f))))
```

**A new `IJobQueue` operation gets covered in `test/wagoe/jobs/adapter_surface_test.clj`,
on the way in.** The three backends have separate suites that share no cases, so
nothing in them says db, in-memory and Redis behave alike — and for a long time
they didn't: three different orderings within a priority, two of them
newest-first. The sweep runs the port against all three and needs a Redis on
localhost:6379 (database 14, which it flushes); without one it compares two
backends and `the-sweep-covers-every-backend` fails rather than skipping
quietly. Where the backends genuinely cannot agree — in-memory has no
cross-process in-flight list, so it cannot reclaim — list the case in
`known-differences` with its reason instead of dropping the assertion.

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
