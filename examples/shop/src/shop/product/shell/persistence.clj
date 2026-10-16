(ns shop.product.shell.persistence
  "Persistence layer for product module."
  (:require [shop.product.ports :as ports]
            [wagoe.platform.database :as db])
  (:import [java.time Instant LocalDate]))

(defn- date->iso
  "A DATE column as the schema's YYYY-MM-DD. Drivers return java.sql.Date,
   whose JSON form depends on the JVM's time zone."
  [v]
  (cond (instance? java.sql.Date v) (str (.toLocalDate ^java.sql.Date v))
        (instance? LocalDate v) (str v)
        :else v))

(def ^:private enum-fields
  "Keywords in Clojure, strings in the database."
  #{})

(defn- ->row [entity]
  (reduce (fn [m k] (cond-> m (keyword? (get m k)) (update k name))) entity enum-fields))

(defn- ->entity [row]
  (some-> row
          (update-vals date->iso)
          (as-> r (reduce (fn [m k] (cond-> m (string? (get m k)) (update k keyword))) r enum-fields))))

(defn- select-by-id [db-ctx id]
  (->entity (db/execute-one! db-ctx {:select [:*] :from [:products] :where [:= :id id]})))

(defrecord DatabaseProductRepository [db-ctx]
  ports/IProductRepository
  (create [_this entity]
    (db/execute-update! db-ctx {:insert-into :products :values [(->row entity)]})
    (select-by-id db-ctx (:id entity)))
  (find-by-id [_this id]
    (select-by-id db-ctx id))
  (find-all [_this opts]
    (mapv ->entity (db/execute-query! db-ctx {:select [:*]
                                              :from [:products]
                                              :order-by [[:created-at :asc] [:id :asc]]
                                              :limit (or (:limit opts) 20)
                                              :offset (or (:offset opts) 0)})))
  (update-entity [_this entity]
    (let [changes (dissoc entity :id :created-at :updated-at)]
      (when (empty? changes)
        (throw (ex-info "Nothing to update" {:type :validation-error :id (:id entity)})))
      (db/execute-update! db-ctx {:update :products
                                  :set (->row (assoc changes :updated-at (Instant/now)))
                                  :where [:= :id (:id entity)]})
      (select-by-id db-ctx (:id entity))))
  (delete [_this id]
    (db/execute-update! db-ctx {:delete-from :products :where [:= :id id]}))
  (transact [_this f]
    (db/with-transaction* db-ctx (fn [_] (f)))))

(defn create-repository [db-ctx]
  (->DatabaseProductRepository db-ctx))
