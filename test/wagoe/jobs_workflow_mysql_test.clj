(ns wagoe.jobs-workflow-mysql-test
  "The jobs `:db` adapter and workflow's boot schema on MySQL (BOU-650).

   Both booted twice, as a restart does, then used. Requires MySQL:
   `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.jobs.ports :as jobs]
            [wagoe.jobs.shell.adapters.db :as jobs-db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.workflow.ports :as workflow]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.workflow.shell.persistence :as workflow-persistence])
  (:import [java.time Instant]))

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(def ^:private mysql-password
  (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe"))

(defn- server []
  (jdbc/get-datasource {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
                        :user "root" :password mysql-password}))

(defn- with-mysql
  "Run `f` with a db-context on a fresh database, dropped afterwards."
  [f]
  (let [dbname (str "jw_" (System/nanoTime))]
    (jdbc/execute! (server) [(str "CREATE DATABASE " dbname)])
    (let [ctx (factory/db-context
               (factory/mysql-config "127.0.0.1" mysql-port dbname "root" mysql-password))]
      (try (f ctx)
           (finally
             (factory/close-db-context! ctx)
             (jdbc/execute! (server) [(str "DROP DATABASE " dbname)]))))))

(defn- job [priority]
  {:id (random-uuid) :job-type :send-email :queue :default :priority priority
   :status :pending :args {:to "a@b.c"} :created-at (Instant/now) :updated-at (Instant/now)})

(deftest ^:integration the-jobs-db-adapter-runs-on-mysql
  (with-mysql
    (fn [{:keys [datasource]}]
      ;; Twice, as a restart does: the second finds tables and indexes there.
      (jobs-db/create-jobs-table! datasource)
      (jobs-db/create-jobs-table! datasource)
      (let [q    (jobs-db/create-db-job-queue datasource)
            low  (job :low)
            crit (job :critical)]
        (jobs/enqueue-job! q :default low)
        (jobs/enqueue-job! q :default crit)
        (testing "dequeue claims by priority, found by its UUID"
          (let [d (jobs/dequeue-job! q :default "w1")]
            (is (= (:id crit) (:id d)))
            (is (= :send-email (:job-type d)))))
        (testing "ack removes the claimed row, matched on its UUID"
          (jobs/ack-job! q :default "w1" (:id crit))
          (is (= 1 (jobs/queue-size q :default))))
        (testing "the store finds a job by its UUID"
          (is (= (:id low) (:id (jobs/find-job (jobs-db/create-db-job-store datasource)
                                               (:id low))))))))))

(deftest ^:integration workflows-boot-schema-runs-on-mysql
  (with-mysql
    (fn [ctx]
      (ig/init-key :wagoe/workflow-db-schema {:ctx ctx :profile :test})
      (testing "a second boot finds the tables there"
        (is (= {:status :initialized}
               (ig/init-key :wagoe/workflow-db-schema {:ctx ctx :profile :test}))))
      (let [store (workflow-persistence/create-workflow-store (:datasource ctx))
            id    (random-uuid)
            now   (Instant/now)]
        (workflow/save-instance! store {:id id :workflow-id :order :entity-type :order
                                        :entity-id (random-uuid) :current-state :draft
                                        :created-at now :updated-at now})
        (is (= :draft (:current-state (workflow/find-instance store id))))
        (is (= :paid (:current-state (workflow/update-instance-state! store id :paid))))))))
