(ns wagoe.calendar.core.conflict
  "Conflict detection for calendar events — pure functions, no I/O.

   Detects overlapping events by expanding recurring events to individual
   occurrences and performing pairwise interval overlap checks.

   FC/IS rule: no I/O, no logging, no exceptions."
  (:require [wagoe.calendar.core.recurrence :as recurrence])
  (:import [java.time Instant]))

;; =============================================================================
;; Overlap check
;; =============================================================================

(defn overlaps?
  "Return true if two (expanded, non-recurring) events overlap in time.

   Uses half-open interval overlap: a.start < b.end AND a.end > b.start.
   Events that share only a boundary (end of A = start of B) do NOT overlap.

   Args:
     event-a - EventData map with :start and :end Instants
     event-b - EventData map with :start and :end Instants

   Returns boolean."
  [event-a event-b]
  (let [as ^Instant (:start event-a)
        ae ^Instant (:end event-a)
        bs ^Instant (:start event-b)
        be ^Instant (:end event-b)]
    (and (.isBefore as be)
         (.isAfter ae bs))))

;; =============================================================================
;; Conflict detection
;; =============================================================================

(defn conflicts?
  "Return true if any occurrence of event-a overlaps any occurrence of event-b
   within the given time window.

   Both events are expanded via recurrence/expand-event before comparison.

   Args:
     event-a      - EventData map (may be recurring)
     event-b      - EventData map (may be recurring)
     window-start - java.time.Instant
     window-end   - java.time.Instant

   Returns boolean."
  [event-a event-b window-start window-end]
  (let [occs-a (recurrence/expand-event event-a window-start window-end)
        occs-b (recurrence/expand-event event-b window-start window-end)]
    (boolean
     (some (fn [a]
             (some (fn [b] (overlaps? a b))
                   occs-b))
           occs-a))))

(defn find-conflicts
  "Return a vector of ConflictResult maps for all overlapping event pairs within the window.

   Performs O(n²) pairwise comparison after expanding recurring events.
   Each pair is reported at most once (a vs b, not b vs a).

   Args:
     events       - seq of EventData maps
     window-start - java.time.Instant
     window-end   - java.time.Instant

   Returns a vector of maps:
     {:event-a       EventData
      :event-b       EventData
      :overlap-start Instant
      :overlap-end   Instant}"
  [events window-start window-end]
  (let [events-vec (vec events)]
    (for [i     (range (count events-vec))
          j     (range (inc i) (count events-vec))
          :let  [ea (nth events-vec i)
                 eb (nth events-vec j)
                 occs-a (recurrence/expand-event ea window-start window-end)
                 occs-b (recurrence/expand-event eb window-start window-end)]
          a     occs-a
          b     occs-b
          :when (overlaps? a b)]
      {:event-a       ea
       :event-b       eb
       :overlap-start (if (.isAfter ^Instant (:start a) ^Instant (:start b))
                        (:start a)
                        (:start b))
       :overlap-end   (if (.isBefore ^Instant (:end a) ^Instant (:end b))
                        (:end a)
                        (:end b))})))
