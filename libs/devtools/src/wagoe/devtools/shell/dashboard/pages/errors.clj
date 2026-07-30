(ns wagoe.devtools.shell.dashboard.pages.errors
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [hiccup2.core :as h]))

;; =============================================================================
;; Error log atom
;; =============================================================================

(defonce error-log* (atom []))
(defonce error-stats-24h* (atom {:total 0 :validation 0 :persistence 0 :fcis 0
                                 :entries []}))

(defn record-error!
  "Record an enriched error. Keeps the display log bounded at 100 entries
   and maintains rolling 24h stats separately so counts stay accurate."
  [enriched-error]
  (let [ts (or (:timestamp-ms enriched-error) (System/currentTimeMillis))
        entry (assoc enriched-error :timestamp-ms ts)]
    ;; Display log: bounded at 100 for rendering
    (swap! error-log* (fn [log]
                        (let [updated (into [entry] log)]
                          (if (> (count updated) 100)
                            (subvec updated 0 100)
                            updated))))
    ;; Stats log: keeps entries within 24h window, capped at 10000
    (swap! error-stats-24h* (fn [{:keys [entries]}]
                              (let [cutoff (- (System/currentTimeMillis) (* 24 60 60 1000))
                                    fresh  (let [f (filterv #(>= (:timestamp-ms %) cutoff)
                                                            (conj entries entry))]
                                             (if (> (count f) 10000)
                                               (subvec f (- (count f) 10000))
                                               f))]
                                {:total       (count fresh)
                                 :validation  (count (filter #(= (:category %) :validation) fresh))
                                 :persistence (count (filter #(= (:category %) :persistence) fresh))
                                 :fcis        (count (filter #(= (:category %) :fcis) fresh))
                                 :entries     fresh})))))

(defn clear-error-log!
  "Reset both the error log and stats atoms."
  []
  (reset! error-log* [])
  (reset! error-stats-24h* {:total 0 :validation 0 :persistence 0 :fcis 0
                            :entries []}))

;; =============================================================================
;; Stats
;; =============================================================================

(defn error-stats
  "Return 24h error counts by category. Prunes stale entries on read."
  []
  (let [cutoff (- (System/currentTimeMillis) (* 24 60 60 1000))
        stats (swap! error-stats-24h*
                     (fn [{:keys [entries]}]
                       (let [fresh (filterv #(>= (:timestamp-ms %) cutoff) entries)]
                         {:total       (count fresh)
                          :validation  (count (filter #(= (:category %) :validation) fresh))
                          :persistence (count (filter #(= (:category %) :persistence) fresh))
                          :fcis        (count (filter #(= (:category %) :fcis) fresh))
                          :entries     fresh})))]
    (dissoc stats :entries)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- relative-time-ms
  "Convert epoch milliseconds to a human-readable relative time string."
  [ts-ms]
  (when ts-ms
    (let [diff-s (/ (- (System/currentTimeMillis) ts-ms) 1000)]
      (cond
        (< diff-s 60)   (str (int diff-s) "s ago")
        (< diff-s 3600) (str (int (/ diff-s 60)) "m ago")
        :else           (str (int (/ diff-s 3600)) "h ago")))))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn render-error-list
  "Render recent errors (max 20) as a list."
  []
  (let [entries (take 20 @error-log*)]
    (if (empty? entries)
      [:div.empty-state "No errors recorded yet. Errors will appear here as they occur."]
      [:div.error-list
       (for [{:keys [code message count timestamp-ms]} entries]
         [:div.error-list-row
          [:span.error-code {:style "color: var(--color-red, #f87171); font-family: monospace; font-weight: bold;"}
           (or code "BND-000")]
          [:span.error-message message]
          (when count
            (c/count-badge count "yellow"))
          (when timestamp-ms
            [:span.request-time (relative-time-ms timestamp-ms)])])])))

(defn render-fragment
  "Return the error list as an HTML string fragment for HTMX polling."
  []
  (str (h/html (render-error-list))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Error Dashboard full page with stats and HTMX polling."
  [opts]
  (let [{:keys [total validation persistence fcis]} (error-stats)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/errors"
                  :title       "Error Dashboard"})
     [:div.stat-row
      (c/stat-card {:label "Total 24h"       :value total       :value-class (when (pos? total) "stat-value-error")})
      (c/stat-card {:label "Validation"      :value validation  :value-class (when (pos? validation) "stat-value-error")})
      (c/stat-card {:label "Persistence"     :value persistence :value-class (when (pos? persistence) "stat-value-error")})
      (c/stat-card {:label "FC/IS Violations" :value fcis       :value-class (when (pos? fcis) "stat-value-error")})]
     (c/card {:title "Recent Errors"
              :right [:span.live-indicator "● polling 2s"]}
             [:div#error-list-container
              {:hx-get     "/dashboard/fragments/error-list"
               :hx-trigger "every 2s"
               :hx-swap    "innerHTML"}
              (render-error-list)]))))
