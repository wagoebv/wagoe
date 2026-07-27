(ns wagoe.observability.metrics.shell.adapters.prometheus
  "Pure-Clojure Prometheus metrics adapter.

   An in-memory metric registry that renders the Prometheus text exposition
   format (https://prometheus.io/docs/instrumenting/exposition_formats/).
   It implements all four metrics protocols
   (IMetricsRegistry, IMetricsEmitter, IMetricsExporter, IMetricsConfig)
   in a single component backed by one Clojure atom, so it is safe for
   concurrent use via `swap!`.

   No external Prometheus client dependency is used — the exposition text is
   produced by this namespace directly.

   Sanitization + collisions (BOU-207): metric/label names are sanitized to valid
   Prometheus identifiers. Post-sanitization COLLISIONS are handled so the output
   stays valid:
   - Metric names: if a later registration sanitizes to the same name as an
     already-registered different key (e.g. :http.requests then :http-requests,
     both -> http_requests), the later one is logged and IGNORED — the first
     registration wins (no duplicate `# TYPE` line).
   - Label keys within a series: keys that sanitize to the same label name are
     de-duplicated deterministically at render (the lexicographically-first
     [name value] pair wins), so no duplicate label appears in a series.

   Series identity
   ---------------
   Each metric value is keyed by (metric-name, label-set), where the label-set
   is the merge of the registry default tags, the metric's registration tags,
   and any per-call tags. Distinct label-sets are therefore distinct series.

   Metric storage
   --------------
   - counter : {label-set -> numeric total}
   - gauge   : {label-set -> numeric value (last write wins)}
   - histogram: {label-set -> {:counts {bucket -> n} :inf n :sum s :count c}}
               (per-bucket non-cumulative counts; cumulative counts are computed
                at export time)
   - summary : {label-set -> {:sum s :count c}}  — quantiles are NOT tracked;
               only the `_sum`/`_count` pair is emitted (see note below).

   Simplifications
   ---------------
   - Summary quantiles are not computed. A summary emits only `_sum` and
     `_count`, which is a valid (if minimal) Prometheus summary exposition.
   - Metric names are emitted as-is (assumed already valid Prometheus names)."
  (:require
   [wagoe.observability.metrics.ports :as ports]
   [wagoe.observability.metrics.schema :as schema]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; =============================================================================
;; Formatting helpers
;; =============================================================================

(defn- prometheus-format?
  "True when `fmt` requests the Prometheus text exposition format."
  [fmt]
  (contains? #{:prometheus "prometheus" :text "text"} fmt))

(defn- fmt-num
  "Render a number for the exposition format. Whole-valued floats are printed
   without a fractional part; other values are printed as-is."
  [n]
  (cond
    (integer? n) (str n)
    (and (number? n)
         (not (Double/isInfinite (double n)))
         (not (Double/isNaN (double n)))
         (== (double n) (Math/rint (double n))))
    (str (long n))
    :else (str n)))

(defn- escape-label-value
  "Escape a Prometheus label value: backslash, double-quote, and newline."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- escape-help
  "Escape a Prometheus HELP line: backslash and newline (quotes are literal)."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\n" "\\n")))

(defn- sanitize-metric-name
  "Coerce a name to a valid Prometheus metric name: only [a-zA-Z0-9_:] are
   allowed, and it may not start with a digit. Invalid chars become `_`."
  [s]
  (let [s (str/replace s #"[^a-zA-Z0-9_:]" "_")]
    (if (re-find #"^[0-9]" s) (str "_" s) s)))

(defn- sanitize-label-name
  "Coerce a name to a valid Prometheus label name: only [a-zA-Z0-9_] (no `:`),
   not starting with a digit. Invalid chars become `_`."
  [s]
  (let [s (str/replace s #"[^a-zA-Z0-9_]" "_")]
    (if (re-find #"^[0-9]" s) (str "_" s) s)))

(defn- sorted-label-pairs
  "Convert a label map into a deterministic, name-sorted seq of
   [sanitized-key-string escaped-value] pairs. Keys that collide after
   sanitization (e.g. :route.name and :route-name -> route_name) are
   de-duplicated deterministically — the lexicographically-first [name value]
   pair wins — so a series never emits a duplicate label name."
  [label-map]
  (->> label-map
       (map (fn [[k v]] [(sanitize-label-name (name k)) (escape-label-value v)]))
       (sort)                              ; by [name value] — deterministic
       (reduce (fn [acc pair]
                 (if (= (first pair) (first (peek acc)))
                   acc                      ; same sanitized key as previous → drop
                   (conj acc pair)))
               [])))

(defn- render-labels
  "Render ordered [key escaped-value] pairs as a Prometheus label block,
   or the empty string when there are no labels."
  [pairs]
  (if (seq pairs)
    (str "{" (str/join "," (map (fn [[k v]] (str k "=\"" v "\"")) pairs)) "}")
    ""))

;; =============================================================================
;; Pure state helpers
;; =============================================================================

(defn- registered?
  [state metric-name]
  (contains? (:metrics state) metric-name))

(defn- name->prom
  "The Prometheus metric name a registration key sanitizes to."
  [k]
  (sanitize-metric-name (name k)))

(defn- collides-with
  "If registering `mname` would sanitize to the same Prometheus metric name as an
   already-registered DIFFERENT key, return that key; else nil."
  [state mname]
  (let [target (name->prom mname)]
    (some (fn [k] (when (and (not= k mname) (= target (name->prom k))) k))
          (keys (:metrics state)))))

(defn- active?
  "True when the metric is registered and not disabled."
  [state metric-name]
  (and (registered? state metric-name)
       (not (contains? (:disabled state) metric-name))))

(defn- label-set
  "Merge registry default tags, the metric's registration tags, and per-call
   tags (in increasing precedence) into the label-set for a series."
  [state metric call-tags]
  (merge (:default-tags state) (:tags metric) call-tags))

(defn- observe-into-buckets
  "Fold an observation `v` into a histogram series value using `buckets`
   (sorted ascending). Increments the count of the first bucket whose upper
   bound is >= v, or the +Inf bucket when v exceeds every bound."
  [series-val buckets v]
  (let [sv (or series-val {:counts {} :inf 0 :sum 0.0 :count 0})
        b  (some (fn [b] (when (<= v b) b)) buckets)]
    (cond-> (-> sv
                (update :sum + v)
                (update :count inc))
      b       (update-in [:counts b] (fnil inc 0))
      (not b) (update :inf inc))))

(defn- observe-into-summary
  "Fold an observation `v` into a summary series value (sum + count only)."
  [series-val v]
  (-> (or series-val {:sum 0.0 :count 0})
      (update :sum + v)
      (update :count inc)))

;; =============================================================================
;; Emission (side-effecting, guarded)
;; =============================================================================

(defn- do-inc-counter!
  [state-atom handle value tags]
  (swap! state-atom
         (fn [s]
           (let [m (get (:metrics s) handle)]
             (if (and (active? s handle) (= :counter (:type m)))
               (update-in s [:series handle (label-set s m tags)] (fnil + 0) value)
               s))))
  nil)

(defn- do-set-gauge!
  [state-atom handle value tags]
  (swap! state-atom
         (fn [s]
           (let [m (get (:metrics s) handle)]
             (if (and (active? s handle) (= :gauge (:type m)))
               (assoc-in s [:series handle (label-set s m tags)] value)
               s))))
  nil)

(defn- do-observe-histogram!
  [state-atom handle value tags]
  (swap! state-atom
         (fn [s]
           (let [m (get (:metrics s) handle)]
             (if (and (active? s handle) (= :histogram (:type m)))
               (update-in s [:series handle (label-set s m tags)]
                          observe-into-buckets (:buckets m) value)
               s))))
  nil)

(defn- do-observe-summary!
  [state-atom handle value tags]
  (swap! state-atom
         (fn [s]
           (let [m (get (:metrics s) handle)]
             (if (and (active? s handle) (= :summary (:type m)))
               (update-in s [:series handle (label-set s m tags)]
                          observe-into-summary value)
               s))))
  nil)

;; =============================================================================
;; Rendering
;; =============================================================================

(defn- histogram-series-lines
  "Render the `_bucket`/`_sum`/`_count` lines for one histogram series."
  [metric-name base-pairs series-val buckets]
  (let [counts (:counts series-val)
        [bucket-lines running]
        (reduce (fn [[acc run] b]
                  (let [run' (+ run (get counts b 0))]
                    [(conj acc (str metric-name "_bucket"
                                    (render-labels (concat base-pairs [["le" (fmt-num b)]]))
                                    " " run'))
                     run']))
                [[] 0]
                buckets)
        total (+ running (:inf series-val 0))]
    (-> bucket-lines
        (conj (str metric-name "_bucket"
                   (render-labels (concat base-pairs [["le" "+Inf"]]))
                   " " total))
        (conj (str metric-name "_sum" (render-labels base-pairs)
                   " " (fmt-num (:sum series-val))))
        (conj (str metric-name "_count" (render-labels base-pairs)
                   " " (:count series-val))))))

(defn- summary-series-lines
  "Render the `_sum`/`_count` lines for one summary series (no quantiles)."
  [metric-name base-pairs series-val]
  [(str metric-name "_sum" (render-labels base-pairs) " " (fmt-num (:sum series-val)))
   (str metric-name "_count" (render-labels base-pairs) " " (:count series-val))])

(defn- metric-lines
  "Render the HELP/TYPE header plus all sample lines for a single metric as a
   vector of strings. HELP/TYPE always precede any samples. A metric with no
   recorded series renders nothing (no dangling TYPE header)."
  [state metric-name]
  (let [metric (get (:metrics state) metric-name)
        series (get (:series state) metric-name {})]
    (if (empty? series)
      []
      (let [nm       (sanitize-metric-name (name metric-name))
            type-str (name (:type metric))
            header   (cond-> []
                       (and (:include-help? state) (:description metric))
                       (conj (str "# HELP " nm " " (escape-help (:description metric))))
                       :always
                       (conj (str "# TYPE " nm " " type-str)))
            ;; sort series deterministically by their rendered label block
            ordered  (sort-by (comp render-labels sorted-label-pairs key) series)]
        (case (:type metric)
          (:counter :gauge)
          (into header
                (map (fn [[labels val]]
                       (str nm (render-labels (sorted-label-pairs labels)) " " (fmt-num val))))
                ordered)

          :histogram
          (into header
                (mapcat (fn [[labels sv]]
                          (histogram-series-lines nm (sorted-label-pairs labels) sv (:buckets metric))))
                ordered)

          :summary
          (into header
                (mapcat (fn [[labels sv]]
                          (summary-series-lines nm (sorted-label-pairs labels) sv)))
                ordered)

          header)))))

(defn- lines->text
  "Join exposition lines, appending a trailing newline; empty in → empty out."
  [lines]
  (if (seq lines)
    (str (str/join "\n" lines) "\n")
    ""))

(defn- render-all
  "Render every active metric, ordered by name."
  [state]
  (->> (keys (:metrics state))
       (remove #(contains? (:disabled state) %))
       (sort-by name)
       (mapcat #(metric-lines state %))
       lines->text))

;; =============================================================================
;; Registration (collision-aware)
;; =============================================================================

(defn- do-register!
  "Shared registration path. Idempotent on the same key. On a post-sanitization
   metric-name collision with a DIFFERENT already-registered key, warn and skip
   so the first registration wins (no duplicate `# TYPE`). `metric-map-fn`
   receives the current state (histograms need `:default-buckets`). Returns
   `mname` as the handle regardless.

   The collision check is check-then-act outside the `swap!` (so the `log/warn`
   is not inside a retryable transaction). Metric registration is assumed
   single-threaded — done once at wiring/startup — so a concurrent pair of
   colliding registrations could both slip through; that is out of scope."
  [state-atom mname metric-map-fn]
  (let [s        @state-atom
        collider (collides-with s mname)]
    (cond
      (registered? s mname) mname

      collider
      (do (log/warn "Prometheus metric name collision after sanitization; ignoring later registration"
                    {:metric mname :collides-with collider :sanitized (name->prom mname)})
          mname)

      :else
      (do (swap! state-atom
                 (fn [st]
                   (if (registered? st mname)
                     st
                     (assoc-in st [:metrics mname] (metric-map-fn st)))))
          mname))))

;; =============================================================================
;; Component
;; =============================================================================

(defrecord PrometheusMetricsComponent [state]
  ports/IMetricsRegistry
  (register-counter! [_ name description tags]
    (do-register! state name (fn [_] {:type :counter :description description :tags (or tags {})})))

  (register-gauge! [_ name description tags]
    (do-register! state name (fn [_] {:type :gauge :description description :tags (or tags {})})))

  (register-histogram! [_ name description buckets tags]
    (do-register! state name
                  (fn [st] {:type :histogram :description description
                            :buckets (vec (sort (if (seq buckets) buckets (:default-buckets st))))
                            :tags (or tags {})})))

  (register-summary! [_ name description quantiles tags]
    (do-register! state name (fn [_] {:type :summary :description description
                                      :quantiles (vec quantiles) :tags (or tags {})})))

  (unregister! [_ name]
    (let [existed? (registered? @state name)]
      (swap! state (fn [s]
                     (-> s
                         (update :metrics dissoc name)
                         (update :series dissoc name)
                         (update :disabled disj name))))
      existed?))

  (list-metrics [_]
    (let [s @state]
      (->> (:metrics s)
           (mapv (fn [[name metric]]
                   (assoc metric
                          :name name
                          :handle name
                          :enabled (active? s name)))))))

  (get-metric [_ name]
    (let [s @state]
      (when-let [metric (get (:metrics s) name)]
        (assoc metric :name name :handle name :enabled (active? s name)))))

  ports/IMetricsEmitter
  (inc-counter! [this metric-handle]
    (ports/inc-counter! this metric-handle 1 {}))
  (inc-counter! [this metric-handle value]
    (ports/inc-counter! this metric-handle value {}))
  (inc-counter! [_ metric-handle value tags]
    (do-inc-counter! state metric-handle value tags))

  (set-gauge! [this metric-handle value]
    (ports/set-gauge! this metric-handle value {}))
  (set-gauge! [_ metric-handle value tags]
    (do-set-gauge! state metric-handle value tags))

  (observe-histogram! [this metric-handle value]
    (ports/observe-histogram! this metric-handle value {}))
  (observe-histogram! [_ metric-handle value tags]
    (do-observe-histogram! state metric-handle value tags))

  (observe-summary! [this metric-handle value]
    (ports/observe-summary! this metric-handle value {}))
  (observe-summary! [_ metric-handle value tags]
    (do-observe-summary! state metric-handle value tags))

  (time-histogram! [this metric-handle f]
    (ports/time-histogram! this metric-handle {} f))
  (time-histogram! [this metric-handle tags f]
    (let [start  (System/nanoTime)
          result (f)
          secs   (/ (double (- (System/nanoTime) start)) 1.0e9)]
      (ports/observe-histogram! this metric-handle secs tags)
      result))

  (time-summary! [this metric-handle f]
    (ports/time-summary! this metric-handle {} f))
  (time-summary! [this metric-handle tags f]
    (let [start  (System/nanoTime)
          result (f)
          secs   (/ (double (- (System/nanoTime) start)) 1.0e9)]
      (ports/observe-summary! this metric-handle secs tags)
      result))

  ports/IMetricsExporter
  (export-metrics [_ format]
    (if (prometheus-format? format)
      (render-all @state)
      ""))

  (export-metric [_ metric-name format]
    (let [s @state]
      (if (and (prometheus-format? format) (active? s metric-name))
        (lines->text (metric-lines s metric-name))
        "")))

  (get-metric-values [_ metric-name]
    (let [s @state]
      (when-let [metric (get (:metrics s) metric-name)]
        {:type        (:type metric)
         :description (:description metric)
         :tags        (:tags metric)
         :buckets     (:buckets metric)
         :series      (get (:series s) metric-name {})
         :enabled     (active? s metric-name)
         :timestamp   (System/currentTimeMillis)})))

  (reset-metrics! [_]
    (swap! state assoc :series {})
    nil)

  (flush! [_] nil)

  ports/IMetricsConfig
  (set-default-tags! [_ tags]
    (let [prev (:default-tags @state)]
      (swap! state assoc :default-tags (or tags {}))
      prev))

  (get-default-tags [_]
    (:default-tags @state))

  (set-export-interval! [_ interval-ms]
    (let [prev (:export-interval @state)]
      (swap! state assoc :export-interval interval-ms)
      prev))

  (get-export-interval [_]
    (:export-interval @state))

  (enable-metric! [_ metric-name]
    (let [found? (registered? @state metric-name)]
      (when found?
        (swap! state update :disabled disj metric-name))
      found?))

  (disable-metric! [_ metric-name]
    (let [found? (registered? @state metric-name)]
      (when found?
        (swap! state update :disabled conj metric-name))
      found?))

  (metric-enabled? [_ metric-name]
    (active? @state metric-name)))

;; =============================================================================
;; Factory functions
;; =============================================================================

(defn- initial-state
  "Build the initial atom state from a Prometheus provider config map."
  [config]
  (let [buckets       (or (:histogram-buckets config)
                          (:histogram-buckets schema/default-prometheus-config))
        default-tags  (get-in config [:tagging :default-tags] {})
        include-help? (not (false? (:include-help-text config)))]
    {:metrics         {}
     :series          {}
     :disabled        #{}
     :default-tags    default-tags
     :export-interval 0
     :include-help?   include-help?
     :default-buckets (vec (sort buckets))
     :registry-name   (:registry-name config)}))

(defn create-metrics-component
  "Create a Prometheus metrics component implementing all four metrics
   protocols, backed by a single atom.

   Args:
     config - Prometheus provider config map (see
              wagoe.observability.metrics.schema/PrometheusMetricsConfig).
              Recognised keys: :histogram-buckets, :include-help-text,
              :registry-name, and :tagging {:default-tags {...}}.

   Returns:
     Component implementing IMetricsRegistry, IMetricsEmitter,
     IMetricsExporter, and IMetricsConfig."
  ([] (create-metrics-component {}))
  ([config] (->PrometheusMetricsComponent (atom (initial-state config)))))

(defn create-metrics-registry
  "Create a Prometheus metrics registry (a full component; all protocols share
   one atom). See `create-metrics-component`."
  ([] (create-metrics-component {}))
  ([config] (create-metrics-component config)))

(defn create-metrics-emitter
  "Create a Prometheus metrics emitter (a full component; all protocols share
   one atom). See `create-metrics-component`."
  ([] (create-metrics-component {}))
  ([config] (create-metrics-component config)))

(defn create-metrics-exporter
  "Create a Prometheus metrics exporter (a full component; all protocols share
   one atom). See `create-metrics-component`."
  ([] (create-metrics-component {}))
  ([config] (create-metrics-component config)))

(defn create-metrics-config
  "Create a Prometheus metrics config (a full component; all protocols share
   one atom). See `create-metrics-component`."
  ([] (create-metrics-component {}))
  ([config] (create-metrics-component config)))

(defn create-metrics-components
  "Create a complete set of Prometheus metrics components that all share a
   single underlying component/atom, so registration, emission, export, and
   config operate on the same state.

   Returns:
     {:registry  IMetricsRegistry
      :emitter   IMetricsEmitter
      :exporter  IMetricsExporter
      :config    IMetricsConfig}"
  ([] (create-metrics-components {}))
  ([config]
   (let [component (create-metrics-component config)]
     {:registry component
      :emitter  component
      :exporter component
      :config   component})))
