(ns wagoe.tenant.core.audit
  "Entries for the tenant audit log.")

(defn data-export-entry
  "A platform admin downloading `tenant-id`'s data export. `context` is
   {:actor-id :actor-email :ip-address :user-agent}."
  [id tenant-id context now]
  (merge context {:id id :tenant-id tenant-id :action :data-export :created-at now}))
