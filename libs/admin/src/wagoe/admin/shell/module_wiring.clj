(ns wagoe.admin.shell.module-wiring
  "Integrant lifecycle management for admin module.

   This namespace defines Integrant initialization and shutdown methods
   for all admin module components:
   - Schema provider (ISchemaProvider implementation)
   - Admin service (IAdminService implementation)
   - Admin routes (HTTP routes)

   Dependencies are injected via Integrant refs, maintaining FC/IS separation
   and enabling testability with mock implementations."
  (:require
   [integrant.core :as ig]
   [wagoe.admin.shell.schema-repository :as schema-repo]
   [wagoe.admin.shell.service :as service]
   [wagoe.admin.shell.http :as http]))

;; =============================================================================
;; Admin Config Component (pass-through holder referenced by other components)
;; =============================================================================

(defmethod ig/init-key :wagoe/admin [_ config] config)

(defmethod ig/halt-key! :wagoe/admin [_ _] nil)

;; =============================================================================
;; Schema Provider Component
;; =============================================================================

(defmethod ig/init-key :wagoe/admin-schema-provider
  [_ {:keys [db-ctx config malli-schemas]}]
  (schema-repo/create-schema-repository db-ctx config malli-schemas))

(defmethod ig/halt-key! :wagoe/admin-schema-provider
  [_ _schema-provider]
  ; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; Admin Service Component
;; =============================================================================

(defmethod ig/init-key :wagoe/admin-service
  [_ {:keys [db-ctx schema-provider logger error-reporter config]}]
  (service/create-admin-service db-ctx schema-provider logger error-reporter config))

(defmethod ig/halt-key! :wagoe/admin-service
  [_ _admin-service]
  ; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; Admin Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/admin-routes
  [_ {:keys [admin-service schema-provider user-service config]}]
  ;; The module's contribution: web routes, mounted under :web-prefix
  (http/admin-routes admin-service schema-provider config user-service))

(defmethod ig/halt-key! :wagoe/admin-routes
  [_ _routes]
  ; No cleanup needed - routes are just data
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   `:malli-schemas` is deliberately absent: which of its entities the admin UI
   manages is the application's decision, and it merges them in. Two helpers
   that used to live here built the same graph against `:wagoe/database-context`
   and `:wagoe/logger` — keys no application wires — so they had never run
   (BOU-326)."
  [settings _ctx]
  {:components
   {:wagoe/admin-schema-provider {:db-ctx (ig/ref :wagoe/db-context)
                                  :config settings}
    :wagoe/admin-service         {:db-ctx          (ig/ref :wagoe/db-context)
                                  :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                  :logger          (ig/ref :wagoe/logging)
                                  :error-reporter  (ig/ref :wagoe/error-reporting)
                                  :config          settings}
    :wagoe/admin-routes          {:admin-service   (ig/ref :wagoe/admin-service)
                                  :schema-provider (ig/ref :wagoe/admin-schema-provider)
                                  :user-service    (ig/ref :wagoe/user-service)
                                  :config          settings}}
   ;; A ref in a collection, not a named slot on the handler: platform holds no
   ;; list of which modules may contribute routes (BOU-330).
   :routes [(ig/ref :wagoe/admin-routes)]})
