(ns wagoe.audience.shell.module-wiring
  "Integrant lifecycle management for the audience module.

   Config keys:

   :boundary/audience
     {:db-ctx          (ig/ref :boundary/db-context)
      :cache-service   (ig/ref :boundary/cache)
      :user-data-source (ig/ref :boundary/user-data-source)}

     Returns {:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}

   :boundary/audience-routes
     {:audience-service (ig/ref :boundary/audience)}

     Returns {:api [...] :web [...]} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.audience.shell.persistence :as persistence]
            [wagoe.audience.shell.cache :as cache]
            [wagoe.audience.shell.service :as service]
            [wagoe.audience.shell.http :as audience-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :boundary/audience
  [_ {:keys [db-ctx cache-service user-data-source]}]
  (log/info "Initializing audience component")
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-audience-store datasource)
        acache     (cache/create-audience-cache datasource cache-service)
        resolver   (service/create-audience-service
                    {:repository       store
                     :cache            acache
                     :user-data-source user-data-source})]
    (when-not user-data-source
      (throw (ex-info "Audience component requires :user-data-source. Wire an IUserDataSource implementation via Integrant config."
                      {:missing-key :user-data-source})))
    {:store    store
     :resolver resolver
     :cache    acache}))

(defmethod ig/halt-key! :boundary/audience
  [_ _component]
  (log/info "Halting audience component")
  nil)

;; =============================================================================
;; Audience Routes Component
;; =============================================================================

(defmethod ig/init-key :boundary/audience-routes
  [_ {:keys [audience-service]}]
  (log/info "Initializing audience routes")
  {:api (audience-http/audience-api-routes (:resolver audience-service) (:store audience-service))
   :web (audience-http/audience-web-routes (:resolver audience-service) (:store audience-service))})

(defmethod ig/halt-key! :boundary/audience-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)
