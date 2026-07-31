# wagoe-calendar

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-calendar.svg)](https://clojars.org/com.wagoe/wagoe-calendar)

Calendar and scheduling library for the [Wagoe](https://github.com/wagoebv/wagoe) framework.

## Features

- **Recurring events** — RFC 5545 RRULE parsing (`DAILY`, `WEEKLY;BYDAY=MO,WE,FR`, `MONTHLY`, `YEARLY`, `COUNT`, `UNTIL`)
- **DST-safe occurrence expansion** — 9:00 AM local time stays at 9:00 AM local across spring-forward / fall-back transitions
- **Conflict detection** — overlap check for any pair of events, including recurring ones, within a time window
- **iCal export** — RFC 5545 VCALENDAR strings ready for Google Calendar / Outlook / iOS subscription
- **iCal import** — parse `.ics` files back to wagoe EventData maps
- **Hiccup UI components** — pure month-view, week-view, mini-calendar for admin interfaces
- **`defevent` macro** — named event type registry (same pattern as `defreport` in wagoe-reports)

## Installation

Add to your `deps.edn`:

```clojure
com.wagoe/wagoe-calendar {:mvn/version "1.0.0-beta-3"}
```

## Quick Start

```clojure
(require '[wagoe.calendar.core.recurrence :as r])
(require '[wagoe.calendar.shell.service :as cal])

(def standup
  {:id        (random-uuid)
   :title     "Daily Standup"
   :start     #inst "2026-03-02T09:00:00Z"
   :end       #inst "2026-03-02T09:15:00Z"
   :timezone  "Europe/Amsterdam"
   :recurrence "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"})

;; Occurrences in a window
(r/occurrences standup
               #inst "2026-03-01T00:00:00Z"
               #inst "2026-03-08T00:00:00Z")
;; => [Mon Mar 2, Tue Mar 3, Wed Mar 4, Thu Mar 5, Fri Mar 6]

;; Export as iCal feed
(cal/export-ical [standup] {})
;; => "BEGIN:VCALENDAR\nVERSION:2.0\n..."

;; Ring HTTP feed response
(cal/ical-feed-response [standup] {:filename "team-calendar.ics"})
;; => {:status 200 :headers {"Content-Type" "text/calendar; charset=utf-8" ...} :body "..."}
```

## Architecture

Follows the [Functional Core / Imperative Shell](https://github.com/wagoebv/wagoe) pattern:

- `core/` — pure functions (no I/O, testable without mocks)
- `shell/` — adapters with side effects (ical4j serialization)
- `schema.clj` — Malli validation schemas
- `ports.clj` — `CalendarAdapterProtocol` interface

## License

Eclipse Public License 2.0 — see [LICENSE](../../LICENSE).
