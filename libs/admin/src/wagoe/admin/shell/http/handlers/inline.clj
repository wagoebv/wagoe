(ns wagoe.admin.shell.http.handlers.inline
  "Inline editing handlers (Week 2): per-field edit widget, update, cancel."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [ring.util.response :as ring-response]))

(defn inline-edit-widget-handler
  "Handler for GET /:entity/:id/:field/edit - returns inline edit form.

   Returns HTMX fragment with form widget for editing a single field."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Check permissions
          _ (shell-permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Verify field exists and is not readonly
          _ (when-not field-config
              (throw (ex-info "Field not found"
                              {:type :not-found
                               :field field})))

          readonly-fields (set (:readonly-fields entity-config))
          _ (when (contains? readonly-fields field)
              (throw (ex-info "Cannot edit readonly field"
                              {:type :forbidden
                               :field field})))

          ; Get current record
          record (ports/get-entity admin-service entity-name id)
          current-value (get record field)]

      ; Return inline edit form fragment
      (support/html-response request
                             (admin-ui/render-inline-edit-form entity-name id field current-value field-config
                                                            (support/display-options config request))))))

(defn update-field-handler
  "Handler for PATCH /:entity/:id/:field - updates single field.

   Validates and updates single field, returns updated cell HTML."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Check permissions
          _ (shell-permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Get new value from form
          raw-params (or (:form-params request)
                         (:body-params request)
                         (:params request)
                         {})

          ;; The same parsing as the full form, in the zone the inline form
          ;; was rendered in. This had its own copy of the conversions, and
          ;; with it every bug the full form had: "forty" stored as NULL, a
          ;; :date taking a time part, a datetime parsed in the wrong zone
          ;; (BOU-521, BOU-523).
          [zones params] (support/form-zone-options config request raw-params)
          field-value    (get params (name field))
          [data parse-errors] (support/parse-form-params-checked
                               {(name field) field-value} entity-config zones)
          parsed-value   (get data field)]

      (if (seq parse-errors)
        (-> (support/html-response request
                                   (admin-ui/render-inline-edit-form-with-error
                                    entity-name id field field-value field-config
                                    (get parse-errors field)
                                    {:zone-id        (:input-zone zones)
                                     :server-zone-id (:server-zone zones)
                                     :offsets        (:offsets zones)}))
            (assoc :status 422))
      (try
        ; Update single field
        (let [updated-record (ports/update-entity-field admin-service entity-name id field parsed-value)
              new-value (get updated-record field)]

          ; Return updated cell HTML with success indicator
          (-> (support/html-response request
                                     (admin-ui/render-inline-edit-cell entity-name id field new-value field-config
                                                                       (support/display-options config request)))
              (ring-response/header "HX-Trigger" "entityUpdated")))

        (catch Exception e
          (let [error-data (ex-data e)]
            (if (= (:type error-data) :validation-error)
              ; Return inline form with error message
              (support/html-response request
                                     (admin-ui/render-inline-edit-form-with-error
                                      entity-name id field field-value field-config
                                      (get-in error-data [:errors field] ["Validation failed"])
                                      {:zone-id        (:input-zone zones)
                                       :server-zone-id (:server-zone zones)
                                       :offsets        (:offsets zones)}))
              ; Re-throw other errors
              (throw e)))))))))

(defn cancel-inline-edit-handler
  "Handler for GET /:entity/:id/:field/cancel - cancels inline edit.

   Returns the original cell HTML without changes."
  [admin-service schema-provider config]
  (fn [request]
    (let [_user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Get current record
          record (ports/get-entity admin-service entity-name id)
          current-value (get record field)]

      ; Return original cell HTML
      (support/html-response request
                             (admin-ui/render-inline-edit-cell entity-name id field current-value field-config
                                                               (support/display-options config request))))))
