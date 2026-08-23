(ns shop.product.core.ui
  "Pure UI generation for product module - Hiccup templates.")

(defn product-list-page
  "Generate product listing page."
  [products _opts]
  [:div.page
   [:h1 "Products"]
   [:div.items
    (for [item products]
      [:div.item {:key (:id item)}
       [:p (str (:id item))]])]])
