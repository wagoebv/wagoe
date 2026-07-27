(ns wagoe.observability.metrics.shell.adapters.no-op
  "No-op metrics adapter that safely ignores all metrics operations.
   
   This adapter implements all metrics protocols but performs no actual metric
   collection, making it safe for feature modules to use metrics protocols even
   when metrics collection is disabled or not configured."
  (:require
   [wagoe.observability.metrics.ports :as ports]))

;; =============================================================================
;; No-Op Metrics Registry Implementation
;; =============================================================================

(defrecord NoOpMetricsRegistry []
  ports/IMetricsRegistry
  (register-counter! [_ _ _ _] ::no-op-handle)
  (register-gauge! [_ _ _ _] ::no-op-handle)
  (register-histogram! [_ _ _ _ _] ::no-op-handle)
  (register-summary! [_ _ _ _ _] ::no-op-handle)
  (unregister! [_ _] false)
  (list-metrics [_] [])
  (get-metric [_ _] nil))

;; =============================================================================
;; No-Op Metrics Emitter Implementation
;; =============================================================================

(defrecord NoOpMetricsEmitter []
  ports/IMetricsEmitter
  (inc-counter! [_ _] nil)
  (inc-counter! [_ _ _] nil)
  (inc-counter! [_ _ _ _] nil)

  (set-gauge! [_ _ _] nil)
  (set-gauge! [_ _ _ _] nil)

  (observe-histogram! [_ _ _] nil)
  (observe-histogram! [_ _ _ _] nil)

  (observe-summary! [_ _ _] nil)
  (observe-summary! [_ _ _ _] nil)

  (time-histogram! [_ _ f] (f))
  (time-histogram! [_ _ _ f] (f))

  (time-summary! [_ _ f] (f))
  (time-summary! [_ _ _ f] (f)))

;; =============================================================================
;; No-Op Metrics Exporter Implementation
;; =============================================================================

(defrecord NoOpMetricsExporter []
  ports/IMetricsExporter
  (export-metrics [_ _] "")
  (export-metric [_ _ _] "")
  (get-metric-values [_ _] {:type :unknown :value 0 :tags {} :timestamp (System/currentTimeMillis)})
  (reset-metrics! [_] nil)
  (flush! [_] nil))

;; =============================================================================
;; No-Op Metrics Config Implementation
;; =============================================================================

(defrecord NoOpMetricsConfig []
  ports/IMetricsConfig
  (set-default-tags! [_ _] {})
  (get-default-tags [_] {})
  (set-export-interval! [_ _] 0)
  (get-export-interval [_] 0)
  (enable-metric! [_ _] false)
  (disable-metric! [_ _] false)
  (metric-enabled? [_ _] false))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-metrics-registry
  "Creates a no-op metrics registry instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IMetricsRegistry instance that ignores all registry operations"
  ([]
   (->NoOpMetricsRegistry))
  ([_config]
   (->NoOpMetricsRegistry)))

(defn create-metrics-emitter
  "Creates a no-op metrics emitter instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IMetricsEmitter instance that ignores all metric emission calls"
  ([]
   (->NoOpMetricsEmitter))
  ([_config]
   (->NoOpMetricsEmitter)))

(defn create-metrics-exporter
  "Creates a no-op metrics exporter instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IMetricsExporter instance that returns empty exports"
  ([]
   (->NoOpMetricsExporter))
  ([_config]
   (->NoOpMetricsExporter)))

(defn create-metrics-config
  "Creates a no-op metrics config instance.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     IMetricsConfig instance that reports disabled state"
  ([]
   (->NoOpMetricsConfig))
  ([_config]
   (->NoOpMetricsConfig)))

;; =============================================================================
;; Combined Component for System Wiring
;; =============================================================================

(defrecord NoOpMetricsComponent []
  ports/IMetricsRegistry
  (register-counter! [_ _ _ _] ::no-op-handle)
  (register-gauge! [_ _ _ _] ::no-op-handle)
  (register-histogram! [_ _ _ _ _] ::no-op-handle)
  (register-summary! [_ _ _ _ _] ::no-op-handle)
  (unregister! [_ _] false)
  (list-metrics [_] [])
  (get-metric [_ _] nil)

  ports/IMetricsEmitter
  (inc-counter! [_ _] nil)
  (inc-counter! [_ _ _] nil)
  (inc-counter! [_ _ _ _] nil)

  (set-gauge! [_ _ _] nil)
  (set-gauge! [_ _ _ _] nil)

  (observe-histogram! [_ _ _] nil)
  (observe-histogram! [_ _ _ _] nil)

  (observe-summary! [_ _ _] nil)
  (observe-summary! [_ _ _ _] nil)

  (time-histogram! [_ _ f] (f))
  (time-histogram! [_ _ _ f] (f))

  (time-summary! [_ _ f] (f))
  (time-summary! [_ _ _ f] (f))

  ports/IMetricsExporter
  (export-metrics [_ _] "")
  (export-metric [_ _ _] "")
  (get-metric-values [_ _] {:type :unknown :value 0 :tags {} :timestamp (System/currentTimeMillis)})
  (reset-metrics! [_] nil)
  (flush! [_] nil)

  ports/IMetricsConfig
  (set-default-tags! [_ _] {})
  (get-default-tags [_] {})
  (set-export-interval! [_ _] 0)
  (get-export-interval [_] 0)
  (enable-metric! [_ _] false)
  (disable-metric! [_ _] false)
  (metric-enabled? [_ _] false))

(defn create-metrics-component
  "Creates a combined no-op metrics component that implements all protocols.
   
   This is the recommended component for system wiring, as it provides
   a single component that application code can use for both registering
   and emitting metrics.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Component implementing IMetricsRegistry, IMetricsEmitter, 
     IMetricsExporter, and IMetricsConfig"
  ([]
   (->NoOpMetricsComponent))
  ([_config]
   (->NoOpMetricsComponent)))

;; =============================================================================
;; Component Integration
;; =============================================================================

(defn create-metrics-components
  "Creates a complete set of no-op metrics components.
   
   This is useful for testing or when metrics collection is completely disabled.
   
   Args:
     config - Configuration map (ignored for no-op implementation)
   
   Returns:
     Map with all metrics component instances:
     {:registry  IMetricsRegistry
      :emitter   IMetricsEmitter
      :exporter  IMetricsExporter
      :config    IMetricsConfig}"
  ([]
   (create-metrics-components {}))
  ([config]
   {:registry (create-metrics-registry config)
    :emitter (create-metrics-emitter config)
    :exporter (create-metrics-exporter config)
    :config (create-metrics-config config)}))