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
       [:form {:hx-get (base/list-path entity-name)
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

(defn record-count-badge
  "The toolbar's record count. A table fragment carries it out of band, so
   it follows search and filters."
  [total-count]
  (table-ui/count-badge "record-count-badge" total-count))

(defn- workflow-column?
  "An entity with a workflow gets a column for its state (BOU-563)."
  [entity-config]
  (some? (:workflow entity-config)))

(defn- record-page-url
  "A record's page, carrying the list it came from (BOU-553)."
  [entity-name record-id return-to]
  (str "/web/admin/" (name entity-name) "/" record-id
       (when return-to (str "?return_to=" (base/url-encode return-to)))))

(defn entity-cell
  "One `[:td]` of a list row: `column` as `cells/columns` describes it, double-
   click editable when the user may write the field. An inline save or cancel
   answers with this same cell, so the edited cell stays the one the list drew.
   `href` is the record's page, for a :title cell, or nil."
  [entity-name record entity-config permissions display column href]
  (let [{:keys [field config role label relation data?]} column
        primary-key (:primary-key entity-config :id)
        content (cells/cell field record
                            {:role          role
                             :field-config  config
                             :entity-config entity-config
                             :relation      relation
                             :display       display
                             :href          href})
        classes (str "field-" (name field) " " (:class column))
        editable? (and (:can-edit permissions)
                       data?
                       (not (some #{field} (:readonly-fields entity-config)))
                       (not= field primary-key))]
    (if editable?
      [:td {:class (str classes " editable")
            :data-label label
            :hx-get (str "/web/admin/" (name entity-name) "/" (get record primary-key) "/" (name field) "/edit")
            :hx-trigger "dblclick"
            :hx-target "this"
            :hx-swap "innerHTML"
            :title [:t :admin/cell-dblclick-hint]}
       content]
      [:td {:class classes
            :data-label label}
       content])))

(defn field-cell
  "`field`'s list cell for `record` alone: what an inline save or cancel
   returns. `return-to` is the list the row is on, as `entity-table-row` takes it."
  [entity-name record entity-config field permissions & [display return-to]]
  (entity-cell entity-name record entity-config permissions display
               (first (cells/columns entity-config [field]))
               (when (:can-view permissions)
                 (record-page-url entity-name (get record (:primary-key entity-config :id)) return-to))))

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
        record-url (record-page-url entity-name record-id return-to)
        ;; The detail page needs read: one who may only read sees it read-only
        can-open? (boolean (:can-view permissions))
        row-attrs (cond-> {:class (if can-open? "entity-row clickable-row" "entity-row")}
                    ;; Focusable from script: closing the drawer returns here
                    can-open?
                    (assoc :data-href (str "/web/admin/" (name entity-name) "/" record-id)
                           :tabindex "-1"))]
    [:tr row-attrs
     [:td.checkbox-cell
       ;; Alpine.js row checkbox with x-model binding to selectedIds array
      [:input (alpine/row-checkbox-attrs record-id (get record (:title-field entity-config primary-key)))]]
     (for [column (or cols (cells/columns entity-config (:list-fields entity-config)))]
       (entity-cell entity-name record entity-config permissions display column
                    (when can-open? record-url)))
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
                             page-url  (href v)]
                         [:a {:href         page-url
                              :class        (str "facet-tab"
                                                 (when v (str " tone-" (name (cells/enum-tone fc v))))
                                                 (when active? " active"))
                              :aria-current (when active? "page")
                              ;; Into the filter container: the filter
                              ;; summary then shows the facet's filter too
                              :hx-get       page-url
                              :hx-target    "#filter-table-container"
                              :hx-swap      "outerHTML"
                              :hx-push-url  "true"}
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
  (let [list-path (base/list-path entity-name)
        hx-target "#entity-table-container"
        table-params (table-ui/table-query->params table-query)
        filter-params (table-ui/search-filters->params (or filters {}))
        list-fields (:list-fields entity-config)
        cols (cells/columns entity-config list-fields)
        ;; The role aligns a header with its cells; the field class lets the
        ;; column menu hide it
        table-columns (for [{:keys [field config label data?] role-class :class} cols]
                        {:field     field
                         :label     label
                         :class     role-class
                         :sortable? (boolean (and data? (:sortable config true)))})]
    ;; Table container - Alpine.js scope is at parent entity-list-page level
    ;; MutationObserver automatically handles HTMX DOM updates (no afterSwap needed)
    [:div#entity-table-container
     {:hx-get (table-ui/list-url list-path table-query filters)
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target
      :hx-swap "outerHTML"}
     ;; The sort and filters the toolbar search sends along: it sits outside
     ;; this swap, so its own URL would go stale
     [:div {:class "table-state" :hidden true}
      ;; The filter drawer sends its own filters, so it takes only these
      [:div {:class "table-sort"}
       (table-ui/hidden-inputs (dissoc table-params "page" "search"))]
      (table-ui/hidden-inputs filter-params)]
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
                  :hx-get      (table-ui/list-url list-path (assoc table-query :page 1 :search nil) nil)
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
                                              :base-url list-path
                                              :hx-target hx-target
                                              :extra-params filters})
             has-pagination? (some? pagination)
             wrapper-class (if has-pagination? "table-and-pagination" "")]
         [:div {:class wrapper-class}
          (table-ui/sort-select {:url list-path :hx-target hx-target :table-query table-query
                                 :filters filters :columns table-columns})
          [:div.table-wrapper
           ;; Form for checkbox submission (hidden inputs + table)
           [:form#table-form
            {:hx-post (str "/web/admin/" (name entity-name) "/bulk")
             :hx-target hx-target
             :hx-swap "outerHTML"}
            ;; Preserve table state
            (table-ui/hidden-inputs (merge table-params filter-params))

            (table-ui/data-table
             {:url         list-path
              :hx-target   hx-target
              :table-query table-query
              :filters     filters
              :columns     table-columns
              ;; Column sizing for table-layout: fixed — framing columns via CSS
              ;; classes, data columns via proportional inline widths
              ;; (overridable via :width in the field config).
              :colgroup    [:colgroup
                            [:col {:class "col-select"}]
                            (base/list-column-styles (cond-> (vec list-fields)
                                                       (workflow-column? entity-config) (conj :workflow))
                                                     entity-config)
                            [:col {:class "col-actions"}]]
              :head-before [[:th {:class "checkbox-header"}
                             [:input (alpine/select-all-checkbox-attrs)]]]
              :head-after  [(when (workflow-column? entity-config)
                              [:th [:t :admin/column-workflow]])
                            [:th {:class "actions-header"} [:span.sr-only [:t :admin/column-actions]]]]
              :rows        (let [return-to (base/current-list-url entity-name table-query filters)]
                             (for [record records]
                               (entity-table-row entity-name record entity-config permissions display cols return-to)))
              :tfoot       (totals-row records cols entity-config display)})]]
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
   (for [[ks label] [[["/"] :admin/kbd-search] [["j" "k"] :admin/kbd-move] [["PgUp" "PgDn"] :admin/kbd-page] ; i18n-ignore: key caps
                     [["x"] :admin/kbd-select] [["f"] :admin/kbd-filter] [["↵"] :admin/kbd-open] [["e"] :admin/kbd-edit]
                     [["q"] :admin/kbd-close]]]
     [:span (for [k ks] [:kbd k]) " " [:t label]])])

(defn- bulk-bar
  "The bar that appears once rows are selected. Inside the Alpine
   bulk-selection scope of the list page."
  [entity-name]
  (table-ui/bulk-bar
   {:form-attrs {:hx-post    (str "/web/admin/" (name entity-name) "/bulk-delete")
                 :hx-target  "#entity-table-container"
                 :hx-swap    "outerHTML"
                 :hx-include "[name='ids[]']"}
    :controls   [[:button.button
                  (merge (alpine/delete-button-attrs)
                         {:type  "submit"
                          :class "bulk-danger"
                          :name  "action"
                          :value "delete"
                          :id    "bulk-delete-btn"})
                  (icons/icon :trash {:size 14})
                  [:t :admin/button-delete-selected]]]
    :confirm    {:id            "bulk-delete-confirm"
                 :title         [:t :admin/modal-confirm-delete-title]
                 :message       [:t :admin/confirm-delete-selected]
                 :confirm-label [:t :common/button-delete]
                 :danger?       true}}))

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
       ;; The topbar names the page; here only the count. Live here and not
       ;; on the badge, which a swap replaces: a new live region is not read.
       [:div.record-meta {:aria-live "polite"}
        (record-count-badge total-count)]

       (when has-search?
         [:div.toolbar-search
          (table-ui/filter-input
           {:name        "search"
            :value       search-value
            :placeholder [:t :admin/filter-placeholder-search {:fields (str/join ", " (map name search-fields))}]
            :url         (base/list-path entity-name)
            :hx-target   "#entity-table-container"
            ;; Page one, sorted and filtered as the table is now
            :hx-include  "#entity-table-container .table-state input"
            :attrs       {:type "text" :class "search-input"}})
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
                                    :hx-get (base/list-path entity-name)
                                    :hx-target "#entity-table-container"
                                    :hx-swap "outerHTML"
                                    :hx-push-url "true"}
         (icons/icon :refresh {:size 18})]
        (when (:can-create permissions)
          [:a.button.primary
           {:class "gap-2"
            :href (base/entity-create-url entity-name entity-config
                                          (when (:create-redirect-url entity-config)
                                            (base/current-list-url entity-name table-query filters)))}
           (icons/icon :plus {:size 18})
           [:t :admin/button-new {:entity (introspection/singular-label entity-config entity-name)}]])]]]

     (bulk-bar entity-name)

       ;; Filter builder + Table wrapper (THIS is the HTMX target for filter updates)
     (filter-table-container entity-name records entity-config table-query total-count
                             permissions filters display)
     (keyboard-hints)
     ;; A row click opens its record here (admin-ux.js), beside the list
     [:div {:id drawer/slot-id}]]))
