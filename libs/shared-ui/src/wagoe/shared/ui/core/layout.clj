(ns wagoe.shared.ui.core.layout
  "Layout components for consistent page structure.

   Contains page layout, navigation, and structural components that provide
   consistent look and feel across all domain modules."
  (:require [wagoe.shared.ui.core.alpine :as alpine]
            [wagoe.shared.ui.core.components :as components]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.ui-style :as ui-style]))

(def ^:private default-css
  (ui-style/bundle :base))

(def ^:private default-js
  (ui-style/js-bundle :base))

(def pilot-css
  (ui-style/bundle :pilot))

(def admin-pilot-css
  (ui-style/bundle :admin-pilot))

(def pilot-js
  (ui-style/js-bundle :pilot))

(def admin-pilot-js
  (ui-style/js-bundle :admin-pilot))

(defn platform-admin?
  "Returns true when the user is a platform-level admin without tenant context."
  [user]
  (and user
       (= :admin (:role user))
       (nil? (:tenant-id user))))

(defn main-navigation
  "Main site navigation component.
   
   Args:
     opts: Optional map with navigation configuration
     
   Returns:
     Hiccup navigation structure"
  [& [opts]]
  (let [{:keys [user brand brand-href]} opts]
    [:nav
     [:a.logo {:href (or brand-href (if user "/web/dashboard" "/"))}
      (or brand
          (icons/brand-logo {:size 140}))]
     (when (platform-admin? user)
       [:div.nav-links
        [:a {:href "/web/audit"}
         (icons/icon :file-text {:size 18})
         [:span {:class "ml-2"} "Audit Trail"]]])
     (if user
       [:div.user-nav
        (icons/theme-toggle-button)
        ;; Alpine.js powered dropdown - replaces app.js toggleUserDropdown()
        [:div.user-dropdown (alpine/dropdown-attrs)
         [:button.dropdown-toggle (alpine/toggle-button-attrs)
          (icons/icon :user {:size 18})
          [:span {:class "ml-2"} (:name user)]
          (icons/icon :chevron-down {:size 16 :class "ml-1"})]
         [:div.dropdown-menu (merge {:x-show  "open"
                                     :x-cloak true}
                                    (alpine/x-transition {:origin "top right"}))
          [:a.dropdown-item {:href "/web/profile"}
           (icons/icon :user {:size 18})
           [:span "Profile & Security"]]
          [:div.dropdown-divider]
          [:form {:method "POST" :action "/web/logout"}
           (csrf/hidden-field)
           [:button.dropdown-item {:type "submit"}
            (icons/icon :log-out {:size 18})
            [:span "Logout"]]]]]]
       [:div.user-nav
        (icons/theme-toggle-button)
        [:a {:href "/web/login"} "Login"]])]))

(defn page-layout
  "Main page layout wrapper.
   
   Args:
     title: Page title string
     content: Main page content (Hiccup structure)
     opts: Optional map with :user, :flash, :css, :js, :skip-header, etc.
     
   Returns:
     Complete HTML page structure"
  [title content & [opts]]
  (let [{:keys [user flash css js skip-header body-attrs brand brand-href
                meta-description lang theme-color extra-head csrf-token]
         :or   {css         default-css
                js          default-js
                skip-header false
                body-attrs  {}
                lang        "en"}} opts
        ;; Falls back to the token bound for the current request, so handlers need
        ;; not thread it explicitly. An explicit :csrf-token opt still overrides.
        csrf-token (or csrf-token (csrf/current-token))]
    [:html {:lang lang}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      ;; CSRF token for HTMX requests — read by the htmx:configRequest listener in init.js.
      (when csrf-token
        [:meta {:name "csrf-token" :content csrf-token}])
      (when meta-description
        [:meta {:name "description" :content meta-description}])
      (when theme-color
        [:meta {:name "theme-color" :content theme-color}])
      [:title title]
      ;; Browsers request /favicon.ico whether or not a page links one, so the
      ;; file exists at that path and every app got a 404 in its access log
      ;; until it did. The PNG links are what modern browsers actually use;
      ;; they follow the theme, which the .ico cannot.
      [:link {:rel "icon" :sizes "any" :href "/favicon.ico"}]
      [:link {:rel "icon" :type "image/png" :href "/assets/wagoe-light-512-icon.png"
              :media "(prefers-color-scheme: light)"}]
      [:link {:rel "icon" :type "image/png" :href "/assets/wagoe-dark-512-icon.png"
              :media "(prefers-color-scheme: dark)"}]
      [:link {:rel "apple-touch-icon" :href "/assets/wagoe-light-512.png"}]
      [:script {:src "/js/init.js"}]
      extra-head
      (for [css-file css]
        [:link {:rel "stylesheet" :href css-file}])]
     [:body body-attrs
      (when-not skip-header
        [:header.site-header
         (main-navigation {:user user
                           :brand brand
                           :brand-href brand-href})])
      (let [children (cond-> []
                       flash (conj [:div.flash-messages
                                    (let [flash-type (or (:type flash)
                                                         (first (keys flash)))
                                          flash-msg  (or (:message flash)
                                                         (first (vals flash)))]
                                      [:div {:class (str "alert alert-" (name flash-type))}
                                       flash-msg])])
                       true (conj content))]
        (into [:main.main-content] children))
      (for [js-file js]
        [:script {:src js-file :defer true}])]]))

(defn pilot-page-layout
  "Page layout using shared daisyUI pilot styling."
  [title content & [opts]]
  (page-layout title
               content
               (merge {:css pilot-css
                       :js pilot-js
                       :body-attrs {:class "bg-base-200 text-base-content"}}
                      opts)))

(defn admin-pilot-page-layout
  "Page layout using admin daisyUI pilot styling."
  [title content & [opts]]
  (page-layout title
               content
               (merge {:css admin-pilot-css
                       :js admin-pilot-js
                       :body-attrs {:class "bg-base-200 text-base-content"}}
                      opts)))

(defn error-layout
  "Error page layout.
   
   Args:
     status: HTTP status code
     title: Error title
     message: Error message
     details: Optional error details
     
   Returns:
     Error page HTML structure"
  [status title message & [details]]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title (str status " - " title)]
    (for [css-file default-css]
      [:link {:rel "stylesheet" :href css-file}])]
   [:body
    [:div.error-page
     [:h1 title]
     [:p.error-message message]
     (when details
       [:div.error-details
        [:h3 "Details:"]
        [:pre details]])
     [:a.button {:href "/"} "Go Home"]]]])

(defn home-page-content
  "Default home page content.
   
   Returns:
     Hiccup structure for home page"
  []
  [:div.home-content
   [:h1 "Welcome to Wagoe Application"]
   [:p "This is a modular application built with Clojure."]
   [:div.navigation-links
    [:a.button {:href "/users"} "Manage Users"]]])

(defn render-error-page
  "Render a complete error page.
   
   Args:
     message: Error message
     status: HTTP status code (optional, defaults to 500)
     
   Returns:
     HTML string for error page"
  ([message]
   (render-error-page message 500))
  ([message status]
   (components/render-html
    (error-layout status "Error" message))))

(defn modal
  "Generic modal dialog component.
   
   Args:
     id: Modal ID for targeting
     title: Modal title
     content: Modal content (Hiccup structure)
     opts: Optional map with :closable, :size, etc.
     
   Returns:
     Hiccup modal structure"
  [id title content & [opts]]
  (let [{:keys [closable size]
         :or   {closable true size "medium"}} opts]
    [:div.modal {:id id :class (str "modal-" size)}
     [:div.modal-backdrop]
     [:div.modal-content
      [:div.modal-header
       [:h3 title]
       (when closable
         [:button.modal-close {:onclick (str "closeModal('" id "')")} "×"])]
      [:div.modal-body content]]]))

(defn sidebar
  "Sidebar component for layouts that need side navigation.
   
   Args:
     content: Sidebar content (Hiccup structure)
     opts: Optional map with :position, :collapsed, etc.
     
   Returns:
     Hiccup sidebar structure"
  [content & [opts]]
  (let [{:keys [position collapsed]
         :or   {position "left"}} opts]
    [:aside.sidebar {:class (str "sidebar-" position (when collapsed " collapsed"))}
     content]))

(defn breadcrumbs
  "Breadcrumb navigation component.
   
   Args:
     crumbs: Vector of [text url] pairs, last item is current page
     
   Returns:
     Hiccup breadcrumb structure"
  [crumbs]
  (when (seq crumbs)
    [:nav.breadcrumbs
     [:ol
      (for [[text url] crumbs]
        (if url
          [:li [:a {:href url} text]]
          [:li.current text]))]]))

(defn card
  "Card component for organizing content.
   
   Args:
     content: Card content (Hiccup structure)
     opts: Optional map with :title, :class, :actions, etc.
     
   Returns:
     Hiccup card structure"
  [content & [opts]]
  (let [{:keys [title class actions]} opts]
    [:div.card {:class class}
     (when title
       [:div.card-header
        [:h4 title]
        (when actions
          [:div.card-actions actions])])
     [:div.card-content content]]))
