(ns wagoe.admin.shell.http.handlers.inline
  "Inline editing handlers (Week 2): per-field edit widget, update, cancel."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [clojure.string :as str]
   [ring.util.response :as ring-response])
  (:import [java.util UUID]))

(defn parse-field-value
  "Parse a single field value from string to appropriate type.

   Helper function for inline editing - extracts type conversion logic
   from parse-form-params.

   Args:
     value: String value from form
     field-config: Field configuration map

   Returns:
     Typed value or nil"
  [value field-config]
  (let [field-type (:type field-config :string)]
    (cond
      ; Empty strings become nil
      (str/blank? value) nil

      ; Boolean checkbox values
      (= field-type :boolean)
      (contains? #{"on" "true" "1"} value)

      ; Integer values
      (= field-type :int)
      (parse-long value)

      ; Decimal values
      (= field-type :decimal)
      (bigdec value)

      ; UUID values
      (= field-type :uuid)
      (UUID/fromString value)

      ; Default: keep as string
      :else value)))

(defn inline-edit-widget-handler
  "Handler for GET /:entity/:id/:field/edit - returns inline edit form.

   Returns HTMX fragment with form widget for editing a single field."
  [admin-service schema-provider _config]
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
                             (admin-ui/render-inline-edit-form entity-name id field current-value field-config)))))

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

          ; Parse the field value using the same logic as full form parsing
          field-value (get raw-params (name field))
          parsed-value (parse-field-value field-value field-config)]

      (try
        ; Update single field
        (let [updated-record (ports/update-entity-field admin-service entity-name id field parsed-value)
              new-value (get updated-record field)]

          ; Return updated cell HTML with success indicator
          (-> (support/html-response request
                                     (admin-ui/render-inline-edit-cell entity-name id field new-value field-config
                                                                       (support/display-options config)))
              (ring-response/header "HX-Trigger" "entityUpdated")))

        (catch Exception e
          (let [error-data (ex-data e)]
            (if (= (:type error-data) :validation-error)
              ; Return inline form with error message
              (support/html-response request
                                     (admin-ui/render-inline-edit-form-with-error
                                      entity-name id field parsed-value field-config
                                      (get-in error-data [:errors field] ["Validation failed"])))
              ; Re-throw other errors
              (throw e))))))))

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
                                                               (support/display-options config))))))
