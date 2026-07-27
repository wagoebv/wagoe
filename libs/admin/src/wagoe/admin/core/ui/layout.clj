(ns wagoe.admin.core.ui.layout
  "Admin shell/layout, error pages, confirmation dialog, and URL utilities.

   Pure Hiccup generators for the admin chrome: sidebar navigation, the
   collapsible shell + topbar, the dashboard home, 403/404 error pages, the
   delete-confirmation modal, and the `build-table-url` helper."
  (:require [wagoe.shared.ui.core.icons :as icons]
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
     [:div.sidebar-brand
      (if-let [logo-url (:logo-url opts)]
        [:img.sidebar-brand-logo {:src logo-url :alt ""}]
        (icons/brand-logo {:size 120 :class "sidebar-brand-logo"}))]
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
     [:a (merge {:href "/web/dashboard"}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :home {:size 20})
      [:span.nav-text [:t :admin/sidebar-dashboard]]]
     [:a (merge {:href "/web"}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :external-link {:size 20})
      [:span.nav-text [:t :admin/sidebar-main-site]]]]]))

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
        [:h1 {:class "text-lg font-semibold"} (or page-title [:t :admin/page-heading])]
        [:div.admin-topbar-actions
         [:span {:class "badge badge-ghost"} [:t :admin/header-welcome {:name (:display-name user (:email user))}]]
         (icons/theme-toggle-button)
         [:form {:method "POST" :action "/web/logout" :class "logout-form"}
          (csrf/hidden-field)
          [:button {:type "submit" :class "logout-button" :aria-label [:t :admin/button-logout]}
           [:span.logout-icon
            (icons/icon :log-out {:size 18 :aria-label [:t :admin/button-logout]})]
           [:span.sr-only [:t :admin/button-logout]]]]]]
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

(defn admin-home
  "Admin dashboard home page content.

   Args:
     entities: Vector of available entity names
     entity-configs: Map of entity configurations
     stats: Optional map of dashboard statistics

   Returns:
     Hiccup structure for dashboard"
  [entities entity-configs & [stats]]
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
    [:div.entity-card
     [:a {:href "/web/audit" :class "entity-card-link"}
      [:div.entity-card-icon (icons/icon :file-text {:size 20})]
      [:div.entity-card-title [:t :admin/audit-trail-title]]
      [:div.entity-card-description [:t :admin/audit-trail-description]]]]]])

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
     reason: Explanation of why access was denied
     user: Current user (optional)

   Returns:
     Hiccup page structure"
  [reason & [user]]
  (layout/admin-pilot-page-layout
   [:t :admin/page-access-denied-title]
   [:div.error-page.admin-forbidden
    [:h1 "403 - " [:t :admin/page-access-denied-title]]
    [:p.error-message [:t :admin/page-access-denied-message]]
    (when reason
      [:p.error-reason reason])
    [:div.error-actions
     (if user
       [:a.button {:href "/"} [:t :admin/button-go-dashboard]]
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
