(ns wagoe.admin.shell.http
  "HTTP routes for the admin interface.

   This namespace wires the admin panel's normalized web routes to their
   handlers. Handlers live in `wagoe.admin.shell.http.handlers.*` and their
   shared plumbing (middleware, error mappings, query/form parsing, handler
   helpers) lives in `wagoe.admin.shell.http.support`.

   The admin UI provides:
   - Entity list pages with search, sort, and pagination
   - Entity detail/edit pages with forms
   - Create and update handlers with validation
   - Delete and bulk delete operations
   - HTMX fragment handlers for dynamic updates

   All routes require authentication and admin role.
   Routes follow normalized format for consistent interceptor application."
  (:require
   [wagoe.admin.shell.http.handlers.crud :as crud]
   [wagoe.admin.shell.http.handlers.delete :as delete]
   [wagoe.admin.shell.http.handlers.detail :as detail]
   [wagoe.admin.shell.http.handlers.inline :as inline]
   [wagoe.admin.shell.http.handlers.list :as list-handlers]
   [wagoe.user.shell.middleware :as user-middleware]))

;; Facade re-export: consumers (e.g. http_test) resolve
;; `wagoe.admin.shell.http/create-entity-handler`.
(def create-entity-handler crud/create-entity-handler)

;; =============================================================================
;; Route Definitions
;; =============================================================================

(defn normalized-web-routes
  "Normalized web routes for admin interface.

   All routes require authentication and admin role.
   Routes use flexible-authentication-middleware for session or token auth."
  [admin-service schema-provider config user-service]
  (let [;; Reuse existing auth middleware from user module
        auth-middleware (user-middleware/flexible-authentication-middleware user-service)]

    [["/"
      {:middleware [auth-middleware]
       :get {:handler (list-handlers/admin-home-handler admin-service schema-provider config)
             :summary "Admin home page"}}]

      ;; More specific routes first (to avoid matching /:id patterns)
     ["/:entity/new"
      {:middleware [auth-middleware]
       :get {:handler (detail/new-entity-handler admin-service schema-provider config)
             :summary "Create form page"}}]

     ["/:entity/table"
      {:middleware [auth-middleware]
       :get {:handler (list-handlers/entity-table-fragment-handler admin-service schema-provider config)
             :summary "HTMX table fragment"}}]

     ["/:entity/bulk-delete"
      {:middleware [auth-middleware]
       :post {:handler (delete/bulk-delete-handler admin-service schema-provider config)
              :summary "Bulk delete entities"}}]

      ;; Inline editing routes (Week 2)
     ["/:entity/:id/:field/edit"
      {:middleware [auth-middleware]
       :get {:handler (inline/inline-edit-widget-handler admin-service schema-provider config)
             :summary "Get inline edit form for field"}}]

     ["/:entity/:id/:field/cancel"
      {:middleware [auth-middleware]
       :get {:handler (inline/cancel-inline-edit-handler admin-service schema-provider config)
             :summary "Cancel inline edit"}}]

     ["/:entity/:id/:field"
      {:middleware [auth-middleware]
       :patch {:handler (inline/update-field-handler admin-service schema-provider config)
               :summary "Update single field (inline edit)"}}]

      ;; General routes with path params last
     ["/:entity"
      {:middleware [auth-middleware]
       :get {:handler (list-handlers/entity-list-handler admin-service schema-provider config)
             :summary "Entity list page"}
       :post {:handler (crud/create-entity-handler admin-service schema-provider config)
              :summary "Create entity"}}]

     ["/:entity/:id"
      {:middleware [auth-middleware]
       :get {:handler (detail/entity-detail-handler admin-service schema-provider config)
             :summary "Entity detail/edit page"}
       :put {:handler (crud/update-entity-handler admin-service schema-provider config)
             :summary "Update entity"}
       :delete {:handler (delete/delete-entity-handler admin-service schema-provider config)
                :summary "Delete entity"}}]]))

(defn admin-routes-normalized
  "Normalized admin routes grouped by category.

   Week 1: Only web routes (server-rendered HTML)
   Week 2+: Add API routes for JSON responses

   Returns:
     Map with :api, :web, :static route vectors"
  [admin-service schema-provider config user-service]
  {:api []  ; Week 2+: JSON API endpoints
   :web (normalized-web-routes admin-service schema-provider config user-service)
   :static []  ; Week 2+: Admin-specific static assets

   ;; Where these mount. Platform held this for six modules and a seventh could
   ;; not contribute routes without editing it (BOU-330).
   :web-prefix "/web/admin"

   ;; Already-pathed, so it is not prefixed again. This module's root route is
   ;; "/", which becomes "/web/admin/" — and the un-slashed "/web/admin" that
   ;; people actually type matched nothing and 404'd on a feature that works
   ;; (BOU-229). 302 rather than 301: browsers cache permanent redirects hard,
   ;; and this mount point is configurable via :base-path.
   :extra-web [["/web/admin"
                {:no-doc  true
                 :get {:handler (fn [_]
                                  {:status  302
                                   :headers {"Location" "/web/admin/"}
                                   :body    ""})}}]]})
