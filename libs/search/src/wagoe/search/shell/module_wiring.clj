(ns wagoe.search.shell.module-wiring
  "Integrant lifecycle management for the search module.

   Config keys:

   :wagoe/search
     {:db-ctx (ig/ref :wagoe/db-context)}

     Returns {:store <ISearchStore> :engine <ISearchEngine>}

   :wagoe/search-routes
     {:search-service (ig/ref :wagoe/search)}

     Returns {:api [...] :web [...] :static []} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.platform.ports.database :as db-protocols]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.shell.service :as service]
            [wagoe.search.shell.http :as search-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/search
  [_ {:keys [db-ctx]}]
  (log/info "Initializing search component")
  (let [datasource (:datasource db-ctx)
        adapter    (:adapter db-ctx)
        ;; PostgreSQL adapter's dialect returns nil — treat nil as :postgresql
        db-type    (or (some-> adapter db-protocols/dialect) :postgresql)
        store      (persistence/create-search-store datasource db-type)
        engine     (service/create-search-service store)]
    (log/info "Search component initialized" {:db-type db-type})
    {:store  store
     :engine engine}))

(defmethod ig/halt-key! :wagoe/search
  [_ _component]
  (log/info "Halting search component")
  nil)

;; =============================================================================
;; Search Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/search-routes
  [_ {:keys [search-service]}]
  (log/info "Initializing search routes")
  {:api    (search-http/search-routes (:engine search-service))
   :web    (search-http/search-web-routes (:engine search-service))
   :static []
   ;; Mounted alongside the admin UI rather than at /web, because that is where
   ;; a reader looks for it. Platform used to hold this fact (BOU-330).
   :web-prefix "/web/admin"})

(defmethod ig/halt-key! :wagoe/search-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`."
  [_settings _ctx]
  {:components
   {:wagoe/search        {:db-ctx (ig/ref :wagoe/db-context)}
    :wagoe/search-routes {:search-service (ig/ref :wagoe/search)}}
   :routes [(ig/ref :wagoe/search-routes)]})
