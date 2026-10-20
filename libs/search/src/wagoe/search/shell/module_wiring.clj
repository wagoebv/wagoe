(ns wagoe.search.shell.module-wiring
  "Integrant lifecycle management for the search module.

   Config keys:

   :wagoe/search
     {:db-ctx      (ig/ref :wagoe/db-context)
      :indexes     {index-id definition}   ; from config.edn
      :contributed {index-id definition}}  ; modules' :search parts

     Returns {:store <ISearchStore> :engine <ISearchEngine>}

   :wagoe/search-routes
     {:search-service (ig/ref :wagoe/search)}

     Returns {:api [...] :web [...] :static []} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.search.core.index :as index]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.shell.service :as service]
            [wagoe.search.shell.http :as search-http]
            [clojure.tools.logging :as log]))

(defn- definitions!
  [indexes contributed]
  (let [{:keys [definitions error]} (index/collect-definitions indexes contributed)]
    (when error
      (throw (ex-info (:message error) error)))
    definitions))

(defmethod ig/init-key :wagoe/search
  [_ {:keys [db-ctx indexes contributed]}]
  (log/info "Initializing search component")
  (let [store  (persistence/create-search-store (:datasource db-ctx) (:adapter db-ctx))
        engine (service/create-search-service store (definitions! indexes contributed))]
    (log/info "Search component initialized" (select-keys store [:full-text? :on-conflict?]))
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
   :web-prefix "/web/admin"
   :nav        {:search {:label [:t :search/nav-title]
                         :icon  :search
                         :items [{:href "/web/admin/search" :icon :database :access :admin
                                  :label [:t :search/page-indices-heading]}]}}})

(defmethod ig/halt-key! :wagoe/search-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`."
  [settings _ctx]
  {:components
   {:wagoe/search        (cond-> {:db-ctx (ig/ref :wagoe/db-context)}
                           (:indexes settings) (assoc :indexes (:indexes settings)))
    :wagoe/search-routes {:search-service (ig/ref :wagoe/search)}}
   :routes [(ig/ref :wagoe/search-routes)]})
