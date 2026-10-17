(ns shop.product.core.ui
  "Pure UI generation for product module - Hiccup templates."
  (:require [shop.product.schema :as schema]
            [wagoe.shared.ui.core.form :as form]
            [wagoe.shared.ui.core.layout :as layout]))

(defn product-list-page
  "Generate product listing page."
  [products _opts]
  [:div.page
   [:h1 "Products"]
   [:p [:a {:href "/web/products/new"} "New product"]]
   [:table.items
    [:thead [:tr [:th "Name"] [:th "Sku"] [:th "Price"]]]
    [:tbody
     (for [item products]
       [:tr
        [:td (str (:name item))]
        [:td (str (:sku item))]
        [:td (str (:price item))]])]]])

(defn product-field
  "One field of the product form, with its errors. A change to the field
   posts the form to /validate, which answers with this again."
  [field values errors]
  (form/field schema/CreateProductRequest field
              {:value        (form/value values field)
               ;; English: the page is not rendered through wagoe-i18n. With it,
               ;; pass the messages as they are and render with its renderer.
               :errors       (mapv form/plain (get errors field))
               :validate-url "/web/products/validate"}))

(defn product-new-page
  "The create form: `values` as submitted, `errors` by field. A plain form
   post, so it works without JavaScript."
  [values errors opts]
  (layout/page-layout
   "New product"
   [:div.page
    [:h1 "New product"]
    (form/form {:method "post" :action "/web/products"}
               (for [field (form/field-keys schema/CreateProductRequest)]
                 (product-field field values errors))
               [:button {:type "submit"} "Create"])]
   opts))
