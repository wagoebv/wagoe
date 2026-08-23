(ns shop.product.schema
  "Schema definitions for product module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def Product
  "Schema for Product entity."
  [:map {:title "Product"}
   [:id :uuid]
   [:name :string]
   [:sku :string]
   [:price {:optional true} :double]
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]
   [:deleted-at {:optional true} [:maybe inst?]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateProductRequest
  "Schema for create product API requests."
  [:map {:title "Create Product Request"}
   [:name :string]
   [:sku :string]
   [:price {:optional true} :double]])

(def UpdateProductRequest
  "Schema for update product API requests."
  [:map {:title "Update Product Request"}
   [:name {:optional true} :string]
   [:sku {:optional true} :string]
   [:price {:optional true} :double]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private product-validator (m/validator Product))
(def ^:private product-explainer (m/explainer Product))

(defn validate-product
  "Validates a product entity against the Product schema."
  [product-data]
  (product-validator product-data))

(defn explain-product
  "Provides detailed validation errors for product data."
  [product-data]
  (product-explainer product-data))
