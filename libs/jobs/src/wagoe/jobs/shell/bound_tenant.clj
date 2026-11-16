(ns wagoe.jobs.shell.bound-tenant
  "The tenant a job is enqueued in rides along on it (BOU-987)."
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.database :as db]))

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
   answer; deferred, the job's id, and a failure is only logged: the caller's
   work has committed."
  [job enqueue!]
  (let [job      (stamp job)
        returned (volatile! false)
        ran      (volatile! false)
        id       (db/after-commit!
                  (fn []
                    (vreset! ran true)
                    (if-not @returned
                      (enqueue! job)
                      (try (enqueue! job)
                           (catch Exception e
                             (log/error e "A job enqueued at commit was lost"
                                        {:job-id    (:id job)
                                         :job-type  (:job-type job)
                                         :tenant-id (get-in job [:metadata :tenant-id])}))))))]
    (vreset! returned true)
    (if @ran id (:id job))))
