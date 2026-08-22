(ns wagoe.gadgets.shell.module-wiring
  "A discovered module with a graph of its own and no HTTP routes."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/gadgets [_ config] config)
(defmethod ig/halt-key! :wagoe/gadgets [_ _] nil)

(defn ig-config [settings _ctx]
  {:components {:wagoe/gadgets settings}})
