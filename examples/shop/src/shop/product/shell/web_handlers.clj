(ns shop.product.shell.web-handlers
  "Web UI handlers for product module."
  (:require [shop.product.core.ui :as ui]
            [shop.product.ports :as ports]
            [hiccup2.core :as h]))

(defn product-list-handler [service _config]
  (fn [_request]
    (let [items (ports/list-products service {})]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (str (h/html (ui/product-list-page items {})))})))
