(ns wagoe.data-retention-test
  "Data export and retention through the assembled system: the user module's
   contributions reach platform's components, and retention is a recurring
   job only when configured."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.main :as main]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.system-config :as sys-config]
            [wagoe.user.ports :as user-ports])
  (:import [java.time Duration Instant]))

(defn- with-system [privacy-settings f]
  (let [cfg    (cond-> (assoc-in (config/load-config {:profile :test})
                                 [:active :wagoe/jobs] {:provider :memory :workers {:count 0}})
                 privacy-settings (assoc-in [:active :wagoe/privacy] privacy-settings))
        system (ig/init (main/worker-ig-config (sys-config/ig-config cfg)))]
    (try (f system) (finally (ig/halt! system)))))

(defn- audit! [audit user created-at]
  (user-ports/create-audit-log audit {:action :update :target-user-id (:id user)
                                      :target-user-email (:email user) :result :success
                                      :created-at created-at}))

(deftest ^:integration retention-runs-the-configured-policies-as-a-recurring-job
  (with-system {:retention {:user/audit-log {:retain "P1Y"}}}
    (fn [system]
      (let [users   (:wagoe/user-repository system)
            audit   (:wagoe/audit-repository system)
            ada     (user-ports/create-user users {:email (str (random-uuid) "@example.com") :name "Ada"
                                                   :role :user :active true :password-hash "x"})
            now     (Instant/now)
            _       (audit! audit ada (.minus now (Duration/ofDays 400)))
            recent  (audit! audit ada (.minus now (Duration/ofDays 1)))
            handler (job-ports/get-handler (:wagoe/job-registry system) :wagoe/retention)]
        (is (contains? @(:schedules (:wagoe/job-scheduler system)) :wagoe/retention))
        (is (:success? (handler {})))
        (is (= [(:id recent)]
               (map :id (user-ports/find-audit-logs-by-user audit (:id ada) {:limit 10}))))

        (testing "and the user module's exporter reaches the export"
          (is (= (:email ada)
                 (get-in (privacy/export-subject (:wagoe/data-export system) {:type :user :id (:id ada)})
                         [:sections :user :profile :email]))))))))

(deftest ^:integration retention-is-off-unless-configured
  (with-system nil
    (fn [system]
      (is (nil? (job-ports/get-handler (:wagoe/job-registry system) :wagoe/retention)))
      (is (not (contains? @(:schedules (:wagoe/job-scheduler system)) :wagoe/retention))))))
