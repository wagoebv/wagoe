(ns wagoe.jobs.shell.adapters.in-memory-test
  "Tests for in-memory job queue adapter."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.jobs.shell.adapters.in-memory :as in-memory]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *system* nil)

(defn with-clean-system
  "Fixture that creates a fresh in-memory jobs system for each test."
  [f]
  (binding [*system* (in-memory/create-in-memory-jobs-system)]
    (f)))

(use-fixtures :each with-clean-system)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn create-test-job
  "Create a test job with defaults."
  ([]
   (create-test-job {}))
  ([overrides]
   (job/create-job
    (merge {:job-type :test-job
            :args {:foo "bar"}
            :queue :default}
           overrides)
    (UUID/randomUUID)
    (Instant/now))))

;; =============================================================================
;; Job Queue Tests
;; =============================================================================

(deftest ^:unit enqueue-and-dequeue-test
  (testing "Enqueue and dequeue immediate job"
    (let [queue (:queue *system*)
          test-job (create-test-job)]
      (ports/enqueue-job! queue :default test-job)
      (is (= 1 (ports/queue-size queue :default)))

      (let [dequeued (ports/dequeue-job! queue :default "test-worker")]
        (is (= (:id test-job) (:id dequeued)))
        (is (= :test-job (:job-type dequeued)))
        (is (zero? (ports/queue-size queue :default)))))))

(deftest ^:unit priority-queue-test
  (testing "Jobs are dequeued by priority"
    (let [queue (:queue *system*)
          low-job (create-test-job {:priority :low})
          normal-job (create-test-job {:priority :normal})
          high-job (create-test-job {:priority :high})
          critical-job (create-test-job {:priority :critical})]

      ;; Enqueue in random order
      (ports/enqueue-job! queue :default low-job)
      (ports/enqueue-job! queue :default normal-job)
      (ports/enqueue-job! queue :default high-job)
      (ports/enqueue-job! queue :default critical-job)

      (is (= 4 (ports/queue-size queue :default)))

      ;; Should dequeue in priority order
      (is (= (:id critical-job) (:id (ports/dequeue-job! queue :default "test-worker"))))
      (is (= (:id high-job) (:id (ports/dequeue-job! queue :default "test-worker"))))
      (is (= (:id normal-job) (:id (ports/dequeue-job! queue :default "test-worker"))))
      (is (= (:id low-job) (:id (ports/dequeue-job! queue :default "test-worker"))))
      (is (zero? (ports/queue-size queue :default))))))

(deftest ^:unit peek-job-test
  (testing "Peek returns next job without removing it"
    (let [queue (:queue *system*)
          test-job (create-test-job)]
      (ports/enqueue-job! queue :default test-job)

      (let [peeked (ports/peek-job queue :default)]
        (is (= (:id test-job) (:id peeked)))
        (is (= 1 (ports/queue-size queue :default)))  ; Still in queue

        ;; Dequeue should return same job
        (is (= (:id test-job) (:id (ports/dequeue-job! queue :default "test-worker"))))))))

(deftest ^:unit delete-job-test
  (testing "Delete removes job from queue"
    (let [queue (:queue *system*)
          store (:store *system*)
          test-job (create-test-job)]
      (ports/enqueue-job! queue :default test-job)
      (is (= 1 (ports/queue-size queue :default)))

      (is (true? (ports/delete-job! queue (:id test-job))))
      (is (nil? (ports/find-job store (:id test-job)))))))

(deftest ^:unit multiple-queues-test
  (testing "Different queues are independent"
    (let [queue (:queue *system*)
          job1 (create-test-job {:queue :queue1})
          job2 (create-test-job {:queue :queue2})]

      (ports/enqueue-job! queue :queue1 job1)
      (ports/enqueue-job! queue :queue2 job2)

      (is (= 1 (ports/queue-size queue :queue1)))
      (is (= 1 (ports/queue-size queue :queue2)))

      (is (= (:id job1) (:id (ports/dequeue-job! queue :queue1 "test-worker"))))
      (is (= (:id job2) (:id (ports/dequeue-job! queue :queue2 "test-worker")))))))

(deftest ^:unit list-queues-test
  (testing "List all queues"
    (let [queue (:queue *system*)]
      (ports/enqueue-job! queue :queue1 (create-test-job {:queue :queue1}))
      (ports/enqueue-job! queue :queue2 (create-test-job {:queue :queue2}))
      (ports/enqueue-job! queue :queue3 (create-test-job {:queue :queue3}))

      (let [queues (set (ports/list-queues queue))]
        (is (= #{:queue1 :queue2 :queue3} queues))))))

;; =============================================================================
;; Scheduled Jobs Tests
;; =============================================================================

(deftest ^:unit schedule-job-test
  (testing "Schedule job for future execution"
    (let [queue (:queue *system*)
          future-time (.plusSeconds (Instant/now) 60)
          test-job (create-test-job {:execute-at future-time})]

      (ports/enqueue-job! queue :default test-job)

      ;; Job should not be in execution queue yet
      (is (zero? (ports/queue-size queue :default)))

      ;; Job should be in scheduled set
      (let [scheduled (in-memory/get-scheduled-jobs (:state *system*))]
        (is (= 1 (count scheduled)))
        (is (= (:id test-job) (:job-id (first scheduled))))))))

(deftest ^:unit process-scheduled-jobs-test
  (testing "Process due scheduled jobs"
    (let [queue (:queue *system*)
          past-time (.minusSeconds (Instant/now) 10)
          future-time (.plusSeconds (Instant/now) 60)
          due-job (create-test-job {:execute-at past-time :priority :high})
          future-job (create-test-job {:execute-at future-time})]

      (ports/enqueue-job! queue :default due-job)
      (ports/enqueue-job! queue :default future-job)

      ;; Neither in execution queue yet
      (is (zero? (ports/queue-size queue :default)))

      ;; Process scheduled jobs
      (let [moved (ports/process-scheduled-jobs! queue)]
        (is (= 1 moved))

        ;; Due job should now be in execution queue
        (is (= 1 (ports/queue-size queue :default)))
        (is (= (:id due-job) (:id (ports/dequeue-job! queue :default "test-worker"))))

        ;; Future job still in scheduled set
        (is (= 1 (count (in-memory/get-scheduled-jobs (:state *system*)))))))))

;; =============================================================================
;; Job Store Tests
;; =============================================================================

(deftest ^:unit save-and-find-job-test
  (testing "Save and retrieve job"
    (let [store (:store *system*)
          test-job (create-test-job)]

      (ports/save-job! store test-job)

      (let [found (ports/find-job store (:id test-job))]
        (is (= (:id test-job) (:id found)))
        (is (= :test-job (:job-type found)))))))

(deftest ^:unit update-job-status-test
  (testing "Update job status to running"
    (let [store (:store *system*)
          test-job (create-test-job)]

      (ports/save-job! store test-job)
      (ports/update-job-status! store (:id test-job) :running nil)

      (let [updated (ports/find-job store (:id test-job))]
        (is (= :running (:status updated)))
        (is (some? (:started-at updated))))))

  (testing "Update job status to completed"
    (let [store (:store *system*)
          test-job (create-test-job)]

      (ports/save-job! store test-job)
      (ports/update-job-status! store (:id test-job) :running nil)
      (ports/update-job-status! store (:id test-job) :completed {:data "success"})

      (let [updated (ports/find-job store (:id test-job))]
        (is (= :completed (:status updated)))
        (is (= {:data "success"} (:result updated)))
        (is (some? (:completed-at updated))))))

  (testing "Update job status to failed"
    (let [store (:store *system*)
          test-job (create-test-job {:max-retries 0})]  ; No retries

      (ports/save-job! store test-job)
      (ports/update-job-status! store (:id test-job) :running nil)
      (ports/update-job-status! store (:id test-job) :failed
                                {:message "Test error"
                                 :type "TestException"})

      (let [updated (ports/find-job store (:id test-job))]
        (is (= :failed (:status updated)))
        (is (= "Test error" (get-in updated [:error :message])))))))

(deftest ^:unit find-jobs-by-status-test
  (testing "Find jobs by status"
    (let [store (:store *system*)
          pending-job (create-test-job)
          now (java.time.Instant/now)
          completed-job (-> (create-test-job)
                            (job/start-job now)
                            (job/complete-job {:result "ok"} now))]

      (ports/save-job! store pending-job)
      (ports/save-job! store completed-job)

      (let [pending-jobs (ports/find-jobs store {:status :pending})
            completed-jobs (ports/find-jobs store {:status :completed})]
        (is (= 1 (count pending-jobs)))
        (is (= 1 (count completed-jobs)))
        (is (= (:id pending-job) (:id (first pending-jobs))))
        (is (= (:id completed-job) (:id (first completed-jobs))))))))

(deftest ^:unit find-jobs-by-type-test
  (testing "Find jobs by job-type"
    (let [store (:store *system*)
          email-job (create-test-job {:job-type :send-email})
          report-job (create-test-job {:job-type :generate-report})]

      (ports/save-job! store email-job)
      (ports/save-job! store report-job)

      (let [email-jobs (ports/find-jobs store {:job-type :send-email})]
        (is (= 1 (count email-jobs)))
        (is (= (:id email-job) (:id (first email-jobs))))))))

(deftest ^:unit find-jobs-by-queue-test
  (testing "Find jobs by queue"
    (let [store (:store *system*)
          default-job (create-test-job {:queue :default})
          critical-job (create-test-job {:queue :critical})]

      (ports/save-job! store default-job)
      (ports/save-job! store critical-job)

      (let [default-jobs (ports/find-jobs store {:queue :default})]
        (is (= 1 (count default-jobs)))
        (is (= (:id default-job) (:id (first default-jobs))))))))

(deftest ^:unit failed-jobs-test
  (testing "Track failed jobs in dead letter queue"
    (let [store (:store *system*)
          job1 (create-test-job {:max-retries 0})
          job2 (create-test-job {:max-retries 0})]

      ;; Fail both jobs
      (ports/save-job! store job1)
      (ports/update-job-status! store (:id job1) :failed
                                {:message "Error 1" :type "Error"})

      (ports/save-job! store job2)
      (ports/update-job-status! store (:id job2) :failed
                                {:message "Error 2" :type "Error"})

      (let [failed (ports/failed-jobs store 10)]
        (is (= 2 (count failed)))
        (is (every? #(= :failed (:status %)) failed))))))

(deftest ^:unit retry-job-test
  (testing "Retry failed job"
    (let [store (:store *system*)
          test-job (create-test-job {:max-retries 3})]

      ;; Fail the job
      (ports/save-job! store test-job)
      (ports/update-job-status! store (:id test-job) :failed
                                {:message "Temporary error" :type "Error"})

      ;; Retry the job
      (let [retried (ports/retry-job! store (:id test-job))]
        (is (= :pending (:status retried)))
        (is (= 1 (:retry-count retried)))
        (is (some? (:execute-at retried)))

        ;; Should be in scheduled jobs
        (let [scheduled (in-memory/get-scheduled-jobs (:state *system*))]
          (is (= 1 (count scheduled))))))))

;; =============================================================================
;; Job Statistics Tests
;; =============================================================================

(deftest ^:unit queue-stats-test
  (testing "Get queue statistics"
    (let [queue (:queue *system*)
          store (:store *system*)
          stats (:stats *system*)]

      ;; Add jobs to queue
      (ports/enqueue-job! queue :default (create-test-job))
      (ports/enqueue-job! queue :default (create-test-job))

      ;; Complete one job
      (let [job (ports/dequeue-job! queue :default "test-worker")]
        (ports/update-job-status! store (:id job) :running nil)
        (ports/update-job-status! store (:id job) :completed {:result "ok"}))

      (let [queue-stats (ports/queue-stats stats :default)]
        (is (= :default (:queue-name queue-stats)))
        (is (= 1 (:size queue-stats)))  ; One still in queue
        (is (= 1 (:succeeded-total queue-stats)))))))

(deftest ^:unit job-stats-test
  (testing "Get overall job statistics"
    (let [store (:store *system*)
          stats (:stats *system*)
          job1 (create-test-job)
          job2 (create-test-job {:max-retries 0})]  ; No retries so it actually fails

      ;; Complete job1
      (ports/save-job! store job1)
      (ports/update-job-status! store (:id job1) :running nil)
      (ports/update-job-status! store (:id job1) :completed {:result "ok"})

      ;; Fail job2
      (ports/save-job! store job2)
      (ports/update-job-status! store (:id job2) :running nil)
      (ports/update-job-status! store (:id job2) :failed
                                {:message "Error" :type "Error"})

      (let [overall-stats (ports/job-stats stats)]
        (is (= 2 (:total-processed overall-stats)))
        (is (= 1 (:total-succeeded overall-stats)))
        (is (= 1 (:total-failed overall-stats)))))))

(deftest ^:unit job-history-test
  (testing "Get job history by type"
    (let [store (:store *system*)
          stats (:stats *system*)
          email-job1 (create-test-job {:job-type :send-email})]
      
      (ports/save-job! store email-job1)
      (Thread/sleep 10)  ; Ensure different timestamps for job creation
      
      (let [email-job2 (create-test-job {:job-type :send-email})
            report-job (create-test-job {:job-type :generate-report})]
        
        (ports/save-job! store email-job2)
        (ports/save-job! store report-job)

        (let [email-history (ports/job-history stats :send-email 10)]
          (is (= 2 (count email-history)))
          ;; Should be sorted newest first
          (is (= (:id email-job2) (:id (first email-history))))
          (is (= (:id email-job1) (:id (second email-history)))))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit full-job-lifecycle-test
  (testing "Complete job lifecycle from enqueue to completion"
    (let [queue (:queue *system*)
          store (:store *system*)
          stats (:stats *system*)
          test-job (create-test-job {:job-type :send-email
                                     :args {:to "user@example.com"
                                            :subject "Test"}
                                     :priority :high})]

      ;; 1. Enqueue job
      (ports/enqueue-job! queue :default test-job)
      (is (= 1 (ports/queue-size queue :default)))

      ;; 2. Dequeue job for processing
      (let [dequeued (ports/dequeue-job! queue :default "test-worker")]
        (is (= (:id test-job) (:id dequeued)))
        (is (zero? (ports/queue-size queue :default)))

        ;; 3. Mark as running
        (ports/update-job-status! store (:id dequeued) :running nil)
        (let [running-job (ports/find-job store (:id dequeued))]
          (is (= :running (:status running-job)))
          (is (some? (:started-at running-job))))

        ;; 4. Complete successfully
        (ports/update-job-status! store (:id dequeued) :completed
                                  {:email-sent true :message-id "123"})
        (let [completed-job (ports/find-job store (:id dequeued))]
          (is (= :completed (:status completed-job)))
          (is (= {:email-sent true :message-id "123"} (:result completed-job)))
          (is (some? (:completed-at completed-job))))

        ;; 5. Verify stats
        (let [overall-stats (ports/job-stats stats)]
          (is (= 1 (:total-processed overall-stats)))
          (is (= 1 (:total-succeeded overall-stats)))
          (is (zero? (:total-failed overall-stats))))))))

(deftest ^:unit job-retry-lifecycle-test
  (testing "Job retry after failure"
    (let [queue (:queue *system*)
          store (:store *system*)
          test-job (create-test-job {:max-retries 2})]

      ;; 1. Enqueue and process
      (ports/enqueue-job! queue :default test-job)
      (let [dequeued (ports/dequeue-job! queue :default "test-worker")]
        (ports/update-job-status! store (:id dequeued) :running nil)

        ;; 2. Fail the job
        (ports/update-job-status! store (:id dequeued) :failed
                                  {:message "Temporary network error"
                                   :type "NetworkException"})

        (let [failed-job (ports/find-job store (:id dequeued))]
          (is (= :retrying (:status failed-job)))  ; Should retry
          (is (= 1 (:retry-count failed-job))))

        ;; 3. Retry manually
        (let [retried (ports/retry-job! store (:id dequeued))]
          (is (= :pending (:status retried)))
          (is (some? (:execute-at retried)))

          ;; Should be in scheduled jobs
          (let [scheduled (in-memory/get-scheduled-jobs (:state *system*))]
            (is (= 1 (count scheduled)))))))))

;; =============================================================================
;; Concurrency Tests
;; =============================================================================

(deftest ^:unit concurrent-enqueue-test
  (testing "Concurrent enqueue operations"
    (let [queue (:queue *system*)
          num-jobs 100
          jobs (repeatedly num-jobs #(create-test-job))]

      ;; Enqueue all jobs concurrently
      (doall (pmap #(ports/enqueue-job! queue :default %) jobs))

      ;; Should have all jobs
      (is (= num-jobs (ports/queue-size queue :default)))

      ;; Should be able to dequeue all
      (let [dequeued (repeatedly num-jobs #(ports/dequeue-job! queue :default "test-worker"))]
        (is (= num-jobs (count (filter some? dequeued))))
        (is (zero? (ports/queue-size queue :default)))))))

(deftest ^:unit concurrent-dequeue-test
  (testing "Concurrent dequeue operations"
    (let [queue (:queue *system*)
          num-jobs 50
          jobs (repeatedly num-jobs #(create-test-job))]

      ;; Enqueue jobs
      (doseq [job jobs]
        (ports/enqueue-job! queue :default job))

      ;; Dequeue concurrently
      (let [dequeued (doall (pmap (fn [_] (ports/dequeue-job! queue :default "test-worker"))
                                  (range num-jobs)))
            successful (filter some? dequeued)]
        ;; All jobs should be dequeued exactly once
        (is (= num-jobs (count successful)))
        (is (zero? (ports/queue-size queue :default)))))))
