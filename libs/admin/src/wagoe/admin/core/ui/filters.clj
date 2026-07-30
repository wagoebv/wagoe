(ns wagoe.admin.core.ui.filters
  "Advanced filter builder components for admin entity lists.

   Pure Hiccup generators for the filter UI: operator selection per field
   type, value inputs, filter rows, and the overall filter builder."
  (:require [wagoe.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

(defn get-operators-for-field-type
  "Get available filter operators for a field type.

   Args:
     field-type: Keyword field type (:string, :int, :boolean, etc.)

   Returns:
     Vector of [operator-keyword display-label] tuples"
  [field-type]
  (case field-type
    :string [[:eq [:t :admin/filter-op-equals]]
             [:ne [:t :admin/filter-op-not-equals]]
             [:contains [:t :admin/filter-op-contains]]
             [:starts-with [:t :admin/filter-op-starts-with]]
             [:ends-with [:t :admin/filter-op-ends-with]]
             [:is-null [:t :admin/filter-op-is-empty]]
             [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    (:int :decimal) [[:eq [:t :admin/filter-op-equals]]
                     [:ne [:t :admin/filter-op-not-equals]]
                     [:gt [:t :admin/filter-op-greater-than]]
                     [:gte [:t :admin/filter-op-gte]]
                     [:lt [:t :admin/filter-op-less-than]]
                     [:lte [:t :admin/filter-op-lte]]
                     [:between [:t :admin/filter-op-between]]
                     [:is-null [:t :admin/filter-op-is-empty]]
                     [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    (:instant :date) [[:eq [:t :admin/filter-op-on-date]]
                      [:ne [:t :admin/filter-op-not-on-date]]
                      [:gt [:t :admin/filter-op-after]]
                      [:gte [:t :admin/filter-op-on-or-after]]
                      [:lt [:t :admin/filter-op-before]]
                      [:lte [:t :admin/filter-op-on-or-before]]
                      [:between [:t :admin/filter-op-between]]
                      [:is-null [:t :admin/filter-op-is-empty]]
                      [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    :boolean [[:eq [:t :admin/filter-op-equals]]]

    :enum [[:eq [:t :admin/filter-op-equals]]
           [:ne [:t :admin/filter-op-not-equals]]
           [:in [:t :admin/filter-op-any-of]]
           [:not-in [:t :admin/filter-op-none-of]]]

    ;; Default for unknown types
    [[:eq [:t :admin/filter-op-equals]]
     [:ne [:t :admin/filter-op-not-equals]]
     [:is-null [:t :admin/filter-op-is-empty]]
     [:is-not-null [:t :admin/filter-op-is-not-empty]]]))

(defn render-filter-value-inputs
  "Render value input(s) for a filter based on operator and field type.

   Args:
     field-name: Keyword field name
     field-config: Field configuration map
     operator: Current operator keyword
     filter-value: Current filter value map

   Returns:
     Hiccup input structure(s)"
  [field-name field-config operator filter-value]
  (let [field-type (:type field-config :string)
        options (:options field-config)]
    (cond
      ;; No input needed for null checks
      (#{:is-null :is-not-null} operator)
      nil

      ;; Between needs two inputs (min/max)
      (= operator :between)
      [:div.filter-value-range
       [:input.filter-value-input
        {:type (if (#{:int :decimal} field-type) "number" "text")
         :name (str "filters[" (name field-name) "][min]")
         :placeholder [:t :admin/filter-range-min]
         :value (get filter-value :min "")}]
       [:span.range-separator [:t :admin/filter-range-separator]]
       [:input.filter-value-input
        {:type (if (#{:int :decimal} field-type) "number" "text")
         :name (str "filters[" (name field-name) "][max]")
         :placeholder [:t :admin/filter-range-max]
         :value (get filter-value :max "")}]]

      ;; Multi-select for :in and :not-in operators
      (#{:in :not-in} operator)
      (if options
        ;; Enum field with predefined options - show multi-select
        [:select.filter-value-input
         {:name (str "filters[" (name field-name) "][values][]")
          :multiple true
          :size (min 5 (count options))}
         (for [[value label] options]
           [:option {:value (name value)
                     :selected (some #{value} (:values filter-value))}
            label])]
        ;; Free-form multi-value input (comma-separated)
        [:input.filter-value-input
         {:type "text"
          :name (str "filters[" (name field-name) "][values]")
          :placeholder "value1, value2, value3"
          :value (str/join ", " (or (:values filter-value) []))}])

      ;; Boolean field - dropdown
      (= field-type :boolean)
      [:select.filter-value-input
       {:name (str "filters[" (name field-name) "][value]")}
       [:option {:value ""} [:t :admin/select-placeholder]]
       [:option {:value "true" :selected (= (:value filter-value) true)} [:t :common/option-yes]]
       [:option {:value "false" :selected (= (:value filter-value) false)} [:t :common/option-no]]]

      ;; Enum field with options - dropdown
      (and (= field-type :enum) options)
      [:select.filter-value-input
       {:name (str "filters[" (name field-name) "][value]")}
       [:option {:value ""} [:t :admin/select-placeholder]]
       (for [[value label] options]
         [:option {:value (name value)
                   :selected (= (:value filter-value) value)}
          label])]

      ;; Date/instant fields - date input
      (#{:instant :date} field-type)
      [:input.filter-value-input
       {:type "date"
        :name (str "filters[" (name field-name) "][value]")
        :value (or (:value filter-value) "")}]

      ;; Numeric fields - number input
      (#{:int :decimal} field-type)
      [:input.filter-value-input
       {:type "number"
        :name (str "filters[" (name field-name) "][value]")
        :placeholder [:t :admin/filter-enter-value]
        :value (or (:value filter-value) "")}]

      ;; Default: text input
      :else
      [:input.filter-value-input
       {:type "text"
        :name (str "filters[" (name field-name) "][value]")
        :placeholder [:t :admin/filter-enter-value]
        :value (or (:value filter-value) "")}])))

(defn render-filter-row
  "Render a single filter row with field, operator, value inputs.

   Args:
     entity-name: Keyword entity name
     field-name: Keyword field name
     field-config: Field configuration map
     filter-value: Current filter value map (with :op, :value, etc.)

   Returns:
     Hiccup filter row structure"
  [entity-name field-name field-config filter-value]
  (let [field-type (:type field-config :string)
        operators (get-operators-for-field-type field-type)
        current-op (:op filter-value :eq)
        field-label (:label field-config (str/capitalize (name field-name)))]
    [:div.filter-row
     ;; Field name (read-only label)
     [:span.filter-field-label field-label]

      ;; Operator selector
     [:select.filter-operator-select
      {:name (str "filters[" (name field-name) "][op]")
       :hx-get (str "/web/admin/" (name entity-name) "/table")
       :hx-target "#filter-table-container"
       :hx-trigger "change"
       :hx-include "closest form"}
      (for [[op label] operators]
        [:option {:value (name op)
                  :selected (= current-op op)}
         label])]

     ;; Value input(s) - changes based on operator
     (render-filter-value-inputs field-name field-config current-op filter-value)

      ;; Remove filter button
     [:button.icon-button.secondary
      {:type "button"
       :aria-label [:t :admin/button-remove-filter]
       :hx-get (str "/web/admin/" (name entity-name) "/table")
       :hx-target "#filter-table-container"
       :hx-trigger "click"
       :hx-vals (str "{\"remove_filter\": \"" (name field-name) "\"}")
       :hx-include "closest form"}
      (icons/icon :x {:size 16})]]))

(defn render-filter-builder
  "Render advanced filter builder UI.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     current-filters: Map of current filters {field-name -> filter-spec}

   Returns:
     Hiccup filter builder structure"
  [entity-name entity-config current-filters]
  (let [;; Use :list-fields as the candidate set — those are the fields the user
        ;; already sees in the table. Fall back to all known fields for entities
        ;; without a manual :list-fields.
        candidate-fields (or (seq (:list-fields entity-config))
                             (keys (:fields entity-config)))
        filterable-fields (filter #(get-in entity-config [:fields % :filterable] true)
                                  candidate-fields)
        has-active-filters? (seq current-filters)
        current-filters (or current-filters {})]
    (when (or (seq filterable-fields) (seq current-filters))
      [:div.filter-builder
       [:div.filter-builder-header
        [:span.filter-builder-title [:t :admin/filters-title]]
        (when has-active-filters?
          [:button.text-button
           {:type "button"
            :hx-get (str "/web/admin/" (name entity-name) "/table")
            :hx-target "#filter-table-container"
            :hx-trigger "click"}
           (icons/icon :x {:size 14})
           " " [:t :admin/button-clear-all-filters]])]

      ;; Form wrapper for all filters
       [:form.filter-form
        {:hx-get (str "/web/admin/" (name entity-name) "/table")
         :hx-target "#filter-table-container"
         :hx-trigger "submit, change delay:500ms from:find input, change from:find select"
         :hx-push-url "true"}

      ;; Active filter rows
        (when has-active-filters?
          [:div.filter-rows
           (for [[field-name filter-value] current-filters]
             (when-let [field-config (get-in entity-config [:fields field-name])]
               (render-filter-row entity-name field-name field-config filter-value)))])

      ;; Add filter dropdown
        (when (seq filterable-fields)
          [:div.filter-add-row
           [:select.filter-field-select
            {:name "add_filter_field"
             :hx-get (str "/web/admin/" (name entity-name) "/table")
             :hx-target "#filter-table-container"
             :hx-trigger "change"
             :hx-include "closest form"}
            [:option {:value ""} [:t :admin/filter-add-option]]
            (for [field-name filterable-fields
                  :when (not (contains? current-filters field-name))]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-label (:label field-config (str/capitalize (name field-name)))]
                [:option {:value (name field-name)} field-label]))]])

      ;; Apply button (for manual submission if auto-trigger doesn't work)
        (when has-active-filters?
          [:button.button.primary {:type "submit"} [:t :admin/button-apply-filters]])]])))
