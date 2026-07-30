(ns wagoe.calendar.shell.adapters.ical
  "ical4j 4.x adapter implementing CalendarAdapterProtocol.

   Converts wagoe EventData maps ↔ RFC 5545 VCALENDAR strings using
   net.fortuna.ical4j 4.x. VEvents are built with ZonedDateTime so that
   TZID parameters are correctly emitted and preserved in round-trips.

   Shell layer — may perform I/O via ical4j CalendarBuilder/CalendarOutputter."
  (:require [wagoe.calendar.ports :as ports]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime LocalDate LocalDateTime ZoneOffset]
           [java.io StringReader StringWriter]
           [java.nio.charset StandardCharsets]
           [net.fortuna.ical4j.model Calendar]
           [net.fortuna.ical4j.model.component VEvent]
           [net.fortuna.ical4j.model.property Uid RRule Description]
           [net.fortuna.ical4j.data CalendarBuilder CalendarOutputter]))

;; =============================================================================
;; Internal helpers — export
;; =============================================================================

(defn- instant->zdt
  "Convert java.time.Instant to ZonedDateTime in the given IANA timezone."
  ^ZonedDateTime [^Instant instant ^String timezone]
  (.atZone instant (ZoneId/of timezone)))

(defn- event->vevent
  "Convert a boundary EventData map to a net.fortuna.ical4j 4.x VEvent.

   Uses the Temporal-accepting VEvent constructor (4.x API) so ical4j emits
   DTSTART;TZID=... correctly."
  [event]
  (let [tz     (:timezone event "UTC")
        zdt-s  (instant->zdt (:start event) tz)
        zdt-e  (instant->zdt (:end event) tz)
        title  ^String (:title event)
        uid    (Uid. (str (:id event)))
        ;; Base VEvent from 4.x Temporal constructor; add UID via fluent chain
        vevent (-> (VEvent. ^java.time.temporal.Temporal zdt-s
                            ^java.time.temporal.Temporal zdt-e
                            title)
                   (.withProperty uid))
        vevent (if-let [rrule (:recurrence event)]
                 (.withProperty vevent (RRule. ^String rrule))
                 vevent)
        vevent (if-let [desc (:description event)]
                 (.withProperty vevent (Description. ^String desc))
                 vevent)]
    (.getFluentTarget vevent)))

(defn- build-calendar
  "Build a net.fortuna.ical4j 4.x Calendar from a seq of VEvents.

   In ical4j 4.x Calendar.withProdId accepts a plain String (not a ProdId object)
   and Calendar.withDefaults adds VERSION:2.0 and CALSCALE:GREGORIAN."
  [vevents ^String product-id]
  (let [base-cal (-> (Calendar.)
                     (.withProdId product-id)
                     (.withDefaults))
        with-events (reduce (fn [^Calendar c ve]
                              (.withComponent c ve))
                            base-cal
                            vevents)]
    (.getFluentTarget with-events)))

;; =============================================================================
;; Internal helpers — import
;; =============================================================================

(defn- find-prop
  "Find the first property matching prop-name in the VEvent's property list.

   In ical4j 4.x getProperties() returns a plain java.util.List<Property>.
   We filter by name rather than calling .getProperty on the list."
  [vevent ^String prop-name]
  (some (fn [p] (when (= prop-name (.getName p)) p))
        (.getProperties vevent)))

(defn- get-prop-value
  "Safely get a property value string from a VEvent by property name."
  [vevent prop-name]
  (when-let [p (find-prop vevent prop-name)]
    (.getValue p)))

(defn- dt-prop->instant
  "Convert an ical4j DtStart or DtEnd property to java.time.Instant.

   In ical4j 4.x the date value is a java.time temporal stored in the property.
   We retrieve it via .getDate and dispatch on its concrete type."
  [dt-prop]
  (when dt-prop
    (try
      (let [dt (.getDate dt-prop)]
        (cond
          (instance? ZonedDateTime dt)
          (.toInstant ^ZonedDateTime dt)

          (instance? LocalDateTime dt)
          (.toInstant ^LocalDateTime dt ZoneOffset/UTC)

          (instance? LocalDate dt)
          (.toInstant (.atStartOfDay ^LocalDate dt) ZoneOffset/UTC)

          (instance? java.util.Date dt)
          (.toInstant ^java.util.Date dt)

          :else nil))
      (catch Exception _ nil))))

(defn- get-tzid
  "Extract the TZID from the DTSTART property's string representation.

   ical4j 4.x resolves timezone references to internal synthetic IDs when
   parsing (e.g. 'ical4j~<uuid>'). Instead we parse the TZID parameter
   directly from the property text (e.g. 'DTSTART;TZID=Europe/Amsterdam:...')
   which always carries the original IANA timezone name."
  [vevent]
  (when-let [p (find-prop vevent "DTSTART")]
    (when-let [text (str p)]
      (when-let [m (re-find #"TZID=([^;:\r\n]+)" text)]
        (str/trim (second m))))))

(defn- uid-string->uuid
  "Convert a UID string to a UUID.
   If the string is already a valid UUID, use it directly.
   Otherwise, generate a deterministic UUID v3 (MD5-based) from the string.
   This ensures the same UID string always produces the same UUID across imports."
  [uid-str]
  (try
    (java.util.UUID/fromString uid-str)
    (catch Exception _
      ;; Use Java's built-in nameUUIDFromBytes which generates UUID v3 (MD5-based)
      (java.util.UUID/nameUUIDFromBytes (.getBytes uid-str StandardCharsets/UTF_8)))))

(defn- vevent->event
  "Convert a net.fortuna.ical4j 4.x VEvent to a boundary EventData map."
  [vevent]
  (let [uid-str   (get-prop-value vevent "UID")
        title     (get-prop-value vevent "SUMMARY")
        ev-start  (dt-prop->instant (find-prop vevent "DTSTART"))
        ev-end    (dt-prop->instant (find-prop vevent "DTEND"))
        rrule-str (get-prop-value vevent "RRULE")
        timezone  (or (get-tzid vevent) "UTC")]
    (cond-> {:id       (uid-string->uuid uid-str)
             :title    (or title "")
             :start    ev-start
             :end      ev-end
             :timezone timezone}
      rrule-str (assoc :recurrence rrule-str))))

;; =============================================================================
;; ICalAdapter record
;; =============================================================================

(defrecord ICalAdapter []
  ports/CalendarAdapterProtocol

  (export-ical [_this events opts]
    (let [product-id (get opts :product-id "-//Wagoe//Calendar//EN")
          vevents    (mapv event->vevent events)
          calendar   (build-calendar vevents product-id)
          writer     (StringWriter.)
          outputter  (CalendarOutputter.)]
      (.output outputter calendar writer)
      (.toString writer)))

  (import-ical [_this ical-string _opts]
    (let [reader   (StringReader. ical-string)
          builder  (CalendarBuilder.)
          calendar (.build builder reader)
          vevents  (filter #(instance? VEvent %)
                           (.getComponents calendar))]
      (mapv vevent->event vevents))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-ical-adapter
  "Create and return a new ICalAdapter instance."
  []
  (->ICalAdapter))
