(ns acme.archive.shell.module-wiring
  "An application module that contributes a data exporter and a retention
   policy, beside the four scaffold keys."
  (:require [integrant.core :as ig]
            [wagoe.platform.ports.privacy :as privacy]))

(defmethod ig/init-key :wagoe/archive            [_ config] config)
(defmethod ig/init-key :wagoe/archive-repository [_ config] config)
(defmethod ig/init-key :wagoe/archive-service    [_ config] config)
(defmethod ig/init-key :wagoe/archive-routes     [_ _] {:api [] :web [] :static []})

(defmethod ig/init-key :wagoe/archive-exporter [_ _]
  (reify privacy/IDataExporter
    (export-data [_ {:keys [id]}] {:archived-for id})))

(defmethod ig/init-key :wagoe/archive-retention [_ _]
  (reify privacy/IRetentionPolicy
    (purge-before! [_ _cutoff] 1)))

(defn ig-config [_settings _ctx]
  {:components  {:wagoe/archive-exporter  {}
                 :wagoe/archive-retention {}}
   :data-export {:archive (ig/ref :wagoe/archive-exporter)}
   :retention   {:archive/old (ig/ref :wagoe/archive-retention)}})
