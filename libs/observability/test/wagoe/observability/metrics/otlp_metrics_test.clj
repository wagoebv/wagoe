(ns wagoe.observability.metrics.otlp-metrics-test
  "Exercises the OTLP metrics adapter against an in-memory metric reader (no
   collector needed): asserts counter/gauge/histogram instruments are created
   and that emitted values are collected by OpenTelemetry."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.metrics.ports :as ports]
            [wagoe.observability.metrics.shell.adapters.otlp :as otlp])
  (:import [io.opentelemetry.sdk.testing.exporter InMemoryMetricReader]))

(defn- component []
  (let [reader   (InMemoryMetricReader/create)
        provider (otlp/meter-provider-from-reader reader "test-svc")
        comp     (otlp/component-from-provider provider {:service-name "test-svc"})]
    [comp reader]))

(defn- by-name [reader]
  (into {} (map (fn [m] [(.getName m) m])) (.collectAllMetrics ^InMemoryMetricReader reader)))

(deftest ^:unit otlp-counter-sums-emitted-values
  (let [[c reader] (component)]
    (ports/register-counter! c :http.requests "requests" {:svc "x"})
    (ports/inc-counter! c :http.requests 2 {:route "/a"})
    (ports/inc-counter! c :http.requests 3 {:route "/a"})
    (let [counter (get (by-name reader) "http.requests")
          points  (into [] (.getPoints (.getLongSumData counter)))]
      (is (some? counter))
      (is (= 5 (reduce + (map #(.getValue %) points)))
          "same-tag increments aggregate to one point of value 5"))))

(deftest ^:unit otlp-gauge-records-last-value
  (let [[c reader] (component)]
    (ports/register-gauge! c :queue.depth "depth" {})
    (ports/set-gauge! c :queue.depth 7.0 {})
    (let [gauge  (get (by-name reader) "queue.depth")
          point  (first (.getPoints (.getDoubleGaugeData gauge)))]
      (is (some? gauge))
      (is (= 7.0 (.getValue point))))))

(deftest ^:unit otlp-histogram-records-observations
  (let [[c reader] (component)]
    (ports/register-histogram! c :req.latency "latency" [0.1 0.5 1.0] {})
    (ports/observe-histogram! c :req.latency 0.3 {})
    (ports/observe-histogram! c :req.latency 0.7 {})
    (let [hist  (get (by-name reader) "req.latency")
          point (first (.getPoints (.getHistogramData hist)))]
      (is (some? hist))
      (is (= 2 (.getCount point)))
      (is (< 0.9 (.getSum point) 1.1)))))

(deftest ^:unit otlp-summary-maps-to-histogram
  (let [[c reader] (component)]
    (ports/register-summary! c :op.duration "duration" [0.5 0.95] {})
    (ports/observe-summary! c :op.duration 1.5 {})
    (let [hist (get (by-name reader) "op.duration")]
      (is (some? hist) "summary is emitted as an OTel histogram")
      (is (= 1 (.getCount (first (.getPoints (.getHistogramData hist)))))))))

(deftest ^:unit otlp-registry-and-config-semantics
  (let [[c _] (component)]
    (testing "disable stops a metric being enabled"
      (ports/register-counter! c :c1 "c1" {})
      (is (true? (ports/metric-enabled? c :c1)))
      (ports/disable-metric! c :c1)
      (is (false? (ports/metric-enabled? c :c1))))
    (testing "default tags round-trip"
      (ports/set-default-tags! c {:env "test"})
      (is (= {:env "test"} (ports/get-default-tags c))))
    (testing "local export is unsupported (push-only)"
      (is (thrown? clojure.lang.ExceptionInfo (ports/export-metrics c :prometheus)))
      (is (nil? (ports/get-metric-values c :c1)))
      (is (nil? (ports/flush! c))))))
