(ns wagoe.shared.ui.core.shell
  "The admin shell: collapsible sidebar, topbar and user menu around a page.

   Here rather than in admin so that modules admin depends on (user) can
   render in it. The sidebar shows the navigation modules contribute
   (`wagoe.platform.core.http.nav`), or a page's own `:nav`, a vector of groups:

     [{:id    :user                  ; a collapsible module group; without
       :label \"Users\" :icon :users  ; an id, a plain list under :label
       :open? true
       :items [{:href \"/web/admin/users\" :label \"Users\"
                :icon :users :active? true}]}]"
  (:require [wagoe.core.utils.text :as text]
            [wagoe.shared.ui.core.alpine :as alpine]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.platform.core.http.pages :as pages]
            [clojure.string :as str]))

(defn- nav-link [attrs icon label]
  [:a (merge attrs (alpine/sidebar-nav-link-attrs))
   (icons/icon icon {:size 20})
   [:span.nav-text label]])

(defn- nav-items [items & [title]]
  [:ul.entity-list
   ;; A collapsed sidebar shows a module's items in a flyout, under its name
   (when title [:li.nav-flyout-title title])
   (for [item items]
     [:li {:class (when (:active? item) "active")}
      ;; Always an icon: a collapsed sidebar hides the label
      (nav-link {:href (:href item)} (:icon item :file) (:label item))])])

(defn- nav-group [{:keys [id label icon items open?]}]
  (if id
    ;; navGroup (admin-ux.js) remembers open/closed per module; until it has
    ;; run, the group of the current page is the open one.
    [:div {:class  (cond-> "nav-module" open? (str " has-active"))
           :x-data (str "navGroup('" (name id) "', " (boolean open?) ")")}
     [:button.nav-module-toggle {:type                 "button"
                                 :aria-expanded        (str (boolean open?))
                                 :x-bind:aria-expanded "open"
                                 (keyword "@click")    "toggle()"}
      (icons/icon (or icon :folder) {:size 20})
      [:span.nav-text label]
      (icons/icon :chevron-down {:size 14 :class "nav-chevron"})]
     (nav-items items label)]
    (list
     (when label [:h3 label])
     (nav-items items))))

(defn user-nav
  "The groups of the request's navigation that `user` may open; nil outside a
   request."
  [user]
  (when-let [{:keys [groups uri]} nav/*nav*]
    (nav/for-user groups uri user)))

(defn sidebar
  "The sidebar for `nav` (see the ns docstring). `opts` supports :logo-url for
   a custom brand image."
  ([nav]
   (sidebar nav {}))
  ([nav opts]
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
     (map nav-group nav)]
    [:div.admin-sidebar-footer
     (nav-link {:href home/*path*} :home [:t :admin/sidebar-dashboard])
     (nav-link {:href "/web"} :external-link [:t :admin/sidebar-main-site])]]))

(defn- user-initials
  "One or two letters for the user menu: from the display name's words, else
   the email's local part."
  [user]
  (let [name-words (some-> (:display-name user) str/trim not-empty (str/split #"\s+"))
        from-email (some-> (:email user) (str/split #"@") first)]
    (text/upper-case
     (cond
       (< 1 (count name-words)) (str (ffirst name-words) (first (last name-words)))
       (seq name-words)         (subs (first name-words) 0 (min 2 (count (first name-words))))
       (seq from-email)         (subs from-email 0 (min 2 (count from-email)))
       :else                    "?"))))

(defn admin-shell
  "`content` in the shell. `opts`: :user, :page-title, :logo-url, and :nav to
   replace the request's navigation (see the ns docstring)."
  [content opts]
  (let [{:keys [user nav page-title]} opts]
    ;; A wrapper div rather than a fragment, for Alpine's initialisation. The
    ;; sidebar store is initialised in admin-ux.js (external script, CSP-safe).
    [:div.hiccup-fragment-wrapper
     [:div.admin-shell (alpine/sidebar-shell-attrs)
      (sidebar (or nav (user-nav user)) opts)
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
             (when (pages/mounted? "/web/logout")
               [:form {:method "POST" :action "/web/logout" :class "logout-form"}
                (csrf/hidden-field)
                [:button {:type "submit" :class "logout-button"}
                 (icons/icon :log-out {:size 14})
                 [:span [:t :admin/button-logout]]]])]])]]
       [:main.admin-content
        content]]]]))

(defn shell-page
  "A complete page titled `title` with `content` in the shell. `opts` as for
   `admin-shell`, plus :flash."
  [title content opts]
  (layout/admin-pilot-page-layout
   title
   (admin-shell content opts)
   {:user        (:user opts)
    :flash       (:flash opts)
    :skip-header true}))
