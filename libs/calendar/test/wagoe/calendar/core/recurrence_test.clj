(ns wagoe.calendar.core.recurrence-test
  "Unit tests for RRULE parsing and occurrence expansion — no I/O, no DB."
  (:require [wagoe.calendar.core.recurrence :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

;; =============================================================================
;; Test data helpers
;; =============================================================================

(defn- make-event
  "Build a minimal EventData map for testing."
  ([start-str end-str]
   (make-event start-str end-str nil "UTC"))
  ([start-str end-str rrule]
   (make-event start-str end-str rrule "UTC"))
  ([start-str end-str rrule timezone]
   (cond-> {:id       (java.util.UUID/randomUUID)
            :title    "Test Event"
            :start    (Instant/parse start-str)
            :end      (Instant/parse end-str)
            :timezone timezone}
     rrule (assoc :recurrence rrule))))

;; =============================================================================
;; recurring?
;; =============================================================================

(deftest ^:unit recurring-test
  (testing "event with RRULE is recurring"
    (let [ev (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z"
                         "FREQ=DAILY")]
      (is (true? (sut/recurring? ev)))))
  (testing "event without RRULE is not recurring"
    (let [ev (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z")]
      (is (false? (sut/recurring? ev)))))
  (testing "event with blank RRULE is not recurring"
    (let [ev (assoc (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z")
                    :recurrence "   ")]
      (is (false? (sut/recurring? ev))))))

;; =============================================================================
;; Non-recurring events
;; =============================================================================

(deftest ^:unit non-recurring-within-window-test
  (testing "non-recurring event inside window returns [start]"
    (let [ev (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")]
      (is (= [(Instant/parse "2026-03-10T09:00:00Z")]
             (sut/occurrences ev
                              (Instant/parse "2026-03-10T00:00:00Z")
                              (Instant/parse "2026-03-11T00:00:00Z"))))))
  (testing "non-recurring event outside window returns []"
    (let [ev (make-event "2026-03-12T09:00:00Z" "2026-03-12T10:00:00Z")]
      (is (empty? (sut/occurrences ev
                                   (Instant/parse "2026-03-10T00:00:00Z")
                                   (Instant/parse "2026-03-11T00:00:00Z"))))))
  (testing "non-recurring event overlapping window start returns [start]"
    (let [ev (make-event "2026-03-09T23:30:00Z" "2026-03-10T00:30:00Z")]
      (is (= [(Instant/parse "2026-03-09T23:30:00Z")]
             (sut/occurrences ev
                              (Instant/parse "2026-03-10T00:00:00Z")
                              (Instant/parse "2026-03-11T00:00:00Z")))))))

;; =============================================================================
;; DAILY recurrence
;; =============================================================================

(deftest ^:unit daily-recurrence-test
  (testing "FREQ=DAILY returns an occurrence each day in window"
    (let [ev   (make-event "2026-03-01T09:00:00Z" "2026-03-01T09:30:00Z"
                           "FREQ=DAILY")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-01T00:00:00Z")
                                (Instant/parse "2026-03-08T00:00:00Z"))]
      (is (= 7 (count occs)))
      (is (= (Instant/parse "2026-03-01T09:00:00Z") (first occs)))
      (is (= (Instant/parse "2026-03-07T09:00:00Z") (last occs)))))
  (testing "FREQ=DAILY;COUNT=3 returns exactly 3 occurrences"
    (let [ev   (make-event "2026-03-01T09:00:00Z" "2026-03-01T09:30:00Z"
                           "FREQ=DAILY;COUNT=3")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-01T00:00:00Z")
                                (Instant/parse "2026-04-01T00:00:00Z"))]
      (is (= 3 (count occs))))))

;; =============================================================================
;; WEEKLY recurrence
;; =============================================================================

(deftest ^:unit weekly-recurrence-test
  (testing "FREQ=WEEKLY returns one occurrence per week"
    (let [ev   (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z"
                           "FREQ=WEEKLY")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-01T00:00:00Z")
                                (Instant/parse "2026-04-01T00:00:00Z"))]
      ;; March 2, 9, 16, 23, 30 = 5 Mondays
      (is (= 5 (count occs)))))
  (testing "FREQ=WEEKLY;BYDAY=MO,WE,FR returns 3 occurrences per week"
    (let [ev   (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z"
                           "FREQ=WEEKLY;BYDAY=MO,WE,FR")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-01T00:00:00Z")
                                (Instant/parse "2026-03-08T00:00:00Z"))]
      ;; Week of March 2: Mon Mar 2, Wed Mar 4, Fri Mar 6 = 3
      (is (= 3 (count occs))))))

;; =============================================================================
;; MONTHLY recurrence
;; =============================================================================

(deftest ^:unit monthly-recurrence-test
  (testing "FREQ=MONTHLY returns one occurrence per month"
    (let [ev   (make-event "2026-01-15T10:00:00Z" "2026-01-15T11:00:00Z"
                           "FREQ=MONTHLY")
          occs (sut/occurrences ev
                                (Instant/parse "2026-01-01T00:00:00Z")
                                (Instant/parse "2026-07-01T00:00:00Z"))]
      ;; Jan 15, Feb 15, Mar 15, Apr 15, May 15, Jun 15 = 6
      (is (= 6 (count occs))))))

;; =============================================================================
;; YEARLY recurrence
;; =============================================================================

(deftest ^:unit yearly-recurrence-test
  (testing "FREQ=YEARLY returns one occurrence per year"
    (let [ev   (make-event "2024-06-01T09:00:00Z" "2024-06-01T10:00:00Z"
                           "FREQ=YEARLY")
          occs (sut/occurrences ev
                                (Instant/parse "2024-01-01T00:00:00Z")
                                (Instant/parse "2027-01-01T00:00:00Z"))]
      ;; 2024, 2025, 2026 = 3
      (is (= 3 (count occs))))))

;; =============================================================================
;; UNTIL limit
;; =============================================================================

(deftest ^:unit until-limit-test
  (testing "FREQ=DAILY;UNTIL stops at the specified date"
    (let [ev   (make-event "2026-03-01T09:00:00Z" "2026-03-01T09:30:00Z"
                           "FREQ=DAILY;UNTIL=20260305T090000Z")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-01T00:00:00Z")
                                (Instant/parse "2026-04-01T00:00:00Z"))]
      ;; Mar 1, 2, 3, 4, 5 = 5
      (is (= 5 (count occs))))))

;; =============================================================================
;; DST edge case — spring-forward Amsterdam (last Sunday of March)
;; =============================================================================

(deftest ^:unit dst-spring-forward-test
  (testing "weekly recurrence at 09:00 Amsterdam stays at 09:00 local after DST spring-forward"
    ;; Amsterdam: UTC+1 in winter, UTC+2 in summer.
    ;; Spring-forward 2026: last Sunday of March = March 29 02:00 local → 03:00.
    ;; An event at 09:00 Amsterdam on March 23 (UTC+1 → 08:00 UTC) should appear
    ;; at 09:00 Amsterdam on March 30 (UTC+2 → 07:00 UTC).
    (let [ev (make-event "2026-03-23T08:00:00Z" "2026-03-23T08:30:00Z"
                         "FREQ=WEEKLY"
                         "Europe/Amsterdam")
          occs (sut/occurrences ev
                                (Instant/parse "2026-03-30T00:00:00Z")
                                (Instant/parse "2026-03-31T00:00:00Z"))]
      (is (= 1 (count occs)))
      ;; After DST, 09:00 Amsterdam = 07:00 UTC
      (is (= (Instant/parse "2026-03-30T07:00:00Z") (first occs))))))

(deftest ^:unit dst-fall-back-test
  (testing "weekly recurrence at 09:00 Amsterdam stays at 09:00 local after DST fall-back"
    ;; Amsterdam: UTC+2 in summer, UTC+1 in winter.
    ;; Fall-back 2026: last Sunday of October = Oct 25, 03:00 local → 02:00.
    ;; An event at 09:00 Amsterdam on Oct 19 (UTC+2 → 07:00 UTC) should appear
    ;; at 09:00 Amsterdam on Oct 26 (UTC+1 → 08:00 UTC).
    (let [ev (make-event "2026-10-19T07:00:00Z" "2026-10-19T07:30:00Z"
                         "FREQ=WEEKLY"
                         "Europe/Amsterdam")
          occs (sut/occurrences ev
                                (Instant/parse "2026-10-26T00:00:00Z")
                                (Instant/parse "2026-10-27T00:00:00Z"))]
      (is (= 1 (count occs)))
      ;; After DST fall-back, 09:00 Amsterdam = 08:00 UTC
      (is (= (Instant/parse "2026-10-26T08:00:00Z") (first occs))))))

;; =============================================================================
;; expand-event
;; =============================================================================

(deftest ^:unit expand-event-test
  (testing "expand-event returns occurrence maps without :recurrence key"
    (let [ev   (make-event "2026-03-02T09:00:00Z" "2026-03-02T09:30:00Z"
                           "FREQ=DAILY;COUNT=3")
          occs (sut/expand-event ev
                                 (Instant/parse "2026-03-01T00:00:00Z")
                                 (Instant/parse "2026-04-01T00:00:00Z"))]
      (is (= 3 (count occs)))
      (is (every? #(not (contains? % :recurrence)) occs))
      (is (every? #(= 30 (.toMinutes (java.time.Duration/between (:start %) (:end %)))) occs))))
  (testing "expand-event for non-recurring event returns single map"
    (let [ev   (make-event "2026-03-10T09:00:00Z" "2026-03-10T10:00:00Z")
          occs (sut/expand-event ev
                                 (Instant/parse "2026-03-10T00:00:00Z")
                                 (Instant/parse "2026-03-11T00:00:00Z"))]
      (is (= 1 (count occs)))
      (is (= (Instant/parse "2026-03-10T09:00:00Z") (:start (first occs)))))))

;; =============================================================================
;; next-occurrence
;; =============================================================================

(deftest ^:unit next-occurrence-test
  (testing "future non-recurring event returns its start"
    (let [reference-time "2026-01-01T00:00:00Z"
          far-future "2099-06-01T09:00:00Z"
          ev         (make-event far-future "2099-06-01T10:00:00Z")]
      (is (= (Instant/parse far-future)
             (sut/next-occurrence* ev (Instant/parse reference-time))))))
  (testing "past non-recurring event returns nil"
    (let [ev (make-event "2020-01-01T09:00:00Z" "2020-01-01T10:00:00Z")]
      (is (nil? (sut/next-occurrence* ev (Instant/parse "2026-01-01T00:00:00Z"))))))
  (testing "recurring event returns next future occurrence"
    (let [reference-time "2026-01-01T00:00:00Z"
          far-future-start "2099-01-01T09:00:00Z"
          ev (make-event far-future-start "2099-01-01T10:00:00Z"
                         "FREQ=DAILY")]
      (is (= (Instant/parse far-future-start)
             (sut/next-occurrence* ev (Instant/parse reference-time)))))))
