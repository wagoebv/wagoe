(ns wagoe.admin.core.ui.layout
  "Admin nav contribution, page layout, error pages, and URL utilities.

   Pure Hiccup generators for the admin chrome: the admin's contribution to
   the sidebar of the shell in `wagoe.shared.ui.core.shell`, the dashboard
   home, 403/404 error pages and the `build-table-url` helper."
  (:require [wagoe.admin.core.schema-introspection :as introspection]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.shell :as shell]))

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

(defn- dashboard-card
  "A sidebar item as a card: its icon, label and description, and an entity's
   record count when `stats` has one."
  [{:keys [href icon label description entity] :or {icon :file}} stats]
  [:div.entity-card
   [:a {:href href :class "entity-card-link"}
    [:div.entity-card-icon (icons/icon icon {:size 20})]
    [:div.entity-card-title label]
    (when description
      [:div.entity-card-description description])
    (when-let [n (get-in stats [entity :count])]
      [:div.entity-card-meta
       [:span.entity-card-count [:t :admin/entity-card-count {:count n} n]]])]])

(defn dashboard
  "Admin dashboard home page content: a section per group of `groups` (the
   sidebar's, see `dashboard-groups`), a card per item. `stats` maps an
   entity to {:count n}."
  [groups stats]
  [:div.admin-home {:class "space-y-4"}
   [:section.admin-home-hero
    [:div.admin-home-hero-inner
     [:div
      [:span.admin-home-kicker [:t :admin/page-kicker]]
      [:h1 [:t :admin/page-heading]]
      [:p [:t :admin/page-description]]]]]
   (for [{:keys [id label icon items]} groups
         ;; A group may have no id: the shell's plain list
         :let [heading (some->> id name (str "admin-home-"))]]
     [:section.admin-home-section {:aria-labelledby heading :data-group (some-> id name)}
      [:h2.admin-home-section-title {:id heading}
       (icons/icon (or icon :folder) {:size 18})
       [:span label]]
      [:div.entity-grid
       (for [item items]
         (dashboard-card item stats))]])])

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
