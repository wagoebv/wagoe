(ns wagoe.jobs.shell.module-wiring-test
  "Jobs already spelled the shared vocabulary correctly — `:memory` | `:redis` |
   `:db`. It accepts the other modules' spellings so that moving between config
   blocks does not mean remembering which one wanted which word (BOU-436)."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.jobs.shell.module-wiring :as wiring]))

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
                               {:queue     (:queue runtime)
                                :runtime   runtime
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
                                {:queue     (:queue runtime)
                                 :runtime   runtime
                                 :recurring {:nightly-report {:cron "0 2 * *"}}}))))))
