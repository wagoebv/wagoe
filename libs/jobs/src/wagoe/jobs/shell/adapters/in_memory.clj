(ns wagoe.jobs.shell.adapters.in-memory
  "In-memory job queue implementation for development and testing.

   Uses Clojure atoms and data structures for job queuing. This adapter is
   suitable for:
   - Local development without Redis
   - Fast unit testing
   - CI/CD pipelines
   - Learning and tutorials

   NOT suitable for:
   - Production use (no persistence, single-process only)
   - Distributed systems (not shared across processes)
   - High-volume job processing (limited by memory)

   State is stored in atoms with the following structure:
   - jobs: Map of job-id -> job
   - queues: Map of queue-name -> priority-map of [priority job-ids]
   - scheduled: Sorted set of [execute-at job-id] pairs
   - failed: Vector of failed job-ids
   - stats: Map of queue-name -> statistics"
  (:require [wagoe.jobs.ports :as ports]
            [wagoe.jobs.core.job :as job]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

;; =============================================================================
;; State Management
;; =============================================================================

(defrecord InMemoryState
           [jobs          ; atom: job-id -> job
            queues        ; atom: queue-name -> {:critical [] :high [] :normal [] :low []}
            scheduled     ; atom: sorted set of {:execute-at inst :job-id uuid}
            failed        ; atom: vector of job-ids
            stats])       ; atom: queue-name -> {:processed :failed :succeeded :durations}

(defn- create-state
  "Create initial state atoms."
  []
  (->InMemoryState
   (atom {})                    ; jobs
   (atom {})                    ; queues
   (atom (sorted-set-by         ; scheduled (sorted by execute-at)
          (fn [a b]
            (let [time-cmp (compare (:execute-at a) (:execute-at b))]
              (if (zero? time-cmp)
                (compare (:job-id a) (:job-id b))
                time-cmp)))))
   (atom [])                    ; failed
   (atom {})))                  ; stats

;; =============================================================================
;; Queue Operations
;; =============================================================================

(defn- add-to-queue!
  "Add job ID to the back of its priority queue.

   FIFO within a priority, which is what the DB adapter's `ORDER BY
   priority_rank, created_at` gives and what a queue means. Critical, high and
   normal used to go to the front, so under load the newest work ran first and
   the oldest waited longest — the opposite of the intent, and only here."
  [queues-atom queue-name priority job-id]
  (swap! queues-atom
         (fn [queues]
           (let [queue (get queues queue-name {:critical [] :high [] :normal [] :low []})
                 priority-key (or priority :normal)]
             (assoc queues queue-name
                    (update queue priority-key #(conj (vec %) job-id)))))))

(defn- remove-job-from-queues!
  "Drop `job-id` from every priority list of every queue.

   A deleted job left in its queue is a tombstone: it still counts towards
   `queue-size`, and the dequeue that reaches it finds no job data and returns
   nil, so the work behind it waits for a poll that may not come."
  [queues-atom job-id]
  (swap! queues-atom
         (fn [queues]
           (reduce-kv (fn [acc queue-name queue]
                        (assoc acc queue-name
                               (reduce-kv (fn [q priority job-ids]
                                            (assoc q priority
                                                   (vec (remove #(= % job-id) job-ids))))
                                          {} queue)))
                      {} queues))))

(defn- remove-from-queue!
  "Remove and return next job ID from priority queue."
  [queues-atom queue-name]
  (let [result (atom nil)]
    (swap! queues-atom
           (fn [queues]
             (let [queue (get queues queue-name {:critical [] :high [] :normal [] :low []})]
               ;; Try each priority level in order
               (if-let [job-id (or (first (:critical queue))
                                   (first (:high queue))
                                   (first (:normal queue))
                                   (first (:low queue)))]
                 (do
                   (reset! result job-id)
                   (assoc queues queue-name
                          (cond
                            (seq (:critical queue)) (update queue :critical #(vec (rest %)))
                            (seq (:high queue)) (update queue :high #(vec (rest %)))
                            (seq (:normal queue)) (update queue :normal #(vec (rest %)))
                            (seq (:low queue)) (update queue :low #(vec (rest %)))
                            :else queue)))
                 queues))))
    @result))

(defn- peek-queue
  "Peek at next job ID without removing."
  [queues-atom queue-name]
  (let [queue (get @queues-atom queue-name {:critical [] :high [] :normal [] :low []})]
    (or (first (:critical queue))
        (first (:high queue))
        (first (:normal queue))
        (first (:low queue)))))

(defn- queue-size-internal
  "Get total size of queue across all priorities."
  [queues-atom queue-name]
  (let [queue (get @queues-atom queue-name {:critical [] :high [] :normal [] :low []})]
    (+ (count (:critical queue))
       (count (:high queue))
       (count (:normal queue))
       (count (:low queue)))))

;; =============================================================================
;; Scheduled Jobs
;; =============================================================================

(defn- add-to-scheduled!
  "Add job to scheduled set."
  [scheduled-atom job-id execute-at]
  (swap! scheduled-atom conj {:execute-at execute-at :job-id job-id}))

(defn- remove-from-scheduled!
  "Remove job from scheduled set."
  [scheduled-atom job-id]
  (swap! scheduled-atom
         (fn [scheduled]
           (into (empty scheduled)
                 (remove #(= (:job-id %) job-id) scheduled)))))

(defn- claim-scheduled!
  "Atomically remove job-id from the scheduled set and report whether THIS call
   was the one that removed it. Uses swap-vals! so concurrent claims are
   serialized by the atom's CAS: exactly one caller sees the id in its `old`
   value and wins. Returns true for the winner, false for losers — the guard
   that promotes a due scheduled job to an execution queue exactly once."
  [scheduled-atom job-id]
  (let [[old _new] (swap-vals! scheduled-atom
                               (fn [scheduled]
                                 (into (empty scheduled)
                                       (remove #(= (:job-id %) job-id) scheduled))))]
    (boolean (some #(= (:job-id %) job-id) old))))

(defn- get-due-jobs
  "Get jobs that are due for execution."
  [scheduled-atom now]
  (let [scheduled @scheduled-atom]
    (->> scheduled
         (take-while #(or (.isBefore (:execute-at %) now)
                          (.equals (:execute-at %) now)))
         (map :job-id))))

;; =============================================================================
;; Scheduled Job Processor (Forward declaration)
;; =============================================================================

(declare process-scheduled-jobs-internal!)

;; =============================================================================
;; Job Queue Implementation
;; =============================================================================

(defrecord InMemoryJobQueue [state]
  ports/IJobQueue

  (enqueue-job! [_ queue-name job]
    (let [job-id (:id job)]
      ;; Store job
      (swap! (:jobs state) assoc job-id job)

      ;; Add to appropriate queue
      (if (:execute-at job)
        ;; Scheduled job: add to sorted set
        (add-to-scheduled! (:scheduled state) job-id (:execute-at job))
        ;; Immediate job: add to priority queue
        (add-to-queue! (:queues state) queue-name (:priority job) job-id))

      (log/info "Enqueued job" {:job-id job-id :queue queue-name :priority (:priority job)})
      job-id))

  (schedule-job! [this queue-name job execute-at]
    (let [scheduled-job (assoc job :execute-at execute-at)]
      (ports/enqueue-job! this queue-name scheduled-job)))

  (dequeue-job! [_ queue-name _worker-id]
    (when-let [job-id (remove-from-queue! (:queues state) queue-name)]
      (get @(:jobs state) job-id)))

  ;; In-memory jobs live in-process, so a crash loses the whole queue anyway;
  ;; there is no cross-process in-flight list to track. ack/reclaim are no-ops
  ;; that satisfy the protocol so worker code is adapter-agnostic.
  (ack-job! [_ _queue-name _worker-id _job-id]
    true)

  (reclaim-abandoned-jobs! [_ _queue-name]
    {:reclaimed 0})

  (peek-job [_ queue-name]
    (when-let [job-id (peek-queue (:queues state) queue-name)]
      (get @(:jobs state) job-id)))

  (delete-job! [_ job-id]
    (let [job (get @(:jobs state) job-id)]
      (swap! (:jobs state) dissoc job-id)
      (remove-from-scheduled! (:scheduled state) job-id)
      (remove-job-from-queues! (:queues state) job-id)
      ;; false, not nil: the port says "true if deleted, false if not found",
      ;; and a caller writing `(if (delete-job! …) …)` cannot tell the two
      ;; apart but one written with `false?` can.
      (some? job)))

  (queue-size [_ queue-name]
    (queue-size-internal (:queues state) queue-name))

  (list-queues [_this]
    ;; Queues that have work. The map keeps an entry for every queue ever
    ;; enqueued to, so without this a drained queue is still named — where the
    ;; DB adapter lists the queues with rows and Redis drops an empty list key.
    (->> @(:queues state)
         (keep (fn [[queue-name priorities]]
                 (when (some seq (vals priorities)) queue-name)))
         vec))

  (process-scheduled-jobs! [this]
    (process-scheduled-jobs-internal! this)))

;; =============================================================================
;; Scheduled Job Processor
;; =============================================================================

(defn process-scheduled-jobs-internal!
  "Move scheduled jobs that are due to execution queues.

   This should be called periodically (e.g., every 5 seconds) by a worker.

   Args:
     queue - InMemoryJobQueue instance

   Returns:
     Number of jobs moved to execution queues"
  [^InMemoryJobQueue queue]
  (let [state (:state queue)
        now (Instant/now)
        due-job-ids (get-due-jobs (:scheduled state) now)
        moved (atom 0)]

    (doseq [job-id due-job-ids]
      ;; Atomic claim before enqueueing: only the caller that removes the job from
      ;; the scheduled set promotes it, so concurrent workers can't both move the
      ;; same scheduled job into an execution queue (would run it twice).
      (when (claim-scheduled! (:scheduled state) job-id)
        (if-let [job (get @(:jobs state) job-id)]
          (let [queue-name (:queue job)]
            (add-to-queue! (:queues state) queue-name (:priority job) job-id)
            (swap! moved inc)
            (log/debug "Moved scheduled job to execution queue"
                       {:job-id job-id :queue queue-name}))
          (log/warn "Claimed due scheduled job has no stored data; skipping"
                    {:job-id job-id}))))

    @moved))

;; =============================================================================
;; Job Store Implementation
;; =============================================================================

(defrecord InMemoryJobStore [state]
  ports/IJobStore

  (save-job! [_ job]
    (swap! (:jobs state) assoc (:id job) job)
    job)

  (find-job [_ job-id]
    (get @(:jobs state) job-id))

  (update-job-status! [_ job-id status result]
    (when-let [job (get @(:jobs state) job-id)]
      (let [now (Instant/now)
            updated-job (case status
                          :running (job/start-job job now)
                          :completed (job/complete-job job result now)
                          :failed (job/fail-job job result now)
                          :cancelled (job/cancel-job job now)
                          job)]
        (swap! (:jobs state) assoc job-id updated-job)

        ;; If job failed and no more retries, move to dead letter queue
        (when (and (= status :failed) (not (job/can-retry? updated-job)))
          (swap! (:failed state) conj job-id))

        updated-job)))

  (find-jobs [_ filters]
    (->> @(:jobs state)
         vals
         (filter (fn [job]
                   (and (or (nil? (:status filters))
                            (= (:status filters) (:status job)))
                        (or (nil? (:job-type filters))
                            (= (:job-type filters) (:job-type job)))
                        (or (nil? (:queue filters))
                            (= (:queue filters) (:queue job))))))
         vec))

  (failed-jobs [_ limit]
    (->> @(:failed state)
         (take limit)
         (map (fn [job-id]
                (get @(:jobs state) job-id)))
         (filter some?)
         vec))

  (retry-job! [_ job-id]
    (when-let [job (get @(:jobs state) job-id)]
      (let [retry-config {:backoff-strategy :exponential
                          :initial-delay-ms 1000
                          :max-delay-ms 60000}
            now (Instant/now)
            jitter-ms (rand-int 100)
            retry-job (job/prepare-retry job retry-config now jitter-ms)]

        ;; Update job data
        (swap! (:jobs state) assoc job-id retry-job)

        ;; Remove from failed queue
        (swap! (:failed state)
               (fn [failed]
                 (vec (remove #(= % job-id) failed))))

        ;; Add back to scheduled jobs
        (add-to-scheduled! (:scheduled state) job-id (:execute-at retry-job))

        retry-job))))

;; =============================================================================
;; Job Statistics Implementation
;; =============================================================================

(defrecord InMemoryJobStats [state]
  ports/IJobStats

  (job-stats [this]
    (let [all-jobs (vals @(:jobs state))
          queues (keys @(:queues state))]
      {:total-processed (count (filter #(#{:completed :failed} (:status %)) all-jobs))
       :total-failed (count (filter #(= :failed (:status %)) all-jobs))
       :total-succeeded (count (filter #(= :completed (:status %)) all-jobs))
       :queues (mapv (fn [queue-name]
                       (let [stats (ports/queue-stats this queue-name)]
                         (assoc stats :queue-name queue-name)))
                     queues)
       :workers []}))  ; In-memory implementation doesn't track workers

  (queue-stats [_ queue-name]
    (let [queue-jobs (->> @(:jobs state)
                          vals
                          (filter #(= (:queue %) queue-name)))
          completed-jobs (filter #(= :completed (:status %)) queue-jobs)
          durations (keep job/calculate-duration completed-jobs)
          avg-duration (when (seq durations)
                         (/ (reduce + durations) (count durations)))]
      {:queue-name queue-name
       :size (queue-size-internal (:queues state) queue-name)
       :processed-total (count (filter #(#{:completed :failed} (:status %)) queue-jobs))
       :failed-total (count (filter #(= :failed (:status %)) queue-jobs))
       :succeeded-total (count completed-jobs)
       :avg-duration-ms avg-duration}))

  (job-history [_ job-type limit]
    (->> @(:jobs state)
         vals
         (filter #(= (:job-type %) job-type))
         (sort-by (juxt :created-at :id) #(compare %2 %1))  ; Newest first, stable by ID
         (take limit)
         vec)))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-in-memory-job-queue
  "Create in-memory job queue.

   Args:
     state - InMemoryState instance (optional, creates new if not provided)

   Returns:
     InMemoryJobQueue implementing IJobQueue"
  ([]
   (create-in-memory-job-queue (create-state)))
  ([state]
   (->InMemoryJobQueue state)))

(defn create-in-memory-job-store
  "Create in-memory job store.

   Args:
     state - InMemoryState instance (optional, creates new if not provided)

   Returns:
     InMemoryJobStore implementing IJobStore"
  ([]
   (create-in-memory-job-store (create-state)))
  ([state]
   (->InMemoryJobStore state)))

(defn create-in-memory-job-stats
  "Create in-memory job stats.

   Args:
     state - InMemoryState instance (optional, creates new if not provided)

   Returns:
     InMemoryJobStats implementing IJobStats"
  ([]
   (create-in-memory-job-stats (create-state)))
  ([state]
   (->InMemoryJobStats state)))

(defn create-in-memory-jobs-system
  "Create complete in-memory jobs system with shared state.

   Returns:
     Map with :queue, :store, :stats sharing same state"
  []
  (let [state (create-state)]
    {:queue (create-in-memory-job-queue state)
     :store (create-in-memory-job-store state)
     :stats (create-in-memory-job-stats state)
     :state state}))

;; =============================================================================
;; Testing Utilities
;; =============================================================================

(defn clear-all-jobs!
  "Clear all jobs from state. Useful for testing.

   Args:
     state - InMemoryState instance"
  [state]
  (reset! (:jobs state) {})
  (reset! (:queues state) {})
  (reset! (:scheduled state) (sorted-set-by
                              (fn [a b]
                                (let [time-cmp (compare (:execute-at a) (:execute-at b))]
                                  (if (zero? time-cmp)
                                    (compare (:job-id a) (:job-id b))
                                    time-cmp)))))
  (reset! (:failed state) [])
  (reset! (:stats state) {}))

(defn get-all-jobs
  "Get all jobs in state. Useful for testing.

   Args:
     state - InMemoryState instance

   Returns:
     Map of job-id -> job"
  [state]
  @(:jobs state))

(defn get-scheduled-jobs
  "Get all scheduled jobs. Useful for testing.

   Args:
     state - InMemoryState instance

   Returns:
     Sorted set of {:execute-at :job-id}"
  [state]
  @(:scheduled state))
