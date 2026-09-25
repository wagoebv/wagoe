(ns wagoe.admin.core.ui.base
  "Shared admin UI primitives used across multiple ui.* sections.

   Leaf namespace: URL helpers, field-value rendering, list-column width
   heuristics, and small formatting/label utilities. Must NOT require any
   other wagoe.admin.core.ui.* implementation namespace — it is the
   dependency root that the focused sections build on."
  (:require [wagoe.core.utils.type-conversion :as tc]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.table :as table-ui]
            [clojure.string :as str])
  (:import (java.time DateTimeException Instant LocalDate LocalDateTime ZoneId ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.util Locale)))

;; =============================================================================
;; URL Helpers
;; =============================================================================

(defn url-encode
  "URL-encode a string for use as a query-string value.

   Required when threading `return_to` (or any contextual URL containing
   its own `?`/`&` characters) through query parameters — otherwise the
   embedded `&` splits into a second top-level parameter and the receiving
   handler sees a truncated value."
  [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn entity-create-url
  "Resolve the URL used for the 'New' button on an entity.

   Entities may expose a dedicated create flow via `:create-redirect-url`
   (e.g. split-table entities that cannot be created via the generic admin
   CRUD path). Falls back to `/web/admin/<entity>/new` when no override is
   configured.

   When `caller-url` is provided AND the entity delegates via
   `:create-redirect-url`, the caller URL is threaded through as a
   `return-to` query parameter so the delegated flow (e.g. the user
   module's `/web/users/new` page) can bring the admin back to their
   current filtered/paginated list view on cancel or success. The value
   is URL-encoded to survive embedded `&`/`=` characters from filters
   and pagination.

   `caller-url` is ignored for the non-delegated path because the generic
   admin create handler re-renders the default list view directly and
   does not honor `return-to`."
  ([entity-name entity-config]
   (entity-create-url entity-name entity-config nil))
  ([entity-name entity-config caller-url]
   (let [redirect-url (:create-redirect-url entity-config)
         base (or redirect-url (str "/web/admin/" (name entity-name) "/new"))]
     (if (and redirect-url caller-url)
       (let [separator (if (str/includes? base "?") "&" "?")]
         (str base separator "return-to=" (url-encode caller-url)))
       base))))

(defn current-list-url
  "Build the current admin list URL with filters/pagination applied.

   Used to seed `return-to` on links that navigate away from a list view
   (notably the delegated create flow), so the user lands back on the
   same filtered/paginated page after cancel or success. Returns the
   plain `/web/admin/<entity>` when no meaningful query params exist."
  [entity-name table-query filters]
  (let [base (str "/web/admin/" (name entity-name))
        ;; Drop empty values so the default list view produces the bare
        ;; `/web/admin/<entity>` rather than `?page=&page-size=`.
        ;; (`encode-query-params` already strips nils, so we only need to
        ;; filter empty strings here.)
        params (into {}
                     (remove (fn [[_ v]] (= "" v)))
                     (merge (table-ui/table-query->params table-query)
                            (table-ui/search-filters->params filters)))
        qs (table-ui/encode-query-params params)]
    (if (str/blank? qs)
      base
      (str base "?" qs))))

;; =============================================================================
;; Field Value Rendering
;; =============================================================================

;; The list table showed whatever the database handed back — for a timestamp
;; column that is `2026-08-27T06:12:50.459979Z`, microseconds and all (BOU-382).
;; Patterns are overridable per render because the application configures them;
;; UTC is the fallback zone rather than the machine's, which core may not read.
(def default-instant-format "yyyy-MM-dd HH:mm")
(def default-date-format "yyyy-MM-dd")

(def ^:private utc (ZoneId/of "UTC"))

;; `DateTimeFormatter` is immutable, so the fallbacks are built once at load.
;; A configured pattern is compiled per render rather than cached: a cache here
;; would be process-lifetime mutable state in core, keyed on caller-supplied
;; data, and parsing a pattern costs microseconds against a page of cells.
(def ^:private default-instant-formatter (DateTimeFormatter/ofPattern default-instant-format))
(def ^:private default-date-formatter (DateTimeFormatter/ofPattern default-date-format))

(defn- display-zone
  ^ZoneId [display]
  (or (:zone-id display) utc))

(defn- server-zone
  "The zone the database reads a zone-less timestamp in: the JVM zone, which is
   also the PostgreSQL session zone and H2's. Supplied by the shell — core may
   not read the clock's zone."
  ^ZoneId [display]
  (or (:server-zone-id display) (display-zone display)))

(defn- with-locale
  ^DateTimeFormatter [^DateTimeFormatter fmt ^Locale locale]
  (if locale (.withLocale fmt locale) fmt))

(defn- compile-formatter
  "An unknown pattern letter yields nil rather than an IllegalArgumentException
   — core must not throw, and a typo in `config.edn` should cost a column its
   formatting rather than 500 the whole list page. A pattern that is not a
   string is not a pattern."
  ^DateTimeFormatter [pattern ^Locale locale]
  (when (string? pattern)
    (try
      (with-locale (DateTimeFormatter/ofPattern pattern) locale)
      (catch IllegalArgumentException _ nil))))

(defn- formatters
  "The formatters to try for `pattern-key`, the configured one first. The
   default is kept as a second chance rather than only as a compile-time
   fallback: a pattern can be valid and still be unable to format the value it
   is handed (a zone pattern against a zone-less timestamp), and dropping
   straight to the raw database value there is the bug BOU-382 fixes."
  [display pattern-key ^DateTimeFormatter default]
  (let [locale (:locale display)]
    (remove nil? [(compile-formatter (get display pattern-key) locale)
                  (with-locale default locale)])))

(defn- safe-format
  "Format `temporal`, or nil when it is missing, when the pattern asks it for a
   field it does not have (a zone pattern against a zone-less value), or when it
   produces nothing at all.

   Blank output counts as failure: `\"\"` and an optional-only pattern such as
   `[HH:mm]` against a date format successfully and return an empty string, and
   an empty cell hides the value rather than misformatting it."
  [^DateTimeFormatter fmt temporal]
  (when (and fmt temporal)
    (let [formatted (try
                      (.format fmt temporal)
                      (catch DateTimeException _ nil))]
      (when-not (str/blank? formatted)
        formatted))))

(defn- ->zoned
  "Coerce a stored timestamp that carries an instant into `zone`."
  ^ZonedDateTime [value ^ZoneId zone]
  (when-let [^Instant inst (tc/string->instant value)]
    (.atZone inst zone)))

(defn- ->naive
  "Coerce a zone-less stored timestamp: the LocalDateTime a driver reading
   TIMESTAMP columns as local hands back, the java.sql.Timestamp most drivers
   return for the same column, or the `2026-08-27 06:12:50` string SQLite keeps
   in a TEXT column. Nothing is shifted — a value with no zone is reformatted
   where it stands rather than moved into one.

   java.sql.Timestamp belongs here, not with the instants. A driver builds it
   from the stored wall time in the JVM zone, and `.toLocalDateTime` is the
   exact inverse. Read as an instant it was shifted by the JVM's offset: on an
   Amsterdam server a stored 12:00 reached the edit form as 10:00, and saving
   any other field wrote 10:00 back. The form submits a zone-less string, which
   the database reads as wall time — so wall time is what has to be rendered."
  ^LocalDateTime [value]
  (cond
    (instance? LocalDateTime value) value
    (instance? java.sql.Timestamp value) (.toLocalDateTime ^java.sql.Timestamp value)

    (string? value)
    (try
      (LocalDateTime/parse (str/replace-first value " " "T"))
      (catch DateTimeParseException _ nil))))

(defn- ->instant
  "Coerce any stored timestamp to the Instant it denotes. Values that carry a
   zone or offset (Instant, OffsetDateTime, `…Z`/`+02:00` strings) and
   java.sql.Timestamp (built by the driver in the JVM zone) convert directly;
   a zone-less value is read in `server-zone`, the zone the database itself
   reads it in (BOU-523)."
  ^Instant [value ^ZoneId server-zone]
  (or (when-not (instance? java.sql.Timestamp value)
        (when-let [ldt (when (or (instance? LocalDateTime value) (string? value))
                         (->naive value))]
          (.toInstant (.atZone ^LocalDateTime ldt server-zone))))
      (tc/string->instant value)))

(defn- ->local-date
  "Coerce a stored date to a LocalDate. A date carries no zone, so a timestamp
   shape is read at UTC: reading `2026-01-09T00:00Z` in a zone west of
   Greenwich would move it to the 8th."
  ^LocalDate [value]
  (cond
    (instance? LocalDate value)     value
    (instance? LocalDateTime value) (.toLocalDate ^LocalDateTime value)
    (instance? java.sql.Date value) (.toLocalDate ^java.sql.Date value)

    :else
    (or (when (string? value)
          (try
            (LocalDate/parse value)
            (catch DateTimeParseException _ nil)))
        (some-> (->naive value) (.toLocalDate))
        (some-> (->zoned value utc) (.toLocalDate)))))

(defn format-instant
  "Render a stored timestamp for display, in `display`'s zone and pattern.

   The value is whatever JDBC produced — `list-entities` does no read-side
   coercion, so it is a String, java.sql.Timestamp, OffsetDateTime or
   LocalDateTime depending on the driver. Anything unparseable is passed
   through as-is rather than swallowed, so a format nobody anticipated stays
   visible."
  [value display]
  (let [temporal (some-> (->instant value (server-zone display))
                         (.atZone (display-zone display)))]
    (or (some #(safe-format % temporal)
              (formatters display :date-time-format default-instant-formatter))
        (str value))))

(defn format-date
  "Render a stored date for display. Same passthrough rule as `format-instant`."
  [value display]
  (let [temporal (->local-date value)]
    (or (some #(safe-format % temporal)
              (formatters display :date-format default-date-formatter))
        (str value))))

;; -----------------------------------------------------------------------------
;; Form-widget values
;;
;; `<input type="date">` and `<input type="datetime-local">` accept exactly one
;; shape each and *silently discard* anything else — the field then renders
;; empty, which reads as "the record failed to load" rather than as a format
;; problem. The stored value is whatever JDBC produced, so it has to be coerced
;; to the widget's shape rather than passed through (BOU-504).
;; -----------------------------------------------------------------------------

(def ^:private html-date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(def ^:private html-datetime-minute-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm"))
(def ^:private html-datetime-second-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
(def ^:private html-datetime-milli-formatter  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS"))

(defn format-for-date-input
  "Coerce a stored value to the `YYYY-MM-DD` an `<input type=\"date\">` accepts.

   Returns nil when the value cannot be read as a date, so the caller can leave
   the input empty rather than feed it something the browser will drop."
  [value]
  (some->> (->local-date value) (safe-format html-date-formatter)))

(defn form-zones
  "The zones a form renders and parses :instant fields in, from `display`:
   `:input-zone` (the client's, else the configured, else the server's — chosen
   by the shell) and `:server-zone`. Both come from the same map, so a form can
   never show in one zone and save in another (BOU-523)."
  [display]
  {:input-zone  (display-zone display)
   :server-zone (server-zone display)})

(defn- ->offset
  "A ZoneOffset from `s` (`-05:00`, `Z`), or nil. It arrives from a form field,
   so anything unreadable is ignored rather than trusted."
  ^ZoneOffset [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (ZoneOffset/of (str/trim s)) (catch DateTimeException _ nil))))

(defn datetime-input-offset
  "The UTC offset `value` has when shown in `input-zone`, e.g. `-05:00`, or nil.
   The form sends it back beside the wall time so `parse-datetime-input` can
   tell the two occurrences of a repeated local time apart."
  [value ^ZoneId input-zone ^ZoneId server-zone]
  (some-> (->instant value server-zone) (.atZone input-zone) (.getOffset) (str)))

(defn format-for-datetime-input
  "Coerce a stored timestamp to what an `<input type=\"datetime-local\">`
   accepts, as wall time in `input-zone`, at the precision the value has:
   `YYYY-MM-DDTHH:mm` on the minute, `…:ss` with seconds, `…:ss.SSS` with
   milliseconds.

   The value is read as the instant it denotes (see `->instant`) and shown in
   `input-zone` — the zone the form is also parsed back in, which is what makes
   an edit to another field leave it unchanged (BOU-519, BOU-523). Seconds are
   kept because the form submits every editable field; finer than
   milliseconds cannot survive the widget. Pair with `datetime-input-step`."
  [value ^ZoneId input-zone ^ZoneId server-zone]
  (when-let [^LocalDateTime ldt (some-> (->instant value server-zone)
                                        (.atZone input-zone)
                                        (.toLocalDateTime))]
    (safe-format (cond
                   (pos? (quot (.getNano ldt) 1000000)) html-datetime-milli-formatter
                   (pos? (.getSecond ldt))              html-datetime-second-formatter
                   :else                                html-datetime-minute-formatter)
                 ldt)))

(def ^:private storage-formatter
  (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss.SSSXXX"))

(defn parse-datetime-input
  "Read what a datetime-local submitted — wall time in `input-zone` — and return
   the instant as an ISO string carrying `server-zone`'s offset, or nil when it
   is not a date-time.

   The offset is the server's, not UTC's, on purpose. Measured: PostgreSQL
   drops the zone of a value written into a TIMESTAMP column without one and
   keeps the wall time, so `…10:00:50Z` read back as 08:00:50Z on an Amsterdam
   server. With the server's offset the wall time is the server's own, which
   is right for a zone-less column; zone-aware columns and SQLite read the
   offset and get the exact instant either way (BOU-523)."
  ([s input-zone server-zone] (parse-datetime-input s input-zone server-zone nil))
  ([s ^ZoneId input-zone ^ZoneId server-zone preferred-offset]
  (when (string? s)
    ;; A value that already names its zone or offset — an API client sending
    ;; `…Z` — is taken as exactly that instant; only a zone-less value, which
    ;; is what the widget sends, is read in `input-zone`.
    ;;
    ;; When the clocks go back a local time happens twice, and `atZone` picks
    ;; the earlier one: `2026-10-25T02:30` in Amsterdam, rendered from 01:30Z,
    ;; was saved as 00:30Z by a form nobody changed. `preferred-offset` is the
    ;; offset the value was rendered with, carried back by the form; ofLocal
    ;; uses it only to choose within such an overlap.
    (when-let [^Instant inst (or (tc/string->instant s)
                                 (some-> ^LocalDateTime (->naive s)
                                         (ZonedDateTime/ofLocal input-zone (->offset preferred-offset))
                                         (.toInstant)))]
      (.format ^DateTimeFormatter storage-formatter
               (.toOffsetDateTime (.atZone inst server-zone)))))))

(defn datetime-input-step
  "The `step` a datetime-local needs for `formatted`, the output of
   `format-for-datetime-input`: nil on the minute, \"1\" with seconds,
   \"0.001\" with milliseconds.

   The default step is 60 seconds. A value with seconds is then a step
   mismatch and the browser refuses to submit the form — keeping the seconds
   without this would turn silent truncation into a form that cannot be saved."
  [formatted]
  (case (count formatted)
    19 "1"
    23 "0.001"
    nil))

(defn render-field-value
  "Render field value for display in table or detail view.

   Args:
     field-name: Keyword field name
     value: Field value to render
     field-config: Field configuration map
     display: Optional {:zone-id :date-time-format :date-format} from the shell.
              Absent, timestamps render at UTC in the default patterns — core
              may not read the machine's clock or zone.

    Returns:
      Hiccup structure or string for display"
  ([field-name value field-config]
   (render-field-value field-name value field-config nil))
  ([_field-name value field-config display]
   (let [field-type (:type field-config :string)]
     (cond
       (nil? value)
       [:span.null-value {:class "badge ui-badge ui-badge-neutral null-value"} "—"]

       (= field-type :boolean)
       (ui/badge (if value [:t :common/option-yes] [:t :common/option-no])
                 {:variant (if value :success :neutral)
                  :class (str "admin-bool-badge "
                              (if value "admin-bool-badge-true" "admin-bool-badge-false"))})

       (= field-type :instant)
       (format-instant value display)

       (= field-type :date)
       (format-date value display)

       (= field-type :uuid)
       [:span.uuid-value {:class "font-mono text-xs opacity-80"} (str value)]

       (= field-type :enum)
       [:span.enum-badge {:class "badge ui-badge ui-badge-outline enum-badge"}
        (str/capitalize (name value))]

       (= field-type :json)
       [:code (str value)]

       (string? value)
       (if (> (count value) 50)
         (str (subs value 0 47) "...")
         value)

       :else
       (str value)))))

;; =============================================================================
;; List Column Width Heuristics
;; =============================================================================

(def ^:private long-name-pattern
  ;; String fields whose names suggest long-form content deserve extra width.
  ;; \b word-boundaries keep this from matching substrings (e.g. "name" inside
  ;; "username"); kebab-cased names still match because '-' is a boundary.
  #"(?i)\b(description|omschrijving|notes?|opmerking|address|adres|comment|bio|summary|samenvatting|content|inhoud|body|message|bericht|excerpt)\b")

(def ^:private medium-name-pattern
  ;; String fields that are typically a sentence-ish label.
  #"(?i)\b(name|naam|title|titel|label|subject|onderwerp|e-?mail|url|slug|path|pad)\b")

(defn list-column-weight
  "Relative width weight for a list column, used to distribute table width
   proportionally instead of evenly.

   Resolution order:
   1. Explicit `:width` in the field config (interpreted as a weight) wins.
   2. Otherwise derived from `:type`, with a name-based heuristic for strings
      (e.g. \"description\" gets more room than \"status\").

   Pure: takes a field keyword + its config map, returns a positive number."
  [field field-config]
  (or (:width field-config)
      (let [field-name (name field)]
        (case (:type field-config)
          :boolean 1
          :enum 2
          (:int :decimal :uuid :json :binary) 2
          (:date :instant) 3
          :text 6
          ;; :string and anything unrecognised fall through to the heuristic
          (cond
            (re-find long-name-pattern field-name) 6
            (re-find medium-name-pattern field-name) 4
            :else 3)))))

(defn- format-pct
  "Render a percentage with at most two decimals, dropping a trailing `.0`
   so whole numbers read as e.g. \"25%\" rather than \"25.0%\"."
  [n]
  (let [rounded (/ (Math/round (* (double n) 100.0)) 100.0)]
    (if (== rounded (Math/rint rounded))
      (str (long rounded))
      (str rounded))))

(defn list-column-styles
  "Given the ordered list-fields and the entity config, return a seq of
   Hiccup `[:col {:style ...}]` elements with proportional `width:N%` for the
   data columns. The select/actions framing columns are sized via CSS classes
   elsewhere, so widths here sum to 100% of the remaining data area.

   Returns a seq (not a vector) so Hiccup splices the elements into the
   surrounding `:colgroup` rather than treating them as a single element.

   Pure helper — no I/O."
  [list-fields entity-config]
  (let [weights (mapv (fn [field]
                        (list-column-weight field (get-in entity-config [:fields field])))
                      list-fields)
        total   (max (reduce + 0 weights) 1)
        rounded (mapv (fn [w] (/ (Math/round (/ (* 10000.0 w) total)) 100.0)) weights)
        ;; The last column absorbs the rounding remainder so the widths sum to
        ;; exactly 100% instead of drifting to 99.99% / 100.01%.
        pcts    (if (seq rounded)
                  (let [head (pop rounded)]
                    (conj head (- 100.0 (reduce + 0.0 head))))
                  rounded)]
    (for [p pcts]
      [:col {:style (str "width:" (format-pct p) "%")}])))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn format-field-label
  "Format field name as human-readable label.

   Args:
     field-name: Keyword field name

   Returns:
     Capitalized string label"
  [field-name]
  (-> field-name
      name
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn get-field-errors
  "Extract errors for a specific field from validation result.

   Args:
     errors: Validation errors map or vector
     field-name: Keyword field name

   Returns:
     Vector of error messages for the field"
  [errors field-name]
  (cond
    (map? errors)
    (get errors field-name [])

    (vector? errors)
    (filterv #(= field-name (:field %)) errors)

    :else
    []))
