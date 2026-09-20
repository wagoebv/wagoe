(ns wagoe.admin.shell.http.handlers.crud
  "Create / update entity handlers."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [wagoe.shared.ui.core.validation :as ui-validation]
   [clojure.tools.logging :as log]))

(defn- client-safe-error-message
  "Message safe to show in an admin flash: a typed domain error whose mapped
   status is 4xx (same contract as the platform error path, BOU-161). Untyped,
   unmapped, or 5xx-mapped errors return nil — the caller shows a generic
   flash instead and the details stay in the server log (BOU-182).

   Handles both mapping shapes in combined-error-mappings: the platform's
   [status title] vectors and the admin map form {:status ...}."
  [e]
  (when-let [error-type (:type (ex-data e))]
    (let [mapping (get support/combined-error-mappings error-type)
          status  (cond
                    (vector? mapping) (first mapping)
                    (map? mapping)    (:status mapping))]
      (when (and status (< status 500))
        (ex-message e)))))

(defn- submitted-params
  "The fields the request carries, whichever decoder put them there.

   Ring leaves an empty `:form-params` on a request with a decoded body, and
   `{}` is truthy, so an `or` chain starting there never reached
   `:body-params`: a JSON write was read as no fields at all (BOU-477)."
  [request]
  (or (some #(when (seq %) %)
            [(:form-params request) (:body-params request) (:params request)])
      {}))

(defn create-entity-handler
  "Handler for creating new entity.

   Validates form data, creates entity, redirects to list with flash message."
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

          ; Check permissions
          _ (shell-permissions/assert-can-create-entity! user entity-name entity-config)

          form-data (support/parse-form-params (submitted-params request) entity-config)

          ; Validate data
          validation-result (ports/validate-entity-data admin-service entity-name form-data)]

      (if (:valid? validation-result)
        ; Create entity and return list page
        (try
          (let [_created-entity (ports/create-entity admin-service entity-name form-data)

                ; Fetch list page data
                entities (ports/list-available-entities schema-provider)
                entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

                ; Get entity list with default options
                result (ports/list-entities admin-service entity-name {})
                records (:records result)
                total-count (:total-count result)
                table-query {:page-size (:page-size result)
                             :page (:page-number result)}

                permissions (permissions/get-entity-permissions user entity-name entity-config)]

            ; Return list page HTML with success message
            (support/html-response request
                                   (admin-ui/admin-layout
                                    (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions
                                                               {:display (support/display-options config request)})
                                    {:user user
                                     :current-entity entity-name
                                     :entities entities
                                     :entity-configs entity-configs
                                     :logo-url (:logo-url config)
                                     :flash {:type :success
                                             :message [:t :admin/flash-created {:label (:label entity-config)}]}})))
          (catch Exception e
            (log/error e "Failed to create entity" {:entity entity-name})
            (let [entities (ports/list-available-entities schema-provider)
                  entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
                  permissions (permissions/get-entity-permissions user entity-name entity-config)]
              (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-detail-page entity-name entity-config form-data {} permissions {})
                                      {:user user
                                       :current-entity entity-name
                                       :entities entities
                                       :entity-configs entity-configs
                                       :logo-url (:logo-url config)
                                       :flash {:type :error
                                               ;; Only 4xx-mapped domain errors carry
                                               ;; client-safe messages; anything else is
                                               ;; internal — logged above, generic flash
                                               ;; (BOU-182: never echo raw exception text).
                                               :message (or (client-safe-error-message e)
                                                            [:t :admin/flash-create-failed
                                                             {:label (:label entity-config)}])}})))))

        ; Validation errors - re-render form
        (let [entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
              permissions (permissions/get-entity-permissions user entity-name entity-config)
              errors (ui-validation/explain->field-errors (:errors validation-result))]

          (-> (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-detail-page entity-name entity-config form-data errors permissions {})
                                      {:user user
                                       :current-entity entity-name
                                       :entities entities
                                       :entity-configs entity-configs
                                       :logo-url (:logo-url config)
                                       :flash {:type :error
                                               :message [:t :admin/flash-validation-errors]}}))
              ;; A form that was rejected is not a 200. The page is the same
              ;; either way, so the status is all that tells a caller —
              ;; anything that is not a browser — that nothing was written.
              (assoc :status 422)))))))

(defn update-entity-handler
  "Handler for updating existing entity.

   Validates form data, updates entity, redirects to list with flash message."
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

          form-data (support/parse-form-params (submitted-params request) entity-config)

          ;; An update is validated as the entity it would leave behind, not as
          ;; the fields the request happened to carry. Validating `form-data`
          ;; alone failed every partial write on the fields it did not mention
          ;; — and the rejection branch answered 200, so the caller saw success
          ;; and an unchanged record (BOU-477).
          existing (ports/get-entity admin-service entity-name id)

          ;; A PUT to an id that is not there used to validate the form, write
          ;; zero rows and answer 200. It is a 404, and saying so is also what
          ;; keeps the merge below honest: merging into nothing would report a
          ;; missing row as a form full of missing fields.
          _ (when-not existing
              (throw (ex-info "Entity not found"
                              {:type        :not-found
                               :entity-name entity-name
                               :id          id})))

          merged   (merge existing form-data)

          validation-result (ports/validate-entity-data admin-service entity-name merged)]

      (if (:valid? validation-result)
        ; Update entity and re-render detail page with success flash
        (let [updated-record (ports/update-entity admin-service entity-name id form-data)
              permissions    (permissions/get-entity-permissions user entity-name entity-config)
              ctx             (support/build-entity-detail-opts admin-service schema-provider config entity-name entity-config updated-record request)]

          (support/html-response request
                                 (admin-ui/admin-layout
                                  (admin-ui/entity-detail-page entity-name entity-config updated-record {} permissions
                                                               (assoc (:page-opts ctx) :flash
                                                                      {:type :success
                                                                       :message [:t :admin/flash-updated {:label (:label entity-config)}]}))
                                  {:user user
                                   :current-entity entity-name
                                   :entities (:entities ctx)
                                   :entity-configs (:entity-configs ctx)
                                   :logo-url (:logo-url config)})))

        ; Validation errors - re-render form with flash inside page content
        (let [permissions (permissions/get-entity-permissions user entity-name entity-config)
              errors      (ui-validation/explain->field-errors (:errors validation-result))
              ctx         (support/build-entity-detail-opts admin-service schema-provider config entity-name entity-config merged request)]

          (-> (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-detail-page entity-name entity-config merged errors permissions
                                                                   (assoc (:page-opts ctx) :flash
                                                                          {:type :error
                                                                           :message [:t :admin/flash-validation-errors]}))
                                      {:user user
                                       :current-entity entity-name
                                       :entities (:entities ctx)
                                       :entity-configs (:entity-configs ctx)
                                       :logo-url (:logo-url config)}))
              (assoc :status 422)))))))
