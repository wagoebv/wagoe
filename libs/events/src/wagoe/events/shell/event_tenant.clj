(ns wagoe.events.shell.event-tenant
  "A handler runs in its event's tenant (BOU-985)."
  (:require [wagoe.events.core.event :as event]
            [wagoe.platform.database :as db]))

(defn in-event-tenant
  "`handler`, called in the tenant of the event it gets: through `tenant-runner`
   (fn [tenant-id f], tenant's `:wagoe/in-tenant`) when there is one, so it
   runs in that tenant's schema, else with `db/*tenant-id*` bound. A handler
   subscribed at boot ran with no tenant, so its writes went to public and a
   push it sent reached every tenant."
  [tenant-runner handler]
  (fn [e]
    (if-let [t (:tenant-id e)]
      (if tenant-runner
        (tenant-runner t #(handler e))
        (binding [db/*tenant-id* t] (handler e)))
      (handler e))))

(defn visible
  "`events` a caller bound to a tenant may see: its own and system events."
  [events]
  (if-let [t db/*tenant-id*]
    (filterv #(event/matches-tenant? t %) events)
    (vec events)))
