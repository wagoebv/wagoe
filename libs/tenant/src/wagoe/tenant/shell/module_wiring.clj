(ns wagoe.tenant.shell.module-wiring
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.tenant.shell.invite-persistence :as invite-persistence]
            [wagoe.tenant.shell.invite-service :as invite-service]
            [wagoe.tenant.shell.membership-middleware :as membership-mw]
            [wagoe.tenant.shell.membership-persistence :as membership-persistence]
            [wagoe.tenant.shell.membership-service :as membership-service]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.tenant-middleware :as tenant-mw]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.service :as tenant-service]
            [wagoe.tenant.shell.tenant-migrations :as tenant-migrations]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Tenant Database Schema
;; =============================================================================

(defn- fan-out-tenant-migrations!
  "Apply pending tenant-scoped migrations to every existing tenant schema on
   startup, so a deploy that ships a new tenant migration reaches all tenants —
   not just newly provisioned ones. No-op on non-PostgreSQL (list-tenant-schemas
   returns []). Never blocks startup: per-tenant failures are collected/logged."
  [ctx]
  (try
    (let [schemas (provisioning/list-tenant-schemas ctx)]
      (when (seq schemas)
        (let [{:keys [errors]} (tenant-migrations/migrate-all-tenants!
                                (db-config/get-active-db-config) schemas)]
          (when (seq errors)
            (log/error "Some tenant schemas failed to migrate on startup" {:errors errors})))))
    (catch Exception e
      ;; Never block application startup on tenant migration fan-out; surface
      ;; the failure so it can be alerted on and re-run.
      (log/error e "Tenant migration fan-out failed on startup"))))

(defmethod ig/init-key :wagoe/tenant-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing tenant module database schema")
  (tenant-persistence/initialize-tenant-schema! ctx)
  (fan-out-tenant-migrations! ctx)
  (log/info "Tenant module database schema initialized")
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/tenant-db-schema
  [_ _state]
  (log/info "Tenant module database schema component halted"))

;; =============================================================================
;; Tenant Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/tenant-repository
  [_ {:keys [ctx logger error-reporter]}]
  (log/info "Initializing tenant repository")
  (let [repo (tenant-persistence/create-tenant-repository ctx logger error-reporter)]
    (log/info "Tenant repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/tenant-repository
  [_ _repo]
  (log/info "Tenant repository halted (no cleanup needed)"))

(defmethod ig/init-key :wagoe/tenant-service
  [_ {:keys [tenant-repository validation-config logger metrics-emitter error-reporter]}]
  (log/info "Initializing tenant service")
  (let [service (tenant-service/create-tenant-service
                 tenant-repository
                 validation-config
                 logger
                 metrics-emitter
                 error-reporter)]
    (log/info "Tenant service initialized")
    service))

(defmethod ig/halt-key! :wagoe/tenant-service
  [_ _service]
  (log/info "Tenant service halted (no cleanup needed)"))

;; =============================================================================
;; Tenant Routes (Structured Format for Top-Level Composition)
;; =============================================================================

(defmethod ig/init-key :wagoe/tenant-routes
  [_ {:keys [tenant-service db-context config]}]
  (log/info "Initializing tenant module routes (normalized format)")
  (require 'wagoe.tenant.shell.http)
  (let [tenant-routes-fn (ns-resolve 'wagoe.tenant.shell.http 'tenant-routes-normalized)
        routes (tenant-routes-fn tenant-service db-context (or config {}))]
    (log/info "Tenant module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))})
    routes))

(defmethod ig/halt-key! :wagoe/tenant-routes
  [_ _routes]
  (log/info "Tenant module routes halted (no cleanup needed)"))

;; =============================================================================
;; Membership Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/membership-repository
  [_ {:keys [ctx logger error-reporter]}]
  (log/info "Initializing membership repository")
  (let [repo (membership-persistence/create-membership-repository ctx logger error-reporter)]
    (log/info "Membership repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/membership-repository
  [_ _repo]
  (log/info "Membership repository halted (no cleanup needed)"))

;; =============================================================================
;; Membership Service
;; =============================================================================

(defmethod ig/init-key :wagoe/membership-service
  [_ {:keys [repository logger metrics-emitter error-reporter]}]
  (log/info "Initializing membership service")
  (let [service (membership-service/create-membership-service
                 repository
                 logger
                 metrics-emitter
                 error-reporter)]
    (log/info "Membership service initialized")
    service))

(defmethod ig/halt-key! :wagoe/membership-service
  [_ _service]
  (log/info "Membership service halted (no cleanup needed)"))

;; =============================================================================
;; Invite Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/invite-repository
  [_ {:keys [ctx logger error-reporter]}]
  (log/info "Initializing invite repository")
  (let [repo (invite-persistence/create-invite-repository ctx logger error-reporter)]
    (log/info "Invite repository initialized")
    repo))

(defmethod ig/halt-key! :wagoe/invite-repository
  [_ _repo]
  (log/info "Invite repository halted (no cleanup needed)"))

;; =============================================================================
;; Invite Service
;; =============================================================================

(defmethod ig/init-key :wagoe/invite-service
  [_ {:keys [repository membership-repository logger metrics-emitter error-reporter]}]
  (log/info "Initializing invite service")
  (let [service (invite-service/create-invite-service
                 repository
                 membership-repository
                 logger
                 metrics-emitter
                 error-reporter)]
    (log/info "Invite service initialized")
    service))

(defmethod ig/halt-key! :wagoe/invite-service
  [_ _service]
  (log/info "Invite service halted (no cleanup needed)"))

;; =============================================================================
;; Membership Routes
;; =============================================================================

(defmethod ig/init-key :wagoe/membership-routes
  [_ {:keys [service]}]
  (log/info "Initializing membership module routes (normalized format)")
  (require 'wagoe.tenant.shell.membership-http)
  (let [routes-fn (ns-resolve 'wagoe.tenant.shell.membership-http 'membership-routes-normalized)
        routes    (routes-fn service)]
    (log/info "Membership module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count  (count (:api routes))})
    routes))

(defmethod ig/halt-key! :wagoe/membership-routes
  [_ _routes]
  (log/info "Membership module routes halted (no cleanup needed)"))

;; =============================================================================
;; Tenant HTTP middleware
;; =============================================================================

(defmethod ig/init-key :wagoe/tenant-http-middleware
  [_ {ts :tenant-service ms :membership-service db :db-context}]
  ;; Build the tenant HTTP middleware seq here, in the tenant lib, and let the
  ;; app inject it into platform's http-handler via :extra-middleware. Platform's
  ;; http-handler no longer dynamically requires the tenant lib (BOU-200) — the
  ;; tenant module owns its own middleware wiring. Ordered: tenant resolution
  ;; first, then membership (mirrors the previous inline platform order). Each
  ;; entry is a (fn [handler] ...) applied lazily when the pipeline compiles, so
  ;; a service that is absent simply contributes no middleware.
  (cond-> []
    (and ts db)
    (conj (fn [handler]
            (log/info "Adding multi-tenant middleware to HTTP pipeline")
            (tenant-mw/wrap-multi-tenant handler ts db {:require-tenant? false})))
    ms
    (conj (fn [handler]
            (log/info "Adding tenant membership middleware to HTTP pipeline")
            (membership-mw/wrap-tenant-membership ms handler)))))

(defmethod ig/halt-key! :wagoe/tenant-http-middleware
  [_ _mw]
  (log/info "Tenant HTTP middleware halted (no cleanup needed)"))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`."
  [_settings {:keys [config validation-config]}]
  (let [obs {:logger         (ig/ref :wagoe/logging)
             :error-reporter (ig/ref :wagoe/error-reporting)}]
    {:components
     {:wagoe/tenant-db-schema      {:ctx (ig/ref :wagoe/db-context)}
      :wagoe/tenant-repository     (merge obs {:ctx (ig/ref :wagoe/db-context)})
      :wagoe/membership-repository (merge obs {:ctx (ig/ref :wagoe/db-context)})
      :wagoe/invite-repository     (merge obs {:ctx (ig/ref :wagoe/db-context)})
      :wagoe/tenant-service        (merge obs {:tenant-repository (ig/ref :wagoe/tenant-repository)
                                               :validation-config validation-config
                                               :metrics-emitter   (ig/ref :wagoe/metrics)})
      :wagoe/membership-service    (merge obs {:repository      (ig/ref :wagoe/membership-repository)
                                               :metrics-emitter (ig/ref :wagoe/metrics)})
      :wagoe/invite-service        (merge obs {:repository            (ig/ref :wagoe/invite-repository)
                                               :membership-repository (ig/ref :wagoe/membership-repository)
                                               :metrics-emitter       (ig/ref :wagoe/metrics)})
      :wagoe/tenant-routes         {:tenant-service (ig/ref :wagoe/tenant-service)
                                    :db-context     (ig/ref :wagoe/db-context)
                                    :config         config}
      :wagoe/membership-routes     {:service (ig/ref :wagoe/membership-service)
                                    :config  config}
      ;; Builds the tenant HTTP middleware seq (tenant resolution + membership).
      ;; The http-handler takes it as :extra-middleware rather than constructing
      ;; it, so it does not need to know this module exists.
      :wagoe/tenant-http-middleware {:tenant-service     (ig/ref :wagoe/tenant-service)
                                     :membership-service (ig/ref :wagoe/membership-service)
                                     :db-context         (ig/ref :wagoe/db-context)}}

     ;; :tenant-service and :extra-middleware stay named — the handler reads the
     ;; first directly and threads the second into its middleware pipeline.
     ;; Routes are a collection, so platform holds no per-module slot (BOU-330).
     :http
     {:tenant-service   (ig/ref :wagoe/tenant-service)
      :extra-middleware (ig/ref :wagoe/tenant-http-middleware)}

     :routes [(ig/ref :wagoe/tenant-routes)
              (ig/ref :wagoe/membership-routes)]}))
