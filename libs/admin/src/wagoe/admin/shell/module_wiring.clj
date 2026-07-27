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
  ; Return normalized routes grouped by category
  (http/admin-routes-normalized admin-service schema-provider config user-service))

(defmethod ig/halt-key! :wagoe/admin-routes
  [_ _routes]
  ; No cleanup needed - routes are just data
  nil)

;; =============================================================================
;; Helper Functions for Testing and Development
;; =============================================================================

(defn admin-system-config
  "Generate Integrant system config for admin module.

   Useful for REPL development and integration testing.

   Args:
     base-config: Base system config with database, logging, etc.

   Returns:
     Complete Integrant config map with admin components

   Example:
     (def config (admin-system-config base-config))
     (def system (ig/init config))"
  [base-config]
  (merge base-config
         {:wagoe/admin-schema-provider
          {:db-ctx (ig/ref :wagoe/database-context)
           :config (ig/ref :wagoe/admin)}

          :wagoe/admin-service
          {:db-ctx (ig/ref :wagoe/database-context)
           :schema-provider (ig/ref :wagoe/admin-schema-provider)
           :logger (ig/ref :wagoe/logger)
           :error-reporter (ig/ref :wagoe/error-reporter)
           :config (ig/ref :wagoe/admin)}

          :wagoe/admin-routes
          {:admin-service (ig/ref :wagoe/admin-service)
           :schema-provider (ig/ref :wagoe/admin-schema-provider)
           :user-service (ig/ref :wagoe/user-service)
           :config (ig/ref :wagoe/admin)}}))

(defn start-admin-only-system
  "Start a minimal system with only admin components for testing.

   Creates an in-memory database and starts admin service.

   Args:
     admin-config: Admin configuration map

   Returns:
     Integrant system map

   Example:
     (def system (start-admin-only-system admin-config))
     (ig/halt! system)"
  [admin-config]
  (let [minimal-config
        {:wagoe/database
         {:adapter :h2
          :memory true}

         :wagoe/database-context
         {:database (ig/ref :wagoe/database)}

         :wagoe/admin
         admin-config

         :wagoe/logger
         {:provider :no-op}

         :wagoe/error-reporter
         {:provider :no-op}

         :wagoe/admin-schema-provider
         {:db-ctx (ig/ref :wagoe/database-context)
          :config (ig/ref :wagoe/admin)}

         :wagoe/admin-service
         {:db-ctx (ig/ref :wagoe/database-context)
          :schema-provider (ig/ref :wagoe/admin-schema-provider)
          :logger (ig/ref :wagoe/logger)
          :error-reporter (ig/ref :wagoe/error-reporter)
          :config (ig/ref :wagoe/admin)}}]

    (ig/init minimal-config)))

(comment
  ; REPL workflow examples

  ; 1. Start minimal admin system for testing
  #_(require '[integrant.repl :as ig-repl])

  (def test-config
    {:enabled? true
     :base-path "/web/admin"
     :require-role :admin
     :entity-discovery {:mode :allowlist
                        :allowlist #{:users}}
     :entities {:users {:label "Users"}}
     :pagination {:default-page-size 50
                  :max-page-size 200}})

  (def system (start-admin-only-system test-config))

  ; 2. Access components
  (def admin-service (:wagoe/admin-service system))
  (def schema-provider (:wagoe/admin-schema-provider system))

  ; 3. Test operations
  (require '[wagoe.admin.ports :as ports])
  (ports/list-available-entities schema-provider)
  ; => [:users]

  (ports/get-entity-config schema-provider :users)
  ; => {:label "Users" :table-name :users :fields {...} ...}

  ; 4. Cleanup
  (ig/halt! system)

  ; End REPL examples
  )
