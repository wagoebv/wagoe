(ns wagoe.workflow.shell.admin-adapter
  "The admin's `IEntityWorkflows` port over this module's store (BOU-563).
   Here and not in the admin, because this library depends on the admin and
   not the other way round."
  (:require [wagoe.admin.ports :as admin-ports]
            [wagoe.workflow.ports :as ports]))

(defrecord EntityWorkflows [store]
  admin-ports/IEntityWorkflows

  (entity-workflows [_ entity-type entity-ids]
    ;; One lookup per id: the store has no batch read, and an admin page is
    ;; one page of rows.
    (into {}
          (keep (fn [id]
                  (when-let [instance (ports/find-instance-by-entity store entity-type id)]
                    [id {:instance-id (:id instance)
                         :workflow-id (:workflow-id instance)
                         :state       (:current-state instance)}])))
          entity-ids))

  (remove-entity-workflows! [_ entity-type entity-id]
    (loop [removed 0]
      (if-let [instance (ports/find-instance-by-entity store entity-type entity-id)]
        (if (ports/delete-instance! store (:id instance))
          (recur (inc removed))
          removed)
        removed))))

(defn create-entity-workflows
  [store]
  (->EntityWorkflows store))
