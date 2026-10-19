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

(defn- start-workflows!
  "Start the workflows of the children created with `created`, then its own
   (`start`, when it has one). In the create's transaction, which each
   workflow joins: no row without its workflow."
  [children created start]
  (doseq [[k {start-child :start}] children
          :when start-child
          {:keys [id]} (get created k)]
    (start-child id))
  (when start (start (:id created))))

(defn- create-with-children
  "Create `prepared` and each child `data` carries under a key of `children`,
   with their workflows, in one transaction: all of them or none. The
   product comes back with its children."
  [repository children prepared data start]
  (ports/transact repository
                  (fn []
                    (let [created (reduce-kv (fn [created k {:keys [foreign-key create]}]
                                               (cond-> created
                                                 (contains? data k)
                                                 (assoc k (mapv #(create (assoc % foreign-key (:id created))) (get data k)))))
                                             (ports/create repository prepared)
                                             children)]
                      (start-workflows! children created start)
                      created))))

(defn- with-children
  "`rows` with their children under each key of `ks` that `children` can read,
   one query per child entity for all the rows."
  [children ks rows]
  (reduce (fn [rows k]
            (let [{:keys [foreign-key find]} (get children k)]
              (if (and find (seq rows))
                (let [by-parent (group-by (comp str foreign-key) (find (mapv :id rows)))]
                  (mapv #(assoc % k (get by-parent (str (:id %)) [])) rows))
                rows)))
          (vec rows)
          ks))

(defrecord ProductService [repository children]
  ports/IProductService
  (create-product [_this data]
    (let [prepared (core/prepare-new-product (apply dissoc data (keys children)) (generate-product-id) (current-time))]
      (create-with-children repository children prepared data nil)))
  (get-product [_this id]
    (some->> (ports/find-by-id repository id)
             vector
             (with-children children (keys children))
             first))
  (list-products [_this opts]
    ;; The children only when asked for: :include, a set of their keys.
    (with-children children (:include opts) (ports/find-all repository opts)))
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
