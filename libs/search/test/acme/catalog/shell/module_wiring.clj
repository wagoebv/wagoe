(ns acme.catalog.shell.module-wiring
  "An application module handing search an index, as plain data and as a
   component, for wagoe.search.shell.module-contribution-test."
  (:require [integrant.core :as ig]
            [wagoe.search.core.index :refer [defsearch]]))

(defsearch products
  {:entity-type :product
   :fields      [{:name :title :weight :a}]
   :api?        true})

(defmethod ig/init-key :wagoe/catalog [_ v] v)

(defmethod ig/init-key :wagoe/catalog-orders-index [_ {:keys [role]}]
  {:entity-type :order
   :fields      [{:name :number :weight :a}]
   :api         {:authorize (fn [request _] (= role (get-in request [:user :role])))}})

(defn ig-config [_settings _ctx]
  {:components {:wagoe/catalog              {}
                :wagoe/catalog-orders-index {:role :admin}}
   :search     {:products products
                :orders   (ig/ref :wagoe/catalog-orders-index)}})
