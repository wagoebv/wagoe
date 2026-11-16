(ns wagoe.jobs-db-postgres-test
  "The jobs :db adapter on PostgreSQL (BOU-640)."
  (:require [clojure.test :refer [deftest is testing]]
            [support.embedded-pg :as epg]
            [wagoe.jobs.ports :as jobs]
            [wagoe.jobs.shell.adapters.db :as jobs-db]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.shell.provisioning :as provisioning])
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

(deftest ^:integration a-job-enqueued-in-a-tenant-request-waits-for-its-commit
  ;; A worker that ran it before the commit missed the request's rows: an
  ;; export finished as :gone and stayed pending (BOU-993)
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)
        ds  (:datasource ctx)]
    (try
      (jobs-db/create-jobs-table! ds)
      (db/execute-ddl! ctx "CREATE SCHEMA tenant_a")
      (let [q   (jobs-db/create-db-job-queue ds)
            job {:id (random-uuid) :job-type :send-email :queue :default :priority :normal
                 :status :pending :args {} :created-at (Instant/now) :updated-at (Instant/now)}]
        (binding [db/*tenant-id* "t1"]
          (provisioning/call-in-schema ctx "tenant_a"
                                       (fn [_]
                                         (jobs/enqueue-job! q :default job)
                                         (is (nil? @(future (jobs/dequeue-job! q :default (random-uuid))))
                                             "a worker sees it before the request commits"))))
        (is (= ["t1" (:id job)] ((juxt (comp :tenant-id :metadata) :id)
                                 (jobs/dequeue-job! q :default (random-uuid))))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))

(deftest ^:integration a-failed-enqueue-leaves-the-tenant-request-usable
  ;; PostgreSQL refuses every later statement of a transaction after a failed
  ;; one, so the export's cleanup ran into an aborted transaction (BOU-993)
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)
        ds  (:datasource ctx)]
    (try
      (jobs-db/create-jobs-table! ds)
      (db/execute-ddl! ctx "CREATE SCHEMA tenant_a")
      (db/execute-ddl! ctx "CREATE TABLE tenant_a.exports (id INT)")
      (let [q   (jobs-db/create-db-job-queue ds)
            job {:id (random-uuid) :job-type :send-email :queue :default :priority :normal
                 :status :pending :args {} :created-at (Instant/now) :updated-at (Instant/now)}]
        (provisioning/call-in-schema ctx "tenant_a"
                                     (fn [tx]
                                       (db/execute-update! tx {:insert-into :exports :values [{:id 1}]})
            ;; Longer than the queue column: the insert fails in the database
                                       (is (thrown? Exception (jobs/enqueue-job! q (keyword (apply str (repeat 300 "q"))) job)))
                                       (db/execute-update! tx {:delete-from :exports :where [:= :id 1]})))
        (is (= [] (db/execute-query! ctx {:select [:*] :from [:tenant_a.exports]}))
            "the cleanup committed"))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
