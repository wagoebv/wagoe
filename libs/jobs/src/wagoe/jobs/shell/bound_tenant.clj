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
