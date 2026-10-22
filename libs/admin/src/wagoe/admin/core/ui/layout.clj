(ns wagoe.admin.core.ui.layout
  "Admin nav contribution, page layout, error pages, and URL utilities.

   Pure Hiccup generators for the admin chrome: the admin's contribution to
   the sidebar of the shell in `wagoe.shared.ui.core.shell`, the dashboard
   home, 403/404 error pages and the `build-table-url` helper."
  (:require [wagoe.admin.core.schema-introspection :as introspection]
            [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.list :as list]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.shell :as shell]
            [wagoe.user.core.ui :as user-ui]))

;; =============================================================================
;; Admin Layout Components
;; =============================================================================

(defn nav
  "The admin's sidebar contribution (`wagoe.platform.core.http.nav`): an item
   per entity not marked :sidebar-hidden, which the platform places in the
   group of its module."
  [entities entity-configs]
  {:admin {:label [:t :admin/nav-title]
           :icon  :settings
           :items (vec (for [entity entities
                             :let  [entity-config (get entity-configs entity)]
                             :when (not (:sidebar-hidden entity-config))]
                         (cond-> {:entity entity
                                  :module (some-> (:module entity-config) keyword)
                                  :href   (str "/web/admin/" (name entity))
                                  :label  (introspection/plural-label entity-config entity)
                                  :icon   (keyword (or (:icon entity-config) "database"))
                                  :access :admin}
                           ;; For its dashboard card
                           (:description entity-config)
                           (assoc :description (:description entity-config)))))}})

(def admin-shell
  "`content` in the shell. `opts`: :user, :page-title, :logo-url."
  shell/admin-shell)

(defn admin-layout
  "A complete admin page: `content` in the shell.

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entity-configs, :flash

   Returns:
     Complete HTML page structure with new admin shell"
  [content opts]
  (let [{:keys [current-entity entity-configs]} opts
        title (if current-entity
                [:t :admin/page-title {:label (:label (get entity-configs current-entity))}]
                [:t :admin/page-title-dashboard])
        page-title (when current-entity
                     (:label (get entity-configs current-entity)))]
    (shell/shell-page title content (assoc opts :page-title page-title))))

(defn dashboard-groups
  "The sidebar's groups for `user`, which the dashboard has a section each of.
   Outside a request, the admin's own items for `entities`."
  [entities entity-configs user]
  (or (shell/user-nav user)
      (nav/collect [(nav entities entity-configs)] nil (constantly true))))

(defn- dashboard-tile
  "An entity as a tile: its count, \"—\" when counting failed, and its
   `:summary` values when `stats` has them."
  [{:keys [href icon label description entity] :or {icon :file}} stats {:keys [entity-configs display]}]
  (let [{:keys [summary] :as stat} (get stats entity)
        entity-config (get entity-configs entity)]
    [:div.entity-card
     [:a {:href href :class "entity-card-link"}
      [:div.entity-card-head
       [:span.entity-card-icon (icons/icon icon {:size 18})]
       [:span.entity-card-title label]]
      (when stat
        [:div.entity-card-meta
         (if-let [n (:count stat)]
           [:span.entity-card-count [:t :admin/entity-card-count {:count n} n]]
           [:span.entity-card-count {:title [:t :admin/dashboard-count-failed]} "—"])])
      (when (seq summary)
        (list/summary-line entity-config (assoc-in display [:overview :summary] summary)))
      (when description
        [:div.entity-card-description description])]]))

(defn- dashboard-link
  "A page that is not an entity: a tile like an entity's, without a count."
  [{:keys [href icon label description] :or {icon :file}}]
  [:div.entity-card
   [:a {:href href :class "entity-card-link dashboard-link" :title description}
    [:div.entity-card-head
     [:span.entity-card-icon (icons/icon icon {:size 18})]
     [:span.entity-card-title.dashboard-link-label label]]]])

(defn- activity-entry
  [{:keys [action result actor-email target-user-email created-at]} {:keys [now display]}]
  [:li.dashboard-activity-entry
   (user-ui/action-badge action)
   [:span.dashboard-activity-who
    (or actor-email [:em [:t :user/audit-actor-system]])
    (when target-user-email
      (list " → " target-user-email))
    ;; A failed login must not read as a login
    (when (= :failure result)
      (list " " (user-ui/result-badge result)))]
   (when created-at
     [:time.dashboard-activity-when {:datetime (str created-at)
                                     :title    (base/format-instant created-at display)}
      (or (base/relative-instant created-at now display)
          (base/format-instant created-at display))])])

(defn- activity-panel
  "The last audit entries, or why there are none. `activity`: {:href trail
   :entries [..]}, or :error? instead of :entries."
  [{:keys [href entries error?]} opts]
  [:section.dashboard-activity {:aria-labelledby "admin-home-activity"}
   [:div.dashboard-activity-head
    [:h2.admin-home-section-title {:id "admin-home-activity"}
     (icons/icon :clock {:size 18})
     [:span [:t :admin/dashboard-activity]]]
    [:a.dashboard-activity-all {:href href} [:t :admin/audit-trail-title]]]
   (cond
     error?          [:p.dashboard-activity-empty [:t :admin/dashboard-activity-failed]]
     (empty? entries) [:p.dashboard-activity-empty [:t :admin/dashboard-activity-empty]]
     :else           [:ol.dashboard-activity-list
                      (for [entry entries] (activity-entry entry opts))])])

(defn dashboard
  "Admin dashboard home page content: a section per group of `groups` (the
   sidebar's, see `dashboard-groups`), an entity as a tile, any other page as
   a compact link. `stats` maps an entity to {:count n :summary [..]}; a nil
   count shows \"—\". `opts`: :activity for the recent-activity panel (see
   `activity-panel`), and :entity-configs, :display and :now to format
   summaries and times."
  ([groups stats] (dashboard groups stats {}))
  ([groups stats {:keys [activity] :as opts}]
   [:div.admin-home
    [:header.admin-home-header
     [:h1 [:t :admin/page-heading]]]
    [:div {:class (if activity "admin-home-body with-activity" "admin-home-body")}
     [:div.admin-home-sections
      (for [{:keys [id label icon items]} groups
            ;; A group may have no id: the shell's plain list
            :let [heading (some->> id name (str "admin-home-"))
                  {tiles true links false} (group-by (comp some? :entity) items)]]
        [:section.admin-home-section {:aria-labelledby heading :data-group (some-> id name)}
         [:h2.admin-home-section-title {:id heading}
          (icons/icon (or icon :folder) {:size 18})
          [:span label]]
         [:div.entity-grid
          (for [item tiles]
            (dashboard-tile item stats opts))
          (map dashboard-link links)]])]
     (when activity
       (activity-panel activity opts))]]))

(defn admin-home
  "`dashboard` for the groups `dashboard-groups` gives `user`."
  [entities entity-configs & [stats user]]
  (dashboard (dashboard-groups entities entity-configs user) stats))

;; =============================================================================
;; Error Pages
;; =============================================================================

(defn admin-forbidden-page
  "403 Forbidden page for admin access denial.

   Args:
     reason: Why access was denied, shown instead of the generic message
     user: Current user (optional)

   Returns:
     Hiccup page structure"
  [reason & [user]]
  (layout/admin-pilot-page-layout
   [:t :admin/page-access-denied-title]
   [:div.error-page.admin-forbidden
    [:h1 "403 - " [:t :admin/page-access-denied-title]]
    (if reason
      [:p.error-message reason]
      [:p.error-message [:t :admin/page-access-denied-message]])
    [:div.error-actions
     (if user
       [:a.button {:href home/*path*} [:t :admin/button-go-dashboard]]
       [:a.button {:href "/web/login"} [:t :admin/button-login]])]]
   {:user user}))

(defn admin-not-found-page
  "404 Not Found page for admin entities.

   Args:
     entity-name: Entity that was not found
     user: Current user

   Returns:
     Hiccup page structure"
  [entity-name user]
  (layout/admin-pilot-page-layout
   [:t :admin/page-not-found-title]
   [:div.error-page.admin-not-found
    [:h1 "404 - " [:t :admin/page-not-found-title]]
    [:p.error-message [:t :admin/page-not-found-message {:name (name entity-name)}]]
    [:div.error-actions
     [:a.button {:href "/web/admin"} [:t :admin/button-back-to-admin]]]]
   {:user user}))

(defn create-config-error
  "Page content for an entity whose config leaves a NOT NULL column off the
   create form (BOU-494), or splits it over two tables with no create flow of
   its own, so no record of it can be created.

   Args:
     entity-name:   Keyword entity name
     entity-config: Its config, carrying :create-config-errors"
  [entity-name entity-config]
  (let [label (:label entity-config)
        label (if (string? label) label (name entity-name))]
    [:div.error-page.admin-config-error
     [:h1 [:t :admin/page-config-error-title]]
     (for [{:keys [kind column field]} (:create-config-errors entity-config)]
       [:p.error-message (if (= :split-table kind)
                           [:t :admin/config-error-split-table {:entity label}]
                           [:t :admin/config-error-not-on-form
                            {:entity label :column column :field (name field)}])])
     [:div.error-actions
      [:a.button {:href (str "/web/admin/" (name entity-name))} [:t :admin/button-back-to-list]]]]))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn build-table-url
  "Build table URL with query parameters.

   Args:
     entity-name: Keyword entity name
     opts: Map with :page, :page-size, :sort, :dir, :search, etc.

   Returns:
     URL string with query parameters"
  [entity-name opts]
  (let [base-url (str "/web/admin/" (name entity-name) "/table")
        params (table-ui/encode-query-params opts)]
    (if (seq params)
      (str base-url "?" params)
      base-url)))
