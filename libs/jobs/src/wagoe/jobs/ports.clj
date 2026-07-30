(ns wagoe.jobs.ports
  "Port definitions for background job processing.

   This module defines protocols for asynchronous job processing, similar to
   Sidekiq (Ruby) or Celery (Python). Jobs can be queued, scheduled, and
   processed asynchronously with retry logic.

   Key Features:
   - Job enqueueing and scheduling
   - Priority queues
   - Retry logic with exponential backoff
   - Dead letter queue for failed jobs
   - Job monitoring and statistics")

;; =============================================================================
;; Job Queue Ports
;; =============================================================================

(defprotocol IJobQueue
  "Protocol for job queue operations."

  (enqueue-job! [this queue-name job]
    "Enqueue a job for immediate processing.

    Args:
      queue-name - Queue name (e.g., :default, :critical, :low-priority)
      job - Job map with {:job-type :job-id :args :metadata}

    Returns:
      Job ID (UUID)")

  (schedule-job! [this queue-name job execute-at]
    "Schedule a job for future execution.

    Args:
      queue-name - Queue name
      job - Job map
      execute-at - Instant when job should execute

    Returns:
      Job ID (UUID)")

  (dequeue-job! [this queue-name worker-id]
    "Reliably dequeue the next job from queue for a worker.

    Atomically moves the job into a per-worker in-flight (processing) list so a
    worker that crashes mid-job does not lose it — the job stays in the
    processing list until acked (see ack-job!) or reclaimed (see
    reclaim-abandoned-jobs!).

    Args:
      queue-name - Queue name
      worker-id  - Id of the worker taking the job (owns the processing list)

    Returns:
      Job map or nil if queue is empty")

  (ack-job! [this queue-name worker-id job-id]
    "Acknowledge that a worker has finished with a dequeued job (completed,
    re-enqueued for retry, or dead-lettered), removing it from the worker's
    in-flight processing list. Must be called for every dequeued job.

    Returns:
      true")

  (reclaim-abandoned-jobs! [this queue-name]
    "Return jobs stranded in the processing lists of dead workers back to the
    ready queue so they are re-executed (at-least-once). A worker is considered
    dead when its heartbeat has expired. Safe and cheap to call periodically.

    Returns:
      {:reclaimed <count>}")

  (peek-job [this queue-name]
    "Peek at next job without removing it.

    Args:
      queue-name - Queue name

    Returns:
      Job map or nil")

  (delete-job! [this job-id]
    "Delete a job from queue.

    Args:
      job-id - Job ID

    Returns:
      true if deleted, false if not found")

  (queue-size [this queue-name]
    "Get number of jobs in queue.

    Args:
      queue-name - Queue name

    Returns:
      Integer count")

  (list-queues [this]
    "List all queue names.

    Returns:
      Vector of queue names")

  (process-scheduled-jobs! [this]
    "Process scheduled jobs that are due for execution.
    Moves jobs from scheduled queue to execution queues.

    Returns:
      Number of jobs moved"))

;; =============================================================================
;; Job Execution Ports
;; =============================================================================

(defprotocol IJobWorker
  "Protocol for job worker operations."

  (process-job! [this job]
    "Process a single job.

    Args:
      job - Job map

    Returns:
      Result map with {:success? :result :error}")

  (start-worker! [this]
    "Start worker to process jobs from queue.

    Returns:
      Worker ID")

  (stop-worker! [this worker-id]
    "Stop worker by ID.

    Args:
      worker-id - Worker ID

    Returns:
      true if stopped")

  (worker-status [this worker-id]
    "Get worker status.

    Args:
      worker-id - Worker ID

    Returns:
      Status map {:status :current-job :processed-count}"))

;; =============================================================================
;; Job Registry Ports
;; =============================================================================

(defprotocol IJobRegistry
  "Protocol for job handler registry."

  (register-handler! [this job-type handler-fn]
    "Register a handler function for job type.

    Args:
      job-type - Keyword identifying job type (e.g., :send-email)
      handler-fn - Function that processes job: (fn [job-args] ...)

    Returns:
      job-type")

  (unregister-handler! [this job-type]
    "Unregister a handler.

    Args:
      job-type - Job type keyword

    Returns:
      true if unregistered")

  (get-handler [this job-type]
    "Get handler function for job type.

    Args:
      job-type - Job type keyword

    Returns:
      Handler function or nil")

  (list-handlers [this]
    "List all registered job types.

    Returns:
      Vector of job type keywords"))

;; =============================================================================
;; Job Persistence Ports
;; =============================================================================

(defprotocol IJobStore
  "Protocol for job persistence and history."

  (save-job! [this job]
    "Persist job to store.

    Args:
      job - Job map

    Returns:
      Saved job with metadata")

  (find-job [this job-id]
    "Find job by ID.

    Args:
      job-id - Job ID

    Returns:
      Job map or nil")

  (update-job-status! [this job-id status result]
    "Update job status and result.

    Args:
      job-id - Job ID
      status - Status keyword (:pending :running :completed :failed :retrying)
      result - Result data or error

    Returns:
      Updated job")

  (find-jobs [this filters]
    "Find jobs matching filters.

    Args:
      filters - Map with optional keys:
                :status - Job status
                :job-type - Job type
                :queue - Queue name
                :from-date - Start date
                :to-date - End date

    Returns:
      Vector of jobs")

  (failed-jobs [this limit]
    "Get failed jobs from dead letter queue.

    Args:
      limit - Maximum number of jobs to return

    Returns:
      Vector of failed jobs")

  (retry-job! [this job-id]
    "Move failed job back to queue for retry.

    Args:
      job-id - Job ID

    Returns:
      Updated job"))

;; =============================================================================
;; Job Statistics Ports
;; =============================================================================

(defprotocol IJobStats
  "Protocol for job statistics and monitoring."

  (job-stats [this]
    "Get overall job statistics.

    Returns:
      Map with:
        :total-processed - Total jobs processed
        :total-failed - Total jobs failed
        :total-succeeded - Total jobs succeeded
        :queues - Map of queue-name -> {:size :processed :failed}
        :workers - Map of worker-id -> status")

  (queue-stats [this queue-name]
    "Get statistics for specific queue.

    Args:
      queue-name - Queue name

    Returns:
      Stats map {:size :processed :failed :avg-duration}")

  (job-history [this job-type limit]
    "Get recent job history for job type.

    Args:
      job-type - Job type keyword
      limit - Maximum number of jobs

    Returns:
      Vector of recent jobs with results"))
