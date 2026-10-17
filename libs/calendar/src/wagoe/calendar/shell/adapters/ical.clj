(ns wagoe.calendar.shell.adapters.ical
  "ical4j 4.x adapter implementing CalendarAdapterProtocol.

   Converts wagoe EventData maps ↔ RFC 5545 VCALENDAR strings using
   net.fortuna.ical4j 4.x. VEvents are built with ZonedDateTime so that
   TZID parameters are correctly emitted and preserved in round-trips.

   Shell layer — may perform I/O via ical4j CalendarBuilder."
  (:require [wagoe.calendar.core.event :as event]
            [wagoe.calendar.ports :as ports]
            [wagoe.calendar.schema :as schema]
            [clojure.string :as str])
  (:import [java.time DayOfWeek Duration Instant ZoneId ZoneOffset ZonedDateTime OffsetDateTime LocalDate LocalDateTime LocalTime]
           [java.time.temporal ChronoUnit Temporal TemporalAdjusters TemporalAmount]
           [java.time.zone ZoneOffsetTransition ZoneOffsetTransitionRule ZoneRules]
           [java.io StringReader]
           [java.nio.charset StandardCharsets]
           [net.fortuna.ical4j.model Calendar ComponentList PropertyList]
           [net.fortuna.ical4j.model.component Daylight Standard VEvent VTimeZone]
           [net.fortuna.ical4j.model.property Description DtStart RRule TzId TzOffsetFrom TzOffsetTo Uid]
           [net.fortuna.ical4j.data CalendarBuilder]))

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

;; VTIMEZONEs are built from java.time's tz rules, the data the rest of the app
;; uses, rather than ical4j's bundled copy: that lacks newer zones and disagrees
;; with the JVM, and writes each zone's full history.

(defn- observance
  "A STANDARD or DAYLIGHT observance starting at transition `t`."
  [^ZoneRules rules ^ZoneOffsetTransition t rrule]
  (let [props (PropertyList. (cond-> [(DtStart. (.getDateTimeBefore t))
                                      (TzOffsetFrom. (.getOffsetBefore t))
                                      (TzOffsetTo. (.getOffsetAfter t))]
                               rrule (conj (RRule. ^String rrule))))]
    (if (.isDaylightSavings rules (.getInstant t)) (Daylight. props) (Standard. props))))

(defn- base-date
  "The day of the month `r` counts from in `year`; negative counts from the end."
  ^LocalDate [^ZoneOffsetTransitionRule r year]
  (let [d (.getDayOfMonthIndicator r)]
    (if (pos? d)
      (LocalDate/of (int year) (.getMonth r) d)
      (.plusDays (.with (LocalDate/of (int year) (.getMonth r) 1) (TemporalAdjusters/lastDayOfMonth))
                 (inc d)))))

(defn- nominal-date
  "The date `r` names in `year`, before its time of day is applied."
  ^LocalDate [^ZoneOffsetTransitionRule r year]
  (let [base (base-date r year)]
    (if-let [w (.getDayOfWeek r)]
      (.with base (if (pos? (.getDayOfMonthIndicator r))
                    (TemporalAdjusters/nextOrSame w)
                    (TemporalAdjusters/previousOrSame w)))
      base)))

(defn- byday [^DayOfWeek w]
  (subs (str w) 0 2))

(defn- year-days
  "BYYEARDAY values, counted back from the year's end, for the dates `r`'s
   weekday can fall on in `year` — so leap years do not move them."
  [^ZoneOffsetTransitionRule r year shift]
  (let [d     (.getDayOfMonthIndicator r)
        start (.plusDays (base-date r year) shift)
        dates (map #(.plusDays start (if (pos? d) % (- %))) (range 7))]
    (when (every? #(>= (.getMonthValue ^LocalDate %) 3) dates)
      (map #(- (.getDayOfYear ^LocalDate %) (.lengthOfYear ^LocalDate %) 1) dates))))

(defn- transition-rrule
  "RRULE for `r`, whose occurrence `t` falls on a wall-clock date that may be a
   day off the rule's own (a time given in UTC, or 24:00). Dates that span two
   months are counted from the year's end, as Egypt needs: the Friday after the
   last Thursday of October."
  [^ZoneOffsetTransitionRule r ^ZoneOffsetTransition t]
  (let [wall  (.toLocalDate (.getDateTimeBefore t))
        shift (.between ChronoUnit/DAYS (nominal-date r (.getYear wall)) wall)
        d     (.getDayOfMonthIndicator r)
        w     (.getDayOfWeek r)
        days  (map #(+ shift %) (cond (nil? w) [d] (pos? d) (range d (+ d 7)) :else (range d (- d 7) -1)))
        len   (.minLength (.getMonth wall))
        month (str "FREQ=YEARLY;BYMONTH=" (.getValue (.getMonth wall)))]
    (cond
      (not (or (every? #(<= 1 % len) days) (every? #(<= (- len) % -1) days)))
      (if-let [yds (and w (year-days r (.getYear wall) shift))]
        (str "FREQ=YEARLY;BYYEARDAY=" (str/join "," (sort yds)) ";BYDAY=" (byday (.plus w shift)))
        ;; No rule in the tz database reaches this; the zones test covers them all.
        (throw (ex-info "Zone rule has no RRULE form" {:type :internal-error :rule (str r)})))
      (nil? w) (str month ";BYMONTHDAY=" (first days))
      (and (pos? (first days)) (zero? (mod (dec (first days)) 7)))
      (str month ";BYDAY=" (inc (quot (first days) 7)) (byday (.plus w shift)))
      (or (= -1 (first days)) (= len (.maxLength (.getMonth wall)) (last days)))
      (str month ";BYDAY=-1" (byday (.plus w shift)))
      :else (str month ";BYDAY=" (byday (.plus w shift))
                 ";BYMONTHDAY=" (str/join "," (sort days))))))

(defn- rule-observance
  "One RRULE observance for `r`, from its first transition at or after `from`."
  [^ZoneRules rules ^ZoneOffsetTransitionRule r ^Instant from]
  (let [t (->> (iterate inc (dec (.getYear (.atZone from ZoneOffset/UTC))))
               (map #(.createTransition r (int %)))
               (drop-while #(.isBefore (.getInstant ^ZoneOffsetTransition %) from))
               first)]
    (observance rules t (transition-rrule r t))))

(defn- observances
  "Observances covering `lo` onwards: the offset in force at `lo`, the
   transitions after it up to `hi` (nil: no limit), and the zone's recurring
   rules when `hi` reaches them. A fixed offset is one STANDARD."
  [^ZoneRules rules ^Instant lo ^Instant hi]
  (let [in-force   (.previousTransition rules (.plusNanos lo 1))
        from       (if in-force (.getInstant in-force) lo)
        last-hist  (some-> (last (.getTransitions rules)) .getInstant)
        initial    (when-not in-force
                     (let [off (.getOffset rules lo)]
                       [(Standard. (PropertyList. [(DtStart. (LocalDateTime/ofEpochSecond (.getEpochSecond lo) 0 off))
                                                   (TzOffsetFrom. off) (TzOffsetTo. off)]))]))
        explicit   (filter #(let [i (.getInstant ^ZoneOffsetTransition %)]
                              (and (not (.isBefore i from)) (or (nil? hi) (not (.isAfter i hi)))))
                           (.getTransitions rules))
        rules-from (if (or (nil? last-hist) (.isAfter from last-hist)) from (.plusNanos last-hist 1))
        recurring  (when (or (nil? hi) (nil? last-hist) (.isAfter hi last-hist))
                     (map #(rule-observance rules % rules-from) (.getTransitionRules rules)))]
    (concat initial (map #(observance rules % nil) explicit) recurring)))

(defn- vtimezone
  "VTIMEZONE for `tzid`, covering a year either side of `events`. A recurring
   event has no end, so then the zone's current rules are always included."
  [^String tzid events]
  (let [year (Duration/ofDays 366)
        lo   (.minus ^Instant (reduce #(if (.isBefore ^Instant %2 %1) %2 %1) (map :start events)) year)
        hi   (when-not (some :recurrence events)
               (.plus ^Instant (reduce #(if (.isAfter ^Instant %2 %1) %2 %1) (map :end events)) year))]
    (VTimeZone. (PropertyList. [(TzId. tzid)])
                (ComponentList. (vec (observances (.getRules (ZoneId/of tzid)) lo hi))))))

(defn- utc? [tzid]
  (= ZoneOffset/UTC (.normalized (ZoneId/of tzid))))

(defn- vtimezones
  "VTIMEZONE per zone a timed event is written in. UTC needs none: it is
   written in the Z form."
  [events]
  (for [[tzid evs] (group-by #(:timezone % "UTC") (remove event/all-day? events))
        :when (not (utc? tzid))]
    (vtimezone tzid evs)))

(defn- event->vevent
  "Convert a boundary EventData map to a net.fortuna.ical4j 4.x VEvent.

   ZonedDateTimes make ical4j emit DTSTART;TZID=..., Instants the UTC Z form,
   LocalDates (all-day events) DTSTART;VALUE=DATE."
  [event]
  (let [tzid   (:timezone event "UTC")
        zone   (ZoneId/of tzid)
        [s e]  (cond
                 (event/all-day? event) (all-day-dates event zone)
                 (utc? tzid)            [(:start event) (:end event)]
                 :else                  [(.atZone ^Instant (:start event) zone)
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
  "Build a net.fortuna.ical4j 4.x Calendar from VTIMEZONEs and VEvents, the
   zones first.

   In ical4j 4.x Calendar.withProdId accepts a plain String (not a ProdId object)
   and Calendar.withDefaults adds VERSION:2.0 and CALSCALE:GREGORIAN."
  [components ^String product-id]
  (let [base-cal (-> (Calendar.)
                     (.withProdId product-id)
                     (.withDefaults))
        with-components (reduce (fn [^Calendar c component]
                                  (.withComponent c component))
                                base-cal
                                components)]
    (.getFluentTarget with-components)))

(defn- utf8-length [cp]
  (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4))

(defn- fold-line
  "Fold a content line at 75 octets (RFC 5545 3.1), never inside a character.
   ical4j folds at 75 characters, which overruns on multi-byte text."
  [^String line]
  (if (<= (alength (.getBytes line StandardCharsets/UTF_8)) 75)
    line
    (let [sb (StringBuilder. (+ (.length line) 16))]
      (loop [i 0 used 0]
        (if (< i (.length line))
          (let [cp (.codePointAt line i)
                n  (long (utf8-length cp))
                used (if (> (+ used n) 75) (do (.append sb "\r\n ") 1) used)]
            (.appendCodePoint sb cp)
            (recur (+ i (Character/charCount cp)) (+ used n)))
          (str sb))))))

(defn- check-events!
  "UID must be unique within the calendar (RFC 5545 3.8.4.7); clients keep one
   of two events that share it. Compared as the UID string, so a UUID and its
   string form collide. A bad :timezone would otherwise escape as an untyped
   ZoneRulesException."
  [events]
  (when-let [bad (first (remove #(schema/valid-timezone? (:timezone % "UTC")) events))]
    (throw (ex-info "Invalid :timezone for iCal export"
                    {:type :validation-error :field :timezone :value (:timezone bad)})))
  (when-let [dups (seq (for [[id n] (frequencies (map (comp str :id) events)) :when (> n 1)] id))]
    (throw (ex-info "Events to export share an :id"
                    {:type :validation-error :field :id :value (vec dups)}))))

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
    (check-events! events)
    (let [product-id (get opts :product-id "-//Wagoe//Calendar//EN")
          calendar   (build-calendar (concat (vtimezones events) (map event->vevent events))
                                     product-id)]
      ;; Calendar.toString is unfolded; CalendarOutputter folds by characters.
      (str (str/join "\r\n" (map fold-line (str/split-lines (str calendar)))) "\r\n")))

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
