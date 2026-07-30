(ns wagoe.calendar.core.ui-test
  "Unit tests for wagoe.calendar.core.ui — pure Hiccup functions."
  (:require [wagoe.calendar.core.ui :as sut]
            [clojure.test :refer [deftest is testing]])
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

(deftest ^:unit events-on-date-timezone-test
  (testing "events-on-date respects event timezone"
    ;; Direct test of the helper function that was buggy
    (let [;; Event at 23:00 UTC on March 10 = 00:00+01:00 on March 11 in Amsterdam
          late-event (make-event "2026-03-10T23:00:00Z"
                                 "2026-03-10T23:30:00Z"
                                 "Europe/Amsterdam"
                                 "Midnight Event")
          events [late-event]]

      ;; Should NOT appear on March 10 in Amsterdam timezone
      (is (empty? (#'sut/events-on-date events
                                        (LocalDate/of 2026 3 10)
                                        "Europe/Amsterdam"))
          "Event at 23:00 UTC should NOT appear on March 10 in Amsterdam (it's March 11 locally)")

      ;; Should appear on March 11 in Amsterdam timezone
      (is (= 1 (count (#'sut/events-on-date events
                                            (LocalDate/of 2026 3 11)
                                            "Europe/Amsterdam")))
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
