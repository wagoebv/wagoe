(ns wagoe.calendar.shell.adapters.ical
  "ical4j 4.x adapter implementing CalendarAdapterProtocol.

   Converts wagoe EventData maps ↔ RFC 5545 VCALENDAR strings using
   net.fortuna.ical4j 4.x. VEvents are built with ZonedDateTime so that
   TZID parameters are correctly emitted and preserved in round-trips.

   Shell layer — may perform I/O via ical4j CalendarBuilder/CalendarOutputter."
  (:require [wagoe.calendar.core.event :as event]
            [wagoe.calendar.ports :as ports]
            [wagoe.calendar.schema :as schema]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime OffsetDateTime LocalDate LocalDateTime LocalTime]
           [java.time.temporal Temporal TemporalAmount]
           [java.io StringReader StringWriter]
           [java.nio.charset StandardCharsets]
           [net.fortuna.ical4j.model Calendar]
           [net.fortuna.ical4j.model.component VEvent]
           [net.fortuna.ical4j.model.property Uid RRule Description]
           [net.fortuna.ical4j.data CalendarBuilder CalendarOutputter]))

;; =============================================================================
;; Internal helpers — export
;; =============================================================================

(defn- all-day-dates
  "Start and exclusive end date of an all-day event, in `zone`. An end that is
   not midnight still covers its day, and the event covers at least one."
  [event ^ZoneId zone]
  (let [start (.toLocalDate (.atZone ^Instant (:start event) zone))
        end-z (.atZone ^Instant (:end event) zone)
        end   (cond-> (.toLocalDate end-z)
                (not= LocalTime/MIDNIGHT (.toLocalTime end-z)) (.plusDays 1))]
    [start (if (.isAfter end start) end (.plusDays start 1))]))

(defn- event->vevent
  "Convert a boundary EventData map to a net.fortuna.ical4j 4.x VEvent.

   ZonedDateTimes make ical4j emit DTSTART;TZID=..., LocalDates (all-day
   events) DTSTART;VALUE=DATE."
  [event]
  (let [zone   (ZoneId/of (:timezone event "UTC"))
        [s e]  (if (event/all-day? event)
                 (all-day-dates event zone)
                 [(.atZone ^Instant (:start event) zone)
                  (.atZone ^Instant (:end event) zone)])
        title  ^String (:title event)
        uid    (Uid. (str (:id event)))
        ;; Base VEvent from 4.x Temporal constructor; add UID via fluent chain
        vevent (-> (VEvent. ^Temporal s ^Temporal e title)
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

(defn- prop-date
  "The java.time temporal an ical4j 4.x DtStart or DtEnd property holds."
  [dt-prop]
  (when dt-prop
    (try (.getDate dt-prop) (catch Exception _ nil))))

(defn- temporal->instant
  "Instant of a DTSTART/DTEND value. Floating times and dates (VALUE=DATE)
   are read in `zone`."
  [dt ^ZoneId zone]
  (cond
    (instance? ZonedDateTime dt) (.toInstant ^ZonedDateTime dt)
    ;; UTC times (DTSTART:20261017T100000Z), what Google Calendar exports
    (instance? OffsetDateTime dt) (.toInstant ^OffsetDateTime dt)
    (instance? LocalDateTime dt) (.toInstant (.atZone ^LocalDateTime dt zone))
    (instance? LocalDate dt)     (.toInstant (.atStartOfDay ^LocalDate dt zone))
    (instance? java.util.Date dt) (.toInstant ^java.util.Date dt)
    :else nil))

(defn- dt-plus-duration
  "DTSTART value plus a DURATION property, or nil without one, or when the
   two do not combine (a time duration on a date)."
  [dt duration-prop]
  (when (and (instance? Temporal dt) duration-prop)
    (try (.plus ^Temporal dt ^TemporalAmount (.getDuration duration-prop))
         (catch Exception _ nil))))

(defn- get-tzid
  "Extract the TZID from the DTSTART property's string representation.

   ical4j 4.x resolves timezone references to internal synthetic IDs when
   parsing (e.g. 'ical4j~<uuid>'). Instead we parse the TZID parameter
   directly from the property text (e.g. 'DTSTART;TZID=Europe/Amsterdam:...')
   which always carries the original IANA timezone name."
  [vevent]
  (when-let [p (find-prop vevent "DTSTART")]
    ;; `str` never returns nil, so this was a `when-let` that always took its
    ;; branch — a guard reading as if a property could stringify to nothing.
    (let [text (str p)]
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
  "Convert a net.fortuna.ical4j 4.x VEvent to a boundary EventData map.
   A DTSTART;VALUE=DATE makes it all-day, its dates in `default-tz`; without a
   DTEND the end is DTSTART plus DURATION, or one day for a date (RFC 5545
   3.6.1). A TZID java.time does not know (Outlook's \"W. Europe Standard
   Time\") falls back to `default-tz`; ical4j already resolved the instants."
  [vevent default-tz]
  (let [uid-str   (get-prop-value vevent "UID")
        title     (get-prop-value vevent "SUMMARY")
        start-dt  (prop-date (find-prop vevent "DTSTART"))
        all-day?  (instance? LocalDate start-dt)
        end-dt    (or (prop-date (find-prop vevent "DTEND"))
                      (dt-plus-duration start-dt (find-prop vevent "DURATION"))
                      (when all-day? (.plusDays ^LocalDate start-dt 1)))
        rrule-str (get-prop-value vevent "RRULE")
        tzid      (get-tzid vevent)
        timezone  (if (and tzid (schema/valid-timezone? tzid)) tzid default-tz)
        zone      (ZoneId/of timezone)]
    (cond-> {:id       (uid-string->uuid uid-str)
             :title    (or title "")
             :start    (temporal->instant start-dt zone)
             :end      (temporal->instant end-dt zone)
             :timezone timezone}
      all-day?  (assoc :all-day? true)
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

  (import-ical [_this ical-string opts]
    (let [tz (or (:timezone opts) "UTC")]
      (when-not (schema/valid-timezone? tz)
        (throw (ex-info "Invalid :timezone for iCal import"
                        {:type :validation-error :field :timezone :value tz})))
      (let [calendar (.build (CalendarBuilder.) (StringReader. ical-string))
            vevents  (filter #(instance? VEvent %) (.getComponents calendar))]
        (mapv #(vevent->event % tz) vevents)))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-ical-adapter
  "Create and return a new ICalAdapter instance."
  []
  (->ICalAdapter))
