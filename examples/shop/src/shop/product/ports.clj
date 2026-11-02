(ns shop.product.ports
  "Product module port definitions (abstract interfaces).")

;; =============================================================================
;; Repository Ports
;; =============================================================================

(defprotocol IProductRepository
  "Repository interface for product persistence operations."

  (find-by-id [this id]
    "The product with this UUID, or nil.")

  (find-all [this options]
    "A page of products, oldest first: by :created-at, then by id. `options`
     holds :limit (default 20) and :offset (default 0).")

  (create [this entity]
    "Store a new product, its :id and :created-at already set, and return it
     as stored. An id or unique field already taken is a :conflict.")

  ;; `update-entity`, not `update-<entity>`: the service protocol below declares
  ;; `update-<entity>` too, and defprotocol interns its methods as vars in this
  ;; namespace — so the second silently overwrote the first, leaving
  ;; ports/update-<entity> with the service arity [this id data]. Nor `update`,
  ;; which would shadow clojure.core/update. The repository's other methods are
  ;; already generic (create, delete), so this matches its own family. (BOU-267)
  (update-entity [this entity]
    "Change the fields `entity` carries besides :id, stamp :updated-at, and
     return the product as stored; nil when there is no such product. No field to
     change is a :validation-error.")

  (delete [this id]
    "Delete the product with this id. An unknown id is not an error.")

  (transact [this f]
    "Call `f` as one unit of work, and return what it returns. When `f`
     throws, none of its writes remain."))

;; =============================================================================
;; Service Ports
;; =============================================================================

(defprotocol IProductService
  "Product service interface for business operations."

  (get-product [this id]
    "Get product by ID.")

  (list-products [this options]
    "List products with pagination.")

  (create-product [this data]
    "Create new product.")

  (update-product [this id data]
    "Update product.")

  (delete-product [this id]
    "Delete product."))
