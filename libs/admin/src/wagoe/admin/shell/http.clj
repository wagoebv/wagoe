(ns wagoe.admin.shell.http
  "HTTP routes for the admin interface.

   This namespace wires the admin panel's web routes to their handlers.
   Handlers live in `wagoe.admin.shell.http.handlers.*` and their
   shared plumbing (middleware, error mappings, query/form parsing, handler
   helpers) lives in `wagoe.admin.shell.http.support`.

   The admin UI provides:
   - Entity list pages with search, sort, and pagination
   - Entity detail/edit pages with forms
   - Create and update handlers with validation
   - Delete and bulk delete operations
   - HTMX fragment handlers for dynamic updates

   All routes require authentication. Who may do what is
   `wagoe.admin.core.permissions`."
  (:require
   [wagoe.admin.shell.http.handlers.crud :as crud]
   [wagoe.admin.shell.http.handlers.delete :as delete]
   [wagoe.admin.shell.http.handlers.detail :as detail]
   [wagoe.admin.shell.http.handlers.inline :as inline]
   [wagoe.admin.shell.http.handlers.list :as list-handlers]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.core.ui.layout :as layout]
   [wagoe.admin.ports :as ports]
   [wagoe.admin.shell.scoped-service :as scoped-service]
   [wagoe.user.shell.middleware :as user-middleware]))

;; Facade re-export: consumers (e.g. http_test) resolve
;; `wagoe.admin.shell.http/create-entity-handler`.
(def create-entity-handler crud/create-entity-handler)

;; =============================================================================
;; Route Definitions
;; =============================================================================

(defn- with-access
  "`user` with their rights, read through `(:access config)` unless the admin's
   middleware already put them on."
  [config user]
  (if (or (nil? user) (permissions/has-access? user))
    user
    (permissions/with-access user ((:access config) user))))

(defn web-routes
  "The admin interface's web routes, as Reitit data.

   Paths are relative to :web-prefix below, not to /web.
   All routes require authentication; the handlers check permissions.
   Routes use flexible-authentication-middleware for session or token auth."
  [admin-service schema-provider config user-service]
  (let [;; Reuse existing auth middleware from user module
        auth-middleware (user-middleware/flexible-authentication-middleware user-service)
        ;; Under :report and :enforce the user carries their rights, and under
        ;; :enforce each request gets the service scoped to them (ADR-041 §6).
        ;; Otherwise the handlers are built once, as before.
        access          (:access config)
        auth-middleware (if access
                          (fn [handler]
                            (auth-middleware
                             (fn [request]
                               (handler (update request :user (partial with-access config))))))
                          auth-middleware)
        h               (if (and access (= :enforce (:authorization-mode config)))
                          (fn [ctor]
                            (fn [request]
                              ((ctor (scoped-service/for-user admin-service schema-provider (:user request))
                                     schema-provider config)
                               request)))
                          (fn [ctor] (ctor admin-service schema-provider config)))]

    [["/"
      {:middleware [auth-middleware]
       :get {:handler (h list-handlers/admin-home-handler)
             :summary "Admin home page"}}]

      ;; More specific routes first (to avoid matching /:id patterns)
     ["/:entity/new"
      {:middleware [auth-middleware]
       :get {:handler (h detail/new-entity-handler)
             :summary "Create form page"}}]

     ["/:entity/new/rows/:child"
      {:middleware [auth-middleware]
       :get {:handler (h detail/new-child-row-handler)
             :summary "HTMX child row for a create form"}}]

     ["/:entity/table"
      {:middleware [auth-middleware]
       :get {:handler (h list-handlers/entity-table-fragment-handler)
             :summary "HTMX table fragment"}}]

     ["/:entity/validate-field"
      {:middleware [auth-middleware]
       :post {:handler (h crud/validate-field-handler)
              :summary "One field's errors, on leaving it"}}]

     ["/:entity/options/:field"
      {:middleware [auth-middleware]
       :get {:handler (h crud/field-options-handler)
             :summary "HTMX options of a foreign key's combobox"}}]

     ["/:entity/lookup"
      {:middleware [auth-middleware]
       :get {:handler (h crud/lookup-handler)
             :summary "HTMX options of a combobox choosing one of the entity's rows"}}]

     ["/:entity/filters"
      {:middleware [auth-middleware]
       :get {:handler (h list-handlers/filter-drawer-handler)
             :summary "HTMX filter drawer"}}]

     ["/:entity/bulk-delete"
      {:middleware [auth-middleware]
       :post {:handler (h delete/bulk-delete-handler)
              :summary "Bulk delete entities"}}]

      ;; Inline editing routes (Week 2)
     ["/:entity/:id/:field/edit"
      {:middleware [auth-middleware]
       :get {:handler (h inline/inline-edit-widget-handler)
             :summary "Get inline edit form for field"}}]

     ["/:entity/:id/:field/cancel"
      {:middleware [auth-middleware]
       :get {:handler (h inline/cancel-inline-edit-handler)
             :summary "Cancel inline edit"}}]

     ["/:entity/:id/:field"
      {:middleware [auth-middleware]
       :patch {:handler (h inline/update-field-handler)
               :summary "Update single field (inline edit)"}}]

      ;; General routes with path params last
     ["/:entity"
      {:middleware [auth-middleware]
       :get {:handler (h list-handlers/entity-list-handler)
             :summary "Entity list page"}
       :post {:handler (h crud/create-entity-handler)
              :summary "Create entity"}}]

     ["/:entity/:id"
      {:middleware [auth-middleware]
       :get {:handler (h detail/entity-detail-handler)
             :summary "Entity detail/edit page"}
       :put {:handler (h crud/update-entity-handler)
             :summary "Update entity"}
       :delete {:handler (h delete/delete-entity-handler)
                :summary "Delete entity"}}]]))

(defn- nav-contribution
  "The admin's sidebar items, from each entity's resolved config, or its own
   config when that cannot be resolved (its table is missing). Only :allowlist
   discovery lists; the other modes throw, and must fail the admin's pages,
   not every page's sidebar. Under :enforce an item shows to a user who may
   read its entity, their rights read once per page."
  [schema-provider config]
  (let [entities (try (ports/list-available-entities schema-provider)
                      (catch clojure.lang.ExceptionInfo _ []))
        configs  (into {} (map (fn [e] [e (try (ports/get-entity-config schema-provider e)
                                               (catch Exception _ (get-in config [:entities e])))]))
                       entities)
        nav      (layout/nav entities configs)]
    (if (= :enforce (:authorization-mode config))
      (let [access (memoize (partial with-access config))]
        (update-in nav [:admin :items]
                   (partial mapv (fn [{:keys [entity] :as item}]
                                   (assoc item :access #(permissions/can-view-entity?
                                                         (access %) entity (get configs entity)))))))
      nav)))

(defn- handler-checks
  "`routes` marked `:authorization :self`: the admin checks per entity, so the
   platform's authorization leaves them to it (ADR-041 §5). Not one parent
   route: the platform orders flat routes by specificity, and a parent would
   put `/:entity/:id` ahead of other modules' `/web/admin/...` pages."
  [routes]
  (mapv (fn [[path data]] [path (assoc data :authorization :self)]) routes))

(defn admin-routes
  "Admin's contribution to the application's route table.

   Web routes only — the admin UI is server-rendered HTML, and there is no
   JSON API for it yet."
  [admin-service schema-provider config user-service]
  {:api []  ; Week 2+: JSON API endpoints
   :web (handler-checks (web-routes admin-service schema-provider config user-service))
   :static []  ; Week 2+: Admin-specific static assets

   ;; Its entities, for the sidebar, asked per page: whether one is a join
   ;; table, and so hidden, needs its table, which may come after boot.
   :nav #(nav-contribution schema-provider config)

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
                 :authorization :signed-in
                 :get {:handler (fn [_]
                                  {:status  302
                                   :headers {"Location" "/web/admin/"}
                                   :body    ""})}}]]})
