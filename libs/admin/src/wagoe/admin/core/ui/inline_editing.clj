(ns wagoe.admin.core.ui.inline-editing
  "Inline (double-click) table-cell editing components.

   Pure Hiccup generators for the editable-cell display mode, the inline
   edit form, and the inline edit form with a validation error."
  (:require [wagoe.admin.core.ui.base :as base]
            [wagoe.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

(defn render-inline-edit-cell
  "Render an editable table cell (normal display mode).

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current field value
     field-config: Field configuration map
     display: Optional display options (zone/date patterns)

   Returns:
     Hiccup td element"
  [entity-name record-id field value field-config & [display]]
  (let [field-label (:label field-config (str/capitalize (name field)))]
    [:td {:class (str "field-" (name field) " editable")
          :data-label field-label
          :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
          :hx-trigger "dblclick"
          :hx-target "this"
          :hx-swap "innerHTML"
          :title [:t :admin/cell-dblclick-hint]}
     [:span.cell-content
      (base/render-field-value field value field-config display)]
     [:span.inline-edit-hint
      (icons/icon :pencil {:size 14})
      " " [:t :common/button-edit]]]))

(defn- temporal-inline-input
  "The date or datetime input for an inline edit, or nil for any other widget.

   These rendered as a text box holding the raw database value — for a
   PostgreSQL timestamp `2026-09-01 12:00:50.0`, the server's wall time — which
   was then parsed back in the browser's zone and moved the value (BOU-523).
   They render as in the full form: the widget's own shape, in the zone the
   hidden `__zone` field names, with the offset for a repeated local time.

   `rejected?` — the value is what the user just submitted and was refused:
   shown as typed, as plain text, since the widgets cannot hold an arbitrary
   string. Only that case. A stored value is always formatted, even when it is
   a string: SQLite returns instants as `…Z` strings, which a datetime-local
   drops and shows empty."
  [field widget-type value required? display rejected?]
  (let [{:keys [input-zone server-zone]} (base/form-zones display)]
    (cond
      (not (#{:datetime-input :date-input} widget-type))
      nil

      rejected?
      [:span.inline-temporal
       [:input.inline-input.error
        {:type "text" :name (name field) :value (str value)
         :required required? :autofocus true :aria-invalid "true"}]
       ;; The offset the user submitted, passed on. Without it, re-submitting a
       ;; refused second occurrence of a repeated local time (the October hour
       ;; that happens twice) picked the first, an hour early.
       (when-let [offset (get-in display [:offsets field])]
         [:input {:type "hidden" :name (str "__offset." (name field)) :value offset}])]

      (= widget-type :datetime-input)
      (let [formatted (base/format-for-datetime-input value input-zone server-zone)
            offset    (base/datetime-input-offset value input-zone server-zone)]
        [:span.inline-temporal
         [:input.inline-input
          (cond-> {:type "datetime-local" :name (name field) :value (str formatted)
                   :required required? :autofocus true}
            (base/datetime-input-step formatted) (assoc :step (base/datetime-input-step formatted)))]
         [:small.field-zone (str input-zone)]
         (when offset
           [:input {:type "hidden" :name (str "__offset." (name field)) :value offset}])])

      :else
      [:input.inline-input
       {:type "date" :name (name field) :value (str (base/format-for-date-input value))
        :required required? :autofocus true}])))

(defn- zone-field
  "The hidden field naming the zone a datetime above was rendered in."
  [display]
  [:input {:type "hidden" :name "__zone" :value (str (:input-zone (base/form-zones display)))}])

(defn render-inline-edit-form
  "Render inline edit form for a single field.

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current field value
     field-config: Field configuration map

   Returns:
     Hiccup form structure"
  [entity-name record-id field value field-config & [display]]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)
        _cancel-url (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")]
    [:form.inline-edit-form
     {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
      :hx-target "closest td"
      :hx-swap "outerHTML"
      :onsubmit "event.preventDefault(); htmx.trigger(this, 'submit');"}
     (zone-field display)

     ; Render appropriate input widget
     (cond
       (#{:datetime-input :date-input} widget-type)
       (temporal-inline-input field widget-type value required? display false)

       (= widget-type :checkbox)
       [:input {:type "checkbox"
                :name (name field)
                :checked (boolean value)
                :autofocus true}]

       (= widget-type :textarea)
       [:textarea.inline-input
        {:name (name field)
         :required required?
         :autofocus true
         :rows 2}
        (str value)]

       (= widget-type :number-input)
       [:input.inline-input
        {:type "number"
         :name (name field)
         :value (str value)
         :required required?
         :autofocus true}]

       ; Default: text input
       :else
       [:input.inline-input
        {:type "text"
         :name (name field)
         :value (str value)
         :required required?
         :autofocus true}])

     ; Action buttons
     [:span.inline-actions
      [:button.inline-save {:type "submit" :title [:t :admin/inline-save]}
       (icons/icon :check {:size 14})]
      [:button.inline-cancel
       {:type "button"
        :title [:t :admin/inline-cancel]
        :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")
        :hx-target "closest td"
        :hx-swap "outerHTML"}
       (icons/icon :x {:size 14})]]]))

(defn render-inline-edit-form-with-error
  "Render inline edit form with validation error.

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current (invalid) field value
     field-config: Field configuration map
     errors: Collection of error messages

   Returns:
     Hiccup form structure with error display"
  [entity-name record-id field value field-config errors & [display]]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)]
    [:div.inline-edit-error
     [:form.inline-edit-form
      {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
       :hx-target "closest td"
       :hx-swap "outerHTML"}
      (zone-field display)

      ; Render input with error class
      (cond
        (#{:datetime-input :date-input} widget-type)
        (temporal-inline-input field widget-type value required? display true)

        (= widget-type :checkbox)
        [:input {:type "checkbox"
                 :name (name field)
                 :checked (boolean value)
                 :class "error"
                 :autofocus true}]

        (= widget-type :textarea)
        [:textarea.inline-input.error
         {:name (name field)
          :required required?
          :autofocus true
          :rows 2}
         (str value)]

        (= widget-type :number-input)
        [:input.inline-input.error
         {:type "number"
          :name (name field)
          :value (str value)
          :required required?
          :autofocus true}]

        ; Default: text input
        :else
        [:input.inline-input.error
         {:type "text"
          :name (name field)
          :value (str value)
          :required required?
          :autofocus true}])

      ; Action buttons
      [:span.inline-actions
       [:button.inline-save {:type "submit" :title [:t :admin/inline-save]}
        (icons/icon :check {:size 14})]
       [:button.inline-cancel
        {:type "button"
         :title [:t :admin/inline-cancel]
         :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")
         :hx-target "closest td"
         :hx-swap "outerHTML"}
        (icons/icon :x {:size 14})]]]

     ; Error message
     [:div.inline-error-message
      (str/join ", " errors)]]))
