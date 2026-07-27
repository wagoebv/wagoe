(ns wagoe.devtools.shell.dashboard.pages.routes
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [wagoe.devtools.shell.repl :as devtools-repl]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [hiccup2.core :as h]))

;; =============================================================================
;; Data
;; =============================================================================

(defn- route-data
  "Get routes. Prefers injected :http-handler from context, falls back to REPL state."
  ([] (route-data nil))
  ([ctx]
   (let [handler (or (:http-handler ctx)
                     (when-let [sys state/system]
                       (get sys :boundary/http-handler)))]
     (when handler
       (devtools-repl/extract-routes-from-handler handler)))))

(defn- filter-routes [routes {:keys [search module method]}]
  (cond->> routes
    (not (str/blank? search))
    (filter (fn [{:keys [path handler]}]
              (or (str/includes? (str path) search)
                  (str/includes? (str handler) search))))
    (not (str/blank? module))
    (filter (fn [r] (= (:module r) module)))
    (not (str/blank? method))
    (filter (fn [r] (= (name (:method r)) method)))))

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- all-modules [routes]
  (->> routes (keep :module) distinct sort))

(defn- all-methods [routes]
  (->> routes (map :method) distinct (sort-by name)))

(defn- handler-short-name [handler-str]
  (when (string? handler-str)
    (let [clean (-> handler-str
                    (str/replace #"@[a-f0-9]+" "")
                    (str/replace #"\$" "/"))]
      (if-let [m (re-find #"boundary\.([^/]+\.[^/]+/[^\s]+)" clean)]
        (second m)
        (last (str/split clean #" "))))))

(defn- route-rows [routes]
  (for [{:keys [method path handler module]} routes]
    {:cells [(c/method-badge method)
             [:span.route-path path]
             [:span.route-handler (or (handler-short-name handler) handler)]
             (if module
               [:a.module-tag {:href  (str "/dashboard/docs/" module "/AGENTS.md")
                               :style "color:inherit;text-decoration:none"
                               :title (str "libs/" module "/ docs")}
                module]
               [:span.module-tag.module-tag-unknown "—"])
             [:button.inspect-link
              {:type     "button"
               :hx-get   (str "/dashboard/fragments/route-inspect?path="
                              (java.net.URLEncoder/encode path "UTF-8")
                              "&method=" (name method))
               :hx-target "#route-detail"
               :hx-swap   "innerHTML show:#route-detail:top"
               :style     "background:none;border:none;color:var(--accent-blue);cursor:pointer;font-size:12px"}
              "inspect →"]]}))

(defn- render-routes-table [routes]
  [:div#routes-table
   (c/data-table
    {:columns      ["Method" "Path" "Handler" "Module" ""]
     :col-template "80px 1fr 1fr 100px 100px"
     :rows         (route-rows routes)})])

;; =============================================================================
;; Fragment endpoints
;; =============================================================================

(defn render-table-fragment
  "Return filtered route table as HTML fragment for HTMX."
  [req]
  (let [params (get req :params {})
        search (or (get params "search") "")
        module (or (get params "module") "")
        method (or (get params "method") "")
        routes (filter-routes (or (route-data req) [])
                              {:search search :module module :method method})]
    (str (h/html (render-routes-table routes)))))

(defn render-inspect-fragment
  "Return route detail (interceptor chain + try-it form) as HTML fragment."
  [req]
  (let [params (get req :params {})
        path   (or (get params "path") "")
        method (or (get params "method") "get")]
    (str (h/html
          [:div.detail-panel
           [:div.detail-header
            [:span (c/method-badge (keyword method))]
            [:span {:style "margin-left:8px"} path]]
           [:div {:style "padding:16px"}
            [:div.detail-label "Try it"]
            [:div.try-it-panel
             [:form {:hx-post "/dashboard/fragments/try-route"
                     :hx-target "#try-result"
                     :hx-swap "innerHTML"}
              [:input {:type "hidden" :name "method" :value method}]
              [:div {:style "display:flex;gap:8px;margin-bottom:8px;align-items:center"}
               [:span {:style "font-family:var(--font-mono);font-size:12px;color:var(--text-muted);white-space:nowrap"} "Path:"]
               [:input {:type "text" :name "path" :value path
                        :style "flex:1;background:var(--bg-input,#1e293b);border:1px solid var(--border-color,#334155);color:var(--text-primary,#f1f5f9);padding:6px 10px;border-radius:4px;font-family:var(--font-mono);font-size:13px"}]]
              [:textarea {:name "body" :placeholder "{:key \"value\"}" :rows 3
                          :style "width:100%;margin-bottom:8px"}]
              [:button.btn.btn-primary {:type "submit"
                                        :style "background:var(--accent-blue);color:#0f172a;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;font-weight:600"}
               "Send Request"]]]
            [:div#try-result]]]))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render [opts]
  (let [routes      (or (route-data opts) [])
        all-routes  (vec routes)
        modules     (all-modules all-routes)
        methods     (all-methods all-routes)
        route-count (count all-routes)
        module-options (into [{:value "" :label "All modules"}]
                             (map (fn [m] {:value m :label m}) modules))
        method-options (into [{:value "" :label "All methods"}]
                             (map (fn [m] {:value (name m) :label (str/upper-case (name m))}) methods))]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/routes"
                  :title       "Route Explorer"})
     (c/card
      {:title "Routes"
       :right [:span.route-count (str route-count " routes")]}
      (c/filter-bar
       (c/filter-input {:name        "search"
                        :placeholder "Search path or handler..."
                        :hx-get      "/dashboard/fragments/routes-table"
                        :hx-trigger  "keyup changed delay:300ms"
                        :hx-target   "#routes-table"
                        :hx-swap     "outerHTML"
                        :hx-include  "[name='module'],[name='method']"})
       (c/filter-select {:name       "module"
                         :hx-get     "/dashboard/fragments/routes-table"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='method']"}
                        module-options)
       (c/filter-select {:name       "method"
                         :hx-get     "/dashboard/fragments/routes-table"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='module']"}
                        method-options))
      (render-routes-table all-routes))
     [:div#route-detail])))

;; =============================================================================
;; Try-it fragment
;; =============================================================================

(defn render-try-result [req]
  (let [params  (get req :params {})
        method  (or (get params "method") (get params :method) "get")
        raw-path (or (get params "path") (get params :path) "/")
        ;; Split path?query so Ring gets a proper :uri and :query-string
        [path query-str] (str/split raw-path #"\?" 2)
        raw-body (or (get params "body") (get params :body) "")
        body    (when (seq raw-body)
                  (try (edn/read-string raw-body) (catch Exception _ nil)))
        handler (or (:http-handler req)
                    (when-let [sys state/system]
                      (get sys :boundary/http-handler)))]
    (if-not handler
      (str (h/html [:div.detail-panel.detail-panel-error
                    [:p "System not running"]]))
      ;; Pass raw query string directly to avoid double-encoding
      (let [result (devtools-repl/simulate-request handler method path
                                                   (cond-> {}
                                                     body      (assoc :body body)
                                                     query-str (assoc :query-string query-str)))
            status (:status result)
            ok?    (and (integer? status) (< status 400))]
        (str (h/html
              [:div {:style "margin-top:12px"}
               [:div {:style (str "font-weight:600;color:" (if ok? "var(--accent-green-light)" "var(--accent-red)"))}
                (str "Status: " status)]
               (c/code-block (if (string? (:body result))
                               (:body result)
                               (pr-str (:body result))))]))))))
