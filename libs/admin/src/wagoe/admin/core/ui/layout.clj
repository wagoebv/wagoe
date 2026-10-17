(ns wagoe.admin.core.ui.layout
  "Admin shell/layout, error pages, confirmation dialog, and URL utilities.

   Pure Hiccup generators for the admin chrome: sidebar navigation, the
   collapsible shell + topbar, the dashboard home, 403/404 error pages, the
   delete-confirmation modal, and the `build-table-url` helper."
  (:require [wagoe.shared.ui.core.home :as home]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.alpine :as alpine]
            [wagoe.platform.core.csrf :as csrf]
            [clojure.string :as str]))

;; =============================================================================
;; Admin Layout Components
;; =============================================================================

(defn admin-sidebar
  "Admin sidebar with entity navigation and icons.

   Args:
     entities: Vector of entity name keywords available to user
     entity-configs: Map of entity-name -> entity-config
     current-entity: Currently active entity name (optional)
     opts: Optional map; supports :logo-url for a custom brand image

   Returns:
     Hiccup sidebar structure with entity list"
  ([entities entity-configs current-entity]
   (admin-sidebar entities entity-configs current-entity {}))
  ([entities entity-configs current-entity opts]
   ;; Alpine.js sidebar - hover expand/collapse via $store.sidebar
   [:aside.admin-sidebar (alpine/sidebar-attrs)
    [:div.admin-sidebar-header
     ;; One row the height of the topbar: the mark at full height and the
     ;; wordmark. The text is nav-text, so a collapsed sidebar keeps the mark.
     [:a {:class "sidebar-brand" :href "/web/admin/"}
      (if-let [logo-url (:logo-url opts)]
        [:img.sidebar-brand-logo {:src logo-url :alt ""}]
        (list
         (icons/brand-logo {:size 48 :variant :icon :class "sidebar-brand-mark"})
         [:span {:class "nav-text sidebar-wordmark"} "wagoe"]))]
     [:div.sidebar-controls
      [:button.sidebar-pin {:type "button"
                            :aria-label [:t :admin/sidebar-pin-button]
                            :title [:t :admin/sidebar-pin-hint]
                            (keyword "@click") "$store.sidebar.togglePin()"
                            :x-bind:aria-pressed "$store.sidebar.pinned"}
       (icons/icon :pin {:size 16})]
      [:button.sidebar-toggle {:type "button"
                               :aria-label [:t :admin/sidebar-toggle-button]
                               :title [:t :admin/sidebar-toggle-hint]
                               (keyword "@click") "$store.sidebar.toggle()"}
       (icons/icon :panel-left-close {:size 16})]]]
    [:nav.admin-sidebar-nav
     [:h3 [:t :admin/sidebar-entities-title]]
     [:ul.entity-list
      (for [entity entities
            :let  [entity-config (get entity-configs entity)]
            :when (not (:sidebar-hidden entity-config))]
        (let [label      (:label entity-config (str/capitalize (name entity)))
              icon       (keyword (or (:icon entity-config) "database"))
              is-active? (= entity current-entity)]
          [:li {:class (when is-active? "active")}
           [:a (merge {:href (str "/web/admin/" (name entity))
                       :data-label label}
                      (alpine/sidebar-nav-link-attrs))
            (icons/icon icon {:size 20})
            [:span.nav-text label]]]))]]
    [:div.admin-sidebar-footer
     [:a (merge {:href home/*path*}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :home {:size 20})
      [:span.nav-text [:t :admin/sidebar-dashboard]]]
     [:a (merge {:href "/web"}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :external-link {:size 20})
      [:span.nav-text [:t :admin/sidebar-main-site]]]]]))

(defn- user-initials
  "One or two letters for the user menu: from the display name's words, else
   the email's local part."
  [user]
  (let [name-words (some-> (:display-name user) str/trim not-empty (str/split #"\s+"))
        from-email (some-> (:email user) (str/split #"@") first)]
    (str/upper-case
     (cond
       (< 1 (count name-words)) (str (ffirst name-words) (first (last name-words)))
       (seq name-words)         (subs (first name-words) 0 (min 2 (count (first name-words))))
       (seq from-email)         (subs from-email 0 (min 2 (count from-email)))
       :else                    "?"))))

(defn admin-shell
  "New admin shell layout with collapsible sidebar (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash, :page-title

   Returns:
     Admin shell structure with sidebar and topbar"
  [content opts]
  (let [{:keys [user current-entity entities entity-configs page-title]} opts]
    ;; Alpine.js sidebar shell - replaces sidebar.js
    ;; State is persisted to localStorage via $store.sidebar
    ;; Note: Using a wrapper div instead of fragment [:<>] for Hiccup compatibility with Alpine.js initialization
    [:div.hiccup-fragment-wrapper
     ;; Alpine sidebar store is initialized in admin-ux.js (external script, CSP-safe)
     ;; Toast notification container
     [:div.toast-container {:role "status" :aria-live "polite"}]
     [:div.admin-shell (alpine/sidebar-shell-attrs)
      (admin-sidebar entities entity-configs current-entity opts)
      [:div.admin-overlay (alpine/sidebar-overlay-attrs)]
      [:div.admin-main
       [:header.admin-topbar
        [:button.mobile-menu-toggle (merge {:type "button"
                                            :aria-label [:t :admin/menu-open-button]}
                                           (alpine/mobile-menu-toggle-attrs))
         (icons/icon :menu {:size 24})]
        [:h1 {:class "admin-topbar-title"} (or page-title [:t :admin/page-heading])]
        [:div.admin-topbar-actions
         (icons/theme-toggle-button)
         ;; Who is signed in, and signing out, behind the initials: the
         ;; topbar keeps its room for the page
         (let [who (:display-name user (:email user))]
           [:details {:class "user-menu"}
            [:summary {:class "user-menu-button" :aria-label [:t :admin/user-menu] :title (str who)}
             (user-initials user)]
            [:div {:class "user-menu-panel"}
             [:div {:class "user-menu-who"}
              [:span [:t :admin/header-welcome {:name who}]]
              (when (and (:display-name user) (:email user))
                [:span {:class "user-menu-email"} (:email user)])]
             [:form {:method "POST" :action "/web/logout" :class "logout-form"}
              (csrf/hidden-field)
              [:button {:type "submit" :class "logout-button"}
               (icons/icon :log-out {:size 14})
               [:span [:t :admin/button-logout]]]]]])]]
       [:main.admin-content
        content]]]]))

(defn admin-layout
  "Main admin layout with new shell structure (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash

   Returns:
     Complete HTML page structure with new admin shell"
  [content opts]
  (let [{:keys [user current-entity entity-configs flash]} opts
        title (if current-entity
                [:t :admin/page-title {:label (:label (get entity-configs current-entity))}]
                [:t :admin/page-title-dashboard])
        page-title (when current-entity
                     (:label (get entity-configs current-entity)))]
    (layout/admin-pilot-page-layout
     title
     (admin-shell content (assoc opts :page-title page-title))
     {:user user
      :flash flash
      :skip-header true})))

(def page-cards
  "Dashboard cards for pages other modules serve; a card is shown only when its
   page is mounted."
  [{:href "/web/audit" :icon :file-text
    :title [:t :admin/audit-trail-title] :description [:t :admin/audit-trail-description]}
   {:href "/web/admin/groups" :icon :users
    :title [:t :admin/groups-title] :description [:t :admin/groups-description]}])

(defn admin-home
  "Admin dashboard home page content.

   Args:
     entities: Vector of available entity names
     entity-configs: Map of entity configurations
     stats: Optional map of dashboard statistics
     mounted?: Optional predicate on a `page-cards` href; default shows them all

   Returns:
     Hiccup structure for dashboard"
  [entities entity-configs & [stats mounted?]]
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
    (for [{:keys [href icon title description]} page-cards
          :when ((or mounted? (constantly true)) href)]
      [:div.entity-card
       [:a {:href href :class "entity-card-link"}
        [:div.entity-card-icon (icons/icon icon {:size 20})]
        [:div.entity-card-title title]
        [:div.entity-card-description description]]])]])

;; =============================================================================
;; Confirmation Dialog
;; =============================================================================

(defn confirm-delete-dialog
  "Confirmation dialog for delete operations.

   Args:
     entity-name: Keyword entity name
     record-id: ID of record to delete

   Returns:
     Hiccup modal structure"
  [entity-name record-id]
  [:div.modal#confirm-delete-modal
   [:div.modal-content
    [:h3 [:t :admin/modal-confirm-delete-title]]
    [:p [:t :admin/modal-confirm-delete-message]]
    [:div.modal-actions
     [:button.button.danger
      {:hx-delete (str "/web/admin/" (name entity-name) "/" record-id)
       :hx-target "#entity-table-container"}
      [:t :common/button-delete]]
     [:button.button.secondary
      {:onclick "closeModal('confirm-delete-modal')"}
      [:t :common/button-cancel]]]]])

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
   create form, so no record of it can be created (BOU-494).

   Args:
     entity-name:   Keyword entity name
     entity-config: Its config, carrying :create-config-errors"
  [entity-name entity-config]
  (let [label (:label entity-config)
        label (if (string? label) label (name entity-name))]
    [:div.error-page.admin-config-error
     [:h1 [:t :admin/page-config-error-title]]
     (for [{:keys [column field]} (:create-config-errors entity-config)]
       [:p.error-message [:t :admin/config-error-not-on-form
                          {:entity label :column column :field (name field)}]])
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
