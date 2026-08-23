(ns shop.product.ports
  "Product module port definitions (abstract interfaces).")

;; =============================================================================
;; Repository Ports
;; =============================================================================

(defprotocol IProductRepository
  "Repository interface for product persistence operations."

  (find-by-id [this id]
    "Find product by ID.")

  (find-all [this options]
    "Find all products with pagination and filtering.")

  (create [this entity]
    "Create new product.")

  ;; `update-entity`, not `update-<entity>`: the service protocol below declares
  ;; `update-<entity>` too, and defprotocol interns its methods as vars in this
  ;; namespace — so the second silently overwrote the first, leaving
  ;; ports/update-<entity> with the service arity [this id data]. Nor `update`,
  ;; which would shadow clojure.core/update. The repository's other methods are
  ;; already generic (create, delete), so this matches its own family. (BOU-267)
  (update-entity [this entity]
    "Update existing product.")

  (delete [this id]
    "Delete product by ID."))

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
