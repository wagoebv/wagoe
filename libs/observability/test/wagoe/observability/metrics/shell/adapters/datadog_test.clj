(ns wagoe.observability.metrics.shell.adapters.datadog-test
  "Test suite for Datadog metrics adapter.
   
   This test suite covers all aspects of the Datadog metrics adapter:
   - Protocol implementations
   - DogStatsD line formatting
   - Tag merging and precedence
   - Sampling behavior
   - Enable/disable functionality
   - Timing accuracy
   - Error handling
   - Configuration validation"
  (:require
   [wagoe.observability.metrics.ports :as ports]
   [wagoe.observability.metrics.shell.adapters.datadog :as datadog]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Test Fixtures and Utilities
;; =============================================================================

(def test-config
  "Test configuration for Datadog metrics adapter."
  {:provider :datadog-statsd
   :host "localhost"
   :port 8125
   :service "test-service"
   :environment "test"
   :global-tags ["service:test-service" "environment:test"]
   :sample-rate 1.0
   :max-packet-size 1432
   :debug? false})

(defn create-mock-sender
  "Create a mock sender that captures sent lines."
  []
  (let [sent-lines (atom [])]
    {:sender (fn [line] (swap! sent-lines conj line))
     :lines sent-lines}))

(defn create-test-components
  "Create test components with mock sender."
  ([]
   (create-test-components test-config))
   ([config]
    (let [{:keys [sender lines]} (create-mock-sender)
          config-atom (atom config)
          registry (datadog/create-datadog-metrics-registry config-atom)
          emitter (#'datadog/->DatadogMetricsEmitter registry config-atom sender)
          exporter (datadog/create-datadog-metrics-exporter registry config-atom)
          metrics-config (datadog/create-datadog-metrics-config config registry)]
     {:registry registry
      :emitter emitter
      :exporter exporter
      :config metrics-config
      :sent-lines lines})))

;; =============================================================================
;; Registry Tests
;; =============================================================================

(deftest ^:unit test-metrics-registry
  (testing "Counter registration"
    (let [{:keys [registry]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test counter" {:team "test"})]
      (is (= :test.counter handle))
      (let [metric (ports/get-metric registry :test.counter)]
        (is (= :counter (:type metric)))
        (is (= "Test counter" (:description metric)))
        (is (= {:team "test"} (:tags metric)))
        (is (:enabled metric)))))

  (testing "Gauge registration"
    (let [{:keys [registry]} (create-test-components)
          handle (ports/register-gauge! registry :test.gauge "Test gauge" {:team "test"})]
      (is (= :test.gauge handle))
      (let [metric (ports/get-metric registry :test.gauge)]
        (is (= :gauge (:type metric)))
        (is (= "Test gauge" (:description metric)))
        (is (= {:team "test"} (:tags metric)))
        (is (:enabled metric)))))

  (testing "Histogram registration"
    (let [{:keys [registry]} (create-test-components)
          handle (ports/register-histogram! registry :test.histogram "Test histogram" [0.1 0.5 1.0] {:team "test"})]
      (is (= :test.histogram handle))
      (let [metric (ports/get-metric registry :test.histogram)]
        (is (= :histogram (:type metric)))
        (is (= "Test histogram" (:description metric)))
        (is (= [0.1 0.5 1.0] (:buckets metric)))
        (is (= {:team "test"} (:tags metric)))
        (is (:enabled metric)))))

  (testing "Summary registration"
    (let [{:keys [registry]} (create-test-components)
          handle (ports/register-summary! registry :test.summary "Test summary" [0.5 0.95 0.99] {:team "test"})]
      (is (= :test.summary handle))
      (let [metric (ports/get-metric registry :test.summary)]
        (is (= :summary (:type metric)))
        (is (= "Test summary" (:description metric)))
        (is (= [0.5 0.95 0.99] (:quantiles metric)))
        (is (= {:team "test"} (:tags metric)))
        (is (:enabled metric)))))

  (testing "Metric unregistration"
    (let [{:keys [registry]} (create-test-components)]
      (ports/register-counter! registry :test.counter "Test counter" {})
      (is (ports/get-metric registry :test.counter))
      (is (ports/unregister! registry :test.counter))
      (is (nil? (ports/get-metric registry :test.counter)))
      (is (false? (ports/unregister! registry :test.counter)))))

  (testing "List metrics"
    (let [{:keys [registry]} (create-test-components)]
      (ports/register-counter! registry :counter1 "Counter 1" {})
      (ports/register-gauge! registry :gauge1 "Gauge 1" {})
      (let [metrics (ports/list-metrics registry)]
        (is (= 2 (count metrics)))
        (is (some #(= :counter1 (:name %)) metrics))
        (is (some #(= :gauge1 (:name %)) metrics))))))

;; =============================================================================
;; Emitter Tests
;; =============================================================================

(deftest ^:unit test-metrics-emitter
  (testing "Counter increment"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test counter" {:metric "tag"})]
      (ports/inc-counter! emitter handle)
      (is (= 1 (count @sent-lines)))
      (is (.contains (first @sent-lines) "test.counter:1|c"))
      (is (.contains (first @sent-lines) "#service:test-service,environment:test,metric:tag"))))

  (testing "Counter increment with value"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test counter" {})]
      (ports/inc-counter! emitter handle 5)
      (is (= 1 (count @sent-lines)))
      (is (.contains (first @sent-lines) "test.counter:5|c"))))

  (testing "Counter increment with tags"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test counter" {:metric "tag"})]
      (ports/inc-counter! emitter handle 3 {:call "tag"})
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.contains line "test.counter:3|c"))
        (is (.contains line "service:test-service"))
        (is (.contains line "environment:test"))
        (is (.contains line "metric:tag"))
        (is (.contains line "call:tag")))))

  (testing "Gauge set"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-gauge! registry :test.gauge "Test gauge" {})]
      (ports/set-gauge! emitter handle 42.5)
      (is (= 1 (count @sent-lines)))
      (is (.contains (first @sent-lines) "test.gauge:42.5|g"))))

  (testing "Histogram observe"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-histogram! registry :test.histogram "Test histogram" [0.1 0.5 1.0] {})]
      (ports/observe-histogram! emitter handle 0.75)
      (is (= 1 (count @sent-lines)))
      (is (.contains (first @sent-lines) "test.histogram:0.75|h"))))

  (testing "Summary observe"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-summary! registry :test.summary "Test summary" [0.5 0.95 0.99] {})]
      (ports/observe-summary! emitter handle 1.25)
      (is (= 1 (count @sent-lines)))
      (is (.contains (first @sent-lines) "test.summary:1.25|h"))))

  (testing "Timing histogram"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-histogram! registry :test.timing "Test timing" [] {})
          result (ports/time-histogram! emitter handle (fn [] (Thread/sleep 10) "result"))]
      (is (= "result" result))
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.contains line "test.timing:"))
        (is (.contains line "|h"))
        ;; Verify timing is reasonable (at least 5ms, less than 100ms)
        (let [value-str (-> line (str/split #":") second (str/split #"\|") first)
              value (Double/parseDouble value-str)]
          (is (>= value 5.0))
          (is (<= value 100.0))))))

  (testing "Timing summary"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-summary! registry :test.timing "Test timing" [0.5 0.95] {})
          result (ports/time-summary! emitter handle {:operation "test"} (fn [] (Thread/sleep 5) 123))]
      (is (= 123 result))
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.contains line "test.timing:"))
        (is (.contains line "|h"))
        (is (.contains line "operation:test"))))))

;; =============================================================================
;; Tag Merging Tests
;; =============================================================================

(deftest ^:unit test-tag-merging
  (testing "Tag precedence: global < metric < call"
    (let [config (assoc test-config :global-tags {:env "prod" :service "test" :global "only"})
          {:keys [registry emitter sent-lines]} (create-test-components config)
          handle (ports/register-counter! registry :test.counter "Test" {:env "staging" :metric "only"})]
      (ports/inc-counter! emitter handle 1 {:env "dev" :call "only"})
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)
            tags-part (-> line (str/split #"#") second)]
        ;; Call tags should override metric and global
        (is (.contains tags-part "env:dev"))
        ;; Service should come from global (not overridden)
        (is (.contains tags-part "service:test"))
        ;; Unique tags from each level should be present
        (is (.contains tags-part "global:only"))
        (is (.contains tags-part "metric:only"))
        (is (.contains tags-part "call:only"))))))

;; =============================================================================
;; Sampling Tests
;; =============================================================================

(deftest ^:unit test-sampling
  (testing "No sampling (rate = 1.0)"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test" {})]
      (dotimes [_ 10]
        (ports/inc-counter! emitter handle))
      (is (= 10 (count @sent-lines)))))

  (testing "Sample rate annotation"
    (let [config (assoc test-config :sample-rate 0.5)
          {:keys [registry emitter sent-lines]} (create-test-components config)
          handle (ports/register-counter! registry :test.counter "Test" {})]
      ;; Force at least one sample by calling many times
      (dotimes [_ 100]
        (ports/inc-counter! emitter handle))
      ;; Should have some samples (not zero, not all)
      (is (> (count @sent-lines) 0))
      (is (< (count @sent-lines) 100))
      ;; Lines should contain sample rate annotation
      (is (every? #(.contains % "|@0.5") @sent-lines))))

  (testing "Gauge not sampled"
    (let [config (assoc test-config :sample-rate 0.5)
          {:keys [registry emitter sent-lines]} (create-test-components config)
          handle (ports/register-gauge! registry :test.gauge "Test" {})]
      (ports/set-gauge! emitter handle 42)
      (is (= 1 (count @sent-lines)))
      ;; Gauge should not have sample rate annotation
      (is (not (.contains (first @sent-lines) "@0.5"))))))

;; =============================================================================
;; Enable/Disable Tests
;; =============================================================================

(deftest ^:unit test-metric-enable-disable
  (testing "Disabled metric does not emit"
    (let [{:keys [registry emitter config sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test" {})]
      (ports/inc-counter! emitter handle)
      (is (= 1 (count @sent-lines)))

      (ports/disable-metric! config :test.counter)
      (ports/inc-counter! emitter handle)
      (is (= 1 (count @sent-lines))) ;; No new lines

      (ports/enable-metric! config :test.counter)
      (ports/inc-counter! emitter handle)
      (is (= 2 (count @sent-lines))))) ;; New line added

  (testing "Metric enabled check"
    (let [{:keys [registry config]} (create-test-components)]
      (ports/register-counter! registry :test.counter "Test" {})
      (is (ports/metric-enabled? config :test.counter))
      (ports/disable-metric! config :test.counter)
      (is (not (ports/metric-enabled? config :test.counter)))
      (ports/enable-metric! config :test.counter)
      (is (ports/metric-enabled? config :test.counter)))))

;; =============================================================================
;; Exporter Tests
;; =============================================================================

(deftest ^:unit test-metrics-exporter
  (testing "Export all metrics"
    (let [{:keys [registry emitter exporter]} (create-test-components)
          counter-handle (ports/register-counter! registry :test.counter "Test counter" {})
          gauge-handle (ports/register-gauge! registry :test.gauge "Test gauge" {})]
      (ports/inc-counter! emitter counter-handle 5)
      (ports/set-gauge! emitter gauge-handle 42)

      (let [export (ports/export-metrics exporter :datadog)]
        (is (= 2 (count export)))
        (is (some #(= :test.counter (:name %)) export))
        (is (some #(= :test.gauge (:name %)) export)))))

  (testing "Export single metric"
    (let [{:keys [registry emitter exporter]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test counter" {})]
      (ports/inc-counter! emitter handle 3)

      (let [export (ports/export-metric exporter :test.counter :datadog)]
        (is (= :test.counter (:name export)))
        (is (= :counter (:type export)))
        (is (= 3 (:value export))))))

  (testing "Get metric values"
    (let [{:keys [registry emitter exporter]} (create-test-components)
          handle (ports/register-histogram! registry :test.histogram "Test histogram" [] {})]
      (ports/observe-histogram! emitter handle 1.0)
      (ports/observe-histogram! emitter handle 2.0)
      (ports/observe-histogram! emitter handle 3.0)

      (let [values (ports/get-metric-values exporter :test.histogram)]
        (is (= :histogram (:type values)))
        (is (= [1.0 2.0 3.0] (:values values)))
        (is (= {:count 3 :sum 6.0 :min 1.0 :max 3.0 :avg 2.0} (:stats values))))))

  (testing "Reset metrics"
    (let [{:keys [registry emitter exporter]} (create-test-components)
          counter-handle (ports/register-counter! registry :test.counter "Test counter" {})
          gauge-handle (ports/register-gauge! registry :test.gauge "Test gauge" {})]
      (ports/inc-counter! emitter counter-handle 5)
      (ports/set-gauge! emitter gauge-handle 42)

      (ports/reset-metrics! exporter)

      (let [counter-values (ports/get-metric-values exporter :test.counter)
            gauge-values (ports/get-metric-values exporter :test.gauge)]
        (is (= 0 (:value counter-values)))
        (is (nil? (:value gauge-values)))))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest ^:unit test-metrics-config
  (testing "Default tags management"
    (let [{:keys [config]} (create-test-components)]
      (is (= {:service "test-service" :environment "test"} (ports/get-default-tags config)))
      (let [previous (ports/set-default-tags! config {:new "tags"})]
        (is (= {:service "test-service" :environment "test"} previous))
        (is (= {:new "tags"} (ports/get-default-tags config))))))

  (testing "Export interval management"
    (let [{:keys [config]} (create-test-components)]
      (is (nil? (ports/get-export-interval config)))
      (let [previous (ports/set-export-interval! config 30000)]
        (is (nil? previous))
        (is (= 30000 (ports/get-export-interval config)))))))

;; =============================================================================
;; DogStatsD Protocol Tests
;; =============================================================================

(deftest ^:unit test-dogstatsd-protocol
  (testing "Counter line format"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :my.counter "Test" {})]
      (ports/inc-counter! emitter handle 5)
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.startsWith line "my.counter:5|c")))))

  (testing "Gauge line format"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-gauge! registry :my.gauge "Test" {})]
      (ports/set-gauge! emitter handle 42.5)
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.startsWith line "my.gauge:42.5|g")))))

  (testing "Histogram line format"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-histogram! registry :my.histogram "Test" [] {})]
      (ports/observe-histogram! emitter handle 1.25)
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)]
        (is (.startsWith line "my.histogram:1.25|h")))))

  (testing "Tags format"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :my.counter "Test" {:key1 "value1" :key2 "value2"})]
      (ports/inc-counter! emitter handle 1)
      (is (= 1 (count @sent-lines)))
      (let [line (first @sent-lines)
            tags-part (-> line (str/split #"#") second)]
        (is (.contains tags-part "key1:value1"))
        (is (.contains tags-part "key2:value2"))))))

;; =============================================================================
;; Component Integration Tests
;; =============================================================================

(deftest ^:unit test-component-creation
  (testing "Create complete component"
    (let [component (datadog/create-datadog-metrics-component test-config)]
      ;; Should implement all protocols
      (is (satisfies? ports/IMetricsRegistry component))
      (is (satisfies? ports/IMetricsEmitter component))
      (is (satisfies? ports/IMetricsExporter component))
      (is (satisfies? ports/IMetricsConfig component))

      ;; Should work end-to-end
      (let [handle (ports/register-counter! component :test.counter "Test" {})]
        (ports/inc-counter! component handle 5)
        (let [metric (ports/get-metric component :test.counter)]
          (is (= :counter (:type metric)))
          (is (= "Test" (:description metric)))))))

  (testing "Create component map"
    (let [components (datadog/create-datadog-metrics-components test-config)]
      (is (contains? components :metrics-registry))
      (is (contains? components :metrics-emitter))
      (is (contains? components :metrics-exporter))
      (is (contains? components :metrics-config))

      (is (satisfies? ports/IMetricsRegistry (:metrics-registry components)))
      (is (satisfies? ports/IMetricsEmitter (:metrics-emitter components)))
      (is (satisfies? ports/IMetricsExporter (:metrics-exporter components)))
      (is (satisfies? ports/IMetricsConfig (:metrics-config components)))))

  (testing "Invalid configuration validation"
    (let [invalid-config {:provider :datadog-statsd}] ;; Missing required fields
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid Datadog metrics configuration"
                            (datadog/create-datadog-metrics-component invalid-config))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:unit test-error-handling
  (testing "Non-existent metric operations are no-ops"
    (let [{:keys [emitter sent-lines]} (create-test-components)]
      (ports/inc-counter! emitter :non-existent 1)
      (ports/set-gauge! emitter :non-existent 42)
      (ports/observe-histogram! emitter :non-existent 1.0)
      (is (= 0 (count @sent-lines)))))

  (testing "Wrong metric type operations are no-ops"
    (let [{:keys [registry emitter sent-lines]} (create-test-components)
          handle (ports/register-counter! registry :test.counter "Test" {})]
      (ports/set-gauge! emitter handle 42) ;; Wrong operation for counter
      (is (= 0 (count @sent-lines)))))

  (testing "Export non-existent metric throws"
    (let [{:keys [exporter]} (create-test-components)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Metric not found"
                            (ports/export-metric exporter :non-existent :datadog)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Metric not found"
                            (ports/get-metric-values exporter :non-existent)))))

  (testing "Unsupported export format throws"
    (let [{:keys [exporter]} (create-test-components)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported export format"
                            (ports/export-metrics exporter :unsupported))))))