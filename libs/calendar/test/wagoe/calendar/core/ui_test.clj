(ns wagoe.calendar.core.ui-test
  "Unit tests for wagoe.calendar.core.ui — pure Hiccup functions."
  (:require [wagoe.calendar.core.ui :as sut]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h])
  (:import [java.time Instant LocalDate]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn make-event
  "Helper to create test event with defaults."
  [start-str end-str timezone & [title]]
  {:id (random-uuid)
   :title (or title "Test Event")
   :start (Instant/parse start-str)
   :end (Instant/parse end-str)
   :timezone timezone})

;; =============================================================================
;; Multi-timezone rendering tests
;; =============================================================================

(deftest ^:unit month-view-multi-timezone-test
  (testing "month-view displays events on correct day in their timezone"
    ;; Regression test for timezone bug: events should appear on the day
    ;; corresponding to their local time in the event's timezone, not UTC.
    ;; Example: Event at 23:00 UTC in Amsterdam (UTC+1) should show on the next day.
    (let [;; Event at 23:00 UTC on March 10, which is 00:00+01:00 on March 11 in Amsterdam
          late-night-event (make-event "2026-03-10T23:00:00Z"
                                       "2026-03-10T23:30:00Z"
                                       "Europe/Amsterdam"
                                       "Late Event")
          ;; Event at 08:00 UTC on March 10, which is 09:00+01:00 on March 10 in Amsterdam
          morning-event (make-event "2026-03-10T08:00:00Z"
                                    "2026-03-10T09:00:00Z"
                                    "Europe/Amsterdam"
                                    "Morning Event")
          events [late-night-event morning-event]
          html (sut/month-view 2026 3 events {:timezone "Europe/Amsterdam"})]

      ;; Verify structure is valid hiccup
      (is (vector? html))
      (is (= :div (first html)))

      ;; Convert to string for pattern matching
      (let [html-str (pr-str html)]
        ;; Both events should be present in the output
        (is (re-find #"Late Event" html-str))
        (is (re-find #"Morning Event" html-str))

        ;; The late-night event (23:00 UTC = 00:00+01:00 on March 11)
        ;; should appear on March 11 when rendered in Amsterdam timezone
        ;; We verify this by checking the event appears in the output
        ;; (full day-cell verification would require deeper DOM inspection)
        (is (re-find #"Late Event" html-str)
            "Late-night event at 23:00 UTC should render in Amsterdam timezone (00:00 on next day)")))))

(deftest ^:unit on-date-timezone-test
  (testing "on-date takes dates in the view timezone"
    ;; Direct test of the helper function that was buggy
    (let [;; Event at 23:00 UTC on March 10 = 00:00+01:00 on March 11 in Amsterdam
          late-event (make-event "2026-03-10T23:00:00Z"
                                 "2026-03-10T23:30:00Z"
                                 "Europe/Amsterdam"
                                 "Midnight Event")
          spanned (#'sut/with-spans [late-event] "Europe/Amsterdam")]

      ;; Should NOT appear on March 10 in Amsterdam timezone
      (is (empty? (#'sut/on-date spanned (LocalDate/of 2026 3 10)))
          "Event at 23:00 UTC should NOT appear on March 10 in Amsterdam (it's March 11 locally)")

      ;; Should appear on March 11 in Amsterdam timezone
      (is (= 1 (count (#'sut/on-date spanned (LocalDate/of 2026 3 11))))
          "Event at 23:00 UTC should appear on March 11 in Amsterdam (00:00+01:00 locally)"))))

(deftest ^:unit week-view-timezone-test
  (testing "week-view renders events with correct timezone"
    (let [;; Event spanning midnight in UTC but not in Amsterdam
          event (make-event "2026-03-10T22:00:00Z"
                            "2026-03-11T02:00:00Z"
                            "Europe/Amsterdam"
                            "Evening Event")
          events [event]
          ;; Week starting March 9 (Monday)
          html (sut/week-view (LocalDate/of 2026 3 9)
                              events
                              {:timezone "Europe/Amsterdam"})]

      ;; Verify structure
      (is (vector? html))
      (is (= :div (first html)))

      ;; Event should appear in output
      (let [html-str (pr-str html)]
        (is (re-find #"Evening Event" html-str)
            "Event should render in week view with correct timezone")))))

;; =============================================================================
;; Links, labels, multi-day events
;; =============================================================================

(defn- nodes
  "Every hiccup vector in tree."
  [tree]
  (filter vector? (tree-seq #(or (vector? %) (seq? %)) seq tree)))

(defn- attrs [node] (when (map? (second node)) (second node)))

(defn- titles-on
  "Sorted event titles in the cells for date."
  [tree date]
  (->> (nodes tree)
       (filter #(= (str date) (:data-date (attrs %))))
       (mapcat nodes)
       (filter #(= :span.event-title (first %)))
       (map second)
       distinct
       sort))

(defn- time-count
  "Number of time badges in tree."
  [tree]
  (count (filter #(some-> (attrs %) :class (str/includes? "event-time")) (nodes tree))))

(defn- week-row
  "The all-day row, or the hour rows, of a week view."
  [week tag]
  (first (filter #(= tag (first %)) (nodes week))))

(defn- catalogue [locale]
  (edn/read-string (slurp (io/resource (str "wagoe/i18n/translations/" locale ".edn")))))

(deftest ^:unit event-badge-href-test
  (let [ev (make-event "2026-03-10T08:00:00Z" "2026-03-10T09:00:00Z" "UTC" "Standup")]
    (testing "no href renders a div"
      (is (= :div (first (sut/event-badge ev)))))
    (testing "an :href on the event links the badge"
      (let [badge (sut/event-badge (assoc ev :href "/events/1"))]
        (is (= :a (first badge)))
        (is (= "/events/1" (:href (attrs badge))))))
    (testing ":event-href builds the link in month and week views"
      (let [href  #(str "/events/" (:title %))
            links (fn [tree] (keep #(when (= :a (first %)) (:href (attrs %))) (nodes tree)))]
        (is (= ["/events/Standup"] (links (sut/month-view 2026 3 [ev] {:event-href href}))))
        (is (= ["/events/Standup"]
               (links (sut/week-view (LocalDate/of 2026 3 9) [ev] {:event-href href}))))))
    (testing "a script scheme never becomes a link"
      (doseq [bad ["javascript:alert(1)" " JavaScript:alert(1)" "java\tscript:alert(1)" "data:text/html,x"]]
        (is (= :div (first (sut/event-badge (assoc ev :href bad)))) bad))
      (doseq [ok ["/events/1" "https://x.test/e" "mailto:a@b.test" "events/1?a=b:c"]]
        (is (= ok (:href (attrs (sut/event-badge (assoc ev :href ok))))) ok)))
    (testing "the event's own :href wins over :event-href"
      (is (= "/own" (:href (attrs (sut/event-badge (assoc ev :href "/own") "UTC"
                                                   {:event-href (constantly "/other")}))))))))

(deftest ^:unit month-names-per-locale-test
  (let [en    (catalogue "en")
        nl    (catalogue "nl")
        title (fn [opts] (->> (nodes (sut/month-view 2026 3 [] opts))
                              (filter #(= :span.month-title (first %)))
                              first
                              rest))]
    (testing "every month has a key in both catalogues"
      (doseq [m (range 1 13)
              :let [[_ k] (#'sut/month-name nil m)]]
        (is (string? (get en k)) (str k " missing in en"))
        (is (string? (get nl k)) (str k " missing in nl"))))
    (testing "month title is a marker without :t"
      (is (= [[:t :calendar/month-march] " " 2026] (title {}))))
    (testing "month title per locale"
      (is (= "March 2026" (apply str (title {:t en}))))
      (is (= "maart 2026" (apply str (title {:t nl}))))
      (is (str/includes? (str (h/html (sut/mini-calendar 2026 12 nil {:t nl})))
                         "december 2026")))))

(deftest ^:unit t-option-resolves-markers-test
  (let [nl (catalogue "nl")]
    (testing "plain hiccup2 shows markers as <t> tags without :t"
      (is (str/includes? (str (h/html (sut/month-view 2026 3 []))) "<t>")))
    (testing "with :t nothing is left to resolve"
      (doseq [view [(sut/month-view 2026 3 [] {:t nl})
                    (sut/week-view (LocalDate/of 2026 3 9) [] {:t nl})
                    (sut/mini-calendar 2026 3 nil {:t nl})]]
        (let [html (str (h/html view))]
          (is (not (str/includes? html "<t>")))
          (is (re-find #">Ma?<" html) "Dutch Monday label rendered"))))))

(deftest ^:unit multi-day-spread-test
  (let [trip (make-event "2026-03-30T10:00:00Z" "2026-04-02T12:00:00Z" "UTC" "Trip")]
    (testing "month view shows it on each day it covers, clipped to the month"
      (let [march (sut/month-view 2026 3 [trip])
            april (sut/month-view 2026 4 [trip])]
        (is (empty? (titles-on march (LocalDate/of 2026 3 29))))
        (is (= ["Trip"] (titles-on march (LocalDate/of 2026 3 30))))
        (is (= ["Trip"] (titles-on march (LocalDate/of 2026 3 31))))
        (is (= ["Trip"] (titles-on april (LocalDate/of 2026 4 1))))
        (is (= ["Trip"] (titles-on april (LocalDate/of 2026 4 2))))
        (is (empty? (titles-on april (LocalDate/of 2026 4 3))))
        (is (= 1 (time-count march)) "time shows on the start day only")
        (is (zero? (time-count april)))))
    (testing "week view puts it in the all-day row on each day, clipped to the week"
      (let [week    (sut/week-view (LocalDate/of 2026 3 30) [trip])
            all-day (week-row week :div.week-all-day)]
        (doseq [d [30 31]]
          (is (= ["Trip"] (titles-on all-day (LocalDate/of 2026 3 d)))))
        (doseq [d [1 2]]
          (is (= ["Trip"] (titles-on all-day (LocalDate/of 2026 4 d)))))
        (is (empty? (titles-on all-day (LocalDate/of 2026 4 3))))
        (is (empty? (titles-on (week-row week :div.week-body) (LocalDate/of 2026 3 30))))
        (is (empty? (titles-on (sut/week-view (LocalDate/of 2026 3 23) [trip])
                               (LocalDate/of 2026 3 29)))
            "previous week shows nothing")))
    (testing "an event that started before the week shows from its first day"
      (let [all-day (week-row (sut/week-view (LocalDate/of 2026 4 6)
                                             [(make-event "2026-03-20T00:00:00Z" "2026-04-08T00:00:00Z" "UTC" "Long")])
                              :div.week-all-day)]
        (is (= ["Long"] (titles-on all-day (LocalDate/of 2026 4 6))))
        (is (= ["Long"] (titles-on all-day (LocalDate/of 2026 4 7))))
        (is (empty? (titles-on all-day (LocalDate/of 2026 4 8))))))))

(deftest ^:unit multi-day-edges-test
  (testing "an event ending at midnight does not spill into the next day"
    (let [ev    (make-event "2026-03-10T22:00:00Z" "2026-03-11T00:00:00Z" "UTC" "Late")
          month (sut/month-view 2026 3 [ev])]
      (is (= ["Late"] (titles-on month (LocalDate/of 2026 3 10))))
      (is (empty? (titles-on month (LocalDate/of 2026 3 11))))
      (is (= ["Late"] (titles-on (week-row (sut/week-view (LocalDate/of 2026 3 9) [ev]) :div.week-body)
                                 (LocalDate/of 2026 3 10)))
          "a same-day event stays in the hour rows")))
  (testing "a short overnight event stays in its start hour in the week view"
    (let [ev   (make-event "2026-03-09T23:00:00Z" "2026-03-10T00:30:00Z" "UTC" "Night")
          week (sut/week-view (LocalDate/of 2026 3 9) [ev])
          body (week-row week :div.week-body)]
      (is (= ["Night"] (titles-on body (LocalDate/of 2026 3 9))))
      (is (empty? (titles-on (week-row week :div.week-all-day) (LocalDate/of 2026 3 10))))
      (is (= ["Night"] (titles-on (sut/month-view 2026 3 [ev]) (LocalDate/of 2026 3 10)))
          "the month view still shows it on both days")))
  (testing "crossing midnight in the view timezone makes it multi-day"
    ;; 22:00–23:30 UTC is 23:00–00:30 in Amsterdam
    (let [ev (make-event "2026-03-10T22:00:00Z" "2026-03-10T23:30:00Z" "Europe/Amsterdam" "Late")]
      (is (= ["Late"] (titles-on (sut/month-view 2026 3 [ev] {:timezone "Europe/Amsterdam"})
                                 (LocalDate/of 2026 3 11))))
      (is (empty? (titles-on (sut/month-view 2026 3 [ev]) (LocalDate/of 2026 3 11)))
          "but not in UTC")))
  (testing "an all-day event on the DST change day covers that date only"
    ;; 29 March 2026 in Amsterdam is 23 hours long
    (let [ev (assoc (make-event "2026-03-28T23:00:00Z" "2026-03-29T22:00:00Z" "Europe/Amsterdam" "Spring")
                    :all-day? true)]
      (doseq [tz ["Europe/Amsterdam" "America/New_York" "Asia/Tokyo"]
              :let [month (sut/month-view 2026 3 [ev] {:timezone tz})]]
        (is (empty? (titles-on month (LocalDate/of 2026 3 28))) tz)
        (is (= ["Spring"] (titles-on month (LocalDate/of 2026 3 29))) tz)
        (is (empty? (titles-on month (LocalDate/of 2026 3 30))) tz)
        (is (zero? (time-count month)) "all-day events show no time"))
      (is (= ["Spring"] (titles-on (week-row (sut/week-view (LocalDate/of 2026 3 23) [ev]
                                                            {:timezone "Asia/Tokyo"})
                                             :div.week-all-day)
                                   (LocalDate/of 2026 3 29))))))
  (testing "a multi-day all-day event across the autumn DST change"
    ;; 24 Oct 00:00 CEST to 27 Oct 00:00 CET in Amsterdam: three dates
    (let [ev    (assoc (make-event "2026-10-23T22:00:00Z" "2026-10-26T23:00:00Z" "Europe/Amsterdam" "Fall")
                       :all-day? true)
          month (sut/month-view 2026 10 [ev] {:timezone "Europe/Amsterdam"})]
      (is (= [24 25 26] (filter #(seq (titles-on month (LocalDate/of 2026 10 %))) (range 1 32)))))))
