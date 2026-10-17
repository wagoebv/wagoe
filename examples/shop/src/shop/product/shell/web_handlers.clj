(ns shop.product.shell.web-handlers
  "Web UI handlers for product module."
  (:require [shop.product.core.ui :as ui]
            [shop.product.ports :as ports]
            [shop.product.schema :as schema]
            [wagoe.shared.ui.core.components :as components]
            [wagoe.shared.ui.core.form :as form]))

(defn- html [status page]
  {:status  status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (components/render-html page)})

(defn product-list-handler [service _config]
  (fn [_request]
    (let [items (ports/list-products service {})]
      (html 200 (ui/product-list-page items {})))))

(defn product-new-handler [_service _config]
  (fn [request]
    (html 200 (ui/product-new-page {} {} {:user (:user request)}))))

(defn product-create-handler
  "The form, posted. A rejected form comes back with its errors and a 422."
  [service _config]
  (fn [request]
    (let [params (:form-params request)
          {:keys [data errors]} (form/validate schema/CreateProductRequest params)]
      (if errors
        (html 422 (ui/product-new-page params errors {:user (:user request)}))
        (do (ports/create-product service data)
            {:status 303 :headers {"Location" "/web/products"}})))))

(defn product-validate-handler
  "The field named `__field`, checked as the whole form would be, for htmx
   to swap over the one on the page. Nothing is written."
  [_service _config]
  (fn [request]
    (let [params (:form-params request)]
      (if-let [field (form/field-key schema/CreateProductRequest (get params "__field"))]
        (let [{:keys [errors]} (form/validate schema/CreateProductRequest params)]
          (html 200 (ui/product-field field params errors)))
        {:status 400 :headers {"Content-Type" "text/plain"} :body "No such field"}))))
