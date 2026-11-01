(ns stores.system-config
  "examples/shop's system, with the product module on the store that
   `:store` under `:wagoe/product` names: `:sql`, the default, or `:memory`."
  (:require [shop.product.shell.memory]
            [shop.system-config :as app]))

(defn ig-config [config]
  (let [system (app/ig-config config)
        store  (get-in config [:active :wagoe/product :store] :sql)]
    (case store
      :sql    system
      :memory (-> system
                  (dissoc :wagoe/product-repository)
                  (assoc :shop/product-memory-repository {}))
      (throw (ex-info "Unknown :store under :wagoe/product; use :sql or :memory"
                      {:type :configuration-error :store store})))))
