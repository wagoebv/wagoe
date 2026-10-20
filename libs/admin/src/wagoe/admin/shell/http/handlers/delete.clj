(ns wagoe.admin.shell.http.handlers.delete
  "Delete + bulk-delete entity handlers."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.core.schema-introspection :as introspection]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [wagoe.shared.ui.core.toast :as toast]
   [ring.util.response :as ring-response])
  (:import [java.util UUID]))

(defn- refused
  "A refused delete: 409 and an error toast."
  [request e]
  (-> (ring-response/response "")
      (ring-response/status 409)
      (ring-response/header "HX-Reswap" "none")
      (toast/with-error-toast (support/refusal-message request e))))

(defn delete-entity-handler
  "Handler for deleting entity.

   Soft or hard delete based on entity schema configuration.
   Returns HTMX fragment triggering table refresh."
  [admin-service schema-provider _config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Unknown entity"
                              {:type :not-found
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Delete entity (soft or hard, as configured), with its children
          [deleted? refusal] (support/attempt #(ports/delete-entity admin-service entity-name id))
          safe-return-to (support/safe-return-to request)]

      (cond
        refusal
        (refused request refusal)

        deleted?
        ; Success - redirect back to return_to (parent context) or entity list
        (toast/redirect-with-toast
         (or safe-return-to (str "/web/admin/" (name entity-name)))
         :success
         ((support/t-fn request) :admin/toast-deleted
                                 {:label (introspection/singular-label entity-config entity-name)}))

        ; Failed to delete
        :else
        (-> (ring-response/response "")
            (ring-response/status 500))))))

(defn- bulk-delete-response
  "The refreshed table after a bulk delete, with a toast counting it."
  [request admin-service entity-name entity-config config user result]
  (let [success-count (or (:success-count result) 0)
        failed-count (or (:failed-count result) 0)

        ; Fetch updated list
        list-result (ports/list-entities admin-service entity-name {})
        records (support/with-workflow-states config entity-config (:records list-result))
        total-count (:total-count list-result)
        table-query {:page-size (:page-size list-result)
                     :page (:page-number list-result)}
        permissions (permissions/get-entity-permissions user entity-name entity-config)

        ; One record is named in the singular (BOU-589)
        label (if (= 1 success-count)
                (introspection/singular-label entity-config entity-name)
                (introspection/plural-label entity-config entity-name))
        all? (zero? failed-count)]
    (support/htmx-fragment-response request
                                    (admin-ui/entity-table entity-name records entity-config table-query total-count permissions {}
                                                           (support/display-options config request))
                                    [(toast/oob-toast
                                      {:type    (if all? :success :warning)
                                       :message (if all?
                                                  [:t :admin/flash-bulk-deleted {:count success-count :label label}]
                                                  [:t :admin/flash-bulk-deleted-partial {:count success-count :failed failed-count}])})
                                     (admin-ui/record-count-badge total-count)])))

(defn bulk-delete-handler
  "Handler for bulk deleting multiple entities.

   Expects form with 'ids[]' parameter containing entity IDs.
   Returns HTMX fragment triggering table refresh."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Unknown entity"
                              {:type :not-found
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Extract IDs from form params
          id-strings (get-in request [:form-params "ids[]"])
          ids (when id-strings
                (mapv #(UUID/fromString %) (if (string? id-strings) [id-strings] id-strings)))

          ; Bulk delete
          [result refusal] (support/attempt #(when (seq ids)
                                               (ports/bulk-delete-entities admin-service entity-name ids)))]
      (if refusal
        (refused request refusal)
        (bulk-delete-response request admin-service entity-name entity-config config user result)))))
