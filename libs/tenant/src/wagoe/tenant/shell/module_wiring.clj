(ns wagoe.tenant.shell.module-wiring
  (:require [wagoe.tenant.shell.audit-persistence :as audit-persistence]
            [wagoe.tenant.shell.data-export :as data-export]
            [wagoe.tenant.shell.invite-persistence :as invite-persistence]
            [wagoe.tenant.shell.invite-service :as invite-service]
            [wagoe.tenant.shell.membership-middleware :as membership-mw]
            [wagoe.tenant.shell.membership-persistence :as membership-persistence]
            [wagoe.tenant.shell.membership-service :as membership-service]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.tenant-middleware :as tenant-mw]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.service :as tenant-service]
            [wagoe.tenant.shell.tenant-iteration :as tenant-iteration]
            [wagoe.tenant.shell.tenant-migrations :as tenant-migrations]
            [wagoe.tenant.shell.user-foreign-keys :as user-foreign-keys]
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
        (let [{:keys [errors]} (tenant-migrations/migrate-all-tenants! ctx schemas)]
          (when (seq errors)
            (log/error "Some tenant schemas failed to migrate on startup" {:errors errors})))))
    (catch Exception e
      ;; Never block application startup on tenant migration fan-out; surface
      ;; the failure so it can be alerted on and re-run.
      (log/error e "Tenant migration fan-out failed on startup"))))

(defn- add-user-foreign-keys!
  "The keys `migrate up` adds, for a database that only boots or migrated
   before auth_users existed (BOU-612). Boot changes no rows and never fails on
   them: a key that needs cleanup first is logged and left to `migrate up`."
  [ctx]
  (try
    (doseq [left-off (user-foreign-keys/add-clean-foreign-keys! ctx)]
      (log/warn "Boot left a tenant→auth_users key off; run `bb migrate up`" left-off))
    (catch Exception e
      (log/error e "Adding tenant→auth_users keys failed on startup"))))

;; A tenant schema migrates the modules the context's `:enabled-libraries`
;; names, which the system config sets on `:wagoe/db-context`.
(defmethod ig/init-key :wagoe/tenant-db-schema
  [_ {:keys [ctx allow-h2?]}]
  (log/info "Initializing tenant module database schema")
  (provisioning/refuse-unsupported-database! ctx allow-h2?)
  (tenant-persistence/initialize-tenant-schema! ctx)
  (add-user-foreign-keys! ctx)
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
  (log/info "Initializing tenant module routes")
  (require 'wagoe.tenant.shell.http)
  (let [tenant-routes-fn (ns-resolve 'wagoe.tenant.shell.http 'tenant-routes)
        routes (tenant-routes-fn tenant-service db-context (or config {}))]
    (log/info "Tenant module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count (count (:api routes))})
    routes))

(defmethod ig/halt-key! :wagoe/tenant-routes
  [_ _routes]
  (log/info "Tenant module routes halted (no cleanup needed)"))

;; =============================================================================
;; Data export
;; =============================================================================

(defmethod ig/init-key :wagoe/tenant-data-exporter
  [_ repositories]
  (data-export/map->TenantDataExporter repositories))

(defmethod ig/init-key :wagoe/tenant-audit-repository
  [_ {:keys [ctx]}]
  (audit-persistence/create-tenant-audit-repository ctx))

(defmethod ig/init-key :wagoe/tenant-export-routes
  [_ {:keys [data-export audit-repository]}]
  (require 'wagoe.tenant.shell.http)
  ((ns-resolve 'wagoe.tenant.shell.http 'export-routes) data-export audit-repository))

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
  (log/info "Initializing membership module routes")
  (require 'wagoe.tenant.shell.membership-http)
  (let [routes-fn (ns-resolve 'wagoe.tenant.shell.membership-http 'membership-routes)
        routes    (routes-fn service)]
    (log/info "Membership module routes initialized successfully"
              {:route-keys (keys routes)
               :api-count  (count (:api routes))})
    routes))

(defmethod ig/halt-key! :wagoe/membership-routes
  [_ _routes]
  (log/info "Membership module routes halted (no cleanup needed)"))

;; =============================================================================
;; Running work in every tenant
;; =============================================================================

(defmethod ig/init-key :wagoe/each-tenant
  [_ {:keys [tenant-service db-context]}]
  ;; (fn [f]): calls (f tenant) in each active tenant's schema; for a module
  ;; with background work over tenant tables, such as workflow's auto-transitions.
  (let [provider (provisioning/create-tenant-schema-provider)]
    (fn [f] (tenant-iteration/for-each-tenant tenant-service provider db-context f))))

(defmethod ig/halt-key! :wagoe/each-tenant [_ _] nil)

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
  [settings {:keys [config validation-config]}]
  (let [obs     {:logger         (ig/ref :wagoe/logging)
                 :error-reporter (ig/ref :wagoe/error-reporting)}]
    {:components
     {:wagoe/tenant-db-schema      (cond-> {:ctx (ig/ref :wagoe/db-context)
                                            ;; Only for order: auth_users must exist before
                                            ;; the keys to it; empty when user is not run.
                                            :user-db-schema (ig/refset :wagoe/user-db-schema)}
                                     ;; Only a test profile on H2 sets it.
                                     (:allow-h2? settings) (assoc :allow-h2? true))
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
      :wagoe/each-tenant           {:tenant-service (ig/ref :wagoe/tenant-service)
                                    :db-context     (ig/ref :wagoe/db-context)}
      :wagoe/membership-routes     {:service (ig/ref :wagoe/membership-service)
                                    :config  config}
      :wagoe/tenant-data-exporter  {:tenant-repository     (ig/ref :wagoe/tenant-repository)
                                    :membership-repository (ig/ref :wagoe/membership-repository)
                                    :invite-repository     (ig/ref :wagoe/invite-repository)}
      ;; :wagoe/data-export is platform's, over every module's :data-export.
      :wagoe/tenant-audit-repository {:ctx (ig/ref :wagoe/db-context)
                                      :schema (ig/ref :wagoe/tenant-db-schema)}
      :wagoe/tenant-export-routes  {:data-export      (ig/ref :wagoe/data-export)
                                    :audit-repository (ig/ref :wagoe/tenant-audit-repository)}
      ;; Builds the tenant HTTP middleware seq (tenant resolution + membership).
      ;; The http-handler takes it as :extra-middleware rather than constructing
      ;; it, so it does not need to know this module exists.
      :wagoe/tenant-http-middleware {:tenant-service     (ig/ref :wagoe/tenant-service)
                                     :membership-service (ig/ref :wagoe/membership-service)
                                     :db-context         (ig/ref :wagoe/db-context)}}

     ;; :tenant-service stays named — the handler reads it directly.
     ;; :extra-middleware joins every module's middleware in the pipeline.
     ;; Routes are a collection, so platform holds no per-module slot (BOU-330).
     :http
     {:tenant-service   (ig/ref :wagoe/tenant-service)
      :extra-middleware (ig/ref :wagoe/tenant-http-middleware)}

     :routes [(ig/ref :wagoe/tenant-routes)
              (ig/ref :wagoe/membership-routes)
              (ig/ref :wagoe/tenant-export-routes)]
     :data-export {:tenant (ig/ref :wagoe/tenant-data-exporter)}}))
