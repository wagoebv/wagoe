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

   Returns:
     Hiccup td element"
  [entity-name record-id field value field-config]
  (let [field-label (:label field-config (str/capitalize (name field)))]
    [:td {:class (str "field-" (name field) " editable")
          :data-label field-label
          :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
          :hx-trigger "dblclick"
          :hx-target "this"
          :hx-swap "innerHTML"
          :title [:t :admin/cell-dblclick-hint]}
     [:span.cell-content
      (base/render-field-value field value field-config)]
     [:span.inline-edit-hint
      (icons/icon :pencil {:size 14})
      " " [:t :common/button-edit]]]))

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
  [entity-name record-id field value field-config]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)
        _cancel-url (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")]
    [:form.inline-edit-form
     {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
      :hx-target "closest td"
      :hx-swap "outerHTML"
      :onsubmit "event.preventDefault(); htmx.trigger(this, 'submit');"}

     ; Render appropriate input widget
     (cond
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
  [entity-name record-id field value field-config errors]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)]
    [:div.inline-edit-error
     [:form.inline-edit-form
      {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
       :hx-target "closest td"
       :hx-swap "outerHTML"}

      ; Render input with error class
      (cond
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
