(ns wagoe.product.shell.service
  "Service layer for product module."
  (:require [wagoe.product.ports :as ports]
            [wagoe.product.core.product :as core])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- current-time []
  (Instant/now))

(defn- generate-product-id []
  (UUID/randomUUID))

(defrecord ProductService [repository]
  ports/IProductService
  (create-product [_this data]
    (let [prepared (core/prepare-new-product data (generate-product-id) (current-time))]
      (.create repository prepared)))
  (get-product [_this id]
    (.find-by-id repository id))
  (list-products [_this opts]
    (.find-all repository opts))
  (update-product [_this id data]
    (.update-entity repository (assoc data :id id)))
  (delete-product [_this id]
    (.delete repository id)))

(defn create-service [repository]
  (->ProductService repository))
