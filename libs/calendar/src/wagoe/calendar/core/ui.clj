(ns wagoe.calendar.core.ui
  "Pure Hiccup UI components for calendar views.

   All functions are pure — they receive data and return Hiccup structures.
   No side effects, no I/O, no HTMX-specific markup.
   Composable into any Ring handler or Hiccup template.

   Components:
     event-badge    — colored pill with title and time
     day-cell       — single day grid cell with event badges
     month-view     — full month calendar grid
     week-view      — 7-column week grid with hourly rows
     mini-calendar  — compact month navigator for sidebars

   Labels are [:t key] markers, resolved by wagoe.i18n.shell.render. A page
   rendered with plain hiccup2 shows them as <t> tags, so either render through
   i18n or pass a :t fn (key -> string) in opts to resolve them here."
  (:require [wagoe.shared.ui.core.components :as ui]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime LocalDate]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- instant->local-date
  "Convert a java.time.Instant to a LocalDate in the given timezone."
  [^Instant instant ^String timezone]
  (.toLocalDate (.atZone instant (ZoneId/of timezone))))

(defn- instant->zdt
  "Convert a java.time.Instant to a ZonedDateTime in the given timezone."
  [^Instant instant ^String timezone]
  (.atZone instant (ZoneId/of timezone)))

(defn- format-time
  "Format a ZonedDateTime as HH:mm."
  [^ZonedDateTime zdt]
  (.format zdt (DateTimeFormatter/ofPattern "HH:mm")))

(defn- days-in-month
  "Return the number of days in the given year/month."
  [year month]
  (let [ld (LocalDate/of year month 1)]
    (.lengthOfMonth ld)))

(defn- first-day-of-week-offset
  "Return the 0-based column index (Mon=0 … Sun=6) for the 1st of the month."
  [year month]
  (let [ld  (LocalDate/of year month 1)
        dow (.getValue (.getDayOfWeek ld))]  ; 1=Mon … 7=Sun
    (dec dow)))

;; An event covers each local date from its start to its end. The end is
;; exclusive, so an event ending at midnight does not spill into the next day.
;; All-day events name dates, not instants: they use their own :timezone so
;; they do not shift a day when viewed from another zone.
(defn- event-span
  "[first-date last-date] the event covers."
  [ev ^String timezone]
  (let [tz    (or (when (:all-day? ev) (:timezone ev)) timezone)
        start (instant->local-date (:start ev) tz)
        end   (some-> ^Instant (:end ev) (.minusNanos 1) (instant->local-date tz))]
    [start (if (and end (.isAfter ^LocalDate end start)) end start)]))

(defn- events-on-date
  "Return events from coll that cover the given LocalDate in the given timezone."
  [events ^LocalDate date ^String timezone]
  (filter (fn [ev]
            (let [[^LocalDate from ^LocalDate to] (event-span ev timezone)]
              (not (or (.isBefore date from) (.isAfter date to)))))
          events))

(defn- spans-days?
  "True for all-day events and events that run past their start date."
  [ev timezone]
  (or (boolean (:all-day? ev))
      (let [[from to] (event-span ev timezone)] (not= from to))))

(defn- label
  "A [:t key] marker, or its translation when the caller passed a :t fn."
  [t k]
  (if t (t k) [:t k]))

(def ^:private month-keys
  [:calendar/month-january :calendar/month-february :calendar/month-march
   :calendar/month-april :calendar/month-may :calendar/month-june
   :calendar/month-july :calendar/month-august :calendar/month-september
   :calendar/month-october :calendar/month-november :calendar/month-december])

(def ^:private day-keys
  [:calendar/day-mon :calendar/day-tue :calendar/day-wed :calendar/day-thu
   :calendar/day-fri :calendar/day-sat :calendar/day-sun])

(defn- month-name
  "Month name for a 1-based month int, as a marker or translated by t."
  [t month]
  (label t (nth month-keys (dec month))))

;; =============================================================================
;; event-badge
;; =============================================================================

(defn event-badge
  "Render a single event as a colored pill showing title and start time.

   Args:
     event    - EventData map; an :href key links the badge, :all-day? true
                hides the time
     timezone - string timezone for local-time display (default UTC)
     opts     - map with optional keys:
                  :event-href fn event -> url, used when the event has no :href
                  :show-time? false to hide the time (default: not all-day)

   Returns:
     Hiccup [:div.event-badge ...], or [:a.event-badge ...] when it links"
  ([event]
   (event-badge event "UTC"))
  ([event timezone]
   (event-badge event timezone {}))
  ([event timezone {:keys [event-href show-time?]
                    :or   {show-time? (not (:all-day? event))}}]
   (let [href (or (:href event) (when event-href (event-href event)))]
     [(if href :a :div)
      (cond-> {:class "event-badge"
               :title (:title event)}
        href (assoc :href href))
      (when show-time?
        (ui/badge (format-time (instant->zdt (:start event) timezone))
                  {:variant :info
                   :class "event-time"}))
      [:span.event-title (:title event)]])))

;; =============================================================================
;; day-cell
;; =============================================================================

(defn- badge-on-date
  "Badge for ev drawn on date: the time shows only on the day the event starts."
  [ev ^LocalDate date timezone opts]
  (event-badge ev timezone
               (assoc opts :show-time?
                      (and (not (:all-day? ev))
                           (.isEqual date (instant->local-date (:start ev) timezone))))))

(defn day-cell
  "Render a single day cell in a calendar grid.

   Args:
     date     - java.time.LocalDate
     events   - seq of EventData maps (already filtered to this day)
     opts     - map with optional keys:
                  :today         LocalDate — if date equals today, adds .today CSS class
                  :selected-date LocalDate — if date equals selected, adds .selected CSS class
                  :timezone      string    — for event time display (default UTC)
                  :event-href    fn event -> url, see event-badge

   Returns:
     Hiccup [:div.day-cell ...] element"
  ([date events]
   (day-cell date events {}))
  ([date events opts]
   (let [{:keys [today selected-date timezone]
          :or   {timezone "UTC"}} opts
         today?    (and today (.isEqual date today))
         selected? (and selected-date (.isEqual date selected-date))
         classes   (str/join " " (filter identity
                                         ["day-cell"
                                          (when today? "today")
                                          (when selected? "selected")
                                          (when (empty? events) "empty")]))]
     [:div {:class classes
            :data-date (str date)}
      [:span.day-number (.getDayOfMonth date)]
      (when (seq events)
        [:div.day-events
         (for [ev events]
           ^{:key (str (:id ev))}
           (badge-on-date ev date timezone opts))])])))

;; =============================================================================
;; month-view
;; =============================================================================

(defn month-view
  "Render a full month calendar grid.

   Renders a 7-column grid (Mon–Sun) with rows for each week.
   Days from the previous and next months are rendered as empty filler cells.
   An event shows on every day it covers within the month.

   Args:
     year   - integer (e.g. 2026)
     month  - integer 1–12
     events - seq of EventData maps (filtered to this month by the caller, or pass all)
     opts   - map with optional keys:
                :today         LocalDate — highlight today
                :selected-date LocalDate — highlight selected date
                :timezone      string    — for event time display (default UTC)
                :event-href    fn event -> url, see event-badge
                :t             fn key -> string, resolves labels here

   Returns:
     Hiccup [:div.calendar-month ...] element"
  ([year month events]
   (month-view year month events {}))
  ([year month events opts]
   (let [{:keys [timezone t]
          :or   {timezone "UTC"}} opts
         days-count (days-in-month year month)
         offset     (first-day-of-week-offset year month)
         ;; Build flat list: nil for filler cells, LocalDate for real days
         all-cells  (concat (repeat offset nil)
                            (map #(LocalDate/of year month %) (range 1 (inc days-count))))
         ;; Pad to full rows (multiple of 7)
         padded     (let [remainder (mod (count all-cells) 7)]
                      (if (pos? remainder)
                        (concat all-cells (repeat (- 7 remainder) nil))
                        all-cells))
         weeks      (partition 7 padded)]
     [:div {:class "calendar-month"}
      [:div.calendar-header
       [:span.month-title (month-name t month) " " year]]
      [:div.calendar-grid
       ;; Day-of-week headers
       [:div.calendar-row.header-row
        (for [k day-keys]
          [:div.day-header (label t k)])]
       ;; Week rows
       (for [week weeks]
         [:div.calendar-row
          (for [date week]
            (if (nil? date)
              [:div.day-cell.filler]
              (day-cell date
                        (events-on-date events date timezone)
                        opts)))])]])))

;; =============================================================================
;; week-view
;; =============================================================================

(defn week-view
  "Render a 7-column week grid with hourly time rows (00:00–23:00).

   All-day and multi-day events go in an all-day row above the hours, on each
   day of the week they cover; other events sit in the hour they start.

   Args:
     start-date - java.time.LocalDate (Monday of the week to display)
     events     - seq of EventData maps
     opts       - map with optional keys:
                    :today      LocalDate — highlight today's column
                    :timezone   string   — for event time display (default UTC)
                    :event-href fn event -> url, see event-badge
                    :t          fn key -> string, resolves labels here

   Returns:
     Hiccup [:div.calendar-week ...] element"
  ([start-date events]
   (week-view start-date events {}))
  ([start-date events opts]
   (let [{:keys [today timezone t]
          :or   {timezone "UTC"}} opts
         week-dates (map #(.plusDays start-date %) (range 7))
         hours      (range 24)
         {spanning true timed false} (group-by #(spans-days? % timezone) events)]
     [:div {:class "calendar-week"}
      ;; Column headers
      [:div.week-header
       [:div.time-gutter]
       (for [[k date] (map vector day-keys week-dates)]
         (let [today? (and today (.isEqual date today))]
           [:div {:class (str "week-day-header" (when today? " today"))
                  :data-date (str date)}
            [:span.week-day-name (label t k)]
            [:span.week-day-number (.getDayOfMonth date)]]))]
      ;; All-day row
      [:div.week-all-day
       [:div.time-label (label t :calendar/all-day)]
       (for [date week-dates]
         [:div {:class "week-cell"
                :data-date (str date)}
          (for [ev (events-on-date spanning date timezone)]
            (badge-on-date ev date timezone opts))])]
      ;; Hour rows
      [:div.week-body
       (for [hour hours]
         [:div.hour-row
          [:div.time-label (format "%02d:00" hour)]
          (for [date week-dates]
            (let [day-events (filter (fn [ev]
                                       (let [ev-zdt (instant->zdt (:start ev) timezone)]
                                         (and (.isEqual (.toLocalDate ev-zdt) date)
                                              (= hour (.getHour ev-zdt)))))
                                     timed)]
              [:div {:class "week-cell"
                     :data-date (str date)
                     :data-hour hour}
               (for [ev day-events]
                 (event-badge ev timezone opts))]))])]])))

;; =============================================================================
;; mini-calendar
;; =============================================================================

(defn mini-calendar
  "Render a compact month navigator suitable for sidebars.

   Renders day numbers only (no event badges). Highlights today and selected date.
   Prev/next navigation links use query-param convention: ?year=Y&month=M.

   Args:
     year          - integer
     month         - integer 1–12
     selected-date - java.time.LocalDate or nil
     opts          - map with optional keys:
                       :today LocalDate — highlight today
                       :t     fn key -> string, resolves labels here

   Returns:
     Hiccup [:div.mini-calendar ...] element"
  ([year month selected-date]
   (mini-calendar year month selected-date {}))
  ([year month selected-date opts]
   (let [{:keys [today t]} opts
         prev-month (if (= month 1) 12 (dec month))
         prev-year  (if (= month 1) (dec year) year)
         next-month (if (= month 12) 1 (inc month))
         next-year  (if (= month 12) (inc year) year)
         days-count (days-in-month year month)
         offset     (first-day-of-week-offset year month)
         all-cells  (concat (repeat offset nil)
                            (map #(LocalDate/of year month %) (range 1 (inc days-count))))
         padded     (let [remainder (mod (count all-cells) 7)]
                      (if (pos? remainder)
                        (concat all-cells (repeat (- 7 remainder) nil))
                        all-cells))
         weeks      (partition 7 padded)]
     [:div {:class "mini-calendar"}
      [:div.mini-header
       [:a {:href (str "?year=" prev-year "&month=" prev-month)
            :class "mini-nav"
            :aria-label (label t :calendar/button-prev-month)}
        "‹"]
       [:span.mini-title (month-name t month) " " year]
       [:a {:href (str "?year=" next-year "&month=" next-month)
            :class "mini-nav"
            :aria-label (label t :calendar/button-next-month)}
        "›"]]
      [:div.mini-grid
       ;; Day-of-week header (single letter)
       [:div.mini-row.header-row
        (for [k [:calendar/day-letter-m :calendar/day-letter-t :calendar/day-letter-w
                 :calendar/day-letter-t :calendar/day-letter-f :calendar/day-letter-s
                 :calendar/day-letter-s]]
          [:div.mini-day-header (label t k)])]
       ;; Week rows
       (for [week weeks]
         [:div.mini-row
          (for [date week]
            (if (nil? date)
              [:div.mini-cell.filler]
              (let [today?    (and today (.isEqual date today))
                    selected? (and selected-date (.isEqual date selected-date))]
                [:div {:class     (str/join " " (filter identity
                                                        ["mini-cell"
                                                         (when today? "today")
                                                         (when selected? "selected")]))
                       :data-date (str date)}
                 (.getDayOfMonth date)])))])]])))
