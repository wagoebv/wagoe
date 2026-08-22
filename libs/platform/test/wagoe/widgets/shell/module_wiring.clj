(ns wagoe.widgets.shell.module-wiring
  "A library that contributes HTTP routes without platform knowing it exists.

   The claim BOU-330 makes is that a 31st library can serve HTTP without a line
   changing in platform. This namespace is that claim: nothing under
   `wagoe.platform` mentions widgets, and `system-config` builds and mounts it
   because its config key and this namespace exist.

   Under `wagoe.` because module discovery derives the wiring namespace from one
   `base-ns` — `wagoe` unless an application says otherwise. A genuinely
   third-party library under its own root would need that made per-module, which
   is a separate question from the route coupling this proves gone."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/widgets-routes
  [_ _]
  {:api [{:path "/widgets" :methods {:get {:handler (fn [_] {:status 200 :body []})}}}]
   :web [{:path "/widgets" :methods {:get {:handler (fn [_] {:status 200 :body "hi"})}}}]
   ;; Its own mount point, declared here rather than in platform.
   :web-prefix "/web/shop"})

(defmethod ig/halt-key! :wagoe/widgets-routes [_ _] nil)

(defmethod ig/init-key :wagoe/widgets [_ config] config)
(defmethod ig/halt-key! :wagoe/widgets [_ _] nil)

(defn ig-config
  "This module's graph — the same contract every framework module uses."
  [settings _ctx]
  {:components {:wagoe/widgets        settings
                :wagoe/widgets-routes {}}
   :routes     [(ig/ref :wagoe/widgets-routes)]})
