(ns wagoe.product.shell.web-handlers
  "Web UI handlers for product module."
  (:require [wagoe.product.core.ui :as ui]
            [wagoe.product.ports :as ports]))

(defn product-list-handler [service _config]
  (fn [_request]
    (let [items (ports/list-products service {})]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (ui/product-list-page items {})})))
