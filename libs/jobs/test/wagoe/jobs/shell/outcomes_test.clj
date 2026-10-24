(ns wagoe.jobs.shell.outcomes-test
  "The worker reports each run's outcome, on the in-memory and the DB store
   (BOU-832)."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.db :as db]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.outcomes :as outcomes]
            [wagoe.jobs.shell.worker :as worker]
            [wagoe.observability.errors.ports :as errors]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.test.logging :refer [with-silent-logging]]))

(defn- recording-metrics [calls]
  (reify metrics/IMetricsEmitter
    (inc-counter! [_ h v tags] (swap! calls conj [:counter h v tags]))
    (observe-histogram! [_ h v tags] (swap! calls conj [:histogram h v tags]))
    (set-gauge! [_ h v tags] (swap! calls conj [:gauge h v tags]))
    (observe-summary! [_ _h _v] nil)
    (time-histogram! [_ _h f] (f))
    (time-summary! [_ _h f] (f))))

(defn- recording-reporter [reports]
  (reify errors/IErrorReporter
    (capture-exception [_ _e] nil)
    (capture-event [_ _event] nil)
    (capture-message [_ message level context tags]
      (swap! reports conj {:message message :level level :context context :tags tags}))))

(defn- in-each-store
  "Calls f with [queue store idempotency] for the in-memory backend and the DB
   one, on the engine WAGOE_TEST_DB names."
  [f]
  (testing "in-memory"
    (let [{:keys [queue store]} (mem/create-in-memory-jobs-system)]
      (f queue store (mem/create-in-memory-idempotency-store))))
  (testing "db"
    (test-db/with-db "jobs_outcomes"
      (fn [{:keys [datasource]}]
        (db/create-jobs-table! datasource)
        (db/create-recurring-tables! datasource)
        (f (db/create-db-job-queue datasource) (db/create-db-job-store datasource)
           (db/create-db-idempotency-store datasource))))))

(defn- enqueue! [queue job-type]
  (ports/enqueue-job! queue :default (job/create-job {:job-type job-type :args {} :max-retries 1}
                                                     (random-uuid) (java.time.Instant/now))))

(deftest ^:integration the-worker-reports-each-run
  (with-silent-logging
    (in-each-store
     (fn [queue store & _]
       (let [calls    (atom [])
             reports  (atom [])
             hooked   (atom [])
             fail?    (atom true)
             registry (doto (worker/create-job-registry)
                        (ports/register-handler! :reports/generate
                                                 (fn [_] (if @fail?
                                                           {:success? false :error {:message "boom"}}
                                                           {:success? true}))))
             ;; Built once, as the wiring does for its pools: run-pending!
             ;; would otherwise build one, and a throttle, per call.
             config   {:report-outcome (outcomes/reporter
                                        {:store          store
                                         :metrics        (recording-metrics calls)
                                         :error-reporter (recording-reporter reports)
                                         :on-outcome     #(swap! hooked conj %)})}
             run!     (fn [] (enqueue! queue :reports/generate) (worker/run-pending! config queue store registry))]
         (run!)
         (run!)
         (testing "the store answers when it last succeeded and how many failures since"
           (let [record (ports/job-outcome store :reports/generate)]
             (is (= :failure (:last-outcome record)))
             (is (nil? (:last-succeeded-at record)))
             (is (= 2 (:failures-since-success record)))
             (is (= "boom" (:last-error record)))))
         (testing "the error tracker hears of the first failure only"
           (is (= 1 (count @reports)))
           (is (= {:job-type "reports/generate"} (:tags (first @reports)))))
         (reset! fail? false)
         (run!)
         (let [record (ports/job-outcome store :reports/generate)]
           (is (= :success (:last-outcome record)))
           (is (some? (:last-succeeded-at record)))
           (is (zero? (:failures-since-success record)))
           (is (= {:reports/generate record} (ports/job-outcomes store))))
         (testing "metrics: a counter by outcome, a duration, the last success"
           (is (= [2 1] [(count (filter #(= [:counter :jobs.runs 1 {:job-type "reports/generate" :outcome "failure"}] %) @calls))
                         (count (filter #(= [:counter :jobs.runs 1 {:job-type "reports/generate" :outcome "success"}] %) @calls))]))
           (is (= 3 (count (filter #(= :jobs.run.duration (second %)) @calls))))
           (is (= 1 (count (filter #(= :jobs.last-success (second %)) @calls)))))
         (testing "the hook gets every outcome"
           (is (= [:failure :failure :success] (map :outcome @hooked))))
         (testing "after a success, the next failure is reported again"
           (reset! fail? true)
           (run!)
           (is (= 2 (count @reports)))))))))

(deftest ^:integration a-failing-hook-does-not-fail-the-job
  (with-silent-logging
    (in-each-store
     (fn [queue store & _]
       (let [registry (doto (worker/create-job-registry)
                        (ports/register-handler! :ping (fn [_] {:success? true})))
             id       (enqueue! queue :ping)]
         (worker/run-pending! {:on-outcome (fn [_] (throw (ex-info "hook" {:type :internal-error})))}
                              queue store registry)
         (is (= :completed (:status (ports/find-job store id))))
         (is (= :success (:last-outcome (ports/job-outcome store :ping)))))))))

(deftest ^:unit without-anything-configured-a-worker-runs-as-before
  (let [{:keys [queue store]} (mem/create-in-memory-jobs-system)
        registry (doto (worker/create-job-registry)
                   (ports/register-handler! :ping (fn [_] {:success? true})))
        id       (enqueue! queue :ping)]
    (is (= 1 (worker/run-pending! {} queue store registry)))
    (is (= :completed (:status (ports/find-job store id))))))

(deftest ^:integration nodes-sharing-an-idempotency-store-report-a-failure-once
  (with-silent-logging
    (in-each-store
     (fn [queue store idempotency]
       (let [reports  (atom [])
             fail?    (atom true)
             registry (doto (worker/create-job-registry)
                        (ports/register-handler! :sync (fn [_] (if @fail?
                                                                 {:success? false :error {:message "down"}}
                                                                 {:success? true}))))
             ;; Two nodes, each with its own reporter, one store.
             node     #(hash-map :report-outcome (outcomes/reporter {:store          store
                                                                    :error-reporter (recording-reporter reports)
                                                                    :idempotency    idempotency}))
             [a b]    [(node) (node)]
             run!     (fn [cfg] (enqueue! queue :sync) (worker/run-pending! cfg queue store registry))]
         (run! a)
         (run! b)
         (run! a)
         (is (= 1 (count @reports)) "every node reported the same failing job")
         (testing "a success frees the throttle for the next failure"
           (reset! fail? false)
           (run! a)
           (reset! fail? true)
           (run! b)
           (is (= 2 (count @reports)))))))))

(deftest ^:integration a-job-overdue-a-success-is-reported-once-per-interval
  (with-silent-logging
    (in-each-store
     (fn [queue store idempotency]
       (let [reports  (atom [])
             fail?    (atom false)
             registry (doto (worker/create-job-registry)
                        (ports/register-handler! :sync (fn [_] (if @fail?
                                                                 {:success? false :error {:message "down"}}
                                                                 {:success? true})))
                        (ports/register-handler! :never-ok (fn [_] {:success? false :error {:message "x"}})))
             opts     {:store          store
                       :idempotency    idempotency
                       :error-reporter (recording-reporter reports)
                       :expect-success {:sync 1 :never-ok 1 :never-ran 1}}
             cfg      {:report-outcome (outcomes/reporter (dissoc opts :error-reporter))}
             later    (.plus (java.time.Instant/now) (java.time.Duration/ofHours 2))]
         (doseq [t [:sync :never-ok]]
           (enqueue! queue t))
         (worker/run-pending! cfg queue store registry)
         (is (= [] (outcomes/check-stale! opts (java.time.Instant/now))) "nothing is late yet")
         (testing "late: reported, but not a type that never ran or never succeeded"
           (is (= [:sync] (outcomes/check-stale! opts later)))
           (is (= "Job sync has not succeeded within 1h" (:message (first @reports))))
           (is (= {:job-type "sync"} (:tags (first @reports)))))
         (testing "within the interval it is not reported again"
           (is (= [] (outcomes/check-stale! opts later))))
         (testing "a failure within that interval shares the throttle"
           (reset! fail? true)
           (worker/run-pending! {:report-outcome (outcomes/reporter (assoc opts :error-reporter
                                                                            (recording-reporter reports)))}
                                (doto queue (enqueue! :sync)) store registry)
           (is (= 1 (count @reports)))))))))

(deftest ^:unit outcomes-can-be-limited-to-registered-job-types
  (let [store    (mem/create-in-memory-job-store)
        registry (doto (worker/create-job-registry)
                   (ports/register-handler! :kept (fn [_] {:success? true})))
        now      (java.time.Instant/now)]
    (doseq [t [:kept :renamed]]
      (ports/record-outcome! store {:job-type t :outcome :success :started-at now
                                    :finished-at now :duration-ms 1}))
    (is (= [:kept] (keys (outcomes/registered-outcomes store registry))))))

(deftest ^:integration a-unique-job-holds-its-key-through-its-retries
  (with-silent-logging
    (in-each-store
     (fn [queue store _]
       (let [refused  (atom [])
             attempts (atom 0)
             dup      #(job/create-job {:job-type :sync :unique-key "sync"} (random-uuid) (java.time.Instant/now))
             registry (doto (worker/create-job-registry)
                        (ports/register-handler! :sync
                                                 (fn [_]
                                                   (swap! attempts inc)
                                                   (swap! refused conj (ports/enqueue-job! queue :default (dup)))
                                                   {:success? false :error {:message "down"}})))
             cfg      {:retry-config {:initial-delay-ms 0 :jitter false}}]
         (ports/enqueue-job! queue :default (job/create-job {:job-type :sync :unique-key "sync" :max-retries 2}
                                                            (random-uuid) (java.time.Instant/now)))
         (worker/run-pending! cfg queue store registry)
         (is (= 2 @attempts) "setup: a run and a retry")
         (is (= [nil nil] @refused) "a second job got in while the first ran or waited to retry")
         (is (some? (ports/enqueue-job! queue :default (dup))) "the key outlived the job's last failure"))))))
