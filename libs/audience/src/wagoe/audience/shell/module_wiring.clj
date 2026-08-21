(ns wagoe.audience.shell.module-wiring
  "Integrant lifecycle management for the audience module.

   Config keys:

   :wagoe/audience
     {:db-ctx          (ig/ref :wagoe/db-context)
      :cache-service   (ig/ref :wagoe/cache)
      :user-data-source (ig/ref :wagoe/user-data-source)}

     Returns {:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}

   :wagoe/audience-routes
     {:audience-service (ig/ref :wagoe/audience)}

     Returns {:api [...] :web [...]} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.audience.shell.persistence :as persistence]
            [wagoe.audience.shell.cache :as cache]
            [wagoe.audience.shell.service :as service]
            [wagoe.audience.shell.http :as audience-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/audience
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
                      {:type :configuration-error :missing-key :user-data-source})))
    {:store    store
     :resolver resolver
     :cache    acache}))

(defmethod ig/halt-key! :wagoe/audience
  [_ _component]
  (log/info "Halting audience component")
  nil)

;; =============================================================================
;; Audience Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/audience-routes
  [_ {:keys [audience-service]}]
  (log/info "Initializing audience routes")
  {:api (audience-http/audience-api-routes (:resolver audience-service) (:store audience-service))
   :web (audience-http/audience-web-routes (:resolver audience-service) (:store audience-service))})

(defmethod ig/halt-key! :wagoe/audience-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)
