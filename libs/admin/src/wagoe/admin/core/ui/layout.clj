(ns wagoe.admin.core.ui.layout
  "Admin shell/layout, error pages, and URL utilities.

   Pure Hiccup generators for the admin chrome: the sidebar navigation for the
   shell in `wagoe.shared.ui.core.shell`, the dashboard home, 403/404 error
   pages and the `build-table-url` helper."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.shell :as shell]
            [wagoe.platform.core.http.pages :as pages]))

;; =============================================================================
;; Admin Layout Components
;; =============================================================================

(defn entity-nav
  "The shell's sidebar navigation for the admin's entities: one group, an item
   per entity not marked :sidebar-hidden."
  [entities entity-configs current-entity]
  [{:label [:t :admin/sidebar-entities-title]
    :items (for [entity entities
                 :let  [entity-config (get entity-configs entity)]
                 :when (not (:sidebar-hidden entity-config))]
             {:href    (str "/web/admin/" (name entity))
              :label   (:label entity-config (text/capitalize (name entity)))
              :icon    (keyword (or (:icon entity-config) "database"))
              :active? (= entity current-entity)})}])

(defn admin-sidebar
  "The shell's sidebar listing `entities`. `opts` supports :logo-url."
  ([entities entity-configs current-entity]
   (admin-sidebar entities entity-configs current-entity {}))
  ([entities entity-configs current-entity opts]
   (shell/sidebar (entity-nav entities entity-configs current-entity) opts)))

(defn- with-nav
  "`opts` with the shell's :nav built from its :entities."
  [{:keys [entities entity-configs current-entity] :as opts}]
  (assoc opts :nav (entity-nav entities entity-configs current-entity)))

(defn admin-shell
  "`content` in the shell, its sidebar listing the admin's entities.
   `opts`: :user, :current-entity, :entities, :entity-configs, :page-title,
   :logo-url."
  [content opts]
  (shell/admin-shell content (with-nav opts)))

(defn admin-layout
  "A complete admin page: `content` in the shell.

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash

   Returns:
     Complete HTML page structure with new admin shell"
  [content opts]
  (let [{:keys [current-entity entity-configs]} opts
        title (if current-entity
                [:t :admin/page-title {:label (:label (get entity-configs current-entity))}]
                [:t :admin/page-title-dashboard])
        page-title (when current-entity
                     (:label (get entity-configs current-entity)))]
    (shell/shell-page title content
                      (assoc (with-nav opts) :page-title page-title))))

(def page-cards
  "Dashboard cards for pages other modules serve; a card is shown only when its
   page is mounted and the user may open it. The audit trail's guard admits
   platform admins only."
  [{:href "/web/audit" :icon :file-text :visible? layout/platform-admin?
    :title [:t :admin/audit-trail-title] :description [:t :admin/audit-trail-description]}
   {:href "/web/admin/groups" :icon :users
    :title [:t :admin/groups-title] :description [:t :admin/groups-description]}])

(defn admin-home
  "Admin dashboard home page content.

   Args:
     entities: Vector of available entity names
     entity-configs: Map of entity configurations
     stats: Optional map of dashboard statistics
     user: Optional signed-in user, for the cards of `page-cards` it may open

   Returns:
     Hiccup structure for dashboard"
  [entities entity-configs & [stats user]]
  [:div.admin-home {:class "space-y-4"}
   [:section.admin-home-hero
    [:div.admin-home-hero-inner
     [:div
      [:span.admin-home-kicker [:t :admin/page-kicker]]
      [:h1 [:t :admin/page-heading]]
      [:p [:t :admin/page-description]]]]]
   [:div.entity-grid
    (for [entity entities]
      (let [entity-config (get entity-configs entity)
            label (:label entity-config)
            description (:description entity-config)
            icon (keyword (or (:icon entity-config) "database"))
            count (get-in stats [entity :count] 0)]
        [:div.entity-card
         [:a {:href (str "/web/admin/" (name entity))
              :class "entity-card-link"}
          [:div.entity-card-icon (icons/icon icon {:size 20})]
          [:div.entity-card-title label]
          (when description
            [:div.entity-card-description description])
          [:div.entity-card-meta
           [:span.entity-card-count [:t :admin/entity-card-count {:count count}]]]]]))
    (for [{:keys [href icon title description visible?]} page-cards
          :when (and (pages/mounted? href)
                     (or (nil? visible?) (visible? user)))]
      [:div.entity-card
       [:a {:href href :class "entity-card-link"}
        [:div.entity-card-icon (icons/icon icon {:size 20})]
        [:div.entity-card-title title]
        [:div.entity-card-description description]]])]])

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
