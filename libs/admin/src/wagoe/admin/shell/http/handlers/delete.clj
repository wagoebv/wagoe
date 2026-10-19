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
   [clojure.string :as str]
   [ring.util.response :as ring-response])
  (:import [java.util UUID]))

(defn- escape-json-string
  "Escape a string for safe embedding in a JSON string value.
   Prevents injection via entity labels or user-controlled text."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")
      (str/replace "</" "<\\/")))

(defn- refused
  "A refused delete: 409 and an error toast."
  [request e]
  (-> (ring-response/response "")
      (ring-response/status 409)
      (ring-response/header "HX-Reswap" "none")
      (ring-response/header "HX-Trigger"
                            (str "{\"showToast\":{\"type\":\"error\",\"message\":\""
                                 (escape-json-string (support/refusal-message request e)) "\"}}"))))

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
        (let [redirect-url (or safe-return-to
                               (str "/web/admin/" (name entity-name)))
              message ((support/t-fn request) :admin/toast-deleted
                                              {:label (introspection/singular-label entity-config entity-name)})
              toast-json (str "{\"type\":\"success\",\"message\":\"" (escape-json-string message) "\"}")]
          (-> (ring-response/response "")
              (ring-response/status 200)
              (ring-response/header "X-Toast" toast-json)
              (ring-response/header "HX-Redirect" redirect-url)))

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
        toast (if (zero? failed-count)
                {:type :success :message [:t :admin/flash-bulk-deleted {:count success-count :label label}]}
                {:type :warning :message [:t :admin/flash-bulk-deleted-partial {:count success-count :failed failed-count}]})]
    (support/htmx-fragment-response request
                                    (admin-ui/entity-table entity-name records entity-config table-query total-count permissions {}
                                                           (support/display-options config request))
                                    [(toast/oob-toast toast)])))

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
