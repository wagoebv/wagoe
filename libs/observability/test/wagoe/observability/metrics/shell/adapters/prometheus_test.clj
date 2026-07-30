(ns wagoe.observability.metrics.shell.adapters.prometheus-test
  "Unit tests for the pure-Clojure Prometheus metrics adapter.

   Covers counter/gauge/histogram emission, label handling and escaping,
   HELP-text toggling, disabled metrics, unknown export formats, and the
   overall well-formedness of the exposition text (HELP/TYPE precede samples)."
  (:require
   [wagoe.observability.metrics.ports :as ports]
   [wagoe.observability.metrics.shell.adapters.prometheus :as prom]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- lines
  "Split exposition text into non-blank lines."
  [text]
  (remove str/blank? (str/split-lines text)))

;; =============================================================================
;; Counters
;; =============================================================================

(deftest ^:unit test-counter-increment
  (testing "counter increments: default +1, explicit value, and per-tag series"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :http_requests "HTTP requests received" {})
      (ports/inc-counter! c :http_requests)                       ; +1
      (ports/inc-counter! c :http_requests 4)                     ; +4  => 5
      (ports/inc-counter! c :http_requests 2 {:method "GET"})     ; new series
      (ports/inc-counter! c :http_requests 3 {:method "GET"})     ; => 5
      (let [text (ports/export-metrics c :prometheus)]
        (is (str/includes? text "# TYPE http_requests counter"))
        (is (str/includes? text "# HELP http_requests HTTP requests received"))
        ;; unlabelled series accumulates 1 + 4 = 5
        (is (some #(= "http_requests 5" %) (lines text))
            (str "expected unlabelled total of 5 in:\n" text))
        ;; labelled series is distinct and accumulates 2 + 3 = 5
        (is (some #(= "http_requests{method=\"GET\"} 5" %) (lines text))
            (str "expected labelled series in:\n" text))))))

;; =============================================================================
;; Gauges
;; =============================================================================

(deftest ^:unit test-gauge-last-write-wins
  (testing "gauge keeps the last value per label-set"
    (let [c (prom/create-metrics-component {})]
      (ports/register-gauge! c :active_users "Active users" {})
      (ports/set-gauge! c :active_users 10)
      (ports/set-gauge! c :active_users 7)                        ; overwrites
      (ports/set-gauge! c :active_users 3 {:region "eu"})
      (let [text (ports/export-metrics c :prometheus)]
        (is (str/includes? text "# TYPE active_users gauge"))
        (is (some #(= "active_users 7" %) (lines text))
            (str "expected last-write-wins gauge of 7 in:\n" text))
        (is (some #(= "active_users{region=\"eu\"} 3" %) (lines text)))))))

;; =============================================================================
;; Histograms
;; =============================================================================

(deftest ^:unit test-histogram-buckets-sum-count
  (testing "histogram produces cumulative buckets, _sum and _count"
    (let [c       (prom/create-metrics-component {})
          buckets [0.1 0.5 1.0 5.0]]
      (ports/register-histogram! c :req_latency "Request latency seconds" buckets {})
      ;; observations: 0.05, 0.2, 0.7, 3.0  → sum 3.95, count 4
      (doseq [v [0.05 0.2 0.7 3.0]]
        (ports/observe-histogram! c :req_latency v))
      (let [text (ports/export-metrics c :prometheus)
            ls   (lines text)]
        (is (str/includes? text "# TYPE req_latency histogram"))
        ;; cumulative counts: le=0.1 → 1 (0.05)
        (is (some #(= "req_latency_bucket{le=\"0.1\"} 1" %) ls) (str text))
        ;; le=0.5 → 2 (0.05, 0.2)
        (is (some #(= "req_latency_bucket{le=\"0.5\"} 2" %) ls) (str text))
        ;; le=1 → 3 (adds 0.7)
        (is (some #(= "req_latency_bucket{le=\"1\"} 3" %) ls) (str text))
        ;; le=5 → 4 (adds 3.0)
        (is (some #(= "req_latency_bucket{le=\"5\"} 4" %) ls) (str text))
        ;; +Inf equals total count
        (is (some #(= "req_latency_bucket{le=\"+Inf\"} 4" %) ls) (str text))
        (is (some #(= "req_latency_count 4" %) ls) (str text))
        (is (some #(str/starts-with? % "req_latency_sum ") ls) (str text))))))

(deftest ^:unit test-histogram-plus-inf-for-large-values
  (testing "observations above every bucket land only in +Inf"
    (let [c (prom/create-metrics-component {})]
      (ports/register-histogram! c :big "Big values" [0.1 0.5] {})
      (ports/observe-histogram! c :big 99.0)
      (let [ls (lines (ports/export-metrics c :prometheus))]
        (is (some #(= "big_bucket{le=\"0.1\"} 0" %) ls))
        (is (some #(= "big_bucket{le=\"0.5\"} 0" %) ls))
        (is (some #(= "big_bucket{le=\"+Inf\"} 1" %) ls))
        (is (some #(= "big_count 1" %) ls))))))

(deftest ^:unit test-histogram-default-buckets-from-config
  (testing "histogram falls back to config :histogram-buckets when none given"
    (let [c (prom/create-metrics-component {:histogram-buckets [0.25 0.75]})]
      (ports/register-histogram! c :lat "latency" nil {})
      (ports/observe-histogram! c :lat 0.5)
      (let [ls (lines (ports/export-metrics c :prometheus))]
        (is (some #(= "lat_bucket{le=\"0.25\"} 0" %) ls))
        (is (some #(= "lat_bucket{le=\"0.75\"} 1" %) ls))))))

;; =============================================================================
;; Summaries
;; =============================================================================

(deftest ^:unit test-summary-sum-count
  (testing "summary emits _sum and _count (no quantiles)"
    (let [c (prom/create-metrics-component {})]
      (ports/register-summary! c :db_query "DB query seconds" [0.5 0.9] {})
      (ports/observe-summary! c :db_query 1.0)
      (ports/observe-summary! c :db_query 2.0)
      (let [text (ports/export-metrics c :prometheus)
            ls   (lines text)]
        (is (str/includes? text "# TYPE db_query summary"))
        (is (some #(= "db_query_count 2" %) ls) (str text))
        (is (some #(= "db_query_sum 3" %) ls) (str text))
        ;; no quantile lines are emitted
        (is (not-any? #(str/includes? % "quantile=") ls))))))

;; =============================================================================
;; Label escaping
;; =============================================================================

(deftest ^:unit test-label-value-escaping
  (testing "backslash, quote and newline in label values are escaped"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :evt "events" {})
      (ports/inc-counter! c :evt 1 {:path "a\"b\\c\nd"})
      (let [text (ports/export-metrics c :prometheus)]
        ;; " → \" , \ → \\ , newline → \n
        (is (str/includes? text "path=\"a\\\"b\\\\c\\nd\"")
            (str "escaping wrong in:\n" text))))))

;; =============================================================================
;; HELP text toggle
;; =============================================================================

(deftest ^:unit test-include-help-text-false-omits-help
  (testing ":include-help-text false suppresses HELP lines but keeps TYPE"
    (let [c (prom/create-metrics-component {:include-help-text false})]
      (ports/register-counter! c :hits "Total hits" {})
      (ports/inc-counter! c :hits)
      (let [text (ports/export-metrics c :prometheus)]
        (is (not (str/includes? text "# HELP")))
        (is (str/includes? text "# TYPE hits counter"))
        (is (some #(= "hits 1" %) (lines text)))))))

;; =============================================================================
;; Disabled metrics
;; =============================================================================

(deftest ^:unit test-disabled-metric-not-emitted
  (testing "a disabled metric is skipped by both emitter and exporter"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :secret "secret counter" {})
      (ports/register-counter! c :visible "visible counter" {})
      (is (ports/disable-metric! c :secret))
      (is (false? (ports/metric-enabled? c :secret)))
      (is (true? (ports/metric-enabled? c :visible)))
      ;; emission on a disabled metric is a no-op
      (ports/inc-counter! c :secret 5)
      (ports/inc-counter! c :visible 2)
      (let [text (ports/export-metrics c :prometheus)]
        (is (not (str/includes? text "secret")) (str text))
        (is (str/includes? text "visible"))
        (is (some #(= "visible 2" %) (lines text))))
      ;; re-enabling brings it back for future emissions
      (is (ports/enable-metric! c :secret))
      (ports/inc-counter! c :secret 5)
      (is (str/includes? (ports/export-metrics c :prometheus) "secret 5")))))

;; =============================================================================
;; Export format handling
;; =============================================================================

(deftest ^:unit test-unknown-format-returns-empty
  (testing "non-prometheus formats return an empty string"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :x "x" {})
      (ports/inc-counter! c :x)
      (is (= "" (ports/export-metrics c :json)))
      (is (= "" (ports/export-metrics c :datadog)))
      (is (= "" (ports/export-metrics c :openmetrics)))
      ;; string aliases and :text are accepted
      (is (str/includes? (ports/export-metrics c "prometheus") "x 1"))
      (is (str/includes? (ports/export-metrics c :text) "x 1")))))

;; =============================================================================
;; Well-formedness: HELP/TYPE precede samples
;; =============================================================================

(deftest ^:unit test-exposition-is-well-formed
  (testing "for each metric HELP precedes TYPE precedes samples"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :orders "Orders placed" {})
      (ports/register-histogram! c :dur "Durations" [0.5 1.0] {})
      (ports/inc-counter! c :orders 3)
      (ports/observe-histogram! c :dur 0.4)
      (let [ls       (vec (lines (ports/export-metrics c :prometheus)))
            idx      (fn [pred] (first (keep-indexed (fn [i l] (when (pred l) i)) ls)))]
        ;; counter block ordering: HELP < TYPE < sample
        (let [idx-help (idx #(= % "# HELP orders Orders placed"))
              idx-type (idx #(= % "# TYPE orders counter"))
              idx-samp (idx #(= % "orders 3"))]
          (is (and idx-help idx-type idx-samp))
          (is (< idx-help idx-type idx-samp)))
        ;; histogram block ordering: TYPE < first sample
        (let [idx-type (idx #(= % "# TYPE dur histogram"))
              idx-samp (idx #(str/starts-with? % "dur_bucket"))]
          (is (and idx-type idx-samp))
          (is (< idx-type idx-samp)))))))

;; =============================================================================
;; Timing + registry + reset
;; =============================================================================

(deftest ^:unit test-timing-records-and-returns-result
  (testing "time-histogram!/time-summary! record an observation and return (f)"
    (let [c (prom/create-metrics-component {})]
      (ports/register-histogram! c :work "work seconds" [0.001 1.0] {})
      (ports/register-summary! c :work2 "work seconds" [] {})
      (is (= :done (ports/time-histogram! c :work (fn [] :done))))
      (is (= :ok (ports/time-summary! c :work2 (fn [] :ok))))
      (let [ls (lines (ports/export-metrics c :prometheus))]
        (is (some #(= "work_count 1" %) ls))
        (is (some #(= "work2_count 1" %) ls))))))

(deftest ^:unit test-registry-and-config-and-reset
  (testing "registration is idempotent; reset clears series; config accessors work"
    (let [c (prom/create-metrics-component {})]
      (is (= :c (ports/register-counter! c :c "counter" {})))
      ;; idempotent re-registration keeps existing data
      (ports/inc-counter! c :c 4)
      (ports/register-counter! c :c "counter again" {})
      (is (str/includes? (ports/export-metrics c :prometheus) "c 4"))
      (is (= 1 (count (ports/list-metrics c))))
      (is (= :counter (:type (ports/get-metric c :c))))
      ;; default tags round-trip
      (is (= {} (ports/set-default-tags! c {:service "svc"})))
      (is (= {:service "svc"} (ports/get-default-tags c)))
      ;; export interval round-trip
      (is (= 0 (ports/set-export-interval! c 5000)))
      (is (= 5000 (ports/get-export-interval c)))
      ;; reset clears series but keeps the metric registered
      (ports/reset-metrics! c)
      (is (= "" (ports/export-metrics c :prometheus)))
      (is (= :counter (:type (ports/get-metric c :c))))
      ;; unknown handle is a graceful no-op
      (is (nil? (ports/inc-counter! c :does-not-exist 1)))
      (is (nil? (ports/get-metric-values c :does-not-exist))))))

(deftest ^:unit test-shared-component-set
  (testing "create-metrics-components shares one state across all keys"
    (let [{:keys [registry emitter exporter config]} (prom/create-metrics-components {})]
      ;; default tags are applied at emission time, so set them first
      (ports/set-default-tags! config {:env "test"})
      (ports/register-counter! registry :shared "shared" {})
      (ports/inc-counter! emitter :shared 9)
      (let [text (ports/export-metrics exporter :prometheus)]
        (is (str/includes? text "shared{env=\"test\"} 9") (str text))))))

(deftest ^:unit invalid-metric-and-label-names-are-sanitized
  (testing "dots/hyphens in metric + label names become underscores (valid Prometheus)"
    (let [c (prom/create-metrics-component {})
          h (ports/register-counter! c :http.requests-total "reqs" {})]
      (ports/inc-counter! c h 1 {:route.name "/a"})
      (let [out (ports/export-metrics c :prometheus)]
        (is (re-find #"http_requests_total" out))
        (is (not (re-find #"http\.requests" out)))
        (is (re-find #"route_name=\"/a\"" out))
        (is (not (re-find #"route\.name" out))))))
  (testing "a name starting with a digit gets an underscore prefix"
    (let [c (prom/create-metrics-component {})
          h (ports/register-counter! c (keyword "5xx") "5xx" {})]
      (ports/inc-counter! c h)
      (is (re-find #"(?m)^_5xx" (ports/export-metrics c :prometheus))))))

;; ============================================================================
;; Post-sanitization collisions (BOU-207)
;; ============================================================================

(deftest ^:unit metric-name-collision-first-registration-wins
  (testing "two keys sanitizing to the same metric name → later one ignored"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :http.requests "first" {})
      (ports/register-counter! c :http-requests "second (collides)" {})
      (ports/inc-counter! c :http.requests 2 {})
      (ports/inc-counter! c :http-requests 5 {}) ; no-op: not registered
      (let [out (ports/export-metrics c :prometheus)]
        (is (= 1 (count (re-seq #"(?m)^# TYPE http_requests " out)))
            "exactly one # TYPE line for http_requests")
        (is (re-find #"(?m)^http_requests 2$" out)
            "only the first metric's value is emitted")
        (is (not (re-find #"http_requests 5" out))
            "the colliding registration recorded nothing")))))

(deftest ^:unit colliding-metric-registration-is-not-added
  (let [c (prom/create-metrics-component {})]
    (ports/register-counter! c :http.requests "first" {})
    (ports/register-counter! c :http-requests "collides" {})
    (testing "the collider is absent from the registry"
      (is (some? (ports/get-metric c :http.requests)))
      (is (nil? (ports/get-metric c :http-requests))))))

(deftest ^:unit label-key-collision-is-deduped-in-a-series
  (testing "two label keys sanitizing to the same name → single label, deterministic"
    (let [c (prom/create-metrics-component {})]
      (ports/register-counter! c :hits "hits" {})
      (ports/inc-counter! c :hits 1 {:route.name "x" :route-name "y"})
      (let [out  (ports/export-metrics c :prometheus)
            line (->> (str/split-lines out)
                      (filter #(str/starts-with? % "hits{"))
                      first)]
        (is (= 1 (count (re-seq #"route_name=" line)))
            "exactly one route_name label in the series")
        (is (str/includes? line "route_name=\"x\"")
            "lexicographically-first value wins deterministically")))))
