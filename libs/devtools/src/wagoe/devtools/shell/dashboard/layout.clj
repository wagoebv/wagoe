(ns wagoe.devtools.shell.dashboard.layout
  "Dashboard shell: HTML wrapper, sidebar, top bar."
  (:require [wagoe.ui-style :as ui-style]
            [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private nav-items
  "Dashboard navigation items with unicode icons."
  [{:path "/dashboard"          :icon "◉" :label "Overview"}
   {:path "/dashboard/routes"   :icon "⇢" :label "Routes"}
   {:path "/dashboard/requests" :icon "⟳" :label "Requests"}
   {:path "/dashboard/schemas"  :icon "▤" :label "Schemas"}
   {:path "/dashboard/db"       :icon "⊞" :label "Database"}
   {:path "/dashboard/errors"   :icon "⚠" :label "Errors"}
   {:path "/dashboard/jobs"     :icon "⚙" :label "Jobs"}
   {:path "/dashboard/config"    :icon "⚡" :label "Config"}
   {:path "/dashboard/security" :icon "🔒" :label "Security"}
   {:path "/dashboard/docs"     :icon "📖" :label "Docs"}])

(defn- sidebar [{:keys [active-path system-status]}]
  [:div.dashboard-sidebar {:x-data "{collapsed: false}" ":class" "collapsed && 'collapsed'"}
   [:div.sidebar-brand
    [:span.sidebar-brand-icon "⚡"]
    [:span.sidebar-brand-text "Boundary Dev"]]
   [:nav.sidebar-nav
    (for [{:keys [path icon label]} nav-items]
      [:a.nav-item {:href path :class (when (or (= path active-path)
                                                (and active-path
                                                     (not= path "/dashboard")
                                                     (str/starts-with? active-path path)))
                                        "active")}
       [:span.nav-icon icon]
       [:span.nav-label label]])]
   [:div.sidebar-footer
    [:div {:style "display:flex;align-items:center;gap:8px"}
     (if (= :error system-status)
       [:span.status-error "● error"]
       [:span.status-healthy "● running"])]]
   [:div.sidebar-toggle {"@click" "collapsed = !collapsed"}
    [:span {:x-text "collapsed ? '→' : '←'"} "←"]]])

(defn- topbar [{:keys [title component-count error-count http-port]}]
  [:div.dashboard-topbar
   [:span.topbar-title (or title "Dashboard")]
   [:span.topbar-status
    (str "running · " (or component-count 0) " components · " (or error-count 0) " errors")]
   (when http-port
     (list
      [:a.topbar-link {:href (str "http://localhost:" http-port) :target "_blank"} "App"]
      [:a.topbar-link {:href (str "http://localhost:" http-port "/web/admin/") :target "_blank"} "Admin"]))])

(defn dashboard-page
  "Wrap page content in the full dashboard shell.
   Options: :title :active-path :system-status :component-count :error-count :http-port"
  [opts & body]
  (let [{:keys [title]} opts
        js-files (ui-style/js-bundle :base)]
    (str
     "<!DOCTYPE html>"
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (str (or title "Dashboard") " — Boundary Dev")]
        [:link {:rel "stylesheet" :href "/assets/dashboard.css"}]
        (for [js-src js-files]
          [:script {:src js-src :defer true}])]
       [:body
        [:div.dashboard-layout
         (sidebar opts)
         [:div.dashboard-main
          (topbar opts)
          (into [:div.dashboard-content] body)]]]]))))
