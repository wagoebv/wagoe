(ns wagoe.shared.ui.core.user-menu
  "Who is signed in, behind their avatar: name and email, extra links, the
   profile and signing out. The admin shell's topbar uses it; an app with its
   own layout puts it in its header and loads /css/user-menu.css."
  (:require [clojure.string :as str]
            [wagoe.core.utils.text :as text]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.core.http.pages :as pages]
            [wagoe.shared.ui.core.icons :as icons]))

(defn initials
  "One or two letters for the avatar: from the display name's words, else
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

(defn- avatar-src
  "The user's :avatar-url when it is an http(s) URL or a path on this site."
  [user]
  (some->> (:avatar-url user) str/trim (re-matches #"(?i)https?://\S+|/[^/\\]\S*")))

(defn avatar
  "The user's picture, else their initials."
  [user]
  (if-let [src (avatar-src user)]
    [:img {:class "user-menu-avatar" :src src :alt ""}]
    (initials user)))

(defn user-menu
  "The menu for `user`. `opts` :items, links shown above the profile, each
   {:href :label :icon}."
  [user & [{:keys [items]}]]
  (let [who (:display-name user (:email user))]
    [:details {:class "user-menu"}
     [:summary {:class "user-menu-button" :aria-label [:t :admin/user-menu] :title (str who)}
      (avatar user)]
     [:div {:class "user-menu-panel"}
      [:div {:class "user-menu-who"}
       [:span [:t :admin/header-welcome {:name who}]]
       (when (and (:display-name user) (:email user))
         [:span {:class "user-menu-email"} (:email user)])]
      (for [{:keys [href label icon]} items]
        [:a {:href href :class "user-menu-item"}
         (icons/icon (or icon :file) {:size 14})
         [:span label]])
      (when (pages/mounted? "/web/profile")
        [:a {:href "/web/profile" :class "user-menu-item"}
         (icons/icon :user {:size 14})
         [:span [:t :user/profile-page-title]]])
      (when (pages/mounted? "/web/logout")
        [:form {:method "POST" :action "/web/logout" :class "logout-form"}
         (csrf/hidden-field)
         [:button {:type "submit" :class "user-menu-item"}
          (icons/icon :log-out {:size 14})
          [:span [:t :admin/button-logout]]]])]]))
