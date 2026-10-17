(ns wagoe.calendar.shell.adapters.ical-test
  "Integration tests for the ical4j adapter — round-trip export/import."
  (:require [wagoe.calendar.shell.service :as service]
            [wagoe.calendar.shell.adapters.ical :as ical]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [java.time DayOfWeek Duration Instant LocalDateTime LocalTime Month ZoneId ZoneOffset]
           [java.time.zone ZoneOffsetTransitionRule ZoneOffsetTransitionRule$TimeDefinition ZoneRules]
           [java.io StringReader]
           [net.fortuna.ical4j.data CalendarBuilder]
           [net.fortuna.ical4j.model.component Observance VTimeZone]
           [net.fortuna.ical4j.util CompatibilityHints]))

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

;; =============================================================================
;; All-day events
;; =============================================================================

(def ^:private dst-day
  "All day on 29 March 2026 in Amsterdam, the 23-hour spring-forward day."
  {:id       (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440002")
   :title    "Holiday"
   :start    (Instant/parse "2026-03-28T23:00:00Z")
   :end      (Instant/parse "2026-03-29T22:00:00Z")
   :timezone "Europe/Amsterdam"
   :all-day? true})

(deftest ^:integration all-day-export-test
  (testing "dates, not times, with an exclusive end"
    (let [ical (service/export-ical [dst-day])]
      (is (str/includes? ical "DTSTART;VALUE=DATE:20260329"))
      (is (str/includes? ical "DTEND;VALUE=DATE:20260330"))))
  (testing "an end that is not midnight still covers its day"
    (let [ical (service/export-ical [(assoc dst-day :end (Instant/parse "2026-03-30T10:00:00Z"))])]
      (is (str/includes? ical "DTEND;VALUE=DATE:20260331"))))
  (testing "a timed event at midnight stays timed"
    (let [ical (service/export-ical [(dissoc dst-day :all-day?)])]
      (is (not (str/includes? ical "VALUE=DATE")))
      (is (str/includes? ical "DTSTART;TZID=Europe/Amsterdam:20260329T000000")))))

(deftest ^:integration all-day-round-trip-test
  (testing "with the zone given, the event comes back as it went out"
    (let [[ev] (service/import-ical (service/export-ical [dst-day]) {:timezone "Europe/Amsterdam"})]
      (is (= dst-day ev))))
  (testing "without one, the dates hold in UTC"
    (let [[ev] (service/import-ical (service/export-ical [dst-day]))]
      (is (true? (:all-day? ev)))
      (is (= "UTC" (:timezone ev)))
      (is (= (Instant/parse "2026-03-29T00:00:00Z") (:start ev)))
      (is (= (Instant/parse "2026-03-30T00:00:00Z") (:end ev)))))
  (testing "timed events do not come back all-day"
    (is (not (contains? (first (service/import-ical (service/export-ical [appointment])))
                        :all-day?)))))

(deftest ^:integration all-day-import-without-dtend-test
  (let [ical "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:day@example.com\nDTSTART;VALUE=DATE:20260310\nSUMMARY:Day off\nEND:VEVENT\nEND:VCALENDAR"
        [ev] (service/import-ical ical {:timezone "Europe/Amsterdam"})]
    (is (true? (:all-day? ev)))
    (is (= (Instant/parse "2026-03-09T23:00:00Z") (:start ev)))
    (is (= (Instant/parse "2026-03-10T23:00:00Z") (:end ev)) "one day long")))

(defn- vcalendar [& lines]
  (str/join "\r\n" (concat ["BEGIN:VCALENDAR" "VERSION:2.0" "PRODID:-//Test//Test//EN"]
                           lines
                           ["END:VCALENDAR" ""])))

(deftest ^:integration import-edge-cases-test
  (testing "UTC times, as Google Calendar exports them"
    (let [[ev] (service/import-ical (vcalendar "BEGIN:VEVENT" "UID:z@example.com"
                                               "DTSTART:20261017T100000Z" "DTEND:20261017T110000Z"
                                               "SUMMARY:Call" "END:VEVENT"))]
      (is (= (Instant/parse "2026-10-17T10:00:00Z") (:start ev)))
      (is (= (Instant/parse "2026-10-17T11:00:00Z") (:end ev)))))
  (testing "a Windows TZID reads as its IANA zone"
    (let [[ev] (service/import-ical
                (vcalendar "BEGIN:VTIMEZONE" "TZID:W. Europe Standard Time"
                           "BEGIN:STANDARD" "DTSTART:16010101T030000" "TZOFFSETFROM:+0200"
                           "TZOFFSETTO:+0100" "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10" "END:STANDARD"
                           "BEGIN:DAYLIGHT" "DTSTART:16010101T020000" "TZOFFSETFROM:+0100"
                           "TZOFFSETTO:+0200" "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3" "END:DAYLIGHT"
                           "END:VTIMEZONE"
                           "BEGIN:VEVENT" "UID:w@example.com"
                           "DTSTART;TZID=W. Europe Standard Time:20261017T100000"
                           "DTEND;TZID=W. Europe Standard Time:20261017T110000"
                           "SUMMARY:Meeting" "END:VEVENT")
                {:timezone "Europe/Amsterdam"})]
      (is (= (Instant/parse "2026-10-17T08:00:00Z") (:start ev)))
      (is (= "Europe/Berlin" (:timezone ev)))))
  (testing "DURATION instead of DTEND"
    (let [[timed day] (service/import-ical
                       (vcalendar "BEGIN:VEVENT" "UID:d1@example.com"
                                  "DTSTART;TZID=Europe/Amsterdam:20261017T100000" "DURATION:PT1H"
                                  "SUMMARY:Hour" "END:VEVENT"
                                  "BEGIN:VEVENT" "UID:d2@example.com"
                                  "DTSTART;VALUE=DATE:20261017" "DURATION:P3D"
                                  "SUMMARY:Trip" "END:VEVENT"))]
      (is (= (Instant/parse "2026-10-17T09:00:00Z") (:end timed)))
      (is (= (Instant/parse "2026-10-20T00:00:00Z") (:end day)))))
  (testing "a nil :timezone means UTC; an unknown one is a validation error"
    (let [ical (vcalendar "BEGIN:VEVENT" "UID:n@example.com" "DTSTART;VALUE=DATE:20261017"
                          "SUMMARY:Day" "END:VEVENT")]
      (is (= "UTC" (:timezone (first (service/import-ical ical {:timezone nil})))))
      (is (= :validation-error
             (:type (ex-data (try (service/import-ical ical {:timezone "Mars/Olympus"})
                                  (catch clojure.lang.ExceptionInfo e e)))))))))

;; =============================================================================
;; Export: VTIMEZONE, UTC, folding, unique UIDs (BOU-719)
;; =============================================================================

(defn- export-error [events]
  (ex-data (try (service/export-ical events) (catch clojure.lang.ExceptionInfo e e))))

(deftest ^:integration export-timezones-test
  (testing "each zone a timed event uses gets one VTIMEZONE, under the name the event uses"
    (let [ical (service/export-ical [appointment (assoc appointment :id (random-uuid))])]
      (is (= 1 (count (re-seq #"BEGIN:VTIMEZONE" ical))))
      (is (str/includes? ical "TZID:Europe/Amsterdam\r\n"))
      (is (not (str/includes? ical "TZURL")))
      (is (< (str/index-of ical "END:VTIMEZONE") (str/index-of ical "BEGIN:VEVENT")))))
  (testing "UTC is written in the Z form, without a VTIMEZONE"
    (let [ical (service/export-ical [(assoc appointment :timezone "UTC")])]
      (is (str/includes? ical "DTSTART:20260310T090000Z"))
      (is (not (str/includes? ical "VTIMEZONE")))))
  (testing "all-day events need no VTIMEZONE"
    (is (not (str/includes? (service/export-ical [dst-day]) "VTIMEZONE")))))

(defn- strict-validation-entries
  "Parse with relaxed parsing off; ical4j's validation entries."
  [ical]
  (CompatibilityHints/setHintEnabled CompatibilityHints/KEY_RELAXED_PARSING false)
  (-> (CalendarBuilder.) (.build (StringReader. ical)) .validate .getEntries))

(deftest ^:integration export-two-zones-test
  (let [nyc  {:id       (random-uuid)
              :title    "Call"
              :start    (Instant/parse "2026-10-17T14:00:00Z")
              :end      (Instant/parse "2026-10-17T15:00:00Z")
              :timezone "America/New_York"}
        evs  [appointment recurring-meeting nyc (assoc appointment :id (random-uuid) :timezone "UTC")]
        ical (service/export-ical evs)]
    (testing "one VTIMEZONE per zone, TZIDs matching the ones referenced"
      (is (= ["Europe/Amsterdam" "America/New_York"]
             (map second (re-seq #"\r\nTZID:([^\r]+)" ical))))
      (is (= #{"Europe/Amsterdam" "America/New_York"}
             (set (map second (re-seq #";TZID=([^:;]+)" ical))))))
    (testing "strict ical4j parsing and validation pass"
      (is (empty? (strict-validation-entries ical))))
    (testing "round trip"
      (is (= (map #(select-keys % [:id :start :end :timezone]) evs)
             (map #(select-keys % [:id :start :end :timezone]) (service/import-ical ical)))))
    (testing "CRLF throughout; DTSTAMP is UTC"
      (is (str/ends-with? ical "END:VCALENDAR\r\n"))
      (is (not (re-find #"[^\r]\n" ical)))
      (is (re-find #"\r\nDTSTAMP:\d{8}T\d{6}Z\r\n" ical)))))

(deftest ^:integration export-folding-test
  (let [title (apply str (repeat 60 "é"))
        ical  (service/export-ical [(assoc appointment :title title)])]
    (is (every? #(<= (alength (.getBytes ^String % "UTF-8")) 75) (str/split ical #"\r\n"))
        "75 octets at most")
    (is (= title (:title (first (service/import-ical ical)))) "unfolds to the original")))

(deftest ^:integration export-validation-test
  (testing "two events with one :id are refused, also when one id is a string"
    (is (= :validation-error (:type (export-error [appointment appointment]))))
    (is (= :validation-error (:type (export-error [appointment (update appointment :id str)])))))
  (testing "an unknown :timezone is a validation error, not a ZoneRulesException"
    (is (= {:type :validation-error :field :timezone :value "Mars/Olympus"}
           (export-error [(assoc appointment :timezone "Mars/Olympus")])))))

;; =============================================================================
;; Export: VTIMEZONE from java.time zone rules (BOU-720)
;; =============================================================================

(defn- vtimezone-blocks [ical]
  (re-seq #"(?s)BEGIN:VTIMEZONE.*?END:VTIMEZONE\r\n" ical))

(defn- at ^Instant [tzid local]
  (.toInstant (.atZone (LocalDateTime/parse local) (ZoneId/of tzid))))

(deftest ^:integration export-zone-ical4j-lacks-test
  (let [tz   "America/Coyhaique"            ; added in tzdata 2025b
        mk   (fn [local] {:id (random-uuid) :title "Visit" :timezone tz
                          :start (at tz local) :end (.plusSeconds (at tz local) 3600)})
        evs  [(mk "2024-07-10T10:00") (mk "2024-12-10T10:00")]  ; -04, then -03
        ical (service/export-ical evs)]
    (testing "written in its own zone, at the local time, not in UTC"
      (is (str/includes? ical "DTSTART;TZID=America/Coyhaique:20240710T100000"))
      (is (str/includes? ical "DTSTART;TZID=America/Coyhaique:20241210T100000"))
      (is (str/includes? ical "TZOFFSETFROM:-0400\r\nTZOFFSETTO:-0300")))
    (testing "round trip keeps the instants"
      (is (= (map :start evs) (map :start (service/import-ical ical)))))
    (testing "strict ical4j parsing and validation pass"
      (is (empty? (strict-validation-entries ical))))))

(deftest ^:integration export-vtimezone-shape-test
  (let [ical  (service/export-ical [appointment recurring-meeting])
        [vtz] (vtimezone-blocks ical)]
    (testing "the JVM's zone under its own name, not a link target"
      (is (str/includes? vtz "TZID:Europe/Amsterdam\r\n"))
      (is (not (str/includes? ical "Brussels"))))
    (testing "current rules as RRULEs, without the zone's history"
      (is (str/includes? vtz "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"))
      (is (str/includes? vtz "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"))
      (is (< (count vtz) 500)))))

(deftest ^:integration export-zone-without-dst-test
  (testing "a fixed offset is one STANDARD observance"
    (let [[vtz] (vtimezone-blocks (service/export-ical [(assoc appointment :timezone "Etc/GMT-5")]))]
      (is (= 1 (count (re-seq #"BEGIN:STANDARD" vtz))))
      (is (str/includes? vtz "TZOFFSETFROM:+0500\r\nTZOFFSETTO:+0500"))
      (is (not (str/includes? vtz "DAYLIGHT")))))
  (testing "a zone that dropped DST long ago keeps only its last change"
    (let [ical  (service/export-ical [(assoc appointment :timezone "Asia/Tokyo")])
          [vtz] (vtimezone-blocks ical)]
      (is (= 1 (count (re-seq #"BEGIN:(STANDARD|DAYLIGHT)" vtz))))
      (is (str/includes? ical "DTSTART;TZID=Asia/Tokyo:20260310T180000"))))
  (testing "an offset id is quoted, and import reads it back"
    (let [ical (service/export-ical [(assoc appointment :timezone "+05:00")])]
      (is (str/includes? ical "DTSTART;TZID=\"+05:00\":20260310T140000"))
      (is (= "+05:00" (:timezone (first (service/import-ical ical))))))))

(defn- onsets
  "[instant offset] for each onset of each observance in `vtz` up to `end`,
   expanded as RFC 5545 says. Not ical4j's ZoneRulesBuilder: that reads
   BYDAY=2SU as the Sunday on or after the 2nd."
  [^VTimeZone vtz ^LocalDateTime end]
  (sort-by first
           (for [^Observance o (.getAll (.getComponentList vtz))
                 :let [start (.getDate (.getRequiredProperty o "DTSTART"))
                       from  (.getOffset (.getRequiredProperty o "TZOFFSETFROM"))
                       to    (.getOffset (.getRequiredProperty o "TZOFFSETTO"))
                       rrule (.orElse (.getProperty o "RRULE") nil)]
                 ^LocalDateTime d (if rrule (.getDates (.getRecur rrule) start start end) [start])]
             [(.toInstant d from) to])))

(defn- offset-mismatches
  "Instants in `samples` where the exported VTIMEZONE for `tzid` and java.time
   disagree about the UTC offset."
  [tzid event samples]
  (let [cal   (.build (CalendarBuilder.) (StringReader. (service/export-ical [(assoc event :timezone tzid)])))
        vtz   (first (filter #(instance? VTimeZone %) (.getComponents cal)))
        ons   (onsets vtz (LocalDateTime/parse "2032-01-01T00:00"))
        rules (.getRules (ZoneId/of tzid))]
    (remove (fn [^Instant i]
              (= (.getOffset rules i)
                 (second (last (take-while #(not (.isAfter ^Instant (first %) i)) ons)))))
            samples)))

(deftest ^:integration export-every-zone-offsets-test
  (let [weekly (assoc recurring-meeting :id (random-uuid))
        old    (assoc appointment :start (Instant/parse "1985-06-01T10:00:00Z")
                      :end (Instant/parse "1985-06-01T11:00:00Z"))
        every  (fn [^Instant from n] (map #(.plus from (Duration/ofHours (* 37 %))) (range n)))
        ;; ical4j registers the zones of calendars it parsed, as ical4j~<uuid>
        zones  (remove #(or (str/starts-with? % "ical4j")
                            (= ZoneOffset/UTC (.normalized (ZoneId/of %))))
                       (sort (ZoneId/getAvailableZoneIds)))
        bad    (fn [event samples]
                 (for [z zones :let [b (offset-mismatches z event samples)] :when (seq b)]
                   [z (first b)]))]
    (testing "a recurring event: offsets agree from a year before it, years on"
      (is (empty? (bad weekly (every (Instant/parse "2025-03-02T08:00:00Z") 1200)))))
    (testing "a single event decades ago: offsets agree a year either side"
      (is (empty? (bad old (every (Instant/parse "1984-06-01T10:00:00Z") 470)))))))

(deftest ^:integration rule-without-rrule-form-test
  (testing "a rule spanning February and March is written out transition by transition"
    (let [std   (ZoneOffset/ofHours 1)
          dst   (ZoneOffset/ofHours 2)
          rule  (fn [m d before after]
                  (ZoneOffsetTransitionRule/of m d DayOfWeek/SUNDAY (LocalTime/of 2 0) false
                                               ZoneOffsetTransitionRule$TimeDefinition/WALL std before after))
          rules (ZoneRules/of std std [] [] [(rule Month/FEBRUARY 23 std dst) (rule Month/OCTOBER 25 dst std)])
          obs   (#'ical/observances
                 rules (Instant/parse "2025-01-01T00:00:00Z") (Instant/parse "2028-01-01T00:00:00Z"))]
      (is (= 1 (count (filter #(.isPresent (.getProperty % "RRULE")) obs))) "October keeps its RRULE")
      (is (= ["20250223T020000" "20260301T020000" "20270228T020000"]
             (for [o obs :when (= "+0200" (.getValue (.getRequiredProperty o "TZOFFSETTO")))]
               (.getValue (.getRequiredProperty o "DTSTART"))))))))

;; =============================================================================
;; Import: zones from java.time, VTIMEZONE RRULEs as RFC 5545 reads them (BOU-723)
;; =============================================================================

(defn- us-eastern
  "A VTIMEZONE as Outlook writes US Eastern: DST from the second Sunday of March."
  [tzid]
  ["BEGIN:VTIMEZONE" (str "TZID:" tzid)
   "BEGIN:STANDARD" "DTSTART:16010101T020000" "TZOFFSETFROM:-0400" "TZOFFSETTO:-0500"
   "RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11" "END:STANDARD"
   "BEGIN:DAYLIGHT" "DTSTART:16010101T020000" "TZOFFSETFROM:-0500" "TZOFFSETTO:-0400"
   "RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3" "END:DAYLIGHT"
   "END:VTIMEZONE"])

(defn- import-at
  "Instants and zone of events at `locals` in `tzid`, the feed carrying `vtz`."
  [tzid vtz locals]
  (let [ical (apply vcalendar
                    (concat vtz
                            (mapcat (fn [l] ["BEGIN:VEVENT" (str "UID:" l)
                                             (str "DTSTART;TZID=" tzid ":" l) (str "DTEND;TZID=" tzid ":" l)
                                             "SUMMARY:x" "END:VEVENT"])
                                    locals)))]
    (map (juxt :start :timezone) (service/import-ical ical {:timezone "Europe/Amsterdam"}))))

(deftest ^:integration import-us-dst-week-test
  (testing "2027-03-10 is before DST in New York (it starts on the 14th), whatever the VTIMEZONE"
    (is (= [[(Instant/parse "2027-03-10T15:00:00Z") "America/New_York"]
            [(Instant/parse "2027-03-15T14:00:00Z") "America/New_York"]]
           (import-at "America/New_York" (us-eastern "America/New_York") ["20270310T100000" "20270315T100000"])
           (import-at "America/New_York" [] ["20270310T100000" "20270315T100000"]))))
  (testing "an Outlook TZID reads as the IANA zone"
    (is (= [[(Instant/parse "2027-03-10T15:00:00Z") "America/New_York"]]
           (import-at "Eastern Standard Time" (us-eastern "Eastern Standard Time") ["20270310T100000"])))))

(deftest ^:integration import-custom-vtimezone-test
  (let [tzid "Custom Eastern"
        [before after gap overlap fall-back]
        (import-at tzid (us-eastern tzid)
                   ["20270310T100000" "20270315T100000" "20270314T023000" "20271107T013000" "20271108T100000"])]
    (testing "BYDAY=2SU is the second Sunday"
      (is (= (Instant/parse "2027-03-10T15:00:00Z") (first before)))
      (is (= (Instant/parse "2027-03-15T14:00:00Z") (first after))))
    (testing "a time in the gap or the overlap reads as ZonedDateTime does"
      (is (= (Instant/parse "2027-03-14T07:30:00Z") (first gap)))
      (is (= (Instant/parse "2027-11-07T05:30:00Z") (first overlap)))
      (is (= (Instant/parse "2027-11-08T15:00:00Z") (first fall-back))))
    (testing "no IANA name, so the :timezone option"
      (is (= "Europe/Amsterdam" (second before)))))
  (testing "an unknown TZID without a VTIMEZONE reads in the :timezone option"
    (is (= [[(Instant/parse "2027-03-10T09:00:00Z") "Europe/Amsterdam"]]
           (import-at "Nowhere" [] ["20270310T100000"])))))

(deftest ^:integration import-round-trip-us-test
  (let [tz   "America/New_York"
        mk   (fn [local rrule] (cond-> {:id (random-uuid) :title "Call, then lunch" :timezone tz
                                         :start (at tz local) :end (.plusSeconds (at tz local) 3600)}
                                 rrule (assoc :recurrence rrule)))
        evs  [(mk "2027-03-08T09:00" nil) (mk "2027-03-13T23:30" nil) (mk "2027-03-10T10:00" "FREQ=WEEKLY")]
        ical (service/export-ical evs)]
    (testing "export writes the local time java.time gives"
      (is (str/includes? ical "DTSTART;TZID=America/New_York:20270308T090000")))
    (is (= (map #(select-keys % [:id :title :start :end :timezone :recurrence]) evs)
           (map #(select-keys % [:id :title :start :end :timezone :recurrence]) (service/import-ical ical))))))

(deftest ^:integration import-unreadable-test
  (testing "malformed input is a validation error"
    (doseq [ical ["not iCal"
                  (vcalendar "BEGIN:VEVENT" "UID:a" "DTSTART:2026-03-10" "END:VEVENT")
                  (vcalendar "BEGIN:VEVENT" "UID:a" "DTSTART:20260310T090000Z" "RRULE:FREQ=NEVER" "END:VEVENT")
                  (vcalendar "BEGIN:VEVENT" "DTSTART:20260310T090000Z" "END:VEVENT")]]
      (is (= :validation-error
             (:type (ex-data (try (service/import-ical ical) (catch clojure.lang.ExceptionInfo e e)))))))))
