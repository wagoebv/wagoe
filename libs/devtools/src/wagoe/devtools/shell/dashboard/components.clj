(ns wagoe.devtools.shell.dashboard.components
  "Reusable Hiccup UI components for the dev dashboard."
  (:require [clojure.string :as str]))

(defn stat-card
  "Renders a stat card with a label, value, and optional sub text.
   Options: {:label :value :value-class :sub :sub-class}"
  [{:keys [label value value-class sub sub-class]}]
  [:div.stat-card
   [:div.stat-label label]
   [:div.stat-value {:class value-class} value]
   (when sub
     [:div.stat-sub {:class sub-class} sub])])

(defn method-badge
  "Renders a color-coded HTTP method badge.
   method — keyword :get :post :put :delete :patch"
  [method]
  (let [method-name (str/upper-case (name method))
        css-class   (str "method-badge method-" (name method))]
    [:span {:class css-class} method-name]))

(defn status-dot
  "Renders a status indicator dot.
   status — :healthy :error :warning"
  [status]
  (let [css-class (str "status-dot status-" (name status))]
    [:span {:class css-class}]))

(defn count-badge
  "Renders a count badge with an optional variant class.
   count   — number to display
   variant — optional string for additional CSS variant class"
  [count variant]
  (let [css-class (if variant (str "count-badge count-badge-" variant) "count-badge")]
    [:span {:class css-class} count]))

(defn data-table
  "Renders a grid-based data table with a header row and data rows.
   opts — {:columns [\"Name\" ...] :col-template \"1fr 1fr\" :rows [{:cells [...] :attrs {...}} ...]}"
  [{:keys [columns col-template rows]}]
  (let [grid-style (str "grid-template-columns:" col-template)]
    [:div.data-table
     ;; Header row
     (into [:div.data-table-header {:style grid-style}]
           (map (fn [col] [:div.data-table-cell.header-cell col]) columns))
     ;; Data rows
     (for [{:keys [cells attrs]} rows]
       (into [:div.data-table-row (merge {:style grid-style} attrs)]
             (map (fn [cell] [:div.data-table-cell cell]) cells)))]))

(defn card
  "Renders a card component with an optional header title and right slot.
   opts — {:title :right :flush?}
   body — child hiccup elements"
  [{:keys [title right flush?]} & body]
  [:div.card {:class (when flush? "card-flush")}
   (when (or title right)
     [:div.card-header
      (when title [:div.card-title title])
      (when right [:div.card-header-right right])])
   (into [:div.card-body] body)])

(defn detail-panel
  "Renders a detail panel. :error variant adds a red border class.
   opts     — {:variant :error|nil}
   children — child hiccup elements"
  [{:keys [variant]} & children]
  (let [css-class (str "detail-panel" (when (= variant :error) " detail-panel-error"))]
    (into [:div {:class css-class}] children)))

(defn filter-bar
  "Renders a flex container for filter controls."
  [& children]
  (into [:div.filter-bar] children))

(defn filter-input
  "Renders a filter text input.
   attrs — HTML attribute map (e.g. {:name :placeholder :value})"
  [attrs]
  [:input.filter-input (merge {:type "text"} attrs)])

(defn filter-select
  "Renders a filter select dropdown.
   attrs   — HTML attribute map
   options — seq of {:value :label} maps"
  [attrs options]
  (into [:select.filter-select attrs]
        (map (fn [{:keys [value label]}]
               [:option {:value value} label])
             options)))

(defn interceptor-chain
  "Renders a horizontal interceptor chain flow diagram.
   steps — seq of {:name :type} maps"
  [steps]
  [:div.interceptor-chain
   (interpose
    [:span.interceptor-arrow "→"]
    (map (fn [{:keys [name type]}]
           [:div.interceptor-step {:class (str "interceptor-" (clojure.core/name type))}
            [:span.interceptor-name name]])
         steps))])

(defn fix-box
  "Renders a fix suggestion box with REPL and CLI commands.
   opts — {:repl-command :cli-command}"
  [{:keys [repl-command cli-command]}]
  [:div.fix-box
   (when repl-command
     [:div.fix-repl
      [:span.fix-label "REPL: "]
      [:code.fix-command repl-command]])
   (when cli-command
     [:div.fix-cli
      [:span.fix-label "CLI: "]
      [:code.fix-command cli-command]])])

(defn code-block
  "Renders a preformatted code block."
  [content]
  [:pre.code-block content])
