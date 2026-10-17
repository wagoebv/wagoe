(ns wagoe.jobs.shell.recurring-test
  "The ticker's policy, on the in-memory backend. Every backend's lease and
   idempotency store are swept in `recurring_surface_test`."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.recurring :as recurring]
            [wagoe.jobs.shell.worker :as worker])
  (:import [java.time Instant]))

(defn- at [s] (Instant/parse s))

(defn- backend []
  {:queue       (mem/create-in-memory-job-queue)
   :state       (mem/create-in-memory-recurring-state)
   :idempotency (mem/create-in-memory-idempotency-store)})

(defn- drain [queue]
  (loop [out []]
    (if-let [j (ports/dequeue-job! queue :default "w")]
      (recur (conj out j))
      out)))

(defn- seed-tick!
  "Pretend `job-type` last fired at `instant`."
  [{:keys [state]} job-type instant]
  (ports/record-tick! state (name job-type) (at instant)))

(deftest ^:unit a-due-tick-enqueues-its-job-once
  (let [b (backend)
        s (recurring/create-scheduler b)]
    (recurring/schedule-recurring! s :nightly-report "0 2 * * *" {:args {:kind :sales}})
    (seed-tick! b :nightly-report "2026-01-01T02:00:00Z")
    (is (= [] (recurring/tick! s (at "2026-01-02T01:59:00Z"))) "not yet due")
    (is (= [:nightly-report] (recurring/tick! s (at "2026-01-02T02:00:03Z"))))
    (is (= [] (recurring/tick! s (at "2026-01-02T02:00:08Z"))) "the next pass finds nothing")
    (let [[j & more] (drain (:queue b))]
      (is (empty? more))
      (is (= :nightly-report (:job-type j)))
      (is (= {:kind :sales} (:args j)))
      (is (= (at "2026-01-02T02:00:00Z") (get-in j [:metadata :recurring-tick]))))))

(deftest ^:unit two-tickers-enqueue-each-tick-once
  (let [b  (backend)
        s1 (recurring/create-scheduler b)
        s2 (recurring/create-scheduler b)]
    (doseq [s [s1 s2]]
      (recurring/schedule-recurring! s :nightly-report "0 2 * * *"))
    (seed-tick! b :nightly-report "2026-01-01T02:00:00Z")
    (testing "the lease lets one of them tick"
      (is (= [:nightly-report] (recurring/tick! s1 (at "2026-01-02T02:00:01Z"))))
      (is (= [] (recurring/tick! s2 (at "2026-01-02T02:00:02Z")))))
    (is (= 1 (count (drain (:queue b)))))))

(deftest ^:unit the-idempotency-key-holds-when-two-tickers-overlap
  ;; Two leaders at once: a lease handed over while the old holder was paused.
  ;; Separate states make both leaders and let both read the old cursor.
  (let [b  (backend)
        s1 (recurring/create-scheduler (assoc b :state (mem/create-in-memory-recurring-state)))
        s2 (recurring/create-scheduler (assoc b :state (mem/create-in-memory-recurring-state)))]
    (doseq [s [s1 s2]]
      (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
      (ports/record-tick! (:state s) "nightly-report" (at "2026-01-01T02:00:00Z")))
    (is (= [:nightly-report] (recurring/tick! s1 (at "2026-01-02T02:00:01Z"))))
    (is (= [] (recurring/tick! s2 (at "2026-01-02T02:00:02Z"))))
    (is (= 1 (count (drain (:queue b)))))))

(deftest ^:unit missed-ticks-run-once-after-downtime
  (let [b (backend)
        s (recurring/create-scheduler b)]
    (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
    (seed-tick! b :nightly-report "2026-01-01T02:00:00Z")
    ;; Down from the 1st to the 5th at noon: four ticks missed.
    (is (= [:nightly-report] (recurring/tick! s (at "2026-01-05T12:00:00Z"))))
    (let [jobs (drain (:queue b))]
      (is (= 1 (count jobs)))
      (is (= (at "2026-01-05T02:00:00Z") (get-in (first jobs) [:metadata :recurring-tick]))
          "the latest missed tick, not the first"))
    (is (= (at "2026-01-05T02:00:00Z") (ports/last-tick (:state b) "nightly-report")))
    (is (= [] (recurring/tick! s (at "2026-01-05T12:00:05Z"))))))

(deftest ^:unit a-new-schedule-does-not-fire-for-the-past
  (let [b (backend)
        s (recurring/create-scheduler b)]
    (recurring/schedule-recurring! s :nightly-report "@yearly")
    (is (= [] (recurring/tick! s (Instant/now))))
    (is (empty? (drain (:queue b))))))

(deftest ^:unit schedules-run-in-their-zone
  (let [b (backend)
        s (recurring/create-scheduler b {:zone "Europe/Amsterdam"})]
    (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
    (seed-tick! b :nightly-report "2026-01-01T01:00:00Z")
    (is (= [] (recurring/tick! s (at "2026-01-02T00:59:00Z"))))
    (is (= [:nightly-report] (recurring/tick! s (at "2026-01-02T01:00:00Z"))) "02:00 CET")))

(deftest ^:unit bad-schedules-are-refused
  (let [s (recurring/create-scheduler (backend))]
    (doseq [[expr opts] [["0 2 * *" nil] ["0 0 30 2 *" nil] ["0 2 * * *" {:zone "Mars/Olympus"}]]]
      (let [e (try (recurring/schedule-recurring! s :x expr opts)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :validation-error (:type (ex-data e))) (pr-str expr opts))))))

(deftest ^:unit enqueue-once-dedupes-retries
  (let [{:keys [queue idempotency]} (backend)
        job #(hash-map :id (random-uuid) :job-type :send-receipt :queue :default
                       :priority :normal :status :pending :args {:order 42}
                       :created-at (Instant/now) :updated-at (Instant/now))
        first-id (recurring/enqueue-once! queue idempotency :default (job) "order-42")]
    (is (uuid? first-id))
    (is (nil? (recurring/enqueue-once! queue idempotency :default (job) "order-42"))
        "a retry with the same key is a no-op")
    (is (some? (recurring/enqueue-once! queue idempotency :default (job) "order-43")))
    (is (= 2 (ports/queue-size queue :default)))))

(deftest ^:unit a-failed-enqueue-releases-its-key
  (let [{:keys [idempotency]} (backend)
        broken #_{:clj-kondo/ignore [:missing-protocol-method]}
               (reify ports/IJobQueue
                 (enqueue-job! [_ _ _] (throw (ex-info "down" {:type :internal-error}))))
        j      {:id (random-uuid) :job-type :send-receipt}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (recurring/enqueue-once! broken idempotency :default j "order-42")))
    (is (true? (ports/claim-key! idempotency "send-receipt" "order-42" 1000))
        "the retry can claim it")))

(deftest ^:unit a-job-type-with-no-handler-is-reported-once
  (let [registry (worker/create-job-registry)
        reported (atom [])
        s        (recurring/create-scheduler (assoc (backend) :registry registry))]
    (ports/register-handler! registry :nightly-report (fn [_] {:success? true}))
    (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
    (recurring/schedule-recurring! s :nightly-reprot "0 2 * * *")
    (with-redefs-fn {#'recurring/report-unhandled! (fn [types _] (swap! reported conj types))}
      (fn []
        (recurring/tick! s (at "2026-01-02T01:00:00Z"))
        (recurring/tick! s (at "2026-01-02T01:00:05Z"))
        (is (= [[:nightly-reprot]] @reported) "on the first tick, not every tick")
        (recurring/schedule-recurring! s :weekly-digest "0 3 * * 1")
        (recurring/tick! s (at "2026-01-02T01:00:10Z"))
        (is (= [[:nightly-reprot] [:weekly-digest]] @reported) "a schedule added later is checked too")))))

(deftest ^:unit only-the-lease-holder-reports
  (let [b        (backend)
        registry (worker/create-job-registry)
        reported (atom [])
        holder   (recurring/create-scheduler (assoc b :registry registry))
        other    (recurring/create-scheduler (assoc b :registry registry))]
    (doseq [s [holder other]]
      (recurring/schedule-recurring! s :nightly-reprot "0 2 * * *"))
    (with-redefs-fn {#'recurring/report-unhandled! (fn [types _] (swap! reported conj types))}
      (fn []
        (recurring/tick! holder (at "2026-01-02T01:00:00Z"))
        (recurring/tick! other (at "2026-01-02T01:00:01Z"))
        (is (= [[:nightly-reprot]] @reported))))))

(deftest ^:unit a-failing-registry-does-not-stop-the-tick
  (let [b        (backend)
        registry #_{:clj-kondo/ignore [:missing-protocol-method]}
                 (reify ports/IJobRegistry
                   (list-handlers [_] (throw (ex-info "down" {:type :internal-error}))))
        s        (recurring/create-scheduler (assoc b :registry registry))]
    (recurring/schedule-recurring! s :nightly-report "0 2 * * *")
    (seed-tick! b :nightly-report "2026-01-01T02:00:00Z")
    (is (= [:nightly-report] (recurring/tick! s (at "2026-01-02T02:00:01Z"))))))
