(ns wagoe.jobs.shell.bound-tenant-test
  "A job enqueued in a tenant runs in it (BOU-987)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.platform.database :as db])
  (:import [java.time Instant]))

(defn- enqueue-and-take [queue job]
  (ports/enqueue-job! queue :default job)
  (ports/dequeue-job! queue :default "w"))

(defn- a-job [& [metadata]]
  (job/create-job (cond-> {:job-type :report} metadata (assoc :metadata metadata))
                  (random-uuid) (Instant/now)))

(deftest ^:unit ^:security a-job-enqueued-in-a-tenant-carries-it
  (let [queue (mem/create-in-memory-job-queue)]
    (is (= "t1" (get-in (binding [db/*tenant-id* "t1"] (enqueue-and-take queue (a-job)))
                        [:metadata :tenant-id])))
    (testing "work in one tenant cannot queue work in another"
      (is (= "t1" (get-in (binding [db/*tenant-id* "t1"] (enqueue-and-take queue (a-job {:tenant-id "t2"})))
                          [:metadata :tenant-id]))))
    (testing "outside a tenant, the tenant the job names stands"
      (is (= "t2" (get-in (enqueue-and-take queue (a-job {:tenant-id "t2"})) [:metadata :tenant-id]))))
    (testing "outside a tenant, none"
      (is (nil? (get-in (enqueue-and-take queue (a-job)) [:metadata :tenant-id]))))))
