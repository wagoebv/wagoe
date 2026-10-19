(ns wagoe.admin.core.ui.list
  "Entity list-page components: search form, table rows, table, and the
   complete list page (toolbar + filter builder + table).

   Pure Hiccup generators. Depends on `base` for URL helpers, field-value
   rendering, and column sizing, and on `filters` for the filter builder."
  (:require [wagoe.admin.core.schema-introspection :as introspection]
            [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.cells :as cells]
            [wagoe.admin.core.ui.drawer :as drawer]
            [wagoe.admin.core.ui.filters :as filters]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.modal :as modal]
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
  [entity-name record entity-config permissions & [display cols return-to]]
  (let [primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        ;; Links carry the list they came from, so the record page can offer
        ;; previous / next in it and the way back (data-href stays bare: the
        ;; drawer and the keyboard add the live list URL themselves)
        record-url (str "/web/admin/" (name entity-name) "/" record-id
                        (when return-to (str "?return_to=" (base/url-encode return-to))))
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
           (or cols (cells/columns entity-config (:list-fields entity-config)))]
       (let [content (cells/cell field record
                                 {:role          role
                                  :field-config  config
                                  :entity-config entity-config
                                  :relation      relation
                                  :display       display
                                  :href          (when can-open? record-url)})
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
         {:href record-url
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
            known    (map (comp cells/value-name first) (:options fc))
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
        [:nav {:class "facet-tabs" :aria-label (:label (first (cells/columns entity-config [facet])))}
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
        cols (cells/columns entity-config list-fields)]
    ;; Table container - Alpine.js scope is at parent entity-list-page level
    ;; MutationObserver automatically handles HTMX DOM updates (no afterSwap needed)
    [:div#entity-table-container
     {:hx-get hx-url
      ;; The toolbar count sits outside this swap; admin-ux.js copies it over
      :data-total-count total-count
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target
      :hx-swap "outerHTML"}
     ;; Inside the swap target, so search, sort and paging keep them current
     (overview-strip entity-name entity-config table-query filters display)
     (cond
       ;; Filtered or searched to nothing: say so, and offer the way back,
       ;; not "create the first record" — there are records
       (and (empty? records) (or (seq filters) (not (str/blank? (:search table-query)))))
       [:div.empty-state {:class "empty-state-filtered"}
        [:div.empty-state-icon (icons/icon :filter {:size 32})]
        [:p [:t :admin/empty-state-no-matches {:label (:label entity-config)}]]
        [:button {:type        "button"
                  :class       "button secondary"
                  :hx-get      (str "/web/admin/" (name entity-name) "/table?"
                                    (table-ui/encode-query-params
                                     (table-ui/table-query->params (assoc table-query :page 1 :search nil))))
                  :hx-target   "#filter-table-container"
                  :hx-swap     "outerHTML"
                  :hx-push-url "true"
                  ;; The search box keeps what was typed; clearing it is the
                  ;; user's call, so this clears the filters and the search
                  "hx-on::before-request" "var s=document.querySelector('.toolbar-search .search-input'); if (s) s.value='';"}
         [:t :admin/button-clear-filters-and-search]]]

       (empty? records)
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

       :else
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
            (for [[k v] table-params :when (some? v)]
              [:input {:type "hidden" :name k :value v}])
            (for [[k v] filter-params
                  v (if (sequential? v) v [v])]
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
                      ;; The role aligns the header with its cells; the field
                      ;; class lets the column menu hide it
                      [1 :class] str " " role-class " field-" (name field))
                     [:th {:class (str role-class " field-" (name field))} label])))
               (when (workflow-column? entity-config)
                 [:th [:t :admin/column-workflow]])
               [:th {:class "actions-header"} [:span.sr-only [:t :admin/column-actions]]]]]
             [:tbody
              (let [return-to (base/current-list-url entity-name table-query filters)]
                (for [record records]
                  (entity-table-row entity-name record entity-config permissions display cols return-to)))]
             (totals-row records cols entity-config display)]]]
          pagination]))]))

(defn filter-table-container
  "Filter builder and table: the target of filter requests, rendered the same
   on the page and in the fragment that replaces it."
  [entity-name records entity-config table-query total-count permissions filters display]
  [:div#filter-table-container {:class "space-y-3"}
   ;; Active filters as chips; editing is in the filter drawer
   (filters/filter-summary entity-name entity-config table-query filters)
   (entity-table entity-name records entity-config table-query total-count permissions filters display)])

(defn- view-menu
  "Density and which columns show, per entity and per browser (admin-ux.js
   keeps the choice in localStorage and applies it)."
  [entity-name entity-config]
  [:details {:class "view-menu" :data-view-entity (name entity-name)}
   [:summary {:class "button ghost view-menu-button" :title [:t :admin/view-menu]}
    (icons/icon :layout-list {:size 16})
    [:span.sr-only [:t :admin/view-menu]]]
   [:div {:class "view-menu-panel"}
    [:fieldset
     [:legend [:t :admin/view-density]]
     (for [[v label] [["compact" :admin/view-density-compact] ["comfortable" :admin/view-density-comfortable]]]
       [:label
        [:input {:type "radio" :name "view-density" :value v :data-view "density"}]
        " " [:t label]])]
    [:fieldset
     [:legend [:t :admin/view-columns]]
     (for [{:keys [field label]} (cells/columns entity-config (:list-fields entity-config))]
       [:label
        [:input {:type "checkbox" :value (name field) :data-view "column" :checked true}]
        " " label])]]])

(defn- keyboard-hints
  "The list's keys (keyboard.js), shown once below the table."
  []
  [:p {:class "kbd-hints" :aria-hidden "true"}
   (for [[ks label] [[["/"] :admin/kbd-search] [["j" "k"] :admin/kbd-move] [["PgUp" "PgDn"] :admin/kbd-page]
                     [["x"] :admin/kbd-select] [["f"] :admin/kbd-filter] [["↵"] :admin/kbd-open] [["e"] :admin/kbd-edit]
                     [["q"] :admin/kbd-close]]]
     [:span (for [k ks] [:kbd k]) " " [:t label]])])

(defn- bulk-bar
  "The bar that appears once rows are selected: how many,
   what to do with them, and how to stop. Inside the Alpine bulk-selection
   scope of the list page."
  [entity-name]
  [:div {:class      "bulk-bar"
         :role       "region"
         :aria-label [:t :admin/bulk-bar-label]
         :x-show     "selectedIds.length > 0"
         :x-cloak    true}
   [:span {:class "bulk-count"}
    [:span {:x-text "selectedIds.length"}] " " [:t :admin/bulk-selected]]
   ;; htmx asks the form, not the button, to confirm a submit
   [:form#bulk-action-form
    (merge (modal/confirm-attrs "bulk-delete-confirm")
           {:hx-post    (str "/web/admin/" (name entity-name) "/bulk-delete")
            :hx-target  "#entity-table-container"
            :hx-swap    "outerHTML"
            :hx-include "[name='ids[]']"})
    [:button.button
     (merge (alpine/delete-button-attrs)
            {:type                "submit"
             :class               "bulk-danger"
             :name                "action"
             :value               "delete"
             :id                  "bulk-delete-btn"
             :form                "bulk-action-form"})
     (icons/icon :trash {:size 14})
     [:t :admin/button-delete-selected]]]
   (modal/confirm-modal
    {:id            "bulk-delete-confirm"
     :title         [:t :admin/modal-confirm-delete-title]
     :message       [:t :admin/confirm-delete-selected]
     :confirm-label [:t :admin/modal-button-delete]
     :cancel-label  [:t :admin/modal-button-cancel]
     :danger?       true})
   [:button {:type         "button"
             :class        "button bulk-clear"
             :x-on:click   "clearSelection()"}
    [:t :admin/bulk-clear]
    [:kbd "q"]]])

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
        search-fields (:search-fields entity-config)
        has-search? (seq search-fields)
        search-value (:search search)]
    ;; Alpine.js bulk selection scope - wraps toolbar and table
    ;; selectedIds array is shared between delete button and checkboxes
    [:div.entity-list-page (merge (alpine/bulk-selection-attrs)
                                  {:class "space-y-4"
                                   ;; Escape clears the selection (keyboard.js)
                                   :x-on:bulk-clear.window "clearSelection()"})
     (when flash
       (for [[type message] flash]
         [:div {:class (str "alert alert-" (name type) " mb-2")} message]))

       ;; Compact toolbar: title + search + actions in one bar
     [:div.table-toolbar-container
      [:div.toolbar-row-actions
       ;; The topbar names the page; here only the count
       [:div.record-meta
        [:span.record-count-badge (str total-count)]]

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
          ;; Typing searches; the icon only says what the field is
          [:span {:class "search-icon" :aria-hidden "true"} (icons/icon :search {:size 15})]
          (when (seq search-value)
            [:button.icon-button.secondary {:type "button"
                                            :aria-label [:t :admin/button-clear-search]
                                            :onclick (str "window.location.href='/web/admin/" (name entity-name) "';")}
             (icons/icon :x {:size 18})])])

       [:div.toolbar-actions
        (view-menu entity-name entity-config)
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
           [:t :admin/button-new {:entity (introspection/singular-label entity-config entity-name)}]])]]]

     (bulk-bar entity-name)

       ;; Filter builder + Table wrapper (THIS is the HTMX target for filter updates)
     (filter-table-container entity-name records entity-config table-query total-count
                             permissions filters display)
     (keyboard-hints)
     ;; A row click opens its record here (admin-ux.js), beside the list
     [:div {:id drawer/slot-id}]]))
