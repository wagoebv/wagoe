(ns wagoe.calendar.schema
  "Malli validation schemas for the calendar module."
  (:require [malli.core :as m])
  (:import [java.time ZoneId]))

;; =============================================================================
;; Validation helpers (defined first so they can be used in schemas)
;; =============================================================================

(defn valid-timezone?
  "Returns true if the given string is a valid IANA timezone identifier."
  [tz]
  (try
    (ZoneId/of tz)
    true
    (catch Exception _ false)))

;; =============================================================================
;; EventData — base event map all library functions operate on
;; =============================================================================

(def EventData
  "Base calendar event map. All library functions operate on this shape.
   Dates are java.time.Instant (UTC). Timezone is a TZ database name string.
   Recurrence holds an RFC 5545 RRULE string when the event repeats."
  [:map
   [:id         :uuid]
   [:title      :string]
   [:start      inst?]                              ; java.time.Instant (UTC)
   [:end        inst?]                              ; java.time.Instant (UTC)
   [:timezone   [:and :string [:fn valid-timezone?]]]  ; IANA timezone with validation
   [:recurrence {:optional true} :string]           ; RFC 5545 RRULE string
   [:attendees  {:optional true} [:vector :uuid]]])

;; =============================================================================
;; EventDef — definition registered via defevent
;; =============================================================================

(def EventDef
  "Event type definition registered via the defevent macro."
  [:map
   [:id     :keyword]
   [:label  {:optional true} :string]
   [:schema {:optional true} :any]])              ; optional Malli schema extension

;; =============================================================================
;; OccurrenceResult — single expanded occurrence of a (possibly recurring) event
;; =============================================================================

(def OccurrenceResult
  "Result of expanding a recurring event to a single occurrence."
  [:map
   [:event EventData]
   [:start inst?]
   [:end   inst?]])

;; =============================================================================
;; ConflictResult — two overlapping event occurrences
;; =============================================================================

(def ConflictResult
  "Result indicating two events overlap within a given window."
  [:map
   [:event-a       EventData]
   [:event-b       EventData]
   [:overlap-start inst?]
   [:overlap-end   inst?]])

;; =============================================================================
;; Additional validation helpers
;; =============================================================================

(def ^:private event-data-validator (m/validator EventData))
(def ^:private event-data-explainer (m/explainer EventData))
(def ^:private event-def-validator (m/validator EventDef))

(defn valid-event?
  "Returns true if the given map satisfies EventData schema."
  [event]
  (event-data-validator event))

(defn explain-event
  "Returns human-readable validation errors for an event map."
  [event]
  (event-data-explainer event))

(defn valid-event-def?
  "Returns true if the given map satisfies EventDef schema."
  [event-def]
  (event-def-validator event-def))
