(ns wagoe.admin.core.ui.detail
  "Entity detail/edit page components: form widgets, field grouping, the
   entity form, parent-context/related-records tables, and the detail page.

   Pure Hiccup generators. Depends on `base` for URL and field helpers."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.cells :as cells]
            [wagoe.admin.core.forms :as forms]
            [wagoe.admin.core.schema-introspection :as introspection]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.form :as form]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.modal :as modal]
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

(defn- field-widget
  [field-name value field-config errors display]
  (let [widget-type (:widget field-config :text-input)
        label (:label field-config (text/capitalize (name field-name)))
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
       ;; A rejected value, shown exactly as it was typed. A date, datetime or
       ;; number input cannot hold arbitrary text: the date widget showed a
       ;; rejected `1990-05-17T10:00` as `1990-05-17`, a number input showed
       ;; `forty` as empty, and submitting again saved a value the user never
       ;; typed (BOU-521 review). Plain text keeps the error and its cause
       ;; side by side until the user fixes it.
       (and has-errors? (string? value)
            (#{:date-input :datetime-input :number-input} widget-type))
       (ui/text-input field-name value
                      {:required     required?
                       :readonly     readonly?
                       :class        "form-control"
                       :aria-invalid "true"})

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

       ;; A foreign key with too many parents for a select
       (= widget-type :combobox)
       (combobox/combobox field-name
                          (assoc (:combobox field-config)
                                 :value       value
                                 :placeholder [:t :admin/combobox-placeholder]
                                 :required    required?
                                 :readonly    readonly?
                                 ;; with-live-validation and describe-input find no
                                 ;; named visible control here.
                                 :value-attrs (when-let [{:keys [url vals]} (:validate field-config)]
                                                (form/live-attrs url field-name vals))
                                 :attrs       (when has-errors?
                                                {:aria-invalid     "true"
                                                 :aria-describedby (ui/field-error-id field-name)})))

       ;; Textarea
       (= widget-type :textarea)
       (ui/textarea field-name value
                    {:placeholder placeholder
                     :required required?
                     :readonly readonly?
                     :class "form-control"
                     :rows (or (:rows field-config) 4)})

       ;; Date/time inputs. The stored value is coerced to the shape the widget
       ;; accepts — handed a full ISO instant, a date input silently drops it
       ;; and renders empty (BOU-504).
       (= widget-type :date-input)
       (ui/text-input field-name (base/format-for-date-input value)
                      {:type "date"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :datetime-input)
       (let [{:keys [input-zone server-zone]} (base/form-zones display)
             formatted (base/format-for-datetime-input value input-zone server-zone)]
         [:span.datetime-with-zone
          (ui/text-input field-name formatted
                         (cond-> {:type "datetime-local"
                                  :required required?
                                  :readonly readonly?
                                  :class "form-control"}
                           ;; Without it a value with seconds fails the
                           ;; default step=60 and the form will not submit.
                           (base/datetime-input-step formatted)
                           (assoc :step (base/datetime-input-step formatted))))
          ;; The widget carries no zone, so say which one it is in.
          [:small.field-zone (str input-zone)]
          ;; …and no offset. When the clocks go back a local time happens
          ;; twice; this tells the parser which of the two was shown.
          (when-let [offset (base/datetime-input-offset value input-zone server-zone)]
            [:input {:type "hidden" :name (str "__offset." (name field-name)) :value offset}])])

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
     (ui/field-errors (ui/field-error-id field-name) errors)]))

(defn render-field-widget
  "Render input widget for field based on its configuration.

   Args:
     field-name: Keyword field name
     value: Current field value
     field-config: Field configuration map
     errors: Optional collection of error messages for this field
     display: Optional display map; a datetime is shown in its zone (BOU-523)

   Returns:
     Hiccup form field structure"
  ([field-name value field-config errors]
   (render-field-widget field-name value field-config errors nil))
  ([field-name value field-config errors display]
   (let [{:keys [url vals]} (:validate field-config)]
     (cond-> (field-widget field-name value field-config errors display)
       url          (form/with-live-validation field-name url vals)
       ;; Ties the input to its error container for screen readers (BOU-398).
       (seq errors) (ui/describe-input field-name (ui/field-error-id field-name))))))

(defn with-live-validation
  "`entity-config` with each field the user can change set to be checked on
   the server when it changes: the form posts to validate-field, which answers
   with the field again. `record-id` is nil on a create form."
  [entity-config entity-name record-id]
  (let [url  (str "/web/admin/" (name entity-name) "/validate-field")
        vals (when record-id {"__id" (str record-id)})]
    (update entity-config :fields update-vals
            (fn [cfg]
              (cond-> cfg
                (not (or (:readonly cfg) (#{:hidden :file-input} (:widget cfg))))
                (assoc :validate {:url url :vals vals}))))))

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

(defn- blank-value?
  "Nothing to show: nil, an empty string, or an unset flag."
  [v]
  (or (nil? v) (false? v) (and (string? v) (str/blank? v))))

(defn- render-field-group
  "Render a single field group: its title beside its fields.

   A group without required fields is optional and folds away (`details`);
   it opens by itself when one of its fields holds a value or an error, so
   nothing filled in or wrong is hidden.

   Args:
     group: Group map with :id, :label, :fields
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map

   Returns:
     Hiccup group structure"
  [group entity-config record errors & [display]]
  (let [fields    (:fields group)
        optional? (not-any? #(true? (get-in entity-config [:fields % :required])) fields)
        open?     (some #(or (seq (get errors %)) (not (blank-value? (get record %)))) fields)
        body      [:div.form-fields
                   (for [field-name fields]
                     (render-field-widget field-name (get record field-name)
                                          (get-in entity-config [:fields field-name])
                                          (get errors field-name) display))]
        group-id  (name (:id group))]
    (if optional?
      [:details {:class         "form-section form-field-group form-section-optional"
                 :data-group-id group-id
                 :open          (boolean open?)}
       [:summary {:class "form-section-toggle"}
        [:div.form-section-header
         [:h3.form-section-title (:label group)]
         [:p.form-section-description [:t :admin/form-section-optional-short]]]
        [:span.form-section-toggle-icon (icons/icon :chevron-down {:size 16})]]
       body]
      [:div {:class "form-section form-field-group" :data-group-id group-id}
       [:div.form-section-header [:h3.form-section-title (:label group)]]
       body])))

;; =============================================================================
;; Child rows of a create form (BOU-570)
;; =============================================================================

(defn child-row
  "One child row of a create form: the fields of `rel` (see
   `forms/nested-relationships`) named for row `index`, and a remove button."
  [rel index values errors & [display]]
  (let [child-cfg (introspection/for-create (:entity-config rel))]
    [:div.child-row {:class "child-row" :data-index (str index)}
     [:div.form-fields {:class "form-fields"}
      (for [field (:fields rel)]
        (render-field-widget (keyword (forms/child-param (:entity rel) index field))
                             (get values field)
                             (get-in child-cfg [:fields field])
                             (get errors field)
                             display))]
     [:button.button.secondary {:type "button" "hx-on:click" "this.closest('.child-row').remove()"}
      (icons/icon :trash {:size 14})
      [:t :admin/button-remove-row]]]))

(defn- child-rows-section
  "The rows of one has-many a parent is created with, and a button that adds
   one through HTMX."
  [entity-name {:keys [rel rows row-errors too-few too-many]} display]
  (let [child   (name (:entity rel))
        list-id (str "child-rows-" child)]
    [:fieldset.child-rows {:class "child-rows form-section" :data-child child}
     [:legend.form-section-title (:label rel) " "
      [:span.form-meta-label [:t :admin/children-minimum {:n (:min rel)}]]]
     (when too-few
       [:div {:class "alert alert-error" :role "alert"}
        [:t :admin/children-too-few {:label (:label too-few) :n (:min too-few) :count (:count too-few)}]])
     (when too-many
       [:div {:class "alert alert-error" :role "alert"}
        [:t :admin/children-too-many {:label (:label too-many) :n (:max too-many) :count (:count too-many)}]])
     [:div.child-rows-list {:id list-id}
      (for [[index values] rows]
        (child-row rel index values (get row-errors index) display))]
     [:button.button.secondary {:type      "button"
                                :class     "gap-2"
                                :hx-get    (str "/web/admin/" (name entity-name) "/new/rows/" child)
                                :hx-target (str "#" list-id)
                                :hx-swap   "beforeend"}
      (icons/icon :plus {:size 16})
      [:t :admin/button-add-row {:entity (or (get-in rel [:entity-config :label]) (:label rel))}]]]))

(defn- link-field
  "A many-to-many relation on the form: checkboxes over its few records, or
   a multiple combobox. `forms/links-param` names it, so unticking every box
   still empties it."
  [k {:keys [rel chosen options url]} errors]
  (let [picked (set (map first chosen))]
    [:div.form-field {:class (str "full-width" (when (seq errors) " has-errors"))}
     (if options
       [:fieldset {:class "link-checkboxes" :id (name k)}
        [:legend (:label rel)]
        (for [[v l] options]
          [:label [:input {:type "checkbox" :name (name k) :value v :checked (contains? picked v)}] [:span l]])]
       (list
        [:label {:for (name k)} (:label rel)]
        (combobox/combobox k {:url          url
                              :multiple     true
                              :chosen       chosen
                              :placeholder  [:t :admin/combobox-placeholder]
                              :remove-label [:t :admin/link-remove]})))
     [:input {:type "hidden" :name forms/links-param :value (name k)}]
     (ui/field-errors (ui/field-error-id k) errors)]))

(defn- links-section
  "The many-to-many relations of the form, under their own heading."
  [links errors]
  (when (seq links)
    [:div {:class "form-section form-links"}
     [:div.form-section-header [:h3.form-section-title [:t :admin/form-section-links]]]
     [:div.form-fields {:class "form-fields"}
      (for [[k lf] links] (link-field k lf (get errors k)))]]))

(defn- record-summary
  "The read-only side of a record — its id, timestamps and other read-only
   fields, rendered by display role, and the records it links — and, filled
   in by the form as you type, what this edit changes. Blank fields show as
   a dash: that a user has no tenant is worth seeing too. Order: the key,
   the other values, then the timestamps."
  [entity-config record display & [links]]
  (let [pk       (:primary-key entity-config :id)
        hidden   (set (:hide-fields entity-config))
        order    (or (seq (:field-order entity-config)) (sort (keys (:fields entity-config))))
        fields   (->> (cons pk (filter (set (:readonly-fields entity-config)) order))
                      (filter #(contains? (:fields entity-config) %))
                      (remove hidden)
                      distinct)
        temporal? (fn [{:keys [field role]}] (and (not= field pk) (contains? #{:date :datetime} role)))
        columns  (let [cols (cells/columns entity-config fields)]
                   (concat (remove temporal? cols) (filter temporal? cols)))
        ctx      {:entity-config entity-config :display display :overview (:overview display)}]
    [:aside {:class "record-summary" :aria-label [:t :admin/record-summary]}
     (when (seq columns)
       [:dl {:class "record-summary-fields"}
        (for [{:keys [field config role label relation] :as column} columns
              :let [v (get record field)]]
          (list
           [:dt label]
           (if (or (nil? v) (and (string? v) (str/blank? v)))
             [:dd {:class "record-summary-blank"} "—"]
             [:dd {:class (:class column)}
              (cells/cell field record (assoc ctx :role role :field-config config :relation relation))])))
        (for [[_ {:keys [rel chosen]}] links]
          (list
           [:dt (:label rel)]
           (if (seq chosen)
             [:dd {:class "c-links"}
              (cells/link-chips (:entity rel) (map (fn [[id title]] {:id id :title title}) chosen) (count chosen))]
             [:dd {:class "record-summary-blank"} "—"])))])
     [:div {:class "record-summary-changes" :x-show "dirty > 0" :x-cloak true}
      [:h3 [:t :admin/changed-in-this-edit]]
      [:p {:x-text "changedText"}]]]))

(defn- save-bar
  "The bar at the foot of the form, sticky while the form scrolls: whether
   there are unsaved changes, and the actions. `dirty` is the form's count of
   changed fields (the dirtyForm component)."
  [entity-name is-edit? cancel-url]
  [:div {:class "form-actions save-bar" :role "region" :aria-label [:t :admin/save-bar-label]}
   [:span {:class "save-state" :aria-live "polite"}
    [:span {:class "save-dot" :aria-hidden "true" :x-bind:class "dirty > 0 ? 'is-dirty' : ''"}]
    [:span {:x-show "dirty === 0"} [:t :admin/save-bar-clean]]
    [:span {:x-show "dirty > 0" :x-cloak true}
     [:span {:x-text "dirty"}] " " [:t :admin/save-bar-unsaved]]]
   [:div {:class "save-actions"}
    [:button {:type "button" :class "button ghost" :x-show "dirty > 0" :x-cloak true
              :x-on:click "discard()"}
     [:t :admin/save-bar-discard]]
    [:a.button.secondary
     {:class "gap-2"
      :href (or cancel-url (str "/web/admin/" (name entity-name)))}
     [:t :common/button-cancel]
     [:kbd "u"]]
    [:button.button.primary {:class "gap-2" :type "submit"}
     (if is-edit? [:t :admin/button-update] [:t :admin/button-create])
     [:kbd {:class "save-kbd"} "⌘S"]]]
   ;; The keys that work with the cursor in a field, where the page starts
   ;; (the bare [ ] u only once Esc has left it). The previous / next hint
   ;; hides itself when there is no list to step through (CSS).
   [:span {:class "save-hint" :aria-hidden "true"}
    (when is-edit?
      [:span {:class "save-hint-nav"} [:kbd "⌥↑"] [:kbd "⌥↓"] " " [:t :admin/kbd-prev-next]])
    [:span [:kbd "Esc"] [:kbd "u"] " " [:t :admin/kbd-to-list]]
    (when is-edit?
      [:span [:kbd "⌘↵"] " " [:t :admin/save-and-back]])]])

(defn entity-form
  "Render entity create/edit form.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map
     permissions: Permission flags
     prefill: Optional starting values for a create form

    Returns:
      Hiccup form structure

   Notes:
     If :field-groups is configured, renders fields in grouped sections.
     Otherwise, renders flat list of editable fields."
  [entity-name entity-config record errors _permissions & [cancel-url display prefill nested links]]
  (let [primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        entity-config (-> (cond-> entity-config (nil? record) introspection/for-create)
                          (with-live-validation entity-name record-id))
        editable-fields (:editable-fields entity-config)
        values (or record prefill)
        field-groups (compute-field-groups entity-config)
        is-edit? (some? record)
        form-action-base (if is-edit?
                           (str "/web/admin/" (name entity-name) "/" record-id)
                           (str "/web/admin/" (name entity-name)))
        default-list-url (str "/web/admin/" (name entity-name))
        ; Preserve return_to through form submission so the update handler can redirect back.
        ; URL-encode the value: contextual list URLs frequently carry their own query params
        ; (filters, pagination, etc.), and a raw `&` would be parsed as a new top-level
        ; parameter on the PUT request, truncating return_to server-side.
        form-action (if (and cancel-url (not= cancel-url default-list-url))
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
             ;; Dirty tracking and ⌘S / Ctrl+S for the save bar (admin-ux.js)
             :x-data "dirtyForm"
             :x-on:input "track()"
             :x-on:change "track()"
             :x-on:keydown.meta.s.window.prevent "save()"
             :x-on:keydown.ctrl.s.window.prevent "save()"
             ;; The form's own save only: a field's live check bubbles up here too
             "hx-on::afterRequest" (str "if (event.detail.successful && event.detail.elt === this) { localStorage.setItem('"
                                        optional-details-key
                                        "', 'false'); }")}
            ;; After a successful edit, push the same URL (including return_to) so the
            ;; browser stays on the detail page with context intact
            (when is-edit?
              {:hx-push-url form-action
               ;; ⌘↵ / Ctrl+↵: save, then back to the list
               :x-on:keydown.meta.enter.window.prevent "saveAndBack()"
               :x-on:keydown.ctrl.enter.window.prevent "saveAndBack()"}))
     ;; No longer need hidden _method field since HTMX sends proper HTTP method
     ;; The zone the :instant fields above were rendered in. The handler parses
     ;; them back in exactly this zone; reading it from the cookie instead would
     ;; shift every value on the first visit, when the page was rendered before
     ;; the cookie existed (BOU-523).
     [:input {:type "hidden" :name "__zone" :value (str (:input-zone (base/form-zones display)))}]
     [:div.form-card {:class "form-card overflow-hidden"}
      [:div.form-card-body {:class "form-card-body space-y-4"}
       [:div.form-meta
        [:span.form-meta-label [:t :admin/form-required-fields-note]]]
       (cond
         ;; Priority 1: Use configured field groups if present
         field-groups
         [:div.form-sections {:class "form-sections"}
          (for [group field-groups]
            (render-field-group group entity-config values errors display))]

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
                    field-value (get values field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors display)))]]
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
                    field-value (get values field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors display)))]]]

         ;; Priority 3: Flat rendering (all fields in one section)
         :else
         [:div.form-fields {:class "form-fields"}
          (for [field-name editable-fields]
            (let [field-config (get-in entity-config [:fields field-name])
                  field-value (get values field-name)
                  field-errors (get errors field-name)]
              (render-field-widget field-name field-value field-config field-errors display)))])
       (links-section links errors)
       (when-not is-edit?
         (for [section nested]
           (child-rows-section entity-name section display)))]]
     ;; Beside the fields: what the record is, that the form cannot change
     (when is-edit? (record-summary entity-config record display links))
     ;; Outside the card: its overflow-hidden would stop the bar sticking
     (save-bar entity-name is-edit? cancel-url)]))

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
            (-> (name f) (str/replace #"-" " ") text/capitalize)]
           [:span.parent-context-field-value (str v)]]))]]))

(defn- new-child-url
  "The \"New <child>\" link of an editable has-many, or nil when the child's
   create form cannot take the parent: the FK is not on the form, the parent
   id is not the UUID the form prefills, or the child delegates to its own
   create flow, which reads neither the FK nor return_to."
  [relationship entity fk-param return-qs]
  (let [child-cfg (:entity-config relationship)
        parent-id (str (:parent-id relationship))]
    (when (and (:editable relationship)
               fk-param
               (some #{(keyword fk-param)} (:editable-fields child-cfg))
               (parse-uuid parent-id)
               (not (:create-redirect-url child-cfg)))
      (str (base/entity-create-url entity child-cfg) "?" fk-param "=" parent-id
           (when return-qs (str "&" return-qs))))))

(defn related-records-table
  "Render a table of related records for a has-many relationship.
   When :editable true, adds an Edit link per row and a link to create a child
   with its :foreign-key set to :parent-id. When :has-more? is set, the
   records are one page of more, and a link leads to the rest.

   Args:
     relationship: {:label \"Order Items\" :fields [...] :editable true
                    :foreign-key :order-id :parent-id \"...\" :return-to \"...\"
                    :has-more? true
                    :entity-config {...}} — the related entity's config, which
                   gives each cell its field type
     records:      vector of record maps (kebab-case keys)
     display:      Optional display options (zone/date patterns) for value
                   rendering

   Returns:
     Hiccup table structure"
  [relationship records & [display]]
  (let [fields    (:fields relationship)
        label     (:label relationship)
        editable? (:editable relationship)
        entity    (name (:entity relationship))
        return-qs (when-let [rt (:return-to relationship)]
                    (str "return_to=" (base/url-encode rt)))
        ;; The create form names its fields in kebab-case; a snake_case
        ;; :foreign-key would not match one.
        fk-param  (some-> (:foreign-key relationship) name (str/replace "_" "-"))
        new-url   (new-child-url relationship entity fk-param return-qs)]
    [:div.related-records {:class "space-y-3 mt-6"}
     [:h2.section-title label]
     (when new-url
       [:a.button.secondary {:class "gap-2" :href new-url}
        (icons/icon :plus {:size 16})
        [:t :admin/button-new {:entity (or (get-in relationship [:entity-config :label]) label)}]])
     (if (empty? records)
       [:p.empty-state [:t :admin/relationship-empty-state {:label label}]]
       [:div.table-wrapper
        [:table.related-table
         [:thead
          [:tr
           (for [f fields]
             [:th (-> (name f) (str/replace #"-" " ") text/capitalize)])
           (when editable?
             [:th])]]
         [:tbody
          (for [record records]
            [:tr
             (for [f fields]
               ;; Rendered by field type, like a list table: a timestamp here
               ;; used to print as the raw database value (BOU-382).
               [:td (if-some [v (get record f)]
                      (base/render-field-value f v (get-in relationship [:entity-config :fields f]) display)
                      "—")])
             (when editable?
               [:td
                [:a.button.secondary
                 {:href (str "/web/admin/" entity "/" (:id record)
                             (when return-qs (str "?" return-qs)))}
                 [:t :common/button-edit]]])])]]])
     (when (and (:has-more? relationship) fk-param (some? (:parent-id relationship)))
       [:a.button.secondary {:href (base/filtered-list-url entity fk-param (:parent-id relationship))}
        [:t :admin/relationship-view-all {:label label}]])]))

(defn with-field-options
  "`entity-config` with each field in `field-options` rendered as a select
   over them — or, given {:url :label}, as a combobox reading its options
   there — unless the config chose a widget other than a text input."
  [entity-config field-options]
  (reduce-kv (fn [cfg field options]
               (cond-> cfg
                 (#{nil :text-input} (get-in cfg [:fields field :widget]))
                 (update-in [:fields field] merge
                            (if (map? options)
                              {:widget :combobox :combobox options}
                              {:widget :select :options options}))))
             entity-config
             (or field-options {})))

(defn- error-summary
  "Every error of a rejected form, by field label, above the form. A field
   the form does not show says so: its error has nowhere else to appear
   (BOU-570)."
  [entity-config errors]
  (when (seq errors)
    (let [off-form (forms/off-form-errors entity-config errors)]
      [:div.validation-errors {:role "alert"}
       [:h4 [:t :admin/form-errors-heading]]
       [:ul
        (for [[field messages] errors
              message (if (coll? messages) messages [messages])]
          [:li
           (or (get-in entity-config [:fields field :label]) (base/format-field-label field))
           (when (contains? off-form field) (list " " [:t :admin/form-error-not-on-form]))
           ": " (str message)])]])))

(defn entity-detail-page
  "Entity detail/edit page.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash, :related-records, :display, :workflow,
           and :field-options {field [[value label] ...]}, which turns a
           text input into a select (a foreign key's parent rows, BOU-563),
           and on a create form :nested [{:rel :rows [[index values]]
           :row-errors {index errors} :too-few {...}}], the child rows the
           parent is created with (BOU-570), and :links, the many-to-many
           relations as `link-fields` in the shell describes them (BOU-778)

   Returns:
     Hiccup page structure"
  [entity-name entity-config record errors permissions & [opts]]
  (let [{:keys [flash return-to sibling-nav]} opts
        entity-config (with-field-options entity-config (:field-options opts))
        label      (:label entity-config)
        singular   (introspection/singular-label entity-config entity-name)
        is-edit?   (some? record)
        page-title (if is-edit? [:t :admin/page-edit-title {:label singular}] [:t :admin/page-create-title {:label singular}])
        ;; What the record is called: the heading of an edit page
        pk         (:primary-key entity-config :id)
        title-f    (:title-field entity-config pk)
        heading    (when is-edit? (str (get record title-f (get record pk))))
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
       ;; The list is the way back: the breadcrumb, Cancel and the save bar
       [:div.page-breadcrumbs
        [:a {:href "/web/admin"} [:t :admin/breadcrumb-admin]]
        " / "
        [:a {:href list-url :data-action "list"} label]
        " / "
        [:span (if is-edit? heading page-title)]]
       [:div.page-header-actions {:class "flex flex-wrap items-center gap-2"}
        (when sibling-nav
          [:div.sibling-nav
           (if (:prev-url sibling-nav)
             [:a.button.secondary {:href (:prev-url sibling-nav) :title "Previous  [  ·  ⌥↑ in a field" :data-nav "prev"}
              (icons/icon :chevron-left {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-left {:size 14})])
           [:span.sibling-nav-count
            (:position sibling-nav) " / " (:total sibling-nav)]
           (if (:next-url sibling-nav)
             [:a.button.secondary {:href (:next-url sibling-nav) :title "Next  ]  ·  ⌥↓ in a field" :data-nav "next"}
              (icons/icon :chevron-right {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-right {:size 14})])])
        (when (and is-edit? (:can-create permissions))
          [:a.button.secondary
           {:class "gap-2"
            ;; `list-url` is the caller context (either return-to from the
            ;; query string or the plain list URL). Threading it through
            ;; ensures the delegated create flow lands the user back on
            ;; their filtered/paginated list on cancel or success.
            :href (base/entity-create-url entity-name entity-config list-url)}
           (icons/icon :plus {:size 14})
           [:t :admin/form-create-heading {:label singular}]])
        (when (and is-edit? (:can-delete permissions))
          ;; URL-encode return-to because contextual list URLs may already
          ;; carry their own query string (filters, pagination). Without
          ;; encoding, any `&` inside return-to would be parsed as additional
          ;; params on the delete request, so the server would drop the
          ;; original list state when redirecting back.
          (let [delete-url (cond-> (str "/web/admin/" (name entity-name) "/" (get record (:primary-key entity-config :id)))
                             return-to (str "?return_to=" (base/url-encode return-to)))]
            (list
             [:button.button.ghost
              (merge (modal/confirm-attrs "delete-record-confirm")
                     {:type "button"
                      :class "gap-2 button-danger-text"
                      :hx-delete delete-url
                      :hx-target "body"
                      :hx-swap "outerHTML"})
              (icons/icon :trash {:size 14})
              [:t :common/button-delete]]
             (modal/confirm-modal
              {:id            "delete-record-confirm"
               :title         [:t :admin/modal-confirm-delete-title]
               :message       [:t :admin/confirm-delete-record]
               :confirm-label [:t :common/button-delete]
               :danger?       true}))))]]
      ;; Edit: the record's name, with what this page is above it.
      ;; New: what this page is.
      (if is-edit?
        [:div.page-heading
         [:span.page-kicker page-title]
         [:h1.page-title heading]]
        [:h1.page-title page-title])
      (when-let [workflow (:workflow opts)]
        [:div.workflow-state-row
         [:span.workflow-state-label [:t :admin/column-workflow]] " "
         (base/workflow-state-link workflow)])]
     (when-let [ctx (:parent-context opts)]
       (parent-context-banner ctx))
     (error-summary entity-config errors)
     (entity-form entity-name entity-config record errors permissions list-url (:display opts) (:prefill opts)
                  (:nested opts) (:links opts))
     (when-let [related-records (:related-records opts)]
       (for [[rel records] related-records]
         (related-records-table rel records (:display opts))))]))

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
