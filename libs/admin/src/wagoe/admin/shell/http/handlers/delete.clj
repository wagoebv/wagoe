(ns wagoe.admin.shell.http.handlers.delete
  "Delete + bulk-delete entity handlers."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
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
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Delete entity (soft or hard based on schema)
          deleted? (ports/delete-entity admin-service entity-name id)
          return-to (get-in request [:query-params "return_to"])
          ;; Only accept relative paths under /web/admin/ to prevent open redirects
          safe-return-to (when (and (not-empty return-to)
                                    (str/starts-with? return-to "/web/admin/"))
                           return-to)]

      (if deleted?
        ; Success - redirect back to return_to (parent context) or entity list
        (let [redirect-url (or safe-return-to
                               (str "/web/admin/" (name entity-name)))
              label (or (:label entity-config) (name entity-name))
              toast-json (str "{\"type\":\"success\",\"message\":\"" (escape-json-string (str label " deleted")) "\"}")]
          (-> (ring-response/response "")
              (ring-response/status 200)
              (ring-response/header "X-Toast" toast-json)
              (ring-response/header "HX-Redirect" redirect-url)))

        ; Failed to delete
        (-> (ring-response/response "")
            (ring-response/status 500))))))

(defn bulk-delete-handler
  "Handler for bulk deleting multiple entities.

   Expects form with 'ids[]' parameter containing entity IDs.
   Returns HTMX fragment triggering table refresh."
  [admin-service schema-provider _config]
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
          _ (shell-permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Extract IDs from form params
          id-strings (get-in request [:form-params "ids[]"])
          ids (when id-strings
                (mapv #(UUID/fromString %) (if (string? id-strings) [id-strings] id-strings)))

          ; Bulk delete
          result (when (and ids (seq ids))
                   (ports/bulk-delete-entities admin-service entity-name ids))
          success-count (or (:success-count result) 0)
          failed-count (or (:failed-count result) 0)

          ; Fetch updated list
          list-result (ports/list-entities admin-service entity-name {})
          records (:records list-result)
          total-count (:total-count list-result)
          table-query {:page-size (:page-size list-result)
                       :page (:page-number list-result)}
          permissions (permissions/get-entity-permissions user entity-name entity-config)

          ; Create toast message
          label (or (:label entity-config) (name entity-name))
          toast-msg (if (zero? failed-count)
                      (str success-count " " label " deleted")
                      (str success-count " " label " deleted, " failed-count " failed"))
          toast-json (str "{\"type\":\""
                          (if (zero? failed-count) "success" "warning")
                          "\",\"message\":\"" (escape-json-string toast-msg) "\"}")]

      ; Return table HTML fragment with toast via showToast event
      (-> (support/htmx-fragment-response request
                                          (admin-ui/entity-table entity-name records entity-config table-query total-count permissions {}))
          (ring-response/header "HX-Trigger" (str "{\"showToast\":" toast-json ",\"entityListUpdated\":{}}"))))))
