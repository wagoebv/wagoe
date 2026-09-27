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

(defn- start-workflows!
  "Start the workflows of the children created with `created`, then its own
   (`start`, when it has one), after the commit: the workflow store writes on
   a connection of its own. No row without its workflow: on a failure the ones
   started are removed, the row deleted with its children, and the error
   rethrown."
  [repository children created start]
  (let [steps   (concat (for [[k {start-child :start undo :remove}] children
                              :when start-child
                              {:keys [id]} (get created k)]
                          [#(start-child id) #(undo id)])
                        (when start [[#(start (:id created)) (constantly nil)]]))
        started (volatile! [])]
    (try
      (doseq [[run undo] steps]
        (run)
        (vswap! started conj undo))
      (catch Exception e
        (run! #(%) @started)
        (ports/delete repository (:id created))
        (throw e)))))

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
    (let [prepared (core/prepare-new-product (apply dissoc data (keys children)) (generate-product-id) (current-time))
          created  (create-with-children repository children prepared data)]
      (start-workflows! repository children created nil)
      created))
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
