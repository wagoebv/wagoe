(ns wagoe.workflow.shell.admin-adapter
  "The admin's `IEntityWorkflows` port over this module's store (BOU-563).
   Here and not in the admin, because this library depends on the admin and
   not the other way round."
  (:require [clojure.tools.logging :as log]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.persistence :as persistence])
  (:import [wagoe.workflow.shell.persistence WorkflowStore]))

(defn- in-transaction?
  "Whether the store lives on the datasource the admin's transaction was
   opened on, so it can delete through that transaction's connection."
  [store tx]
  (and (instance? WorkflowStore store)
       (some? (:transaction-of tx))
       (identical? (:datasource store) (:transaction-of tx))))

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

  (remove-entity-workflows! [_ tx entity-type entity-id]
    (if (in-transaction? store tx)
      (persistence/delete-entity-instances! (:datasource tx) entity-type entity-id)
      ;; Another datasource (or no transaction): not atomic with the entity
      ;; delete. A failure here still fails the admin's transaction, but an
      ;; instance deleted before it is not restored.
      (do (when tx
            (log/debug "workflow store is on another datasource; removing outside the admin transaction"
                       {:entity-type entity-type :entity-id entity-id}))
          (loop [removed 0]
            (if-let [instance (ports/find-instance-by-entity store entity-type entity-id)]
              (if (ports/delete-instance! store (:id instance))
                (recur (inc removed))
                removed)
              removed))))))

(defn create-entity-workflows
  [store]
  (->EntityWorkflows store))
