(ns wagoe.observability.metrics.shell.adapters.datadog
  "Datadog metrics adapter implementation using DogStatsD protocol.
   
   This adapter integrates with Datadog (https://datadoghq.com) for production metrics
   collection via the DogStatsD UDP protocol. It implements all metrics protocols to
   provide comprehensive metrics capabilities that integrate with Datadog's platform.
   
   Features:
   - DogStatsD UDP protocol implementation
   - Counter, gauge, histogram, and timing metrics
   - Tag merging (global, metric default, call-specific)
   - Sampling support for performance optimization
   - Runtime metric enable/disable for performance tuning
   - In-memory metric registry and value tracking
   - Injectable send function for testability
   
   Configuration:
   The adapter requires a host and service name, optionally accepting:
   - Port (default 8125)
   - Global tags applied to all metrics
   - Sample rate for counters and histograms
   - Maximum packet size (UDP MTU considerations)
   - Origin detection for container environments
   
   Example:
   (def datadog-metrics
     (create-datadog-metrics-components
       {:provider :datadog-statsd
        :host \"localhost\"
        :port 8125
        :service \"my-service\"
        :environment \"production\"
        :global-tags {:team \"backend\" :version \"1.0.0\"}
        :sample-rate 0.1
        :max-packet-size 1432}))"
  (:require
   [wagoe.observability.metrics.ports :as ports]
   [wagoe.observability.metrics.schema :as schema]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [malli.core :as m])
  (:import
   [java.net DatagramSocket DatagramPacket InetAddress]
   [java.nio.charset StandardCharsets]
   [java.util.concurrent ThreadLocalRandom]))

;; =============================================================================
;; DogStatsD Protocol Utilities
;; =============================================================================

(def ^:private datadog-statsd-config-validator (m/validator schema/DatadogStatsdConfig))
(def ^:private datadog-statsd-config-explainer (m/explainer schema/DatadogStatsdConfig))

(def ^:private dogstatsd-type-mapping
  "Mapping from internal metric types to DogStatsD type suffixes."
  {:counter "c"
   :gauge "g"
   :histogram "h"
   :summary "h"}) ; Map summary to histogram for DogStatsD

(defn- format-tags
  "Format tags into DogStatsD tag format: tag1:value1,tag2:value2
   Accepts either:
   - A map of key-value pairs: {:key value}
   - A vector of strings: [\"key:value\"]"
  [tags]
  (when (seq tags)
    (cond
      (map? tags)
      (->> tags
           (map (fn [[k v]] (str (name k) ":" v)))
           (interpose ",")
           (apply str))

      (vector? tags)
      (str/join "," tags)

      :else
      (throw (ex-info "Tags must be either a map or vector of strings"
                      {:tags tags :type (type tags)})))))

(defn- vector-tags->map
  "Convert vector tags format back to map format.
   Converts [\"key:value\" \"key2:value2\"] to {:key \"value\" :key2 \"value2\"}"
  [vector-tags]
  (when (seq vector-tags)
    (reduce
     (fn [acc tag-str]
       (let [[k v] (str/split tag-str #":" 2)]
         (assoc acc (keyword k) v)))
     {}
     vector-tags)))

(defn- build-metric-line
  "Build a DogStatsD metric line: metric.name:value|type|@sample_rate|#tags"
  [metric-name value metric-type tags sample-rate]
  (let [type-suffix (get dogstatsd-type-mapping metric-type "c")
        sample-segment (when (and sample-rate
                                  (#{:counter :histogram :summary} metric-type))
                         (str "|@" sample-rate))
        tag-segment (when tags (str "|#" tags))]
    (str (name metric-name) ":" value "|" type-suffix sample-segment tag-segment)))

(defn- should-sample?
  "Determine if metric should be sampled based on sample rate."
  [sample-rate]
  (or (nil? sample-rate)
      (>= sample-rate 1.0)
      (< (.nextDouble (ThreadLocalRandom/current)) sample-rate)))

(defn- merge-tags
  "Merge tags with precedence: global < metric-default < call-specific
   Global tags can be either a map or vector of strings.
   Metric and call tags are always maps."
  [global-tags metric-tags call-tags]
  (cond
    ;; If global-tags is a vector of strings, return it combined with formatted maps
    (vector? global-tags)
    (let [metric-formatted (when (seq metric-tags)
                             (map (fn [[k v]] (str (name k) ":" v)) metric-tags))
          call-formatted (when (seq call-tags)
                           (map (fn [[k v]] (str (name k) ":" v)) call-tags))]
      (vec (concat global-tags metric-formatted call-formatted)))

    ;; If global-tags is a map (or nil), merge normally
    :else
    (merge global-tags metric-tags call-tags)))

(defn- create-udp-sender
  "Create a UDP sender function for DogStatsD lines."
  [host port max-packet-size]
  (let [socket (atom nil)
        address (InetAddress/getByName host)]
    (fn [line]
      (try
        (let [line-bytes (.getBytes line StandardCharsets/UTF_8)]
          (when (> (count line-bytes) max-packet-size)
            (log/warn "Metric line exceeds max packet size, dropping:"
                      {:line-length (count line-bytes)
                       :max-size max-packet-size
                       :metric-line (subs line 0 (min 100 (count line)))}))
          (when (<= (count line-bytes) max-packet-size)
            (when (nil? @socket)
              (reset! socket (DatagramSocket.)))
            (let [packet (DatagramPacket. line-bytes (count line-bytes) address port)]
              (.send @socket packet))))
        (catch Exception e
          (log/warn e "Failed to send metric line to DogStatsD" {:line line}))))))

;; =============================================================================
;; Metrics Registry Implementation
;; =============================================================================

(defrecord DatadogMetricsRegistry [metrics enabled config]
  ports/IMetricsRegistry

  (register-counter! [_ name description tags]
    (swap! metrics assoc name
           {:type :counter
            :description description
            :tags tags
            :values (atom 0)})
    (swap! enabled conj name)
    name)

  (register-gauge! [_ name description tags]
    (swap! metrics assoc name
           {:type :gauge
            :description description
            :tags tags
            :values (atom nil)})
    (swap! enabled conj name)
    name)

  (register-histogram! [_ name description buckets tags]
    (swap! metrics assoc name
           {:type :histogram
            :description description
            :buckets buckets
            :tags tags
            :values (atom [])})
    (swap! enabled conj name)
    name)

  (register-summary! [_ name description quantiles tags]
    (swap! metrics assoc name
           {:type :summary
            :description description
            :quantiles quantiles
            :tags tags
            :values (atom [])})
    (swap! enabled conj name)
    name)

  (unregister! [_ name]
    (let [existed? (contains? @metrics name)]
      (swap! metrics dissoc name)
      (swap! enabled disj name)
      existed?))

  (list-metrics [_this]
    (->> @metrics
         (map (fn [[name metric]]
                (assoc metric
                       :name name
                       :handle name
                       :enabled (contains? @enabled name))))))

  (get-metric [_ name]
    (when-let [metric (get @metrics name)]
      (assoc metric
             :name name
             :handle name
             :enabled (contains? @enabled name)))))

;; =============================================================================
;; Metrics Emitter Implementation
;; =============================================================================

(defrecord DatadogMetricsEmitter [registry config send-fn]
  ports/IMetricsEmitter

  (inc-counter! [this metric-handle]
    (ports/inc-counter! this metric-handle 1 {}))

  (inc-counter! [this metric-handle value]
    (ports/inc-counter! this metric-handle value {}))

  (inc-counter! [_ metric-handle value tags]
    (let [{:keys [metrics enabled]} registry
          metric (get @metrics metric-handle)
          config-val @config]
      (when (and metric
                 (= :counter (:type metric))
                 (contains? @enabled metric-handle)
                 (should-sample? (:sample-rate config-val)))
        (swap! (:values metric) + value)
        (let [merged-tags (merge-tags (:global-tags config-val)
                                      (:tags metric)
                                      tags)
              formatted-tags (format-tags merged-tags)
              line (build-metric-line metric-handle value :counter
                                      formatted-tags (:sample-rate config-val))]
          (when (:debug? config-val)
            (log/debug "Sending counter metric:" line))
          (send-fn line)))))

  (set-gauge! [this metric-handle value]
    (ports/set-gauge! this metric-handle value {}))

  (set-gauge! [_ metric-handle value tags]
    (let [{:keys [metrics enabled]} registry
          metric (get @metrics metric-handle)
          config-val @config]
      (when (and metric
                 (= :gauge (:type metric))
                 (contains? @enabled metric-handle))
        (reset! (:values metric) value)
        (let [merged-tags (merge-tags (:global-tags config-val)
                                      (:tags metric)
                                      tags)
              formatted-tags (format-tags merged-tags)
              line (build-metric-line metric-handle value :gauge
                                      formatted-tags nil)]
          (when (:debug? config-val)
            (log/debug "Sending gauge metric:" line))
          (send-fn line)))))

  (observe-histogram! [this metric-handle value]
    (ports/observe-histogram! this metric-handle value {}))

  (observe-histogram! [_ metric-handle value tags]
    (let [{:keys [metrics enabled]} registry
          metric (get @metrics metric-handle)
          config-val @config]
      (when (and metric
                 (= :histogram (:type metric))
                 (contains? @enabled metric-handle)
                 (should-sample? (:sample-rate config-val)))
        (swap! (:values metric) conj value)
        (let [merged-tags (merge-tags (:global-tags config-val)
                                      (:tags metric)
                                      tags)
              formatted-tags (format-tags merged-tags)
              line (build-metric-line metric-handle value :histogram
                                      formatted-tags (:sample-rate config-val))]
          (when (:debug? config-val)
            (log/debug "Sending histogram metric:" line))
          (send-fn line)))))

  (observe-summary! [this metric-handle value]
    (ports/observe-summary! this metric-handle value {}))

  (observe-summary! [_ metric-handle value tags]
    (let [{:keys [metrics enabled]} registry
          metric (get @metrics metric-handle)
          config-val @config]
      (when (and metric
                 (= :summary (:type metric))
                 (contains? @enabled metric-handle)
                 (should-sample? (:sample-rate config-val)))
        (swap! (:values metric) conj value)
        (let [merged-tags (merge-tags (:global-tags config-val)
                                      (:tags metric)
                                      tags)
              formatted-tags (format-tags merged-tags)
              line (build-metric-line metric-handle value :summary
                                      formatted-tags (:sample-rate config-val))]
          (when (:debug? config-val)
            (log/debug "Sending summary metric:" line))
          (send-fn line)))))

  (time-histogram! [this metric-handle f]
    (ports/time-histogram! this metric-handle {} f))

  (time-histogram! [this metric-handle tags f]
    (let [start-time (System/nanoTime)
          result (f)
          duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)]
      (ports/observe-histogram! this metric-handle duration-ms tags)
      result))

  (time-summary! [this metric-handle f]
    (ports/time-summary! this metric-handle {} f))

  (time-summary! [this metric-handle tags f]
    (let [start-time (System/nanoTime)
          result (f)
          duration-ms (/ (- (System/nanoTime) start-time) 1000000.0)]
      (ports/observe-summary! this metric-handle duration-ms tags)
      result)))

;; =============================================================================
;; Metrics Exporter Implementation
;; =============================================================================

(defn- calculate-histogram-stats
  "Calculate basic statistics for histogram values."
  [values]
  (if (empty? values)
    {:count 0 :sum 0 :min 0 :max 0 :avg 0}
    (let [count (count values)
          sum (reduce + values)
          min-val (reduce min values)
          max-val (reduce max values)
          avg (/ sum count)]
      {:count count :sum sum :min min-val :max max-val :avg avg})))

(defrecord DatadogMetricsExporter [registry config]
  ports/IMetricsExporter

  (export-metrics [this format]
    (case format
      :datadog
      (let [metrics @(:metrics registry)]
        (->> metrics
             (map (fn [[name metric]]
                    (let [values @(:values metric)
                          base-data {:name name
                                     :type (:type metric)
                                     :description (:description metric)
                                     :tags (:tags metric)
                                     :enabled (contains? @(:enabled registry) name)}]
                      (case (:type metric)
                        :counter (assoc base-data :value values)
                        :gauge (assoc base-data :value values)
                        (:histogram :summary) (assoc base-data
                                                     :values values
                                                     :stats (calculate-histogram-stats values))))))
             (into [])))

      :json
      (let [data (ports/export-metrics this :datadog)]
        (json/generate-string data {:pretty true}))

      (throw (ex-info "Unsupported export format" {:type :validation-error :format format}))))

  (export-metric [this metric-name format]
    (case format
      :datadog
      (if-let [metric (get @(:metrics registry) metric-name)]
        (let [values @(:values metric)
              base-data {:name metric-name
                         :type (:type metric)
                         :description (:description metric)
                         :tags (:tags metric)
                         :enabled (contains? @(:enabled registry) metric-name)}]
          (case (:type metric)
            :counter (assoc base-data :value values)
            :gauge (assoc base-data :value values)
            (:histogram :summary) (assoc base-data
                                         :values values
                                         :stats (calculate-histogram-stats values))))
        (throw (ex-info "Metric not found" {:type :not-found :metric-name metric-name})))

      :json
      (let [data (ports/export-metric this metric-name :datadog)]
        (json/generate-string data {:pretty true}))

      (throw (ex-info "Unsupported export format" {:type :validation-error :format format}))))

  (get-metric-values [_ metric-name]
    (if-let [metric (get @(:metrics registry) metric-name)]
      (let [values @(:values metric)
            timestamp (System/currentTimeMillis)]
        (case (:type metric)
          :counter {:type :counter
                    :value values
                    :tags (:tags metric)
                    :timestamp timestamp}
          :gauge {:type :gauge
                  :value values
                  :tags (:tags metric)
                  :timestamp timestamp}
          (:histogram :summary) {:type (:type metric)
                                 :values values
                                 :stats (calculate-histogram-stats values)
                                 :tags (:tags metric)
                                 :timestamp timestamp}))
      (throw (ex-info "Metric not found" {:type :not-found :metric-name metric-name}))))

  (reset-metrics! [_this]
    (doseq [[_ metric] @(:metrics registry)]
      (case (:type metric)
        :counter (reset! (:values metric) 0)
        :gauge (reset! (:values metric) nil)
        (:histogram :summary) (reset! (:values metric) []))))

  (flush! [_this]
    ;; No-op for UDP DogStatsD - fire and forget
    nil))

;; =============================================================================
;; Metrics Configuration Implementation
;; =============================================================================

(defrecord DatadogMetricsConfig [config registry]
  ports/IMetricsConfig

  (set-default-tags! [_ tags]
    (let [previous (:global-tags @config)]
      (swap! config assoc :global-tags tags)
      (if (vector? previous)
        (vector-tags->map previous)
        previous)))

  (get-default-tags [_this]
    (let [tags (:global-tags @config)]
      (if (vector? tags)
        (vector-tags->map tags)
        tags)))

  (set-export-interval! [_ interval-ms]
    (let [previous (:export-interval @config)]
      (swap! config assoc :export-interval interval-ms)
      previous))

  (get-export-interval [_this]
    (:export-interval @config))

  (enable-metric! [_ metric-name]
    (let [existed? (contains? @(:metrics registry) metric-name)]
      (when existed?
        (swap! (:enabled registry) conj metric-name))
      existed?))

  (disable-metric! [_ metric-name]
    (let [existed? (contains? @(:metrics registry) metric-name)]
      (when existed?
        (swap! (:enabled registry) disj metric-name))
      existed?))

  (metric-enabled? [_ metric-name]
    (contains? @(:enabled registry) metric-name)))

;; =============================================================================
;; Component Creation Functions
;; =============================================================================

(defn create-datadog-metrics-registry
  "Create a Datadog metrics registry instance."
  [config]
  (let [metrics (atom {})
        enabled (atom #{})]
    (->DatadogMetricsRegistry metrics enabled config)))

(defn create-datadog-metrics-emitter
  "Create a Datadog metrics emitter instance."
  [registry config send-fn]
  (let [config-atom (if (instance? clojure.lang.Atom config)
                      config
                      (atom config))]
    (->DatadogMetricsEmitter registry config-atom send-fn)))

(defn create-datadog-metrics-exporter
  "Create a Datadog metrics exporter instance."
  [registry config]
  (->DatadogMetricsExporter registry config))

(defn create-datadog-metrics-config
  "Create a Datadog metrics config instance."
  [config registry]
  (let [config-atom (atom (merge {:host "localhost"
                                  :port 8125
                                  :max-packet-size 1432
                                  :debug? false}
                                 config))]
    (->DatadogMetricsConfig config-atom registry)))

(defrecord DatadogMetricsComponent [config registry emitter exporter metrics-config send-fn]
  ports/IMetricsRegistry
  (register-counter! [_ name description tags]
    (ports/register-counter! registry name description tags))
  (register-gauge! [_ name description tags]
    (ports/register-gauge! registry name description tags))
  (register-histogram! [_ name description buckets tags]
    (ports/register-histogram! registry name description buckets tags))
  (register-summary! [_ name description quantiles tags]
    (ports/register-summary! registry name description quantiles tags))
  (unregister! [_ name]
    (ports/unregister! registry name))
  (list-metrics [_this]
    (ports/list-metrics registry))
  (get-metric [_ name]
    (ports/get-metric registry name))

  ports/IMetricsEmitter
  (inc-counter! [_ metric-handle]
    (ports/inc-counter! emitter metric-handle))
  (inc-counter! [_ metric-handle value]
    (ports/inc-counter! emitter metric-handle value))
  (inc-counter! [_ metric-handle value tags]
    (ports/inc-counter! emitter metric-handle value tags))
  (set-gauge! [_ metric-handle value]
    (ports/set-gauge! emitter metric-handle value))
  (set-gauge! [_ metric-handle value tags]
    (ports/set-gauge! emitter metric-handle value tags))
  (observe-histogram! [_ metric-handle value]
    (ports/observe-histogram! emitter metric-handle value))
  (observe-histogram! [_ metric-handle value tags]
    (ports/observe-histogram! emitter metric-handle value tags))
  (observe-summary! [_ metric-handle value]
    (ports/observe-summary! emitter metric-handle value))
  (observe-summary! [_ metric-handle value tags]
    (ports/observe-summary! emitter metric-handle value tags))
  (time-histogram! [_ metric-handle f]
    (ports/time-histogram! emitter metric-handle f))
  (time-histogram! [_ metric-handle tags f]
    (ports/time-histogram! emitter metric-handle tags f))
  (time-summary! [_ metric-handle f]
    (ports/time-summary! emitter metric-handle f))
  (time-summary! [_ metric-handle tags f]
    (ports/time-summary! emitter metric-handle tags f))

  ports/IMetricsExporter
  (export-metrics [_ format]
    (ports/export-metrics exporter format))
  (export-metric [_ metric-name format]
    (ports/export-metric exporter metric-name format))
  (get-metric-values [_ metric-name]
    (ports/get-metric-values exporter metric-name))
  (reset-metrics! [_this]
    (ports/reset-metrics! exporter))
  (flush! [_this]
    (ports/flush! exporter))

  ports/IMetricsConfig
  (set-default-tags! [_ tags]
    (ports/set-default-tags! metrics-config tags))
  (get-default-tags [_this]
    (ports/get-default-tags metrics-config))
  (set-export-interval! [_ interval-ms]
    (ports/set-export-interval! metrics-config interval-ms))
  (get-export-interval [_this]
    (ports/get-export-interval metrics-config))
  (enable-metric! [_ metric-name]
    (ports/enable-metric! metrics-config metric-name))
  (disable-metric! [_ metric-name]
    (ports/disable-metric! metrics-config metric-name))
  (metric-enabled? [_ metric-name]
    (ports/metric-enabled? metrics-config metric-name)))

(defn create-datadog-metrics-component
  "Create a complete Datadog metrics component that implements all protocols.
   
   This is the recommended way to create Datadog metrics for use with Integrant."
  [config]
  (when-not (datadog-statsd-config-validator config)
    (throw (ex-info "Invalid Datadog metrics configuration"
                    {:type :configuration-error
                     :config config
                     :errors (datadog-statsd-config-explainer config)})))
  (let [host (:host config)
        port (:port config 8125)
        max-packet-size (:max-packet-size config 1432)
        send-fn (create-udp-sender host port max-packet-size)
        config-atom (atom config)
        registry (create-datadog-metrics-registry config-atom)
        emitter (create-datadog-metrics-emitter registry config-atom send-fn)
        exporter (create-datadog-metrics-exporter registry config-atom)
        metrics-config (create-datadog-metrics-config config registry)]
    (->DatadogMetricsComponent config registry emitter exporter metrics-config send-fn)))

(defn create-datadog-metrics-components
  "Create a map of Datadog metrics components for Integrant system configuration.
   
   Returns a map with keys:
   - :metrics-registry - IMetricsRegistry implementation
   - :metrics-emitter - IMetricsEmitter implementation
   - :metrics-exporter - IMetricsExporter implementation
   - :metrics-config - IMetricsConfig implementation"
  [config]
  (when-not (datadog-statsd-config-validator config)
    (throw (ex-info "Invalid Datadog metrics configuration"
                    {:type :configuration-error
                     :config config
                     :errors (datadog-statsd-config-explainer config)})))
  (let [host (:host config)
        port (:port config 8125)
        max-packet-size (:max-packet-size config 1432)
        send-fn (create-udp-sender host port max-packet-size)
        config-atom (atom config)
        registry (create-datadog-metrics-registry config-atom)]
    {:metrics-registry registry
     :metrics-emitter (create-datadog-metrics-emitter registry config-atom send-fn)
     :metrics-exporter (create-datadog-metrics-exporter registry config-atom)
     :metrics-config (create-datadog-metrics-config config registry)}))