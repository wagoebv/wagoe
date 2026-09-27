(ns shop.product.shell.service
  "Service layer for product module."
  (:require [shop.product.ports :as ports]
            [shop.product.core.product :as core])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- current-time []
  (Instant/now))

(defn- generate-product-id []
  (UUID/randomUUID))

(defn- create-with-children
  "Create `prepared` and each child `data` carries under a key of `children`,
   in one transaction: all of them or none. The product comes back with them."
  [repository children prepared data]
  (ports/transact repository
                  (fn []
                    (reduce-kv (fn [created k {:keys [foreign-key create]}]
                                 (cond-> created
                                   (contains? data k)
                                   (assoc k (mapv #(create (assoc % foreign-key (:id created))) (get data k)))))
                               (ports/create repository prepared)
                               children))))

(defrecord ProductService [repository children]
  ports/IProductService
  (create-product [_this data]
    (let [prepared (core/prepare-new-product (apply dissoc data (keys children)) (generate-product-id) (current-time))]
      (create-with-children repository children prepared data)))
  (get-product [_this id]
    (ports/find-by-id repository id))
  (list-products [_this opts]
    (ports/find-all repository opts))
  (update-product [_this id data]
    (ports/update-entity repository (assoc data :id id)))
  (delete-product [_this id]
    (ports/delete repository id)))

(defn create-service
  "`children` is what a create may carry: {key {:foreign-key k :create f}},
   one per entity that belongs to this one. The module wiring builds it."
  ([repository] (create-service repository {}))
  ([repository children]
   (->ProductService repository children)))
