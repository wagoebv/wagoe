# wagoe-calendar — Dev Guide

## 1. Purpose

`wagoe-calendar` handles recurring events, timezone-aware occurrence expansion, conflict detection, iCal export/import, and admin UI components for any Wagoe-based application dealing with scheduling (appointments, bookings, shifts, room reservations, etc.).

It saves ~€3.2k–€4k per project by eliminating boilerplate around:
- RFC 5545 RRULE parsing (weekly standups, monthly invoices, annual reviews)
- DST-safe occurrence expansion (9:00 AM stays 9:00 AM local across spring-forward)
- Overlap / conflict detection for double-booking prevention
- iCal feed generation for Google Calendar / Outlook / iOS subscription

**FC/IS rule**: `core/` is pure. All I/O (ical4j serialization, HTTP responses) lives in `shell/`.

---

## 2. Key Namespaces

| Namespace | Layer | Responsibility |
|-----------|-------|----------------|
| `wagoe.calendar.schema` | shared | Malli schemas: `EventData`, `EventDef`, `OccurrenceResult`, `ConflictResult` |
| `wagoe.calendar.ports` | shared | `CalendarAdapterProtocol`, implemented by the `:wagoe/calendar` component |
| `wagoe.calendar.core.event` | core | Pure helpers: `duration`, `all-day?`, `within-range?`, `valid-event?` |
| `wagoe.calendar.core.feed` | core | `ical-response`: Ring response for an iCal feed |
| `wagoe.calendar.core.recurrence` | core | RRULE parsing, `occurrences`, `next-occurrence*`, `expand-event` |
| `wagoe.calendar.core.conflict` | core | `overlaps?`, `conflicts?`, `find-conflicts` |
| `wagoe.calendar.core.ui` | core | Pure Hiccup: `month-view`, `week-view`, `mini-calendar`, `event-badge` |
| `wagoe.calendar.shell.registry` | shell | `defevent` macro, event type registry (`register-event-type!`, `get-event-type`, `list-event-types`, `clear-registry!`) |
| `wagoe.calendar.shell.adapters.ical` | shell | ical4j adapter (`ICalAdapter`) |
| `wagoe.calendar.shell.service` | shell | The same calls without Integrant: `export-ical`, `import-ical`, `ical-feed-response` |
| `wagoe.calendar.shell.module-wiring` | shell | `:wagoe/calendar` init-key: returns the ical4j adapter |

---

## 3. `defevent` Usage

The `defevent` macro and the event type registry live in the shell
(`wagoe.calendar.shell.registry`); `wagoe.calendar.core.event` is pure.

```clojure
(require '[wagoe.calendar.shell.registry :as registry])

;; Define an event type with optional schema extension
(registry/defevent appointment-event
  {:id    :appointment
   :label "Appointment"
   :schema [:map
            [:patient-id :uuid]
            [:room       :string]]})

;; Registry operations
(registry/get-event-type :appointment)   ;; => {:id :appointment ...}
(registry/list-event-types)              ;; => [:appointment ...]
(registry/clear-registry!)               ;; use in tests only
```

**Validation** of raw EventData maps (independent of type registry, pure core):
```clojure
(require '[wagoe.calendar.core.event :as event])

(event/valid-event? {:id (random-uuid) :title "X"
                     :start #inst "2026-03-10T09:00:00Z"
                     :end   #inst "2026-03-10T10:00:00Z"
                     :timezone "Europe/Amsterdam"})
;; => true
```

---

**Linting:** the macro binds a var clj-kondo cannot see from a published jar,
so the bound name reads as an unresolved symbol. From 1.0.0-rc-4,
`wagoe-calendar` ships a clj-kondo export carrying the rule (BOU-503) — but an
export applies only once copied into `.clj-kondo/imports/`. clj-kondo does not
read exports during an ordinary lint, and `bb check` passes source paths rather
than the classpath. Import them once, and again after changing dependencies:

```bash
bb lint:imports     # writes .clj-kondo/imports/ — commit it
```

Before rc-4, or if you would rather not commit the imports, keep the rule local:

```clojure
{:lint-as {wagoe.calendar.shell.registry/defevent clojure.core/def}}
```

## 4. Occurrence Calculation

All occurrence functions are pure and live in `wagoe.calendar.core.recurrence`.

```clojure
(require '[wagoe.calendar.core.recurrence :as r])

(def standup
  {:id        (random-uuid)
   :title     "Standup"
   :start     #inst "2026-03-02T08:00:00Z"   ; 09:00 Amsterdam (UTC+1)
   :end       #inst "2026-03-02T08:30:00Z"
   :timezone  "Europe/Amsterdam"
   :recurrence "FREQ=WEEKLY;BYDAY=MO,WE,FR"})

;; Returns vector of Instant values (Mon/Wed/Fri in the given week)
(r/occurrences standup
               #inst "2026-03-01T00:00:00Z"
               #inst "2026-03-08T00:00:00Z")
;; => [#inst "2026-03-02T08:00:00Z"
;;     #inst "2026-03-04T08:00:00Z"
;;     #inst "2026-03-06T08:00:00Z"]

;; Expand to full event maps (no :recurrence key)
(r/expand-event standup
                #inst "2026-03-01T00:00:00Z"
                #inst "2026-03-08T00:00:00Z")
;; => [{:id ... :title "Standup" :start #inst "..." :end #inst "..." :timezone "..."}
;;     ...]

;; Next future occurrence from an explicit reference instant
(r/next-occurrence* standup #inst "2026-03-02T00:00:00Z")
;; => #inst "2026-XX-XXT08:00:00Z"  ; next Mon/Wed/Fri
```

### Timezone notes

- `:timezone` must be a valid IANA TZ database name (e.g. `"Europe/Amsterdam"`, `"America/New_York"`).
- ical4j's `Recur` iterates in UTC internally but respects DST when a `TimeZone` is attached to the `DateTime` property.
- **Always store `:start` and `:end` as UTC `Instant`s** in your database. Convert to local time only for display.
- `expand-event` preserves the original duration across DST boundaries.

---

## 5. Conflict Detection

```clojure
(require '[wagoe.calendar.core.conflict :as c])

;; Simple overlap check (expanded, single-occurrence events)
(c/overlaps? event-a event-b)   ;; => bool

;; Check two events (potentially recurring) in a window
(c/conflicts? event-a event-b
              #inst "2026-03-01T00:00:00Z"
              #inst "2026-04-01T00:00:00Z")
;; => bool

;; Find all conflicts in a collection
(c/find-conflicts [room-a-booking room-b-booking shift-a shift-b]
                  #inst "2026-03-01T00:00:00Z"
                  #inst "2026-04-01T00:00:00Z")
;; => [{:event-a {...} :event-b {...}
;;      :overlap-start #inst "..." :overlap-end #inst "..."} ...]
```

`find-conflicts` is O(n²) after expansion. For large event collections, pre-filter to a narrow time window.

---

## 6. iCal Export + HTTP Feed

`:wagoe/calendar` is a `CalendarAdapterProtocol` (ical4j). Take
`(ig/ref :wagoe/calendar)` and call it through the ports; an app needs no
shell require, so `bb check:ports` stays green.

```clojure
(require '[wagoe.calendar.ports :as cal]
         '[wagoe.calendar.core.feed :as feed])

;; Export to RFC 5545 string
(cal/export-ical calendar [standup appointment] {})
;; => "BEGIN:VCALENDAR\nVERSION:2.0\n..."

;; Custom PRODID
(cal/export-ical calendar events {:product-id "-//MyApp//EN"})
;; Each zone used gets a VTIMEZONE; UTC is written as Z. Two events with
;; one :id are a :validation-error (UID must be unique).

;; Ring response for an HTTP subscription feed (pure)
(feed/ical-response ical-string {:filename "team.ics" :max-age 300})
;; => {:status 200
;;     :headers {"Content-Type" "text/calendar; charset=utf-8"
;;               "Content-Disposition" "attachment; filename=\"team.ics\""
;;               "Cache-Control" "private, max-age=300"}
;;     :body "BEGIN:VCALENDAR..."}
```

Wire into a Reitit route:
```clojure
["/calendar/feed.ics"
 {:get {:handler (fn [_req]
                   (feed/ical-response (cal/export-ical calendar (list-events) {})))}}]
```

Without Integrant, `wagoe.calendar.shell.service` has `export-ical`,
`import-ical` and `ical-feed-response` without the adapter argument.

**All-day events.** `:all-day? true` is written as `DTSTART;VALUE=DATE` with an
exclusive `DTEND` (the day after the last), dates taken in the event's
`:timezone`. An end that is not local midnight still covers its day.

---

## 7. iCal Import

```clojure
(cal/import-ical calendar ical-string {:timezone "Europe/Amsterdam"})
;; => [{:id #uuid "..." :title "..." :start #inst "..." :end #inst "..."
;;      :timezone "Europe/Amsterdam" :recurrence "FREQ=WEEKLY;BYDAY=MO"}
;;     ...]
```

- `:start` and `:end` are `java.time.Instant` (UTC).
- `:timezone` is extracted from the `DTSTART;TZID=...` parameter. Without one,
  the `:timezone` option applies (default `"UTC"`), to dates and floating times.
- `DTSTART;VALUE=DATE` comes back `:all-day? true`, starting at midnight in that
  zone. Without a `DTEND` it lasts one day.
- Unknown VEVENT properties are ignored.
- `:recurrence` is the raw RRULE string (ready for `recurrence/occurrences`).

---

## 8. Calendar UI Components

All components are pure Hiccup functions in `wagoe.calendar.core.ui`.

```clojure
(require '[wagoe.calendar.core.ui :as ui])
(require '[java.time LocalDate])

;; Month grid
(ui/month-view 2026 3 events {:today (LocalDate/now) :timezone "Europe/Amsterdam"})

;; Week grid (hourly rows)
(ui/week-view (LocalDate/of 2026 3 2) events {:today (LocalDate/now)})

;; Compact sidebar navigator
(ui/mini-calendar 2026 3 (LocalDate/now) {:today (LocalDate/now)})

;; Single event pill
(ui/event-badge event "Europe/Amsterdam")

;; Link each event to its record
(ui/month-view 2026 3 events {:event-href #(str "/appointments/" (:id %))})
```

- **Links.** An event's own `:href` links its badge; otherwise the view option `:event-href` (fn event -> url) does. A linked badge is an `[:a.event-badge]`.
- **Labels are `[:t …]` markers** (weekdays, month names, "All day"). They resolve only through `wagoe.i18n.shell.render`; plain `hiccup2.core/html` prints them as `<t>` tags. Either render through i18n, or pass `:t` (fn key -> string, e.g. the request's t-fn) to resolve them when the hiccup is built.
- **Multi-day events** show on every day they cover, clipped to the visible month or week. The end is exclusive, so an event ending at midnight stays on one day. In the week view, events of 24 hours or more sit in an all-day row above the hours; shorter overnight events stay in their start hour. The time shows on the start day only.
- **Links** with a scheme other than `http(s)` or `mailto` (such as `javascript:`) are dropped and the badge renders unlinked.
- **All-day events** are marked `:all-day? true`. They show no time, and their dates are taken in the event's own `:timezone`, so they do not shift a day when the view uses another zone.

**CSS classes** emitted (for your stylesheet):
- `.calendar-month`, `.calendar-grid`, `.calendar-row`, `.day-cell`, `.day-cell.today`, `.day-cell.selected`, `.event-badge`, `.event-time`, `.event-title`
- `.calendar-week`, `.week-header`, `.week-day-header`, `.week-all-day`, `.hour-row`, `.week-cell`
- `.mini-calendar`, `.mini-grid`, `.mini-cell`, `.mini-cell.today`, `.mini-cell.selected`

---

## 9. Common Pitfalls

### 1. DST — UNTIL vs local time
`UNTIL=20260329T010000Z` is UTC. If your event is at 09:00 Amsterdam (UTC+1 in winter), the UNTIL should be `20260329T080000Z` (08:00 UTC). Using the wrong offset causes the last occurrence to be silently dropped. Prefer `COUNT=N` when possible.

### 2. UTC storage — always store Instants
Store `:start` and `:end` as UTC in the database. Never store local time strings. The `:timezone` field drives display formatting.

### 3. Instant vs ZonedDateTime
`occurrences` returns `java.time.Instant` values. To display them in a local timezone, use `(.atZone instant (ZoneId/of timezone))`.

### 4. ical4j DateTime mutability
ical4j `DateTime` objects are mutable. The adapter always creates new instances — never share them across threads.

### 5. RRULE BYDAY with MONTHLY
`FREQ=MONTHLY;BYDAY=1MO` means "first Monday of each month". Without the ordinal prefix, `BYDAY=MO` with `FREQ=MONTHLY` means *every* Monday of each month (multiple per month). Be explicit.

### 6. Expansion window size
`expand-event` calls `occurrences` which iterates all dates in the window. For `FREQ=DAILY` over 10 years, this produces ~3650 maps. Always pass a narrow window relevant to your use case.

### 7. All-day is a flag
`event/all-day?` reads `:all-day? true` and nothing else. A timed event from midnight to midnight is not all-day: set the flag, or it is exported with times.

### 8. Registry pollution in tests
Always use `(use-fixtures :each (fn [f] (registry/clear-registry!) (f) (registry/clear-registry!)))` (with `wagoe.calendar.shell.registry` aliased as `registry`) to prevent `defevent` definitions at namespace load time from leaking across tests.

---

## 10. Testing Commands

```bash
# All calendar tests
clojure -M:test :calendar

# Unit tests only
clojure -M:test --focus-meta :unit :calendar

# DST edge cases specifically
clojure -M:test :calendar --focus wagoe.calendar.core.recurrence-test

# iCal round-trip integration tests
clojure -M:test --focus-meta :integration :calendar

# Lint
clojure -M:clj-kondo --lint libs/calendar/src libs/calendar/test
```

---

## 11. REPL Smoke Check

```clojure
(require '[wagoe.calendar.core.recurrence :as r])
(require '[wagoe.calendar.shell.service :as cal])

(def standup
  {:id        (random-uuid)
   :title     "Standup"
   :start     #inst "2026-03-02T08:00:00Z"
   :end       #inst "2026-03-02T08:30:00Z"
   :timezone  "Europe/Amsterdam"
   :recurrence "FREQ=WEEKLY;BYDAY=MO,WE,FR"})

;; 3 occurrences in first week of March
(r/occurrences standup #inst "2026-03-01T00:00:00Z" #inst "2026-03-08T00:00:00Z")

;; iCal export
(cal/export-ical [standup] {})

;; Ring feed response
(:status (cal/ical-feed-response [standup]))
;; => 200
```
