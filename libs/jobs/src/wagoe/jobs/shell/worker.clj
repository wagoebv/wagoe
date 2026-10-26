(ns wagoe.jobs.shell.worker
  "Background job worker implementation.

   Workers poll job queues, execute jobs, and handle retries. This module
   provides a production-ready worker with:
   - Graceful shutdown
   - Configurable polling intervals
   - Automatic retry handling
   - Scheduled job processing
   - Job handler registry
   - Comprehensive error handling"
  (:require [wagoe.jobs.core.job :as job]
            [wagoe.jobs.core.outcome :as outcome]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.outcomes :as outcomes]
            [wagoe.jobs.shell.adapters.redis :as redis-adapter]
            [wagoe.observability.core.sql-redaction :as redaction]
            [wagoe.observability.errors.ports :as errors]
            ;; Loaded for its effect: `log/error e` below is written redacted.
            [wagoe.observability.logging.shell.redacting-factory]
            [wagoe.observability.tracing.ports :as tracing-ports]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Job Handler Registry
;; =============================================================================

(defrecord JobRegistry [handlers]  ; atom: job-type -> handler-fn
  ports/IJobRegistry

  (register-handler! [_ job-type handler-fn]
    (swap! handlers assoc job-type handler-fn)
    (log/info "Registered job handler" {:job-type job-type})
    job-type)

  (unregister-handler! [_ job-type]
    (let [existed? (contains? @handlers job-type)]
      (swap! handlers dissoc job-type)
      (when existed?
        (log/info "Unregistered job handler" {:job-type job-type}))
      existed?))

  (get-handler [_ job-type]
    (get @handlers job-type))

  (list-handlers [_this]
    (vec (keys @handlers))))

(defn create-job-registry
  "Create a new job handler registry.

   Returns:
     JobRegistry implementing IJobRegistry"
  []
  (->JobRegistry (atom {})))

;; =============================================================================
;; Worker State
;; =============================================================================

(defrecord WorkerState
           [id              ; UUID
            queue-name      ; Keyword
            running?        ; atom: boolean
            current-job     ; atom: job or nil
            processed-count ; atom: integer
            failed-count    ; atom: integer
            started-at      ; Instant
            last-heartbeat]) ; atom: Instant

(defn- create-worker-state
  "Create initial worker state."
  [queue-name]
  (->WorkerState
   (UUID/randomUUID)
   queue-name
   (atom true)
   (atom nil)
   (atom 0)
   (atom 0)
   (Instant/now)
   (atom (Instant/now))))

;; =============================================================================
;; Job Execution
;; =============================================================================

(defn failure
  "What a job failure stores and logs: no value a database driver quoted, since
   it lands in the jobs table (BOU-633). :type names the kind of failure, the
   thrown class has its own key (BOU-323)."
  [^Throwable e]
  (let [^Throwable shown (redaction/redacted-exception e)
        trace            (java.io.StringWriter.)]
    ;; Into a writer: plain .printStackTrace goes to System.err, and the stored
    ;; trace was always empty.
    (.printStackTrace shown (java.io.PrintWriter. trace))
    {:message         (.getMessage shown)
     :type            :handler-error
     :exception-class (-> e class .getName)
     :stacktrace      (str trace)}))

(defn fatal?
  "Whether `t` is an error no job should swallow: the JVM may not survive it.
   Out of memory, an internal VM error, a killed thread. A StackOverflowError
   has unwound by the time it is caught, so it is the handler's failure, as an
   AssertionError or ExceptionInInitializerError is (BOU-834)."
  [^Throwable t]
  (or (and (instance? VirtualMachineError t) (not (instance? StackOverflowError t)))
      (= "java.lang.ThreadDeath" (.getName (class t)))))

(defn- execute-job-handler
  "Execute job handler function with error handling.

   Args:
     handler-fn - Function (fn [args] -> {:success? boolean :result any :error map})
     job-args - Job arguments map

   Returns:
     Result map with :success?, :result, :error"
  [handler-fn job-args]
  (try
    (let [result (handler-fn job-args)]
      (if (:success? result)
        result
        (assoc result :success? false)))
    (catch Throwable t
      (when (fatal? t) (throw t))
      {:success? false
       :error    (failure t)})))

(defn- run-handler
  "Run `handler-fn` on `job`'s args. A job that carries a tenant runs inside
   that tenant's schema through `:tenant-runner` (fn [tenant-id f]), and fails
   when there is none rather than running against public."
  [config handler-fn job]
  (if-let [tenant-id (get-in job [:metadata :tenant-id])]
    (if-let [in-tenant (:tenant-runner config)]
      ;; A failed handler throws its result out, so the tenant transaction
      ;; rolls back rather than commit half a job that will be retried. Typed,
      ;; so `with-tenant-schema` rethrows it unwrapped.
      (try
        (in-tenant tenant-id
                   #(let [result (execute-job-handler handler-fn (:args job))]
                      (if (:success? result)
                        result
                        (throw (ex-info "Tenant job failed"
                                        {:type :job-failed ::failed result})))))
        (catch Throwable t
          (when (fatal? t) (throw t))
          (or (::failed (ex-data t))
              {:success? false :error (failure t)})))
      {:success? false
       :error    {:type    :configuration-error
                  :message (str "Job " (:id job) " belongs to tenant " tenant-id
                                ", and this worker has no tenant runner; enable :wagoe/tenant")}})
    (execute-job-handler handler-fn (:args job))))

(defn- handle-missing-handler!
  "Handle a dequeued job whose type has no handler registered on THIS instance.

   With N instances each registering only the handlers they own, an instance can
   legitimately dequeue a job-type it cannot run. Rather than failing it straight
   to the dead-letter queue (a silent drop — another instance may own the
   handler), the job is re-enqueued for another worker and dead-lettered only
   after it has gone unhandled for too long.

   Two safeguards make this correct across a heterogeneous, loaded fleet:

   1. Delayed re-enqueue. The job is parked in the scheduled set
      :requeue-delay-ms into the future, NOT pushed back onto the ready queue. A
      handlerless worker treats a re-enqueue as 'processed' and would otherwise
      re-dequeue the same job on its next immediate poll, spinning. Parking it
      removes it from the ready queue during the delay so the requeuing worker
      backs off and other workers get a fair poll.

   2. Age-based (not attempt-based) give-up. The job is dead-lettered only once it
      has been continuously unhandled for :max-requeue-age-ms (default 5 min),
      tracked via :first-missing-at in metadata. Counting re-enqueue *attempts*
      would be wrong: under load, or with more handlerless workers than the
      attempt budget, wrong-worker misses would exhaust the budget and drop a job
      a slow handler-owning worker simply hadn't polled yet. A wall-clock window
      is independent of fleet size and load — every handler-owning worker gets the
      full window to claim the job. A high :max-requeues acts only as a runaway
      backstop (e.g. against a broken clock), not the primary policy.

   Args:
     config       - Worker config:
                    :requeue-delay-ms    delay before a re-enqueue is pollable (default 1000)
                    :max-requeue-age-ms  age after which an unhandled job is dead-lettered (default 300000)
                    :max-requeues        hard backstop on re-enqueue attempts (default 10000)
     store        - IJobStore
     registry     - IJobRegistry (this instance's handlers)
     worker-state - WorkerState
     job          - The dequeued job map

   Returns:
     The job to re-enqueue once this one is acked, or nil when it was
     dead-lettered. The caller enqueues it — see the note at the re-enqueue."
  [config store registry worker-state job]
  (let [job-id       (:id job)
        job-type     (:job-type job)
        delay-ms     (or (:requeue-delay-ms config) 1000)
        max-age-ms   (or (:max-requeue-age-ms config) 300000)
        max-requeues (or (:max-requeues config) 10000)
        now-ms       (.toEpochMilli (Instant/now))
        first-seen   (get-in job [:metadata :first-missing-at])
        requeues     (get-in job [:metadata :requeue-count] 0)
        aged-out?    (and first-seen (>= (- now-ms first-seen) max-age-ms))
        over-cap?    (>= requeues max-requeues)]
    (if-not (or aged-out? over-cap?)
      (do
        (log/warn "No handler for job type on this instance — re-enqueuing (delayed) for another worker"
                  {:job-id job-id :job-type job-type
                   :requeue-count requeues :delay-ms delay-ms
                   :unhandled-for-ms (when first-seen (- now-ms first-seen)) :max-age-ms max-age-ms
                   :local-handlers (ports/list-handlers registry)})
        ;; Returned rather than enqueued here, so one place decides when the
        ;; replacement is written relative to the ack — see the caller.
        ;;
        ;; Delayed via the scheduled set (see docstring): it leaves the ready
        ;; queue so this worker cannot immediately reacquire and tight-loop.
        (-> job
            (update :metadata assoc
                    :first-missing-at (or first-seen now-ms)
                    :requeue-count (inc requeues))
            (assoc :execute-at (.plusMillis (Instant/now) (long delay-ms)))))
      (let [reason (if aged-out?
                     (str "no worker handled it within " max-age-ms " ms")
                     (str "re-enqueue backstop of " max-requeues " attempts reached"))
            error  {:message (str "No handler registered for job type " job-type " on any worker: " reason)
                    :type    :no-handler}]
        (log/error "No handler for job type — dead-lettering"
                   {:job-id job-id :job-type job-type :reason reason
                    :unhandled-for-ms (when first-seen (- now-ms first-seen)) :requeue-count requeues})
        ;; A missing handler is a configuration error, not a transient failure:
        ;; exhaust the retry budget so update-job-status! routes it terminally to
        ;; the dead-letter queue instead of scheduling pointless retries.
        (ports/save-job! store (assoc job :retry-count (:max-retries job 3)))
        (ports/update-job-status! store job-id :failed error)
        (swap! (:failed-count worker-state) inc)
        nil))))

(defn- start-lease-renewal!
  "Renew the claim on `job-id` until the returned fn is called, so a job that
   outlives the queue's lease is not reclaimed and run twice (BOU-823)."
  [queue worker-state job-id]
  (if-not (satisfies? ports/ILeasedJobQueue queue)
    (constantly nil)
    (let [interval (ports/renew-interval-ms queue)
          stop     (promise)
          renew!   #(try
                      (when-not (ports/renew-lease! queue (:queue-name worker-state)
                                                    (:id worker-state) job-id)
                        (log/warn "Job lease lost while running" {:job-id job-id}))
                      (catch Exception e
                        (log/warn e "Job lease renewal failed" {:job-id job-id})))]
      (doto (Thread. ^Runnable #(while (= ::renew (deref stop interval ::renew)) (renew!))
                     (str "job-lease-" job-id))
        (.setDaemon true)
        (.start))
      ;; Not joined: a renewal after the ack matches no claim, and a hung
      ;; database must not hold up the worker.
      #(deliver stop true))))

(defn- with-reporter
  "`config` with `:report-outcome`, the fn each run's outcome goes to, built
   from its `:metrics`, `:error-reporter`, `:on-outcome` and
   `:report-failures-every-ms` unless it has one. Built once per pool, so the
   pool's workers share one error-tracker throttle."
  [config store]
  (cond-> config
    (not (:report-outcome config))
    (assoc :report-outcome (outcomes/reporter (assoc config :store store)))))

(defn- process-single-job!
  "Process a single job from queue.

   Args:
     config - Worker configuration map (:max-requeues for missing-handler budget)
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation
     worker-state - WorkerState

   Returns:
     true if job was processed, false if queue was empty"
  [config queue store registry worker-state]
  (when-let [job (ports/dequeue-job! queue (:queue-name worker-state) (:id worker-state))]
    (let [job-id   (:id job)
          job-type (:job-type job)
          tracer   (:tracer config)
          ;; Anything to put back on the queue, enqueued in the `finally` just
          ;; ahead of the ack. Held rather than enqueued at the decision point
          ;; only so there is one place that does it.
          requeue  (atom nil)
          fail!
          (fn [error]
            (let [updated (job/fail-job job error (Instant/now))]
              (ports/update-job-status! store job-id :failed error)
              (swap! (:failed-count worker-state) inc)
              ;; The retry the docstring and the changelog promised and the
              ;; code never scheduled: a retryable failure was recorded as
              ;; :retrying, acked, and never run again (BOU-418 review).
              ;; Decided from the job in hand rather than from the store's
              ;; answer, so a store that has not seen this job cannot turn a
              ;; retryable failure into a silent drop.
              (when (= :retrying (:status updated))
                (reset! requeue (job/prepare-retry updated
                                                   (:retry-config config)
                                                   (Instant/now)
                                                   (rand-int 100))))))
          run-job!
          (fn []
            (reset! (:current-job worker-state) job)
            (let [stop-renewal! (start-lease-renewal! queue worker-state job-id)]
              (try
                ;; Resolve the handler BEFORE marking the job running, so a missing
                ;; handler can be re-routed without leaving the job stuck in :running.
                (if-let [handler-fn (ports/get-handler registry job-type)]
                  (do
                    (log/info "Processing job" {:job-id job-id :job-type job-type})
                    (ports/update-job-status! store job-id :running nil)
                    (let [started (Instant/now)
                          t0      (System/nanoTime)
                          result  (run-handler config handler-fn job)]
                      (when-let [report! (:report-outcome config)]
                        (report! (outcome/run-outcome job result started (Instant/now)
                                                      (quot (- (System/nanoTime) t0) 1000000))))
                      (if (:success? result)
                        (do
                          (ports/update-job-status! store job-id :completed (:result result))
                          (swap! (:processed-count worker-state) inc)
                          (log/info "Job completed successfully" {:job-id job-id}))
                        (do
                          (fail! (:error result))
                          (log/warn "Job failed" {:job-id job-id :error (dissoc (:error result) :stacktrace)})))))

                  ;; No handler on this instance — re-enqueue (bounded), never silent DLQ.
                  (reset! requeue
                          (handle-missing-handler! config store registry worker-state job)))

                (catch Throwable t
                  (when (fatal? t) (throw t))
                  ;; Unexpected error during processing
                  (fail! (failure t))
                  (log/error t "Unexpected error processing job" {:job-id job-id}))

                (finally
                  (stop-renewal!)
                  ;; The replacement goes back on the queue BEFORE the claim is
                  ;; released. Acking first opened a window in which the job was
                  ;; neither queued nor in flight, so a process that died there
                  ;; lost the retry outright — no reclaim could find it, on any
                  ;; backend (BOU-418 review). This order can only duplicate, and
                  ;; duplication is what at-least-once means.
                  (when-let [j @requeue]
                    (ports/enqueue-job! queue (:queue-name worker-state) j)
                    (log/info "Job re-queued" {:job-id      (:id j)
                                               :job-type    (:job-type j)
                                               :retry-count (:retry-count j)
                                               :execute-at  (:execute-at j)}))
                  ;; Ack removes the job from this worker's in-flight processing list.
                  ;; By here the job is completed, re-enqueued for retry, or dead-lettered
                  ;; — no longer in flight. A crash BEFORE this point leaves it in the
                  ;; processing list for reclaim-abandoned-jobs! to re-run (at-least-once).
                  (ports/ack-job! queue (:queue-name worker-state) (:id worker-state) job-id)
                  (reset! (:current-job worker-state) nil)
                  (reset! (:last-heartbeat worker-state) (Instant/now))))))]
      ;; A worker thread holds the previous job's error context (BOU-848).
      (errors/with-clean-context
       (:error-reporter config)
       ;; Emit a span per job execution when a tracer is configured (no-op safe).
       #(if tracer
          (tracing-ports/with-span* tracer (str "job " job-type)
            {:job.id (str job-id) :job.type (str job-type)}
            (fn [_span] (run-job!)))
          (run-job!)))
      true)))

;; =============================================================================
;; Scheduled Job Processing
;; =============================================================================

(defn- process-scheduled-jobs!
  "Process scheduled jobs that are due for execution.

   Args:
     queue - IJobQueue implementation

   Returns:
     Number of jobs moved to execution queues"
  [queue]
  (try
    (ports/process-scheduled-jobs! queue)
    (catch Exception e
      (log/error e "Error processing scheduled jobs")
      0)))

(defn run-pending!
  "Run every job that is due, on the calling thread, until no queue has one
   left. Returns how many ran. For tests: with a worker thread the outcome is
   a race (BOU-469). A retry or a scheduled job lands in the future, so this
   ends."
  [config queue store registry]
  (let [state  (create-worker-state :default)
        config (with-reporter config store)]
    (loop [n 0]
      ;; Every round: a job may enqueue one with an :execute-at already due,
      ;; which lands in the scheduled set rather than on a queue.
      (process-scheduled-jobs! queue)
      (let [ran (reduce (fn [ran q]
                          (cond-> ran
                            (process-single-job! config queue store registry (assoc state :queue-name q))
                            inc))
                        0
                        (ports/list-queues queue))]
        (if (pos? ran) (recur (+ n ran)) n)))))

;; =============================================================================
;; Worker Loop
;; =============================================================================

(defn- worker-loop
  "Main worker loop that polls queue and processes jobs.

   Uses exponential backoff when the queue is empty to reduce idle CPU usage
   without increasing job pickup latency. Backoff resets immediately when a
   job is successfully processed.

   Args:
     config - Worker configuration map
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation
     worker-state - WorkerState"
  [config queue store registry worker-state]
  (let [min-poll-ms          (or (:poll-interval-ms config) 100)
        max-poll-ms          (or (:max-poll-interval-ms config) 2000)
        scheduled-interval-ms (or (:scheduled-interval-ms config) 5000)
        heartbeat-interval-ms (or (:heartbeat-interval-ms config) 5000)
        last-scheduled-check (atom (Instant/now))
        last-heartbeat       (atom Instant/EPOCH)
        current-poll-ms      (atom min-poll-ms)]

    (while @(:running? worker-state)
      (try
        ;; Keep worker heartbeat fresh for distributed stats/monitoring.
        ;; Throttled: when jobs are flowing the loop spins per job, which
        ;; would otherwise send a Redis round-trip every iteration.
        (let [now (Instant/now)]
          (when (>= (.toMillis (java.time.Duration/between @last-heartbeat now))
                    heartbeat-interval-ms)
            (redis-adapter/heartbeat-worker! queue (:id worker-state))
            (reset! last-heartbeat now)))

        ;; Process scheduled jobs and reclaim abandoned in-flight jobs periodically
        (let [now (Instant/now)
              elapsed-ms (.toMillis (java.time.Duration/between @last-scheduled-check now))]
          (when (>= elapsed-ms scheduled-interval-ms)
            (let [moved (process-scheduled-jobs! queue)]
              (when (pos? moved)
                (log/debug "Moved scheduled jobs to execution queues" {:count moved})))
            (try
              (let [{:keys [reclaimed]} (ports/reclaim-abandoned-jobs! queue (:queue-name worker-state))]
                (when (and reclaimed (pos? reclaimed))
                  (log/warn "Reclaimed abandoned jobs onto the ready queue" {:count reclaimed})))
              (catch Exception e
                (log/error e "Error reclaiming abandoned jobs")))
            (reset! last-scheduled-check now)))

        ;; Process next job from queue; apply exponential backoff on empty queue
        (let [processed? (process-single-job! config queue store registry worker-state)]
          (if processed?
            ;; Job found — reset backoff so next pickup is immediate
            (reset! current-poll-ms min-poll-ms)
            ;; No jobs — sleep, then double interval up to max
            (do
              (Thread/sleep @current-poll-ms)
              (swap! current-poll-ms #(min max-poll-ms (* 2 %))))))

        (catch InterruptedException _e
          (log/info "Worker interrupted, shutting down" {:worker-id (:id worker-state)})
          (reset! (:running? worker-state) false))

        (catch Throwable t
          (when (fatal? t)
            (log/error t "Fatal error, worker stopping" {:worker-id (:id worker-state)})
            (throw t))
          (log/error t "Unexpected error in worker loop" {:worker-id (:id worker-state)})
          (Thread/sleep @current-poll-ms)
          (swap! current-poll-ms #(min max-poll-ms (* 2 %)))))))

  (log/info "Worker stopped" {:worker-id (:id worker-state)
                              :processed (:processed-count worker-state)
                              :failed (:failed-count worker-state)}))

;; =============================================================================
;; Worker Management
;; =============================================================================

(defrecord Worker
           [state         ; WorkerState
            thread        ; Thread running worker-loop
            queue         ; IJobQueue
            store         ; IJobStore
            registry      ; IJobRegistry
            config]       ; Configuration map

  ports/IJobWorker

  (process-job! [_ job]
    ;; Manual job processing (for testing or direct invocation)
    (let [_job-id (:id job)
          job-type (:job-type job)]
      (if-let [handler-fn (ports/get-handler registry job-type)]
        (errors/with-clean-context (:error-reporter config)
                                   #(run-handler config handler-fn job))
        {:success? false
         :error {:message (str "No handler registered for job type: " job-type)
                 :type :no-handler}})))

  (start-worker! [_this]
    (:id state))  ; Already started in create-worker

  (stop-worker! [_ worker-id]
    (when (= worker-id (:id state))
      (log/info "Stopping worker" {:worker-id worker-id})
      (reset! (:running? state) false)
      (when thread
        (.join thread 5000))  ; Wait up to 5 seconds for graceful shutdown
      (redis-adapter/unregister-worker! queue worker-id)
      true))

  (worker-status [_ worker-id]
    (when (= worker-id (:id state))
      {:id (:id state)
       :queue-name (:queue-name state)
       :status (if @(:running? state) :running :stopped)
       :current-job @(:current-job state)
       :processed-count @(:processed-count state)
       :failed-count @(:failed-count state)
       :started-at (:started-at state)
       :last-heartbeat @(:last-heartbeat state)})))

(defn create-worker
  "Create and start a background job worker.

   Args:
     config - Worker configuration map with:
              :queue-name - Queue to process (keyword, required)
              :poll-interval-ms - Milliseconds between polls (default 1000)
              :scheduled-interval-ms - Check scheduled jobs interval (default 5000)
              :virtual-threads? - Run the loop on a virtual thread (default false)
              :tenant-runner - (fn [tenant-id f]) that calls f in the tenant's
                               schema; a job with :metadata :tenant-id fails
                               without one
              :metrics, :error-reporter - where each run's outcome goes
                               (optional, see `wagoe.jobs.shell.outcomes`)
              :on-outcome - (fn [outcome]) called after each run (optional)
              :report-failures-every-ms - error-tracker throttle (default 1h)
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation

   Returns:
     Worker implementing IJobWorker"
  [config queue store registry]
  (let [config (with-reporter config store)
        queue-name (or (:queue-name config) :default)
        worker-state (create-worker-state queue-name)
        worker (->Worker worker-state nil queue store registry config)
        loop-fn #(worker-loop config queue store registry worker-state)
        thread-name (str "job-worker-" (:id worker-state))
        ^java.lang.Thread$Builder builder (if (:virtual-threads? config)
                                              (Thread/ofVirtual)
                                              (.daemon (Thread/ofPlatform) true))
        thread (.unstarted (.name builder thread-name) ^Runnable loop-fn)]

    ;; Fail-loud guard: a worker with no handlers cannot run any job type — every
    ;; job it dequeues is re-enqueued and ultimately dead-lettered. Warn at startup
    ;; so a misconfigured (or differing) handler set surfaces immediately.
    (when (empty? (ports/list-handlers registry))
      (log/warn "Job worker started with NO registered handlers — it cannot process any job type. Register handlers before starting workers."
                {:worker-id (:id worker-state) :queue-name queue-name}))

    (.start thread)
    (redis-adapter/register-worker! queue (:id worker-state) queue-name)

    (log/info "Started worker" {:worker-id (:id worker-state) :queue-name queue-name})

    (assoc worker :thread thread)))

(defn create-worker-pool
  "Create multiple workers for parallel job processing.

   Args:
     config - Worker pool configuration with:
              :queue-name - Queue to process
              :worker-count - Number of workers (default 4)
              :poll-interval-ms - Polling interval
              :scheduled-interval-ms - Scheduled check interval
     queue - IJobQueue implementation
     store - IJobStore implementation
     registry - IJobRegistry implementation

   Returns:
     Vector of Worker instances"
  [config queue store registry]
  (let [worker-count (or (:worker-count config) 4)
        config       (with-reporter config store)]
    (log/info "Creating worker pool" {:worker-count worker-count :queue-name (:queue-name config)})
    (vec (repeatedly worker-count #(create-worker config queue store registry)))))

(defn stop-worker-pool!
  "Stop all workers in a pool gracefully.

   Args:
     workers - Vector of Worker instances"
  [workers]
  (log/info "Stopping worker pool" {:worker-count (count workers)})
  (doseq [worker workers]
    (ports/stop-worker! worker (:id (:state worker)))))

(defn worker-pool-status
  "Get status of all workers in a pool.

   Args:
     workers - Vector of Worker instances

   Returns:
     Vector of worker status maps"
  [workers]
  (mapv #(ports/worker-status % (:id (:state %))) workers))
