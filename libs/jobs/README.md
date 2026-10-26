# Background Jobs Module

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-jobs.svg)](https://clojars.org/com.wagoe/wagoe-jobs)

**Asynchronous job processing for Wagoe Framework**

Background job processing on Redis, your SQL database, or in memory, with:

- ✅ Distributed job queuing (Redis-backed)
- ✅ Priority queues (critical, high, normal, low)
- ✅ Scheduled jobs (run at specific time)
- ✅ Recurring jobs on cron schedules, one replica enqueueing each tick
- ✅ Idempotency keys (`enqueue-once!`)
- ✅ Automatic retries with exponential backoff
- ✅ Dead letter queue for failed jobs
- ✅ Job monitoring and statistics
- ✅ Pure functional core (FC/IS pattern)
- ✅ Pluggable adapters (Redis, database, in-memory)

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Examples](#usage-examples)
- [Configuration](#configuration)
- [Job Handlers](#job-handlers)
- [Monitoring](#monitoring)
- [Production Deployment](#production-deployment)
- [API Reference](#api-reference)

---

## Quick Start

### 1. Add the Dependency

```clojure
;; deps.edn — brings Jedis with it
{:deps {com.wagoe/wagoe-jobs {:mvn/version "1.0.1"}}}
```

In a Wagoe application, enabling `:wagoe/jobs` builds the queue, store, registry
and workers for you (see [AGENTS.md](AGENTS.md)). The steps below use the
library directly.

### 2. Create Job Queue

```clojure
(require '[wagoe.jobs.shell.adapters.redis :as redis-jobs])

;; Create Redis connection pool
(def redis-pool
  (redis-jobs/create-redis-pool
    {:host "localhost"
     :port 6379}))

;; Create job queue
(def job-queue (redis-jobs/create-redis-job-queue redis-pool))
(def job-store (redis-jobs/create-redis-job-store redis-pool))
```

### 3. Define Job Handler

```clojure
(require '[wagoe.jobs.ports :as ports])

(defn send-email-handler
  "Job handler for sending emails."
  [job-args]
  (try
    (let [{:keys [to subject body]} job-args]
      ;; Send email logic here
      (println "Sending email to" to)
      {:success? true
       :result {:sent-at (java.time.Instant/now)
                :message-id "msg-123"}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type (.getName (.getClass e))}})))

;; Register handler
(require '[wagoe.jobs.shell.worker :as worker])
(def job-registry (worker/create-job-registry))
(ports/register-handler! job-registry :send-email send-email-handler)
```

### 4. Enqueue Jobs

```clojure
(require '[wagoe.jobs.core.job :as job])

;; Create and enqueue immediate job
(let [job-input {:job-type :send-email
                 :args {:to "user@example.com"
                        :subject "Welcome!"
                        :body "Thanks for signing up."}
                 :priority :high}
      new-job (job/create-job job-input (java.util.UUID/randomUUID) (java.time.Instant/now))]

  (ports/enqueue-job! job-queue :default new-job))

;; Schedule job for future execution
(let [job-input {:job-type :send-reminder
                 :args {:user-id "123"}
                 :priority :normal}
      execute-at (.plusSeconds (java.time.Instant/now) 3600)  ; 1 hour from now
      new-job (job/schedule-job job-input (java.util.UUID/randomUUID) execute-at (java.time.Instant/now))]

  (ports/enqueue-job! job-queue :default new-job))
```

### 5. Process Jobs

```clojure
;; Workers dequeue, run the handler, ack, retry and dead-letter
(def workers (worker/create-worker-pool
               {:queue-name :default :worker-count 2}
               job-queue job-store job-registry))

(worker/stop-worker-pool! workers)
```

---

## Core Concepts

### Job Lifecycle

```
1. Created (pending) → 2. Enqueued → 3. Dequeued → 4. Running → 5. Completed/Failed
                                                         ↓
                                                    6. Retry (if failed)
                                                         ↓
                                                    7. Dead Letter (max retries)
```

**Job States:**
- **pending**: Job is queued, waiting to be processed
- **running**: Job is currently being processed
- **completed**: Job completed successfully
- **failed**: Job failed after all retries
- **retrying**: Job failed but will be retried
- **cancelled**: Job was cancelled before execution

### Priority Levels

Jobs can have different priorities:

- **critical**: Processed first (use sparingly!)
- **high**: Important jobs (notifications, payments)
- **normal**: Standard priority (default)
- **low**: Background tasks (cleanup, analytics)

### Retry Behavior

Failed jobs are automatically retried with exponential backoff:

```
Retry 1: 1 second delay
Retry 2: 2 seconds delay
Retry 3: 4 seconds delay
Retry 4: 8 seconds delay
...
Max: 60 seconds delay
```

**Configuration:**
```clojure
{:max-retries 3                ; Total retry attempts (default: 3)
 :backoff-strategy :exponential ; :linear, :exponential, or :constant
 :initial-delay-ms 1000        ; Initial retry delay (default: 1 second)
 :max-delay-ms 60000           ; Max retry delay (default: 60 seconds)
 :jitter true}                 ; Add random jitter (default: true)
```

---

## Recurring Jobs

```clojure
:wagoe/jobs {:recurring {:nightly-report {:cron "0 2 * * *"
                                          :zone "Europe/Amsterdam"}}}
```

Each tick enqueues a `:nightly-report` job; register its handler as usual.
Only the replica holding the ticker lease enqueues, and each tick is claimed
as an idempotency key, so it is enqueued once. After downtime the latest
missed tick runs once and earlier ones are skipped. Zones default to UTC.

At runtime: `(recurring/schedule-recurring! scheduler :nightly-report "0 2 * * *" {:zone "UTC"})`
on the `:wagoe/job-scheduler` component (`wagoe.jobs.shell.recurring`).

`(recurring/enqueue-once! queue idempotency-store :default job key)` enqueues
unless the job type and `key` were seen before; it returns nil for a duplicate.
`:wagoe/idempotency-store` is the store, usable by any module.

---

## Multi-Tenancy Support

The jobs module provides first-class support for multi-tenant applications with automatic tenant context propagation and schema isolation.

### Key Features

- **Tenant-Scoped Jobs**: Jobs automatically execute in the correct tenant's database schema
- **Transparent Context Propagation**: Tenant metadata flows from enqueue to execution
- **Schema Switching**: Automatic `SET search_path` for PostgreSQL tenant schemas
- **Tenant Isolation**: Jobs for tenant A cannot access tenant B's data
- **Backward Compatible**: Non-tenant jobs continue to work unchanged

### Enqueuing Tenant Jobs

```clojure
(require '[wagoe.jobs.shell.tenant-context :as tenant-jobs])

;; Enqueue job with tenant context
(tenant-jobs/enqueue-tenant-job! 
  job-queue 
  "tenant-123"             ; Tenant ID
  :send-email              ; Job type
  {:to "user@example.com"  ; Job arguments
   :subject "Welcome!"
   :body "Thanks for signing up."})

;; Job is stored with metadata: {:tenant-id "tenant-123"}
```

### Processing Tenant Jobs

With `:wagoe/tenant` on, the `:wagoe/jobs` wiring gives each worker a
`:tenant-runner`: a job whose `:metadata` carries `:tenant-id` runs its ordinary
`(fn [args])` handler in one transaction on that tenant's schema, with
`db/*tenant-id*` bound. Without a runner such a job fails rather than run against
public. Outside the wiring, build one with
`(tenant-jobs/tenant-runner db-ctx tenant-service tenant-schema-provider)` and
pass it as `:tenant-runner` to `worker/create-worker-pool`.

`process-tenant-job!` runs a single job the same way, for a handler that wants
the database context as its second argument:

```clojure
(tenant-jobs/process-tenant-job!
  job
  (fn [args db-ctx] {:success? true})   ; db-ctx carries the transaction as :tx
  db-ctx tenant-service tenant-schema-provider)
```

### How Schema Switching Works

**PostgreSQL**: Automatic `SET search_path TO tenant_xxx`:

```clojure
;; Job enqueued for tenant "acme-corp"
(tenant-jobs/enqueue-tenant-job! job-queue "acme-corp" :send-email {...})

;; Worker processes job
(tenant-jobs/process-tenant-job! job handler db-ctx tenant-service tenant-schema-provider)
;; → Executes: SET search_path TO tenant_acme_corp
;; → Handler runs in tenant schema
;; → After completion: SET search_path TO public
```

**Non-PostgreSQL**: `process-tenant-job!` runs the handler in public, with the
tenant id in the job's `:metadata`; the worker's tenant runner refuses the job instead:

```clojure
(defn handler [job-args db-ctx]
  (let [tenant-id (get-in job [:metadata :tenant-id])]  ; Available for manual filtering
    (jdbc/execute! db-ctx 
                   ["SELECT * FROM users WHERE tenant_id = ?" tenant-id])))
```

### Tenant Context Structure

```clojure
;; Extracted tenant context
{:tenant-id "uuid-123"              ; Tenant UUID
 :tenant-slug "acme-corp"           ; Human-readable slug
 :tenant-schema "tenant_acme_corp"  ; PostgreSQL schema name
 :tenant-entity {...}}              ; The tenant itself
```

### Use Cases

#### Tenant-Scoped Notifications

```clojure
(defn notify-tenant-users! [tenant-id event]
  (tenant-jobs/enqueue-tenant-job! 
    job-queue 
    tenant-id
    :send-notification
    {:event-type event
     :recipients :all-users}))

;; Handler automatically queries tenant's users table
(defn notification-handler [job-args db-ctx]
  (let [users (jdbc/execute! db-ctx ["SELECT email FROM users WHERE active = true"])]
    ;; Only returns users from tenant's schema
    (doseq [user users]
      (send-email! (:email user) ...))))
```

#### Scheduled Tenant Reports

```clojure
(defn schedule-tenant-report! [tenant-id]
  (let [tomorrow (-> (java.time.Instant/now) (.plus 1 java.time.temporal.ChronoUnit/DAYS))
        job-input {:job-type :generate-report
                   :args {:report-type :monthly-summary}
                   :metadata {:tenant-id tenant-id}}
        job (job/schedule-job job-input (java.util.UUID/randomUUID) tomorrow (java.time.Instant/now))]
    (job-ports/enqueue-job! job-queue :reports job)))
```

### Performance Characteristics

- **Tenant Resolution**: < 5ms (single database query, cacheable)
- **Schema Switching**: < 1ms (PostgreSQL session command)
- **Total Overhead**: < 10ms per job (verified in integration tests)

### Backward Compatibility

Jobs without tenant metadata continue to work unchanged:

```clojure
;; Old-style job (no tenant context)
(let [job (job/create-job {:job-type :send-email :args {...}} (random-uuid) (java.time.Instant/now))]
  (job-ports/enqueue-job! job-queue :default job))

;; Worker detects no tenant-id, processes in public schema
(when-let [job (job-ports/dequeue-job! job-queue :default worker-id)]
  (if (:tenant-id (:metadata job))
    (tenant-jobs/process-tenant-job! ...)  ; Tenant job
    (process-regular-job! ...)))           ; Regular job
```

### Testing

Comprehensive tests verify tenant isolation:

```bash
# Run tenant-context integration tests
clojure -M:test :jobs --focus wagoe.jobs.shell.tenant-context-test
```

### Migration Guide

**Before (Multi-Tenant Application Without Framework Support)**:

```clojure
(defn handler [job-args]
  (let [tenant-id (:tenant-id job-args)  ; Manual tenant extraction
        db-ctx (get-tenant-connection tenant-id)]
    (jdbc/execute! db-ctx ["SELECT * FROM users"])))
```

**After (With Tenant Jobs Module)**:

```clojure
(defn handler [job-args db-ctx]
  ;; db-ctx is already tenant-scoped!
  (jdbc/execute! db-ctx ["SELECT * FROM users"]))

;; Enqueue with tenant context
(tenant-jobs/enqueue-tenant-job! job-queue tenant-id :process-users {})
```

### API Reference

**`wagoe.jobs.shell.tenant-context`**

- `(enqueue-tenant-job! job-queue tenant-id job-type args [opts])` - Enqueue job with tenant context; returns the job id
- `(extract-tenant-context job tenant-service)` - Extract tenant metadata from job; throws when the tenant is gone
- `(process-tenant-job! job handler db-ctx tenant-service tenant-schema-provider)` - Process job with schema switching
- `(wrap-handler-with-tenant-context handler db-ctx tenant-service tenant-schema-provider)` - Same, as a `(fn [job])`
- `(tenant-runner db-ctx tenant-service tenant-schema-provider)` - The worker's `:tenant-runner`

---

## Usage Examples

### Example 1: Send Welcome Email

```clojure
(defn enqueue-welcome-email!
  "Enqueue job to send welcome email to new user."
  [job-queue user]
  (let [job-input {:job-type :send-email
                   :args {:to (:email user)
                          :subject "Welcome to Wagoe!"
                          :body (str "Hi " (:name user) ", welcome!")
                          :template :welcome}
                   :priority :high
                   :metadata {:user-id (:id user)}}
        new-job (job/create-job job-input (java.util.UUID/randomUUID) (java.time.Instant/now))]

    (ports/enqueue-job! job-queue :emails new-job)))
```

### Example 2: Process Uploads

```clojure
(defn enqueue-image-processing!
  "Enqueue job to process uploaded image."
  [job-queue upload-id file-path]
  (let [job-input {:job-type :process-image
                   :args {:upload-id upload-id
                          :file-path file-path
                          :sizes [:thumbnail :medium :large]}
                   :priority :normal
                   :max-retries 5}  ; Retry up to 5 times
        new-job (job/create-job job-input (java.util.UUID/randomUUID) (java.time.Instant/now))]

    (ports/enqueue-job! job-queue :media new-job)))
```

### Example 3: Scheduled Reports

```clojure
(defn schedule-daily-report!
  "Schedule daily report generation."
  [job-queue]
  (let [tomorrow-8am (-> (java.time.LocalDateTime/now)
                         (.plusDays 1)
                         (.withHour 8)
                         (.withMinute 0)
                         (.atZone (java.time.ZoneId/systemDefault))
                         (.toInstant))
        job-input {:job-type :generate-report
                   :args {:report-type :daily-summary
                          :recipients ["admin@example.com"]}
                   :priority :normal}
        new-job (job/schedule-job job-input (java.util.UUID/randomUUID) tomorrow-8am (java.time.Instant/now))]

    (ports/enqueue-job! job-queue :reports new-job)))
```

### Example 4: Bulk Operations

```clojure
(defn enqueue-bulk-export!
  "Enqueue job for bulk data export."
  [job-queue user-id filters]
  (let [job-input {:job-type :export-data
                   :args {:user-id user-id
                          :filters filters
                          :format :csv
                          :email-when-done true}
                   :priority :low  ; Low priority for bulk operations
                   :max-retries 1}  ; Don't retry long-running jobs
        new-job (job/create-job job-input (java.util.UUID/randomUUID) (java.time.Instant/now))]

    (ports/enqueue-job! job-queue :bulk-operations new-job)))
```

---

## Configuration

### Redis Configuration

```clojure
(def redis-config
  {:host "localhost"              ; Redis host
   :port 6379                     ; Redis port (default: 6379)
   :password "secret"             ; Redis password (optional)
   :database 0                    ; Redis database number (default: 0)
   :max-total 20                  ; Max connections (default: 20)
   :max-idle 10                   ; Max idle connections (default: 10)
   :min-idle 2                    ; Min idle connections (default: 2)
   :timeout 2000})                ; Connection timeout ms (default: 2000)

(def pool (redis-jobs/create-redis-pool redis-config))
```

### Production Configuration

```clojure
;; resources/conf/prod/config.edn
:wagoe/jobs
{:provider :redis
 :redis    {:host     #env REDIS_HOST
            :port     #long #or [#env REDIS_PORT 6379]
            :password #env REDIS_PASSWORD
            :max-total 50
            :max-idle  20}
 :workers  {:count  5                     ; workers per queue
            :queues [:default :emails]    ; one pool per queue; a queue not named here is never processed
            :poll-interval-ms 1000
            :scheduled-interval-ms 5000}}  ; how often due scheduled jobs are moved to their queue
```

---

## Job Handlers

### Handler Function Signature

```clojure
(defn my-job-handler
  "Job handler function.

  Args:
    job-args - Map of job arguments

  Returns:
    Result map with:
      :success? - Boolean indicating success/failure
      :result - Result data (if successful)
      :error - Error map (if failed) with:
               :message - Error message
               :type - Error type
               :stacktrace - Stack trace (optional)"
  [job-args]
  {:success? true
   :result {:completed-at (java.time.Instant/now)}})
```

### Example Handlers

**Send Email:**
```clojure
(defn send-email-handler [job-args]
  (try
    (let [{:keys [to subject body]} job-args
          result (email/send! to subject body)]
      {:success? true
       :result {:message-id (:id result)
                :sent-at (java.time.Instant/now)}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type :email-error
               :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e))}})))
```

**Process Payment:**
```clojure
(defn process-payment-handler [job-args]
  (try
    (let [{:keys [order-id amount payment-method]} job-args
          result (payment/charge! order-id amount payment-method)]

      (if (:success result)
        {:success? true
         :result {:transaction-id (:transaction-id result)
                  :status "completed"}}
        {:success? false
         :error {:message (:error-message result)
                 :type :payment-failed}}))
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type :payment-error}})))
```

**Generate Report:**
```clojure
(defn generate-report-handler [job-args]
  (try
    (let [{:keys [report-type start-date end-date]} job-args
          report-data (reports/generate report-type start-date end-date)
          file-path (reports/save-to-file report-data)]

      {:success? true
       :result {:file-path file-path
                :rows (count report-data)
                :generated-at (java.time.Instant/now)}})
    (catch Exception e
      {:success? false
       :error {:message (.getMessage e)
               :type :report-generation-error}})))
```

---

## Monitoring

### Job Statistics

```clojure
(require '[wagoe.jobs.ports :as ports])

;; Get overall statistics
(ports/job-stats job-stats)
;; =>
{:total-processed 1500
 :total-failed 23
 :total-succeeded 1477
 :queues [{:queue-name :default
           :size 5
           :processed-total 1200
           :failed-total 15
           :succeeded-total 1185}
          {:queue-name :emails
           :size 0
           :processed-total 300
           :failed-total 8
           :succeeded-total 292}]
 :workers [{:id "worker-1" :status :running}
           {:id "worker-2" :status :idle}]}

;; Get queue-specific statistics
(ports/queue-stats job-stats :default)
;; =>
{:queue-name :default
 :size 5
 :processed-total 1200
 :failed-total 15
 :succeeded-total 1185
 :avg-duration-ms 250.5}
```

### Failed Jobs

```clojure
;; Get failed jobs from dead letter queue
(ports/failed-jobs job-store 10)
;; => Vector of 10 most recent failed jobs

;; Retry a failed job
(ports/retry-job! job-store job-id)
```

### Job History

```clojure
;; Get recent jobs of specific type
(ports/job-history job-stats :send-email 20)
;; => 20 most recent email jobs with results
```

---

## Production Deployment

### 1. Start Workers

`:wagoe/jobs` starts `:wagoe/job-workers` and closes the pool on halt; modules
contribute handlers through `:job-handlers` (see [AGENTS.md](AGENTS.md)). Run the
same jar on every node and set `:workers {:count 0}` on web nodes that should only
enqueue. The framework's own uberjar also has a `worker` mode that serves no HTTP.

### 2. Monitor with HTTP Endpoints

```clojure
(defn job-stats-handler
  "GET /api/admin/jobs/stats"
  [job-stats]
  (fn [request]
    {:status 200
     :body (ports/job-stats job-stats)}))

(defn failed-jobs-handler
  "GET /api/admin/jobs/failed"
  [job-store]
  (fn [request]
    (let [limit (or (get-in request [:query-params :limit]) 20)]
      {:status 200
       :body {:jobs (ports/failed-jobs job-store limit)}})))

(defn retry-job-handler
  "POST /api/admin/jobs/:id/retry"
  [job-store]
  (fn [request]
    (let [job-id (get-in request [:path-params :id])]
      (if-let [job (ports/retry-job! job-store (java.util.UUID/fromString job-id))]
        {:status 200
         :body {:message "Job queued for retry"
                :job job}}
        {:status 404
         :body {:error {:type "not-found" :message "Job not found"}}}))))
```

### 3. Docker Deployment

**Dockerfile:**
```dockerfile
FROM clojure:temurin-21-tools-deps

WORKDIR /app
COPY . .

# Build uberjar
RUN clojure -T:build uber

# Run workers (the jar your build.clj writes; :workers in config decides what it processes)
CMD ["java", "-jar", "target/app.jar"]
```

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  workers:
    build: .
    depends_on:
      - redis
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      WORKER_COUNT: 5
    deploy:
      replicas: 3  # Run 3 worker containers

volumes:
  redis-data:
```

---

## API Reference

### Core Functions

**wagoe.jobs.core.job**

- `(create-job job-input job-id now)` - Create new job
- `(schedule-job job-input job-id execute-at now)` - Create scheduled job
- `(start-job job now)` - Mark job as running
- `(complete-job job result now)` - Mark job as completed
- `(fail-job job error now)` - Mark job as failed
- `(cancel-job job now)` - Cancel job
- `(calculate-retry-delay retry-count config jitter-ms)` - Calculate retry delay
- `(prepare-retry job retry-config now jitter-ms)` - Prepare job for retry

### Ports (Protocols)

**IJobQueue** - Job queue operations
- `enqueue-job!` - Add job to queue
- `schedule-job!` - Schedule job for future
- `dequeue-job!` - Reliably take the next job for a worker (moves it to a per-worker in-flight list)
- `ack-job!` - Acknowledge a finished job (removes it from the in-flight list)
- `reclaim-abandoned-jobs!` - Return a crashed worker's in-flight jobs to the ready queue (at-least-once)
- `delete-job!` - Remove job
- `queue-size` - Get queue size

**IJobStore** - Job persistence
- `save-job!` - Save job to store
- `find-job` - Find job by ID
- `update-job-status!` - Update job status
- `failed-jobs` - Get failed jobs
- `retry-job!` - Retry failed job

**IJobStats** - Job statistics
- `job-stats` - Get overall stats
- `queue-stats` - Get queue stats
- `job-history` - Get job history

---

## Best Practices

### 1. Keep Jobs Small
Jobs should complete in seconds, not minutes.

**Good:**
```clojure
{:job-type :send-email
 :args {:to "user@example.com" :template :welcome}}
```

**Bad:**
```clojure
{:job-type :process-all-users  ; Too broad!
 :args {}}
```

### 2. Use Appropriate Priorities
- **critical**: Only for truly urgent jobs (< 1% of jobs)
- **high**: Important but not urgent (payments, notifications)
- **normal**: Default for most jobs
- **low**: Background cleanup, analytics

### 3. Set Realistic Retry Limits
```clojure
;; Short-lived external API calls
{:max-retries 5}

;; Long-running data processing
{:max-retries 1}  ; Don't retry

;; Idempotent operations
{:max-retries 10}
```

### 4. Add Metadata for Debugging
```clojure
{:job-type :process-order
 :args {:order-id "123"}
 :metadata {:user-id "user-456"
            :source "mobile-app"
            :api-version "2.0"}}
```

---

## Troubleshooting

### Jobs Not Processing

**Check workers are running:**
```clojure
(ports/job-stats job-stats)
;; Check :workers count
```

**Check Redis connection:**
```bash
redis-cli ping
# Should return PONG
```

### High Failure Rate

**Check job handler errors:**
```clojure
(ports/failed-jobs job-store 10)
;; Inspect :error field
```

**Enable debug logging:**
```clojure
;; In logback.xml
<logger name="wagoe.jobs" level="DEBUG"/>
```

### Memory Issues

**Limit concurrent jobs:**
```clojure
;; Reduce worker count, under :wagoe/jobs
{:workers {:count 2}}  ; Instead of 10

;; Increase poll interval
{:workers {:poll-interval-ms 5000}}  ; 5 seconds instead of 1
```

---

## License

Part of Wagoe Framework - See main LICENSE file.

---

**Next:** See [Examples](../examples/) for complete working applications using background jobs.
