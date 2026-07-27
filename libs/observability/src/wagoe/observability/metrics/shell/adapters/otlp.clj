(ns wagoe.observability.metrics.shell.adapters.otlp
  "OpenTelemetry OTLP metrics adapter: bridges the Boundary metrics ports onto
   OpenTelemetry instruments and pushes them over OTLP/HTTP (protobuf) to any
   OTel collector (SigNoz, Grafana, Datadog-via-OTel, …). No backend-specific
   code — only the endpoint changes.

   Metric-type mapping onto OTel instruments:
     counter   -> LongCounter    (.add)
     gauge     -> DoubleGauge    (.set)
     histogram -> DoubleHistogram(.record)
     summary   -> DoubleHistogram(.record)   ; OTel has no summary type

   Export is push-based (a PeriodicMetricReader flushes to the collector), so the
   `IMetricsExporter` local-render methods are not meaningful here: `export-*`
   throw, `flush!` forces an OTLP flush, `get-metric-values`/`reset-metrics!` are
   inert. Transport is OTLP/HTTP protobuf via okhttp (gRPC is not bundled)."
  (:require [wagoe.observability.metrics.ports :as ports]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [io.opentelemetry.api.common Attributes]
           [io.opentelemetry.api.metrics DoubleGauge DoubleHistogram LongCounter Meter]
           [io.opentelemetry.exporter.otlp.http.metrics OtlpHttpMetricExporter]
           [io.opentelemetry.sdk.metrics SdkMeterProvider]
           [io.opentelemetry.sdk.metrics.export MetricReader PeriodicMetricReader]
           [io.opentelemetry.sdk.resources Resource]
           [java.util ArrayList]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- metric-name ^String [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- attr-name ^String [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn ->attributes
  "Convert a Clojure tag map into OpenTelemetry `Attributes`."
  ^Attributes [m]
  (let [b (Attributes/builder)]
    (doseq [[k v] m]
      (let [ks (attr-name k)]
        (cond
          (string? v)  (.put b ks ^String v)
          (integer? v) (.put b ks (long v))
          (float? v)   (.put b ks (double v))
          (boolean? v) (.put b ks (boolean v))
          (nil? v)     nil
          :else        (.put b ks (str v)))))
    (.build b)))

(defn- merge-tags [default-tags metric-tags call-tags]
  (merge default-tags metric-tags call-tags))

;; ---------------------------------------------------------------------------
;; Aggregate component (implements all four metrics protocols)
;; ---------------------------------------------------------------------------

(defrecord OtlpMetricsComponent [^Meter meter registry enabled default-tags ^SdkMeterProvider provider]
  ports/IMetricsRegistry
  (register-counter! [_ name description tags]
    (let [inst (-> (.counterBuilder meter (metric-name name))
                   (.setDescription (or description ""))
                   (.build))]
      (swap! registry assoc name {:type :counter :instrument inst :tags tags})
      (swap! enabled conj name)
      name))

  (register-gauge! [_ name description tags]
    (let [inst (-> (.gaugeBuilder meter (metric-name name))
                   (.setDescription (or description ""))
                   (.build))]
      (swap! registry assoc name {:type :gauge :instrument inst :tags tags})
      (swap! enabled conj name)
      name))

  (register-histogram! [_ name description buckets tags]
    (let [b    (-> (.histogramBuilder meter (metric-name name))
                   (.setDescription (or description "")))
          b    (if (seq buckets)
                 (.setExplicitBucketBoundariesAdvice b (ArrayList. ^java.util.Collection (mapv double buckets)))
                 b)
          inst (.build b)]
      (swap! registry assoc name {:type :histogram :instrument inst :tags tags})
      (swap! enabled conj name)
      name))

  (register-summary! [_ name description _quantiles tags]
    ;; OTel has no summary instrument; map to a histogram.
    (let [inst (-> (.histogramBuilder meter (metric-name name))
                   (.setDescription (or description ""))
                   (.build))]
      (swap! registry assoc name {:type :summary :instrument inst :tags tags})
      (swap! enabled conj name)
      name))

  (unregister! [_ name]
    (let [existed? (contains? @registry name)]
      (swap! registry dissoc name)
      (swap! enabled disj name)
      existed?))

  (list-metrics [_]
    (mapv (fn [[name m]]
            {:name name :handle name :type (:type m)
             :tags (:tags m) :enabled (contains? @enabled name)})
          @registry))

  (get-metric [_ name]
    (when-let [m (get @registry name)]
      {:name name :handle name :type (:type m)
       :tags (:tags m) :enabled (contains? @enabled name)}))

  ports/IMetricsEmitter
  (inc-counter! [this h] (ports/inc-counter! this h 1 {}))
  (inc-counter! [this h value] (ports/inc-counter! this h value {}))
  (inc-counter! [_ h value tags]
    (let [m (get @registry h)]
      (when (and m (= :counter (:type m)) (contains? @enabled h))
        (.add ^LongCounter (:instrument m) (long value)
              (->attributes (merge-tags @default-tags (:tags m) tags)))))
    nil)

  (set-gauge! [this h value] (ports/set-gauge! this h value {}))
  (set-gauge! [_ h value tags]
    (let [m (get @registry h)]
      (when (and m (= :gauge (:type m)) (contains? @enabled h))
        (.set ^DoubleGauge (:instrument m) (double value)
              (->attributes (merge-tags @default-tags (:tags m) tags)))))
    nil)

  (observe-histogram! [this h value] (ports/observe-histogram! this h value {}))
  (observe-histogram! [_ h value tags]
    (let [m (get @registry h)]
      (when (and m (#{:histogram :summary} (:type m)) (contains? @enabled h))
        (.record ^DoubleHistogram (:instrument m) (double value)
                 (->attributes (merge-tags @default-tags (:tags m) tags)))))
    nil)

  (observe-summary! [this h value] (ports/observe-summary! this h value {}))
  (observe-summary! [this h value tags] (ports/observe-histogram! this h value tags))

  (time-histogram! [this h f] (ports/time-histogram! this h {} f))
  (time-histogram! [this h tags f]
    (let [start (System/nanoTime)
          result (f)]
      (ports/observe-histogram! this h (/ (- (System/nanoTime) start) 1e6) tags)
      result))

  (time-summary! [this h f] (ports/time-summary! this h {} f))
  (time-summary! [this h tags f]
    (let [start (System/nanoTime)
          result (f)]
      (ports/observe-histogram! this h (/ (- (System/nanoTime) start) 1e6) tags)
      result))

  ports/IMetricsExporter
  (export-metrics [_ _format]
    (throw (ex-info "OTLP metrics are pushed to the collector; no local export format"
                    {:type :validation-error :provider :otlp})))
  (export-metric [_ _metric-name _format]
    (throw (ex-info "OTLP metrics are pushed to the collector; no local export format"
                    {:type :validation-error :provider :otlp})))
  (get-metric-values [_ _metric-name] nil)
  (reset-metrics! [_] nil)
  (flush! [_]
    (when provider
      (-> (.forceFlush provider) (.join 10 TimeUnit/SECONDS)))
    nil)

  ports/IMetricsConfig
  (set-default-tags! [_ tags]
    (let [prev @default-tags] (reset! default-tags tags) prev))
  (get-default-tags [_] @default-tags)
  ;; Export interval is fixed when the PeriodicMetricReader is built; expose it
  ;; but changing it at runtime is not supported by the OTel reader.
  (set-export-interval! [_ _interval-ms] nil)
  (get-export-interval [_] nil)
  (enable-metric! [_ name]
    (let [existed? (contains? @registry name)]
      (when existed? (swap! enabled conj name))
      existed?))
  (disable-metric! [_ name]
    (let [existed? (contains? @registry name)]
      (when existed? (swap! enabled disj name))
      existed?))
  (metric-enabled? [_ name] (contains? @enabled name)))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn- metrics-endpoint ^String [endpoint]
  (let [base (if (str/ends-with? endpoint "/")
               (subs endpoint 0 (dec (count endpoint)))
               endpoint)]
    (str base "/v1/metrics")))

(defn meter-provider-from-reader
  "Build an `SdkMeterProvider` around an arbitrary `MetricReader`, tagged
   `service.name`. Production uses a PeriodicMetricReader over OTLP; tests inject
   an in-memory reader."
  ^SdkMeterProvider [^MetricReader reader service-name]
  (let [resource (.merge (Resource/getDefault)
                         (Resource/create (-> (Attributes/builder)
                                              (.put "service.name" ^String service-name)
                                              (.build))))]
    (-> (SdkMeterProvider/builder)
        (.registerMetricReader reader)
        (.setResource resource)
        (.build))))

(defn build-meter-provider
  "Build an `SdkMeterProvider` with a periodic OTLP/HTTP metric exporter."
  ^SdkMeterProvider [{:keys [endpoint service-name interval-ms timeout-ms headers]
                      :or   {endpoint     "http://localhost:4318"
                             service-name "wagoe"
                             interval-ms  60000
                             timeout-ms   10000}}]
  (let [eb       (-> (OtlpHttpMetricExporter/builder)
                     (.setEndpoint (metrics-endpoint endpoint))
                     (.setTimeout (long timeout-ms) TimeUnit/MILLISECONDS))
        _        (doseq [[k v] headers] (.addHeader eb (str (name k)) (str v)))
        exporter (.build eb)
        reader   (-> (PeriodicMetricReader/builder exporter)
                     (.setInterval (long interval-ms) TimeUnit/MILLISECONDS)
                     (.build))]
    (meter-provider-from-reader reader service-name)))

(defn component-from-provider
  "Wrap a built `SdkMeterProvider` as an `OtlpMetricsComponent`."
  [^SdkMeterProvider provider {:keys [default-tags] :or {default-tags {}}}]
  (->OtlpMetricsComponent (.get provider "wagoe.observability")
                          (atom {}) (atom #{}) (atom default-tags) provider))

(defn create-metrics-component
  "Build the OTLP metrics component (wiring entry point; mirrors the datadog /
   prometheus adapters)."
  [config]
  (log/info "Building OTLP metrics component"
            {:endpoint (:endpoint config) :service-name (:service-name config)})
  (let [provider (build-meter-provider config)]
    (component-from-provider provider config)))
