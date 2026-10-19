(ns wagoe.workflow.core.ui
  "Pure Hiccup UI components for workflow admin visualization.

   All functions are pure — they receive data and return Hiccup structures.
   No side effects, no I/O, no logging.

   Components:
     state-badge          — colored state pill
     state-machine-vis    — read-only workflow state diagram
     audit-log-table      — chronological audit trail table
     instances-filter-form — search/filter form for instance list
     instances-table      — paginated instance list table
     instances-page       — full page: instance list
     instance-detail-page — full page: instance detail with state viz + audit trail"
  (:require [wagoe.admin.core.ui.layout :as admin-layout]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.form :as form]
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
;; Instances Table
;; =============================================================================

(defn instances-table
  "Render a paginated table of workflow instances.

   Args:
     instances - vector of WorkflowInstance maps

   Returns:
     Hiccup table or empty-state structure"
  [instances]
  (if (empty? instances)
    [:div.empty-state
     (icons/icon :layout-list {:size 32})
     [:h3 [:t :workflow/empty-state-no-instances]]
     [:p [:t :workflow/empty-state-instances-description]]]
    (ui/table-wrapper
     [:table {:class "data-table ui-table"}
      [:thead
       [:tr
        [:th [:t :workflow/column-instance-id]]
        [:th [:t :workflow/column-workflow]]
        [:th [:t :workflow/column-entity-type]]
        [:th [:t :workflow/column-entity-id]]
        [:th [:t :workflow/column-current-state]]
        [:th [:t :workflow/column-updated]]]]
      [:tbody
       (for [inst instances]
         (let [id-str (str (:id inst))
               short-id (subs id-str 0 (min 8 (count id-str)))]
           [:tr {:onclick (str "window.location.href='/web/admin/workflows/" id-str "'")
                 :style "cursor: pointer;"}
            [:td
             [:a {:href (str "/web/admin/workflows/" id-str)}
              [:code {:style "font-size: 0.85em;"} short-id "…"]]]
            [:td [:code (name (:workflow-id inst))]]
            [:td [:code (name (:entity-type inst))]]
            [:td
             (let [eid-str (str (:entity-id inst))
                   short-eid (subs eid-str 0 (min 8 (count eid-str)))]
               [:code {:style "font-size: 0.85em;"} short-eid "…"])]
            [:td (state-badge (:current-state inst) :current)]
            [:td {:style "font-size: 0.85em; white-space: nowrap;"}
             (str (:updated-at inst))]]))]])))

;; =============================================================================
;; Filter Form
;; =============================================================================

(defn instances-filter-form
  "Render the search/filter form for the workflow instances list.

   Args:
     params - current query-params map (string keys)
     states - the registered workflows' states; the state filter is a select
              of them, or a text field when none is registered

   Returns:
     Hiccup form structure"
  [params states]
  [:form.workflow-filter-form {:method "GET"
                               :action "/web/admin/workflows"}
   [:label.form-field
    [:t :workflow/filter-label-workflow-id]
    (ui/text-input :workflow-id (get params "workflow-id" "")
                   {:placeholder [:t :workflow/filter-placeholder-workflow-id]})]
   [:label.form-field
    [:t :workflow/filter-label-entity-type]
    (ui/text-input :entity-type (get params "entity-type" "")
                   {:placeholder [:t :workflow/filter-placeholder-entity-type]})]
   [:label.form-field
    [:t :workflow/filter-label-current-state]
    (if (seq states)
      (ui/select-field :state
                       (cons ["" [:t :workflow/filter-option-any-state]]
                             (map (juxt name form/label-text) states))
                       (get params "state" ""))
      (ui/text-input :state (get params "state" "")
                     {:placeholder [:t :workflow/filter-placeholder-current-state]}))]
   [:button.button.secondary {:type "submit"}
    (icons/icon :search {:size 14})
    " " [:t :common/button-search]]])

;; =============================================================================
;; Full Pages
;; =============================================================================

(defn- admin-page
  "`content` in the admin shell, with its sidebar, under `heading` in the
   topbar. `opts` carries the shell's :entities, :entity-configs and :logo-url,
   which the shell reads from the admin; without them the sidebar lists no
   entities."
  [title heading content opts]
  (layout/admin-pilot-page-layout
   title
   (admin-layout/admin-shell [:div.workflow-page content]
                             (assoc opts :page-title heading))
   {:user        (:user opts)
    :flash       (:flash opts)
    :skip-header true}))

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
     opts      - map with :user, :flash, :states (for the filter), and the
                 admin shell's :entities, :entity-configs and :logo-url

   Returns:
     Complete HTML page structure (doctype + html)"
  [instances params opts]
  (admin-page
   [:t :workflow/page-instances-title]
   [:t :workflow/page-instances-heading]
   (list (instances-filter-form params (:states opts))
         (instances-table instances))
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
