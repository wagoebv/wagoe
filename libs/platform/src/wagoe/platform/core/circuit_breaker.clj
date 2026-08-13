(ns wagoe.platform.core.circuit-breaker
  "When to stop calling a service that keeps failing.

   Retries bound the damage of one call; this bounds the damage of many. A
   service that is down still receives every request until each one times out,
   and every caller pays that timeout. Declining to call is the only thing that
   stops both.

   FC/IS: pure. The state lives elsewhere — see `…shell.rpc.breaker`, which
   keeps it in the cache so replicas share one breaker rather than each
   discovering the outage separately."
  (:require [clojure.set :as set]))

(def default-config
  {:failure-threshold 5
   :open-ms           30000
   ;; Only failures where the call did not reach the service. A remote error
   ;; means it answered — it is up, and refusing to call it because its answers
   ;; are unwelcome would be a different feature.
   :trip-on           #{:rpc/unavailable :rpc/timeout}})

(defn ceil-seconds
  "`ms` as whole seconds, rounded up, never less than one.

   Cache TTLs are in seconds and breaker windows are in milliseconds, so the
   conversion has to round the right way: flooring an `:open-ms` of 1500 to one
   second makes the stored state expire before the window it describes has
   elapsed, and the breaker forgets an outage it is still meant to be
   protecting against."
  [ms]
  (max 1 (long (Math/ceil (/ (double ms) 1000.0)))))

(defn counts-as-failure?
  "Whether `error-type` is evidence the service is unreachable.

   `:rpc/timeout` counts here although it is not retried. Retrying a timeout
   risks running a non-idempotent call twice; declining to make a new call
   risks nothing. The two decisions read alike and are not."
  [config error-type]
  (contains? (:trip-on config default-config) error-type))

(defn state
  "`:closed`, `:open` or `:half-open` for `breaker` at `now-ms`.

   `:half-open` is not stored — it is what `:open` becomes once the window has
   elapsed. Deriving it means nothing has to run on a timer to move the
   breaker on."
  [{:keys [opened-at-ms]} config now-ms]
  (cond
    (nil? opened-at-ms) :closed
    (< (- now-ms opened-at-ms) (:open-ms config (:open-ms default-config))) :open
    :else :half-open))

(defn on-failure
  "The breaker after a failed call.

   Trips at the threshold and not before, so one blip does not take a service
   out of service."
  [{:keys [failures]} config now-ms]
  (let [failures  (inc (or failures 0))
        threshold (:failure-threshold config (:failure-threshold default-config))]
    (if (>= failures threshold)
      {:failures failures :opened-at-ms now-ms}
      {:failures failures})))

(defn on-success
  "The breaker after a call that reached the service.

   Reset outright rather than decremented: the count is of *consecutive*
   failures, and one success says the run has ended."
  [_breaker]
  {:failures 0})

(defn error
  "The error a caller gets when the breaker declined to make the call."
  [operation base-url retry-after-ms]
  {:error (cond-> {:type    :rpc/circuit-open
                   :message (str "Circuit open for " base-url
                                 "; not attempting the call")}
            operation      (assoc :operation (keyword (name operation)))
            retry-after-ms (assoc :retry-after-ms retry-after-ms))})

(defn retry-after-ms
  "How long until the breaker will next allow a probe."
  [{:keys [opened-at-ms]} config now-ms]
  (when opened-at-ms
    (max 0 (- (+ opened-at-ms (:open-ms config (:open-ms default-config))) now-ms))))

(defn config-problem
  "Why `config` is unusable, or nil."
  [config]
  (let [{:keys [failure-threshold open-ms trip-on]} (merge default-config config)]
    (cond
      (not (pos-int? failure-threshold))
      "Circuit breaker :failure-threshold must be a positive integer"

      (not (pos-int? open-ms))
      "Circuit breaker :open-ms must be a positive integer"

      (not (and (set? trip-on) (seq trip-on)))
      "Circuit breaker :trip-on must be a non-empty set of error types"

      ;; Tripping on something the client never produces means a breaker that
      ;; can never open — configured, inert, and indistinguishable from working.
      (empty? (set/intersection trip-on #{:rpc/unavailable :rpc/timeout
                                          :rpc/remote-error :rpc/protocol}))
      "Circuit breaker :trip-on names no error type the RPC client produces")))
