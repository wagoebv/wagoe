(ns wagoe.workflow.core.ui
  "Pure Hiccup UI components for workflow admin visualization.

   All functions are pure — they receive data and return Hiccup structures.
   No side effects, no I/O, no logging.

   Components:
     state-badge          — colored state pill
     state-machine-vis    — read-only workflow state diagram
     audit-log-table      — chronological audit trail table
     instances-filter-form — the instance list's filters
     instances-table      — the instance list, sortable
     instances-page       — full page: instance list
     instance-detail-page — full page: instance detail with state viz + audit trail"
  (:require [wagoe.shared.ui.core.shell :as shell]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.form :as form]
            [wagoe.shared.ui.core.table :as table-ui]
            [clojure.string :as str]))

;; =============================================================================
;; State Badge
;; =============================================================================

(defn state-badge
  "Render a state keyword as a tone badge, the look the admin entity pages give
   an enum value (`c-badge tone-*` in wagoe-admin.css).

   Args:
     state-kw - keyword (e.g. :pending, :paid)
     variant  - :current (success), :reachable (info), else neutral

   Returns:
     Hiccup span element"
  ([state-kw]
   (state-badge state-kw :default))
  ([state-kw variant]
   (ui/tone-badge (form/label-text state-kw)
                  (case variant
                    :current :success
                    :reachable :info
                    :neutral))))

;; =============================================================================
;; State Machine Visualization
;; =============================================================================

(defn state-machine-vis
  "Render a read-only visualization of a workflow state machine.

   Displays all states as colored badges (current highlighted in green,
   reachable states in blue) and a collapsible transition reference table.

   Args:
     definition    - WorkflowDefinition map or nil if not registered
     current-state - keyword (e.g. :paid)
     visited-states - collection of state keywords already visited (from audit log)

   Returns:
     Hiccup structure"
  [definition current-state visited-states]
  (let [visited (set visited-states)]
    [:div
     (if (nil? definition)
       [:div
        [:p.text-muted [:t :workflow/definition-not-loaded]]
        [:div {:style "display: flex; gap: 0.5rem; flex-wrap: wrap;"}
         (state-badge current-state :current)]]
       (let [all-states  (:states definition)
             transitions (:transitions definition)
             initial     (:initial-state definition)
             reachable   (set (map :to
                                   (filter #(= current-state (:from %)) transitions)))
             ordered     (concat [initial]
                                 (sort-by name (disj (set all-states) initial)))
             state-var   (fn [s]
                           ;; Reachable before visited: a state the instance
                           ;; left can be the next one again.
                           (cond
                             (= s current-state) :current
                             (contains? reachable s) :reachable
                             (contains? visited s) :visited
                             :else :default))]
         [:div
          [:div {:style "display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; margin-bottom: 1rem;"}
           (for [s ordered]
             (state-badge s (state-var s)))]
          [:details
           [:summary {:style "cursor: pointer; font-size: 0.9em; margin-bottom: 0.5rem;"}
            [:t :workflow/link-view-all-transitions {:count (count transitions)}]]
           (ui/table-wrapper
            [:table {:class "data-table ui-table" :style "margin-top: 0.5rem;"}
             [:thead
              [:tr
               [:th [:t :workflow/column-from]]
               [:th "→ " [:t :workflow/column-to]]
               [:th [:t :workflow/column-required-permissions]]]]
             [:tbody
              (for [{:keys [from to required-permissions]} transitions]
                [:tr
                 [:td (state-badge from (state-var from))]
                 [:td (state-badge to (state-var to))]
                 [:td
                  (if (seq required-permissions)
                    (str/join ", " (map name required-permissions))
                    [:span.text-muted "—"])]])]])]]))]))

;; =============================================================================
;; Audit Log Table
;; =============================================================================

(defn audit-log-table
  "Render a chronological audit log table for a workflow instance.

   Args:
     entries       - vector of AuditEntry maps (oldest first)
     current-state - the instance's state now; only the last entry's to-state
                     can be it, so only that badge reads as current

   Returns:
     Hiccup table or empty-state structure"
  [entries current-state]
  (if (empty? entries)
    [:div.empty-state
     (icons/icon :file-text {:size 32})
     [:h3 [:t :workflow/empty-state-no-transitions]]
     [:p [:t :workflow/empty-state-transitions-description]]]
    (ui/table-wrapper
     [:table {:class "data-table ui-table"}
      [:thead
       [:tr
        [:th [:t :workflow/audit-column-when]]
        [:th [:t :workflow/audit-column-transition]]
        [:th [:t :workflow/audit-column-from]]
        [:th [:t :workflow/audit-column-to]]
        [:th [:t :workflow/audit-column-actors]]
        [:th [:t :workflow/audit-column-context]]]]
      [:tbody
       (for [[i entry] (map-indexed vector entries)
             :let [now? (and (= i (dec (count entries)))
                             (= (:to-state entry) current-state))]]
         [:tr
          [:td {:style "white-space: nowrap; font-size: 0.85em;"}
           (str (:occurred-at entry))]
          [:td [:code (name (:transition entry))]]
          [:td (state-badge (:from-state entry) :visited)]
          [:td (state-badge (:to-state entry) (if now? :current :visited))]
          [:td
           (if (seq (:actor-roles entry))
             (str/join ", " (map name (:actor-roles entry)))
             [:span.text-muted "—"])]
          [:td
           (if (:context entry)
             [:code {:style "font-size: 0.8em;"} (pr-str (:context entry))]
             [:span.text-muted "—"])]])]])))

;; =============================================================================
;; Instances List
;; =============================================================================

(def instances-path "/web/admin/workflows")

(def instances-target
  "The container the list's filters and sort re-render."
  "workflows-table-container")

(def instance-columns
  "The list's columns; :sortable? ones are what the store sorts by."
  [{:field :id :label [:t :workflow/column-instance-id] :class "c-title" :sortable? false}
   {:field :workflow-id :label [:t :workflow/column-workflow]}
   {:field :entity-type :label [:t :workflow/column-entity-type]}
   {:field :entity-id :label [:t :workflow/column-entity-id] :sortable? false}
   {:field :current-state :label [:t :workflow/column-current-state]}
   {:field :updated-at :label [:t :workflow/column-updated]}])

(def default-query
  "Newest first, as the store lists them."
  {:sort :updated-at :dir :desc})

(defn- short-id [x]
  (let [s (str x)]
    [:code (subs s 0 (min 8 (count s))) "…"]))

(defn- instance-row [inst]
  (let [href (str instances-path "/" (:id inst))]
    [:tr {:class "clickable-row" :data-href href}
     (map (fn [{:keys [field label] col-class :class} cell]
            [:td {:class (str "field-" (name field) (when col-class (str " " col-class))) :data-label label} cell])
          instance-columns
          [[:a {:href href} (short-id (:id inst))]
           [:code (name (:workflow-id inst))]
           [:code (name (:entity-type inst))]
           (short-id (:entity-id inst))
           (state-badge (:current-state inst) :current)
           [:span.c-datetime (str (:updated-at inst))]])
     [:td.actions-cell
      [:a.row-nav-hint {:href href :aria-label [:t :workflow/button-open-instance]}
       (icons/icon :chevron-right {:size 14})]]]))

(defn instances-table
  "The instances in the shared data-table, or an empty state, in the
   container the list's filters and sort re-render.

   Args:
     instances   - vector of WorkflowInstance maps
     table-query - {:sort :dir}, `default-query` when omitted
     filters     - the query params filtered by (string keys), when any

   Returns:
     Hiccup structure"
  ([instances] (instances-table instances default-query {}))
  ([instances table-query filters]
   (let [target  (str "#" instances-target)
         filters (into {} (remove (comp str/blank? val)) filters)]
     [:div {:id instances-target}
      ;; The sort the toolbar filters send along: they sit outside this swap
      [:div {:class "table-state" :hidden true}
       (table-ui/hidden-inputs (select-keys (table-ui/table-query->params table-query) ["sort" "dir"]))]
      (cond
        (and (empty? instances) (seq filters))
        [:div.empty-state {:class "empty-state-filtered"}
         [:div.empty-state-icon (icons/icon :filter {:size 32})]
         [:p [:t :workflow/empty-state-no-matches]]
         [:a.button.secondary {:href instances-path} [:t :common/button-clear]]]

        (empty? instances)
        [:div.empty-state
         (icons/icon :layout-list {:size 32})
         [:h3 [:t :workflow/empty-state-no-instances]]
         [:p [:t :workflow/empty-state-instances-description]]]

        :else
        [:div
         (table-ui/sort-select {:url instances-path :hx-target target :table-query table-query
                                :filters filters :columns instance-columns})
         (ui/table-wrapper
          (table-ui/data-table {:url         instances-path
                                :hx-target   target
                                :table-query table-query
                                :filters     filters
                                :columns     instance-columns
                                :head-after  [[:th.actions-header [:span.sr-only [:t :common/column-actions]]]]
                                :rows        (map instance-row instances)}))])])))

;; =============================================================================
;; Filters
;; =============================================================================

(defn instances-filter-form
  "The list's filters: each re-queries the table as it changes, sending the
   others and the table's sort along, and pushes the URL.

   Args:
     params - current query-params map (string keys)
     states - the registered workflows' states; the state filter is a select
              of them, or a text field when none is registered

   Returns:
     Hiccup structure"
  [params states]
  (let [input (fn [k label opts]
                (table-ui/filter-input
                 (merge {:name       k
                         :value      (get params k "")
                         :label      label
                         :url        instances-path
                         :hx-target  (str "#" instances-target)
                         :hx-include (str "#workflow-filters [name], #" instances-target " .table-state input")
                         :attrs      {:id (str "workflow-filter-" k) :type "text" :class "input input-sm"}}
                        opts)))
        current (get params "state" "")]
    [:div#workflow-filters.toolbar-filters
     (input "workflow-id" [:t :workflow/filter-label-workflow-id]
            {:placeholder [:t :workflow/filter-placeholder-workflow-id]})
     (input "entity-type" [:t :workflow/filter-label-entity-type]
            {:placeholder [:t :workflow/filter-placeholder-entity-type]})
     (if (seq states)
       ;; A filtered-by state no workflow registers stays selectable, so the
       ;; select never shows "any" over a filtered list
       (input "state" [:t :workflow/filter-label-current-state]
              {:placeholder [:t :workflow/filter-option-any-state]
               :options     (cond-> (mapv (juxt name form/label-text) states)
                              (and (seq current) (not-any? #(= current (name %)) states))
                              (conj [current (form/label-text current)]))
               :attrs       {:id "workflow-filter-state" :class "select select-sm"}})
       (input "state" [:t :workflow/filter-label-current-state]
              {:placeholder [:t :workflow/filter-placeholder-current-state]}))]))

;; =============================================================================
;; Full Pages
;; =============================================================================

(defn- admin-page
  "`content` in the admin shell under `heading` in the topbar. `opts` carries
   the admin's :logo-url; the sidebar is the navigation the platform binds."
  [title heading content opts]
  (shell/shell-page title [:div.workflow-page content]
                    (assoc opts :page-title heading)))

(defn- card
  "A titled card on the detail page."
  [title & body]
  (into [:article.workflow-card
         [:header.workflow-card-header
          [:h2.workflow-card-title title]]]
        body))

(defn instances-page
  "Render the full workflow instances list page.

   Args:
     instances - vector of WorkflowInstance maps
     params    - query-params map (string keys from ring request)
     opts      - map with :user, :flash, :states (for the filter),
                 :table-query (`default-query` when absent), and the admin's
                 :logo-url

   Returns:
     Complete HTML page structure (doctype + html)"
  [instances params opts]
  (admin-page
   [:t :workflow/page-instances-title]
   [:t :workflow/page-instances-heading]
   [:div.entity-list-page {:class "space-y-4"}
    [:div.table-toolbar-container
     [:div.toolbar-row-actions
      (instances-filter-form params (:states opts))]]
    (instances-table instances
                     (or (:table-query opts) default-query)
                     (select-keys params ["workflow-id" "entity-type" "state"]))]
   opts))

(defn instance-detail-page
  "Render the workflow instance detail page.

   Displays instance metadata, a state machine visualization with the
   current state highlighted, and the full chronological audit trail.

   Args:
     instance   - WorkflowInstance map
     definition - WorkflowDefinition map or nil
     audit-log  - vector of AuditEntry maps (oldest first)
     opts       - as for `instances-page`

   Returns:
     Complete HTML page structure"
  [instance definition audit-entries opts]
  (let [current-state (:current-state instance)
        wf-name       (name (:workflow-id instance))
        n             (count audit-entries)]
    (admin-page
     [:t :workflow/page-detail-title {:name wf-name}]
     [:t :workflow/breadcrumb-instances]
     (list
      [:nav.page-breadcrumbs {:aria-label [:t :common/aria-breadcrumb]}
       [:a {:href "/web/admin/workflows"} [:t :workflow/breadcrumb-instances]]
       " / "
       [:code (str (:id instance))]]

      (card [:t :workflow/card-details-title]
            [:dl.workflow-details
             [:dt [:t :workflow/detail-workflow]]
             [:dd [:code wf-name]]
             [:dt [:t :workflow/detail-entity-type]]
             [:dd [:code (name (:entity-type instance))]]
             [:dt [:t :workflow/detail-entity-id]]
             [:dd [:code (str (:entity-id instance))]]
             [:dt [:t :workflow/detail-state]]
             [:dd (state-badge current-state :current)]
             [:dt [:t :workflow/detail-created]]
             [:dd (str (:created-at instance))]
             [:dt [:t :workflow/detail-updated]]
             [:dd (str (:updated-at instance))]])

      (card [:t :workflow/card-state-machine-title]
            (state-machine-vis definition current-state (map :to-state audit-entries)))

      (card (list (icons/icon :file-text {:size 18})
                  [:t :workflow/card-audit-title]
                  [:small.workflow-card-count
                   [:t :workflow/audit-count {:count n} n]])
            (audit-log-table audit-entries current-state)))
     opts)))
