(ns wagoe.admin.core.ui.list
  "Entity list-page components: search form, table rows, table, and the
   complete list page (toolbar + filter builder + table).

   Pure Hiccup generators. Depends on `base` for URL helpers, field-value
   rendering, and column sizing, and on `filters` for the filter builder."
  (:require [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.filters :as filters]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.alpine :as alpine]
            [clojure.string :as str]))

(defn entity-search-form
  "Compact inline search form for entity list filtering.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     current-search: Current search term (optional)
     current-filters: Current filter values (optional)

    Returns:
      Hiccup search form structure"
  [entity-name entity-config current-search _current-filters]
  (let [search-fields (:search-fields entity-config)
        has-search? (seq search-fields)]
    (when has-search?
      [:div.entity-search-form
       [:form {:hx-get (str "/web/admin/" (name entity-name) "/table")
               :hx-target "#entity-table-container"
               :hx-push-url "true"
               :hx-trigger "submit"}
        [:div.search-controls
         [:input {:type "text"
                  :name "search"
                  :placeholder [:t :admin/filter-placeholder-search {:fields (str/join ", " (map name search-fields))}]
                  :value (or current-search "")
                  :autofocus true
                  :class "search-input"}]
         [:button.icon-button {:type "submit" :aria-label [:t :common/button-search]}
          (icons/icon :search {:size 20})]
         (when (seq current-search)
           [:button.icon-button.secondary {:type "button"
                                           :aria-label [:t :admin/button-clear-search]
                                           :onclick (str "window.location.href='/web/admin/" (name entity-name) "';")}
            (icons/icon :x {:size 20})])]]])))

(defn entity-table-row
  "Generate entity table row.

   Args:
     entity-name: Keyword entity name
     record: Entity record map
     entity-config: Entity configuration map
     permissions: Permission flags for this entity

   Returns:
     Hiccup table row"
  [entity-name record entity-config permissions]
  (let [list-fields (:list-fields entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        readonly-fields (set (:readonly-fields entity-config))
        ;; The detail/edit handler is guarded by assert-can-edit-entity!, so only
        ;; wire the row-click navigation when the current user actually has
        ;; permission — otherwise clicking a data cell sends them to a 403.
        can-open? (boolean (:can-edit permissions))
        row-attrs (cond-> {:class (if can-open? "entity-row clickable-row" "entity-row")}
                    can-open?
                    (assoc :data-href (str "/web/admin/" (name entity-name) "/" record-id)))]
    [:tr row-attrs
     [:td.checkbox-cell
       ;; Alpine.js row checkbox with x-model binding to selectedIds array
      [:input (alpine/row-checkbox-attrs record-id)]]
     (for [field list-fields]
       (let [field-config (get-in entity-config [:fields field])
             field-label (:label field-config (str/capitalize (name field)))
             value (get record field)
             editable? (and (:can-edit permissions)
                            (not (contains? readonly-fields field))
                            (not= field primary-key))]
         (if editable?
           ; Editable cell with double-click to edit (Week 2)
           [:td {:class (str "field-" (name field) " editable")
                 :data-label field-label
                 :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
                 :hx-trigger "dblclick"
                 :hx-target "this"
                 :hx-swap "innerHTML"
                 :title [:t :admin/cell-dblclick-hint]}
            (base/render-field-value field value field-config)]
           ; Non-editable cell
           [:td {:class (str "field-" (name field))
                 :data-label field-label}
            (base/render-field-value field value field-config)])))
     [:td.actions-cell
      (when can-open?
        [:a.row-nav-hint
         {:href (str "/web/admin/" (name entity-name) "/" record-id)
          :aria-label [:t :common/button-edit]
          :tabindex 0}
         (icons/icon :chevron-right {:size 14})])]]))

(defn entity-table
  "Generate entity table with sorting and pagination.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters (sort, page, etc.)
     total-count: Total number of records
     permissions: Permission flags
     filters: Optional search filters

   Returns:
     Hiccup table structure"
  [entity-name records entity-config table-query total-count permissions & [filters]]
  (let [{:keys [sort dir page page-size]} table-query
        base-url (str "/web/admin/" (name entity-name) "/table")
        hx-target "#entity-table-container"
        table-params (table-ui/table-query->params table-query)
        filter-params (table-ui/search-filters->params (or filters {}))
        qs-map (merge table-params filter-params)
        hx-url (str base-url "?" (table-ui/encode-query-params qs-map))
        list-fields (:list-fields entity-config)]
    ;; Table container - Alpine.js scope is at parent entity-list-page level
    ;; MutationObserver automatically handles HTMX DOM updates (no afterSwap needed)
    [:div#entity-table-container
     {:hx-get hx-url
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target}
     (if (empty? records)
       [:div.empty-state {:class "p-10 text-center"}
        [:div.empty-state-icon
         (icons/icon :inbox {:size 48})]
        [:p {:class "mt-2 text-base-content/70"} [:t :admin/empty-state-no-records]]
        (when (:can-create permissions)
          [:a.button.primary
           {:class "mt-4"
            ;; Only build the contextual list URL when the entity delegates
            ;; creation — for generic entities the caller URL is discarded
            ;; and the extra work would be wasted.
            :href (base/entity-create-url entity-name entity-config
                                          (when (:create-redirect-url entity-config)
                                            (base/current-list-url entity-name table-query filters)))}
           [:t :admin/button-create-first-record]])]
       (let [pagination (table-ui/pagination {:table-query table-query
                                              :total-count total-count
                                              :base-url base-url
                                              :push-url-base (str "/web/admin/" (name entity-name))
                                              :hx-target hx-target
                                              :extra-params filters})
             has-pagination? (some? pagination)
             wrapper-class (if has-pagination? "table-and-pagination" "")]
         [:div {:class wrapper-class}
          [:div.table-wrapper
           ;; Form for checkbox submission (hidden inputs + table)
           [:form#table-form
            {:hx-post (str "/web/admin/" (name entity-name) "/bulk")
             :hx-target hx-target
             :hx-swap "outerHTML"}
            ;; Preserve table state
            (for [[k v] table-params]
              [:input {:type "hidden" :name k :value v}])
            (for [[k v] filter-params]
              [:input {:type "hidden" :name k :value v}])

            [:table.data-table {:class "data-table table"}
              ;; Column sizing for table-layout: fixed — framing columns via CSS
              ;; classes, data columns via proportional inline widths.
             [:colgroup
              [:col {:class "col-select"}]  ; Checkbox
              ;; Proportional widths derived from field type + name heuristic
              ;; (overridable via :width in the field config).
              (base/list-column-styles list-fields entity-config)
              [:col {:class "col-actions"}]]  ; Actions
             [:thead
              [:tr
               [:th {:class "checkbox-header"}
                ;; Alpine.js select-all checkbox with reactive binding
                [:input (alpine/select-all-checkbox-attrs)]]
               (for [field list-fields]
                 (let [field-config (get-in entity-config [:fields field])
                       sortable? (:sortable field-config true)]
                   (if sortable?
                     (table-ui/sortable-th {:label (:label field-config (str/capitalize (name field)))
                                            :field field
                                            :current-sort sort
                                            :current-dir dir
                                            :base-url base-url
                                            :push-url-base (str "/web/admin/" (name entity-name))
                                            :page page
                                            :page-size page-size
                                            :hx-target hx-target
                                            :hx-push-url? true
                                            :extra-params filters})
                     [:th (:label field-config (str/capitalize (name field)))])))
               [:th {:class "actions-header"} [:t :admin/column-actions]]]]
             [:tbody
              (for [record records]
                (entity-table-row entity-name record entity-config permissions))]]]]
          pagination]))]))

(defn entity-list-page
  "Complete entity list page with search, table, and actions.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters
     total-count: Total number of records
     permissions: Permission flags
     opts: Optional map with :search, :filters, :flash

   Returns:
     Hiccup page structure"
  [entity-name records entity-config table-query total-count permissions & [opts]]
  (let [{:keys [search filters flash]} opts
        label (:label entity-config)
        search-fields (:search-fields entity-config)
        has-search? (seq search-fields)
        search-value (:search search)]
    ;; Alpine.js bulk selection scope - wraps toolbar and table
    ;; selectedIds array is shared between delete button and checkboxes
    [:div.entity-list-page (merge (alpine/bulk-selection-attrs)
                                  {:class "space-y-4"})
     (when flash
       (for [[type message] flash]
         [:div {:class (str "alert alert-" (name type) " mb-2")} message]))

       ;; Compact toolbar: title + search + actions in one bar
     [:div.table-toolbar-container
      [:div.toolbar-row-actions
       [:div.record-meta
        [:h1.entity-list-title label]
        [:span.record-count-badge
         (str total-count " " label)]
        [:span.selection-count
         {:x-show "selectedIds.length > 0"
          :x-text "selectedIds.length + ' selected'"}]]

       (when has-search?
         [:div.toolbar-search
          [:input.search-input {:type "text"
                                :name "search"
                                :class "search-input"
                                :placeholder [:t :admin/filter-placeholder-search {:fields (str/join ", " (map name search-fields))}]
                                :value (or search-value "")
                                :hx-get (str "/web/admin/" (name entity-name) "/table")
                                :hx-target "#entity-table-container"
                                :hx-push-url "true"
                                :hx-trigger "keyup changed delay:300ms, search"
                                :hx-include "this"}]
          [:button.icon-button {:type "button"
                                :aria-label [:t :common/button-search]
                                :hx-get (str "/web/admin/" (name entity-name) "/table")
                                :hx-target "#entity-table-container"
                                :hx-push-url "true"
                                :hx-include "previous .search-input"}
           (icons/icon :search {:size 18})]
          (when (seq search-value)
            [:button.icon-button.secondary {:type "button"
                                            :aria-label [:t :admin/button-clear-search]
                                            :onclick (str "window.location.href='/web/admin/" (name entity-name) "';")}
             (icons/icon :x {:size 18})])])

       [:div.toolbar-actions
         ;; Delete button - Alpine.js reactively disables when nothing selected
        [:form#bulk-action-form
         {:hx-post (str "/web/admin/" (name entity-name) "/bulk-delete")
          :hx-target "#entity-table-container"
          :hx-swap "outerHTML"
          :hx-include "[name='ids[]']"}
         [:button.icon-button.danger
          (merge (alpine/delete-button-attrs)
                 {:type "submit"
                  :name "action"
                  :value "delete"
                  :id "bulk-delete-btn"
                  :form "bulk-action-form"
                  :aria-label [:t :admin/button-delete-selected]
                  :hx-confirm [:t :admin/confirm-delete-selected]
                  :data-confirm-title [:t :admin/modal-confirm-delete-title]
                  :data-confirm-cancel [:t :admin/modal-button-cancel]
                  :data-confirm-label [:t :admin/modal-button-delete]})
          (icons/icon :trash {:size 18})]]
        [:button.icon-button.ghost {:type "button"
                                    :aria-label [:t :admin/button-refresh]
                                    :hx-get (str "/web/admin/" (name entity-name) "/table")
                                    :hx-target "#entity-table-container"
                                    :hx-push-url "true"}
         (icons/icon :refresh {:size 18})]
        (when (:can-create permissions)
          [:a.button.primary
           {:class "gap-2"
            :href (base/entity-create-url entity-name entity-config
                                          (when (:create-redirect-url entity-config)
                                            (base/current-list-url entity-name table-query filters)))
            :aria-label (str "Create new " (name entity-name))}
           (icons/icon :plus {:size 18})
           [:t :admin/button-new {:entity (str/capitalize (name entity-name))}]])]]]

       ;; Filter builder + Table wrapper (THIS is the HTMX target for filter updates)
     [:div#filter-table-container {:class "space-y-3"}
      ;; Filter builder (will be updated by HTMX)
      (filters/render-filter-builder entity-name entity-config filters)
      ;; Table (will also be updated by HTMX)
      (entity-table entity-name records entity-config table-query total-count permissions filters)]]))
