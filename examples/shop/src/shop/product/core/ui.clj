(ns shop.product.core.ui
  "Pure UI generation for product module - Hiccup templates.")

(defn product-list-page
  "Generate product listing page."
  [products _opts]
  [:div.page
   [:h1 "Products"]
   [:table.items
    [:thead [:tr [:th "Name"] [:th "Sku"] [:th "Price"]]]
    [:tbody
     (for [item products]
       [:tr
        [:td (str (:name item))]
        [:td (str (:sku item))]
        [:td (str (:price item))]])]]])
