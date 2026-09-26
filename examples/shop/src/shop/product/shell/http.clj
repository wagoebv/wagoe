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

;; Thrown, not returned: the platform maps :type to the status and answers
;; in the shape it uses for every error, a missing reference included.
(defn- invalid []
  (throw (ex-info "Invalid product" {:type :validation-error})))

(defn- not-found []
  (throw (ex-info "No such product" {:type :not-found})))

(defn- id-of [request]
  (some-> (get-in request [:path-params :id]) parse-uuid))

(def ^:private max-page 100)

(defn- page-of
  "limit and offset from the query string. A missing or malformed value is
   the default, and limit is capped so one request cannot read the table."
  [request]
  (let [n (fn [k default]
            (let [v (get-in request [:query-params k])]
              (or (when (string? v) (parse-long v)) default)))]
    {:limit  (-> (n "limit" 20) (max 1) (min max-page))
     :offset (max 0 (n "offset" 0))}))

;; Every route requires a signed-in user and answers 401 without one.
;; Generate with --public-api for routes open to anyone.
(def ^:private signed-in ['wagoe.user.shell.http-interceptors/require-authenticated])

(defn api-routes
  "Reitit route data. Paths are relative — the platform mounts them under /api/v1."
  [service]
  [["/products"
    {:get  {:summary "List products, oldest first"
            :interceptors signed-in
            :swagger {:parameters [{:name "limit" :in "query" :required false :type "integer"
                                    :description "Default 20, at most 100"}
                                   {:name "offset" :in "query" :required false :type "integer"}]}
            :handler (fn [request]
                       {:status 200 :body (ports/list-products service (page-of request))})}
     :post {:summary "Create a product"
            :interceptors signed-in
            :handler (fn [request]
                       (let [data (decode-create (:body-params request))]
                         (if (valid-create? data)
                           {:status 201 :body (ports/create-product service data)}
                           (invalid))))}}]
   ["/products/:id"
    {:swagger {:parameters [{:name "id" :in "path" :required true :type "string"}]}
     :get    {:summary "Get a product"
              :interceptors signed-in
              :handler (fn [request]
                         (if-let [found (some->> (id-of request) (ports/get-product service))]
                           {:status 200 :body found}
                           (not-found)))}
     :put    {:summary "Update a product"
              :interceptors signed-in
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
              :interceptors signed-in
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

