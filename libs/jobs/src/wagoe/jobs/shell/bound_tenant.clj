(ns wagoe.jobs.shell.bound-tenant
  "The tenant a job is enqueued in rides along on it (BOU-987)."
  (:require [wagoe.platform.database :as db]))

(defn stamp
  "`job` with the bound tenant under `[:metadata :tenant-id]`. Without it the
   worker ran tenant work against public. The bound tenant wins over one the
   job names, so work in one tenant cannot queue work in another; outside a
   tenant the job's own stands."
  [job]
  (if db/*tenant-id*
    (assoc-in job [:metadata :tenant-id] (str db/*tenant-id*))
    job))

(defn after-commit
  "`(enqueue! job)`, `job` stamped now, once the transaction open on this
   thread commits, and never if it rolls back: a worker must not run it
   before the rows it reads are there (BOU-993). With none open, now, and its
   answer; deferred, the job's id."
  [job enqueue!]
  (let [job (stamp job)
        now (volatile! false)
        id  (db/after-commit! #(do (vreset! now true) (enqueue! job)))]
    (if @now id (:id job))))
