(ns wagoe.jobs.core.cron
  "Five-field cron expressions and their next run, in a given time zone.

   Fields: minute hour day-of-month month day-of-week, each `*`, a number, a
   range `a-b`, a step `*/n`, `a/n` or `a-b/n`, or a comma list of those.
   Months and weekdays also take names (JAN, MON). Day-of-week 0 and 7 are
   Sunday. As in Vixie cron, when both day fields are restricted a day
   matching either fires. `@hourly`, `@daily`, `@weekly`, `@monthly` and
   `@yearly` are shorthands.

   Times are wall-clock in the zone: each matching local time fires once. A
   local time skipped by a DST jump fires at the shifted instant; a local
   time that occurs twice fires on its first occurrence."
  (:require [clojure.string :as str])
  (:import [java.time Instant LocalDateTime ZoneId ZonedDateTime]
           [java.time.temporal ChronoUnit]))

(def ^:private macros
  {"@yearly"   "0 0 1 1 *"
   "@annually" "0 0 1 1 *"
   "@monthly"  "0 0 1 * *"
   "@weekly"   "0 0 * * 0"
   "@daily"    "0 0 * * *"
   "@midnight" "0 0 * * *"
   "@hourly"   "0 * * * *"})

(def ^:private fields
  [{:key :minutes :min 0 :max 59}
   {:key :hours :min 0 :max 23}
   {:key :days :min 1 :max 31}
   {:key :months :min 1 :max 12
    :names (zipmap ["JAN" "FEB" "MAR" "APR" "MAY" "JUN" "JUL" "AUG" "SEP" "OCT" "NOV" "DEC"]
                   (range 1 13))}
   {:key :weekdays :min 0 :max 7
    :names (zipmap ["SUN" "MON" "TUE" "WED" "THU" "FRI" "SAT"] (range 0 7))}])

(defn- error [message expr]
  {:error {:type :validation-error :message message :expression expr}})

(defn- parse-value [s {:keys [names]}]
  (or (get names (str/upper-case s))
      (when (re-matches #"\d{1,2}" s) (parse-long s))))

(defn- parse-part
  "The values one comma-separated part covers, or nil when it is malformed."
  [part {:keys [min max] :as field}]
  (let [[range-s step-s & more] (str/split part #"/" -1)
        step (if step-s (when (re-matches #"\d{1,2}" step-s) (parse-long step-s)) 1)
        [lo hi] (if (= "*" range-s)
                  [min max]
                  (let [[a b & extra] (str/split range-s #"-" -1)
                        lo (some-> a (parse-value field))]
                    (when-not extra
                      [lo (cond b (parse-value b field)
                                step-s max
                                :else lo)])))]
    (when (and (empty? more) step (pos? step) lo hi (<= min lo hi max))
      (range lo (inc hi) step))))

(defn- parse-field [s field]
  (let [values (map #(parse-part % field) (str/split s #"," -1))]
    (when (every? some? values)
      (set (apply concat values)))))

(defn parse
  "A schedule from cron expression `expr`, or `{:error …}`."
  [expr]
  (let [expr  (str/trim (str expr))
        expr' (get macros (str/lower-case expr) expr)
        parts (str/split expr' #"\s+")]
    (if (not= 5 (count parts))
      (error "A cron expression has five fields: minute hour day-of-month month day-of-week" expr)
      (let [parsed (map parse-field parts fields)
            bad    (first (keep-indexed (fn [i v] (when (nil? v) i)) parsed))]
        (if bad
          (error (str "Invalid " (name (:key (fields bad))) " field: " (nth parts bad)) expr)
          (-> (zipmap (map :key fields) parsed)
              (update :weekdays #(cond-> (disj % 7) (% 7) (conj 0)))
              (assoc :expression expr
                     ;; Vixie cron: a day field written with * is unrestricted.
                     :any-day?     (str/starts-with? (nth parts 2) "*")
                     :any-weekday? (str/starts-with? (nth parts 4) "*"))))))))

(defn- day-matches? [{:keys [days weekdays any-day? any-weekday?]} ^LocalDateTime t]
  (let [dom? (contains? days (.getDayOfMonth t))
        dow? (contains? weekdays (mod (.getValue (.getDayOfWeek t)) 7))]
    (if (or any-day? any-weekday?)
      (and dom? dow?)
      (or dom? dow?))))

(def ^:private horizon-years
  "Far enough to reach a 29 February that falls on a given weekday."
  28)

(defn next-run
  "The first instant strictly after `after` that `schedule` fires in `zone`, or
   nil when it never fires (30 February)."
  [schedule ^ZoneId zone ^Instant after]
  (let [{:keys [minutes hours months]} schedule
        from  (.atZone after zone)
        ;; Keeps a run inside a repeated hour on the side it started from.
        offset (.getOffset from)
        start (-> (.toLocalDateTime from) (.truncatedTo ChronoUnit/MINUTES) (.plusMinutes 1))
        limit (+ (.getYear start) horizon-years)]
    (loop [^LocalDateTime t start]
      (cond
        (> (.getYear t) limit) nil

        (not (contains? months (.getMonthValue t)))
        (recur (-> t (.withDayOfMonth 1) (.truncatedTo ChronoUnit/DAYS) (.plusMonths 1)))

        (not (day-matches? schedule t))
        (recur (-> t (.truncatedTo ChronoUnit/DAYS) (.plusDays 1)))

        (not (contains? hours (.getHour t)))
        (recur (-> t (.truncatedTo ChronoUnit/HOURS) (.plusHours 1)))

        (not (contains? minutes (.getMinute t)))
        (recur (.plusMinutes t 1))

        :else
        (let [instant (.toInstant (ZonedDateTime/ofLocal t zone offset))]
          (if (.isAfter instant after) instant (recur (.plusMinutes t 1))))))))

(def ^:private lookbacks
  "Windows `latest-run` tries before walking from `after`, so a long gap costs
   a handful of steps rather than one per missed tick."
  [60000 3600000 86400000 (* 32 86400000) (* 367 86400000)])

(defn latest-run
  "The last instant in (`after`, `upto`] that `schedule` fires in `zone`, or nil."
  [schedule zone ^Instant after ^Instant upto]
  (let [run-by (fn [t]
                 (when-let [n (next-run schedule zone t)]
                   (when-not (.isAfter ^Instant n upto) n)))
        from   (fn [^long window]
                 (let [t (.minusMillis upto window)]
                   (if (.isAfter t after) t after)))]
    (some (fn [start]
            (when-let [n (run-by start)]
              (loop [t n]
                (if-let [n' (run-by t)] (recur n') t))))
          (concat (map from lookbacks) [after]))))

(defn due-tick
  "What a ticker should enqueue now for a schedule last fired at `last-tick`:
   `{:tick instant :skipped? bool}`, or nil when nothing is due. Ticks missed
   while nothing ran collapse into the latest one; `:skipped?` says some did."
  [schedule zone ^Instant last-tick ^Instant now]
  (when-let [tick (latest-run schedule zone last-tick now)]
    {:tick tick :skipped? (not= tick (next-run schedule zone last-tick))}))
