(ns wagoe.admin.core.ui.filters
  "Advanced filter builder components for admin entity lists.

   Pure Hiccup generators for the filter UI: operator selection per field
   type, value inputs, filter rows, and the overall filter builder."
  (:require [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.table :as table-ui]
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

(defn- same-value?
  "Whether a filter's value is this option. The value arrives from the query
   string as a string; the config names options as keywords."
  [filter-value option]
  (let [as-str #(if (keyword? %) (name %) (str %))]
    (and (some? filter-value) (= (as-str filter-value) (as-str option)))))

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
                     :selected (boolean (some #(same-value? % value) (:values filter-value)))}
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
       [:option {:value "true" :selected (same-value? (:value filter-value) true)} [:t :common/option-yes]]
       [:option {:value "false" :selected (same-value? (:value filter-value) false)} [:t :common/option-no]]]

      ;; Enum field with options - dropdown
      (and (= field-type :enum) options)
      [:select.filter-value-input
       {:name (str "filters[" (name field-name) "][value]")}
       [:option {:value ""} [:t :admin/select-placeholder]]
       (for [[value label] options]
         [:option {:value (name value)
                   :selected (same-value? (:value filter-value) value)}
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
       :hx-swap "outerHTML"
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
       :hx-swap "outerHTML"
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
        ;; A has-many count column (ADR-040) is not a column of the table
        filterable-fields (filter #(and (contains? (:fields entity-config) %)
                                        (get-in entity-config [:fields % :filterable] true))
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
            :hx-swap "outerHTML"
            :hx-trigger "click"}
           (icons/icon :x {:size 14})
           " " [:t :admin/button-clear-all-filters]])]

      ;; Form wrapper for all filters
       [:form.filter-form
        {:hx-get (str "/web/admin/" (name entity-name) "/table")
         :hx-target "#filter-table-container"
         :hx-swap "outerHTML"
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
             :hx-swap "outerHTML"
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

;; =============================================================================
;; Filter summary and filter drawer
;; =============================================================================

;; Above the table, one line: a Filter button with the number of active
;; filters, each active filter as a chip that removes itself, and Clear all.
;; The editing happens in a drawer on the right, every field under the other,
;; in the slot the record drawer uses — one panel at a time.

(defn blank-filter?
  "A filter spec that filters nothing: no value, no values, no bounds, and not
   an is-empty check. The drawer sends every field; these are the untouched
   ones."
  [{:keys [op value values min max] :as spec}]
  (and (map? spec)
       (not (#{:is-null :is-not-null} op))
       (str/blank? (str value))
       (not-any? #(not (str/blank? (str %))) (if (sequential? values) values [values]))
       (str/blank? (str min))
       (str/blank? (str max))))

(defn active-filters
  "`filters` without the blank ones."
  [filters]
  (into {} (remove (comp blank-filter? val)) filters))

(defn filterable-fields
  "The fields a list can filter on: its columns (all fields without
   :list-fields) that are columns of the table and not :filterable false."
  [entity-config]
  (filter #(and (contains? (:fields entity-config) %)
                (get-in entity-config [:fields % :filterable] true))
          (or (seq (:list-fields entity-config))
              (keys (:fields entity-config)))))

(def ^:private op-symbols
  {:eq "=" :ne "≠" :gt ">" :gte "≥" :lt "<" :lte "≤"
   :contains "∋" :starts-with "^" :ends-with "$"})

(defn- value-label
  "How a filter value reads in a chip: an enum's option label, yes/no for a
   boolean, else the value."
  [field-config v]
  (let [s (if (keyword? v) (name v) (str v))]
    (or (some (fn [[k label]] (when (= s (name k)) label)) (:options field-config))
        (when (= :boolean (:type field-config))
          (if (= "true" s) [:t :common/option-yes] [:t :common/option-no]))
        s)))

(defn- chip-text
  "`Role = Admin`, `Amount 10 – 100`, `Status any of Sent, Paid`, `Notes is empty`."
  [field-config label {:keys [op value values min max] :or {op :eq}}]
  (let [ops (into {} (get-operators-for-field-type (:type field-config :string)))]
    (case op
      (:is-null :is-not-null) (list label " " (ops op))
      :between                (list label " " (str min) " – " (str max))
      (:in :not-in)           (list label " " (ops op) " "
                                    (interpose ", " (map #(value-label field-config %) values)))
      (list label " " (get op-symbols op (ops op)) " " (value-label field-config value)))))

(defn- table-url
  "The table fragment for `filters`, from page one, sorted as now. The search
   box is included by the caller (hx-include), so a chip keeps the search."
  [entity-name table-query filters]
  (str "/web/admin/" (name entity-name) "/table?"
       (table-ui/encode-query-params
        (merge (table-ui/table-query->params (assoc table-query :page 1 :search nil))
               (table-ui/search-filters->params filters)))))

(defn filter-summary
  "The line above the table: Filter (n), a chip per active filter, Clear all."
  [entity-name entity-config table-query current-filters]
  (let [current (active-filters current-filters)
        fields  (filterable-fields entity-config)
        swap    {:hx-target  "#filter-table-container"
                 :hx-swap    "outerHTML"
                 :hx-push-url "true"
                 :hx-include ".toolbar-search .search-input"}]
    (when (or (seq fields) (seq current))
      [:div {:class "filter-summary"}
       [:button {:type        "button"
                 :class       (str "button filter-open" (when (seq current) " has-filters"))
                 :data-filter-open "true"
                 :hx-get      (str "/web/admin/" (name entity-name) "/filters?"
                                   (table-ui/encode-query-params (table-ui/search-filters->params current)))
                 :hx-target   "#record-drawer-slot"
                 :hx-swap     "innerHTML"
                 :title       "f"}
        (icons/icon :filter {:size 14})
        [:t :admin/filters-title]
        (when (seq current) [:span {:class "filter-count"} (str (count current))])]
       (for [[field spec] current
             :let [fc (get-in entity-config [:fields field])
                   label (:label fc (str/capitalize (str/replace (name field) #"[-_]" " ")))]]
         [:span {:class "filter-chip"}
          [:span (chip-text fc label spec)]
          [:button (merge swap {:type       "button"
                                :aria-label [:t :admin/button-remove-filter]
                                :hx-get     (table-url entity-name table-query (dissoc current field))})
           (icons/icon :x {:size 12})]])
       (when (seq current)
         [:button (merge swap {:type  "button"
                               :class "text-button filter-clear"
                               :hx-get (table-url entity-name table-query {})})
          [:t :admin/button-clear-all-filters]])])))

(defn- drawer-field
  "One field in the filter drawer: its label, then operator and value."
  [entity-name field field-config spec]
  (let [operators (get-operators-for-field-type (:type field-config :string))
        op        (:op spec :eq)
        label     (:label field-config (str/capitalize (str/replace (name field) #"[-_]" " ")))]
    [:div {:class (str "filter-field" (when (and spec (not (blank-filter? spec))) " is-active"))}
     [:label {:class "filter-field-name" :for (str "filter-" (name field))} label]
     [:div {:class "filter-field-inputs"}
      (when (< 1 (count operators))
        [:select {:class      "filter-operator-select"
                  :name       (str "filters[" (name field) "][op]")
                  :aria-label [:t :admin/filter-operator {:field label}]
                  ;; A different operator may need different inputs (between,
                  ;; any of): the drawer re-renders with what is filled in
                  :hx-get     (str "/web/admin/" (name entity-name) "/filters")
                  :hx-include "closest form"
                  :hx-target  "#record-drawer-slot"
                  :hx-swap    "innerHTML"
                  :hx-trigger "change"}
         (for [[o l] operators]
           [:option {:value (name o) :selected (= o op)} l])])
      (when (= 1 (count operators))
        [:input {:type "hidden" :name (str "filters[" (name field) "][op]") :value (name op)}])
      (let [inputs (render-filter-value-inputs field field-config op spec)]
        ;; The first value input carries the label's id
        (if (and (vector? inputs) (map? (second inputs)))
          (update inputs 1 assoc :id (str "filter-" (name field)))
          inputs))]]))

(defn filter-drawer
  "The filter drawer: every filterable field, active ones first, each with
   operator and value. Apply sends them all; empty ones are no filter."
  [entity-name entity-config current-filters]
  (let [current (or current-filters {})          ; blank ones too: an operator just chosen
        active? #(not (blank-filter? (get current %)))
        fields  (filterable-fields entity-config)
        ordered (concat (filter #(and (current %) (active? %)) fields)
                        (remove #(and (current %) (active? %)) fields))]
    [:aside {:class           "record-drawer filter-drawer"
             :id              "record-drawer"
             :aria-labelledby "filter-drawer-title"}
     [:header {:class "drawer-header"}
      [:div {:class "drawer-heading"}
       [:h2 {:id "filter-drawer-title" :class "drawer-title"} [:t :admin/filters-title]]]
      [:div {:class "drawer-nav"}
       [:button {:type "button" :class "icon-button ghost drawer-close" :data-drawer "close"
                 :aria-label [:t :admin/drawer-close] :title "q"}
        (icons/icon :x {:size 16})]]]
     [:form {:class      "filter-drawer-form"
             :hx-get     (str "/web/admin/" (name entity-name) "/table")
             :hx-target  "#filter-table-container"
             :hx-swap    "outerHTML"
             :hx-push-url "true"
             :hx-include ".toolbar-search .search-input"
             "hx-on::after-request" "if (event.detail.successful && event.detail.elt === this) window.AdminUX.closeDrawer()"}
      [:div {:class "filter-fields"}
       (for [field ordered]
         (drawer-field entity-name field (get-in entity-config [:fields field]) (get current field)))]
      [:div {:class "drawer-footer"}
       [:button {:type "submit" :class "button primary"} [:t :admin/button-apply-filters] [:kbd "↵"]]
       [:button {:type      "button"
                 :class     "button ghost"
                 :hx-get    (str "/web/admin/" (name entity-name) "/table")
                 :hx-target "#filter-table-container"
                 :hx-swap   "outerHTML"
                 :hx-push-url "true"
                 :hx-include ".toolbar-search .search-input"
                 "hx-on::after-request" "if (event.detail.successful) window.AdminUX.closeDrawer()"}
        [:t :admin/button-clear-all-filters]]]]]))
