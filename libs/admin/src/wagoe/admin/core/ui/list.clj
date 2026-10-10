(ns wagoe.admin.core.ui.list
  "Entity list-page components: search form, table rows, table, and the
   complete list page (toolbar + filter builder + table).

   Pure Hiccup generators. Depends on `base` for URL helpers, field-value
   rendering, and column sizing, and on `filters` for the filter builder."
  (:require [wagoe.admin.core.display :as display]
            [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.cells :as cells]
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
               :hx-swap "outerHTML"
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

(defn- workflow-column?
  "An entity with a workflow gets a column for its state (BOU-563)."
  [entity-config]
  (some? (:workflow entity-config)))

;; A list column, described once per table so a row does not re-derive its
;; role, label and relation for every cell (ADR-040).

(defn- humanize-column
  "The humanized key — without its `-id` for a relation, which shows a name."
  [field relation?]
  (base/format-field-label (cond-> (name field) relation? (str/replace #"[-_]id$" ""))))

(defn- columns
  "`fields` as list columns: {:field :config :role :class :label :relation :data?}.
   `:data?` is a column of the table, not a computed one: only those sort,
   filter and edit. The role comes with the config, or is derived now for a
   config built without it."
  [entity-config fields]
  (let [belongs-to (display/belongs-to-fields entity-config)
        has-many   (display/has-many-columns entity-config)]
    (mapv (fn [field]
            (let [config (get-in entity-config [:fields field])
                  role   (or (get-in entity-config [:display-roles field])
                             (display/display-role entity-config field))]
              {:field    field
               :config   config
               :role     role
               :class    (cells/cell-class role)
               :label    (or (:label config)
                             (:label (get has-many field))
                             (humanize-column field (contains? belongs-to field)))
               :relation (or (get belongs-to field) (get has-many field))
               :data?    (contains? (:fields entity-config) field)}))
          fields)))

(defn entity-table-row
  "Generate entity table row.

   Args:
     entity-name: Keyword entity name
     record: Entity record map
     entity-config: Entity configuration map
     permissions: Permission flags for this entity
     display: Optional display options (zone/date patterns) for value rendering
     cols: Optional list columns, when the caller built them for the table

   Returns:
     Hiccup table row"
  [entity-name record entity-config permissions & [display cols]]
  (let [primary-key (:primary-key entity-config :id)
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
     (for [{:keys [field config role label relation data?] :as column}
           (or cols (columns entity-config (:list-fields entity-config)))]
       (let [content (cells/cell field record
                                 {:role          role
                                  :field-config  config
                                  :entity-config entity-config
                                  :relation      relation
                                  :display       display
                                  :href          (when can-open?
                                                   (str "/web/admin/" (name entity-name) "/" record-id))})
             classes (str "field-" (name field) " " (:class column))
             editable? (and (:can-edit permissions)
                            data?
                            (not (contains? readonly-fields field))
                            (not= field primary-key))]
         (if editable?
           ; Editable cell with double-click to edit (Week 2)
           [:td {:class (str classes " editable")
                 :data-label label
                 :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
                 :hx-trigger "dblclick"
                 :hx-target "this"
                 :hx-swap "innerHTML"
                 :title [:t :admin/cell-dblclick-hint]}
            content]
           ; Non-editable cell
           [:td {:class classes
                 :data-label label}
            content])))
     (when (workflow-column? entity-config)
       [:td.field-workflow {:data-label [:t :admin/column-workflow]}
        (base/workflow-state-link (:admin/workflow record))])
     [:td.actions-cell
      (when can-open?
        [:a.row-nav-hint
         {:href (str "/web/admin/" (name entity-name) "/" record-id)
          :aria-label [:t :common/button-edit]
          :tabindex 0}
         (icons/icon :chevron-right {:size 14})])]]))

(defn totals-row
  "A footer with the page total of each `:total` money or number column, or
   nil when the list has none (ADR-040)."
  [records cols entity-config display]
  (let [totals (into {}
                     (keep (fn [{:keys [field config role]}]
                             (when (and (:total config) (#{:money :number} role))
                               (when-let [t (cells/page-total records field)]
                                 [field (cells/format-aggregate t role config entity-config display)]))))
                     cols)]
    (when (seq totals)
      [:tfoot
       [:tr {:class "totals-row"}
        [:td {:class "checkbox-cell"}]
        (for [[i {:keys [field] :as column}] (map-indexed vector cols)]
          [:td {:class (str "field-" (name field) " " (:class column))}
           (or (get totals field)
               (when (zero? i) [:span {:class "totals-label"} [:t :admin/page-total]]))])
        (when (workflow-column? entity-config) [:td])
        [:td {:class "actions-cell"}]]])))

(defn- facet-value
  "The value the list is filtered on for `facet`, as a string, or nil."
  [filters facet]
  (let [v (get filters facet)]
    (some-> (if (map? v) (when (= :eq (:op v :eq)) (:value v)) v)
            cells/value-name)))

(defn facet-tabs
  "The facet's values as tabs with their counts over the filtered set, the
   current one marked; `All` clears it (ADR-040). Nil without a facet."
  [entity-name entity-config table-query filters display]
  (let [facet  (:facet entity-config)
        counts (get-in display [:overview :facets facet])]
    (when (and facet (seq counts))
      (let [fc       (get-in entity-config [:fields facet])
            current  (facet-value filters facet)
            others   (dissoc filters facet)
            href     (fn [v]
                       (base/current-list-url entity-name (dissoc table-query :page)
                                              (cond-> others v (assoc facet v))))
            known    (map (comp name first) (:options fc))
            values   (distinct (concat known (sort (remove nil? (keys counts)))))
            tab      (fn [v label n]
                       (let [active?   (= v current)
                             page-url  (href v)
                             list-path (str "/web/admin/" (name entity-name))]
                         [:a {:href         page-url
                              :class        (str "facet-tab"
                                                 (when v (str " tone-" (name (cells/enum-tone fc v))))
                                                 (when active? " active"))
                              :aria-current (when active? "page")
                              ;; Through the fragment, into the filter container:
                              ;; the filter builder then shows the facet's filter too
                              :hx-get       (str list-path "/table" (subs page-url (count list-path)))
                              :hx-target    "#filter-table-container"
                              :hx-swap      "outerHTML"
                              :hx-push-url  page-url}
                          label
                          [:span {:class "facet-count"} (str n)]]))]
        [:nav {:class "facet-tabs" :aria-label (:label (first (columns entity-config [facet])))}
         (tab nil [:t :admin/facet-all] (reduce + 0 (vals counts)))
         (for [v values
               :let [n (get counts v 0)]
               :when (or (pos? n) (= v current))]
           (tab v (cells/enum-label fc v) n))]))))

(defn summary-line
  "The entity's `:summary` aggregates over the filtered set (ADR-040)."
  [entity-config display]
  (when-let [items (seq (get-in display [:overview :summary]))]
    [:dl {:class "list-summary"}
     (for [{:keys [label value role field]} items]
       [:div {:class "list-summary-item"}
        [:dt label]
        [:dd {:class (cells/cell-class role)}
         (if (some? value)
           (cells/format-aggregate value role (get-in entity-config [:fields field]) entity-config display)
           "—")]])]))

(defn overview-strip
  "Summary and facet tabs above the table."
  [entity-name entity-config table-query filters display]
  (let [summary (summary-line entity-config display)
        tabs    (facet-tabs entity-name entity-config table-query filters display)]
    (when (or summary tabs)
      [:div {:class "list-overview"} summary tabs])))

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
     display: Optional display options threaded to cell rendering

   Returns:
     Hiccup table structure"
  [entity-name records entity-config table-query total-count permissions & [filters display]]
  (let [{:keys [sort dir page page-size]} table-query
        base-url (str "/web/admin/" (name entity-name) "/table")
        hx-target "#entity-table-container"
        table-params (table-ui/table-query->params table-query)
        filter-params (table-ui/search-filters->params (or filters {}))
        qs-map (merge table-params filter-params)
        hx-url (str base-url "?" (table-ui/encode-query-params qs-map))
        list-fields (:list-fields entity-config)
        cols (columns entity-config list-fields)]
    ;; Table container - Alpine.js scope is at parent entity-list-page level
    ;; MutationObserver automatically handles HTMX DOM updates (no afterSwap needed)
    [:div#entity-table-container
     {:hx-get hx-url
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target
      :hx-swap "outerHTML"}
     ;; Inside the swap target, so search, sort and paging keep them current
     (overview-strip entity-name entity-config table-query filters display)
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
              (base/list-column-styles (cond-> (vec list-fields)
                                         (workflow-column? entity-config) (conj :workflow))
                                       entity-config)
              [:col {:class "col-actions"}]]  ; Actions
             [:thead
              [:tr
               [:th {:class "checkbox-header"}
                ;; Alpine.js select-all checkbox with reactive binding
                [:input (alpine/select-all-checkbox-attrs)]]
               (for [{:keys [field config label data?] role-class :class} cols]
                 (let [sortable? (and data? (:sortable config true))]
                   (if sortable?
                     (update-in
                      (table-ui/sortable-th {:label label
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
                      ;; The role aligns the header with its cells
                      [1 :class] str " " role-class)
                     [:th {:class role-class} label])))
               (when (workflow-column? entity-config)
                 [:th [:t :admin/column-workflow]])
               [:th {:class "actions-header"} [:t :admin/column-actions]]]]
             [:tbody
              (for [record records]
                (entity-table-row entity-name record entity-config permissions display cols))]
             (totals-row records cols entity-config display)]]]
          pagination]))]))

(defn filter-table-container
  "Filter builder and table: the target of filter requests, rendered the same
   on the page and in the fragment that replaces it."
  [entity-name records entity-config table-query total-count permissions filters display]
  [:div#filter-table-container {:class "space-y-3"}
   (filters/render-filter-builder entity-name entity-config filters)
   (entity-table entity-name records entity-config table-query total-count permissions filters display)])

(defn entity-list-page
  "Complete entity list page with search, table, and actions.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters
     total-count: Total number of records
     permissions: Permission flags
     opts: Optional map with :search, :filters, :flash, :display

   Returns:
     Hiccup page structure"
  [entity-name records entity-config table-query total-count permissions & [opts]]
  (let [{:keys [search filters flash display]} opts
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
                                :hx-swap "outerHTML"
                                :hx-push-url "true"
                                :hx-trigger "keyup changed delay:300ms, search"
                                :hx-include "this"}]
          [:button.icon-button {:type "button"
                                :aria-label [:t :common/button-search]
                                :hx-get (str "/web/admin/" (name entity-name) "/table")
                                :hx-target "#entity-table-container"
                                :hx-swap "outerHTML"
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
                                    :hx-swap "outerHTML"
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
     (filter-table-container entity-name records entity-config table-query total-count
                             permissions filters display)]))
