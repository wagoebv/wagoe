(ns wagoe.jobs-db-postgres-test
  "The jobs :db adapter on PostgreSQL (BOU-640)."
  (:require [clojure.test :refer [deftest is testing]]
            [support.embedded-pg :as epg]
            [wagoe.jobs.ports :as jobs]
            [wagoe.jobs.shell.adapters.db :as jobs-db]
            [wagoe.platform.shell.adapters.database.factory :as factory])
  (:import [java.time Instant]))

(deftest ^:integration a-worker-acks-its-job-on-postgresql
  ;; The worker id is a UUID and locked_by a VARCHAR: PostgreSQL refused the
  ;; comparison, so every finished job stayed `processing` and ran again.
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)
        ds  (:datasource ctx)]
    (try
      (jobs-db/create-jobs-table! ds)
      (let [q      (jobs-db/create-db-job-queue ds)
            worker (random-uuid)
            job    {:id (random-uuid) :job-type :send-email :queue :default :priority :normal
                    :status :pending :args {} :created-at (Instant/now) :updated-at (Instant/now)}]
        (jobs/enqueue-job! q :default job)
        (is (= (:id job) (:id (jobs/dequeue-job! q :default worker))))
        (testing "the ack deletes the claim"
          (jobs/ack-job! q :default worker (:id job))
          (is (zero? (jobs/queue-size q :default)))
          (is (nil? (jobs/dequeue-job! q :default worker)))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
