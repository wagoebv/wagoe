(ns wagoe.devtools.shell.dashboard.pages.jobs
  "Dashboard page for Jobs & Queues monitoring."
  (:require [wagoe.devtools.shell.dashboard.layout :as layout]
            [wagoe.devtools.shell.dashboard.components :as c]
            [hiccup2.core :as h]))

(defn- normalize-queues
  "Normalize queue stats to a consistent format.
   Adapters return :queues as a vector of maps with :queue-name key,
   e.g. [{:queue-name :default :size 5 :processed-total 30 ...}].
   Returns a seq of maps with :queue-name :size :processed :failed :avg-duration."
  [queues]
  (cond
    ;; Vector of maps (actual adapter format)
    (and (sequential? queues) (every? map? queues))
    (map (fn [q]
           {:queue-name   (:queue-name q)
            :size         (or (:size q) 0)
            :processed    (or (:processed-total q) (:processed q) 0)
            :failed       (or (:failed-total q) (:failed q) 0)
            :avg-duration (:avg-duration-ms q)})
         queues)

    ;; Map of queue-name -> stats (alternative format)
    (map? queues)
    (map (fn [[qn stats]]
           {:queue-name   qn
            :size         (or (:size stats) 0)
            :processed    (or (:processed-total stats) (:processed stats) 0)
            :failed       (or (:failed-total stats) (:failed stats) 0)
            :avg-duration (or (:avg-duration-ms stats) (:avg-duration stats))})
         queues)

    :else []))

(defn- queue-table
  "Render a table of queues with their sizes and stats."
  [queues]
  (let [normalized (normalize-queues queues)]
    (if (empty? normalized)
      [:div.empty-state "No queues active."]
      (c/data-table
       {:columns      ["Queue" "Pending" "Processed" "Failed" "Avg Duration"]
        :col-template "1fr 100px 100px 100px 120px"
        :rows         (for [{:keys [queue-name size processed failed avg-duration]}
                            (sort-by (comp str :queue-name) normalized)]
                        {:cells [[:span.text-mono (name queue-name)]
                                 [:span (str size)]
                                 [:span (str processed)]
                                 [:span {:style (when (pos? failed)
                                                  "color:var(--color-red,#f87171)")}
                                  (str failed)]
                                 [:span (if avg-duration (str avg-duration "ms") "—")]]})}))))

(defn- failed-jobs-list
  "Render the list of failed jobs."
  [failed-jobs]
  (if (empty? failed-jobs)
    [:div.empty-state "No failed jobs."]
    [:div.error-list
     (for [{:keys [id job-type queue error retry-count]} failed-jobs]
       [:div.error-list-row
        [:span.error-code {:style "color: var(--color-red, #f87171); font-family: monospace; font-weight: bold;"}
         (name (or job-type :unknown))]
        [:span.error-message (or error "Unknown error")]
        (when retry-count
          (c/count-badge retry-count "yellow"))
        (when queue
          [:span.request-time (name queue)])
        [:button.filter-input
         {:hx-post   (str "/dashboard/fragments/retry-job?job-id=" id)
          :hx-target "#failed-jobs-container"
          :hx-swap   "innerHTML"
          :style     "cursor:pointer;padding:2px 8px;font-size:11px;width:auto"}
         "Retry"]])]))

(defn- jobs-content
  "Render the jobs page content (used for both full page and fragment)."
  [{:keys [job-stats failed-jobs]}]
  (let [{:keys [total-processed total-failed total-succeeded queues]} job-stats
        normalized-queues (normalize-queues queues)
        active (reduce + 0 (map :size normalized-queues))]
    (list
     [:div.stat-row
      (c/stat-card {:label "Active/Pending" :value active
                    :value-class (when (pos? active) "stat-value-warning")})
      (c/stat-card {:label "Processed" :value (or total-processed 0)})
      (c/stat-card {:label "Succeeded" :value (or total-succeeded 0)
                    :value-class "green"})
      (c/stat-card {:label "Failed" :value (or total-failed 0)
                    :value-class (when (and total-failed (pos? total-failed)) "stat-value-error")})]
     [:div.two-col
      (c/card {:title "Queues"} (queue-table (or queues {})))
      (c/card {:title "Failed Jobs"
               :right [:span.live-indicator "● polling 5s"]}
              [:div#failed-jobs-container
               (failed-jobs-list (or failed-jobs []))])])))

(defn render
  "Render the Jobs & Queues full page."
  [opts]
  (if (or (:job-stats opts) (:job-queue opts) (:job-store opts))
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div {:hx-get     "/dashboard/fragments/jobs-content"
            :hx-trigger "every 5s"
            :hx-swap    "innerHTML"}
      (jobs-content opts)])
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div.empty-state
      "No job service configured. Add :boundary/jobs to your system config to enable job monitoring."])))

(defn render-fragment
  "Render the jobs content as an HTML fragment for HTMX polling."
  [opts]
  (str (h/html (jobs-content opts))))

(defn render-failed-jobs-fragment
  "Render only the failed jobs list as an HTML fragment for retry swap."
  [opts]
  (str (h/html (failed-jobs-list (or (:failed-jobs opts) [])))))
