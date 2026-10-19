(ns wagoe.audience.core.filter
  "Filter multimethods for audience segment evaluation.
   FC/IS rule: pure functions only — no I/O, no side effects, no logging.

   Validity is checked up front by `explain-filter` (pure, returns an anomaly
   map or nil). The shell validates a segment's filters before compilation and
   raises the typed error at the HTTP boundary, so the multimethods below assume
   a known filter type and a supported operator and never throw — their unknown
   branches fail safe (nil / a false predicate)."
  (:require [clojure.string :as str]
            [wagoe.core.utils.pii-redaction :as pii])
  (:import [java.util.regex Pattern]))

;; =============================================================================
;; SQL operator mapping
;; =============================================================================

(def ^:private sql-ops
  "Maps filter operator keywords to HoneySQL operator keywords."
  {:eq       :=
   :neq      :<>
   :gt       :>
   :gte      :>=
   :lt       :<
   :lte      :<=
   :in       :in
   :contains :like})

(defn- sql-op
  "Return the HoneySQL operator for a filter :op keyword. `field-clause` builds
   the :contains pattern itself."
  [op value]
  (case op
    :in       [:in value]
    [(get sql-ops op :=) value]))

(defn- days-ago-instant
  "The start of the day `days` before `now`, as an Instant.

   A bound parameter rather than `CURRENT_DATE - INTERVAL '… days'`, which is
   PostgreSQL syntax: H2 and SQLite both refuse to parse it, so these two
   filter types threw on the database every generated project starts with
   (BOU-425).

   An Instant, not a `java.sql.Date` or a LocalDate — this is the pure core, so
   the JDBC type is the adapter's business. It matters which: a LocalDate
   parameter is bound as a string, and SQLite stores these columns as epoch
   millis, where type affinity sorts every number before every string. That
   comparison does not fail; it answers no rows for `>=` and every row for
   `<=`, which is worse.

   `now` is the LocalDate the shell supplies through `compile-segment`."
  [now days]
  (.toInstant (.atStartOfDay (.minusDays now (long days)) java.time.ZoneOffset/UTC)))

(defn- field-clause
  "Build a HoneySQL clause [op field value] for a field-based filter. :contains
   escapes % _ and ! in the value with !, as
   `wagoe.platform.ports.database/like-pattern` does; this library does not
   depend on platform (BOU-684)."
  [{:keys [field op value]}]
  (if (= :contains op)
    [:escape [:like field (str "%" (str/replace (str value) #"[!%_]" "!$0") "%")] [:inline "!"]]
    (let [[honey-op honey-val] (sql-op op value)]
      [honey-op field honey-val])))

;; =============================================================================
;; filter->sql multimethod
;; =============================================================================

(defmulti filter->sql
  "Convert a filter map to a HoneySQL where-clause fragment.
   Dispatches on the :type key.

   Returns a HoneySQL clause vector, or nil if the filter cannot be
   expressed as SQL (e.g. :behavior uses an in-process predicate instead).

   Built-in types: :demographics, :location, :role, :account-tenure,
                   :last-active, :behavior, :feature-usage

   Applications can extend the multimethod:
     (defmethod filter->sql :my-type [filt] ...)"
  :type)

;; =============================================================================
;; filter->predicate multimethod
;; =============================================================================

(defmulti filter->predicate
  "Convert a filter map to a predicate function (fn [user-map] -> boolean).
   Dispatches on the :type key.

   Built-in types without a SQL representation (:behavior, :feature-usage)
   use this path. DB-evaluable filters may also provide a predicate for
   in-process post-filtering.

   Applications can extend the multimethod:
     (defmethod filter->predicate :my-type [filt] ...)"
  :type)

;; =============================================================================
;; Default implementations — unknown filter types are rejected up front by
;; `explain-filter`, so these are unreachable in a validated pipeline and fail
;; safe (no SQL clause / a predicate that matches nobody).
;; =============================================================================

(defmethod filter->sql :default [_filt] nil)

(defmethod filter->predicate :default [_filt] (constantly false))

;; =============================================================================
;; :demographics — simple field equality / comparison
;; =============================================================================

(defmethod filter->sql :demographics [filt]
  (field-clause filt))

(defmethod filter->predicate :demographics [{:keys [field op value]}]
  ;; A missing value matches no comparison, as NULL does in SQL: compare sorts
  ;; nil below everything, so :lt matched every user without the field.
  (let [[honey-op honey-val] (sql-op op value)
        ord        (fn [p] #(and (some? %) (p (compare % honey-val))))
        compare-fn (case honey-op
                     :=    #(= % honey-val)
                     :<>   #(and (some? %) (not= % honey-val))
                     :>    (ord pos?)
                     :>=   (ord (complement neg?))
                     :<    (ord neg?)
                     :<=   (ord (complement pos?))
                     :in   #(contains? (set honey-val) %)
                     :like (let [pat (re-pattern (str "(?i).*" (Pattern/quote (str value)) ".*"))]
                             #(and (some? %) (boolean (re-find pat (str %)))))
                     ;; validated up front; unreachable, matches nobody
                     (constantly false))]
    (fn [user] (compare-fn (get user field)))))

;; =============================================================================
;; :location — geographic field filter (same logic as demographics)
;; =============================================================================

(defmethod filter->sql :location [filt]
  (field-clause filt))

(defmethod filter->predicate :location [filt]
  (filter->predicate (assoc filt :type :demographics)))

;; =============================================================================
;; :role — role equality filter
;; =============================================================================

(defmethod filter->sql :role [filt]
  (field-clause filt))

(defmethod filter->predicate :role [filt]
  (filter->predicate (assoc filt :type :demographics)))

;; =============================================================================
;; :account-tenure — days since account creation
;;   SQL: compare (current_date - created_at) in days against :value
;; =============================================================================

(defmethod filter->sql :account-tenure [{:keys [op value now]}]
  ;; tenure >= N days means created_at <= now - N days (inverted comparison)
  (when now
    (let [days         (long value)
          ;; Comparison is inverted (tenure ≥ N days ⇔ created_at ≤ now - N days);
          ;; every op in supported-ops is mapped explicitly so none falls through.
          sql-operator (get {:gte :<=, :gt :<, :lte :>=, :lt :>, :eq :=, :neq :<>} op :<=)]
      [sql-operator :created_at (days-ago-instant now days)])))

(defmethod filter->predicate :account-tenure [{:keys [op value now]}]
  ;; nil without a date, rather than a predicate closing over one: it would
  ;; throw on the first user that has the timestamp, which is what the caller
  ;; is least able to explain. `compile-segment` reports the filter as
  ;; unsupported instead (BOU-425 review).
  ;;
  ;; `now` is supplied by the shell (compile-segment); the op is validated by
  ;; explain-filter, so the case has no throwing fallback.
  (when now
    (let [today      now
          compare-fn (case op
                       :gte #(>= % value)
                       :gt  #(> % value)
                       :lte #(<= % value)
                       :lt  #(< % value)
                       :eq  #(= % value)
                       :neq #(not= % value)
                       (constantly false))]
      (fn [user]
        (when-let [created (:created-at user)]
          (let [days (.between java.time.temporal.ChronoUnit/DAYS
                               (.toLocalDate (.atZone (.toInstant created)
                                                      java.time.ZoneOffset/UTC))
                               today)]
            (compare-fn days)))))))

;; =============================================================================
;; :last-active — activity within a rolling date window
;;   :within-days op: last_active_at >= (now - N days)
;; =============================================================================

(defmethod filter->sql :last-active [{:keys [op value now]}]
  (when (and now (= op :within-days))
    [:>= :last_active_at (days-ago-instant now (long value))]))

(defmethod filter->predicate :last-active [{:keys [op value now]}]
  ;; nil without a date — see :account-tenure above.
  (when now
    (let [today now]
      (case op
        :within-days
        (fn [user]
          (when-let [last-active (:last-active-at user)]
            (let [cutoff      (.minusDays today value)
                  active-date (.toLocalDate (.atZone (.toInstant last-active)
                                                     java.time.ZoneOffset/UTC))]
              (not (.isBefore active-date cutoff)))))
        (constantly false)))))

;; =============================================================================
;; :behavior — arbitrary in-process predicate; not SQL-evaluable
;; =============================================================================

(defmethod filter->sql :behavior [_filt]
  nil)

(defmethod filter->predicate :behavior [{:keys [value]}]
  value)

;; =============================================================================
;; :feature-usage — declarative feature-usage check; not SQL-evaluable
;;   :used-within N days checks user's feature usage log
;; =============================================================================

(defmethod filter->sql :feature-usage [_filt]
  nil)

(defmethod filter->predicate :feature-usage [{:keys [field op value now]}]
  ;; nil without a date — see :account-tenure above.
  (when now
    (case op
      :used-within
      (fn [user]
        (let [usage (get-in user [:feature-usage field])]
          (when usage
            (let [cutoff    (.minusDays now value)
                  last-used (.toLocalDate (.atZone (.toInstant usage)
                                                   java.time.ZoneOffset/UTC))]
              (not (.isBefore last-used cutoff))))))
      (constantly false))))

;; =============================================================================
;; Validation (pure) — the shell calls this before compilation and raises the
;; typed error at the HTTP boundary. Keeping it here lets the multimethods above
;; assume valid input and stay throw-free.
;; =============================================================================

(def supported-ops
  "User-facing operators accepted per built-in filter type. Source of truth for
   `explain-filter`; kept in step with the filter->predicate/filter->sql cases."
  {:demographics   #{:eq :neq :gt :gte :lt :lte :in :contains}
   :location       #{:eq :neq :gt :gte :lt :lte :in :contains}
   :role           #{:eq :neq :gt :gte :lt :lte :in :contains}
   :account-tenure #{:eq :neq :gt :gte :lt :lte}
   :last-active    #{:within-days}
   :feature-usage  #{:used-within}})

(defn known-type?
  "True when `t` has a registered filter->sql or filter->predicate method
   (built-in or application-extended via defmethod). `:default` is the
   multimethod fallback, not a real filter type, so it never counts as known —
   otherwise a {:type :default} filter would validate and then silently match
   nobody via the fail-safe defaults."
  [t]
  (boolean (and (not= t :default)
                (or (contains? (methods filter->sql) t)
                    (contains? (methods filter->predicate) t)))))

(defn explain-filter
  "Return an anomaly map {:error {...}} describing why `filt` cannot be
   evaluated, or nil when it is valid. Pure — never throws.

   Checks the :type is registered and — for built-in types with a fixed
   operator set — that :op is supported. Application-registered types are
   trusted for their own operators."
  [{:keys [type op] :as filt}]
  (cond
    (not (known-type? type))
    {:error {:type :unknown-filter-type :filter-type type :filter filt
             :message (str "No filter implementation for filter type " type)}}

    (and (contains? supported-ops type)
         (not (contains? (supported-ops type) op)))
    {:error {:type :unsupported-filter-op :filter-type type :op op :filter filt
             :message (str "Unsupported operator " op " for filter type " type)}}

    :else nil))

(defn explain-filters
  "Return the first {:error {...}} among `filters`, or nil when all are valid."
  [filters]
  (some explain-filter filters))

;; =============================================================================
;; Field allowlist
;; =============================================================================

(def default-filter-fields
  "Columns of the framework's `users` table a filter may name. A filter's field
   becomes a SQL column, and an audience's count answers questions about it, so
   anything not listed here — credentials above all — stays out of reach."
  #{:name :role :tenant-id :login-count :last-login :created-at :updated-at
    :language :timezone :theme :date-format :time-format
    :notifications-email :notifications-push :notifications-sms})

(defn- normalize-field
  "`:Password_Hash`, `:passwordHash` and `:password-hash` name the same unquoted
   column. A namespace is kept: HoneySQL reads `:t/col` as another table's column."
  [field]
  (keyword (pii/normalize-key-name (subs (str field) 1))))

(defn allowed-filter-fields
  "The default fields plus an app's `configured` ones."
  [configured]
  (into default-filter-fields (map normalize-field) configured))

(defn secret-field?
  "Whether `field` looks like a credential, which no configuration may allow.
   Matched without separators, so `:apikey` and `:passWord` are caught too."
  [field]
  (boolean (re-find #"password|passwd|hash|secret|token|salt|mfa|otp|backupcode|apikey|lockout|failedlogin"
                    (str/replace (name (normalize-field field)) "-" ""))))

(defn- filter-field
  "The column a filter reads, or nil. A :feature-usage field names a feature
   inside the user's usage map, not a column."
  [{:keys [type field]}]
  (when (and field (not= :feature-usage type))
    field))

(defn- explain-fields
  "{:error {...}} for the first filter whose normalized field satisfies `bad?`."
  [filters bad? message]
  (when-let [field (some #(when-let [f (filter-field %)]
                            (when (bad? (normalize-field f)) f))
                         filters)]
    {:error {:type :validation-error :field field
             :message (str "Filter field " field " is not allowed" message)}}))

(defn explain-secret-fields
  "{:error {...}} for the first filter naming a credential-like field, or nil."
  [filters]
  (explain-fields filters secret-field? ""))

(defn explain-filter-fields
  "{:error {...}} for the first filter whose field is not in `allowed`, or nil.
   Credentials are refused whatever `allowed` holds."
  [filters allowed]
  (explain-fields filters #(or (secret-field? %) (not (contains? allowed %)))
                  "; add it to :filter-fields"))
