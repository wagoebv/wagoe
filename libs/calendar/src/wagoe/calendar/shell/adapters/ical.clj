(ns wagoe.calendar.shell.adapters.ical
  "ical4j 4.x adapter implementing CalendarAdapterProtocol.

   Converts wagoe EventData maps ↔ RFC 5545 VCALENDAR strings using
   net.fortuna.ical4j 4.x. Zones are read and written with java.time's rules,
   not ical4j's.

   Shell layer — may perform I/O via ical4j."
  (:require [wagoe.calendar.core.event :as event]
            [wagoe.calendar.core.recurrence :as recurrence]
            [wagoe.calendar.ports :as ports]
            [wagoe.calendar.schema :as schema]
            [clojure.string :as str])
  (:import [java.time DateTimeException DayOfWeek Duration Instant YearMonth ZoneId ZoneOffset OffsetDateTime LocalDate LocalDateTime LocalTime Period]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit Temporal TemporalAdjusters]
           [java.time.zone ZoneOffsetTransition ZoneOffsetTransitionRule ZoneRules]
           [java.io StringReader]
           [java.nio.charset StandardCharsets]
           [net.fortuna.ical4j.model Calendar ComponentList ParameterList PropertyList Recur TemporalAmountAdapter]
           [net.fortuna.ical4j.model.component Daylight Standard VEvent VTimeZone]
           [net.fortuna.ical4j.model.property Description DtEnd DtStamp DtStart RRule Summary TzId TzOffsetFrom TzOffsetTo Uid]
           [net.fortuna.ical4j.transform.recurrence Frequency]
           [net.fortuna.ical4j.util Strings]
           [net.fortuna.ical4j.data CalendarParser CalendarParserFactory ContentHandler ParserException UnfoldingReader]))

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

(defn- observance-props
  ^PropertyList [^LocalDateTime start ^ZoneOffset from ^ZoneOffset to rrule]
  (PropertyList. (cond-> [(DtStart. start) (TzOffsetFrom. from) (TzOffsetTo. to)]
                   rrule (conj (RRule. ^String rrule)))))

(defn- observance
  "A STANDARD or DAYLIGHT observance starting at transition `t`."
  [^ZoneRules rules ^ZoneOffsetTransition t rrule]
  (let [props (observance-props (.getDateTimeBefore t) (.getOffsetBefore t) (.getOffsetAfter t) rrule)]
    (if (.isDaylightSavings rules (.getInstant t)) (Daylight. props) (Standard. props))))

(defn- base-date
  "The day of the month `r` counts from in `year`; negative counts from the end."
  ^LocalDate [^ZoneOffsetTransitionRule r year]
  (let [d (.getDayOfMonthIndicator r)]
    (if (pos? d)
      (LocalDate/of (int year) (.getMonth r) d)
      (.plusDays (.atEndOfMonth (YearMonth/of (int year) (.getMonth r))) (inc d)))))

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
   last Thursday of October. Nil for a shape no RRULE can say."
  [^ZoneOffsetTransitionRule r ^ZoneOffsetTransition t]
  (let [wall  (.toLocalDate (.getDateTimeBefore t))
        shift (.between ChronoUnit/DAYS (nominal-date r (.getYear wall)) wall)
        d     (.getDayOfMonthIndicator r)
        w     (.getDayOfWeek r)
        days  (map #(+ shift %) (cond (nil? w) [d] (pos? d) (range d (+ d 7)) :else (range d (- d 7) -1)))
        d0    (first days)
        wd    (some-> w (.plus shift) byday)
        len   (.minLength (.getMonth wall))
        month (str "FREQ=YEARLY;BYMONTH=" (.getValue (.getMonth wall)))]
    (cond
      (not (or (every? #(<= 1 % len) days) (every? #(<= (- len) % -1) days)))
      (when-let [yds (and w (year-days r (.getYear wall) shift))]
        (str "FREQ=YEARLY;BYYEARDAY=" (str/join "," (sort yds)) ";BYDAY=" wd))
      (nil? w)                                    (str month ";BYMONTHDAY=" d0)
      (and (pos? d0) (zero? (mod (dec d0) 7)))    (str month ";BYDAY=" (inc (quot d0 7)) wd)
      (or (= -1 d0) (= len (.maxLength (.getMonth wall)) (last days)))
      (str month ";BYDAY=-1" wd)
      :else (str month ";BYDAY=" wd ";BYMONTHDAY=" (str/join "," (sort days))))))

(def ^:private rule-horizon
  "How far a rule with no RRULE form is written out, transition by transition.
   No rule in today's tz database needs it; a future one would."
  (Instant/parse "2100-01-01T00:00:00Z"))

(defn- rule-observances
  "One RRULE observance for `r`, from its first transition at or after `from`;
   or, if it has no RRULE form, its transitions up to `hi` or `rule-horizon`."
  [^ZoneRules rules ^ZoneOffsetTransitionRule r ^Instant from ^Instant hi]
  (let [ts (->> (iterate inc (dec (.getYear (.atZone from ZoneOffset/UTC))))
                (map #(.createTransition r (int %)))
                (drop-while #(.isBefore (.getInstant ^ZoneOffsetTransition %) from)))
        until (if (.isBefore hi rule-horizon) hi rule-horizon)]
    (if-let [rrule (transition-rrule r (first ts))]
      [(observance rules (first ts) rrule)]
      (map #(observance rules % nil)
           (take-while #(not (.isAfter (.getInstant ^ZoneOffsetTransition %) until)) ts)))))

(defn- observances
  "Observances covering `lo` onwards: the offset in force at `lo`, the
   transitions after it up to `hi`, and the zone's recurring rules when `hi`
   reaches them. A fixed offset is one STANDARD."
  [^ZoneRules rules ^Instant lo ^Instant hi]
  (let [history    (.getTransitions rules)
        in-force   (.previousTransition rules (.plusNanos lo 1))
        from       (if in-force (.getInstant in-force) lo)
        last-hist  (if (seq history) (.getInstant ^ZoneOffsetTransition (last history)) Instant/MIN)
        initial    (when-not in-force
                     (let [off (.getOffset rules lo)]
                       [(Standard. (observance-props (LocalDateTime/ofEpochSecond (.getEpochSecond lo) 0 off)
                                                     off off nil))]))
        explicit   (filter #(let [i (.getInstant ^ZoneOffsetTransition %)]
                              (not (or (.isBefore i from) (.isAfter i hi))))
                           history)
        rules-from (if (.isAfter from last-hist) from (.plusNanos last-hist 1))
        recurring  (when (.isAfter hi last-hist)
                     (mapcat #(rule-observances rules % rules-from hi) (.getTransitionRules rules)))]
    (concat initial (map #(observance rules % nil) explicit) recurring)))

(defn- vtimezone
  "VTIMEZONE for `tzid`, covering a year either side of `events`. A recurring
   event has no end, so then the zone's current rules are always included."
  [^String tzid events]
  (let [year (Duration/ofDays 366)
        lo   (.minus ^Instant (first (sort (map :start events))) year)
        hi   (if (some recurrence/recurring? events)
               Instant/MAX
               (.plus ^Instant (last (sort (map :end events))) year))]
    (VTimeZone. (PropertyList. [(TzId. tzid)])
                (ComponentList. (vec (observances (.getRules (ZoneId/of tzid)) lo hi))))))

(defn- utc? [^ZoneId zone]
  (= ZoneOffset/UTC (.normalized zone)))

(defn- vtimezones
  "VTIMEZONE per zone a timed event is written in. UTC needs none: it is
   written in the Z form."
  [events]
  (for [[tzid evs] (group-by #(:timezone % "UTC") (remove event/all-day? events))
        :when (not (utc? (ZoneId/of tzid)))]
    (vtimezone tzid evs)))

(defn- event->vevent
  "Convert a boundary EventData map to a net.fortuna.ical4j 4.x VEvent.

   Instants are written in the UTC Z form, LocalDates (all-day events) as
   DTSTART;VALUE=DATE, other times as local times with a TZID. Not from a
   ZonedDateTime: ical4j writes that in its own copy of the zone, whose US
   rules start DST a week early in some years."
  [event]
  (let [tzid   (:timezone event "UTC")
        zone   (ZoneId/of tzid)
        title  ^String (:title event)
        base   (cond
                 (event/all-day? event) (let [[s e] (all-day-dates event zone)] (VEvent. ^Temporal s ^Temporal e title))
                 (utc? zone)            (VEvent. ^Temporal (:start event) ^Temporal (:end event) title)
                 :else                  (let [tz (ParameterList. [(net.fortuna.ical4j.model.parameter.TzId. tzid)])
                                              at #(LocalDateTime/ofInstant % zone)]
                                          (VEvent. (PropertyList. [(DtStamp. (Instant/now))
                                                                   (DtStart. tz ^Temporal (at (:start event)))
                                                                   (DtEnd. tz ^Temporal (at (:end event)))
                                                                   (Summary. title)]))))
        vevent (.withProperty ^VEvent base (Uid. (str (:id event))))
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

;; Import reads the text through ical4j's parser only, not CalendarBuilder:
;; that resolves every TZID through ical4j's own zones, built by its
;; ZoneRulesBuilder, which reads BYDAY=2SU as the Sunday on or after the 2nd
;; (US DST a week early in some years), and binds each parsed VTIMEZONE to a
;; JVM-wide ical4j~<uuid> ZoneId.

(defn- parse-components
  "The components of `ical`, as {:name :props :children}, each property
   {:name :params :value} as written. Names are upper-cased."
  [^String ical]
  (let [stack (volatile! [{:props [] :children []}])
        prop  (volatile! nil)
        h     (reify ContentHandler
                (startCalendar [_])
                (endCalendar [_])
                (startComponent [_ n]
                  (vswap! stack conj {:name (str/upper-case n) :props [] :children []}))
                (endComponent [_ _]
                  (vswap! stack (fn [s] (let [c (peek s) s (pop s)]
                                          (conj (pop s) (update (peek s) :children conj c))))))
                (startProperty [_ n] (vreset! prop {:name (str/upper-case n) :params {}}))
                (parameter [_ n v] (vswap! prop assoc-in [:params (str/upper-case n)] (Strings/unquote v)))
                (propertyValue [_ v] (vswap! prop assoc :value v))
                (endProperty [_ _]
                  (vswap! stack (fn [s] (conj (pop s) (update (peek s) :props conj @prop))))))]
    (.parse ^CalendarParser (.get (CalendarParserFactory/getInstance))
            (UnfoldingReader. (StringReader. ical)) h)
    (:children (peek @stack))))

(defn- props [c ^String prop-name]
  (filter #(= prop-name (:name %)) (:props c)))

(defn- prop [c prop-name]
  (first (props c prop-name)))

(def ^:private date-time-format (DateTimeFormatter/ofPattern "uuuuMMdd'T'HHmmss"))

(defn- read-date
  "LocalDate for a DATE, OffsetDateTime for a UTC DATE-TIME, else LocalDateTime."
  [{:keys [^String value params]}]
  (let [value (str/trim value)]
    (cond
      (or (= "DATE" (get params "VALUE")) (= 8 (count value)))
      (LocalDate/parse value DateTimeFormatter/BASIC_ISO_DATE)

      (str/ends-with? value "Z")
      (.atOffset (LocalDateTime/parse (subs value 0 (dec (count value))) date-time-format) ZoneOffset/UTC)

      :else (LocalDateTime/parse value date-time-format))))

(def ^:private windows-zones
  "Windows zone names, as Outlook and Exchange write TZIDs, to IANA zones
   (CLDR windowsZones.xml, territory 001). The JDK carries no such table."
  {"Hawaiian Standard Time"         "Pacific/Honolulu"
   "Alaskan Standard Time"          "America/Anchorage"
   "Pacific Standard Time"          "America/Los_Angeles"
   "US Mountain Standard Time"      "America/Phoenix"
   "Mountain Standard Time"         "America/Denver"
   "Central America Standard Time"  "America/Guatemala"
   "Central Standard Time"          "America/Chicago"
   "Central Standard Time (Mexico)" "America/Mexico_City"
   "Canada Central Standard Time"   "America/Regina"
   "SA Pacific Standard Time"       "America/Bogota"
   "Eastern Standard Time"          "America/New_York"
   "US Eastern Standard Time"       "America/Indiana/Indianapolis"
   "Atlantic Standard Time"         "America/Halifax"
   "Newfoundland Standard Time"     "America/St_Johns"
   "E. South America Standard Time" "America/Sao_Paulo"
   "Argentina Standard Time"        "America/Argentina/Buenos_Aires"
   "SA Western Standard Time"       "America/La_Paz"
   "Pacific SA Standard Time"       "America/Santiago"
   "GMT Standard Time"              "Europe/London"
   "Greenwich Standard Time"        "Atlantic/Reykjavik"
   "W. Europe Standard Time"        "Europe/Berlin"
   "Central Europe Standard Time"   "Europe/Budapest"
   "Romance Standard Time"          "Europe/Paris"
   "Central European Standard Time" "Europe/Warsaw"
   "W. Central Africa Standard Time" "Africa/Lagos"
   "GTB Standard Time"              "Europe/Bucharest"
   "E. Europe Standard Time"        "Europe/Chisinau"
   "FLE Standard Time"              "Europe/Kiev"
   "Israel Standard Time"           "Asia/Jerusalem"
   "Egypt Standard Time"            "Africa/Cairo"
   "South Africa Standard Time"     "Africa/Johannesburg"
   "Turkey Standard Time"           "Europe/Istanbul"
   "Russian Standard Time"          "Europe/Moscow"
   "Arab Standard Time"             "Asia/Riyadh"
   "Arabian Standard Time"          "Asia/Dubai"
   "Iran Standard Time"             "Asia/Tehran"
   "Pakistan Standard Time"         "Asia/Karachi"
   "India Standard Time"            "Asia/Kolkata"
   "Nepal Standard Time"            "Asia/Kathmandu"
   "Bangladesh Standard Time"       "Asia/Dhaka"
   "SE Asia Standard Time"          "Asia/Bangkok"
   "China Standard Time"            "Asia/Shanghai"
   "Singapore Standard Time"        "Asia/Singapore"
   "Taipei Standard Time"           "Asia/Taipei"
   "Tokyo Standard Time"            "Asia/Tokyo"
   "Korea Standard Time"            "Asia/Seoul"
   "W. Australia Standard Time"     "Australia/Perth"
   "Cen. Australia Standard Time"   "Australia/Adelaide"
   "AUS Central Standard Time"      "Australia/Darwin"
   "E. Australia Standard Time"     "Australia/Brisbane"
   "AUS Eastern Standard Time"      "Australia/Sydney"
   "Tasmania Standard Time"         "Australia/Hobart"
   "New Zealand Standard Time"      "Pacific/Auckland"})

(defn- known-zone
  "The IANA zone a TZID names, directly or as a Windows zone name."
  [tzid]
  (let [id (get windows-zones tzid tzid)]
    (when (schema/valid-timezone? id) id)))

(defn- yearly-recur
  "An observance's RRULE. Only YEARLY: anything finer, expanded from 1601,
   would run for minutes."
  ^Recur [^String rrule]
  (let [r (Recur. rrule)]
    (when-not (= Frequency/YEARLY (.getFrequency r))
      (throw (ex-info "VTIMEZONE RRULE must be YEARLY" {:type :validation-error :field :rrule :value rrule})))
    r))

(defn- observance-onsets
  "[instant from to] per onset of observance `o` before `until`. RRULEs are
   expanded by ical4j's Recur, which reads BYDAY=2SU as RFC 5545 does."
  [o ^LocalDateTime until]
  (let [^LocalDateTime start (read-date (prop o "DTSTART"))
        _     (when-not (instance? LocalDateTime start)
                (throw (ex-info "VTIMEZONE observance DTSTART must be a local time"
                                {:type :validation-error :field :dtstart})))
        from  (ZoneOffset/of (str/trim (str (:value (prop o "TZOFFSETFROM")))))
        to    (ZoneOffset/of (str/trim (str (:value (prop o "TZOFFSETTO")))))
        dates (concat [start]
                      (mapcat #(.getDates (yearly-recur (:value %)) start start until) (props o "RRULE"))
                      (for [p (props o "RDATE") v (str/split (:value p) #",")] (read-date (assoc p :value v))))]
    (for [^LocalDateTime d (distinct dates) :when (.isBefore d until)]
      [(.toInstant d from) from to])))

(defn- vtimezone-rules
  "ZoneRules for a VTIMEZONE's observances, their onsets up to the end of the
   year after `year`, the feed's last."
  ^ZoneRules [observances year]
  (let [until  (LocalDateTime/of (int (+ year 2)) 1 1 0 0)
        onsets (sort-by first (mapcat #(observance-onsets % until) observances))
        base   (or (second (first onsets)) ZoneOffset/UTC)
        ts     (second (reduce (fn [[cur acc] [^Instant i _ to]]
                                 (if (= to cur)
                                   [cur acc]
                                   [to (conj acc (ZoneOffsetTransition/of (LocalDateTime/ofInstant i cur) cur to))]))
                               [base []]
                               onsets))]
    (ZoneRules/of base base [] ts [])))

(defn- rules-instant
  "`l` in `rules` as ZonedDateTime/ofLocal reads it: in an overlap the earlier
   offset, in a gap the offset before it."
  ^Instant [^ZoneRules rules ^LocalDateTime l]
  (let [offsets (.getValidOffsets rules l)]
    (.toInstant l ^ZoneOffset (if (seq offsets) (first offsets) (.getOffsetBefore (.getTransition rules l))))))

(defn- zone-resolver
  "Reads a TZID: {:timezone iana-id-or-nil, :at (fn [LocalDateTime] Instant)}.
   An IANA or Windows name uses java.time's rules, whatever the feed's
   VTIMEZONE says; another name its VTIMEZONE; without one, `default-zone`."
  [vtimezones last-year ^ZoneId default-zone tzid]
  (if-let [id (some-> tzid known-zone)]
    (let [zone (ZoneId/of id)]
      {:timezone id :at #(.toInstant (.atZone ^LocalDateTime % zone))})
    (if-let [obs (seq (get vtimezones tzid))]
      (let [rules (vtimezone-rules obs last-year)]
        {:at #(rules-instant rules %)})
      {:at #(.toInstant (.atZone ^LocalDateTime % default-zone))})))

(defn- value->instant
  "Instant of a read DTSTART/DTEND value: dates start at midnight in `zone`,
   local times are read by `at`."
  [v at ^ZoneId zone]
  (cond
    (instance? OffsetDateTime v) (.toInstant ^OffsetDateTime v)
    (instance? LocalDateTime v)  (at v)
    (instance? LocalDate v)      (.toInstant (.atStartOfDay ^LocalDate v zone))))

(defn- plus-duration
  "Instant `start` plus a DURATION: days are calendar days, as for a
   ZonedDateTime. Nil when they do not combine (a time duration on a date)."
  [start at ^ZoneId zone duration-prop]
  (let [amount (.getDuration (TemporalAmountAdapter/parse (str/trim (:value duration-prop))))]
    (cond
      (instance? LocalDate start)
      (when (instance? Period amount) (value->instant (.plus ^LocalDate start amount) at zone))
      (instance? Duration amount) (.plus ^Instant (value->instant start at zone) ^Duration amount)
      (instance? LocalDateTime start) (at (.plus ^LocalDateTime start ^Period amount))
      :else (.toInstant (.plus ^OffsetDateTime start ^Period amount)))))

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
      (java.util.UUID/nameUUIDFromBytes (.getBytes ^String uid-str StandardCharsets/UTF_8)))))

(defn- vevent->event
  "EventData for a parsed VEVENT. A DTSTART;VALUE=DATE makes it all-day;
   without a DTEND the end is DTSTART plus DURATION, or one day for a date
   (RFC 5545 3.6.1). `:timezone` is DTSTART's zone when java.time knows it,
   else `default-tz`."
  [vevent resolve default-tz]
  (let [uid       (or (some-> (prop vevent "UID") :value str/trim not-empty)
                      (throw (ex-info "VEVENT without a UID" {:type :validation-error :field :uid})))
        start-p   (prop vevent "DTSTART")
        start     (some-> start-p read-date)
        resolve   #(resolve (get-in % [:params "TZID"]))
        {:keys [timezone at]} (resolve start-p)
        timezone  (or timezone default-tz)
        zone      (ZoneId/of timezone)
        all-day?  (instance? LocalDate start)
        end-p     (prop vevent "DTEND")
        dur-p     (prop vevent "DURATION")
        end       (cond
                    end-p (value->instant (read-date end-p) (:at (resolve end-p)) zone)
                    (and start dur-p) (plus-duration start at zone dur-p)
                    all-day? (value->instant (.plusDays ^LocalDate start 1) at zone))
        rrule     (some-> (prop vevent "RRULE") :value str/trim)]
    (some-> rrule Recur.)                 ; refuses a malformed RRULE
    (cond-> {:id       (uid-string->uuid uid)
             :title    (or (some-> (prop vevent "SUMMARY") :value Strings/unescape) "")
             :start    (some-> start (value->instant at zone))
             :end      end
             :timezone timezone}
      all-day? (assoc :all-day? true)
      rrule    (assoc :recurrence rrule))))

(defn- unreadable [^Exception e]
  (throw (ex-info (str "Unreadable iCal: " (.getMessage e)) {:type :validation-error} e)))

(defn- read-calendar
  "EventData for each VEVENT in `ical`, local times read by `zone-resolver`."
  [ical default-tz]
  (let [components (parse-components ical)
        vtimezones (into {} (for [c components :when (= "VTIMEZONE" (:name c))]
                              [(some-> (prop c "TZID") :value str/trim) (:children c)]))
        vevents    (filter #(= "VEVENT" (:name %)) components)
        last-year  (transduce (comp (mapcat #(keep (partial prop %) ["DTSTART" "DTEND"]))
                                    (keep #(some->> (:value %) str/trim (re-find #"^\d{4}") parse-long)))
                              max 1970 vevents)
        resolve    (memoize #(zone-resolver vtimezones last-year (ZoneId/of default-tz) %))]
    (mapv #(vevent->event % resolve default-tz) vevents)))

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
      (try
        (read-calendar ical-string tz)
        (catch ParserException e (unreadable e))
        (catch DateTimeException e (unreadable e))
        (catch IllegalArgumentException e (unreadable e))))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-ical-adapter
  "Create and return a new ICalAdapter instance."
  []
  (->ICalAdapter))
