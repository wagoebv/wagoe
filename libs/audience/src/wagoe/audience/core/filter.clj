(ns wagoe.audience.core.filter
  "Filter multimethods for audience segment evaluation.
   FC/IS rule: pure functions only — no I/O, no side effects, no logging.

   Validity is checked up front by `explain-filter` (pure, returns an anomaly
   map or nil). The shell validates a segment's filters before compilation and
   raises the typed error at the HTTP boundary, so the multimethods below assume
   a known filter type and a supported operator and never throw — their unknown
   branches fail safe (nil / a false predicate)."
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
  "Return the HoneySQL operator for a filter :op keyword.
   For :contains, the value is wrapped in % wildcards."
  [op value]
  (case op
    :in       [:in value]
    :contains [:like (str "%" value "%")]
    [(get sql-ops op :=) value]))

(defn- field-clause
  "Build a HoneySQL clause [op field value] for a field-based filter."
  [{:keys [field op value]}]
  (let [[honey-op honey-val] (sql-op op value)]
    [honey-op field honey-val]))

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
  (let [[honey-op honey-val] (sql-op op value)
        compare-fn (case honey-op
                     :=    #(= % honey-val)
                     :<>   #(not= % honey-val)
                     :>    #(pos? (compare % honey-val))
                     :>=   #(>= (compare % honey-val) 0)
                     :<    #(neg? (compare % honey-val))
                     :<=   #(<= (compare % honey-val) 0)
                     :in   #(contains? (set honey-val) %)
                     :like (let [pat (re-pattern (str "(?i).*" (Pattern/quote (str value)) ".*"))]
                             #(boolean (re-find pat (str %))))
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

(defmethod filter->sql :account-tenure [{:keys [op value]}]
  ;; tenure >= N days means created_at <= now - N days (inverted comparison)
  (let [days         (long value)
        ;; Comparison is inverted (tenure ≥ N days ⇔ created_at ≤ now - N days);
        ;; every op in supported-ops is mapped explicitly so none falls through.
        sql-operator (get {:gte :<=, :gt :<, :lte :>=, :lt :>, :eq :=, :neq :<>} op :<=)]
    [sql-operator :created_at [:raw (str "CURRENT_DATE - INTERVAL '" days " days'")]]))

(defmethod filter->predicate :account-tenure [{:keys [op value now]}]
  ;; `now` is supplied by the shell (compile-segment); the op is validated by
  ;; explain-filter, so the case has no throwing fallback.
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
          (compare-fn days))))))

;; =============================================================================
;; :last-active — activity within a rolling date window
;;   :within-days op: last_active_at >= (now - N days)
;; =============================================================================

(defmethod filter->sql :last-active [{:keys [op value]}]
  (when (= op :within-days)
    (let [days (long value)]
      [:>= :last_active_at [:raw (str "CURRENT_DATE - INTERVAL '" days " days'")]])))

(defmethod filter->predicate :last-active [{:keys [op value now]}]
  (let [today now]
    (case op
      :within-days
      (fn [user]
        (when-let [last-active (:last-active-at user)]
          (let [cutoff      (.minusDays today value)
                active-date (.toLocalDate (.atZone (.toInstant last-active)
                                                   java.time.ZoneOffset/UTC))]
            (not (.isBefore active-date cutoff)))))
      (constantly false))))

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
  (let [today now]
    (case op
      :used-within
      (fn [user]
        (let [usage (get-in user [:feature-usage field])]
          (when usage
            (let [cutoff    (.minusDays today value)
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
