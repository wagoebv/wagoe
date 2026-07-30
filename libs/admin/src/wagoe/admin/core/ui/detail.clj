(ns wagoe.admin.core.ui.detail
  "Entity detail/edit page components: form widgets, field grouping, the
   entity form, parent-context/related-records tables, and the detail page.

   Pure Hiccup generators. Depends on `base` for URL and field helpers."
  (:require [wagoe.admin.core.ui.base :as base]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

;; =============================================================================
;; Entity Detail/Edit Components
;; =============================================================================

(defn- field-width-class
  "Determine CSS width class for a form field based on widget type.

   Args:
     widget-type: Keyword widget type

   Returns:
     String CSS class ('full-width' or 'half-width')"
  [widget-type]
  (if (#{:textarea :file-input :hidden} widget-type)
    "full-width"
    "half-width"))

(defn render-field-widget
  "Render input widget for field based on its configuration.

   Args:
     field-name: Keyword field name
     value: Current field value
     field-config: Field configuration map
     errors: Optional collection of error messages for this field

   Returns:
     Hiccup form field structure"
  [field-name value field-config errors]
  (let [widget-type (:widget field-config :text-input)
        label (:label field-config (str/capitalize (name field-name)))
        required? (:required field-config false)
        readonly? (:readonly field-config false)
        placeholder (:placeholder field-config)
        help-text (:help-text field-config)
        options (:options field-config)
        field-type (:type field-config :string)
        min (:min field-config)
        max (:max field-config)
        pattern (:pattern field-config)
        width-class (field-width-class widget-type)
        has-errors? (seq errors)]
    [:div.form-field {:class (str width-class
                                  (when required? " is-required")
                                  (when has-errors? " has-errors"))}
     [:label {:for (name field-name)}
      label
      (when required? [:span.required " *"])]
     (cond
       ;; Text input widgets
       (= widget-type :text-input)
       (ui/text-input field-name value
                      (merge {:placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when pattern {:pattern pattern})
                             (when (and (= field-type :string) min) {:minlength min})
                             (when (and (= field-type :string) max) {:maxlength max})))

       (= widget-type :email-input)
       (ui/email-input field-name value
                       {:placeholder placeholder
                        :required required?
                        :readonly readonly?
                        :class "form-control"})

       (= widget-type :password-input)
       (ui/password-input field-name value
                          {:placeholder placeholder
                           :required required?
                           :readonly readonly?
                           :class "form-control"
                           :autocomplete "new-password"})

       (= widget-type :url-input)
       (ui/text-input field-name value
                      {:type "url"
                       :placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :number-input)
       (ui/text-input field-name value
                      (merge {:type "number"
                              :placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when min {:min min})
                             (when max {:max max})))

       ;; Boolean input
       (= widget-type :checkbox)
       (list
        (ui/checkbox field-name value {:required required?
                                       :disabled readonly?
                                       :class "checkbox checkbox-sm"})
        (when help-text
          [:span.help-text help-text]))

       ;; Select/dropdown
       (= widget-type :select)
       (ui/select-field field-name options value
                        {:required required?
                         :disabled readonly?
                         :class "form-control"})

       ;; Textarea
       (= widget-type :textarea)
       (ui/textarea field-name value
                    {:placeholder placeholder
                     :required required?
                     :readonly readonly?
                     :class "form-control"
                     :rows (or (:rows field-config) 4)})

       ;; Date/time inputs
       (= widget-type :date-input)
       (ui/text-input field-name value
                      {:type "date"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :datetime-input)
       (ui/text-input field-name value
                      {:type "datetime-local"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; Color picker
       (= widget-type :color-input)
       (ui/text-input field-name value
                      {:type "color"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; File upload
       (= widget-type :file-input)
       [:input {:type "file"
                :id (name field-name)
                :name (name field-name)
                :required required?
                :disabled readonly?
                :class "form-control"}]

       ;; Hidden field
       (= widget-type :hidden)
       [:input {:type "hidden"
                :id (name field-name)
                :name (name field-name)
                :value (or value "")}]

       ;; Default fallback
       :else
       (ui/text-input field-name value
                      {:placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"}))

     (when help-text
       [:small.help-text help-text])
     (when (seq errors)
       [:div.field-errors
        (for [error errors]
          [:span.error error])])]))

;; =============================================================================
;; Field Grouping Helpers
;; =============================================================================

(defn- compute-field-groups
  "Compute field groups for form rendering.

   Takes configured :field-groups and :editable-fields and returns a vector
   of group maps with only the fields that are actually editable.
   Ungrouped editable fields are appended as a final 'Other' group.

   Args:
     entity-config: Entity configuration map with :field-groups, :editable-fields, :ui

   Returns:
     Vector of group maps: [{:id :identity :label \"Identity\" :fields [:email :name]} ...]
     Each group only contains fields that are in :editable-fields.
     Returns nil if no :field-groups configured (use flat rendering)."
  [entity-config]
  (when-let [field-groups (:field-groups entity-config)]
    (let [editable-set (set (:editable-fields entity-config []))
          ;; Filter each group's fields to only include editable ones
          groups-with-editable (for [group field-groups
                                     :let [editable-in-group (filterv editable-set (:fields group))]
                                     ;; Only include groups that have at least one editable field
                                     :when (seq editable-in-group)]
                                 (assoc group :fields editable-in-group))
          ;; Collect all fields that are in configured groups
          grouped-fields (set (mapcat :fields groups-with-editable))
          ;; Find ungrouped editable fields (preserve order from editable-fields)
          ungrouped-fields (filterv #(not (grouped-fields %))
                                    (:editable-fields entity-config []))]
      (if (seq ungrouped-fields)
        ;; Append "Other" group for ungrouped fields
        (let [other-label (get-in entity-config [:ui :field-grouping :other-label] [:t :admin/fieldgroup-other])]
          (conj (vec groups-with-editable)
                {:id :other
                 :label other-label
                 :fields ungrouped-fields}))
        ;; All fields are grouped
        (vec groups-with-editable)))))

(defn- render-field-group
  "Render a single field group with heading and fields.

   Args:
     group: Group map with :id, :label, :fields
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map

   Returns:
     Hiccup fieldset/group structure"
  [group entity-config record errors]
  [:div.form-field-group {:class "form-field-group"
                          :data-group-id (name (:id group))}
   [:h3.form-section-title (:label group)]
   [:div.form-fields
    (for [field-name (:fields group)]
      (let [field-config (get-in entity-config [:fields field-name])
            field-value (get record field-name)
            field-errors (get errors field-name)]
        (render-field-widget field-name field-value field-config field-errors)))]])

(defn entity-form
  "Render entity create/edit form.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map
     permissions: Permission flags

    Returns:
      Hiccup form structure

   Notes:
     If :field-groups is configured, renders fields in grouped sections.
     Otherwise, renders flat list of editable fields."
  [entity-name entity-config record errors _permissions & [cancel-url]]
  (let [editable-fields (:editable-fields entity-config)
        field-groups (compute-field-groups entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        is-edit? (some? record)
        form-action-base (if is-edit?
                           (str "/web/admin/" (name entity-name) "/" record-id)
                           (str "/web/admin/" (name entity-name)))
        default-list-url (str "/web/admin/" (name entity-name))
        ; Preserve return_to through form submission so the update handler can redirect back.
        ; URL-encode the value: contextual list URLs frequently carry their own query params
        ; (filters, pagination, etc.), and a raw `&` would be parsed as a new top-level
        ; parameter on the PUT request, truncating return_to server-side.
        form-action (if (and is-edit? cancel-url (not= cancel-url default-list-url))
                      (str form-action-base "?return_to=" (base/url-encode cancel-url))
                      form-action-base)
        _form-method (if is-edit? "PUT" "POST")
        hx-attr (if is-edit? :hx-put :hx-post)
        required-fields (filter (fn [field-name]
                                  (true? (get-in entity-config [:fields field-name :required])))
                                editable-fields)
        optional-fields (filter (fn [field-name]
                                  (not (true? (get-in entity-config [:fields field-name :required]))))
                                editable-fields)
        optional-details-key (str "admin_optional_details_open_" (name entity-name))]
    [:form.entity-form
     (merge {hx-attr form-action
             :hx-target "body"
             :hx-swap "outerHTML"
             "hx-on::afterRequest" (str "if (event.detail.successful) { localStorage.setItem('"
                                        optional-details-key
                                        "', 'false'); }")}
            ;; After a successful edit, push the same URL (including return_to) so the
            ;; browser stays on the detail page with context intact
            (when is-edit?
              {:hx-push-url form-action}))
     ;; No longer need hidden _method field since HTMX sends proper HTTP method
     [:div.form-card {:class "form-card overflow-hidden"}
      [:div.form-card-body {:class "form-card-body space-y-4"}
       [:div.form-meta
        [:span.form-meta-label [:t :admin/form-required-fields-note]]]
       (cond
         ;; Priority 1: Use configured field groups if present
         field-groups
         [:div.form-sections {:class "form-sections"}
          (for [group field-groups]
            (render-field-group group entity-config record errors))]

         ;; Priority 2: Split into required/optional sections if both exist
         (and (seq required-fields) (seq optional-fields))
         [:div.form-sections {:class "form-sections"}
          [:div.form-section {:class "form-section"}
           [:div.form-section-header {:class "form-section-header"}
            [:h3.form-section-title [:t :admin/form-section-required]]
            [:p.form-section-description [:t :admin/form-section-required-description]]]
           [:div.form-fields {:class "form-fields"}
            (for [field-name required-fields]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-value (get record field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors)))]]
          [:details.form-section.form-section-optional
           {:class "form-section form-section-optional"
            :x-data (str "collapsibleOptionalFields('" optional-details-key "')")
            :x-init "init()"
            :x-bind:open "open"
            :x-on:toggle "onToggle($event)"}
           [:summary.form-section-toggle {:class "form-section-toggle"}
            [:div.form-section-header {:class "form-section-header"}
             [:h3.form-section-title [:t :admin/form-section-optional]]
             [:p.form-section-description [:t :admin/form-section-optional-description]]]
            [:span.form-section-toggle-icon
             (icons/icon :chevron-down {:size 16})]]
           [:div.form-fields {:class "form-fields"}
            (for [field-name optional-fields]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-value (get record field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors)))]]]

         ;; Priority 3: Flat rendering (all fields in one section)
         :else
         [:div.form-fields {:class "form-fields"}
          (for [field-name editable-fields]
            (let [field-config (get-in entity-config [:fields field-name])
                  field-value (get record field-name)
                  field-errors (get errors field-name)]
              (render-field-widget field-name field-value field-config field-errors)))])]
      [:div.form-actions {:class "form-actions justify-end border-t border-base-300 pt-4 mt-4"}
       [:button.button.primary {:class "gap-2" :type "submit"}
        (if is-edit? [:t :admin/button-update] [:t :admin/button-create])]
       [:a.button.secondary
        {:class "gap-2"
         :href (or cancel-url (str "/web/admin/" (name entity-name)))}
        [:t :common/button-cancel]]]]]))

(defn parent-context-banner
  "Renders a read-only info strip showing key fields from a parent record.
   Shown at the top of a child entity edit page (e.g. order-item → order).

   Args:
     ctx: {:config {:label \"Order\" :fields [:order-number :status ...]}
           :record  parent-record-map}"
  [{:keys [config record]}]
  (let [label  (:label config)
        fields (:fields config [])]
    [:div.parent-context-banner
     [:span.parent-context-label label]
     [:div.parent-context-fields
      (for [f fields]
        (when-let [v (get record f)]
          [:div.parent-context-field
           [:span.parent-context-field-label
            (-> (name f) (str/replace #"-" " ") str/capitalize)]
           [:span.parent-context-field-value (str v)]]))]]))

(defn related-records-table
  "Render a table of related records for a has-many relationship.
   When :editable true, adds an Edit link per row.

   Args:
     relationship: {:label \"Order Items\" :fields [...] :editable true}
     records:      vector of record maps (kebab-case keys)

   Returns:
     Hiccup table structure"
  [relationship records]
  (let [fields    (:fields relationship)
        label     (:label relationship)
        editable? (:editable relationship)
        entity    (name (:entity relationship))]
    [:div.related-records {:class "space-y-3 mt-6"}
     [:h2.section-title label]
     (if (empty? records)
       [:p.empty-state [:t :admin/relationship-empty-state {:label label}]]
       [:div.table-wrapper
        [:table.related-table
         [:thead
          [:tr
           (for [f fields]
             [:th (-> (name f) (str/replace #"-" " ") str/capitalize)])
           (when editable?
             [:th])]]
         [:tbody
          (for [record records]
            [:tr
             (for [f fields]
               [:td (or (get record f) "—")])
             (when editable?
               [:td
                [:a.button.secondary
                 {:href (str "/web/admin/" entity "/" (:id record)
                             (when-let [rt (:return-to relationship)]
                               (str "?return_to=" (base/url-encode rt))))}
                 [:t :common/button-edit]]])])]]])]))

(defn entity-detail-page
  "Entity detail/edit page.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash and :related-records

   Returns:
     Hiccup page structure"
  [entity-name entity-config record errors permissions & [opts]]
  (let [{:keys [flash return-to sibling-nav]} opts
        label      (:label entity-config)
        is-edit?   (some? record)
        page-title (if is-edit? [:t :admin/page-edit-title {:label label}] [:t :admin/page-create-title {:label label}])
        list-url   (or return-to (str "/web/admin/" (name entity-name)))]
    [:div.entity-detail-page {:class "space-y-4"}
     (when flash
       (let [flash-type (or (:type flash)
                            (first (keys flash)))
             flash-msg  (or (:message flash)
                            (first (vals flash)))]
         [:div {:class (str "alert alert-" (name flash-type))}
          flash-msg]))
     [:div.page-header {:class "space-y-3"}
      [:div.page-header-row
       [:div.page-breadcrumbs
        [:a {:href "/web/admin"} [:t :admin/breadcrumb-admin]]
        " / "
        [:a {:href list-url} label]
        " / "
        [:span page-title]]
       [:div.page-header-actions {:class "flex flex-wrap items-center gap-2"}
        [:a.button.secondary
         {:class "gap-2"
          :href list-url}
         (icons/icon :chevron-left {:size 16})
         [:t :admin/button-back-to-list]]
        (when sibling-nav
          [:div.sibling-nav
           (if (:prev-url sibling-nav)
             [:a.button.secondary {:href (:prev-url sibling-nav) :title "Previous"}
              (icons/icon :chevron-left {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-left {:size 14})])
           [:span.sibling-nav-count
            (:position sibling-nav) " / " (:total sibling-nav)]
           (if (:next-url sibling-nav)
             [:a.button.secondary {:href (:next-url sibling-nav) :title "Next"}
              (icons/icon :chevron-right {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-right {:size 14})])])
        (when is-edit?
          [:a.button.primary
           {:class "gap-2"
            ;; `list-url` is the caller context (either return-to from the
            ;; query string or the plain list URL). Threading it through
            ;; ensures the delegated create flow lands the user back on
            ;; their filtered/paginated list on cancel or success.
            :href (base/entity-create-url entity-name entity-config list-url)}
           (icons/icon :plus {:size 16})
           [:t :admin/form-create-heading {:label label}]])
        (when (and is-edit? (:can-delete permissions))
          ;; URL-encode return-to because contextual list URLs may already
          ;; carry their own query string (filters, pagination). Without
          ;; encoding, any `&` inside return-to would be parsed as additional
          ;; params on the delete request, so the server would drop the
          ;; original list state when redirecting back.
          (let [delete-url (cond-> (str "/web/admin/" (name entity-name) "/" (get record (:primary-key entity-config :id)))
                             return-to (str "?return_to=" (base/url-encode return-to)))]
            [:button.button.danger
             {:type "button"
              :class "gap-2"
              :hx-delete delete-url
              :hx-target "body"
              :hx-swap "outerHTML"
              :hx-confirm [:t :admin/confirm-delete-record]
              :data-confirm-title [:t :admin/modal-confirm-delete-title]
              :data-confirm-cancel [:t :admin/modal-button-cancel]
              :data-confirm-label [:t :admin/modal-button-delete]}
             (icons/icon :trash {:size 16})
             [:t :common/button-delete]]))]]
      [:h1.page-title page-title]]
     (when-let [ctx (:parent-context opts)]
       (parent-context-banner ctx))
     (when (seq errors)
       (ui/validation-errors errors))
     (entity-form entity-name entity-config record errors permissions list-url)
     (when-let [related-records (:related-records opts)]
       (for [[rel records] related-records]
         (related-records-table rel records)))]))

(defn entity-new-page
  "Entity creation page (convenience wrapper).

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash

   Returns:
     Hiccup page structure"
  [entity-name entity-config errors permissions & [opts]]
  (entity-detail-page entity-name entity-config nil errors permissions opts))
