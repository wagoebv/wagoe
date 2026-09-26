(ns shop.product.shell.persistence
  "Persistence layer for product module."
  (:require [shop.product.ports :as ports]
            [wagoe.platform.database :as db]))

(defn- select-by-id [db-ctx id]
  (db/execute-one! db-ctx {:select [:*] :from [:products] :where [:= :id id]}))

(defrecord DatabaseProductRepository [db-ctx]
  ports/IProductRepository
  (create [_this entity]
    (db/execute-update! db-ctx {:insert-into :products :values [entity]})
    (select-by-id db-ctx (:id entity)))
  (find-by-id [_this id]
    (select-by-id db-ctx id))
  (find-all [_this opts]
    (db/execute-query! db-ctx {:select [:*]
                               :from [:products]
                               :limit (or (:limit opts) 20)}))
  (update-entity [_this entity]
    (db/execute-update! db-ctx {:update :products
                                :set (dissoc entity :id)
                                :where [:= :id (:id entity)]})
    (select-by-id db-ctx (:id entity)))
  (delete [_this id]
    (db/execute-update! db-ctx {:delete-from :products :where [:= :id id]})))

(defn create-repository [db-ctx]
  (->DatabaseProductRepository db-ctx))
