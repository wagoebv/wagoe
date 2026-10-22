(ns wagoe.admin.shell.http.handlers.list
  "Admin home + entity list handlers.

   Renders the dashboard, the entity list page, and the HTMX table fragment."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.ui.filters :as filters]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [wagoe.shared.ui.core.htmx :as htmx]
   [ring.util.response :as ring-response]))

(defn- list-options
  "The list's options from the query string, without the filters that filter
   nothing: the filter drawer sends every field, the untouched ones blank."
  [request]
  (let [options (support/parse-query-params (:query-params request))
        active  (filters/active-filters (:filters options))]
    (if (seq active)
      (assoc options :filters active)
      (dissoc options :filters))))

;; =============================================================================
;; List Overview (ADR-040)
;; =============================================================================

(defn- display-with-overview
  "The display options for a list page, with the overview its cells need when
   the service provides one (ADR-040)."
  [admin-service config request entity-name options records]
  (cond-> (support/display-options config request)
    (satisfies? ports/IListOverview admin-service)
    (assoc :overview (ports/list-overview admin-service entity-name options records))))

;; =============================================================================
;; Admin Home Handler
;; =============================================================================

(defn admin-home-handler
  "The dashboard: the sidebar's sections, a card per page the user may open.
   For every signed-in user, except under :enforce, where it needs read on
   one entity in a scope that entity can check (ADR-041 §6)."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/get-current-user request)
          available (ports/list-available-entities schema-provider)
          all-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) available)
          _ (when (= :enforce (permissions/mode user))
              (shell-permissions/assert-can-access-admin! user all-configs))
          entities (permissions/filter-visible-entities user available all-configs)
          entity-configs (select-keys all-configs entities)
          groups (admin-ui/dashboard-groups entities entity-configs user)
          ;; Counted only for a card the dashboard shows
          stats (into {} (comp (mapcat :items)
                               (keep :entity)
                               (filter (set entities))
                               (map (fn [e] [e {:count (ports/count-entities admin-service e {})}])))
                      groups)]
      (support/html-response request
                             (admin-ui/admin-layout
                              (admin-ui/dashboard groups stats)
                              {:user user
                               :nav groups
                               :current-entity nil
                               :entity-configs entity-configs
                               :logo-url (:logo-url config)})))))

;; =============================================================================
;; Entity List Handlers
;; =============================================================================

(defn- list-state
  "Everything the list page and its table fragment render from."
  [admin-service schema-provider config request user entity-name]
  (when-not (ports/validate-entity-exists schema-provider entity-name)
    (throw (ex-info "Unknown entity"
                    {:type :not-found
                     :entity-name entity-name})))
  (let [options (list-options request)
        entity-config (ports/get-entity-config schema-provider entity-name)
        _ (shell-permissions/assert-can-view-entity! user entity-name entity-config)
        ;; The bare table is the one response without the filter chips
        entity-config (if (htmx/fragment-request? (:headers request) ["entity-table-container"])
                        entity-config
                        (-> (support/with-filter-value-labels admin-service schema-provider user entity-name
                              entity-config (:filters options))
                            ;; A link filter's chip names the record, not its id
                            (as-> c (support/with-link-options admin-service schema-provider config c
                                      #(contains? (:filters options) (:key %))))))
        result (ports/list-entities admin-service entity-name options)
        records (support/with-workflow-states config entity-config (:records result))]
    {:entity-config entity-config
     :options options
     :records records
     :total-count (:total-count result)
     ;; The page size and page the service used; sort/dir defaulted
     :table-query (merge {:sort (or (:sort options) (:default-sort entity-config) :id)
                          :dir (or (:dir options) (:sort-dir options) :asc)}
                         options
                         {:page-size (:page-size result)
                          :page (:page-number result)})
     :permissions (permissions/get-entity-permissions user entity-name entity-config)
     :display (display-with-overview admin-service config request entity-name options records)}))

(defn- table-fragment-response
  "The table, or with the filter summary when that is the target, plus the
   toolbar count out of band."
  [request entity-name {:keys [entity-config options records total-count table-query
                               permissions display]}]
  (let [args [entity-name records entity-config table-query total-count permissions
              (:filters options) display]]
    (support/htmx-fragment-response
     request
     (if (= "filter-table-container" (get-in request [:headers "hx-target"]))
       (apply admin-ui/filter-table-container args)
       (apply admin-ui/entity-table args))
     [(admin-ui/record-count-badge total-count)])))

(defn entity-list-handler
  "The entity list page: table, search, sorting, paging, filters. Its own
   controls request this URL and push it, so the address bar holds the list's
   state; they get the table fragment back."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request schema-provider)
          entity-name (support/get-entity-name request)
          state (list-state admin-service schema-provider config request user entity-name)]
      ;; One URL, two bodies: a cache must not answer one with the other
      (ring-response/header
       (if (htmx/fragment-request? (:headers request) ["entity-table-container" "filter-table-container"])
         (table-fragment-response request entity-name state)
         (let [{:keys [entity-config options records total-count table-query permissions display]} state
               entities (ports/list-available-entities schema-provider)
               entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)]
           (support/html-response request
                                  (admin-ui/admin-layout
                                   (admin-ui/entity-list-page entity-name records entity-config table-query
                                                             total-count permissions
                                                             (assoc options :display display))
                                   {:user user
                                    :current-entity entity-name
                                    :entity-configs entity-configs
                                    :flash (:flash request)
                                    :logo-url (:logo-url config)}))))
       "Vary" "HX-Request, HX-Target"))))

(defn entity-table-fragment-handler
  "GET /:entity/table — the table fragment, for callers that ask for it
   directly. A browser navigating here is sent to the list page."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request schema-provider)
          entity-name (support/get-entity-name request)]
      (when-not (get-in request [:headers "hx-request"])
        (throw (ex-info "Redirect to full page"
                        {:type :redirect
                         :location (str "/web/admin/" (name entity-name)
                                        (when-let [qs (:query-string request)] (str "?" qs)))
                         :status 303})))
      (table-fragment-response request entity-name
                               (list-state admin-service schema-provider config request user entity-name)))))

(defn filter-drawer-handler
  "GET /:entity/filters — the filter drawer for the filters in the query
   string, blank ones kept: re-rendered when an operator changes, it keeps
   the operator chosen for a field that has no value yet."
  [admin-service schema-provider config]
  (fn [request]
    (let [[user entity-name entity-config] (support/viewable-entity! schema-provider request)
          entity-config (support/with-link-options admin-service schema-provider config entity-config any?)
          filters (:filters (support/parse-query-params (:query-params request)))]
      (support/html-response request
                             (admin-ui/filter-drawer entity-name
                                                     (admin-ui/with-field-options
                                                       entity-config
                                                       (support/filter-field-options admin-service schema-provider config
                                                                                     user entity-name filters))
                                                     filters)))))
