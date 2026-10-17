(ns acme.guards.shell.module-wiring
  "An application module that adds HTTP middleware and one component, and
   leaves the four scaffold keys to discovery."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/guards            [_ config] config)
(defmethod ig/init-key :wagoe/guards-repository [_ config] config)
(defmethod ig/init-key :wagoe/guards-service    [_ config] config)
(defmethod ig/init-key :wagoe/guards-routes     [_ _] {:api [] :web [] :static []})

(defmethod ig/init-key :wagoe/guards-http-middleware
  [_ {:keys [header]}]
  [(fn [handler]
     (fn [request]
       (assoc-in (handler request) [:headers "X-Guard"] header)))])

(defn ig-config [_settings _ctx]
  {:components {:wagoe/guards-http-middleware {:header "on"}}
   :http       {:extra-middleware (ig/ref :wagoe/guards-http-middleware)}})
