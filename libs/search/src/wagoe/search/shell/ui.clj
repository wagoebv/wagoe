(ns wagoe.search.shell.ui
  "Hiccup UI components for the search Admin interface.

   They receive data and return Hiccup. In the shell, not core, because they
   load wagoe-shared-ui at run time (BOU-932).

   Components:
     field-weight-badge   — weight indicator (A/B/C/D)
     search-result-row    — single result row
     search-results-fragment — HTMX results area
     indices-page         — list all registered indices (full page)
     index-detail-page    — index config + live search test form (full page)"
  (:require [wagoe.core.utils.text :as text]))

;; wagoe-shared-ui is not a dependency of wagoe-search: it brings the platform's
;; HTTP stack (BOU-932). These pages run only when an app mounts search's web
;; routes, and such an app has shared-ui. Resolved on first use, so this
;; namespace loads without it.
(def ^:private badge* (delay (requiring-resolve 'wagoe.shared.ui.core.components/badge)))
(def ^:private table-wrapper* (delay (requiring-resolve 'wagoe.shared.ui.core.components/table-wrapper)))
(def ^:private icon* (delay (requiring-resolve 'wagoe.shared.ui.core.icons/icon)))
(def ^:private home-path* (delay (requiring-resolve 'wagoe.shared.ui.core.home/*path*)))
(def ^:private admin-page* (delay (requiring-resolve 'wagoe.shared.ui.core.layout/admin-pilot-page-layout)))

(defn- badge [& args] (apply @badge* args))
(defn- table-wrapper [& args] (apply @table-wrapper* args))
(defn- icon [& args] (apply @icon* args))
(defn- home-path [] @@home-path*) ; the var, deref'd per call: callers bind it
(defn- admin-page [& args] (apply @admin-page* args))

;; =============================================================================
;; Badges
;; =============================================================================

(defn- language-badge [language]
  (badge (name language) {:variant :neutral
                          :class "status-badge"}))

(defn- doc-count-badge
  "A document count, or a dash for a caller who may not see one."
  [n]
  (badge (if n (str n " docs") "–")
         {:variant (if (pos? (or n 0)) :success :neutral)
          :class "status-badge"}))

(defn field-weight-badge
  "Render a field weight label as a colored badge."
  [weight]
  (let [variant (case weight
                  (:a :A) :success
                  (:b :B) :info
                  :neutral)]
    (badge [:t :search/badge-weight {:weight (text/upper-case (name weight))}]
           {:variant variant
            :class "status-badge"})))

;; =============================================================================
;; Indices List Page
;; =============================================================================

(defn- index-row
  [{:keys [id entity-type language fields doc-count]}]
  [:tr
   [:td [:a {:href (str "/web/admin/search/" (name id))}
         [:code (name id)]]]
   [:td [:code (name entity-type)]]
   [:td (language-badge language)]
   [:td (count fields)]
   [:td (doc-count-badge doc-count)]])

(defn indices-page
  "Render the search indices admin list page.

   Args:
     indices - vector of index info maps: {:id :entity-type :language :fields :doc-count}
     opts    - map with :user, :flash (passed to page-layout)"
  [indices opts]
  (let [{:keys [user flash]} opts]
    (admin-page
     [:t :search/page-indices-title]
     [:div {:style "padding: 1.5rem;"}
      [:div.page-header
       [:h1.page-title
        (icon :search {:size 24})
        [:t :search/page-indices-heading]]
       [:a.button.secondary {:href (home-path)}
        [:t :search/button-back-dashboard]]]
      [:p {:style "color: var(--muted-color); margin-top: 0;"}
       [:t :search/page-indices-description]]
      (if (seq indices)
        (table-wrapper
         [:table {:class "data-table ui-table"}
          [:thead
           [:tr
            [:th [:t :search/column-index-id]]
            [:th [:t :search/column-entity-type]]
            [:th [:t :search/column-language]]
            [:th [:t :search/column-fields]]
            [:th [:t :search/column-documents]]]]
          [:tbody
           (for [idx indices]
             (index-row idx))]])
        [:div.empty-state
         (icon :search {:size 32})
         [:h3 [:t :search/empty-state-no-indices]]
         [:p [:t :search/empty-state-help]]])]
     {:user  user
      :flash flash})))

;; =============================================================================
;; Index Detail + Live Search Page
;; =============================================================================

(defn search-result-row
  "Render a single search result row."
  [{:keys [entity-type entity-id rank snippet metadata]}]
  (let [eid-str  (str entity-id)
        short-id (subs eid-str 0 (min 8 (count eid-str)))]
    [:tr
     [:td [:code (name entity-type)]]
     [:td [:code {:title eid-str} (str short-id "...")]]
     [:td (when rank (format "%.3f" (double rank)))]
     [:td (if snippet
            [:span {:style "font-size: 0.85em;"}
             [:span {:dangerouslySetInnerHTML {:__html snippet}}]]
            [:em {:style "color: var(--muted-color);"} "-"])]
     [:td (if metadata
            [:code {:style "font-size: 0.75em;"} (pr-str metadata)]
            [:em {:style "color: var(--muted-color);"} "-"])]]))

(defn search-results-fragment
  "Render the HTMX search results area (replaces #search-results).

   Args:
     results - vector of SearchResult maps
     query   - string (displayed in header)
     total   - integer (total matching docs)
     took-ms - integer (query time)"
  [results query total took-ms]
  [:div#search-results
   [:div {:style "display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;"}
    [:small {:style "color: var(--muted-color);"}
     [:t :search/result-summary {:total total :query (or query "") :time (or took-ms 0)}]]
    (when (> total (count results))
      [:small {:style "color: var(--muted-color);"}
       [:t :search/result-showing-first {:count (count results)}]])]
   (if (seq results)
     (table-wrapper
      [:table {:class "data-table ui-table"}
       [:thead
        [:tr
         [:th [:t :search/result-column-entity-type]]
         [:th [:t :search/result-column-entity-id]]
         [:th [:t :search/result-column-rank]]
         [:th [:t :search/result-column-snippet]]
         [:th [:t :search/result-column-metadata]]]]
       [:tbody
        (for [r results]
          (search-result-row r))]])
     [:div.empty-state
      (icon :search {:size 32})
      [:p [:t :search/result-empty-state {:query (or query "")}]]])])

(defn index-detail-page
  "Render the search index detail page with live search test form.

   Args:
     index-info - map: {:id :entity-type :language :fields :doc-count}
     results    - SearchResponse map or nil (shown when a search was performed)
     query      - string (previous search query, for repopulating the form)
     opts       - map with :user, :flash (passed to page-layout)"
  [index-info results query opts]
  (let [{:keys [user flash]} opts
        {:keys [id entity-type language fields doc-count]} index-info
        index-name (name id)]
    (admin-page
     [:t :search/page-detail-title {:index-name index-name}]
     [:div {:style "padding: 1.5rem;"}
      [:nav {:aria-label [:t :common/aria-breadcrumb]
             :style "margin-bottom: 1rem; font-size: 0.9em;"}
       [:a {:href "/web/admin/search"} [:t :search/breadcrumb-indices]]
       " / "
       [:code {:style "font-size: inherit;"} index-name]]
      [:div {:style "display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; margin-bottom: 1.5rem;"}
       [:article
        [:header [:h3 {:style "margin: 0;"} [:t :search/card-info-title]]]
        [:dl {:style "display: grid; grid-template-columns: 120px 1fr; gap: 0.4rem 1rem; margin: 0;"}
         [:dt [:t :search/info-entity-type]] [:dd [:code (name entity-type)]]
         [:dt [:t :search/info-language]]    [:dd (language-badge language)]
         [:dt [:t :search/info-documents]]   [:dd (doc-count-badge doc-count)]]]
       [:article
        [:header [:h3 {:style "margin: 0;"} [:t :search/card-fields-title]]]
        (if (seq fields)
          [:div {:style "display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.5rem;"}
           (for [{fname :name weight :weight} fields]
             [:span {:style "display: flex; align-items: center; gap: 4px;"}
              [:code (name fname)]
              (field-weight-badge weight)])]
          [:em {:style "color: var(--muted-color);"} [:t :search/fields-empty-state]])]]
      [:article
       [:header [:h3 {:style "margin: 0 0 0.75rem;"} [:t :search/card-test-title]]]
       [:form {:hx-post   (str "/web/admin/search/" index-name "/search")
               :hx-target "#search-results"
               :hx-swap   "outerHTML"
               :hx-indicator "#search-spinner"}
        [:div {:style "display: flex; gap: 0.75rem; align-items: flex-end;"}
         [:div {:style "flex: 1;"}
          [:input {:type        "text"
                   :name        "query"
                   :id          "search-query"
                   :placeholder [:t :search/test-form-placeholder]
                   :value       (or query "")
                   :style       "width: 100%;"}]]
         [:button {:type "submit"}
          (icon :search {:size 16})
          " " [:t :common/button-search]
          [:span#search-spinner.htmx-indicator " ..."]]]]]
      (if results
        (search-results-fragment
         (:results results)
         (:query results)
         (:total results)
         (:took-ms results))
        [:div#search-results
         [:div.empty-state
          (icon :search {:size 32})
          [:p [:t :search/test-form-empty-state]]]])]
     {:user  user
      :flash flash})))
