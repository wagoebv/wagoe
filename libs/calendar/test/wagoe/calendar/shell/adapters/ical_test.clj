(ns wagoe.calendar.shell.adapters.ical-test
  "Integration tests for the ical4j adapter — round-trip export/import."
  (:require [wagoe.calendar.shell.service :as service]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private appointment
  {:id       (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
   :title    "Team Standup"
   :start    (Instant/parse "2026-03-10T09:00:00Z")
   :end      (Instant/parse "2026-03-10T09:30:00Z")
   :timezone "Europe/Amsterdam"})

(def ^:private recurring-meeting
  {:id         (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440001")
   :title      "Weekly Sync"
   :start      (Instant/parse "2026-03-02T08:00:00Z")
   :end        (Instant/parse "2026-03-02T08:30:00Z")
   :timezone   "Europe/Amsterdam"
   :recurrence "FREQ=WEEKLY;BYDAY=MO,WE,FR"})

;; =============================================================================
;; export-ical
;; =============================================================================

(deftest ^:integration export-ical-basic-test
  (testing "exported string starts with VCALENDAR"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "BEGIN:VCALENDAR"))
      (is (str/includes? ical "END:VCALENDAR"))))
  (testing "exported string contains VEVENT"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "BEGIN:VEVENT"))
      (is (str/includes? ical "END:VEVENT"))))
  (testing "exported string contains event title"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "Team Standup"))))
  (testing "exported string contains UID"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "550e8400-e29b-41d4-a716-446655440000"))))
  (testing "exported string contains VERSION:2.0"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "VERSION:2.0")))))

(deftest ^:integration export-ical-rrule-test
  (testing "recurring event RRULE is included in export"
    (let [ical (service/export-ical [recurring-meeting])]
      (is (str/includes? ical "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")))))

(deftest ^:integration export-ical-multi-event-test
  (testing "multi-event feed contains both VEVENTs"
    (let [ical (service/export-ical [appointment recurring-meeting])]
      (is (= 2 (count (re-seq #"BEGIN:VEVENT" ical)))))))

(deftest ^:integration export-ical-custom-prodid-test
  (testing "custom :product-id is used"
    (let [ical (service/export-ical [appointment] {:product-id "-//MyApp//EN"})]
      (is (str/includes? ical "-//MyApp//EN")))))

;; =============================================================================
;; import-ical
;; =============================================================================

(deftest ^:integration import-ical-basic-test
  (testing "imported events have correct field count"
    (let [ical   (service/export-ical [appointment])
          events (service/import-ical ical)]
      (is (= 1 (count events)))))
  (testing "imported event has correct title"
    (let [ical   (service/export-ical [appointment])
          event  (first (service/import-ical ical))]
      (is (= "Team Standup" (:title event)))))
  (testing "imported event has correct timezone"
    (let [ical   (service/export-ical [appointment])
          event  (first (service/import-ical ical))]
      (is (= "Europe/Amsterdam" (:timezone event))))))

;; =============================================================================
;; Round-trip
;; =============================================================================

(deftest ^:integration round-trip-non-recurring-test
  (testing "non-recurring event round-trips with same title and UID"
    (let [ical       (service/export-ical [appointment])
          events     (service/import-ical ical)
          ev         (first events)]
      (is (= "Team Standup" (:title ev)))
      (is (= (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
             (:id ev))))))

(deftest ^:integration round-trip-rrule-test
  (testing "RRULE survives round-trip"
    (let [ical   (service/export-ical [recurring-meeting])
          events (service/import-ical ical)
          ev     (first events)]
      (is (= "FREQ=WEEKLY;BYDAY=MO,WE,FR" (:recurrence ev))))))

(deftest ^:integration round-trip-multi-event-test
  (testing "multi-event round-trip preserves event count"
    (let [ical   (service/export-ical [appointment recurring-meeting])
          events (service/import-ical ical)]
      (is (= 2 (count events))))))

;; =============================================================================
;; ical-feed-response
;; =============================================================================

(deftest ^:integration ical-feed-response-test
  (testing "returns status 200"
    (let [resp (service/ical-feed-response [appointment])]
      (is (= 200 (:status resp)))))
  (testing "Content-Type is text/calendar"
    (let [resp (service/ical-feed-response [appointment])]
      (is (= "text/calendar; charset=utf-8"
             (get-in resp [:headers "Content-Type"])))))
  (testing "Content-Disposition includes filename"
    (let [resp (service/ical-feed-response [appointment])]
      (is (str/includes? (get-in resp [:headers "Content-Disposition"])
                         "calendar.ics"))))
  (testing "body is a VCALENDAR string"
    (let [resp (service/ical-feed-response [appointment])]
      (is (str/includes? (:body resp) "BEGIN:VCALENDAR"))))
  (testing "custom filename via opts"
    (let [resp (service/ical-feed-response [appointment] {:filename "my-feed.ics"})]
      (is (str/includes? (get-in resp [:headers "Content-Disposition"])
                         "my-feed.ics")))))

;; =============================================================================
;; Non-UUID UID handling
;; =============================================================================

(deftest ^:integration non-uuid-uid-import-test
  (testing "importing iCal with non-UUID UID generates deterministic UUID"
    ;; Many calendar systems (Google, Outlook) use non-UUID UID strings
    (let [ical-with-text-uid "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:meeting-2026-03-10@example.com\nDTSTART:20260310T090000Z\nDTEND:20260310T093000Z\nSUMMARY:Meeting\nEND:VEVENT\nEND:VCALENDAR"
          events-first  (service/import-ical ical-with-text-uid)
          events-second (service/import-ical ical-with-text-uid)]
      ;; Both imports should produce the same UUID (deterministic)
      (is (= 1 (count events-first)))
      (is (= 1 (count events-second)))
      (is (= (:id (first events-first))
             (:id (first events-second)))
          "Same non-UUID UID should produce same deterministic UUID on repeated imports")
      ;; The generated UUID should be valid
      (is (uuid? (:id (first events-first))))
      ;; Title should be preserved
      (is (= "Meeting" (:title (first events-first))))))

  (testing "importing iCal with valid UUID UID preserves the UUID"
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          ical-with-uuid-uid (str "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:" uuid-str "\nDTSTART:20260310T090000Z\nDTEND:20260310T093000Z\nSUMMARY:Meeting\nEND:VEVENT\nEND:VCALENDAR")
          events (service/import-ical ical-with-uuid-uid)]
      (is (= 1 (count events)))
      (is (= (java.util.UUID/fromString uuid-str)
             (:id (first events)))
          "Valid UUID UID should be preserved exactly"))))

(deftest ^:integration malformed-ical-import-test
  (testing "importing iCal with missing DTEND is valid (DTEND optional per RFC 5545)"
    ;; Missing DTEND - should not throw, DTEND is optional (DURATION can replace it)
    (let [ical-no-dtend "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:test-uid\nDTSTART:20260310T090000Z\nSUMMARY:Valid Event\nEND:VEVENT\nEND:VCALENDAR"
          events (service/import-ical ical-no-dtend)]
      (is (= 1 (count events))
          "Missing DTEND is valid (defaults to DTSTART or uses DURATION)")))

  (testing "importing iCal with missing UID throws exception"
    ;; Missing UID (required per RFC 5545)
    (let [ical-no-uid "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nDTSTART:20260310T090000Z\nDTEND:20260310T093000Z\nSUMMARY:Invalid Event\nEND:VEVENT\nEND:VCALENDAR"]
      (is (thrown? Exception (service/import-ical ical-no-uid))
          "Missing UID should throw")))

  (testing "importing iCal with invalid date formats throws exception"
    ;; Invalid date format (not ISO 8601)
    (let [ical-bad-date "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:test-uid\nDTSTART:2026-03-10\nDTEND:20260310T093000Z\nSUMMARY:Bad Date\nEND:VEVENT\nEND:VCALENDAR"]
      (is (thrown? Exception (service/import-ical ical-bad-date))
          "Invalid date format should throw")))

  (testing "importing non-iCal string throws exception"
    ;; Not a VCALENDAR at all
    (let [not-ical "This is not an iCal file"]
      (is (thrown? Exception (service/import-ical not-ical))
          "Non-iCal string should throw"))))

(deftest ^:integration rrule-validation-edge-cases-test
  (testing "importing iCal with invalid RRULE syntax throws exception"
    ;; Invalid FREQ value
    (let [ical-bad-freq "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:test-uid\nDTSTART:20260310T090000Z\nDTEND:20260310T093000Z\nSUMMARY:Bad RRULE\nRRULE:FREQ=INVALIDFREQ\nEND:VEVENT\nEND:VCALENDAR"]
      (is (thrown? Exception (service/import-ical ical-bad-freq))
          "Invalid FREQ value should throw")))

  (testing "importing iCal with RRULE edge cases that should work"
    ;; BYDAY without ordinal in MONTHLY (means every occurrence of that day in the month)
    (let [ical-monthly-byday "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:test-uid\nDTSTART:20260302T090000Z\nDTEND:20260302T093000Z\nSUMMARY:Every Monday\nRRULE:FREQ=MONTHLY;BYDAY=MO\nEND:VEVENT\nEND:VCALENDAR"
          events (service/import-ical ical-monthly-byday)]
      (is (= 1 (count events)))
      (is (= "FREQ=MONTHLY;BYDAY=MO" (:recurrence (first events)))
          "BYDAY without ordinal in MONTHLY is valid"))

    ;; UNTIL with timezone (RFC 5545 allows UNTIL in UTC or local time with TZID)
    (let [ical-until-tz "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:test-uid\nDTSTART;TZID=Europe/Amsterdam:20260302T090000\nDTEND;TZID=Europe/Amsterdam:20260302T093000\nSUMMARY:Until with TZ\nRRULE:FREQ=DAILY;UNTIL=20260310T090000Z\nEND:VEVENT\nEND:VCALENDAR"
          events (service/import-ical ical-until-tz)]
      (is (= 1 (count events)))
      (is (str/includes? (:recurrence (first events)) "UNTIL=")
          "UNTIL with timezone is valid"))))
