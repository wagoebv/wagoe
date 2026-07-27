(ns wagoe.devtools.shell.dashboard.pages.config
  "Dashboard page for configuration viewing and editing."
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [wagoe.devtools.core.config-editor :as cfg-edit]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]))

(defn- config-section
  "Render a single top-level config section as an editable card.
   Strips ig/ref values to serializable placeholders so the textarea
   contains valid EDN that can round-trip through edn/read-string."
  [section-key section-val]
  (let [key-str (pr-str section-key)
        val-str (pr-str (cfg-edit/strip-refs section-val))]
    (c/card {:title key-str}
            [:div
             [:textarea.code-block
              {:name        (str "config-" key-str)
               :rows        (min 15 (max 3 (count (str/split-lines val-str))))
               :style       "width:100%;font-family:var(--font-mono);font-size:12px;background:var(--bg-inset);color:var(--fg-base);border:1px solid var(--border);padding:8px;resize:vertical"
               :data-original val-str}
              val-str]
             [:div {:style "display:flex;gap:8px;margin-top:8px;justify-content:flex-end"}
              [:button.filter-input
               {:hx-post    "/dashboard/fragments/config-preview"
                :hx-target  (str "#preview-" (hash key-str))
                :hx-swap    "innerHTML"
                :hx-include (str "[name='config-" key-str "']")
                :style      "cursor:pointer;padding:4px 12px;width:auto"}
               "Preview Changes"]
              [:button.filter-input
               {:hx-post    "/dashboard/fragments/config-apply"
                :hx-target  (str "#preview-" (hash key-str))
                :hx-swap    "innerHTML"
                :hx-include (str "[name='config-" key-str "']")
                :hx-confirm "Apply this config change? Affected components will restart."
                :style      "cursor:pointer;padding:4px 12px;width:auto;background:var(--accent-green);color:var(--bg-base)"}
               "Apply"]]
             [:div {:id (str "preview-" (hash key-str))}]])))

(defn- config-content
  "Render the config tree with editable sections.
   Uses real config values in the editable textareas (not redacted) so that
   Apply doesn't overwrite secrets with ********. This is a dev-only dashboard."
  [config]
  [:div
   [:div.stat-row
    (c/stat-card {:label "Components" :value (count config)})
    (c/stat-card {:label "Mode" :value "editable" :value-class "green"})
    (c/stat-card {:label "Status" :value "live" :sub "changes restart affected components"})]
   (for [[k v] (sort-by str config)]
     (config-section k v))])

(defn render
  "Render the Config Editor full page."
  [opts]
  (let [config (:config opts)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/config"
                  :title       "Config Editor"})
     (if config
       (config-content config)
       [:div.empty-state "No config available. Start the system with (go) first."]))))

(defn parse-edited-value
  "Parse an edited textarea value back to a Clojure value,
   restoring ig/ref placeholders to real refs."
  [new-val-str fallback]
  (try
    (cfg-edit/restore-refs (edn/read-string new-val-str))
    (catch Exception _ fallback)))

(defn render-preview-fragment
  "Render a diff preview of proposed config change."
  [section-key old-val new-val-str]
  (str (h/html
        (let [parsed (parse-edited-value new-val-str ::parse-failed)]
          (if (= parsed ::parse-failed)
            [:div.detail-panel
             [:p {:style "color:var(--color-red,#f87171)"} "Invalid EDN — please fix syntax errors before applying."]
             [:pre.code-block {:style "border-color:var(--color-red,#f87171)"}
              new-val-str]]
            (let [diff (cfg-edit/config-diff {section-key old-val}
                                             {section-key parsed})]
              (if (empty? (:changed diff))
                [:div.detail-panel [:p "No changes detected."]]
                [:div.detail-panel
                 [:p [:strong "Affected components: "]
                  (str/join ", " (map pr-str (cfg-edit/affected-components diff)))]
                 [:pre.code-block
                  (str "Current: " (pr-str old-val) "\n\nProposed: " new-val-str)]])))))))

(defn render-apply-result
  "Render the result of a config apply operation as an HTML fragment."
  [{:keys [success? restarted error]}]
  (str (h/html
        (if success?
          [:div.detail-panel
           [:p {:style "color:var(--accent-green)"} "✓ Config applied successfully"]
           (when (seq restarted)
             [:p (str "Restarted: " (str/join ", " (map pr-str restarted)))])]
          [:div.detail-panel.detail-panel-error
           [:p {:style "color:var(--color-red,#f87171)"} (str "✗ " (or error "Apply failed"))]]))))
