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
      (let [q      (jobs-db/create-db-job-queue ds :lease-ms 1)
            worker (random-uuid)
            job    {:id (random-uuid) :job-type :send-email :queue :default :priority :normal
                    :status :pending :args {} :created-at (Instant/now) :updated-at (Instant/now)}]
        (jobs/enqueue-job! q :default job)
        (is (= (:id job) (:id (jobs/dequeue-job! q :default worker))))
        (testing "the ack deletes the claim"
          ;; A failed ack leaves the row `processing`, which queue-size and
          ;; dequeue do not see; only a lapsed lease brings it back.
          (jobs/ack-job! q :default worker (:id job))
          (Thread/sleep 50)
          (is (= {:reclaimed 0} (jobs/reclaim-abandoned-jobs! q :default)))
          (is (nil? (jobs/dequeue-job! q :default worker)))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
