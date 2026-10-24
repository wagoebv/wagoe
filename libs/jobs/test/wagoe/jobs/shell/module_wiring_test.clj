(ns wagoe.jobs.shell.module-wiring-test
  "Jobs already spelled the shared vocabulary correctly — `:memory` | `:redis` |
   `:db`. It accepts the other modules' spellings so that moving between config
   blocks does not mean remembering which one wanted which word (BOU-436)."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
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
    (is (= (ig/ref :wagoe/job-tenant-runner) (workers #{:wagoe/jobs :wagoe/tenant})))
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
