(ns wagoe.admin.shell.http.handlers.detail
  "Entity detail / edit page handlers (view existing + new-entity form)."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.ui.drawer :as drawer]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [clojure.string :as str]
   [ring.util.response :as ring-response]))

(defn- peek-request?
  "An HTMX request for the record drawer rather than the detail page: a row
   click on a list asks for `?peek=1`."
  [request]
  (and (get-in request [:headers "hx-request"])
       (= "1" (get-in request [:query-params "peek"]))))

(defn- drawer-display
  "The display options for the drawer, with the overview of its one record —
   relation titles — when the service provides one (ADR-040)."
  [admin-service config request entity-name entity-config record]
  (cond-> (support/display-options config request)
    (satisfies? ports/IListOverview admin-service)
    (assoc :overview (ports/list-overview admin-service entity-name
                                          {:overview/fields (drawer/fields entity-config)}
                                          [record]))))

(defn entity-detail-handler
  "Handler for entity detail/edit page: the form for editing an existing
   entity, read-only for a user who may read it but not write."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request schema-provider)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Unknown entity"
                              {:type :not-found
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Read opens the page; without write it renders read-only
          _ (shell-permissions/assert-can-view-entity! user entity-name entity-config)

          ; Get entity record
          record (ports/get-entity admin-service entity-name id)

          ; Verify record exists
          _ (when-not record
              (throw (ex-info "Entity not found"
                              {:type :not-found
                               :entity-name entity-name
                               :id id})))

          permissions (cond-> (permissions/get-entity-permissions user entity-name entity-config)
                        ;; Write in another scope than this record's: read-only here
                        (and (= :enforce (permissions/mode user))
                             (not (permissions/record-in-scope?
                                   (permissions/scopes user entity-name :write entity-config)
                                   entity-config record)))
                        (assoc :can-edit false))
          ctx         (support/build-entity-detail-opts admin-service schema-provider config entity-name entity-config record request)]

      (if (peek-request? request)
        (support/html-response request
                               (admin-ui/record-drawer entity-name entity-config record
                                                       {:display         (drawer-display admin-service config request
                                                                                         entity-name entity-config record)
                                                        :related-records (get-in ctx [:page-opts :related-records])
                                                        :return-to       (support/safe-return-to request)
                                                        :permissions     permissions}))
      (support/html-response request
                             (admin-ui/admin-layout
                              (admin-ui/entity-detail-page entity-name entity-config record {} permissions (:page-opts ctx))
                              {:user user
                               :current-entity entity-name
                               :entity-configs (:entity-configs ctx)
                               :logo-url (:logo-url config)}))))))

(defn new-entity-handler
  "Handler for new entity creation form.

   Shows empty form for creating new entity. If the entity declares a
   `:create-redirect-url`, redirects there instead (used for split-table
   entities that need a dedicated create flow)."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request schema-provider)
          entity-name (support/get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Unknown entity"
                              {:type :not-found
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          disabled (support/create-disabled-response request user entity-name entity-config)

          ; Check permissions
          _ (when-not disabled
              (shell-permissions/assert-can-create-entity! user entity-name entity-config))]

      (or
       disabled
       (support/create-config-error-response request config schema-provider user entity-name entity-config)
       (if-let [redirect-url (:create-redirect-url entity-config)]
        ;; Append return-to so the delegated create flow can bring the user
        ;; back to the admin list view (or whichever page they came from) on
        ;; success or cancel, instead of falling through to module-owned URLs
        ;; that may not exist as GET routes.
         (let [return-to (str "/web/admin/" (name entity-name))
               separator (if (str/includes? redirect-url "?") "&" "?")
               target (str redirect-url separator "return-to=" return-to)]
           (ring-response/redirect target 303))
         (let [; Get all available entities for sidebar
               entities (ports/list-available-entities schema-provider)
               entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

              ; Get permissions
               permissions (permissions/get-entity-permissions user entity-name entity-config)
               prefill     (support/foreign-key-prefill entity-name entity-configs (:query-params request))
               ;; :min blank rows of each has-many created with it (BOU-570).
               nested      (mapv (fn [rel] {:rel rel :rows (mapv (fn [i] [(str i) {}]) (range (:min rel)))})
                                 (support/nested-relationships schema-provider entity-config))]
           (support/html-response request
                                  (admin-ui/admin-layout
                                   (admin-ui/entity-detail-page entity-name entity-config nil {} permissions
                                                                {:display   (support/display-options config request)
                                                                ;; A parent's "New" link (BOU-491).
                                                                 :return-to (support/safe-return-to request)
                                                                 :prefill   prefill
                                                                 :nested    nested
                                                                 :field-options (support/foreign-key-options
                                                                                 admin-service config entity-configs
                                                                                 entity-name prefill)
                                                                 :links (support/link-fields admin-service config entity-configs
                                                                                             entity-name nil {})})
                                   {:user user
                                    :current-entity entity-name
                                    :entity-configs entity-configs
                                    :logo-url (:logo-url config)}))))))))

(defn- row-index
  "An index for an added child row: random, so replicas do not hand out the
   same one, and at most nine digits, which the parser reads. A collision is
   refused as a duplicate row."
  []
  (.nextLong (java.util.concurrent.ThreadLocalRandom/current) 1 1000000000))

(defn new-child-row-handler
  "GET /:entity/new/rows/:child — one blank child row for the create form of
   `:entity`, which HTMX appends (BOU-570)."
  [_admin-service schema-provider config]
  (fn [request]
    (let [user        (support/require-admin-user! request schema-provider)
          entity-name (support/get-entity-name request)
          _           (when-not (ports/validate-entity-exists schema-provider entity-name)
                        (throw (ex-info "Unknown entity"
                                        {:type :not-found :entity-name entity-name})))
          entity-config (ports/get-entity-config schema-provider entity-name)
          _           (shell-permissions/assert-can-create-entity! user entity-name entity-config)
          child       (keyword (get-in request [:path-params :child]))
          rel         (some #(when (= child (:entity %)) %)
                            (support/nested-relationships schema-provider entity-config))]
      (when-not rel
        (throw (ex-info "Not a has-many created with this entity"
                        {:type :not-found :entity-name entity-name :child child})))
      (support/html-response request
                             (admin-ui/child-row rel (row-index)
                                                 {} nil (support/display-options config request))))))
