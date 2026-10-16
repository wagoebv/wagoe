(ns wagoe.jobs-workflow-mysql-test
  "The jobs `:db` adapter and workflow's boot schema on MySQL (BOU-650).

   Both booted twice, as a restart does, then used. Requires MySQL:
   `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [support.mysql :as mysql]
            [wagoe.jobs.ports :as jobs]
            [wagoe.jobs.shell.adapters.db :as jobs-db]
            [wagoe.workflow.shell.entity-uuid :as entity-uuid]
            [wagoe.workflow.shell.unique-instances :as unique-instances]
            [wagoe.workflow.ports :as workflow]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.workflow.shell.persistence :as workflow-persistence])
  (:import [java.time Instant]))

(def ^:private with-mysql (partial mysql/with-database "jw"))

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
                                               (:id low))))))
        (testing "a due scheduled job turns ready, compared on DATETIME(6)"
          (jobs/schedule-job! q :later (job :normal) (.minusSeconds (Instant/now) 1))
          (is (= 1 (jobs/process-scheduled-jobs! q)))
          (is (= 1 (jobs/queue-size q :later))))
        (testing "an expired claim is reclaimed"
          (let [short-lease (jobs-db/create-db-job-queue datasource :lease-ms 1)]
            (is (some? (jobs/dequeue-job! short-lease :later "w2")))
            (Thread/sleep 20)
            (is (= {:reclaimed 1} (jobs/reclaim-abandoned-jobs! short-lease :later)))))))))

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

(deftest ^:integration boot-changes-are-looked-for-in-this-database-only
  ;; A nil catalog searched every database on the server, so another
  ;; database's workflow_instances silenced the "run migrate up" warning (BOU-660).
  (with-mysql
    (fn [other]
      (ig/init-key :wagoe/workflow-db-schema {:ctx other :profile :test})
      (is (entity-uuid/present? (:datasource other)))
      (is (unique-instances/present? (:datasource other)))
      (mysql/with-database "jw_this"
        (fn [{:keys [datasource]}]
          (is (not (entity-uuid/present? datasource)))
          (is (not (unique-instances/present? datasource))))))))
