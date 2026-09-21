(ns shop.product.shell.web-handlers
  "Web UI handlers for product module."
  (:require [shop.product.core.ui :as ui]
            [shop.product.ports :as ports]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]))

(defn product-list-handler [service _config]
  (fn [request]
    (let [items (ports/list-products service {})]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (i18n/render (ui/product-list-page items {})
                          (i18n-middleware/resolve-t-fn request))})))
