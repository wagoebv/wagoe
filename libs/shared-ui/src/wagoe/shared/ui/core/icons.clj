(ns wagoe.shared.ui.core.icons
  "Lucide-based icon system for Wagoe Framework.
   
   Provides inline SVG icons that work seamlessly with Hiccup.
   Icons are ISC-licensed from https://lucide.dev/
   
   Usage:
     (icon :home)
     (icon :users {:size 20 :class \"text-primary\"})
     (icon :chevron-right {:stroke-width 2.5})
     
   Available icons can be listed with (available-icons)")

(def ^:private default-attrs
  "Default SVG attributes for all icons."
  {:xmlns "http://www.w3.org/2000/svg"
   :viewBox "0 0 24 24"
   :fill "none"
   :stroke "currentColor"
   :stroke-width "2"
   :stroke-linecap "round"
   :stroke-linejoin "round"})

;; Icon path definitions (Lucide icons - ISC License)
;; Paths from https://github.com/lucide-icons/lucide
(def ^:private icon-paths
  "Map of icon names to their SVG path definitions."
  {;; ===== Navigation =====
   :home
   [[:path {:d "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"}]
    [:path {:d "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]]

   :menu
   [[:line {:x1 "4" :x2 "20" :y1 "12" :y2 "12"}]
    [:line {:x1 "4" :x2 "20" :y1 "6" :y2 "6"}]
    [:line {:x1 "4" :x2 "20" :y1 "18" :y2 "18"}]]

   :x
   [[:path {:d "M18 6 6 18"}]
    [:path {:d "m6 6 12 12"}]]

   :chevron-right
   [[:path {:d "m9 18 6-6-6-6"}]]

   :chevron-left
   [[:path {:d "m15 18-6-6 6-6"}]]

   :chevron-down
   [[:path {:d "m6 9 6 6 6-6"}]]

   :chevron-up
   [[:path {:d "m18 15-6-6-6 6"}]]

   ;; ===== Actions =====
   :plus
   [[:path {:d "M5 12h14"}]
    [:path {:d "M12 5v14"}]]

   :minus
   [[:path {:d "M5 12h14"}]]

   :edit
   [[:path {:d "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}]
    [:path {:d "m15 5 4 4"}]]

   :trash
   [[:path {:d "M3 6h18"}]
    [:path {:d "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"}]
    [:path {:d "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"}]]

   :save
   [[:path {:d "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"}]
    [:path {:d "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7"}]
    [:path {:d "M7 3v4a1 1 0 0 0 1 1h7"}]]

   :search
   [[:circle {:cx "11" :cy "11" :r "8"}]
    [:path {:d "m21 21-4.3-4.3"}]]

   :filter
   [[:polygon {:points "22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"}]]

   :refresh
   [[:path {:d "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}]
    [:path {:d "M21 3v5h-5"}]
    [:path {:d "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}]
    [:path {:d "M8 16H3v5"}]]

   :download
   [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
    [:polyline {:points "7 10 12 15 17 10"}]
    [:line {:x1 "12" :x2 "12" :y1 "15" :y2 "3"}]]

   :upload
   [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
    [:polyline {:points "17 8 12 3 7 8"}]
    [:line {:x1 "12" :x2 "12" :y1 "3" :y2 "15"}]]

   :copy
   [[:rect {:width "14" :height "14" :x "8" :y "8" :rx "2" :ry "2"}]
    [:path {:d "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]]

   :check
   [[:path {:d "M20 6 9 17l-5-5"}]]

   ;; ===== Status =====
   :check-circle
   [[:circle {:cx "12" :cy "12" :r "10"}]
    [:path {:d "m9 12 2 2 4-4"}]]

   :x-circle
   [[:circle {:cx "12" :cy "12" :r "10"}]
    [:path {:d "m15 9-6 6"}]
    [:path {:d "m9 9 6 6"}]]

   :alert-circle
   [[:circle {:cx "12" :cy "12" :r "10"}]
    [:line {:x1 "12" :x2 "12" :y1 "8" :y2 "12"}]
    [:line {:x1 "12" :x2 "12.01" :y1 "16" :y2 "16"}]]

   :info
   [[:circle {:cx "12" :cy "12" :r "10"}]
    [:path {:d "M12 16v-4"}]
    [:path {:d "M12 8h.01"}]]

   :loader
   [[:path {:d "M12 2v4"}]
    [:path {:d "m16.2 7.8 2.9-2.9"}]
    [:path {:d "M18 12h4"}]
    [:path {:d "m16.2 16.2 2.9 2.9"}]
    [:path {:d "M12 18v4"}]
    [:path {:d "m4.9 19.1 2.9-2.9"}]
    [:path {:d "M2 12h4"}]
    [:path {:d "m4.9 4.9 2.9 2.9"}]]

   ;; ===== Entities =====
   :users
   [[:path {:d "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"}]
    [:circle {:cx "9" :cy "7" :r "4"}]
    [:path {:d "M22 21v-2a4 4 0 0 0-3-3.87"}]
    [:path {:d "M16 3.13a4 4 0 0 1 0 7.75"}]]

   :user
   [[:path {:d "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"}]
    [:circle {:cx "12" :cy "7" :r "4"}]]

   :user-plus
   [[:path {:d "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"}]
    [:circle {:cx "9" :cy "7" :r "4"}]
    [:line {:x1 "19" :x2 "19" :y1 "8" :y2 "14"}]
    [:line {:x1 "22" :x2 "16" :y1 "11" :y2 "11"}]]

   :settings
   [[:path {:d "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"}]
    [:circle {:cx "12" :cy "12" :r "3"}]]

   :database
   [[:ellipse {:cx "12" :cy "5" :rx "9" :ry "3"}]
    [:path {:d "M3 5V19A9 3 0 0 0 21 19V5"}]
    [:path {:d "M3 12A9 3 0 0 0 21 12"}]]

   :file
   [[:path {:d "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"}]
    [:path {:d "M14 2v4a2 2 0 0 0 2 2h4"}]]

   :file-text
   [[:path {:d "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"}]
    [:path {:d "M14 2v4a2 2 0 0 0 2 2h4"}]
    [:path {:d "M10 9H8"}]
    [:path {:d "M16 13H8"}]
    [:path {:d "M16 17H8"}]]

   :folder
   [[:path {:d "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"}]]

   :inbox
   [[:polyline {:points "22 12 16 12 14 15 10 15 8 12 2 12"}]
    [:path {:d "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"}]]

   :mail
   [[:rect {:width "20" :height "16" :x "2" :y "4" :rx "2"}]
    [:path {:d "m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"}]]

   :calendar
   [[:path {:d "M8 2v4"}]
    [:path {:d "M16 2v4"}]
    [:rect {:width "18" :height "18" :x "3" :y "4" :rx "2"}]
    [:path {:d "M3 10h18"}]]

   :clock
   [[:circle {:cx "12" :cy "12" :r "10"}]
    [:polyline {:points "12 6 12 12 16 14"}]]

   :clipboard
   [[:rect {:width "8" :height "4" :x "8" :y "2" :rx "1" :ry "1"}]
    [:path {:d "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"}]]

   :briefcase
   [[:path {:d "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"}]
    [:rect {:width "20" :height "14" :x "2" :y "6" :rx "2"}]]

   :credit-card
   [[:rect {:width "20" :height "14" :x "2" :y "5" :rx "2"}]
    [:line {:x1 "2" :x2 "22" :y1 "10" :y2 "10"}]]

   :building-2
   [[:path {:d "M10 12h4"}]
    [:path {:d "M10 8h4"}]
    [:path {:d "M14 21v-3a2 2 0 0 0-4 0v3"}]
    [:path {:d "M6 10H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2"}]
    [:path {:d "M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"}]]

   ;; ===== Security =====
   :lock
   [[:rect {:width "18" :height "11" :x "3" :y "11" :rx "2" :ry "2"}]
    [:path {:d "M7 11V7a5 5 0 0 1 10 0v4"}]]

   :unlock
   [[:rect {:width "18" :height "11" :x "3" :y "11" :rx "2" :ry "2"}]
    [:path {:d "M7 11V7a5 5 0 0 1 9.9-1"}]]

   :shield
   [[:path {:d "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"}]]

   :key
   [[:path {:d "m15.5 7.5 2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4"}]
    [:path {:d "m21 2-9.6 9.6"}]
    [:circle {:cx "7.5" :cy "15.5" :r "5.5"}]]

   :eye
   [[:path {:d "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"}]
    [:circle {:cx "12" :cy "12" :r "3"}]]

   :eye-off
   [[:path {:d "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"}]
    [:path {:d "M14.084 14.158a3 3 0 0 1-4.242-4.242"}]
    [:path {:d "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"}]
    [:path {:d "m2 2 20 20"}]]

   ;; ===== UI Elements =====
   :more-horizontal
   [[:circle {:cx "12" :cy "12" :r "1"}]
    [:circle {:cx "19" :cy "12" :r "1"}]
    [:circle {:cx "5" :cy "12" :r "1"}]]

   :more-vertical
   [[:circle {:cx "12" :cy "12" :r "1"}]
    [:circle {:cx "12" :cy "5" :r "1"}]
    [:circle {:cx "12" :cy "19" :r "1"}]]

   :external-link
   [[:path {:d "M15 3h6v6"}]
    [:path {:d "M10 14 21 3"}]
    [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]]

   :log-out
   [[:path {:d "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}]
    [:polyline {:points "16 17 21 12 16 7"}]
    [:line {:x1 "21" :x2 "9" :y1 "12" :y2 "12"}]]

   :log-in
   [[:path {:d "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"}]
    [:polyline {:points "10 17 15 12 10 7"}]
    [:line {:x1 "15" :x2 "3" :y1 "12" :y2 "12"}]]

   ;; ===== Theme =====
   :sun
   [[:circle {:cx "12" :cy "12" :r "4"}]
    [:path {:d "M12 2v2"}]
    [:path {:d "M12 20v2"}]
    [:path {:d "m4.93 4.93 1.41 1.41"}]
    [:path {:d "m17.66 17.66 1.41 1.41"}]
    [:path {:d "M2 12h2"}]
    [:path {:d "M20 12h2"}]
    [:path {:d "m6.34 17.66-1.41 1.41"}]
    [:path {:d "m19.07 4.93-1.41 1.41"}]]

   :moon
   [[:path {:d "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"}]]

   ;; ===== Sidebar =====
   :panel-left
   [[:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}]
    [:path {:d "M9 3v18"}]]

   :panel-left-close
   [[:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}]
    [:path {:d "M9 3v18"}]
    [:path {:d "m16 15-3-3 3-3"}]]

   :panel-left-open
   [[:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}]
    [:path {:d "M9 3v18"}]
    [:path {:d "m14 9 3 3-3 3"}]]

   :pin
   [[:path {:d "M12 17v5"}]
    [:path {:d "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"}]]

   :pin-off
   [[:path {:d "M12 17v5"}]
    [:path {:d "M15 9.34V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H7.89"}]
    [:path {:d "m2 2 20 20"}]
    [:path {:d "M9 9v1.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h11"}]]

   ;; ===== Sorting =====
   :arrow-up
   [[:path {:d "m5 12 7-7 7 7"}]
    [:path {:d "M12 19V5"}]]

   :arrow-down
   [[:path {:d "M12 5v14"}]
    [:path {:d "m19 12-7 7-7-7"}]]

   :arrow-up-down
   [[:path {:d "m21 16-4 4-4-4"}]
    [:path {:d "M17 20V4"}]
    [:path {:d "m3 8 4-4 4 4"}]
    [:path {:d "M7 4v16"}]]

   ;; ===== Layout =====
   :layout-grid
   [[:rect {:width "7" :height "7" :x "3" :y "3" :rx "1"}]
    [:rect {:width "7" :height "7" :x "14" :y "3" :rx "1"}]
    [:rect {:width "7" :height "7" :x "14" :y "14" :rx "1"}]
    [:rect {:width "7" :height "7" :x "3" :y "14" :rx "1"}]]

   :layout-list
   [[:rect {:width "7" :height "7" :x "3" :y "3" :rx "1"}]
    [:rect {:width "7" :height "7" :x "3" :y "14" :rx "1"}]
    [:path {:d "M14 4h7"}]
    [:path {:d "M14 9h7"}]
    [:path {:d "M14 15h7"}]
    [:path {:d "M14 20h7"}]]})

(defn icon
  "Render a Lucide icon as inline SVG Hiccup.
   
   Args:
     icon-name: Keyword icon name (e.g., :home, :users, :settings)
     opts: Optional map with:
           :size - Width/height in pixels (default 24)
           :class - Additional CSS classes
           :stroke-width - SVG stroke width (default 2)
           :color - Icon color (default 'currentColor')
           :aria-label - Accessibility label
           
   Returns:
     Hiccup SVG vector, or nil if icon not found
     
   Examples:
     (icon :home)
     (icon :users {:size 20})
     (icon :settings {:class \"text-primary\" :size 16})
     (icon :edit {:aria-label \"Edit user\" :stroke-width 2.5})"
  ([icon-name]
   (icon icon-name {}))
  ([icon-name opts]
   (when-let [paths (get icon-paths icon-name)]
     (let [{:keys [size class stroke-width color aria-label]
            :or {size 24
                 stroke-width 2
                 color "currentColor"}} opts
           attrs (cond-> (merge default-attrs
                                {:width size
                                 :height size
                                 :stroke-width stroke-width
                                 :stroke color})
                   class (assoc :class class)
                   aria-label (assoc :aria-label aria-label))]
       (into [:svg attrs] paths)))))

(defn icon-button
  "Render an icon inside a button-friendly wrapper with accessibility support.
   
   Args:
     icon-name: Keyword icon name
     opts: Map with :label (for accessibility), :size, :class
     
   Returns:
     Hiccup span with icon and sr-only label
     
   Example:
     (icon-button :edit {:label \"Edit user\" :size 20})"
  [icon-name {:keys [label size class] :or {size 20}}]
  [:span {:class (str "icon-btn " class)}
   (icon icon-name {:size size :aria-label label})
   (when label
     [:span.sr-only label])])

(defn available-icons
  "Returns a sorted vector of all available icon names.
   
   Example:
     (available-icons)
     => [:alert-circle :arrow-down :arrow-up ...]"
  []
  (vec (sort (keys icon-paths))))

(defn theme-toggle-button
  "Render a theme toggle button with sun/moon icons.
   
   The button automatically switches between light and dark mode using
   the ThemeManager JavaScript module loaded via theme.js.
   
   Args:
     opts: Optional map with:
           :class - Additional CSS classes
           :size - Icon size in pixels (default 20)
           
   Returns:
     Hiccup button with theme toggle functionality
     
   Example:
     (theme-toggle-button)
     (theme-toggle-button {:class \"header-action\" :size 22})"
  ([]
   (theme-toggle-button {}))
  ([opts]
   (let [{:keys [class size] :or {size 20}} opts]
     [:button.theme-toggle
      {:type "button"
       :data-theme-toggle "true"
       :aria-label "Toggle theme"
       :class class}
      ;; Light mode icon (sun) - shown when in dark mode
      [:span {:data-theme-icon "light" :style "display: none;"}
       (icon :sun {:size size :aria-label "Switch to light mode"})]
      ;; Dark mode icon (moon) - shown when in light mode
      [:span {:data-theme-icon "dark"}
       (icon :moon {:size size :aria-label "Switch to dark mode"})]])))

(defn brand-logo
  "Render the Wagoe brand logo with automatic light/dark theme switching.
   
   Uses CSS to show the appropriate logo variant based on the current theme.
   In light mode, shows the dark logo (dark text on light background).
   In dark mode, shows the light logo (light text on dark background).
   
   Args:
     opts: Optional map with:
           :size - Height in pixels (default 32, max-width auto-scales)
           :class - Additional CSS classes
           :variant - :full (default) or :icon for icon-only version
           
   Returns:
     Hiccup span with both logo images, CSS controls visibility
     
   Example:
     (brand-logo)
     (brand-logo {:size 40 :class \"sidebar-logo\"})
     (brand-logo {:variant :icon})"
  ([]
   (brand-logo {}))
  ([opts]
   (let [{:keys [size class variant]
          :or {size 32 variant :full}} opts
         suffix (if (= variant :icon) "-icon" "")
         light-src (str "/assets/wagoe-light-512" suffix ".png")
         dark-src (str "/assets/wagoe-dark-512" suffix ".png")
         img-style (str "height: " size "px; width: auto;")]
     [:span.brand-logo
      {:class class}
      ;; Light theme logo (dark text) - shown when data-theme="light" or no theme set
      [:img.brand-logo-light
       {:src light-src
        :alt "Wagoe"
        :style img-style}]
      ;; Dark theme logo (light text) - shown when data-theme="dark"
      [:img.brand-logo-dark
       {:src dark-src
        :alt "Wagoe"
        :style img-style}]])))

(comment
  ;; Usage examples:

  ;; Basic icon
  (icon :home)

  ;; Icon with custom size
  (icon :users {:size 20})

  ;; Icon with custom styling
  (icon :settings {:class "text-primary-600" :size 16 :stroke-width 2.5})

  ;; Icon button with accessibility
  (icon-button :edit {:label "Edit user" :size 18})

  ;; List all available icons
  (available-icons)
  ;=> [:alert-circle :arrow-down :arrow-up :arrow-up-down :calendar ...]

  ;; Brand logo
  (brand-logo)
  (brand-logo {:size 40 :class "header-logo"}))
