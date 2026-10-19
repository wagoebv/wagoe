(ns wagoe.audience.shell.module-wiring
  "Integrant lifecycle management for the audience module.

   Config keys:

   :wagoe/audience-db-schema
     {:db-ctx (ig/ref :wagoe/db-context)}   ; creates the module's tables

   :wagoe/audience-user-source
     {:db-ctx (ig/ref :wagoe/db-context)
      :table  :users}                 ; the users table this app segments on

   :wagoe/audience
     {:db-ctx           (ig/ref :wagoe/db-context)
      :cache-service    (ig/ref :wagoe/cache)
      :user-data-source (ig/ref :wagoe/audience-user-source)
      :filter-fields    #{:plan}}         ; columns a filter may name, beyond the defaults

     Returns {:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>
              :filter-fields <the effective allowlist>}

   :wagoe/audience-routes
     {:audience-service (ig/ref :wagoe/audience)
      :middleware       (ig/ref :wagoe/admin-only-middleware)}

     Returns {:api [...] :web [...]} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.shell.adapters.user-sql :as user-sql]
            [wagoe.audience.shell.persistence :as persistence]
            [wagoe.audience.shell.cache :as cache]
            [wagoe.audience.shell.service :as service]
            [wagoe.audience.shell.http :as audience-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/audience-db-schema
  [_ {:keys [db-ctx]}]
  (persistence/initialize-audience-schema! (:datasource db-ctx))
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/audience-db-schema
  [_ _]
  nil)

(defmethod ig/init-key :wagoe/audience-user-source
  [_ {:keys [db-ctx table field-mapping]}]
  (log/info "Initializing audience user data source" {:table (or table :users)})
  (user-sql/create-sql-user-data-source (:datasource db-ctx) table field-mapping))

(defmethod ig/halt-key! :wagoe/audience-user-source
  [_ _source]
  nil)

(defn- checked-filter-fields
  "The allowlist for `configured`, refusing a malformed or credential-like entry."
  [configured]
  (when-not (or (nil? configured) (and (coll? configured) (every? keyword? configured)))
    (throw (ex-info ":wagoe/audience :filter-fields must be a set of keywords"
                    {:type :configuration-error :key :filter-fields :value configured})))
  (when-let [secret (seq (filter filter/secret-field? configured))]
    (throw (ex-info (str ":wagoe/audience :filter-fields may not name credentials: " (vec secret))
                    {:type :configuration-error :key :filter-fields :fields (vec secret)})))
  (filter/allowed-filter-fields configured))

(defmethod ig/init-key :wagoe/audience
  [_ {:keys [db-ctx cache-service user-data-source db-schema filter-fields]}]
  (log/info "Initializing audience component")
  (when-not db-schema
    (log/warn "Audience component started without :db-schema — its tables may not exist"))
  (let [allowed    (checked-filter-fields filter-fields)
        datasource (:datasource db-ctx)
        store      (persistence/create-audience-store datasource)
        acache     (cache/create-audience-cache datasource cache-service)
        resolver   (service/create-audience-service
                    {:repository       store
                     :cache            acache
                     :user-data-source user-data-source
                     :filter-fields    allowed})]
    (when-not user-data-source
      (throw (ex-info "Audience component requires :user-data-source. Wire an IUserDataSource implementation via Integrant config."
                      {:type :configuration-error :missing-key :user-data-source})))
    {:store         store
     :resolver      resolver
     :cache         acache
     :filter-fields allowed}))

(defmethod ig/halt-key! :wagoe/audience
  [_ _component]
  (log/info "Halting audience component")
  nil)

;; =============================================================================
;; Audience Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/audience-routes
  [_ {:keys [audience-service middleware]}]
  ;; These endpoints create and delete segments and read their membership. They
  ;; carried no route middleware, and the global authentication only *sets*
  ;; `:user` when credentials are present — so an anonymous caller could list a
  ;; segment's user ids and delete the segment, and did (BOU-419 review).
  (when (empty? middleware)
    ;; Refuses rather than serving them open, in both cases below — but the
    ;; second is reached by a route nobody expects, so it says what to do.
    ;;
    ;;   nil  — the ref was pruned. `service audience` drops every user-owned
    ;;          key, and the guard is one, so the vector never arrives.
    ;;   []   — the user module contributed nothing, i.e. no user service.
    (throw (ex-info (if (nil? middleware)
                      (str "Audience routes need :wagoe/admin-only-middleware, which service "
                           "selection dropped. Run `service audience user` — these endpoints "
                           "manage segments and expose member ids, so they are not served "
                           "without something to authorize against.")
                      (str "Audience routes must not be mounted without authorization "
                           "middleware: they manage segments and expose member ids."))
                    {:type :configuration-error :missing-key :middleware})))
  (log/info "Initializing audience routes" {:guards (count middleware)})
  {:api (audience-http/audience-api-routes (:resolver audience-service)
                                           (:store audience-service)
                                           middleware
                                           (select-keys audience-service [:filter-fields]))
   :web (audience-http/audience-web-routes (:resolver audience-service)
                                           (:store audience-service)
                                           middleware
                                           (select-keys audience-service [:filter-fields]))})

(defmethod ig/halt-key! :wagoe/audience-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   There was no `ig-config` here at all, so the platform fell back to emitting
   `:wagoe/audience` as a bare settings map: the service was never built and the
   routes were never mounted. Under that fallback the module could not have
   booted anyway — its `:user-data-source` had no implementation (BOU-419).

   Two refs are conditional, for the same reason in both cases — a ref to a
   component the config does not contain is a dangling ref that fails the boot:

     `:cache-service`, only when `:wagoe/cache` is on;
     `:wagoe/audience-routes`, which is mounted only when the user module is on,
     because the authorization guard these endpoints need lives there. Without
     it they are not mounted at all rather than mounted open (BOU-419 review)."
  [settings {:keys [enabled]}]
  (let [enabled  (or enabled #{})
        settings (or settings {})]
    (cond-> {:components
             {:wagoe/audience-db-schema   {:db-ctx (ig/ref :wagoe/db-context)}
              :wagoe/audience-user-source {:db-ctx        (ig/ref :wagoe/db-context)
                                           :table         (:users-table settings)
                                           :field-mapping (:users-field-mapping settings)}
              :wagoe/audience             (cond-> {:db-ctx           (ig/ref :wagoe/db-context)
                                                   :db-schema        (ig/ref :wagoe/audience-db-schema)
                                                   :user-data-source (ig/ref :wagoe/audience-user-source)
                                                   :filter-fields    (:filter-fields settings)}
                                            (contains? enabled :wagoe/cache)
                                            (assoc :cache-service (ig/ref :wagoe/cache)))}}

      (contains? enabled :wagoe/user)
      (-> (assoc-in [:components :wagoe/audience-routes]
                    {:audience-service (ig/ref :wagoe/audience)
                     :middleware       (ig/ref :wagoe/admin-only-middleware)})
          (assoc :routes [(ig/ref :wagoe/audience-routes)])))))
