(ns wagoe.product.core.product
  "Pure business logic for product domain.
   
   All functions in this namespace are pure - they have no side effects,
   don't perform I/O, and always return the same output for the same input."
  (:require [wagoe.product.schema :as schema]))

;; =============================================================================
;; Entity Creation
;; =============================================================================

(defn prepare-new-product
  "Prepare data for creating a new product.
   
   Args:
     data - Input data map
     entity-id - UUID supplied by the shell
     current-time - java.time.Instant for timestamps
   
   Returns:
     Prepared product entity map
   
   Pure: true"
  [data entity-id current-time]
  (merge data
         {:id entity-id
          :created-at current-time
          :updated-at current-time}))

;; =============================================================================
;; Entity Updates
;; =============================================================================

(defn apply-product-update
  "Apply updates to existing product entity.
   
   Args:
     existing - Current product entity
     updates - Map of fields to update
     current-time - java.time.Instant for updated-at
   
   Returns:
     Updated product entity map
   
   Pure: true"
  [existing updates current-time]
  (merge existing
         updates
         {:updated-at current-time}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-product
  "Validate product entity data.
   
   Args:
     data - product data to validate
   
   Returns:
     Vector of [valid? errors data]
   
   Pure: true"
  [data]
  (if (schema/validate-product data)
    [true nil data]
    [false (schema/explain-product data) nil]))
