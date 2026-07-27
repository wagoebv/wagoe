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
     mini-calendar  — compact month navigator for sidebars"
  (:require [wagoe.shared.ui.core.components :as ui]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime LocalDate Month]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

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

(defn- events-on-date
  "Return events from coll whose :start falls on the given LocalDate in the given timezone."
  [events ^LocalDate date ^String timezone]
  (filter (fn [ev]
            (let [ev-date (instant->local-date (:start ev) timezone)]
              (.isEqual ev-date date)))
          events))

(defn- month-name
  "Return the full English month name for a 1-based month int."
  [month]
  (.getDisplayName (Month/of month)
                   java.time.format.TextStyle/FULL
                   Locale/ENGLISH))

;; =============================================================================
;; event-badge
;; =============================================================================

(defn event-badge
  "Render a single event as a colored pill showing title and start time.

   Args:
     event    - EventData map
     timezone - string timezone for local-time display (default UTC)

   Returns:
     Hiccup [:div.event-badge ...] element"
  ([event]
   (event-badge event "UTC"))
  ([event timezone]
   (let [zdt      (instant->zdt (:start event) timezone)
         time-str (format-time zdt)]
     [:div {:class "event-badge"
            :title (:title event)}
      (ui/badge time-str {:variant :info
                          :class "event-time"})
      [:span.event-title (:title event)]])))

;; =============================================================================
;; day-cell
;; =============================================================================

(defn day-cell
  "Render a single day cell in a calendar grid.

   Args:
     date     - java.time.LocalDate
     events   - seq of EventData maps (already filtered to this day)
     opts     - map with optional keys:
                  :today         LocalDate — if date equals today, adds .today CSS class
                  :selected-date LocalDate — if date equals selected, adds .selected CSS class
                  :timezone      string    — for event time display (default UTC)

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
           (event-badge ev timezone))])])))

;; =============================================================================
;; month-view
;; =============================================================================

(defn month-view
  "Render a full month calendar grid.

   Renders a 7-column grid (Mon–Sun) with rows for each week.
   Days from the previous and next months are rendered as empty filler cells.

   Args:
     year   - integer (e.g. 2026)
     month  - integer 1–12
     events - seq of EventData maps (filtered to this month by the caller, or pass all)
     opts   - map with optional keys:
                :today         LocalDate — highlight today
                :selected-date LocalDate — highlight selected date
                :timezone      string    — for event time display (default UTC)

   Returns:
     Hiccup [:div.calendar-month ...] element"
  ([year month events]
   (month-view year month events {}))
  ([year month events opts]
   (let [{:keys [timezone]
          :or   {timezone "UTC"}} opts
         days-count (days-in-month year month)
         offset     (first-day-of-week-offset year month)
         day-names  [[:t :calendar/day-mon] [:t :calendar/day-tue] [:t :calendar/day-wed]
                     [:t :calendar/day-thu] [:t :calendar/day-fri] [:t :calendar/day-sat] [:t :calendar/day-sun]]
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
       [:span.month-title (str (month-name month) " " year)]]
      [:div.calendar-grid
       ;; Day-of-week headers
       [:div.calendar-row.header-row
        (for [d day-names]
          [:div.day-header d])]
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

   Args:
     start-date - java.time.LocalDate (Monday of the week to display)
     events     - seq of EventData maps
     opts       - map with optional keys:
                    :today    LocalDate — highlight today's column
                    :timezone string   — for event time display (default UTC)

   Returns:
     Hiccup [:div.calendar-week ...] element"
  ([start-date events]
   (week-view start-date events {}))
  ([start-date events opts]
   (let [{:keys [today timezone]
          :or   {timezone "UTC"}} opts
         day-names  [[:t :calendar/day-mon] [:t :calendar/day-tue] [:t :calendar/day-wed]
                     [:t :calendar/day-thu] [:t :calendar/day-fri] [:t :calendar/day-sat] [:t :calendar/day-sun]]
         week-dates (map #(.plusDays start-date %) (range 7))
         hours      (range 24)]
     [:div {:class "calendar-week"}
      ;; Column headers
      [:div.week-header
       [:div.time-gutter]
       (for [[d date] (map vector day-names week-dates)]
         (let [today? (and today (.isEqual date today))]
           [:div {:class (str "week-day-header" (when today? " today"))
                  :data-date (str date)}
            [:span.week-day-name d]
            [:span.week-day-number (.getDayOfMonth date)]]))]
      ;; Hour rows
      [:div.week-body
       (for [hour hours]
         [:div.hour-row
          [:div.time-label (format "%02d:00" hour)]
          (for [date week-dates]
            (let [day-events (filter (fn [ev]
                                       (let [ev-date (instant->local-date (:start ev) timezone)
                                             ev-zdt  (instant->zdt (:start ev) timezone)]
                                         (and (.isEqual ev-date date)
                                              (= hour (.getHour ev-zdt)))))
                                     events)]
              [:div {:class "week-cell"
                     :data-date (str date)
                     :data-hour hour}
               (for [ev day-events]
                 (event-badge ev timezone))]))])]])))

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

   Returns:
     Hiccup [:div.mini-calendar ...] element"
  ([year month selected-date]
   (mini-calendar year month selected-date {}))
  ([year month selected-date opts]
   (let [today      (:today opts)
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
            :aria-label [:t :calendar/button-prev-month]}
        "‹"]
       [:span.mini-title (str (month-name month) " " year)]
       [:a {:href (str "?year=" next-year "&month=" next-month)
            :class "mini-nav"
            :aria-label [:t :calendar/button-next-month]}
        "›"]]
      [:div.mini-grid
       ;; Day-of-week header (single letter)
       [:div.mini-row.header-row
        (for [d [[:t :calendar/day-letter-m] [:t :calendar/day-letter-t] [:t :calendar/day-letter-w]
                 [:t :calendar/day-letter-t] [:t :calendar/day-letter-f] [:t :calendar/day-letter-s]
                 [:t :calendar/day-letter-s]]]
          [:div.mini-day-header d])]
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
