(ns wagoe.calendar.core.recurrence
  "RRULE parsing and occurrence expansion — pure functions, no I/O.

   Uses ical4j's net.fortuna.ical4j.model.Recur for RFC 5545 RRULE parsing.
   All date arithmetic uses java.time with explicit timezone handling so that
   DST transitions are respected (e.g. a 9:00 AM recurring event stays at
   9:00 AM local time even when UTC offset changes).

   ical4j 4.x API note: Recur.getDates accepts java.time.ZonedDateTime seeds
   and returns List<ZonedDateTime>, giving correct DST-aware expansion.

   FC/IS rule: no I/O, no logging, no exceptions thrown for valid inputs."
  (:require [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime Duration]
           [java.time.temporal ChronoUnit]
           [net.fortuna.ical4j.model Recur]))

;; =============================================================================
;; Public API
;; =============================================================================

(defn recurring?
  "Return true if the event has a non-blank :recurrence (RRULE) value.

   Args:
     event - EventData map

   Returns boolean."
  [event]
  (let [r (:recurrence event)]
    (boolean (and r (not (str/blank? r))))))

(defn occurrences
  "Return a vector of Instants representing occurrence starts within [window-start, window-end).

   For recurring events the RRULE is parsed via ical4j Recur and dates are
   iterated as ZonedDateTimes in the event's :timezone so that DST transitions
   are correctly handled — a 09:00 local-time recurrence stays at 09:00 even
   after a clock change.

   For non-recurring events: returns [(:start event)] if the event overlaps
   the window (start < window-end AND end > window-start), else [].

   Args:
     event        - EventData map
     window-start - java.time.Instant (inclusive)
     window-end   - java.time.Instant (exclusive)

   Returns a vector of java.time.Instant values (occurrence starts)."
  [event window-start window-end]
  (let [zone     (ZoneId/of (:timezone event))
        ev-start ^Instant (:start event)
        ev-end   ^Instant (:end event)
        ws       ^Instant window-start
        we       ^Instant window-end]
    (if (recurring? event)
      (let [recur       (Recur. ^String (:recurrence event))
            ;; Use ZonedDateTime seeds for DST-aware expansion (ical4j 4.x API)
            seed        (.atZone ev-start zone)
            ;; Expand slightly before window-start so occurrences at the boundary are included
            range-start (.atZone (.minus ws 1 ChronoUnit/MILLIS) zone)
            range-end   (.atZone we zone)
            zdts        (.getDates recur seed range-start range-end)]
        (->> zdts
             (map (fn [^ZonedDateTime zdt] (.toInstant zdt)))
             (filter (fn [^Instant occ-start]
                       (and (not (.isBefore occ-start ws))
                            (.isBefore occ-start we))))
             vec))
      ;; Non-recurring: return [start] if event overlaps window
      ;; half-open interval: start < window-end AND end > window-start
      (if (and (.isBefore ev-start we)
               (.isAfter ev-end ws))
        [ev-start]
        []))))

(defn next-occurrence*
  "Return the first occurrence start Instant after the explicit reference time, or nil if none.

   For non-recurring events: returns (:start event) if it is in the future, else nil.

   Args:
     event - EventData map
     now - java.time.Instant supplied by the shell

   Returns java.time.Instant or nil."
  [event now]
  (let [now        ^Instant now
        ;; Search 100 years ahead to handle events with far-future starts
        far-future (.plus now 36524 ChronoUnit/DAYS)
        occs       (occurrences event now far-future)]
    (first occs)))

(defn expand-event
  "Return a vector of single-occurrence EventData maps for the given window.

   Each returned map has :start and :end set to the occurrence's times.
   The :recurrence key is removed since each map represents a single occurrence.

   For non-recurring events: returns a single-element vector (or empty if outside window).

   Args:
     event        - EventData map
     window-start - java.time.Instant
     window-end   - java.time.Instant

   Returns a vector of EventData maps (without :recurrence key)."
  [event window-start window-end]
  (let [ev-start ^Instant (:start event)
        ev-end   ^Instant (:end event)
        ev-dur   (Duration/between ev-start ev-end)
        occs     (occurrences event window-start window-end)]
    (->> occs
         (mapv (fn [^Instant occ-start]
                 (let [occ-end (.plusSeconds occ-start (.getSeconds ev-dur))]
                   (-> event
                       (assoc :start occ-start
                              :end   occ-end)
                       (dissoc :recurrence))))))))
