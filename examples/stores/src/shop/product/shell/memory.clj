(ns shop.product.shell.memory
  "The product repository over an atom: the module's port on a store that is
   not SQL. It answers as shell/persistence.clj does, and
   shop.product.shell.repository-contract-test holds both to that."
  (:require [integrant.core :as ig]
            [shop.product.ports :as ports])
  (:import [java.time Instant]))

(defn- oldest-first
  "The SQL repository's order: created-at, then id. The id by its string, which
   orders as the database's UUID does; UUID.compareTo compares signed halves."
  [rows]
  (sort-by (juxt :created-at (comp str :id)) rows))

(defrecord MemoryProductRepository [state]
  ports/IProductRepository
  (create [_ {:keys [id] :as entity}]
    ;; The primary key, which SQL enforces and a map does not.
    (let [[before _] (swap-vals! state #(cond-> % (not (contains? % id)) (assoc id entity)))]
      (when (contains? before id)
        (throw (ex-info "A product with this id exists" {:type :conflict :id id})))
      entity))
  (find-by-id [_ id]
    (get @state id))
  (find-all [_ {:keys [limit offset]}]
    (->> (vals @state) oldest-first (drop (or offset 0)) (take (or limit 20)) vec))
  (update-entity [_ {:keys [id] :as entity}]
    (let [changes (dissoc entity :id :created-at :updated-at)]
      (when (empty? changes)
        (throw (ex-info "Nothing to update" {:type :validation-error :id id})))
      (-> (swap! state #(cond-> % (contains? % id) (update id merge changes {:updated-at (Instant/now)})))
          (get id))))
  (delete [_ id]
    (swap! state dissoc id)
    nil)
  (transact [_ f]
    ;; All or nothing, as a SQL transaction is, but not isolated: a write
    ;; another thread makes while `f` runs is undone with it.
    (let [before @state]
      (try
        (f)
        (catch Throwable t
          (reset! state before)
          (throw t))))))

(defn create-repository []
  (->MemoryProductRepository (atom {})))

;; Derived, so the service's (ig/ref :wagoe/product-repository) finds this one
;; when it stands in for the SQL repository.
(derive :shop/product-memory-repository :wagoe/product-repository)

(defmethod ig/init-key :shop/product-memory-repository [_ _]
  (create-repository))
