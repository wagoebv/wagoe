(ns wagoe.devtools.shell.dashboard.pages.requests
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [wagoe.devtools.shell.dashboard.middleware :as middleware]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- relative-time
  "Convert java.time.Instant to a human-readable relative time string."
  [instant]
  (let [now-ms   (System/currentTimeMillis)
        then-ms  (.toEpochMilli instant)
        diff-s   (/ (- now-ms then-ms) 1000)]
    (cond
      (< diff-s 60)   (str (int diff-s) "s ago")
      (< diff-s 3600) (str (int (/ diff-s 60)) "m ago")
      :else           (str (int (/ diff-s 3600)) "h ago"))))

(defn- status-color
  "Return inline CSS style string based on HTTP status code."
  [status]
  (cond
    (< status 300) "color: var(--color-green-light, #4ade80)"
    (< status 400) "color: var(--color-blue, #60a5fa)"
    :else          "color: var(--color-red, #f87171)"))

(defn- duration-style
  "Return yellow style if ms > 100, empty string otherwise."
  [ms]
  (if (> ms 100)
    "color: var(--color-yellow, #facc15)"
    ""))

;; =============================================================================
;; Filtering
;; =============================================================================

(defn- status-matches?
  "Check if a numeric status code matches a filter like \"2xx\", \"4xx\", etc."
  [status filter-str]
  (when (and status (not (str/blank? filter-str)))
    (let [prefix (subs filter-str 0 1)]
      (= prefix (str (quot status 100))))))

(defn- filter-entries
  "Filter request log entries by path substring and status range.
   Argument order is [filters entries] for ->> threading compatibility."
  [{:keys [path-filter status-filter]} entries]
  (cond->> entries
    (not (str/blank? path-filter))
    (filter (fn [e] (str/includes? (str (:path e)) path-filter)))
    (not (str/blank? status-filter))
    (filter (fn [e] (status-matches? (:status e) status-filter)))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn- request-rows
  "Build data-table rows from request log entries."
  [entries]
  (for [{:keys [id status method path duration-ms timestamp]} entries]
    {:cells [[:span {:style (status-color status)} status]
             (c/method-badge method)
             [:span.route-path path]
             [:span {:style (duration-style duration-ms)} (str duration-ms "ms")]
             [:span.request-time (relative-time timestamp)]
             [:button.inspect-link
              {:type      "button"
               :hx-get    (str "/dashboard/fragments/request-detail?id=" id)
               :hx-target "#request-detail"
               :hx-swap   "innerHTML show:#request-detail:top"
               :style     "background:none;border:none;color:var(--accent-blue);cursor:pointer;font-size:12px"}
              "details →"]]}))

(defn render-request-list
  "Build a data-table from the current request log, newest first, limit 50."
  [& [filters]]
  (let [entries (->> (middleware/request-log)
                     (filter-entries (or filters {}))
                     (take 50))]
    (if (empty? entries)
      [:div.empty-state "No requests captured yet. Make some HTTP requests to see them here."]
      (c/data-table
       {:columns      ["Status" "Method" "Path" "Duration" "Time" ""]
        :col-template "70px 90px 1fr 90px 100px 80px"
        :rows         (request-rows entries)}))))

(defn render-fragment
  "Return the request list as an HTML string fragment for HTMX polling."
  [req]
  (let [params (get req :params {})
        filters {:path-filter   (or (get params "path") "")
                 :status-filter (or (get params "status") "")}]
    (str (h/html (render-request-list filters)))))

(defn- render-headers-table
  "Render a map of headers as a simple key-value grid."
  [headers label]
  (when (seq headers)
    [:div {:style "margin-bottom:1rem"}
     [:div {:style "font-size:11px;font-weight:600;color:var(--text-muted);margin-bottom:0.5rem;text-transform:uppercase"} label]
     (c/data-table
      {:columns      ["Header" "Value"]
       :col-template "220px 1fr"
       :rows         (for [[k v] (sort-by key headers)]
                       {:cells [[:span {:style "font-family:var(--font-mono);font-weight:500"} k]
                                [:span {:style "font-family:var(--font-mono);word-break:break-all"} (str v)]]})})]))

(defn render-detail-fragment
  "Return request detail (headers, params) as an HTML fragment."
  [req]
  (let [params   (get req :params {})
        id-str   (or (get params "id") "")
        entries  (middleware/request-log)
        entry    (first (filter #(= (str (:id %)) id-str) entries))]
    (if-not entry
      (str (h/html [:div.detail-panel [:p.no-data "Request not found — it may have been evicted from the log."]]))
      (let [{:keys [method path status duration-ms timestamp request response]} entry
            req-headers  (:headers request)
            req-params   (:params request)
            resp-headers (:headers response)]
        (str (h/html
              [:div.detail-panel
               [:div.detail-header
                [:span (c/method-badge method)]
                [:span {:style "margin-left:8px"} path]
                [:span {:style "margin-left:auto;font-size:12px"}
                 [:span {:style (status-color status)} (str status)]
                 [:span {:style "margin-left:8px;color:var(--text-muted)"} (str duration-ms "ms")]
                 [:span {:style "margin-left:8px;color:var(--text-muted)"} (relative-time timestamp)]]]
               [:div {:style "padding:1rem 1.5rem"}
                (render-headers-table req-headers "Request Headers")
                (when (seq req-params)
                  (render-headers-table req-params "Request Params"))
                (render-headers-table resp-headers "Response Headers")]]))))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Request Inspector full page with live HTMX polling."
  [opts]
  (layout/dashboard-page
   (merge opts {:active-path "/dashboard/requests"
                :title       "Request Inspector"})
   (c/filter-bar
    (c/filter-input {:name        "path"
                     :placeholder "Filter by path..."
                     :id          "path-search"
                     :hx-get      "/dashboard/fragments/request-list"
                     :hx-trigger  "keyup changed delay:300ms"
                     :hx-target   "#request-list-container"
                     :hx-swap     "innerHTML"
                     :hx-include  "[name='status']"})
    (c/filter-select {:name       "status"
                      :id         "status-filter"
                      :hx-get     "/dashboard/fragments/request-list"
                      :hx-trigger "change"
                      :hx-target  "#request-list-container"
                      :hx-swap    "innerHTML"
                      :hx-include "[name='path']"}
                     [{:value "" :label "All statuses"}
                      {:value "2xx" :label "2xx Success"}
                      {:value "3xx" :label "3xx Redirect"}
                      {:value "4xx" :label "4xx Client Error"}
                      {:value "5xx" :label "5xx Server Error"}])
    [:span.live-indicator "● Live — polling every 2s"])
   (c/card {:title "Requests"
            :right [:span.live-indicator "● polling 2s"]}
           [:div#request-list-container
            {:hx-get     "/dashboard/fragments/request-list"
             :hx-trigger "every 2s"
             :hx-swap    "innerHTML"
             :hx-include "[name='path'],[name='status']"}
            (render-request-list)])
   [:div#request-detail]))

