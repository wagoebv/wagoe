(ns wagoe.jobs.shell.module-wiring-test
  "Jobs already spelled the shared vocabulary correctly — `:memory` | `:redis` |
   `:db`. It accepts the other modules' spellings so that moving between config
   blocks does not mean remembering which one wanted which word (BOU-436)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [integrant.core :as ig]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.jobs.shell.module-wiring :as wiring]
            [wagoe.platform.ports.privacy :as privacy]))

(deftest ^:unit dead-letter-retention-needs-a-store-that-implements-it
  (is (satisfies? privacy/IRetentionPolicy
                  (ig/init-key :wagoe/job-dead-letter-retention {:store (mem/create-in-memory-job-store)})))
  (is (nil? (ig/init-key :wagoe/job-dead-letter-retention {:store (reify Object)}))
      "nil, which :wagoe/retention refuses when the policy is configured"))

(deftest ^:unit the-provider-vocabulary-is-shared
  (testing "the canonical spellings pass through untouched"
    (doseq [p [:memory :redis :db]]
      (is (= p (wiring/normalize-provider p)))))

  (testing "the other modules' spellings are accepted"
    (is (= :memory (wiring/normalize-provider :in-memory)))
    (is (= :db (wiring/normalize-provider :database))))

  (testing "omitting it keeps the documented default"
    (is (= :memory (wiring/normalize-provider nil)))))

(deftest ^:unit the-runtime-builds-from-either-spelling
  (doseq [provider [:memory :in-memory nil]]
    (let [runtime (ig/init-key :wagoe/jobs-runtime {:provider provider})]
      (try
        (is (some? (:queue runtime)) (str (pr-str provider) " built no queue"))
        (is (some? (:store runtime)) (str (pr-str provider) " built no store"))
        (finally (ig/halt-key! :wagoe/jobs-runtime runtime)))))

  (testing "and an unknown one still names the vocabulary"
    (let [e (try (ig/init-key :wagoe/jobs-runtime {:provider :postgres})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :wagoe/jobs-unknown-provider (:type (ex-data e)))))))

(deftest ^:unit the-scheduler-takes-its-schedules-from-config
  (let [runtime   (ig/init-key :wagoe/jobs-runtime {:provider :memory})
        scheduler (ig/init-key :wagoe/job-scheduler
                               {:runtime   runtime
                                :recurring {:nightly-report {:cron "0 2 * * *" :args {:kind :sales}}}
                                :scheduler {:zone "Europe/Amsterdam"}})]
    (try
      (is (= {:args {:kind :sales}}
             (get-in @(:schedules scheduler) [:nightly-report :job])))
      (is (= "Europe/Amsterdam" (str (get-in @(:schedules scheduler) [:nightly-report :zone]))))
      (is (some? (:executor scheduler)) "and it is ticking")
      (finally (ig/halt-key! :wagoe/job-scheduler scheduler))))

  (testing "a bad expression fails the boot"
    (let [runtime (ig/init-key :wagoe/jobs-runtime {:provider :memory})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ig/init-key :wagoe/job-scheduler
                                {:runtime   runtime
                                 :recurring {:nightly-report {:cron "0 2 * *"}}}))))))

(deftest ^:unit with-tenancy-the-workers-get-a-tenant-runner
  (let [workers #(get-in (wiring/ig-config {} {:enabled %}) [:components :wagoe/job-workers :tenant-runner])]
    (is (= (ig/ref :wagoe/in-tenant) (workers #{:wagoe/jobs :wagoe/tenant})))
    (is (nil? (workers #{:wagoe/jobs})))))

(defn on-outcome [_])

(deftest ^:unit the-workers-take-the-outcome-hook-from-config
  (is (= 'my.app/hook
         (get-in (wiring/ig-config {:on-outcome 'my.app/hook} {:enabled #{:wagoe/jobs}})
                 [:components :wagoe/job-workers :on-outcome])))
  (is (= [] (ig/init-key :wagoe/job-workers {:workers    {:count 0}
                                             :on-outcome `on-outcome})))
  (testing "a symbol that resolves to nothing fails the boot"
    (let [e (try (ig/init-key :wagoe/job-workers {:workers {:count 0} :on-outcome 'no.such/hook})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :configuration-error (:type (ex-data e)))))))

(deftest ^:unit expect-success-gives-the-scheduler-a-watch
  (let [runtime (ig/init-key :wagoe/jobs-runtime {:provider :memory})]
    (is (= {:nightly-report 26}
           (get-in (wiring/ig-config {:expect-success {:nightly-report 26}} {:enabled #{:wagoe/jobs}})
                   [:components :wagoe/job-scheduler :expect-success])))
    (let [s (ig/init-key :wagoe/job-scheduler {:runtime runtime :expect-success {:nightly-report 26}})]
      (try (is (fn? (:watch s)))
           (finally (ig/halt-key! :wagoe/job-scheduler s))))
    (testing "the watch checks only job types with a handler"
      (let [now (java.time.Instant/now)
            s   (ig/init-key :wagoe/job-scheduler {:runtime        runtime
                                                   :registry       (ig/init-key :wagoe/job-registry {:handler-maps []})
                                                   :expect-success {:nightly-report 26}})]
        (ports/record-outcome! (:store runtime) {:job-type :nightly-report :outcome :success :started-at now
                                                 :finished-at now :duration-ms 1})
        (try (is (= [] ((:watch s) (.plus now (java.time.Duration/ofHours 27)))))
             (finally (ig/halt-key! :wagoe/job-scheduler s)))))
    (testing "hours that are not a positive number fail the boot"
      (doseq [bad [{:nightly-report 0} {:nightly-report "26"} [:nightly-report 26] 26]]
        (let [e (try (ig/init-key :wagoe/job-scheduler {:runtime runtime :expect-success bad})
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :validation-error (:type (ex-data e))) (pr-str bad)))))))

(deftest ^:unit a-queue-that-does-not-shrink-is-reported-once
  (let [queue   (mem/create-in-memory-job-queue)
        t0      (java.time.Instant/now)
        at      #(.plus t0 (java.time.Duration/ofMinutes %))
        watch   (wiring/queue-watch queue (* 15 60000) (* 20 60000))
        warned? #(some (fn [e] (re-find #"queue :orphans" (str (:message e)))) (log-test/the-log))]
    (dotimes [_ 2]
      (ports/enqueue-job! queue :orphans (job/create-job {:job-type :x :queue :orphans} (random-uuid) t0)))
    (log-test/with-log
      (watch t0)
      (watch (at 10))
      (is (not (warned?)) "not yet stalled")
      (watch (at 16))
      (watch (at 30))
      (is (= 1 (count (filter #(re-find #"queue :orphans" (str (:message %))) (log-test/the-log))))
          "reported once per stall"))
    (testing "a queue that shrinks starts over"
      (ports/dequeue-job! queue :orphans "w1")
      (log-test/with-log
        (watch (at 31))
        (watch (at 40))
        (is (not (warned?)))))
    (testing "after a gap, as when the lease was elsewhere, it starts over"
      (log-test/with-log
        (watch (at 70))
        (is (not (warned?)) "39 min since it shrank, but this node did not see them")))))

(deftest ^:unit the-queue-watch-is-on-by-default
  (let [runtime (ig/init-key :wagoe/jobs-runtime {:provider :memory})
        config  (get-in (wiring/ig-config {} {:enabled #{:wagoe/jobs}}) [:components :wagoe/job-scheduler])
        start   #(ig/init-key :wagoe/job-scheduler (merge config {:runtime runtime :registry nil :error-reporter nil} %))]
    (let [s (start {})]
      (try (is (fn? (:watch s))) (finally (ig/halt-key! :wagoe/job-scheduler s))))
    (let [s (start {:stalled-queue-ms 0})]
      (try (is (nil? (:watch s)) "off, and with it the lease") (finally (ig/halt-key! :wagoe/job-scheduler s))))))

(deftest ^:unit a-recurring-job-on-an-unpolled-queue-warns-at-boot
  (let [runtime (ig/init-key :wagoe/jobs-runtime {:provider :memory})
        boot    #(ig/init-key :wagoe/job-scheduler {:runtime runtime :workers % :stalled-queue-ms 0
                                                    :recurring {:report {:cron "0 2 * * *" :queue :reports}
                                                                :sweep  {:cron "0 3 * * *"}}})]
    (log-test/with-log
      (ig/halt-key! :wagoe/job-scheduler (boot {:queues [:default]}))
      (is (log-test/logged? 'wagoe.jobs.shell.module-wiring :warn #"\{:report :reports\}")))
    (testing "not when a pool polls it, nor on a node without workers"
      (doseq [workers [{:queues [:default :reports]} {:count 0}]]
        (log-test/with-log
          (ig/halt-key! :wagoe/job-scheduler (boot workers))
          (is (not (log-test/logged? 'wagoe.jobs.shell.module-wiring :warn #"does not poll")) (pr-str workers)))))))
