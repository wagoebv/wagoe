(ns wagoe.product.shell.http
  "HTTP routes for product module.")

;; The handlers below are stubs that return canned responses. When you
;; wire them to the service, add to the ns form above:
;;   (:require [wagoe.product.ports :as ports])

(defn api-routes
  "Reitit route data: [path data & children].

   Paths are relative — the platform mounts these under /api/v1."
  [_service]
  [["/products"
    {:get  {:handler (fn [_req] {:status 200 :body []})}
     :post {:handler (fn [_req] {:status 201 :body {}})}}]
   ["/products/:id"
    {:get    {:handler (fn [_req] {:status 200 :body {}})}
     :put    {:handler (fn [_req] {:status 200 :body {}})}
     :delete {:handler (fn [_req] {:status 204})}}]])

(defn web-routes
  "Mounted under /web — do not repeat the prefix here."
  [_service _config]
  [["/products"
    {:get {:handler (fn [_req] {:status 200 :body "<html><body>Web UI</body></html>"})}}]])

(defn product-routes
  "This module's contribution to the application's route table.

   :api    versioned, mounted under /api/v1
   :web    mounted under /web
   :static mounted as written"
  [service config]
  {:api    (api-routes service)
   :web    (web-routes service config)
   :static []})

