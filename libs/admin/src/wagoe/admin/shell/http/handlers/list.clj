(ns wagoe.admin.shell.http.handlers.list
  "Admin home + entity list handlers.

   Renders the dashboard, the entity list page, and the HTMX table fragment."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.http.support :as support]))

;; =============================================================================
;; Admin Home Handler
;; =============================================================================

(defn admin-home-handler
  "Handler for admin home page — shows dashboard with entity tiles."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entities (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
          stats (into {} (map (fn [e] [e {:count (ports/count-entities admin-service e {})}])) entities)]
      (support/html-response request
                             (admin-ui/admin-layout
                              (admin-ui/admin-home entities entity-configs stats)
                              {:user user
                               :current-entity nil
                               :entities entities
                               :entity-configs entity-configs
                               :logo-url (:logo-url config)})))))

;; =============================================================================
;; Entity List Handlers
;; =============================================================================

(defn entity-list-handler
  "Handler for entity list page with table, search, pagination.

   Supports:
    - Text search across configured search-fields
    - Column sorting (ascending/descending)
    - Pagination (page-based)
    - Field filters"
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          options (support/parse-query-params (:query-params request))

          ; Get entity data
          result (ports/list-entities admin-service entity-name options)
          records (:records result)
          total-count (:total-count result)

          ; Merge pagination info from result into options for UI
          ; This ensures UI shows the actual page-size used (from config defaults)
          ; Also ensure sort/dir are present (use defaults if not in options)
          table-query (merge {:sort (or (:sort options) (:default-sort entity-config) :id)
                              :dir (or (:dir options) (:sort-dir options) :asc)}
                             options
                             {:page-size (:page-size result)
                              :page (:page-number result)})

          ; Get all available entities for sidebar
          entities (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)

          ; Flash message from redirects
          flash (:flash request)]

      (support/html-response request
                             (admin-ui/admin-layout
                              (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions options)
                              {:user user
                               :current-entity entity-name
                               :entities entities
                               :entity-configs entity-configs
                               :flash flash
                               :logo-url (:logo-url config)})))))

(defn entity-table-fragment-handler
  "HTMX handler for entity table fragment.

    Returns just the table HTML for dynamic updates.
    Used when sorting, filtering, or paginating without full page reload."
  [admin-service schema-provider _config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)

          ; Check if this is an HTMX request or direct browser navigation
          is-htmx? (get-in request [:headers "hx-request"])

          ; If not HTMX, redirect to main entity page with query params
          _ (when-not is-htmx?
              (let [query-string (:query-string request)
                    redirect-url (str "/web/admin/" (name entity-name)
                                      (when query-string (str "?" query-string)))]
                (throw (ex-info "Redirect to full page"
                                {:type :redirect
                                 :location redirect-url
                                 :status 303}))))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          options (support/parse-query-params (:query-params request))

          ; Get entity data
          result (ports/list-entities admin-service entity-name options)
          records (:records result)
          total-count (:total-count result)

          ; Merge pagination info from result into options for UI
          table-query (merge {:sort (or (:sort options) (:default-sort entity-config) :id)
                              :dir (or (:dir options) (:sort-dir options) :asc)}
                             options
                             {:page-size (:page-size result)
                              :page (:page-number result)})

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)

          ; HTMX sends the target element id in the HX-Target header.
          ; Filter actions target #filter-table-container (needs filter+table).
          ; Search/sort/pagination target #entity-table-container (table only).
          htmx-target (get-in request [:headers "hx-target"])
          filters (:filters options)]

      (if (= htmx-target "filter-table-container")
         ; Filter action: return filter builder + table so the filter UI stays visible
        (support/htmx-fragment-response request
                                        [:div#filter-table-container
                                         (admin-ui/render-filter-builder entity-name entity-config filters)
                                         (admin-ui/entity-table entity-name records entity-config table-query total-count permissions filters)])
         ; Search / sort / pagination: return just the table
        (support/htmx-fragment-response request
                                        (admin-ui/entity-table entity-name records entity-config table-query total-count permissions filters))))))
