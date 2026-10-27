(ns wagoe.gadgets.shell.module-wiring
  "A discovered module with a graph of its own, job handlers, and no HTTP routes."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/gadgets [_ config] config)
(defmethod ig/halt-key! :wagoe/gadgets [_ _] nil)

(defmethod ig/init-key :wagoe/gadgets-job-handlers [_ _] {:gadgets/count (fn [_] {:success? true})})

(defn ig-config [settings _ctx]
  {:components   {:wagoe/gadgets              settings
                  :wagoe/gadgets-job-handlers {}}
   :job-handlers [(ig/ref :wagoe/gadgets-job-handlers)]
   :recurring    {:gadgets/count {:cron "0 * * * *"}}})
