(ns wagoe.jobs.core.job
  "Core business logic for job management.

   Pure functions for creating, updating, and managing background jobs.
   No side effects - all I/O happens in the shell layer.")

;; =============================================================================
;; Job Creation
;; =============================================================================

(defn create-job
  "Create a new job from input.

   Args:
     job-input - Map with :job-type, :args, and optional fields
     job-id - UUID for the job (provided by caller)
     now - Current timestamp (provided by caller)

   Returns:
     Complete job map with defaults"
  [job-input job-id now]
  (cond-> {:id job-id
           :job-type (:job-type job-input)
           :queue (or (:queue job-input) :default)
           :args (:args job-input {})
           :status :pending
           :priority (or (:priority job-input) :normal)
           :retry-count 0
           :max-retries (or (:max-retries job-input) 3)
           :created-at now
           :updated-at now
           :metadata (or (:metadata job-input) {})}
    (:execute-at job-input)
    (assoc :execute-at (:execute-at job-input))))

(defn schedule-job
  "Create a scheduled job for future execution.

   Args:
     job-input - Job input map
     job-id - UUID for the job
     execute-at - Instant when job should run
     now - Current timestamp (provided by caller)

   Returns:
     Job map with execute-at set"
  [job-input job-id execute-at now]
  (-> (create-job job-input job-id now)
      (assoc :execute-at execute-at)))

;; =============================================================================
;; Job State Transitions
;; =============================================================================

(defn start-job
  "Mark job as running.

   Args:
     job - Job map
     now - Current timestamp (provided by caller)

   Returns:
     Updated job with :running status"
  [job now]
  (assoc job
         :status :running
         :started-at now
         :updated-at now))

(defn complete-job
  "Mark job as completed successfully.

   Args:
     job - Job map
     result - Result data from job execution
     now - Current timestamp (provided by caller)

   Returns:
     Updated job with :completed status"
  [job result now]
  (assoc job
         :status :completed
         :result result
         :completed-at now
         :updated-at now))

(defn fail-job
  "Mark job as failed.

   Args:
     job - Job map
     error - Error map with :message, :type, :stacktrace
     now - Current timestamp (provided by caller)

   Returns:
     Updated job with :failed or :retrying status"
  [job error now]
  (let [new-retry-count (inc (:retry-count job))
        should-retry? (< new-retry-count (:max-retries job))]
    (-> job
        (assoc :status (if should-retry? :retrying :failed)
               :retry-count new-retry-count
               :error error
               :updated-at now)
        (cond->
         (not should-retry?)
          (assoc :completed-at now)))))

(defn cancel-job
  "Mark job as cancelled.

   Args:
     job - Job map
     now - Current timestamp (provided by caller)

   Returns:
     Updated job with :cancelled status"
  [job now]
  (assoc job
         :status :cancelled
         :completed-at now
         :updated-at now))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn calculate-retry-delay
  "Calculate delay before next retry using exponential backoff.

   Args:
     retry-count - Current retry attempt (0-indexed)
     config - Retry configuration map with:
              :backoff-strategy - :linear, :exponential, or :constant
              :initial-delay-ms - Initial delay (default 1000ms)
              :max-delay-ms - Maximum delay (default 60000ms)
              :jitter - Whether jitter is enabled (default true)
     jitter-ms - Jitter in milliseconds (provided by caller)

   Returns:
     Delay in milliseconds"
  [retry-count config jitter-ms]
  (let [backoff-strategy (or (:backoff-strategy config) :exponential)
        initial-delay (or (:initial-delay-ms config) 1000)
        max-delay (or (:max-delay-ms config) 60000)
        jitter? (get config :jitter true)

        base-delay (case backoff-strategy
                     :linear (* initial-delay (inc retry-count))
                     :exponential (* initial-delay (Math/pow 2 retry-count))
                     :constant initial-delay)

        capped-delay (min base-delay max-delay)

        final-delay (if jitter?
                      (+ capped-delay (long (or jitter-ms 0)))
                      capped-delay)]
    (long final-delay)))

(defn schedule-retry
  "Calculate when job should be retried.

   Args:
     job - Failed job map
     retry-config - Retry configuration
     now - Current timestamp (provided by caller)
     jitter-ms - Jitter in milliseconds (provided by caller)

   Returns:
     Instant when job should be retried"
  [job retry-config now jitter-ms]
  (let [delay-ms (calculate-retry-delay (:retry-count job) retry-config jitter-ms)]
    (.plusMillis now delay-ms)))

(defn prepare-retry
  "Prepare a failed job for retry.

   Args:
     job - Failed job map
     retry-config - Retry configuration
     now - Current timestamp (provided by caller)
     jitter-ms - Jitter in milliseconds (provided by caller)

   Returns:
     Job ready for retry with new execute-at"
  [job retry-config now jitter-ms]
  (let [retry-at (schedule-retry job retry-config now jitter-ms)]
    (-> job
        (assoc :status :pending
               :execute-at retry-at
               :updated-at now)
        (dissoc :started-at :error :result))))

;; =============================================================================
;; Job Filtering & Querying
;; =============================================================================

(defn ready-for-execution?
  "Check if job is ready to be executed.

   Args:
     job - Job map
     now - Current time (Instant)

   Returns:
     true if job should be executed now"
  [job now]
  (and (= :pending (:status job))
       (or (nil? (:execute-at job))
           (.isBefore (:execute-at job) now)
           (.equals (:execute-at job) now))))

(defn filter-executable-jobs
  "Filter jobs that are ready for execution.

   Args:
     jobs - Collection of jobs
     now - Current time (Instant)

   Returns:
     Filtered collection of executable jobs"
  [jobs now]
  (filter #(ready-for-execution? % now) jobs))

(defn sort-by-priority
  "Sort jobs by priority (critical > high > normal > low).

   Args:
     jobs - Collection of jobs

   Returns:
     Sorted collection"
  [jobs]
  (let [priority-order {:critical 4 :high 3 :normal 2 :low 1}]
    (sort-by #(get priority-order (:priority %) 2) > jobs)))

(defn jobs-by-status
  "Group jobs by status.

   Args:
     jobs - Collection of jobs

   Returns:
     Map of status -> jobs"
  [jobs]
  (group-by :status jobs))

;; =============================================================================
;; Job Validation
;; =============================================================================

(defn valid-job-transition?
  "Check if status transition is valid.

   Args:
     from-status - Current status
     to-status - Desired status

   Returns:
     true if transition is allowed"
  [from-status to-status]
  (let [valid-transitions {:pending #{:running :cancelled}
                           :running #{:completed :failed :retrying :cancelled}
                           :retrying #{:pending :failed}
                           :failed #{:pending}  ; Manual retry
                           :completed #{}
                           :cancelled #{}}]
    (contains? (get valid-transitions from-status #{}) to-status)))

(defn can-retry?
  "Check if job can be retried.

   Args:
     job - Job map

   Returns:
     true if job has retries remaining"
  [job]
  (< (:retry-count job 0) (:max-retries job 3)))

;; =============================================================================
;; Job Statistics
;; =============================================================================

(defn calculate-duration
  "Calculate job execution duration.

   Args:
     job - Job map

   Returns:
     Duration in milliseconds or nil if not completed"
  [job]
  (when (and (:started-at job) (:completed-at job))
    (.toMillis (java.time.Duration/between
                (:started-at job)
                (:completed-at job)))))

(defn job-summary
  "Create summary of job for monitoring.

   Args:
     job - Job map

   Returns:
     Summary map with key fields"
  [job]
  {:id (:id job)
   :job-type (:job-type job)
   :status (:status job)
   :queue (:queue job)
   :priority (:priority job)
   :retry-count (:retry-count job)
   :duration-ms (calculate-duration job)
   :created-at (:created-at job)
   :completed-at (:completed-at job)})

(defn aggregate-stats
  "Calculate aggregate statistics for jobs.

   Args:
     jobs - Collection of jobs

   Returns:
     Stats map with counts and averages"
  [jobs]
  (let [by-status (jobs-by-status jobs)
        completed-jobs (get by-status :completed [])
        durations (keep calculate-duration completed-jobs)
        avg-duration (when (seq durations)
                       (/ (reduce + durations) (count durations)))]
    {:total (count jobs)
     :pending (count (get by-status :pending []))
     :running (count (get by-status :running []))
     :completed (count completed-jobs)
     :failed (count (get by-status :failed []))
     :retrying (count (get by-status :retrying []))
     :cancelled (count (get by-status :cancelled []))
     :avg-duration-ms avg-duration}))
