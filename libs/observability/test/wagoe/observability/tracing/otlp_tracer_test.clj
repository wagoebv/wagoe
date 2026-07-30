(ns wagoe.observability.tracing.otlp-tracer-test
  "Exercises the OTLP tracer against an in-memory span exporter (no collector
   needed): asserts spans are actually built + exported, attributes land,
   nested spans link parent→child via W3C context, and exceptions mark the span
   errored and rethrow."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.tracing.ports :as ports]
            [wagoe.observability.tracing.core :refer [with-span]]
            [wagoe.observability.tracing.shell.adapters.otlp :as otlp])
  (:import [io.opentelemetry.sdk.testing.exporter InMemorySpanExporter]
           [io.opentelemetry.sdk.trace.export SimpleSpanProcessor]
           [io.opentelemetry.api.common AttributeKey]
           [io.opentelemetry.api.trace StatusCode]))

(defn- in-memory-tracer
  "Return [tracer exporter] backed by an in-memory span exporter."
  []
  (let [exporter  (InMemorySpanExporter/create)
        processor (SimpleSpanProcessor/create exporter)
        tracer    (otlp/tracer-from-sdk (otlp/sdk-from-processor processor "test-svc"))]
    [tracer exporter]))

(defn- finished [exporter]
  (into [] (.getFinishedSpanItems ^InMemorySpanExporter exporter)))

(defn- by-name [exporter]
  (into {} (map (fn [s] [(.getName s) s])) (finished exporter)))

(deftest ^:unit otlp-tracer-conformant-and-context-has-real-ids
  (let [[t _] (in-memory-tracer)]
    (is (satisfies? ports/ITracer t))
    (testing "a live span yields real 32/16-hex ids"
      (let [sp  (ports/start-span! t "x")
            ctx (ports/span-context t sp)]
        (is (re-matches #"[0-9a-f]{32}" (:trace-id ctx)))
        (is (re-matches #"[0-9a-f]{16}" (:span-id ctx)))
        (ports/end-span! t sp)))
    (testing "nil span is a no-op everywhere"
      (is (= {:trace-id nil :span-id nil} (ports/span-context t nil)))
      (is (nil? (ports/end-span! t nil)))
      (is (nil? (ports/add-event! t nil "e")))
      (is (nil? (ports/set-attributes! t nil {:a 1}))))))

(deftest ^:unit otlp-with-span-exports-named-span-with-attributes
  (let [[t ex] (in-memory-tracer)]
    (is (= :ok (with-span t [sp "work" {:route "/x" :n 3 :ok? true}]
                 (ports/add-event! t sp "checkpoint")
                 :ok)))
    (let [spans (finished ex)
          s     (first spans)
          attrs (.getAttributes s)]
      (is (= 1 (count spans)))
      (is (= "work" (.getName s)))
      (is (= "/x" (.get attrs (AttributeKey/stringKey "route"))))
      (is (= 3 (.get attrs (AttributeKey/longKey "n"))))
      (is (= true (.get attrs (AttributeKey/booleanKey "ok?"))))
      (is (pos? (count (.getEvents s))) "add-event! recorded an event"))))

(deftest ^:unit otlp-nested-spans-link-parent-child
  (let [[t ex] (in-memory-tracer)
        ids    (with-span t [outer "outer"]
                 (let [outer-id (:span-id (ports/span-context t outer))
                       inner-id (with-span t [inner "inner"]
                                  (:span-id (ports/span-context t inner)))]
                   {:outer outer-id :inner inner-id}))
        named  (by-name ex)]
    (is (= 2 (count (finished ex))))
    (testing "inner span's parent is the outer span"
      (let [inner (get named "inner")]
        (is (= (:outer ids)
               (.getSpanId (.getParentSpanContext inner))))))
    (testing "both spans share the same trace"
      (is (= (.getTraceId (get named "outer"))
             (.getTraceId (get named "inner")))))))

(deftest ^:unit otlp-exception-marks-span-errored-and-rethrows
  (let [[t ex] (in-memory-tracer)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (with-span t [_sp "boom"] (throw (ex-info "kaboom" {})))))
    (let [s (first (finished ex))]
      (is (= "boom" (.getName s)))
      (is (= StatusCode/ERROR (.getStatusCode (.getStatus s))))
      (is (pos? (count (.getEvents s))) "record-exception! added an exception event"))))
