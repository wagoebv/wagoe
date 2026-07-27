(ns wagoe.jobs.core.job-test
  "Unit tests for pure job core functions.
   All tests use injected timestamps and UUIDs — no I/O."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.jobs.core.job :as job])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Fixed test values — deterministic
;; =============================================================================

(def ^:private test-id (UUID/fromString "00000000-0000-0000-0000-000000000001"))
(def ^:private test-now (Instant/parse "2026-01-01T12:00:00Z"))
(def ^:private test-later (Instant/parse "2026-01-01T13:00:00Z"))

(def ^:private base-input
  {:job-type :send-email
   :args {:to "user@example.com"}
   :queue :default})

;; =============================================================================
;; Job Creation
;; =============================================================================

(deftest ^:unit create-job-test
  (testing "creates job with required fields"
    (let [job (job/create-job base-input test-id test-now)]
      (is (= test-id (:id job)))
      (is (= :send-email (:job-type job)))
      (is (= :default (:queue job)))
      (is (= {:to "user@example.com"} (:args job)))
      (is (= :pending (:status job)))
      (is (= :normal (:priority job)))
      (is (= 0 (:retry-count job)))
      (is (= 3 (:max-retries job)))
      (is (= test-now (:created-at job)))
      (is (= test-now (:updated-at job)))))

  (testing "applies defaults for missing optional fields"
    (let [job (job/create-job {:job-type :cleanup} test-id test-now)]
      (is (= :default (:queue job)))
      (is (= :normal (:priority job)))
      (is (= {} (:args job)))
      (is (= {} (:metadata job)))))

  (testing "respects custom priority and max-retries"
    (let [job (job/create-job (assoc base-input :priority :high :max-retries 5)
                              test-id test-now)]
      (is (= :high (:priority job)))
      (is (= 5 (:max-retries job)))))

  (testing "does not set execute-at when not provided"
    (let [job (job/create-job base-input test-id test-now)]
      (is (nil? (:execute-at job)))))

  (testing "sets execute-at when provided"
    (let [job (job/create-job (assoc base-input :execute-at test-later) test-id test-now)]
      (is (= test-later (:execute-at job))))))

(deftest ^:unit schedule-job-test
  (testing "creates scheduled job with execute-at"
    (let [job (job/schedule-job base-input test-id test-later test-now)]
      (is (= test-id (:id job)))
      (is (= test-later (:execute-at job)))
      (is (= :pending (:status job))))))

;; =============================================================================
;; State Transitions
;; =============================================================================

(deftest ^:unit start-job-test
  (testing "transitions job to :running"
    (let [pending-job (job/create-job base-input test-id test-now)
          running-job (job/start-job pending-job test-later)]
      (is (= :running (:status running-job)))
      (is (= test-later (:started-at running-job)))
      (is (= test-later (:updated-at running-job)))))

  (testing "preserves original fields"
    (let [pending-job (job/create-job base-input test-id test-now)
          running-job (job/start-job pending-job test-later)]
      (is (= test-id (:id running-job)))
      (is (= :send-email (:job-type running-job))))))

(deftest ^:unit complete-job-test
  (let [pending-job (job/create-job base-input test-id test-now)
        running-job (job/start-job pending-job test-now)
        done-at (Instant/parse "2026-01-01T14:00:00Z")
        result {:emails-sent 1}
        completed-job (job/complete-job running-job result done-at)]

    (testing "transitions to :completed"
      (is (= :completed (:status completed-job))))

    (testing "stores result"
      (is (= result (:result completed-job))))

    (testing "records completed-at and updated-at"
      (is (= done-at (:completed-at completed-job)))
      (is (= done-at (:updated-at completed-job))))))

(deftest ^:unit fail-job-test
  (let [pending-job (job/create-job base-input test-id test-now)
        running-job (job/start-job pending-job test-now)
        error {:message "SMTP timeout" :type "IOException"}]

    (testing "transitions to :retrying when retries remain"
      (let [failed (job/fail-job running-job error test-later)]
        (is (= :retrying (:status failed)))
        (is (= 1 (:retry-count failed)))
        (is (= error (:error failed)))
        (is (= test-later (:updated-at failed)))))

    (testing "transitions to :failed when max retries exhausted"
      (let [exhausted-job (assoc pending-job :retry-count 2 :max-retries 3)
            failed (job/fail-job exhausted-job error test-later)]
        (is (= :failed (:status failed)))
        (is (= test-later (:completed-at failed)))))

    (testing "increments retry-count"
      (let [failed (job/fail-job running-job error test-later)]
        (is (= 1 (:retry-count failed)))))))

(deftest ^:unit cancel-job-test
  (testing "transitions job to :cancelled"
    (let [pending-job (job/create-job base-input test-id test-now)
          cancelled (job/cancel-job pending-job test-later)]
      (is (= :cancelled (:status cancelled)))
      (is (= test-later (:completed-at cancelled)))
      (is (= test-later (:updated-at cancelled))))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(deftest ^:unit calculate-retry-delay-test
  (let [config {:backoff-strategy :exponential
                :initial-delay-ms 1000
                :max-delay-ms 60000}]

    (testing "exponential backoff: retry 0 → 1000ms"
      (is (= 1000 (job/calculate-retry-delay 0 config 0))))

    (testing "exponential backoff: retry 1 → 2000ms"
      (is (= 2000 (job/calculate-retry-delay 1 config 0))))

    (testing "exponential backoff: retry 2 → 4000ms"
      (is (= 4000 (job/calculate-retry-delay 2 config 0))))

    (testing "caps at max-delay"
      (is (= 60000 (job/calculate-retry-delay 10 config 0))))

    (testing "adds jitter-ms to delay"
      (is (= 1500 (job/calculate-retry-delay 0 config 500))))

    (testing "linear backoff"
      (let [linear {:backoff-strategy :linear :initial-delay-ms 1000 :max-delay-ms 60000}]
        (is (= 1000 (job/calculate-retry-delay 0 linear 0)))
        (is (= 2000 (job/calculate-retry-delay 1 linear 0)))
        (is (= 3000 (job/calculate-retry-delay 2 linear 0)))))

    (testing "constant backoff"
      (let [constant {:backoff-strategy :constant :initial-delay-ms 5000 :max-delay-ms 60000}]
        (is (= 5000 (job/calculate-retry-delay 0 constant 0)))
        (is (= 5000 (job/calculate-retry-delay 5 constant 0)))))))

(deftest ^:unit schedule-retry-test
  (let [config {:backoff-strategy :exponential
                :initial-delay-ms 1000
                :max-delay-ms 60000}
        job-at-retry-0 {:retry-count 0}]

    (testing "returns Instant 1000ms after now with 0 jitter"
      (let [retry-at (job/schedule-retry job-at-retry-0 config test-now 0)]
        (is (= (.plusMillis test-now 1000) retry-at))))))

(deftest ^:unit prepare-retry-test
  (let [config {:backoff-strategy :exponential
                :initial-delay-ms 1000
                :max-delay-ms 60000}
        pending-job (job/create-job base-input test-id test-now)
        running-job (job/start-job pending-job test-now)
        error {:message "Timeout"}
        failed-job (job/fail-job running-job error test-now)]

    (testing "resets status to :pending"
      (let [retry-job (job/prepare-retry failed-job config test-later 0)]
        (is (= :pending (:status retry-job)))))

    (testing "sets execute-at for future execution"
      (let [retry-job (job/prepare-retry failed-job config test-later 0)]
        (is (= (.plusMillis test-later 2000) (:execute-at retry-job)))))

    (testing "clears started-at, error, result"
      (let [retry-job (job/prepare-retry failed-job config test-later 0)]
        (is (nil? (:started-at retry-job)))
        (is (nil? (:error retry-job)))
        (is (nil? (:result retry-job)))))))

;; =============================================================================
;; Filtering & Querying
;; =============================================================================

(deftest ^:unit ready-for-execution?-test
  (testing "pending job with no execute-at is ready"
    (let [job (job/create-job base-input test-id test-now)]
      (is (job/ready-for-execution? job test-later))))

  (testing "pending job with execute-at in the past is ready"
    (let [job (assoc (job/create-job base-input test-id test-now) :execute-at test-now)]
      (is (job/ready-for-execution? job test-later))))

  (testing "pending job with execute-at in the future is not ready"
    (let [job (assoc (job/create-job base-input test-id test-now) :execute-at test-later)]
      (is (not (job/ready-for-execution? job test-now)))))

  (testing "non-pending job is not ready"
    (let [job (job/start-job (job/create-job base-input test-id test-now) test-now)]
      (is (not (job/ready-for-execution? job test-later))))))

(deftest ^:unit filter-executable-jobs-test
  (let [id1 (UUID/randomUUID)
        id2 (UUID/randomUUID)
        id3 (UUID/randomUUID)
        j1 (job/create-job base-input id1 test-now)
        j2 (assoc (job/create-job base-input id2 test-now) :execute-at test-later)
        j3 (job/start-job (job/create-job base-input id3 test-now) test-now)
        jobs [j1 j2 j3]]

    (testing "returns only jobs ready for execution"
      (let [ready (job/filter-executable-jobs jobs test-now)]
        (is (= 1 (count ready)))
        (is (= id1 (:id (first ready))))))))

(deftest ^:unit sort-by-priority-test
  (let [low    (assoc (job/create-job base-input (UUID/randomUUID) test-now) :priority :low)
        normal (assoc (job/create-job base-input (UUID/randomUUID) test-now) :priority :normal)
        high   (assoc (job/create-job base-input (UUID/randomUUID) test-now) :priority :high)
        critical (assoc (job/create-job base-input (UUID/randomUUID) test-now) :priority :critical)
        sorted (job/sort-by-priority [low normal high critical])]

    (testing "sorts critical first, low last"
      (is (= :critical (:priority (first sorted))))
      (is (= :low (:priority (last sorted)))))))

(deftest ^:unit jobs-by-status-test
  (let [j1 (job/create-job base-input (UUID/randomUUID) test-now)
        j2 (job/create-job base-input (UUID/randomUUID) test-now)
        j3 (job/start-job (job/create-job base-input (UUID/randomUUID) test-now) test-now)
        grouped (job/jobs-by-status [j1 j2 j3])]

    (testing "groups correctly by status"
      (is (= 2 (count (:pending grouped))))
      (is (= 1 (count (:running grouped)))))))

;; =============================================================================
;; Validation
;; =============================================================================

(deftest ^:unit valid-job-transition?-test
  (testing "allowed transitions"
    (is (job/valid-job-transition? :pending :running))
    (is (job/valid-job-transition? :pending :cancelled))
    (is (job/valid-job-transition? :running :completed))
    (is (job/valid-job-transition? :running :failed))
    (is (job/valid-job-transition? :running :retrying))
    (is (job/valid-job-transition? :failed :pending)))

  (testing "disallowed transitions"
    (is (not (job/valid-job-transition? :completed :running)))
    (is (not (job/valid-job-transition? :cancelled :running)))
    (is (not (job/valid-job-transition? :pending :completed)))))

(deftest ^:unit can-retry?-test
  (testing "job with retries remaining can retry"
    (let [job (job/create-job base-input test-id test-now)]
      (is (job/can-retry? job))))

  (testing "job at max retries cannot retry"
    (let [job (assoc (job/create-job base-input test-id test-now)
                     :retry-count 3 :max-retries 3)]
      (is (not (job/can-retry? job))))))

;; =============================================================================
;; Statistics
;; =============================================================================

(deftest ^:unit calculate-duration-test
  (let [started-at (Instant/parse "2026-01-01T12:00:00Z")
        completed-at (Instant/parse "2026-01-01T12:00:10Z")]

    (testing "calculates duration in milliseconds"
      (let [job (assoc (job/create-job base-input test-id test-now)
                       :started-at started-at
                       :completed-at completed-at)]
        (is (= 10000 (job/calculate-duration job)))))

    (testing "returns nil when timestamps missing"
      (let [job (job/create-job base-input test-id test-now)]
        (is (nil? (job/calculate-duration job)))))))

(deftest ^:unit job-summary-test
  (testing "returns key fields only"
    (let [j (job/create-job base-input test-id test-now)
          summary (job/job-summary j)]
      (is (= test-id (:id summary)))
      (is (= :send-email (:job-type summary)))
      (is (= :pending (:status summary)))
      (is (= :default (:queue summary)))
      (is (= :normal (:priority summary)))
      (is (= 0 (:retry-count summary))))))

(deftest ^:unit aggregate-stats-test
  (let [id1 (UUID/randomUUID)
        id2 (UUID/randomUUID)
        id3 (UUID/randomUUID)
        started-at (Instant/parse "2026-01-01T12:00:00Z")
        done-at    (Instant/parse "2026-01-01T12:00:05Z")
        j1 (job/create-job base-input id1 test-now)
        j2 (-> (job/create-job base-input id2 test-now)
               (job/start-job started-at)
               (job/complete-job {:ok true} done-at))
        j3 (-> (job/create-job base-input id3 test-now)
               (job/start-job started-at)
               (job/fail-job {:message "err"} done-at))
        stats (job/aggregate-stats [j1 j2 j3])]

    (testing "counts by status"
      (is (= 3 (:total stats)))
      (is (= 1 (:pending stats)))
      (is (= 1 (:completed stats)))
      (is (= 1 (:retrying stats))))

    (testing "calculates average duration for completed jobs"
      (is (= 5000 (:avg-duration-ms stats))))))
