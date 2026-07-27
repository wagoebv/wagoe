(ns wagoe.devtools.shell.dashboard.pages.docs
  "Module documentation viewer for the dev dashboard.
   Serves README.md and AGENTS.md from libs/<module>/."
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; =============================================================================
;; Data
;; =============================================================================

(defn- safe-name?
  "Reject path components containing traversal sequences or separators."
  [s]
  (and (string? s)
       (seq s)
       (not (str/includes? s ".."))
       (not (str/includes? s "/"))
       (not (str/includes? s "\\"))))

(defn- module-dir
  "Resolve the libs/<module>/ directory. Returns a File or nil."
  [module-name]
  (when (safe-name? module-name)
    (let [dir (io/file "libs" module-name)]
      (when (.isDirectory dir) dir))))

(defn- read-doc-file
  "Read a documentation file from a module directory. Returns content string or nil."
  [module-name filename]
  (when (safe-name? filename)
    (when-let [dir (module-dir module-name)]
      (let [f (io/file dir filename)]
        (when (.exists f)
          (slurp f))))))

(defn- available-docs
  "Return a list of {:name :path} for available doc files in a module."
  [module-name]
  (when-let [dir (module-dir module-name)]
    (let [candidates ["AGENTS.md" "README.md"]]
      (filterv (fn [name]
                 (.exists (io/file dir name)))
               candidates))))

(defn- all-modules-with-docs
  "Scan libs/ for modules that have at least one doc file."
  []
  (let [libs-dir (io/file "libs")]
    (when (.isDirectory libs-dir)
      (->> (.listFiles libs-dir)
           (filter #(.isDirectory %))
           (filter (fn [d]
                     (or (.exists (io/file d "AGENTS.md"))
                         (.exists (io/file d "README.md")))))
           (map #(.getName %))
           sort))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn render-index
  "Render the docs index page listing all modules with their available docs."
  [opts]
  (let [modules (all-modules-with-docs)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/docs"
                  :title       "Module Documentation"})
     (c/card {:title "Module Documentation"
              :right [:span {:style "font-size:12px;color:var(--text-muted)"}
                      (str (count modules) " modules")]}
             (c/data-table
              {:columns      ["Module" "Documentation"]
               :col-template "200px 1fr"
               :rows         (for [m modules]
                               (let [docs (available-docs m)]
                                 {:cells [[:span {:style "font-family:var(--font-mono);font-weight:600"} m]
                                          [:div {:style "display:flex;gap:8px"}
                                           (for [d docs]
                                             [:a {:href  (str "/dashboard/docs/" m "/" d)
                                                  :style "font-size:12px;color:var(--accent-blue);text-decoration:none"}
                                              d])]]}))})))))

(defn render
  "Render the documentation viewer page for a specific module and file."
  [opts module-name filename]
  (let [content  (read-doc-file module-name filename)
        docs     (available-docs module-name)
        modules  (all-modules-with-docs)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/docs"
                  :title       (str module-name " — " filename)})
     [:div.two-col.two-col-sidebar
      ;; Sidebar: module list
      (c/card {:title "Modules"}
              [:div {:style "font-size:12px;line-height:2"}
               (for [m modules]
                 (let [active? (= m module-name)]
                   [:div {:style (when active? "font-weight:bold")}
                    [:a {:href  (str "/dashboard/docs/" m "/AGENTS.md")
                         :style (str "color:" (if active? "var(--accent-blue)" "var(--text-secondary)") ";text-decoration:none")}
                     m]]))])

      ;; Doc tabs + content
      (c/card {:title (str "libs/" module-name "/")
               :right [:div {:style "display:flex;gap:8px"}
                       (for [d docs]
                         (let [active? (= d filename)]
                           [:a {:href  (str "/dashboard/docs/" module-name "/" d)
                                :style (str "font-size:12px;padding:4px 8px;border-radius:4px;"
                                            "text-decoration:none;color:"
                                            (if active? "#0f172a" "var(--text-secondary)")
                                            ";background:"
                                            (if active? "var(--accent-blue)" "transparent"))}
                            d]))]}
              (if content
                [:div
                 [:div#doc-content.markdown-body {:style "max-height:80vh;overflow-y:auto"}]
                 [:textarea#doc-raw {:style "display:none"} content]
                 [:script {:src "/assets/marked.min.js"}]
                 [:script (h/raw
                           "document.getElementById('doc-content').innerHTML=marked.parse(document.getElementById('doc-raw').value);")]]
                [:div.empty-state
                 (str "File not found: libs/" module-name "/" filename)]))])))
