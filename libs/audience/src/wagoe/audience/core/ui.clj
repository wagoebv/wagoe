(ns wagoe.audience.core.ui
  "Pure Hiccup UI components for audience segments.
   No I/O, no side effects.")

;; =============================================================================
;; filter-badge
;; =============================================================================

(defn filter-badge
  "Renders a single filter as a compact badge.

   Args:
     filter-map - map with :type :field :op :value

   Returns:
     Hiccup [:span ...] element"
  [{:keys [type field op value]}]
  [:span.inline-flex.items-center.gap-1.rounded-full.bg-blue-50.px-2.py-1.text-xs.text-blue-700
   [:span.font-semibold (name type)]
   (when field [:span (name field)])
   [:span.text-blue-500 (name op)]
   [:span (str value)]])

;; =============================================================================
;; segment-card
;; =============================================================================

(defn segment-card
  "Renders a segment as a card.

   Args:
     segment - AudienceDefinition map with :id :label :description :filters
               :member-count :cached-at

   Returns:
     Hiccup [:div.rounded-lg ...] element"
  [{:keys [label description filters member-count cached-at]}]
  [:div.rounded-lg.border.border-gray-200.bg-white.p-4.shadow-sm
   [:div.flex.items-center.justify-between
    [:h3.text-lg.font-semibold label]
    [:span.text-sm.text-gray-500 (str "(" (or member-count 0) " members)")]]
   (when description
     [:p.mt-1.text-sm.text-gray-600 description])
   [:div.mt-3.flex.flex-wrap.gap-1
    (for [f filters]
      (filter-badge f))]
   (when cached-at
     [:p.mt-2.text-xs.text-gray-400 (str "Cached: " cached-at)])])

;; =============================================================================
;; segment-list
;; =============================================================================

(defn segment-list
  "Renders a list of segment cards.

   Args:
     segments - seq of AudienceDefinition maps

   Returns:
     Hiccup [:div.space-y-4 ...] element"
  [segments]
  [:div.space-y-4
   (for [seg segments]
     (segment-card seg))])

;; =============================================================================
;; builder-layout
;; =============================================================================

(defn builder-layout
  "Page layout for the audience builder.

   Renders a form with:
     - Name + description fields
     - Placeholder div for Replicant filter panel widget
     - Placeholder div for Replicant composition builder
     - Placeholder for live preview
     - Hidden input for serialized filter state

   Args:
     opts - map with optional key :segment (existing AudienceDefinition for edit mode)

   Returns:
     Hiccup [:div ...] element"
  [{:keys [segment]}]
  [:div.max-w-4xl.mx-auto.p-6
   [:h1.text-2xl.font-bold.mb-6 (if segment "Edit Audience" "New Audience")]
   [:form {:method "post" :action "/api/audiences"}
    [:div.space-y-4
     [:div
      [:label.block.text-sm.font-medium "Name"]
      [:input.mt-1.block.w-full.rounded-md.border-gray-300
       {:type "text" :name "label" :value (:label segment "")}]]
     [:div
      [:label.block.text-sm.font-medium "Description"]
      [:textarea.mt-1.block.w-full.rounded-md.border-gray-300
       {:name "description"} (:description segment "")]]
     ;; Placeholder div for Replicant filter panel widget
     [:div#audience-filter-panel.mt-6.min-h-32.border-2.border-dashed.border-gray-300.rounded-lg.p-4
      [:p.text-gray-400 "Filter builder will mount here"]]
     ;; Placeholder div for Replicant composition builder
     [:div#audience-composition-builder.mt-4.min-h-24.border-2.border-dashed.border-gray-300.rounded-lg.p-4
      [:p.text-gray-400 "Composition builder will mount here"]]
     ;; Placeholder for live preview
     [:div#audience-preview.mt-4
      [:p.text-gray-400 "Preview will appear here"]]
     ;; Hidden input for serialized filter state (populated by Replicant)
     [:input {:type "hidden" :name "filters-data" :id "audience-filters-data"}]
     [:div.mt-6.flex.gap-3
      [:button.rounded-md.bg-blue-600.px-4.py-2.text-white {:type "submit"} "Save"]
      [:a.rounded-md.bg-gray-100.px-4.py-2.text-gray-700 {:href "/web/audiences"} "Cancel"]]]]])
