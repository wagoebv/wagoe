(ns wagoe.calendar.core.event
  "Pure helper functions for working with EventData maps.

   Provides `duration`, `all-day?`, `within-range?`, and validation
   delegates. The event type definition registry and the `defevent` macro
   live in the shell (wagoe.calendar.shell.registry) — this namespace
   holds no mutable state.

   FC/IS rule: no I/O here. All side effects live in the shell layer."
  (:require [wagoe.calendar.schema :as schema])
  (:import [java.time Duration Instant ZoneId]))

;; =============================================================================
;; Pure helpers
;; =============================================================================

(defn duration
  "Return the java.time.Duration between an event's :start and :end.

   Args:
     event - EventData map with :start and :end Instants

   Returns java.time.Duration (may be zero or negative for malformed events)."
  [event]
  (Duration/between ^Instant (:start event) ^Instant (:end event)))

(defn all-day?
  "Return true if the event spans at least 24 hours and starts at midnight UTC.

   Args:
     event - EventData map

   Returns boolean."
  [event]
  (let [dur     (duration event)
        start   ^Instant (:start event)
        zdt     (.atZone start (ZoneId/of "UTC"))
        midnight? (and (zero? (.getHour zdt))
                       (zero? (.getMinute zdt))
                       (zero? (.getSecond zdt))
                       (zero? (.getNano zdt)))]
    (and midnight?
         (>= (.toHours dur) 24))))

(defn within-range?
  "Return true if the event overlaps with [range-start, range-end).

   Uses half-open interval: event start < range-end AND event end > range-start.

   Args:
     event       - EventData map with :start and :end Instants
     range-start - java.time.Instant
     range-end   - java.time.Instant

   Returns boolean."
  [event range-start range-end]
  (let [es ^Instant (:start event)
        ee ^Instant (:end event)
        rs ^Instant range-start
        re ^Instant range-end]
    (and (.isBefore es re)
         (.isAfter ee rs))))

(defn valid-event?
  "Returns true if the given map satisfies EventData schema.

   Delegates to wagoe.calendar.schema/valid-event?."
  [event]
  (schema/valid-event? event))

(defn explain-event
  "Returns human-readable validation errors for an event map.

   Delegates to wagoe.calendar.schema/explain-event."
  [event]
  (schema/explain-event event))
