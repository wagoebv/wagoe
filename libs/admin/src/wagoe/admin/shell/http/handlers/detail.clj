(ns wagoe.admin.shell.http.handlers.detail
  "Entity detail / edit page handlers (view existing + new-entity form)."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [clojure.string :as str]
   [ring.util.response :as ring-response]))

(defn entity-detail-handler
  "Handler for entity detail/edit page.

   Shows form for editing existing entity."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Get entity record
          record (ports/get-entity admin-service entity-name id)

          ; Verify record exists
          _ (when-not record
              (throw (ex-info "Entity not found"
                              {:type :not-found
                               :entity-name entity-name
                               :id id})))

          permissions (permissions/get-entity-permissions user entity-name entity-config)
          ctx         (support/build-entity-detail-opts admin-service schema-provider entity-name entity-config record request)]

      (support/html-response request
                             (admin-ui/admin-layout
                              (admin-ui/entity-detail-page entity-name entity-config record {} permissions (:page-opts ctx))
                              {:user user
                               :current-entity entity-name
                               :entities (:entities ctx)
                               :entity-configs (:entity-configs ctx)
                               :logo-url (:logo-url config)})))))

(defn new-entity-handler
  "Handler for new entity creation form.

   Shows empty form for creating new entity. If the entity declares a
   `:create-redirect-url`, redirects there instead (used for split-table
   entities that need a dedicated create flow)."
  [_admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-create-entity! user entity-name entity-config)]

      ;; Split-table entities MUST have :create-redirect-url because the generic
      ;; admin create flow only writes to one table, leaving orphaned rows.
      ;; Fail early with a clear error instead of letting the service layer throw.
      (when (and (:split-table-update entity-config)
                 (not (:create-redirect-url entity-config)))
        (throw (ex-info (str "Entity '" (name entity-name) "' uses split-table-update but has no "
                             ":create-redirect-url configured. Add :create-redirect-url to the "
                             "entity config in :boundary/admin :entities.")
                        {:type :invalid-config
                         :entity-name entity-name})))

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
              permissions (permissions/get-entity-permissions user entity-name entity-config)]
          (support/html-response request
                                 (admin-ui/admin-layout
                                  (admin-ui/entity-detail-page entity-name entity-config nil {} permissions {})
                                  {:user user
                                   :current-entity entity-name
                                   :entities entities
                                   :entity-configs entity-configs
                                   :logo-url (:logo-url config)})))))))
