(ns shop.product.shell.persistence
  "Persistence layer for product module."
  (:require [shop.product.ports :as ports]
            [wagoe.platform.database :as db]
            [honey.sql :as sql]))

(defrecord DatabaseProductRepository [db-ctx]
  ports/IProductRepository
  (create [_this entity]
    (db/execute-one! db-ctx
      (sql/format {:insert-into :Products
                   :values [entity]
                   :returning [:*]})))
  (find-by-id [_this id]
    (db/execute-one! db-ctx
      (sql/format {:select [:*]
                   :from [:Products]
                   :where [:= :id id]})))
  (find-all [_this opts]
    (db/execute-query! db-ctx
      (sql/format {:select [:*]
                   :from [:Products]
                   :limit (:limit opts 20)})))
  (update-entity [_this entity]
    (db/execute-one! db-ctx
      (sql/format {:update :Products
                   :set (dissoc entity :id)
                   :where [:= :id (:id entity)]
                   :returning [:*]})))
  (delete [_this id]
    (db/execute-one! db-ctx
      (sql/format {:delete-from :Products
                   :where [:= :id id]}))))

(defn create-repository [db-ctx]
  (->DatabaseProductRepository db-ctx))
