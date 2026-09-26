(ns shop.product.shell.http
  "HTTP routes for product module."
  (:require [shop.product.ports :as ports]
            [shop.product.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]
            [shop.product.shell.web-handlers :as web-handlers]))

;; JSON has no decimal type: Muuntaja reads 9.99 as a Double, and malli
;; has no decoder for decimal?, so without this a decimal field refused both
;; 9.99 and "9.99".
(defn- ->decimal [x]
  (cond (number? x) (bigdec x)
        (string? x) (try (bigdec x) (catch NumberFormatException _ x))
        :else x))

(def ^:private json->data
  (mt/transformer mt/strip-extra-keys-transformer mt/json-transformer
                  {:decoders {'decimal? ->decimal}}))

(def ^:private decode-create (m/decoder schema/CreateProductRequest json->data))
(def ^:private valid-create? (m/validator schema/CreateProductRequest))
(def ^:private decode-update (m/decoder schema/UpdateProductRequest json->data))
(def ^:private valid-update? (m/validator schema/UpdateProductRequest))

(defn- invalid []
  {:status 400 :body {:error {:type :validation-error :message "Invalid product"}}})

(defn- not-found []
  {:status 404 :body {:error {:type :not-found :message "No such product"}}})

(defn- id-of [request]
  (some-> (get-in request [:path-params :id]) parse-uuid))

(defn api-routes
  "Reitit route data. Paths are relative — the platform mounts them under /api/v1."
  [service]
  [["/products"
    {:get  {:summary "List products"
            :handler (fn [_request]
                       {:status 200 :body (ports/list-products service {})})}
     :post {:summary "Create a product"
            :handler (fn [request]
                       (let [data (decode-create (:body-params request))]
                         (if (valid-create? data)
                           {:status 201 :body (ports/create-product service data)}
                           (invalid))))}}]
   ["/products/:id"
    {:swagger {:parameters [{:name "id" :in "path" :required true :type "string"}]}
     :get    {:summary "Get a product"
              :handler (fn [request]
                         (if-let [found (some->> (id-of request) (ports/get-product service))]
                           {:status 200 :body found}
                           (not-found)))}
     :put    {:summary "Update a product"
              :handler (fn [request]
                         (let [id   (id-of request)
                               data (decode-update (:body-params request))]
                           (cond
                             (nil? id)                 (not-found)
                             (empty? data)             (invalid)
                             (not (valid-update? data)) (invalid)
                             :else (if-let [updated (ports/update-product service id data)]
                                     {:status 200 :body updated}
                                     (not-found)))))}
     :delete {:summary "Delete a product"
              :handler (fn [request]
                         (if-let [id (id-of request)]
                           (do (ports/delete-product service id) {:status 204})
                           (not-found)))}}]])

(defn web-routes
  "Mounted under /web — do not repeat the prefix here."
  [service config]
  [["/products"
    {:get {:handler (web-handlers/product-list-handler service config)}}]])

(defn product-routes
  "This module's contribution to the application's route table.

   :api    versioned, mounted under /api/v1
   :web    mounted under /web
   :static mounted as written"
  [service config]
  {:api    (api-routes service)
   :web    (web-routes service config)
   :static []})

